import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * A Yggdrasil server that is not one, so the cross-system test can be run without owning two.
 * <p>
 * It answers the handful of things Loki actually asks an API server for: the authlib-injector
 * metadata, {@code /publickeys}, a profile with a signed textures property, a name to UUID lookup,
 * and one authenticated endpoint that wants a bearer token. Everything else 404s, which is a useful
 * answer in itself — a test that passes because a stub said yes to everything has tested nothing.
 * <p>
 * It publishes <b>several</b> property keys and signs with the last of them. That is the case a
 * single key cannot cover and the one most likely to be got wrong: a client that only reads the
 * first key renders every player on this server as Steve.
 *
 * <h2>Output</h2>
 * Prints one {@code KEY=value} line per fact the caller needs, then stays up until killed:
 * {@code ROOT}, {@code TOKEN}, {@code UUID}, {@code NAME}, {@code SKIN_DOMAIN}, {@code KEYS}.
 *
 * @param args a label for the logs, then optionally the port to bind (0, the default, picks one)
 */
public class StubYggdrasil {
    private static final int PROPERTY_KEYS = 3;

    private final String label;
    private final String token;
    private final UUID uuid;
    private final String name;
    private final String skinDomain;
    private final KeyPair[] propertyKeys = new KeyPair[PROPERTY_KEYS];
    private final KeyPair certificateKey;
    private String root;

