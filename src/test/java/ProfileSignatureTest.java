import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.unmojang.loki.transformers.ServicesKeyInfoTransformer;
import org.unmojang.loki.transformers.SignatureValidTransformer;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

/**
 * Real signatures, real keys, real bytecode: signs a property with an RSA key, patches authlib's
 * Property with the transformer, and asks the patched method what it thinks.
 * <p>
 * Runs in three modes because the key source is chosen once per process and cached, which is the
 * behaviour under test rather than an obstacle to it:
 * <ul>
 *   <li>{@code publickeys} - keys from a Mojang shaped {@code /publickeys}, including that a
 *       second published key verifies just as well as the first, which is what makes rotation a
 *       non-event, and that the set survives the server going away</li>
 *   <li>{@code metadata} - keys declared in authlib-injector metadata win over the endpoint</li>
 *   <li>{@code offline} - nothing reachable, so only Mojang's bundled key is left</li>
 * </ul>
 *
 * @param args the mode, then the directory holding the compiled Property stand-in
 */
public class ProfileSignatureTest {
    private static int failures = 0;

    /** The textures property value, as the API server would have signed it. */
    private static final String VALUE = "eyJ0aW1lc3RhbXAiOjE3MDAwMDAwMDAwMDAsInByb2ZpbGVOYW1lIjoiVGVzdCJ9";

    private static void check(String label, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + label + (detail == null ? "" : "  [" + detail + "]"));
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String sign(KeyPair key, String value) throws Exception {
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(key.getPrivate());
        signer.update(value.getBytes("UTF-8"));
        return java.util.Base64.getEncoder().encodeToString(signer.sign());
    }

    private static String der(KeyPair key) {
        return java.util.Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
    }

