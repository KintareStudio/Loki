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
 * Puts Loki on a pre-1.7 game connection, which has no Netty to hook.
 * <p>
 * From 1.7 the client and the server both go through Netty, and Loki hooks the two Bootstrap
 * methods. Before that they use plain sockets, so the only thing common to every version is
 * {@code java.net.Socket} itself: its two stream accessors are where the announcement is written,
 * read and taken back off.
 * <p>
 * {@code ServerSocket.accept} is hooked as well, and only to answer one question — whether this end
 * is the server on that socket. Nothing about the JVM says whether it is running a game or serving
 * one, but a socket that arrived through accept can only be the latter.
 *
 * <h2>Why this is safe on sockets that are none of Loki's business</h2>
 * Every socket in the process passes through here, HTTP included. The filters decide on the first
 * byte whether they are looking at the protocol they know and step aside for good if not, so what a
 * request to an API server costs is one comparison on its first read and one on its first write.
 */
public class SocketStreamTransformer extends LokiTransformer {
    private static final String SOCKET = "java/net/Socket";
    private static final String SERVER_SOCKET = "java/net/ServerSocket";
    private static final String ANNOUNCE = "org/unmojang/loki/legacy/LegacyAnnounce";

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
            }
            if (hook == null) continue;

            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.ARETURN) continue;

                // The value being returned is on the stack. Hand it to Loki along with the socket
                // it belongs to, and return whatever comes back.
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
