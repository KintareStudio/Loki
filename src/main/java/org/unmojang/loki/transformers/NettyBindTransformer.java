package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

/**
 * Hands {@code ProfileAdvertiser} the listener a server just opened.
 * <p>
 * The mirror of {@link NettyConnectTransformer}: where a client's {@code connect} gives Loki the
 * connection it is making, a server's {@code bind} gives it the one it is accepting on. From the
 * returned future comes the server channel, and a server channel reads accepted connections the way
 * any other reads bytes, which is how each new connection is reached before it has said anything.
 * <p>
 * {@code bind} is declared on {@code AbstractBootstrap} rather than on {@code ServerBootstrap}, so
 * that is what gets patched, and a client bootstrap that binds is patched along with it. That is
 * harmless: the advertiser only ever acts on an inbound message that turns out to be a channel, and
 * a client channel never reads one. The overloads delegate to each other, so the hook can fire more
 * than once for the same listener; the advertiser refuses to install itself twice.
 */
public class NettyBindTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        // 1.7.x relocates Netty to net.minecraft.util.io.netty, hence the loose match
        return !Loki.disable_profile_advertise
                && className.endsWith("/bootstrap/AbstractBootstrap")
                && className.contains("netty");
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if (!"bind".equals(mn.name) || !mn.desc.endsWith(")Lio/netty/channel/ChannelFuture;")
                    && !mn.desc.endsWith("/ChannelFuture;")) continue;

            boolean patched = false;
            AbstractInsnNode[] instructions = mn.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                if (instructions[i].getOpcode() != Opcodes.ARETURN) continue;

                InsnList insns = new InsnList();
                insns.add(new InsnNode(Opcodes.DUP)); // the future being returned
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "org/unmojang/loki/hooks/ProfileAdvertiser",
                        "noteBind",
                        "(Ljava/lang/Object;)V",
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
