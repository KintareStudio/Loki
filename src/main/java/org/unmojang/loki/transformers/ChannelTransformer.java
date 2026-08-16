package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

/**
 * Puts Loki on a Classic game connection, which has no streams to filter.
 * <p>
 * Every other version below 1.7 reaches the network through {@code java.net.Socket} and its two
 * streams, which is where {@link SocketStreamTransformer} sits. Classic does not: the client opens
 * a {@code SocketChannel} and the server accepts through a {@code ServerSocketChannel}, and both
 * ask the socket only for its options. The bytes go past in a {@code ByteBuffer} instead.
 * <p>
 * The channel classes themselves are abstract, so what is patched is the implementation both ends
 * really get — {@code sun.nio.ch.SocketChannelImpl}, and the accept that says which end this is.
 *
 * <h2>Why this is safe on channels that are none of Loki's business</h2>
 * Netty is NIO too, so a modern client's every packet passes through here. The first byte decides:
 * a Classic connection opens with a Player Identification, and anything else stands the filter down
 * for the life of the channel. What it costs everyone else is one comparison per connection.
 */
public class ChannelTransformer extends LokiTransformer {
    private static final String CHANNEL = "sun/nio/ch/SocketChannelImpl";
    private static final String SERVER_CHANNEL = "sun/nio/ch/ServerSocketChannelImpl";
    private static final String ANNOUNCE = "org/unmojang/loki/hooks/LegacyAnnounce";
    private static final String BUFFER = "Ljava/nio/ByteBuffer;";

    protected boolean matches(String className) {
        return CHANNEL.equals(className) || SERVER_CHANNEL.equals(className);
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            boolean write = CHANNEL.equals(className) && "write".equals(mn.name)
                    && ("(" + BUFFER + ")I").equals(mn.desc);
            boolean read = CHANNEL.equals(className) && "read".equals(mn.name)
                    && ("(" + BUFFER + ")I").equals(mn.desc);
            boolean close = CHANNEL.equals(className) && ("implCloseSelectableChannel".equals(mn.name)
                    || "close".equals(mn.name)) && "()V".equals(mn.desc);
            boolean accept = SERVER_CHANNEL.equals(className) && "accept".equals(mn.name)
                    && "()Ljava/nio/channels/SocketChannel;".equals(mn.desc);

            if (!write && !read && !close && !accept) continue;

            if (write) {
                // At the top, because the marker has to be in the buffer before the channel takes
                // it, and the block has to be out in front of whatever it holds.
                InsnList entry = new InsnList();
                entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
                entry.add(new VarInsnNode(Opcodes.ALOAD, 1));
                entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ANNOUNCE, "beforeChannelWrite",
                        "(Ljava/lang/Object;" + BUFFER + ")V", false));
                mn.instructions.insert(entry);
            }

            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                InsnList patch = new InsnList();
                if ((write || read) && insn.getOpcode() == Opcodes.IRETURN) {
                    // The count is already on the stack, so the channel and the buffer go on top of
                    // it and the hook takes them in that order.
                    patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ANNOUNCE,
                            write ? "afterChannelWrite" : "afterChannelRead",
                            "(ILjava/lang/Object;" + BUFFER + ")I", false));
                } else if (accept && insn.getOpcode() == Opcodes.ARETURN) {
                    patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ANNOUNCE, "acceptedChannel",
                            "(Ljava/lang/Object;)Ljava/lang/Object;", false));
                    patch.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST,
                            "java/nio/channels/SocketChannel"));
                } else {
                    continue;
                }
                mn.instructions.insertBefore(insn, patch);
                changed = true;
            }

            if (close) {
                // Nothing below 1.7 tells the game a visit is over, so the end of the connection
                // stands in for it here as it does on a socket.
                InsnList entry = new InsnList();
                entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
                entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ANNOUNCE, "closingChannel",
                        "(Ljava/lang/Object;)V", false));
                mn.instructions.insert(entry);
                changed = true;
            }

            if (write) changed = true;
            if (changed) Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
        }

        return changed;
    }
}
