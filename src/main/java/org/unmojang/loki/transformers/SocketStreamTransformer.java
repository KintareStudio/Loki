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

public class SocketStreamTransformer extends LokiTransformer {
    private static final String SOCKET = "java/net/Socket";
    private static final String SERVER_SOCKET = "java/net/ServerSocket";
    private static final String ANNOUNCE = "org/unmojang/loki/hooks/LegacyAnnounce";

    protected boolean matches(String className) {
        return SOCKET.equals(className) || SERVER_SOCKET.equals(className);
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            String hook = null;
            String descriptor = null;

            if (SOCKET.equals(className) && "getInputStream".equals(mn.name)
                    && "()Ljava/io/InputStream;".equals(mn.desc)) {
                hook = "wrapInput";
                descriptor = "(Ljava/io/InputStream;Ljava/net/Socket;)Ljava/io/InputStream;";
            } else if (SOCKET.equals(className) && "getOutputStream".equals(mn.name)
                    && "()Ljava/io/OutputStream;".equals(mn.desc)) {
                hook = "wrapOutput";
                descriptor = "(Ljava/io/OutputStream;Ljava/net/Socket;)Ljava/io/OutputStream;";
            } else if (SERVER_SOCKET.equals(className) && "accept".equals(mn.name)
                    && "()Ljava/net/Socket;".equals(mn.desc)) {
                hook = "accepted";
                descriptor = "(Ljava/lang/Object;)Ljava/lang/Object;";
            } else if (SOCKET.equals(className) && "close".equals(mn.name)
                    && "()V".equals(mn.desc)) {
                InsnList patch = new InsnList();
                patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
                patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ANNOUNCE, "closing",
                        "(Ljava/net/Socket;)V", false));
                mn.instructions.insert(patch);
                changed = true;
                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                continue;
            }
            if (hook == null) continue;

            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.ARETURN) continue;

                InsnList patch = new InsnList();
                if (!"accepted".equals(hook)) patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
                patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ANNOUNCE, hook, descriptor, false));
                if ("accepted".equals(hook)) {
                    patch.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, SOCKET));
                }
                mn.instructions.insertBefore(insn, patch);
                changed = true;
            }

            if (changed) Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
        }

        return changed;
    }
}
