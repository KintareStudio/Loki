package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public class RealmsURLTransformer extends LokiTransformer {
    protected boolean matches(String name) {
        return !name.startsWith("org/unmojang/loki/");
    }

    protected int writerFlags(String name) {
        return ClassWriter.COMPUTE_MAXS;
    }

    protected boolean patch(ClassNode cn, String name) {
        boolean realms = false;
        for (MethodNode method : cn.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LdcInsnNode
                        && "mco/client/compatible".equals(((LdcInsnNode) instruction).cst)) realms = true;
            }
        }
        if (!realms) return false;
        boolean changed = false;
        for (MethodNode method : cn.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;) {
                AbstractInsnNode next = instruction.getNext();
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if ("java/net/URI".equals(call.owner) && "toASCIIString".equals(call.name)
                            && "()Ljava/lang/String;".equals(call.desc)) {
                        method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "org/unmojang/loki/hooks/RealmsHooks", "redirect",
                                "(Ljava/lang/String;)Ljava/lang/String;", false));
                        changed = true;
                    }
                }
                instruction = next;
            }
        }
        return changed;
    }
}
