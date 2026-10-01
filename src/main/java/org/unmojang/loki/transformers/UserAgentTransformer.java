package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class UserAgentTransformer extends LokiTransformer {
    private final String userAgent;

    public UserAgentTransformer(String userAgent) {
        this.userAgent = userAgent;
    }

    protected boolean matches(String className) {
        return "sun/net/www/protocol/http/HttpURLConnection".equals(className)
                || "jdk/internal/net/http/HttpRequestImpl".equals(className);
    }

    protected int writerFlags(String className) {
        return 0;
    }

    protected boolean patch(ClassNode cn, String className) {
        String field = className.startsWith("sun/") ? "userAgent" : "USER_AGENT";
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if ("<clinit>".equals(mn.name)) continue;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.GETSTATIC) continue;
                FieldInsnNode fin = (FieldInsnNode) insn;
                if (!className.equals(fin.owner) || !field.equals(fin.name)) continue;

                LdcInsnNode ldc = new LdcInsnNode(userAgent);
                mn.instructions.set(insn, ldc);
                insn = ldc;
                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            }
        }

        return changed;
    }
}