    private static void appendKeys(StringBuilder body, String field, KeyPair[] keys) {
        body.append("\"").append(field).append("\":[");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) body.append(",");
            body.append("{\"publicKey\":\"").append(der(keys[i])).append("\"}");
        }
        body.append("]");
    }

    /** Serves a Mojang shaped publickeys document, with the two kinds of key kept apart. */
    private static HttpServer publicKeys(final KeyPair[] property, final KeyPair[] certificate)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/publickeys", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                StringBuilder body = new StringBuilder("{");
                appendKeys(body, "profilePropertyKeys", property);
                body.append(",");
                appendKeys(body, "playerCertificateKeys", certificate);
                body.append(",\"authenticationKeys\":[]}");

                byte[] bytes = body.toString().getBytes("UTF-8");
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream out = exchange.getResponseBody();
                out.write(bytes);
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    /** Plants Mojang's key where authlib keeps it, so the fallback has something to find. */
    private static void bundleMojangKey(KeyPair mojang) throws Exception {
        File onClasspath = new File(ProfileSignatureTest.class.getResource("/").toURI());
        FileOutputStream out = new FileOutputStream(new File(onClasspath, "yggdrasil_session_pubkey.der"));
        try {
            out.write(mojang.getPublic().getEncoded());
        } finally {
            out.close();
        }
    }

    private static final class PatchingLoader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static byte[] read(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        DataInputStream in = new DataInputStream(new FileInputStream(file));
        try {
            in.readFully(bytes);
        } finally {
            in.close();
        }
        return bytes;
    }

    /**
     * Patches both stand-ins with the real transformers and loads them into one loader, so the
     * Property that KeyInfo.validateProperty resolves is the same patched Property.
     */
    private static Class<?>[] prepare(String stubDir) throws Exception {
        byte[] property = new SignatureValidTransformer().transform(null,
                "com/mojang/authlib/properties/Property", null, null,
                read(new File(stubDir, "com/mojang/authlib/properties/Property.class")));
        byte[] keyInfo = new ServicesKeyInfoTransformer().transform(null,
                "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo", null, null,
                read(new File(stubDir, "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo.class")));

        check("transformer patched Property", property != null, null);
        check("transformer patched YggdrasilServicesKeyInfo", keyInfo != null, null);
        if (property == null || keyInfo == null) {
            System.out.println("ProfileSignatureTest: cannot continue");
            System.exit(1);
        }

        PatchingLoader loader = new PatchingLoader();
        return new Class<?>[]{
                loader.define("com.mojang.authlib.properties.Property", property),
                loader.define("com.mojang.authlib.yggdrasil.YggdrasilServicesKeyInfo", keyInfo)
        };
    }

    /** 1.19 to 1.19.4 asks through the key info, not through the property. */
    private static boolean validatedByKeyInfo(Class<?>[] classes, String value, String signature)
            throws Exception {
        Object property = classes[0].getConstructor(String.class, String.class)
                .newInstance(value, signature);
        Object keyInfo = classes[1].getConstructor(java.security.PublicKey.class).newInstance((Object) null);
        Method validateProperty = classes[1].getMethod("validateProperty", classes[0]);
        return ((Boolean) validateProperty.invoke(keyInfo, property)).booleanValue();
    }

    /** What a player certificate is checked with on 1.19+. */
    private static boolean verifiedByCertificateSignature(Class<?>[] classes, String data, String signature)
            throws Exception {
        Object keyInfo = classes[1].getConstructor(java.security.PublicKey.class).newInstance((Object) null);
        Signature verifier = (Signature) classes[1].getMethod("signature").invoke(keyInfo);
        verifier.update(data.getBytes("UTF-8"));
        return verifier.verify(java.util.Base64.getDecoder().decode(signature));
    }

    /** How 1.7.6 to 1.18.2 asks. */
    private static boolean valid(Class<?> property, String value, String signature) throws Exception {
        Object instance = property.getConstructor(String.class, String.class).newInstance(value, signature);
        Method isSignatureValid = property.getMethod("isSignatureValid", java.security.PublicKey.class);
        // The argument is authlib's own key, which the patched method ignores on purpose
        return ((Boolean) isSignatureValid.invoke(instance, (Object) null)).booleanValue();
    }

    /** See ProfileRedirectTest: the key server runs on a non-daemon thread, so never hang on it. */
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.println("ProfileSignatureTest: CRASHED");
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        String mode = args[0];
        String stubDir = args[1];

        // Must land before the transformer loads Loki, which reads this once
        System.setProperty("Loki.enforce_secure_profile", "true");

        KeyPair active = rsa();
        KeyPair grace = rsa();
        KeyPair certificate = rsa();
        KeyPair stranger = rsa();
        KeyPair mojang = rsa();
        bundleMojangKey(mojang);

        HttpServer server = null;
        if ("publickeys".equals(mode) || "metadata".equals(mode)) {
            server = publicKeys(new KeyPair[]{active, grace}, new KeyPair[]{certificate});
            System.setProperty("minecraft.api.services.host", "http://127.0.0.1:" + server.getAddress().getPort());
        } else {
            // A port nothing is listening on, so the fetch fails rather than hangs
            System.setProperty("minecraft.api.services.host", "http://127.0.0.1:1");
        }
        if ("metadata".equals(mode)) {
            System.setProperty("Loki.signature_keys", der(stranger));
        }

        Class<?>[] classes = prepare(stubDir);
        Class<?> property = classes[0];

        System.out.println();
        System.out.println("== mode: " + mode + " ==");

        if ("publickeys".equals(mode)) {
            System.out.println("  -- 1.19 to 1.19.4, which asks through the key info --");
            check("a published property key verifies",
                    validatedByKeyInfo(classes, VALUE, sign(active, VALUE)), null);
            check("the second one does too",
                    validatedByKeyInfo(classes, VALUE, sign(grace, VALUE)), null);
            check("a certificate key does not sign properties",
                    !validatedByKeyInfo(classes, VALUE, sign(certificate, VALUE)), null);
            check("a certificate key verifies a certificate",
                    verifiedByCertificateSignature(classes, VALUE, sign(certificate, VALUE)), null);
            check("a property key does not verify a certificate",
                    !verifiedByCertificateSignature(classes, VALUE, sign(active, VALUE)), null);
            check("Mojang's key verifies either",
                    verifiedByCertificateSignature(classes, VALUE, sign(mojang, VALUE))
                            && validatedByKeyInfo(classes, VALUE, sign(mojang, VALUE)), null);
            check("a stranger verifies neither",
                    !verifiedByCertificateSignature(classes, VALUE, sign(stranger, VALUE))
                            && !validatedByKeyInfo(classes, VALUE, sign(stranger, VALUE)), null);

            System.out.println("  -- 1.7.6 to 1.18.2, which asks through the property --");
            check("the active key verifies", valid(property, VALUE, sign(active, VALUE)), null);
            check("a second published key verifies too, so rotation does not break profiles",
                    valid(property, VALUE, sign(grace, VALUE)), null);
            check("an unpublished key does not", !valid(property, VALUE, sign(stranger, VALUE)), null);
            check("Mojang's own key does, for profiles proxied from a fallback",
                    valid(property, VALUE, sign(mojang, VALUE)), null);
            check("a tampered value does not",
                    !valid(property, VALUE + "x", sign(active, VALUE)), null);
            check("an unsigned property does not", !valid(property, VALUE, null), null);
            check("a malformed signature does not", !valid(property, VALUE, "not base64 @@@"), null);

            server.stop(0);
            check("the keys outlive the API server going away",
                    valid(property, VALUE, sign(active, VALUE)), null);
        } else if ("metadata".equals(mode)) {
            check("the key from the metadata verifies", valid(property, VALUE, sign(stranger, VALUE)), null);
            check("the endpoint is not consulted when metadata declared keys",
                    !valid(property, VALUE, sign(active, VALUE)), null);
            check("Mojang's key still verifies", valid(property, VALUE, sign(mojang, VALUE)), null);
            server.stop(0);
        } else {
            check("Mojang's key is what is left when nothing is reachable",
                    valid(property, VALUE, sign(mojang, VALUE)), null);
            check("a server signed property cannot be verified without the server",
                    !valid(property, VALUE, sign(active, VALUE)), null);
            // Constructs through the real constructor, which is where Loki used to fetch a key and
            // throw when it could not. Nothing is reachable here, so this would not survive that.
            check("the key info still builds and answers with nothing reachable",
                    validatedByKeyInfo(classes, VALUE, sign(mojang, VALUE))
                            && verifiedByCertificateSignature(classes, VALUE, sign(mojang, VALUE)), null);
        }

        System.out.println();
        System.out.println(failures == 0
                ? "ProfileSignatureTest[" + mode + "]: PASSED"
                : "ProfileSignatureTest[" + mode + "]: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
