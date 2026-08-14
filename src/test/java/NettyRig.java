import org.unmojang.loki.hooks.ProfileAdvertiser;
import org.unmojang.loki.transformers.NettyBindTransformer;
import org.unmojang.loki.util.NettyBridge;
import org.unmojang.loki.util.Protocol;
import org.unmojang.loki.util.ServerListPing;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loki's server side against a real Netty, taken out of a real Minecraft jar, with no Minecraft.
 * <p>
 * A whole server takes a minute to start and, when something is wrong at this level, says only that
 * a ping timed out. This binds a listener with the same Netty the game would use, patched the same
 * way, and answers a status request with a canned document, so the only thing under test is Loki's
 * handling of the pipeline. Seconds instead of minutes, and a stack trace instead of a silence.
 * <p>
 * It exists because that difference mattered once: Netty 4.0 declares {@code read()} on a package
 * private interface, the bridge could not find it, and every connection to a 1.7 server was accepted
 * and then never served. Five full server runs said "timed out"; this said which method and why.
 *
 * <h2>Exit codes</h2>
 * 0 when the declaration went in and the document survived, 1 when it did not, and 3 when this jar
 * cannot be set up at all — no Netty in it, or a Netty too different to drive. A rig that cannot run
 * is not a fault in Loki, and should not be reported as one.
 *
 * @param args the Minecraft jar to take Netty from, then a port to bind
 */
public class NettyRig {
    private static final String STATUS =
            "{\"description\":\"rig\",\"players\":{\"max\":1,\"online\":0},"
                    + "\"version\":{\"name\":\"rig\",\"protocol\":47}}";
    private static final String API_ROOT = "http://127.0.0.1:1/authlib-injector";
    private static final String ANCHOR = "io/netty/bootstrap/AbstractBootstrap.class";

    private static int failures = 0;

    private static void check(String label, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + label);
    }

    /** Loads Netty from the game's own jars, patching Loki's target on the way in. */
    static final class NettyLoader extends URLClassLoader {
        NettyLoader(URL[] netty) {
            super(netty, NettyRig.class.getClassLoader());
        }

        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("io.netty.")) return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    String path = name.replace('.', '/') + ".class";
                    byte[] bytes = read(findResource(path).openStream());
                    byte[] patched = new NettyBindTransformer().transform(this, name.replace('.', '/'),
                            null, null, bytes);
                    byte[] use = patched != null ? patched : bytes;
                    if (patched != null) System.out.println("  patched " + name);
                    loaded = defineClass(name, use, 0, use.length);
                } catch (Exception e) {
                    return super.loadClass(name, resolve);
                }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        in.close();
        return out.toByteArray();
    }

    /**
     * Where this version keeps Netty. Up to 1.17 it is unpacked in the server jar itself; from 1.18
     * the jar is a bundler and the real libraries sit inside it, so those get unpacked to be used.
     */
    static URL[] nettyOf(File jarFile) throws Exception {
        ZipFile jar = new ZipFile(jarFile);
        try {
            if (jar.getEntry(ANCHOR) != null) return new URL[]{jarFile.toURI().toURL()};

            File unpacked = new File(jarFile.getParentFile(), "netty");
            if (!unpacked.isDirectory() && !unpacked.mkdirs()) return new URL[0];

            List<URL> urls = new ArrayList<URL>();
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("META-INF/libraries/io/netty/") || !name.endsWith(".jar")) continue;

                File out = new File(unpacked, name.substring(name.lastIndexOf('/') + 1));
                if (!out.isFile()) {
                    OutputStream to = new FileOutputStream(out);
                    try {
                        to.write(read(jar.getInputStream(entry)));
                    } finally {
                        to.close();
                    }
                }
                urls.add(out.toURI().toURL());
            }
            return urls.toArray(new URL[0]);
        } finally {
            jar.close();
        }
    }

    public static void main(String[] args) throws Exception {
        File jarFile = new File(args[0]);
        int port = Integer.parseInt(args[1]);
        System.setProperty(ProfileAdvertiser.PROP_API_ROOT, API_ROOT);

        URL[] netty = nettyOf(jarFile);
        if (netty.length == 0) {
            System.out.println("  skipped: no Netty found in " + jarFile.getName());
            System.exit(3);
        }

        NettyLoader loader = new NettyLoader(netty);
        Object bootstrap;
        Object responder;
        Class<?> serverBootstrap;
        try {
            serverBootstrap = loader.loadClass("io.netty.bootstrap.ServerBootstrap");
            Class<?> group = loader.loadClass("io.netty.channel.EventLoopGroup");
            Class<?> channelHandler = loader.loadClass("io.netty.channel.ChannelHandler");
            Object boss = loader.loadClass("io.netty.channel.nio.NioEventLoopGroup")
                    .getConstructor().newInstance();

            bootstrap = serverBootstrap.getConstructor().newInstance();
            serverBootstrap.getMethod("group", group).invoke(bootstrap, boss);
            serverBootstrap.getMethod("channel", Class.class).invoke(bootstrap,
                    loader.loadClass("io.netty.channel.socket.nio.NioServerSocketChannel"));

            // Answers any inbound frame with a status response, which is all the rig has to be
            responder = NettyBridge.newHandler(loader.loadClass("io.netty.channel.ChannelPipeline"),
                    new String[]{"ChannelInboundHandler"},
                    new NettyBridge.PeekAdapter() {
                        public Object inbound(Object ctx, Object msg) {
                            try {
                                Object buf = NettyBridge.wrapBytes(msg, Protocol.statusResponse(STATUS));
                                NettyBridge.call(ctx, "writeAndFlush", new Object[]{buf});
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            return msg;
                        }
                    });
            serverBootstrap.getMethod("childHandler", channelHandler).invoke(bootstrap, responder);
        } catch (Throwable t) {
            System.out.println("  skipped: cannot drive this Netty (" + t + ")");
            System.exit(3);
            return;
        }

        Object future = serverBootstrap.getMethod("bind", int.class)
                .invoke(bootstrap, Integer.valueOf(port));
        future.getClass().getMethod("sync").invoke(future);

        String json;
        try {
            json = ServerListPing.statusJson("127.0.0.1", port, 5000);
        } catch (Exception e) {
            check("the rig answered a ping (" + e + ")", false);
            System.exit(1);
            return;
        }

        check("declared " + API_ROOT, json.contains("\"profileApi\":\"" + API_ROOT + "\""));
        check("left the rest of the document alone",
                json.contains("\"version\"") && json.contains("\"players\""));
        if (failures != 0) System.out.println("  got: " + json);
        System.exit(failures == 0 ? 0 : 1);
    }
}
