package org.unmojang.loki.util;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds Netty handlers without compiling against Netty.
 * <p>
 * Loki's build has no Netty on it and could not use one if it did: 1.7.x relocates the whole
 * library to {@code net.minecraft.util.io.netty}, so the only package name that is ever right is
 * the one read back off the objects the game hands us. Everything here is therefore a dynamic proxy
 * over interfaces resolved from the game's own classloader, driven by reflection.
 * <p>
 * The dispatch rule is Netty's own naming, which is why this stays as small as it is: an inbound
 * event {@code channelRead} is passed on with {@code ctx.fireChannelRead}, and an outbound
 * operation {@code write} with {@code ctx.write}. A {@link Peek} only has to say what it wants done
 * with the message; forwarding the other dozen methods is not its problem.
 */
public final class NettyBridge {
    /** Inbound events, which a context re-fires under a {@code fire}-prefixed name. */
    private static final Set<String> FIRED = new HashSet<String>(Arrays.asList(
            "channelRegistered", "channelUnregistered", "channelActive", "channelInactive",
            "channelRead", "channelReadComplete", "channelWritabilityChanged",
            "userEventTriggered", "exceptionCaught"));

    /** Lifecycle callbacks that stop with us; a context has nothing matching to forward to. */
    private static final Set<String> SWALLOWED = new HashSet<String>(Arrays.asList(
            "handlerAdded", "handlerRemoved"));

    private static final Map<Class<?>, Class<?>> BOXED = new HashMap<Class<?>, Class<?>>();

    static {
        BOXED.put(boolean.class, Boolean.class);
        BOXED.put(byte.class, Byte.class);
        BOXED.put(char.class, Character.class);
        BOXED.put(short.class, Short.class);
        BOXED.put(int.class, Integer.class);
        BOXED.put(long.class, Long.class);
        BOXED.put(float.class, Float.class);
        BOXED.put(double.class, Double.class);
    }

    private NettyBridge() {}

    /** What a handler wants to do with the messages passing through it. */
    public interface Peek {
        /**
         * @param msg the outgoing message, usually a {@code ByteBuf}
         * @return the message to write on, normally {@code msg} itself
         */
        Object outbound(Object ctx, Object msg);

        /**
         * @param msg the incoming message, usually a {@code ByteBuf}
         * @return the message to pass on, normally {@code msg} itself
         */
        Object inbound(Object ctx, Object msg);
    }

    /** A {@link Peek} that lets everything through, for handlers that only care about one side. */
    public static abstract class PeekAdapter implements Peek {
        public Object outbound(Object ctx, Object msg) {
            return msg;
        }

        public Object inbound(Object ctx, Object msg) {
            return msg;
        }
    }

    /**
     * @param sample        any object from Netty's {@code channel} package, used to find where that
     *                      package lives in this installation
     * @param simpleNames   handler interfaces to implement, e.g. {@code ChannelOutboundHandler}
     */
    public static Object newHandler(Object sample, String[] simpleNames, Peek peek) throws Exception {
        // A class is as good an anchor as an instance of one, and is sometimes all a caller has:
        // a listener has no channel to point at until it has accepted something.
        Class<?> sampleType = sample instanceof Class ? (Class<?>) sample : sample.getClass();
        Class<?> anchor = interfaceNamed(sampleType, "ChannelPipeline");
        if (anchor == null) anchor = interfaceNamed(sampleType, "ChannelHandlerContext");
        if (anchor == null) throw new IllegalArgumentException("Not a Netty channel object: " + sample.getClass());

        String prefix = anchor.getName().substring(0, anchor.getName().lastIndexOf('.') + 1);
        ClassLoader loader = anchor.getClassLoader();
        Class<?>[] interfaces = new Class<?>[simpleNames.length];
        for (int i = 0; i < simpleNames.length; i++) {
            interfaces[i] = Class.forName(prefix + simpleNames[i], false, loader);
        }
        return Proxy.newProxyInstance(loader, interfaces, new Dispatcher(peek));
    }

