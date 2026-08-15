package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.unmojang.loki.Loki;
import org.unmojang.loki.LokiUtil;

/**
 * Decides whether a profile property's signature is good, on the versions that ask.
 * <p>
 * By default this answers yes without looking, which is the only thing that works when the API
 * server does not sign at all. With {@code Loki.enforce_secure_profile} it becomes a real check
 * against the keys the API server publishes, in {@link org.unmojang.loki.hooks.ProfileKeys}.
 * <p>
 * The replacement reads the property's own {@code value} and {@code signature} fields directly. It
 * is emitted into the declaring class, so private access is not a problem and no reflection is
 * needed on a path that runs once per profile. Both fields have carried those names, and this
 * method that exact descriptor, in every authlib from 1.5.6 to 3.3.39, which is every Minecraft
 * from 1.7.6 to 1.18.2. From 1.19 on the game asks {@code ServicesKeyInfo.validateProperty}
 * instead, and this method survives only for mods that still call it.
 */
public class SignatureValidTransformer extends LokiTransformer {

    protected boolean matches(String className) {
        // MCAuthlib, which MojangFix and Ears use, keeps its own copy of this class as a nested
        // GameProfile$Property. Different library, identical shape: same method descriptor, same
        // two field names, same SHA1withRSA, so the same replacement fits both.
        return "com/mojang/authlib/properties/Property".equals(className)
                || className.endsWith("/data/GameProfile$Property");
    }

    protected boolean patch(ClassNode cn, String className) {
        boolean changed = false;

        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("isSignatureValid") && mn.desc.equals("(Ljava/security/PublicKey;)Z")) {
                mn.instructions.clear();
                mn.tryCatchBlocks.clear();
                if (mn.localVariables != null) mn.localVariables.clear();

                // Always the call, never a constant true: whether signatures are checked can change
                // during a session, since a server can ask for it in its ping, and a class patched
                // to return true was patched when the game started and cannot be asked again.
                // ProfileKeys answers that question per call.
                //
                // The PublicKey argument is deliberately ignored: it is authlib's single hardcoded
                // key, and the point of this is to trust a set instead.
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, className, "value", "Ljava/lang/String;"));
                mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mn.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, className, "signature", "Ljava/lang/String;"));
                mn.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "org/unmojang/loki/hooks/ProfileKeys",
                        "isSignatureValid",
                        "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Z",
                        false));
                mn.instructions.add(new InsnNode(Opcodes.IRETURN));

                Loki.log.debug("Patching " + LokiUtil.getFqmn(className, mn.name, mn.desc));
                changed = true;
                break;
            }
        }

        return changed;
    }
}
