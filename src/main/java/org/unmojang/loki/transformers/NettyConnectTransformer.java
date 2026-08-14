package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

/**
 * Tells {@code ProfileRedirect} which server the game is dialling, before the handshake is written.
 * <p>
 * Netty's {@code Bootstrap} is the right place to hook. Every 1.7+ client routes both server list
 * pings and joins through it, the JDK-level alternatives are not reachable from here (Loki's hooks
 * are only appended to the bootstrap classloader on Java 9+), and unlike hooking sockets or channels
 * it is independent of whether Netty picked the NIO, epoll or kqueue transport.
 * <p>
 * The string and {@code InetAddress} overloads of {@code connect} delegate to the
 * {@code SocketAddress} ones, so those two are all that need patching.
 * <p>
 * The call goes on the way out rather than on the way in, because the returned future is how Loki
 * reaches the channel, and the channel is where the handshake that says whether this is a join or a
 * ping can be read. The returned value itself is left exactly as it was.
 */
public class NettyConnectTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        // 1.7.x relocates Netty to net.minecraft.util.io.netty, hence the loose match
        return !Loki.disable_profile_redirect
                && className.endsWith("/bootstrap/Bootstrap")
                && className.contains("netty");
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if (!"connect".equals(mn.name)) continue;
            if (!mn.desc.startsWith("(Ljava/net/SocketAddress;)")
                    && !mn.desc.startsWith("(Ljava/net/SocketAddress;Ljava/net/SocketAddress;)")) continue;

            boolean patched = false;
            AbstractInsnNode[] instructions = mn.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                if (instructions[i].getOpcode() != Opcodes.ARETURN) continue;

                InsnList insns = new InsnList();
                insns.add(new InsnNode(Opcodes.DUP));         // the future being returned
                insns.add(new VarInsnNode(Opcodes.ALOAD, 1)); // remoteAddress
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "org/unmojang/loki/hooks/ProfileRedirect",
                        "noteConnect",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V",
                        false));
                mn.instructions.insertBefore(instructions[i], insns);
                patched = true;
            }

            if (!patched) continue;
            Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
            changed = true;
        }

        return changed;
    }
}