    /**
     * Copies what a {@code ByteBuf} currently holds, up to {@code max} bytes.
     * <p>
     * Absolute reads only: the buffer belongs to whoever is sending it, and moving its reader index
     * would corrupt the very packet Loki is trying to stay out of the way of.
     *
     * @return null if this is not a readable {@code ByteBuf}, which is how a caller finds out that
     *         it is looking at something other than a framed packet
     */
    public static byte[] peekBytes(Object byteBuf, int max) {
        if (byteBuf == null) return null;
        try {
            Object readable = call(byteBuf, "readableBytes", new Object[0]);
            if (!(readable instanceof Integer)) return null;
            int available = ((Integer) readable).intValue();
            if (available <= 0) return null;

            byte[] copy = new byte[Math.min(available, max)];
            Object at = call(byteBuf, "readerIndex", new Object[0]);
            call(byteBuf, "getBytes", new Object[]{at, copy});
            return copy;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Builds a {@code ByteBuf} holding the given bytes, in whichever Netty the game is using.
     *
     * @param sample any {@code ByteBuf}, used only to find where Netty's buffer package lives
     */
    public static Object wrapBytes(Object sample, byte[] data) throws Exception {
        String bufferClass = sample.getClass().getName();
        int packageEnd = bufferClass.lastIndexOf('.');
        Class<?> unpooled = Class.forName(bufferClass.substring(0, packageEnd + 1) + "Unpooled",
                false, sample.getClass().getClassLoader());
        return unpooled.getMethod("wrappedBuffer", byte[].class).invoke(null, (Object) data);
    }

    /**
     * Runs something once a channel closes, without keeping a handler in its pipeline.
     * <p>
     * A handler would see every packet on a live connection for the sake of one event at the end of
     * it, which is a cost this has no business adding. Netty already offers the event on its own:
     * the close future completes exactly once, when the channel does.
     * <p>
     * The listener interface is taken off {@code addListener}'s own signature rather than named.
     * It lives in a different package from the channel types, and 1.7.x relocates the whole tree,
     * so the method that accepts it is the one thing that always knows where it is.
     *
     * @param channel the channel to watch
     * @param action  what to run when it closes, on whichever thread Netty completes the future on
     */
    public static void onClose(Object channel, final Runnable action) throws Exception {
        Object closeFuture = call(channel, "closeFuture", new Object[0]);
        Method addListener = resolve(closeFuture.getClass(), "addListener", new Object[]{null});
        if (addListener == null) throw new NoSuchMethodException("closeFuture().addListener");

        Class<?> listenerType = addListener.getParameterTypes()[0];
        Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(),
                new Class<?>[]{listenerType}, new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (args == null || args.length != 1) {
                            if ("toString".equals(method.getName())) return "Loki";
                            if ("hashCode".equals(method.getName())) {
                                return Integer.valueOf(System.identityHashCode(proxy));
                            }
                            return null;
                        }
                        if ("equals".equals(method.getName())) return Boolean.valueOf(proxy == args[0]);
                        action.run();
                        return null;
                    }
                });
        call(closeFuture, "addListener", new Object[]{listener});
    }

    /** Drops a reference to a message Loki is replacing, so its buffer goes back to the pool. */
    public static void release(Object message) {
        try {
            call(message, "release", new Object[0]);
        } catch (Throwable ignored) {
            // Not reference counted, or already released
        }
    }

    /** Takes a handler out of the pipeline it is running in, from inside one of its own callbacks. */
    public static void removeSelf(Object ctx) {
        try {
            call(call(ctx, "pipeline", new Object[0]), "remove", new Object[]{call(ctx, "handler", new Object[0])});
        } catch (Throwable ignored) {
            // Already gone, or the channel is closing. Either way there is nothing left to remove.
        }
    }

    /**
     * Invokes a method by name, picking the overload whose parameters fit the arguments.
     * <p>
     * Name and arity alone are not enough on Netty's interfaces: {@code ChannelPipeline.remove}
     * takes a handler, a name or a class, and only one of those is what a caller means.
     */
    public static Object call(Object target, String name, Object[] args) throws Exception {
        Method method = resolve(target.getClass(), name, args);
        if (method == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "." + name + "/" + args.length);
        }
        if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
                // Nothing else to try; the invoke below will say so
            }
        }
        return method.invoke(target, args);
    }

    /**
     * Finds the method to invoke, preferring one declared somewhere public.
     * <p>
     * A public declaration is worth preferring because it can simply be invoked. Netty 4.0 puts
     * {@code read()} and its neighbours on a package private {@code ChannelOutboundInvoker},
     * though, and refusing to look there loses the method altogether: the call then throws, the
     * read never reaches the socket, and the connection silently stops being served. So look
     * everywhere, and let {@link #call} open up whatever it finds.
     */
    private static Method resolve(Class<?> type, String name, Object[] args) {
        Method method = resolve(type, name, args, true);
        return method != null ? method : resolve(type, name, args, false);
    }

    private static Method resolve(Class<?> type, String name, Object[] args, boolean publicTypesOnly) {
        List<Method> candidates = new ArrayList<Method>();
        collect(type, name, args.length, candidates, publicTypesOnly);
        Method fallback = null;
        for (int i = 0; i < candidates.size(); i++) {
            Method candidate = candidates.get(i);
            if (fallback == null) fallback = candidate;
            if (fits(candidate.getParameterTypes(), args)) return candidate;
        }
        return fallback;
    }

    /** Collects matching methods from the hierarchy, optionally only where the type is public. */
    private static void collect(Class<?> type, String name, int arity, List<Method> into,
                                boolean publicTypesOnly) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (!publicTypesOnly || Modifier.isPublic(current.getModifiers())) {
                addDeclared(current, name, arity, into);
            }
            Class<?>[] interfaces = current.getInterfaces();
            for (int i = 0; i < interfaces.length; i++) {
                collect(interfaces[i], name, arity, into, publicTypesOnly);
            }
        }
    }

    private static void addDeclared(Class<?> type, String name, int arity, List<Method> into) {
        Method[] declared = type.getDeclaredMethods();
        for (int i = 0; i < declared.length; i++) {
            Method method = declared[i];
            if (!method.getName().equals(name)) continue;
            if (method.getParameterTypes().length != arity) continue;
            if (!Modifier.isPublic(method.getModifiers())) continue;
            into.add(method);
        }
    }

    private static boolean fits(Class<?>[] parameters, Object[] args) {
        for (int i = 0; i < parameters.length; i++) {
            if (args[i] == null) {
                if (parameters[i].isPrimitive()) return false;
                continue;
            }
            Class<?> parameter = parameters[i].isPrimitive() ? BOXED.get(parameters[i]) : parameters[i];
            if (!parameter.isInstance(args[i])) return false;
        }
        return true;
    }

    private static Class<?> interfaceNamed(Class<?> type, String simpleName) {
        if (type != null && type.getName().endsWith("." + simpleName)) return type;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Class<?>[] interfaces = current.getInterfaces();
            for (int i = 0; i < interfaces.length; i++) {
                if (interfaces[i].getName().endsWith("." + simpleName)) return interfaces[i];
                Class<?> nested = interfaceNamed(interfaces[i], simpleName);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static final class Dispatcher implements InvocationHandler {
        private final Peek peek;

        Dispatcher(Peek peek) {
            this.peek = peek;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (args == null || args.length == 0) {
                if ("toString".equals(name)) return "Loki";
                if ("hashCode".equals(name)) return Integer.valueOf(System.identityHashCode(proxy));
                return null;
            }
            if ("equals".equals(name) && args.length == 1) {
                return Boolean.valueOf(proxy == args[0]);
            }
            if (SWALLOWED.contains(name)) return null;

            // Every Netty handler callback takes its context first, and the rest is what a context
            // wants to be handed back.
            Object ctx = args[0];
            Object[] rest = new Object[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);

            if (rest.length != 0) {
                if ("write".equals(name)) rest[0] = peek.outbound(ctx, rest[0]);
                else if ("channelRead".equals(name)) rest[0] = peek.inbound(ctx, rest[0]);
            }

            String target = FIRED.contains(name)
                    ? "fire" + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                    : name;
            try {
                Object result = call(ctx, target, rest);
                return method.getReturnType() == void.class ? null : result;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
