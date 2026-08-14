package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
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

            InsnList insns = new InsnList();
            insns.add(new VarInsnNode(Opcodes.ALOAD, 1)); // remoteAddress
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "org/unmojang/loki/hooks/ProfileRedirect",
                    "noteConnect",
                    "(Ljava/lang/Object;)V",
                    false));
            mn.instructions.insert(insns);

            Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
            changed = true;
        }

        return changed;
    }
}
