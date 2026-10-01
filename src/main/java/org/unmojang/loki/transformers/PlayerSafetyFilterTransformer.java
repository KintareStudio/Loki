package org.unmojang.loki.transformers;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.unmojang.loki.Loki;

import java.net.URL;

public class PlayerSafetyFilterTransformer extends LokiTransformer {
    private static final String TARGET = "net/minecraft/server/network/PlayerSafetyServiceTextFilter";
    private static final String AUTHORITY_FORMAT = "https://login.microsoftonline.com/%s/";
    private static final String BUILDER = "com/microsoft/aad/msal4j/ConfidentialClientApplication$Builder";

    protected boolean matches(String className) {
        return TARGET.equals(className) && System.getProperty("Loki.filteringV1.authority") != null;
    }

    protected boolean patch(ClassNode cn, String className) {
        String authority = System.getProperty("Loki.filteringV1.authority");
        URL url;
        try {
            url = new URL(authority);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid V1 filtering authority", error);
        }
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !"/".equals(url.getPath())
                || url.getQuery() != null || url.getRef() != null) {
            throw new IllegalArgumentException("V1 filtering authority must be an HTTPS origin");
        }
        String format = "https://" + url.getAuthority() + "/%s/";
        for (MethodNode method : cn.methods) {
            if (!"createTextFilterFromConfig".equals(method.name)
                    || !"(Ljava/lang/String;)Lnet/minecraft/server/network/ServerTextFilter;".equals(method.desc)) {
                continue;
            }
            LdcInsnNode authorityLiteral = null;
            MethodInsnNode authorityCall = null;
            for (AbstractInsnNode node = method.instructions.getFirst(); node != null; node = node.getNext()) {
                if (node instanceof LdcInsnNode && AUTHORITY_FORMAT.equals(((LdcInsnNode) node).cst)) {
                    authorityLiteral = (LdcInsnNode) node;
                } else if (node instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) node;
                    if (BUILDER.equals(call.owner) && "authority".equals(call.name)
                            && "(Ljava/lang/String;)Lcom/microsoft/aad/msal4j/AbstractClientApplicationBase$Builder;".equals(call.desc)) {
                        authorityCall = call;
                    }
                }
            }
            if (authorityLiteral == null || authorityCall == null) return false;
            authorityLiteral.cst = format;
            InsnList disableMicrosoftDiscovery = new InsnList();
            disableMicrosoftDiscovery.add(new InsnNode(Opcodes.ICONST_0));
            disableMicrosoftDiscovery.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "com/microsoft/aad/msal4j/AbstractClientApplicationBase$Builder",
                    "instanceDiscovery", "(Z)Lcom/microsoft/aad/msal4j/AbstractClientApplicationBase$Builder;", false));
            method.instructions.insert(authorityCall, disableMicrosoftDiscovery);
            Loki.log.info("Redirected V1 filtering authority to " + url.getAuthority());
            return true;
        }
        return false;
    }
}
