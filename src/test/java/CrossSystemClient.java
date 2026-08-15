import org.unmojang.loki.hooks.ProfileKeys;
import org.unmojang.loki.hooks.ProfileRedirect;
import org.unmojang.loki.util.NettyBridge;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * One client, on one API server, joining a server that runs on another.
 * <p>
 * This is the half of the feature that {@code real-server-test.sh} cannot see. That one checks that
 * a server declares its API server; this attaches Loki as a <em>client</em>, configured wherever the
 * caller says, joins that server for real, and asks what the client now does about profiles, keys
 * and texture domains — and, just as importantly, what it does not do about credentials.
 * <p>
 * There is no Minecraft here, and there does not need to be. Everything under test hangs off
 * {@code Bootstrap.connect} and the handshake that follows it, both of which are the client's, not
 * the game's. Netty comes out of the version's own jar so each era is driven by the Netty it
 * shipped with, and the agent is attached the way a launcher would attach it, so the transformers
 * that run are the ones an operator gets.
 *
 * <h2>What a run proves</h2>
 * <ul>
 *   <li>the server's declaration reached this client, and moved profile reads to it</li>
 *   <li>a profile that exists only on that API server resolves, which is the whole point</li>
 *   <li>its signature verifies, including when it was signed with a key other than the first one
 *       published — the case a single-key client renders as Steve</li>
 *   <li>authenticated calls stayed on this client's own API server, token and all</li>
 *   <li>leaving puts every one of those back</li>
 * </ul>
 *
 * <h2>Exit codes</h2>
 * 0 when everything held, 1 when something did not, 3 when this jar has no Netty this can drive,
 * which is a fact about the jar rather than a fault in Loki.
 *
 * @param args serverJar, host, port, label, the API root the server was pointed at, then optionally
 *             a UUID to look up on it and a bearer token for this client's own API server ("-" for
 *             either when there is none)
 */
public class CrossSystemClient {
    private static final int TIMEOUT_MS = 15000;
    private static final long DISCOVERY_WAIT_MS = 20000L;
    private static final long LEAVE_WAIT_MS = 5000L;

    private static int failures = 0;