    private StubYggdrasil(String label) throws Exception {
        this.label = label;
        this.token = label.toLowerCase() + "-token-" + Long.toHexString(new Random().nextLong());
        this.uuid = UUID.randomUUID();
        this.name = "Player" + label;
        this.skinDomain = "cdn-" + label.toLowerCase() + ".example";
        for (int i = 0; i < propertyKeys.length; i++) propertyKeys[i] = rsa();
        this.certificateKey = rsa();
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** The key as authlib-injector publishes it: PEM, not the bare DER {@code /publickeys} uses. */
    private static String pem(KeyPair key) {
        String der = b64(key.getPublic().getEncoded());
        StringBuilder body = new StringBuilder("-----BEGIN PUBLIC KEY-----\\n");
        for (int at = 0; at < der.length(); at += 64) {
            body.append(der, at, Math.min(at + 64, der.length())).append("\\n");
        }
        return body.append("-----END PUBLIC KEY-----\\n").toString();
    }

    private String signed(KeyPair key, String value) throws Exception {
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(key.getPrivate());
        signer.update(value.getBytes("UTF-8"));
        return b64(signer.sign());
    }

    private static String dashless(UUID id) {
        return id.toString().replace("-", "");
    }

    /** The textures document, base64 encoded, exactly as a real one is carried. */
    private String textures() {
        String json = "{\"timestamp\":" + System.currentTimeMillis()
                + ",\"profileId\":\"" + dashless(uuid) + "\""
                + ",\"profileName\":\"" + name + "\""
                + ",\"textures\":{\"SKIN\":{\"url\":\"https://" + skinDomain + "/skin/" + name + ".png\"}}}";
        try {
            return b64(json.getBytes("UTF-8"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String profileJson() throws Exception {
        String value = textures();
        // Signed with the last published key on purpose: a client that reads only the first fails
        String signature = signed(propertyKeys[propertyKeys.length - 1], value);
        return "{\"id\":\"" + dashless(uuid) + "\",\"name\":\"" + name + "\",\"properties\":["
                + "{\"name\":\"textures\",\"value\":\"" + value + "\",\"signature\":\"" + signature + "\"}]}";
    }

    private String metadataJson() {
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < propertyKeys.length; i++) {
            if (i > 0) keys.append(",");
            keys.append("\"").append(pem(propertyKeys[i])).append("\"");
        }
        return "{\"meta\":{\"serverName\":\"Stub " + label + "\",\"implementationName\":\"stub\","
                + "\"implementationVersion\":\"1\"},"
                + "\"skinDomains\":[\"" + skinDomain + "\"],"
                + "\"signaturePublickeys\":[" + keys + "]}";
    }

    private String publicKeysJson() {
        StringBuilder body = new StringBuilder("{\"profilePropertyKeys\":[");
        for (int i = 0; i < propertyKeys.length; i++) {
            if (i > 0) body.append(",");
            body.append("{\"publicKey\":\"").append(b64(propertyKeys[i].getPublic().getEncoded())).append("\"}");
        }
        body.append("],\"playerCertificateKeys\":[{\"publicKey\":\"")
                .append(b64(certificateKey.getPublic().getEncoded()))
                .append("\"}],\"authenticationKeys\":[]}");
        return body.toString();
    }

    /** Server ids that were joined, and by whom, so hasJoined can answer honestly. */
    private final Map<String, String> joined = new ConcurrentHashMap<String, String>();

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    /** The value of a JSON string field, without a parser for the four fields this needs. */
    private static String between(String json, String field) {
        int at = json.indexOf(field);
        if (at < 0) return null;
        int open = json.indexOf('"', at + field.length() + 1);
        int close = open < 0 ? -1 : json.indexOf('"', open + 1);
        return close < 0 ? null : json.substring(open + 1, close);
    }

    private static String valueOf(String query, String key) {
        if (query == null) return null;
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            if (eq > 0 && pairs[i].substring(0, eq).equals(key)) return pairs[i].substring(eq + 1);
        }
        return null;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        exchange.close();
    }

    private void route(HttpServer server) {
        server.createContext("/authlib-injector", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                String path = exchange.getRequestURI().getPath().substring("/authlib-injector".length());
                System.out.println("[" + label + "] " + exchange.getRequestMethod() + " " + path);
                try {
                    handle(exchange, path);
                } catch (Exception e) {
                    send(exchange, 500, "{\"error\":\"" + e + "\"}");
                }
            }

            private void handle(HttpExchange exchange, String path) throws Exception {
                if (path.length() == 0 || "/".equals(path)) {
                    send(exchange, 200, metadataJson());
                } else if ("/minecraftservices/publickeys".equals(path)) {
                    send(exchange, 200, publicKeysJson());
                } else if ("/sessionserver/session/minecraft/join".equals(path)) {
                    // What a client's join becomes once Loki has translated it. The body carries
                    // the token, the profile and the server id; a real one would check the token,
                    // and this one does too, because a test that accepts anything proves nothing.
                    String body = new String(readAll(exchange.getRequestBody()), "UTF-8");
                    if (body.indexOf(token) < 0) {
                        send(exchange, 403, "{\"error\":\"ForbiddenOperationException\"}");
                        return;
                    }
                    String serverId = between(body, "\"serverId\"");
                    if (serverId == null) {
                        send(exchange, 400, "{\"error\":\"no serverId\"}");
                        return;
                    }
                    joined.put(serverId, name);
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                } else if (path.startsWith("/sessionserver/session/minecraft/hasJoined")) {
                    // And what a server's check becomes. Answering only for a server id that was
                    // actually joined is what makes the two halves a test rather than two stubs.
                    String query = exchange.getRequestURI().getQuery();
                    String serverId = valueOf(query, "serverId");
                    if (serverId == null || !name.equals(joined.get(serverId))) {
                        exchange.sendResponseHeaders(204, -1);
                        exchange.close();
                        return;
                    }
                    send(exchange, 200, profileJson());
                } else if (path.startsWith("/sessionserver/session/minecraft/profile/")) {
                    String asked = path.substring(path.lastIndexOf('/') + 1);
                    if (!dashless(uuid).equalsIgnoreCase(asked.replace("-", ""))) {
                        // What Mojang answers for a profile it has never heard of, and what makes
                        // a client that failed to redirect fail loudly rather than quietly
                        send(exchange, 204, "");
                        return;
                    }
                    send(exchange, 200, profileJson());
                } else if (path.startsWith("/api/users/profiles/minecraft/")) {
                    String asked = path.substring(path.lastIndexOf('/') + 1);
                    if (!name.equalsIgnoreCase(asked)) {
                        send(exchange, 204, "");
                        return;
                    }
                    send(exchange, 200, "{\"id\":\"" + dashless(uuid) + "\",\"name\":\"" + name + "\"}");
                } else if ("/minecraftservices/minecraft/profile".equals(path)) {
                    // The one endpoint here that takes a credential, so that a test can tell whether
                    // an authenticated call went to the right API server
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    if (authorization == null || !authorization.equals("Bearer " + token)) {
                        send(exchange, 401, "{\"error\":\"not this server's token\"}");
                        return;
                    }
                    send(exchange, 200, profileJson());
                } else {
                    send(exchange, 404, "{\"error\":\"no such endpoint\"}");
                }
            }
        });
    }

    public static void main(String[] args) throws Exception {
        String label = args.length > 0 ? args[0] : "A";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 0;

        StubYggdrasil stub = new StubYggdrasil(label);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        stub.route(server);
        server.start();

        stub.root = "http://127.0.0.1:" + server.getAddress().getPort() + "/authlib-injector";
        System.out.println("ROOT=" + stub.root);
        System.out.println("TOKEN=" + stub.token);
        System.out.println("UUID=" + dashless(stub.uuid));
        System.out.println("NAME=" + stub.name);
        System.out.println("SKIN_DOMAIN=" + stub.skinDomain);
        System.out.println("KEYS=" + PROPERTY_KEYS);
        System.out.flush();
    }
}
