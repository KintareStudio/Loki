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
