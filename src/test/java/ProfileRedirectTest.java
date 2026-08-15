import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Fake;
import org.unmojang.loki.RequestInterceptor;
import org.unmojang.loki.Ygglib;
import org.unmojang.loki.hooks.Hooks;
import org.unmojang.loki.hooks.ProfileRedirect;
import org.unmojang.loki.transformers.NettyConnectTransformer;
import org.unmojang.loki.util.Json;
import org.unmojang.loki.util.Protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * End to end for the server-declared profile API: a game server advertises one in its Server List
 * Ping, Loki picks it up through the Netty connect hook, and profile reads start going there while
 * everything else stays put.
 * <p>
 * Everything is stood up locally. There is no dependency on a real server or on the network.
 *
 * @param args the directory holding the compiled Bootstrap stand-in
 */
public class ProfileRedirectTest {
    private static int failures = 0;
    private static Object bootstrap;

    private static void check(String label, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + label + (detail == null ? "" : "  [" + detail + "]"));
    }

    /** A stand-in API server that records what was asked of it. */
    private static class Api {
        final HttpServer server;
        final int port;
        final List<String> hits = Collections.synchronizedList(new ArrayList<String>());

        /**
         * @param signingKey what its authlib-injector metadata declares
         * @param endpointKey what its /publickeys serves, or null to publish none there
         */
        Api(final String tag, final String prefix, final boolean withExtraProperty,
            final String signingKey, final String endpointKey) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();
            server.createContext("/", new HttpHandler() {
                public void handle(HttpExchange exchange) throws IOException {
                    String path = exchange.getRequestURI().getPath();
                    hits.add(path);
                    String body;
                    if (path.equals(prefix)) { // authlib-injector root
                        body = "{\"meta\":{\"serverName\":\"" + tag + "\"},"
                                + "\"skinDomains\":[\"cdn-" + tag + ".example\"],"
                                + "\"signaturePublickeys\":[\"-----BEGIN PUBLIC KEY-----\\n"
                                + signingKey + "\\n-----END PUBLIC KEY-----\"]}";
                    } else if (path.equals(prefix + "/minecraftservices/publickeys")) {
                        body = endpointKey == null
                                ? "{\"profilePropertyKeys\":[],\"playerCertificateKeys\":[]}"
                                : "{\"profilePropertyKeys\":[{\"publicKey\":\"" + endpointKey + "\"}],"
                                        + "\"playerCertificateKeys\":[{\"publicKey\":\"" + endpointKey + "\"}]}";
                    } else if (path.startsWith(prefix + "/sessionserver/session/minecraft/profile/")) {
                        body = "{\"id\":\"abc\",\"name\":\"TestPlayer\",\"properties\":["
                                + "{\"name\":\"textures\",\"value\":\"" + tag + "-textures\"}"
                                + (withExtraProperty ? ",{\"name\":\"smuggled\",\"value\":\"nope\"}" : "")
                                + "]}";
                    } else {
                        body = "{\"servedBy\":\"" + tag + "\",\"path\":\"" + path + "\"}";
                    }
                    byte[] bytes = body.getBytes("UTF-8");
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                }
            });
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + port;
        }

        boolean saw(String fragment) {
            synchronized (hits) {
                for (String hit : hits) if (hit.contains(fragment)) return true;
            }
            return false;
        }
    }

    /** A stand-in game server that answers a Server List Ping and nothing else. */
    private static class McServer extends Thread {
        private final ServerSocket socket;
        private final String statusJson;

        McServer(String statusJson) throws IOException {
            this.socket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            this.statusJson = statusJson;
            setDaemon(true);
        }

        int port() {
            return socket.getLocalPort();
        }

        public void run() {
            while (!socket.isClosed()) {
                Socket client = null;
                try {
                    client = socket.accept();
                    DataInputStream in = new DataInputStream(client.getInputStream());
                    DataOutputStream out = new DataOutputStream(client.getOutputStream());

                    readVarInt(in);          // handshake length
                    readVarInt(in);          // packet id
                    readVarInt(in);          // protocol version
                    in.skipBytes(readVarInt(in)); // server address
                    in.skipBytes(2);         // port
                    readVarInt(in);          // next state
                    readVarInt(in);          // status request length
                    readVarInt(in);          // status request packet id

                    ByteArrayOutputStream payload = new ByteArrayOutputStream();
                    DataOutputStream packet = new DataOutputStream(payload);
                    writeVarInt(packet, 0x00);
                    byte[] json = statusJson.getBytes("UTF-8");
                    writeVarInt(packet, json.length);
                    packet.write(json);
                    packet.flush();

                    writeVarInt(out, payload.size());
                    out.write(payload.toByteArray());
                    out.flush();
                } catch (IOException ignored) {
                } finally {
                    if (client != null) try { client.close(); } catch (IOException ignored) {}
                }
            }
        }

        static int readVarInt(DataInputStream in) throws IOException {
            int result = 0;
            for (int i = 0; i < 5; i++) {
                int read = in.readUnsignedByte();
                result |= (read & 0x7F) << (i * 7);
                if ((read & 0x80) == 0) return result;
            }
            throw new IOException("VarInt is longer than 5 bytes");
        }

        static void writeVarInt(DataOutputStream out, int value) throws IOException {
            do {
                int part = value & 0x7F;
                value >>>= 7;
                out.writeByte(value != 0 ? (part | 0x80) : part);
            } while (value != 0);
        }
    }

    /** Asks the verifier ServicesKeyInfo.signature() hands out whether it accepts this signature. */
    private static boolean verifiesCertificate(String signature, String value) throws Exception {
        java.security.Signature verifier = org.unmojang.loki.hooks.ProfileKeys.certificateSignature("x");
        verifier.update(value.getBytes("UTF-8"));
        return verifier.verify(java.util.Base64.getDecoder().decode(signature));
    }

    private static String der(java.security.KeyPair key) {
        return java.util.Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
    }

    /** Signs a property value the way an API server would, so it can be checked against its keys. */
    private static String signed(java.security.KeyPair key, String value) throws Exception {
        java.security.Signature signer = java.security.Signature.getInstance("SHA1withRSA");
        signer.initSign(key.getPrivate());
        signer.update(value.getBytes("UTF-8"));
        return java.util.Base64.getEncoder().encodeToString(signer.sign());
    }

    private static String base64(String value) throws Exception {
        return java.util.Base64.getEncoder().encodeToString(value.getBytes("UTF-8"));
    }

    private static String profileWith(String properties) {
        return "{\"id\":\"abc\",\"name\":\"TestPlayer\",\"properties\":[" + properties + "]}";
    }

    private static String status(String declaration) {
        return "{\"version\":{\"name\":\"1.21.1\",\"protocol\":767},"
                + "\"players\":{\"max\":20,\"online\":1},\"description\":\"test\""
                + (declaration == null ? "" : ",\"loki\":{\"profileApi\":\"" + declaration + "\"}") + "}";
    }

    /** A server that names its endpoints instead of a root, which assumes nothing about its paths. */
    private static String statusNamingEndpoints(String base) {
        return "{\"version\":{\"name\":\"1.21.1\",\"protocol\":767},"
                + "\"players\":{\"max\":20,\"online\":1},\"description\":\"test\",\"loki\":{"
                + "\"session\":\"" + base + "/nothing/like/ali/sessions\","
                + "\"services\":\"" + base + "/authlib-injector/minecraftservices\","
                + "\"skinDomains\":[\"cdn-named.example\"]}}";
    }

    /**
     * Dials a server the way the game does, and writes the handshake that says what for.
     * <p>
     * Both a server list ping and a player arriving open a connection through the same
     * {@code connect}, so the handshake's next state is the only thing that tells them apart.
     *
     * @return the connect's return value, so a caller can check the hook left it alone
     */
    private static Object handshake(String host, int port, int nextState) throws Exception {
        Object future = bootstrap.getClass().getMethod("connect", SocketAddress.class)
                .invoke(bootstrap, InetSocketAddress.createUnresolved(host, port));

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(payload);
        McServer.writeVarInt(packet, 0x00); // Handshake
        McServer.writeVarInt(packet, -1);   // protocol version
        byte[] address = host.getBytes("UTF-8");
        McServer.writeVarInt(packet, address.length);
        packet.write(address);
        packet.writeShort(port);
        McServer.writeVarInt(packet, nextState);
        packet.flush();

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream framed = new DataOutputStream(frame);
        McServer.writeVarInt(framed, payload.size());
        framed.write(payload.toByteArray());
        framed.flush();

        Fake.Channel channel = ((Fake.Future) future).channel();

        // Netty asks for a read as soon as the channel is active. A handler that cannot pass that
        // on stops the socket being read at all, which looks like a server that never answers.
        Fake.readReachedTheSocket = false;
        channel.pipeline().read();
        check("a read passes through the watcher", Fake.readReachedTheSocket, null);

        // A non-zero reader index, as a buffer that has already been through an encoder would have
        channel.pipeline().write(new ByteBuf(frame.toByteArray(), 3));
        check("the watcher left the pipeline after one packet", channel.pipeline().size() == 0,
                String.valueOf(channel.pipeline().size()));
        return future;
    }

    private static Object join(String host, int port) throws Exception {
        return handshake(host, port, Protocol.STATE_LOGIN);
    }

    private static void ping(String host, int port) throws Exception {
        handshake(host, port, Protocol.STATE_STATUS);
    }

    /** The player quitting, being kicked, or the connection dropping: all one event to Netty. */
    private static void disconnect(Object future) {
        ((Fake.Future) future).channel().close();
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        InputStream in = conn.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /** Loads the patched bytes without reflecting into ClassLoader, which Java 9+ forbids. */
    private static final class PatchingLoader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** Patches the Bootstrap stand-in with the real transformer and loads the result. */
    private static void prepareBootstrap(String stubDir) throws Exception {
        File classFile = new File(stubDir, "io/netty/bootstrap/Bootstrap.class");
        byte[] original = new byte[(int) classFile.length()];
        DataInputStream in = new DataInputStream(new FileInputStream(classFile));
        try {
            in.readFully(original);
        } finally {
            in.close();
        }

        byte[] patched = new NettyConnectTransformer()
                .transform(null, "io/netty/bootstrap/Bootstrap", null, null, original);
        check("transformer patched Bootstrap", patched != null, null);
        if (patched == null) {
            System.out.println("ProfileRedirectTest: cannot continue");
            System.exit(1);
        }

        bootstrap = new PatchingLoader()
                .define("io.netty.bootstrap.Bootstrap", patched)
                .getDeclaredConstructor().newInstance();
    }

    /**
     * The test servers run on non-daemon threads, so an exception escaping the run would leave the
     * JVM alive with nothing to do. A test that hangs tells you less than one that fails.
     */
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.println("ProfileRedirectTest: CRASHED");
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        // Two signing keys that share nothing, so which one a profile verifies against is visible
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair primaryKey = generator.generateKeyPair();
        java.security.KeyPair declaredKey = generator.generateKeyPair();
        // Declared only in the declared server's metadata, never at its endpoint, so which of the
        // two sources was used is visible in which signature verifies
        java.security.KeyPair metadataOnlyKey = generator.generateKeyPair();

        Api primary = new Api("primary", "/ali", false, der(primaryKey), null);
        Api declared = new Api("declared", "/authlib-injector", true,
                der(metadataOnlyKey), der(declaredKey));

        // Must land before RequestInterceptor's static initialiser reads them
        System.setProperty("minecraft.api.env", "custom");
        System.setProperty("minecraft.api.session.host", primary.base() + "/ali/sessionserver");
        System.setProperty("minecraft.api.account.host", primary.base() + "/ali/api");
        System.setProperty("minecraft.api.profiles.host", primary.base() + "/ali/api");
        System.setProperty("minecraft.api.services.host", primary.base() + "/ali/minecraftservices");
        System.setProperty("Loki.texture_domains", "cdn-primary.example");
        // Whose keys are trusted is only a question worth asking where signatures are checked, so
        // the checks below are run with checking on. The section at the end turns it off again, to
        // exercise the other thing that can turn it on: the server saying it enforces.
        System.setProperty("Loki.enforce_secure_profile", "true");

        McServer declaring = new McServer(status(declared.base() + "/authlib-injector"));
        McServer silent = new McServer(status(null));
        McServer cleartext = new McServer(status("http://cleartext.example/authlib-injector"));
        declaring.start();
        silent.start();
        cleartext.start();

        prepareBootstrap(args[0]);
        RequestInterceptor.setURLFactory();

        String profileUrl = "http://sessionserver.mojang.com/session/minecraft/profile/abc";

        System.out.println();
        System.out.println("== before connecting anywhere ==");
        check("profile read served by the configured API server",
                fetch(profileUrl).contains("primary-textures"), null);

        System.out.println();
        System.out.println("== finding the textures property in a profile ==");
        check("found when it is the only one",
                "mine".equals(Ygglib.texturesOf(new Json.JSONObject(profileWith(
                        "{\"name\":\"textures\",\"value\":\"" + base64("mine") + "\"}")), "abc")), null);
        // A profile from a server-declared API server need not be shaped like Mojang's
        check("found when something else comes first",
                "mine".equals(Ygglib.texturesOf(new Json.JSONObject(profileWith(
                        "{\"name\":\"other\",\"value\":\"" + base64("theirs") + "\"},"
                                + "{\"name\":\"textures\",\"value\":\"" + base64("mine") + "\"}")), "abc")), null);
        boolean refused = false;
        try {
            Ygglib.texturesOf(new Json.JSONObject(profileWith(
                    "{\"name\":\"other\",\"value\":\"" + base64("theirs") + "\"}")), "abc");
        } catch (Exception e) {
            refused = true;
        }
        check("refused when there is none, rather than decoding whatever was there", refused, null);

        String someValue = "eyJ0aW1lc3RhbXAiOjF9";
        String signedByDeclared = signed(declaredKey, someValue);
        check("a key the client was not configured with does not verify anything yet",
                !org.unmojang.loki.hooks.ProfileKeys.isSignatureValid("x", someValue, signedByDeclared),
                null);

        System.out.println();
        System.out.println("== pinging a server that declares a profile API ==");
        ping("127.0.0.1", declaring.port());
        ProfileRedirect.awaitDiscovery(8000L);
        check("a ping does not publish an override", ProfileRedirect.sessionBase() == null,
                ProfileRedirect.sessionBase());
        check("a ping is not answered by the declared API server",
                fetch(profileUrl).contains("primary-textures"), null);

        System.out.println();
        System.out.println("== on a server that declares a profile API ==");
        Object future = join("127.0.0.1", declaring.port());
        check("the hook left the connect's return value alone",
                String.valueOf(future).startsWith("future:"), String.valueOf(future));
        ProfileRedirect.awaitDiscovery(8000L);
        check("override published", ProfileRedirect.sessionBase() != null
                && ProfileRedirect.sessionBase().startsWith(declared.base()), ProfileRedirect.sessionBase());

        String profile = fetch(profileUrl);
        check("profile read served by the declared API server", profile.contains("declared-textures"), null);
        check("non-textures properties stripped out", !profile.contains("smuggled"), profile);

        fetch("http://api.mojang.com/users/profiles/minecraft/Notch");
        check("name to uuid redirected", declared.saw("/users/profiles/minecraft/Notch"), null);

        primary.hits.clear();
        declared.hits.clear();
        fetch("http://sessionserver.mojang.com/session/minecraft/join?serverId=x");
        check("join not redirected", primary.saw("/join") && !declared.saw("/join"), null);

        primary.hits.clear();
        declared.hits.clear();
        fetch("http://api.minecraftservices.com/player/certificates");
        check("certificates not redirected",
                primary.saw("/player/certificates") && !declared.saw("/player/certificates"), null);

        primary.hits.clear();
        declared.hits.clear();
        fetch("http://api.minecraftservices.com/minecraft/profile");
        check("the authenticated own-profile endpoint not redirected",
                primary.saw("/minecraft/profile") && !declared.saw("/minecraft/profile"), null);

        String domains = System.getProperty("Loki.texture_domains", "");
        check("the declared server's signing key now verifies a profile property",
                org.unmojang.loki.hooks.ProfileKeys.isSignatureValid("x", someValue, signedByDeclared), null);
        // And certificates too: the players on this server authenticated against its Yggdrasil, so
        // their chat keys carry its signature rather than the configured server's
        check("and a player certificate, since its Yggdrasil signed those as well",
                verifiesCertificate(signedByDeclared, someValue), null);
        check("its endpoint is preferred to its metadata, which knows no key types",
                !org.unmojang.loki.hooks.ProfileKeys.isSignatureValid("x", someValue,
                        signed(metadataOnlyKey, someValue)), null);

        check("declared skin domains merged into the allowlist",
                domains.contains("cdn-primary.example") && domains.contains("cdn-declared.example"), domains);
        check("declared CDN passes the texture check",
                Hooks.isAllowedTextureDomain("https://cdn-declared.example/tex/1"), null);
        check("an unrelated domain still does not",
                !Hooks.isAllowedTextureDomain("https://somewhere-else.example/tex/1"), null);

        System.out.println();
        System.out.println("== moving to a server that declares nothing ==");
        join("127.0.0.1", silent.port());
        ProfileRedirect.awaitDiscovery(8000L);
        check("override cleared", ProfileRedirect.sessionBase() == null, null);
        check("and its signing key is not trusted any more either",
                !org.unmojang.loki.hooks.ProfileKeys.isSignatureValid("x", someValue, signedByDeclared),
                null);
        check("for certificates either", !verifiesCertificate(signedByDeclared, someValue), null);
        check("profile read back on the configured API server",
                fetch(profileUrl).contains("primary-textures"), null);

        System.out.println();
        System.out.println("== transferred onto a server that declares one ==");
        handshake("127.0.0.1", declaring.port(), Protocol.STATE_TRANSFER);
        ProfileRedirect.awaitDiscovery(8000L);
        check("a transfer counts as arriving", ProfileRedirect.sessionBase() != null
                && ProfileRedirect.sessionBase().startsWith(declared.base()), ProfileRedirect.sessionBase());

        System.out.println();
        System.out.println("== a server naming its endpoints instead of a root ==");
        McServer naming = new McServer(statusNamingEndpoints(declared.base()));
        naming.start();
        join("127.0.0.1", naming.port());
        ProfileRedirect.awaitDiscovery(8000L);
        check("the session endpoint is taken as named, whatever its path",
                ProfileRedirect.sessionBase() != null
                        && ProfileRedirect.sessionBase().endsWith("/nothing/like/ali/sessions"),
                ProfileRedirect.sessionBase());
        check("an endpoint it did not name is not redirected",
                ProfileRedirect.accountBase() == null, ProfileRedirect.accountBase());
        check("its keys are read from the services endpoint it named",
                org.unmojang.loki.hooks.ProfileKeys.isSignatureValid("x", someValue,
                        signed(declaredKey, someValue)), null);
        check("and the skin domains it declared are trusted",
                System.getProperty(ProfileRedirect.PROP_TEXTURE_DOMAINS, "").contains("cdn-named.example"),
                System.getProperty(ProfileRedirect.PROP_TEXTURE_DOMAINS));

        System.out.println();
        System.out.println("== a server that says nothing about textures ==");
        McServer quietAboutSkins = new McServer("{\"version\":{\"name\":\"1.21.1\",\"protocol\":767},"
                + "\"players\":{\"max\":20,\"online\":1},\"description\":\"test\",\"loki\":{"
                + "\"session\":\"" + declared.base() + "/authlib-injector/sessionserver\"}}");
        quietAboutSkins.start();
        join("127.0.0.1", quietAboutSkins.port());
        ProfileRedirect.awaitDiscovery(8000L);
        check("its CDN is not blocked for being unmentioned",
                Hooks.isAllowedTextureDomain("https://never-heard-of-it.example/tex/1"), null);

        System.out.println();
        System.out.println("== back to the client's own list on leaving ==");
        join("127.0.0.1", silent.port());
        ProfileRedirect.awaitDiscovery(8000L);
        check("the client's own domains are enforced again",
                Hooks.isAllowedTextureDomain("https://cdn-primary.example/tex/1")
                        && !Hooks.isAllowedTextureDomain("https://never-heard-of-it.example/tex/1"), null);
        check("and a previous server's CDN does not linger",
                !Hooks.isAllowedTextureDomain("https://cdn-named.example/tex/1"), null);

        System.out.println();
        System.out.println("== a client that would rather keep its list ==");
        System.setProperty("Loki.strict_texture_domains", "true");
        join("127.0.0.1", quietAboutSkins.port());
        ProfileRedirect.awaitDiscovery(8000L);
        check("the flag keeps it enforced even where a server said nothing",
                !Hooks.isAllowedTextureDomain("https://never-heard-of-it.example/tex/1"), null);
        System.clearProperty("Loki.strict_texture_domains");

        System.out.println();
        System.out.println("== disconnecting, rather than moving to another server ==");
        Object connection = join("127.0.0.1", declaring.port());
        ProfileRedirect.awaitDiscovery(8000L);
        check("on the server, its API is in use", ProfileRedirect.sessionBase() != null, null);
        check("and its key is trusted",
                org.unmojang.loki.hooks.ProfileKeys.isSignatureValid("x", someValue, signedByDeclared),
                null);

        disconnect(connection);
        check("quitting to the menu gives the configured API back",
                ProfileRedirect.sessionBase() == null, ProfileRedirect.sessionBase());
        check("its signing key is not trusted from the menu",
                !org.unmojang.loki.hooks.ProfileKeys.isSignatureValid("x", someValue, signedByDeclared),
                null);
        check("nor is its CDN still allowed",
                !Hooks.isAllowedTextureDomain("https://cdn-declared.example/tex/1"), null);
        check("and the client's own domains are back",
                Hooks.isAllowedTextureDomain("https://cdn-primary.example/tex/1"), null);

        // The old connection is torn down after the new one is up more often than not, and a close
        // that undoes the arrival it arrived after would leave the player on a server whose profiles
        // the client has just stopped resolving.
        Object leaving = join("127.0.0.1", silent.port());
        ProfileRedirect.awaitDiscovery(8000L);
        Object arriving = join("127.0.0.1", declaring.port());
        ProfileRedirect.awaitDiscovery(8000L);
        disconnect(leaving);
        check("a late close from the server just left does not undo the new one",
                ProfileRedirect.sessionBase() != null, ProfileRedirect.sessionBase());
        disconnect(arriving);

        System.out.println();
        System.out.println("== a client with checking off, on a server that enforces ==");
        System.clearProperty("Loki.enforce_secure_profile");
        String nonsense = "this is not a signature";
        check("with checking off, a signature is not looked at at all",
                org.unmojang.loki.hooks.ProfileKeys.isSignatureValid("x", someValue, nonsense), null);

        McServer enforcing = new McServer("{\"version\":{\"name\":\"1.21.1\",\"protocol\":767},"
                + "\"players\":{\"max\":20,\"online\":1},\"description\":\"test\",\"loki\":{"
                + "\"session\":\"" + declared.base() + "/authlib-injector/sessionserver\","
                + "\"services\":\"" + declared.base() + "/authlib-injector/minecraftservices\","
                + "\"enforceSecureProfile\":true}}");
        enforcing.start();
        join("127.0.0.1", enforcing.port());
        ProfileRedirect.awaitDiscovery(8000L);
        check("a server that enforces turns it on for the visit",
                !org.unmojang.loki.hooks.ProfileKeys.isSignatureValid("x", someValue, nonsense), null);
        check("and what its own API server signed verifies, so it is usable while it is on",
                org.unmojang.loki.hooks.ProfileKeys.isSignatureValid("x", someValue, signedByDeclared),
                null);

        join("127.0.0.1", silent.port());
        ProfileRedirect.awaitDiscovery(8000L);
        check("leaving puts the client back on the setting it chose",
                org.unmojang.loki.hooks.ProfileKeys.isSignatureValid("x", someValue, nonsense), null);

        // A server can only ever make a client stricter, and only a client can refuse that
        System.setProperty("Loki.ignore_declared_secure_profile", "true");
        join("127.0.0.1", enforcing.port());
        ProfileRedirect.awaitDiscovery(8000L);
        check("a client that would rather decide for itself is not made to check",
                org.unmojang.loki.hooks.ProfileKeys.isSignatureValid("x", someValue, nonsense), null);
        System.clearProperty("Loki.ignore_declared_secure_profile");
        System.setProperty("Loki.enforce_secure_profile", "true");
        check("and a client that always checks keeps checking, whatever a server says",
                !org.unmojang.loki.hooks.ProfileKeys.isSignatureValid("x", someValue, nonsense), null);

        System.out.println();
        System.out.println("== a server declaring a cleartext public API ==");
        System.setProperty("minecraft.api.session.host", "https://real.example.com/sessionserver");
        join("127.0.0.1", cleartext.port());
        ProfileRedirect.awaitDiscovery(8000L);
        // Honoured, as a configured cleartext API server is. What travels over it is profile reads,
        // and a request carrying credentials is refused whatever the transport.
        check("a cleartext declaration is taken as given", ProfileRedirect.sessionBase() != null
                && ProfileRedirect.sessionBase().startsWith("http://cleartext.example"),
                ProfileRedirect.sessionBase());

        primary.server.stop(0);
        declared.server.stop(0);

        System.out.println();
        System.out.println(failures == 0 ? "ProfileRedirectTest: PASSED" : "ProfileRedirectTest: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
