package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Publish the legacy RCON running flag before its worker can read it. */
public final class LegacyRconThreadTransformer extends LokiTransformer {
    protected boolean matches(String name) {
        return name.indexOf('/') < 0 || name.startsWith("net/minecraft/");
    }

    protected int writerFlags(String name) {
        return ClassWriter.COMPUTE_MAXS;
    }

    private static AbstractInsnNode next(AbstractInsnNode node) {
        do { node = node.getNext(); } while (node != null && node.getOpcode() < 0);
        return node;
    }

    protected boolean patch(ClassNode cn, String name) {
        if (!cn.interfaces.contains("java/lang/Runnable")) return false;
        boolean register = false, close = false;
        for (MethodNode method : cn.methods) {
            for (AbstractInsnNode node : method.instructions.toArray()) {
                if (!(node instanceof LdcInsnNode)) continue;
                Object constant = ((LdcInsnNode) node).cst;
                Object value = constant instanceof String ? ((String) constant).trim() : constant;
                register |= "registerSocket:".equals(value);
                close |= "closeSocket:".equals(value);
            }
        }
        if (!register || !close) return false;
        for (MethodNode method : cn.methods) {
            if (!"()V".equals(method.desc) || (method.access & Opcodes.ACC_SYNCHRONIZED) == 0)
                continue;
            for (AbstractInsnNode node : method.instructions.toArray()) {
                if (!(node instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) node;
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL ||
                        !"java/lang/Thread".equals(call.owner) || !"start".equals(call.name) ||
                        !"()V".equals(call.desc)) continue;
                AbstractInsnNode self = next(call), one = self == null ? null : next(self);
                AbstractInsnNode store = one == null ? null : next(one);
                if (!(self instanceof VarInsnNode) || self.getOpcode() != Opcodes.ALOAD ||
                        ((VarInsnNode) self).var != 0 || one == null ||
                        one.getOpcode() != Opcodes.ICONST_1 || !(store instanceof FieldInsnNode))
                    continue;
                FieldInsnNode flag = (FieldInsnNode) store;
                if (flag.getOpcode() != Opcodes.PUTFIELD || !cn.name.equals(flag.owner) ||
                        !"Z".equals(flag.desc)) continue;
                FieldNode field = null;
                for (FieldNode candidate : cn.fields)
                    if (candidate.name.equals(flag.name) && candidate.desc.equals(flag.desc))
                        field = candidate;
                if (field == null || (field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) != 0)
                    continue;
                // The already-loaded Thread stays below these three operands on the stack.
                method.instructions.remove(self);
                method.instructions.remove(one);
                method.instructions.remove(store);
                InsnList assignment = new InsnList();
                assignment.add(self); assignment.add(one); assignment.add(store);
                method.instructions.insertBefore(call, assignment);
                field.access |= Opcodes.ACC_VOLATILE;
                return true;
            }
        }
        return false;
    }
}
