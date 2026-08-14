import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Fake;
import org.unmojang.loki.RequestInterceptor;
import org.unmojang.loki.hooks.Hooks;
import org.unmojang.loki.hooks.ProfileRedirect;
import org.unmojang.loki.transformers.NettyConnectTransformer;
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

        Api(final String tag, final String prefix, final boolean withExtraProperty) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();
            server.createContext("/", new HttpHandler() {
                public void handle(HttpExchange exchange) throws IOException {
                    String path = exchange.getRequestURI().getPath();
                    hits.add(path);
                    String body;
                    if (path.equals(prefix)) { // authlib-injector root
                        body = "{\"meta\":{\"serverName\":\"" + tag + "\"},"
                                + "\"skinDomains\":[\"cdn-" + tag + ".example\"]}";
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

    private static String status(String declaration) {
        return "{\"version\":{\"name\":\"1.21.1\",\"protocol\":767},"
                + "\"players\":{\"max\":20,\"online\":1},\"description\":\"test\""
                + (declaration == null ? "" : ",\"loki\":{\"profileApi\":\"" + declaration + "\"}") + "}";
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

    public static void main(String[] args) throws Exception {
        Api primary = new Api("primary", "/ali", false);
        Api declared = new Api("declared", "/authlib-injector", true);

        // Must land before RequestInterceptor's static initialiser reads them
        System.setProperty("minecraft.api.env", "custom");
        System.setProperty("minecraft.api.session.host", primary.base() + "/ali/sessionserver");
        System.setProperty("minecraft.api.account.host", primary.base() + "/ali/api");
        System.setProperty("minecraft.api.profiles.host", primary.base() + "/ali/api");
        System.setProperty("minecraft.api.services.host", primary.base() + "/ali/minecraftservices");
        System.setProperty("Loki.texture_domains", "cdn-primary.example");

        McServer declaring = new McServer(status(declared.base() + "/authlib-injector"));
        McServer silent = new McServer(status(null));
        McServer downgrading = new McServer(status("http://evil.example.com/authlib-injector"));
        declaring.start();
        silent.start();
        downgrading.start();

        prepareBootstrap(args[0]);
        RequestInterceptor.setURLFactory();

        String profileUrl = "http://sessionserver.mojang.com/session/minecraft/profile/abc";

        System.out.println();
        System.out.println("== before connecting anywhere ==");
        check("profile read served by the configured API server",
                fetch(profileUrl).contains("primary-textures"), null);

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
        check("profile read back on the configured API server",
                fetch(profileUrl).contains("primary-textures"), null);

        System.out.println();
        System.out.println("== transferred onto a server that declares one ==");
        handshake("127.0.0.1", declaring.port(), Protocol.STATE_TRANSFER);
        ProfileRedirect.awaitDiscovery(8000L);
        check("a transfer counts as arriving", ProfileRedirect.sessionBase() != null
                && ProfileRedirect.sessionBase().startsWith(declared.base()), ProfileRedirect.sessionBase());

        System.out.println();
        System.out.println("== a server declaring a cleartext public API ==");
        System.setProperty("minecraft.api.session.host", "https://real.example.com/sessionserver");
        join("127.0.0.1", downgrading.port());
        ProfileRedirect.awaitDiscovery(8000L);
        check("cleartext downgrade refused", ProfileRedirect.sessionBase() == null, null);

        primary.server.stop(0);
        declared.server.stop(0);

        System.out.println();
        System.out.println(failures == 0 ? "ProfileRedirectTest: PASSED" : "ProfileRedirectTest: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
