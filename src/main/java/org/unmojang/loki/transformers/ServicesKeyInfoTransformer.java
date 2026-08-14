package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

/**
 * Redirects the 1.19+ signature checks at the keys the API server publishes.
 * <p>
 * Both methods that consult a key are replaced, so the {@code publicKey} field the class was built
 * around is left alone: nothing reads it once these two are gone, since {@code keyBitCount} returns
 * a constant in every authlib from 3.5.41 to 10.0.76. Overwriting it, as Loki used to, cost a
 * request to {@code /publickeys} per construction and took the game down with it when that request
 * failed, all to set a field no one would look at.
 */
public class ServicesKeyInfoTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        return "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo".equals(className)
                || "com/mojang/authlib/services/MinecraftServicesKeyInfo".equals(className)
                || "com/mojang/authlib/services/MinecraftServicesDiscoveryService".equals(className);
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean isKeyInfo = "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo".equals(className)
                || "com/mojang/authlib/services/MinecraftServicesKeyInfo".equals(className);
        boolean isDiscoveryService = "com/mojang/authlib/services/MinecraftServicesDiscoveryService".equals(className);

        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if (isKeyInfo && "validateProperty".equals(mn.name) && "(Lcom/mojang/authlib/properties/Property;)Z".equals(mn.desc)) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                InsnList insns = new InsnList();
                if (Loki.enforce_secure_profile) {
                    // Against every key the API server publishes for profile properties, not
                    // against the one this object happens to hold
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    insns.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "org/unmojang/loki/hooks/ProfileKeys",
                            "isPropertyValid",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                            false
                    ));
                } else {
                    insns.add(new InsnNode(Opcodes.ICONST_1));
                }
                insns.add(new InsnNode(Opcodes.IRETURN));

                mn.instructions.add(insns);

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            } else if (isKeyInfo && "signature".equals(mn.name) && "()Ljava/security/Signature;".equals(mn.desc)) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                InsnList insns = new InsnList();
                if (Loki.enforce_secure_profile) {
                    // This is what verifies a player's certificate, so it answers for the
                    // certificate keys, and for all of them: one Signature, several keys behind it
                    insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insns.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "org/unmojang/loki/hooks/ProfileKeys",
                            "certificateSignature",
                            "(Ljava/lang/Object;)Ljava/security/Signature;",
                            false
                    ));
                } else {
                    insns.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "org/unmojang/loki/hooks/Hooks",
                            "createDummySignature",
                            "()Ljava/security/Signature;",
                            false
                    ));
                }
                insns.add(new InsnNode(Opcodes.ARETURN));

                mn.instructions.add(insns);
                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            } else if (isDiscoveryService && "getServicesKeySet".equals(mn.name)
                    && "()Lcom/mojang/authlib/services/ServicesKeySet;".equals(mn.desc)) {

                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                InsnList insns = new InsnList();
                insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
                insns.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/unmojang/loki/hooks/Hooks",
                        "buildServicesKeySet",
                        "(Ljava/lang/ClassLoader;)Ljava/lang/Object;",
                        false
                ));
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "com/mojang/authlib/services/ServicesKeySet"));
                insns.add(new InsnNode(Opcodes.ARETURN));

                mn.instructions.add(insns);

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
            }
        }

        return changed;
    }
}
