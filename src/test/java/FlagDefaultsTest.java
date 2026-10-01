import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.unmojang.loki.Loki;
import org.unmojang.loki.hooks.ProfileAdvertiser;
import org.unmojang.loki.hooks.ProfileKeys;
import org.unmojang.loki.transformers.PatchyTransformer;
import org.unmojang.loki.transformers.PlayerAttributesTransformer;

public class FlagDefaultsTest {
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    private static final class Attributes extends PlayerAttributesTransformer {
        boolean apply(ClassNode node) { return patch(node, "com/mojang/authlib/TestAttributes"); }
    }

    private static final class Patchy extends PatchyTransformer {
        boolean enabled() { return matches("com/mojang/patchy/BlockedServers"); }
    }

    public static void main(String[] args) {
        boolean enabled = !"disabled".equals(args[0]);
        if (!enabled) {
            for (String flag : new String[]{"chat_restrictions", "enable_patchy", "enable_snooper"}) {
                System.setProperty("Loki." + flag, "false");
            }
            System.setProperty("Loki.verify_signatures", "false");
        }
        check(Loki.chat_restrictions == enabled, "chat restrictions");
        check(Loki.enable_patchy == enabled, "Patchy blocking");
        check(Loki.enable_snooper == enabled, "telemetry");
        check(Loki.enforce_secure_profile == enabled, "secure profiles");
        check(Loki.verify_signatures == enabled, "signature alias");
        check(ProfileKeys.enforcing() == enabled, "runtime signature verification");
        String declaration = ProfileAdvertiser.declaration();
        check(enabled ? declaration != null && declaration.contains("\"enforceSecureProfile\":true")
                : declaration == null, "advertised signature policy");
        check(new Patchy().enabled() == !enabled, "Patchy bypass only when explicitly disabled");

        ClassNode node = new ClassNode();
        MethodNode chat = new MethodNode(Opcodes.ACC_PUBLIC, "chatAllowed", "()Z", null, null);
        chat.instructions.add(new InsnNode(Opcodes.ICONST_0));
        chat.instructions.add(new InsnNode(Opcodes.IRETURN));
        MethodNode telemetry = new MethodNode(Opcodes.ACC_PUBLIC, "telemetryAllowed", "()Z", null, null);
        telemetry.instructions.add(new InsnNode(Opcodes.ICONST_1));
        telemetry.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(chat);
        node.methods.add(telemetry);
        check(new Attributes().apply(node) == !enabled, "backend attributes preserved by default");
        check(chat.instructions.getFirst().getOpcode() == (enabled ? Opcodes.ICONST_0 : Opcodes.ICONST_1),
                "backend chat refusal respected unless explicitly bypassed");
        check(telemetry.instructions.getFirst().getOpcode() == (enabled ? Opcodes.ICONST_1 : Opcodes.ICONST_0),
                "backend telemetry permission respected unless explicitly disabled");
        System.out.println("FlagDefaultsTest[" + args[0] + "]: PASSED");
    }
}
