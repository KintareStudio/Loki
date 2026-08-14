package org.unmojang.loki.transformers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

public class AllowedDomainTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return className.equals("com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService")
                || className.equals("com/mojang/authlib/yggdrasil/TextureUrlChecker")
                || className.equals("com/mojang/authlib/services/MinecraftServicesDiscoveryService")
                // CustomPlayerModels GameProfile
                || className.equals("com/tom/cpm/retro/GameProfile")
                // MCAuthlib (used in MojangFix and Ears mods, possibly more)
                || className.endsWith("/data/GameProfile");
    }

    protected int writerFlags(String className) {
        return ClassWriter.COMPUTE_FRAMES;
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if ((mn.name.equals("isWhitelistedDomain") || mn.name.equals("isAllowedTextureDomain"))
                    && mn.desc.equals("(Ljava/lang/String;)Z")) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                // Delegate rather than bake the domains in: the allowlist is not final at class
                // load time any more, since a server can redirect profile queries at runtime and
                // contribute the skin domains that come with them.
                int urlSlot = (mn.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1; // <=26.2 : 26.3+
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, urlSlot));
                mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "org/unmojang/loki/hooks/Hooks",
                        "isAllowedTextureDomain",
                        "(Ljava/lang/String;)Z",
                        false));
                mn.instructions.add(new InsnNode(Opcodes.IRETURN));

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            }

            // CPM's domain check is too simple to patch skinDomains support into, and who cares anyway?
            if (className.equals("com/tom/cpm/retro/GameProfile")) {
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode
                            && "http://textures.minecraft.net/texture/".equals(((LdcInsnNode) insn).cst)) {
                        ((LdcInsnNode) insn).cst = "";

                        Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                        changed = true;
                    }
                }
            }
        }

        return changed;
    }

}