    private static void check(String label, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "    ok   " : "    FAIL ") + label
                + (detail == null || detail.length() == 0 ? "" : "  [" + detail + "]"));
    }

    private static void note(String message) {
        System.out.println("    --   " + message);
    }

    /** A GET through whatever URL machinery Loki has installed, which is the point of doing it. */
    private static String[] get(String url, String bearer) {
        HttpURLConnection conn = null;
        try {
            // Through a URI rather than new URL(String), which is deprecated from Java 20 and would
            // put a warning in every run of this on the newer versions
            conn = (HttpURLConnection) new java.net.URI(url).toURL().openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
            int status = conn.getResponseCode();
            InputStream stream = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = stream == null ? "" : new String(NettyRig.read(stream), "UTF-8");
            return new String[]{String.valueOf(status), body};
        } catch (Exception e) {
            return new String[]{"-1", String.valueOf(e)};
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** The value of a JSON string field, without dragging a parser in for six of them. */
    private static String field(String json, String name) {
        String needle = "\"" + name + "\"";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) return null;
        int open = json.indexOf('"', colon);
        if (open < 0) return null;
        int close = json.indexOf('"', open + 1);
        if (close < 0) return null;
        return json.substring(open + 1, close);
    }

    /** How many keys one of the two arrays in a publickeys document holds. */
    private static int keysIn(String json, String field) {
        int from = json.indexOf("\"" + field + "\"");
        if (from < 0) return 0;
        int to = json.indexOf(']', from);
        if (to < 0) to = json.length();

        int found = 0;
        for (int at = json.indexOf("\"publicKey\"", from);
             at >= 0 && at < to;
             at = json.indexOf("\"publicKey\"", at + 1)) {
            found++;
        }
        return found;
    }

    /**
     * The protocol number this server speaks, out of its own status response.
     * <p>
     * Not a constant and not a table: a modern server checks the version in the handshake itself and
     * closes the connection on the spot if it does not like it, so a wrong number here does not
     * produce a failed login, it produces a client that was never on the server long enough to be
     * asked anything. Asking the server is the one way that stays right as versions come out.
     */
    private static int protocolOf(String host, int port) {
        try {
            String status = org.unmojang.loki.util.ServerListPing.statusJson(host, port, TIMEOUT_MS);
            int at = status.indexOf("\"protocol\"");
            if (at < 0) return -1;
            int digits = at;
            while (digits < status.length() && !Character.isDigit(status.charAt(digits))) digits++;
            int end = digits;
            while (end < status.length() && Character.isDigit(status.charAt(end))) end++;
            return end > digits ? Integer.parseInt(status.substring(digits, end)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static byte[] handshake(String host, int port, int protocol, int nextState) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(payload);
        writeVarInt(packet, 0x00);        // handshake
        writeVarInt(packet, protocol);
        byte[] address = host.getBytes("UTF-8");
        writeVarInt(packet, address.length);
        packet.write(address);
        packet.writeShort(port);
        writeVarInt(packet, nextState);
        packet.flush();

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream framed = new DataOutputStream(frame);
        writeVarInt(framed, payload.size());
        framed.write(payload.toByteArray());
        framed.flush();
        return frame.toByteArray();
    }

    private static void writeVarInt(DataOutputStream out, int value) throws Exception {
        while ((value & 0xFFFFFF80) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    /** Everything needed to drive one connection, kept together so it can be torn down again. */
    private static final class Connection {
        Object group;
        Object channel;
    }

    /**
     * Joins the way the game joins: connect through Netty's own {@code Bootstrap}, then write the
     * handshake that says this is a login rather than a ping.
     */
    private static Connection join(ClassLoader netty, String host, int port, int protocol) throws Exception {
        Class<?> bootstrapClass = netty.loadClass("io.netty.bootstrap.Bootstrap");
        Class<?> groupClass = netty.loadClass("io.netty.channel.EventLoopGroup");
        Class<?> handlerClass = netty.loadClass("io.netty.channel.ChannelHandler");
        Class<?> pipelineClass = netty.loadClass("io.netty.channel.ChannelPipeline");

        Connection connection = new Connection();
        connection.group = netty.loadClass("io.netty.channel.nio.NioEventLoopGroup")
                .getConstructor().newInstance();

        Object bootstrap = bootstrapClass.getConstructor().newInstance();
        bootstrapClass.getMethod("group", groupClass).invoke(bootstrap, connection.group);
        bootstrapClass.getMethod("channel", Class.class).invoke(bootstrap,
                netty.loadClass("io.netty.channel.socket.nio.NioSocketChannel"));
        // A client bootstrap insists on a handler, and this one has nothing to do with the packets
        bootstrapClass.getMethod("handler", handlerClass).invoke(bootstrap,
                NettyBridge.newHandler(pipelineClass, new String[]{"ChannelInboundHandler"},
                        new NettyBridge.PeekAdapter() {}));

        Object future = bootstrapClass.getMethod("connect", SocketAddress.class)
                .invoke(bootstrap, new InetSocketAddress(host, port));
        NettyBridge.call(future, "sync", new Object[0]);
        connection.channel = NettyBridge.call(future, "channel", new Object[0]);

        Object unpooled = netty.loadClass("io.netty.buffer.Unpooled")
                .getMethod("wrappedBuffer", byte[].class)
                .invoke(null, (Object) handshake(host, port, protocol, 2));
        // Waited on, because the write travels the pipeline on the event loop: without this the
        // handshake has not reached the watcher yet, and there is no discovery to wait for
        Object written = NettyBridge.call(connection.channel, "writeAndFlush", new Object[]{unpooled});
        NettyBridge.call(written, "sync", new Object[0]);
        return connection;
    }

    private static void leave(Connection connection) throws Exception {
        NettyBridge.call(connection.channel, "close", new Object[0]);
        // The close future completes on the event loop, so the client is not back on its own API
        // server the instant close() returns
        long until = System.currentTimeMillis() + LEAVE_WAIT_MS;
        while (ProfileRedirect.sessionBase() != null && System.currentTimeMillis() < until) {
            Thread.sleep(50L);
        }
        NettyBridge.call(connection.group, "shutdownGracefully", new Object[0]);
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.println("  CrossSystemClient: CRASHED");
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        File serverJar = new File(args[0]);
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        String label = args[3];
        String declaredRoot = args[4];
        String probeUuid = args.length > 5 && !"-".equals(args[5]) ? args[5] : null;
        String token = args.length > 6 && !"-".equals(args[6]) ? args[6] : null;

        String ownSession = System.getProperty("minecraft.api.session.host", "");
        String ownServices = System.getProperty("minecraft.api.services.host", "");
        boolean ownIsDeclared = ownSession.startsWith(declaredRoot);

        System.out.println("  -- client on " + label + " --");
        note("its own session API: " + (ownSession.length() == 0 ? "(Mojang's default)" : ownSession));

        // Without this every signature check answers true without looking, and half of what follows
        // would pass while proving nothing. Better to refuse to run than to report that.
        if (!Boolean.getBoolean("Loki.enforce_secure_profile")) {
            System.out.println("    FAIL run this with -DLoki.enforce_secure_profile=true, "
                    + "or the key checks pass without verifying anything");
            System.exit(1);
        }

        URL[] netty = NettyRig.nettyOf(serverJar);
        if (netty.length == 0) {
            System.out.println("    skipped: no Netty in " + serverJar.getName());
            System.exit(3);
        }
        // A plain loader: the agent is attached, so the transformers see these classes as they load,
        // exactly as they see the game's
        URLClassLoader loader = new URLClassLoader(netty, CrossSystemClient.class.getClassLoader());

        check("nothing is redirected before joining anything",
                ProfileRedirect.sessionBase() == null, ProfileRedirect.sessionBase());

        int protocol = protocolOf(host, port);
        check("the server answers a ping, and says which protocol it speaks", protocol > 0,
                String.valueOf(protocol));
        Connection connection = join(loader, host, port, protocol);
        ProfileRedirect.awaitDiscovery(DISCOVERY_WAIT_MS);

        String session = ProfileRedirect.sessionBase();
        check("the server's declaration arrived", session != null, null);
        if (session == null) {
            System.out.println("  CrossSystemClient[" + label + "]: " + failures + " FAILED");
            System.exit(1);
        }
        check("and names the API server it was pointed at", session.startsWith(declaredRoot), session);

        // Profile reads move; everything else is supposed to stay where it was. Nothing to redirect
        // is said with a null, so these are not "somewhere else" but "not our business".
        String lookup = ProfileRedirect.baseFor("sessionserver.mojang.com", "/session/minecraft/profile/x");
        check("profile reads go to the declared API server",
                lookup != null && lookup.startsWith(declaredRoot), lookup);
        check("joining a server does not",
                ProfileRedirect.baseFor("sessionserver.mojang.com", "/session/minecraft/join") == null,
                ProfileRedirect.baseFor("sessionserver.mojang.com", "/session/minecraft/join"));
        check("nor does the authenticated own-profile endpoint",
                ProfileRedirect.baseFor("api.minecraftservices.com", "/minecraft/profile") == null,
                ProfileRedirect.baseFor("api.minecraftservices.com", "/minecraft/profile"));

        // Asked of the endpoint ProfileKeys itself asks, rather than of a root guessed back out of
        // the session URL, which a server that named its endpoints outright need not even have
        String services = ProfileRedirect.servicesBase();
        if (services != null) {
            String[] published = get(services + "/publickeys", null);
            if ("200".equals(published[0])) {
                note("the declared API server publishes "
                        + keysIn(published[1], "profilePropertyKeys") + " property and "
                        + keysIn(published[1], "playerCertificateKeys") + " certificate key(s)");
            }
        }

        String signedValue = null;
        String signedSignature = null;
        if (probeUuid != null) {
            // Through the hostname a mod or a legacy client would use, so the URL machinery is in
            // the path rather than assumed out of it
            String[] profile = get("http://sessionserver.mojang.com/session/minecraft/profile/"
                    + probeUuid + "?unsigned=false", null);
            check("a profile from that API server resolves", "200".equals(profile[0]),
                    profile[0] + " " + profile[1]);

            if ("200".equals(profile[0])) {
                signedValue = field(profile[1], "value");
                signedSignature = field(profile[1], "signature");
                check("it came back signed", signedSignature != null, null);
                if (signedSignature != null) {
                    check("its signature verifies against the keys that server publishes",
                            ProfileKeys.isSignatureValid("x", signedValue, signedSignature), null);
                    check("a tampered value does not",
                            !ProfileKeys.isSignatureValid("x", signedValue + "x", signedSignature), null);
                }
            }
        }

        if (token != null) {
            String[] own = get("http://api.minecraftservices.com/minecraft/profile", token);
            check("this client's own token still works, so the credential stayed home",
                    "200".equals(own[0]), own[0] + " " + own[1]);
        }

        String texturesDomains = System.getProperty(ProfileRedirect.PROP_TEXTURE_DOMAINS, "");
        note("texture domains while connected: " + (texturesDomains.length() == 0
                ? "(any, since none were declared)" : texturesDomains));

        // Everything above was meant to be true *while on the server*. If the server hung up in the
        // middle of it, the client was right to put its own API server back and the run says so
        // here, rather than leaving a pile of confusing failures above to be read backwards.
        check("the server kept the connection up while all that was checked",
                Boolean.TRUE.equals(NettyBridge.call(connection.channel, "isActive", new Object[0])),
                null);

        leave(connection);

        check("leaving puts profile reads back", ProfileRedirect.sessionBase() == null,
                ProfileRedirect.sessionBase());
        if (probeUuid != null && !ownIsDeclared) {
            String[] afterwards = get("http://sessionserver.mojang.com/session/minecraft/profile/"
                    + probeUuid, null);
            check("and that profile is no longer resolvable from here", !"200".equals(afterwards[0]),
                    afterwards[0]);
        }
        // The whole bargain in one assertion: a property that server really signed, checked again
        // from the menu. It stops verifying unless that API server was this client's own all along,
        // in which case nothing was ever borrowed and nothing is given back.
        if (signedSignature != null) {
            boolean stillValid = ProfileKeys.isSignatureValid("x", signedValue, signedSignature);
            check(ownIsDeclared
                            ? "its signatures keep verifying, being this client's own API server"
                            : "its signatures stop verifying, the trust having been for the visit",
                    ownIsDeclared == stillValid, null);
        }

        System.out.println(failures == 0
                ? "  CrossSystemClient[" + label + "]: PASSED"
                : "  CrossSystemClient[" + label + "]: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
