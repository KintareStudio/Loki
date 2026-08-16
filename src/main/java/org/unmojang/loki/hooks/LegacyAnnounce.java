package org.unmojang.loki.hooks;

import org.unmojang.loki.util.LegacyProtocol;
import org.unmojang.loki.util.LegacyStreams;

import org.unmojang.loki.util.logger.NilLogger;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Where the pre-1.7 announcement is decided: which end of the connection this is, and whether the
 * connection is one of ours at all.
 * <p>
 * The stream filters are installed from {@code java.net.Socket}, which means every socket in the
 * process passes through here — an HTTP request Loki itself makes as much as a game connection. So
 * the filters are built to notice, on the very first byte, that they are looking at something else
 * and step aside: a request that starts with {@code GET} is not a Classic identification packet and
 * is never touched again.
 * <p>
 * A socket handed out by {@code ServerSocket.accept} is this process acting as a server; anything
 * else is it acting as a client. That is the only role detection needed, and it does not depend on
 * knowing whether the JVM is running a game or a server.
 */
public final class LegacyAnnounce {
    private static final NilLogger log = NilLogger.get("Loki");

    /** Sockets that arrived through accept, so this end is the server on them. */
    private static final Map<Object, Boolean> accepted =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    /** Whether the client on a given accepted socket marked itself. */
    private static final Map<Object, LegacyStreams.Marked> marks =
            Collections.synchronizedMap(new WeakHashMap<Object, LegacyStreams.Marked>());

    private LegacyAnnounce() {}

    private static boolean disabled() {
        return Boolean.getBoolean("Loki.disable_legacy_announce");
    }

    /** Called from {@code ServerSocket.accept}, which is how this end learns it is the server. */
    public static Object accepted(Object socket) {
        if (socket != null && !disabled()) accepted.put(socket, Boolean.TRUE);
        return socket;
    }

    private static boolean isServer(Object socket) {
        return accepted.containsKey(socket);
    }

    private static LegacyStreams.Marked markOf(Object socket) {
        synchronized (marks) {
            LegacyStreams.Marked mark = marks.get(socket);
            if (mark == null) {
                mark = new LegacyStreams.Marked();
                marks.put(socket, mark);
            }
            return mark;
        }
    }

    /**
     * What this server tells a Loki client: the same object it would put in a status response from
     * 1.7 onwards, so that both eras declare one thing in one format.
     * <p>
     * Built through the system class loader, since this class sits on the bootstrap path and the
     * advertiser does not.
     */
    private static String declaration() {
        try {
            Object body = Class.forName("org.unmojang.loki.hooks.ProfileAdvertiser", true,
                            ClassLoader.getSystemClassLoader())
                    .getMethod("declaration").invoke(null);
            return body == null ? null : "{" + body + "}";
        } catch (Throwable t) {
            log.debug("Nothing to declare on this connection (" + t + ")");
            return null;
        }
    }

    /**
     * Called from {@code Socket.getInputStream}. On a server this watches for the client's marker;
     * on a client it takes the server's block back out before the game reads it.
     */
    public static InputStream wrapInput(InputStream in, final java.net.Socket socket) {
        if (in == null || disabled()) return in;
        try {
            if (isServer(socket)) {
                return LegacyStreams.watchBetaLogin(in, markOf(socket));
            }
            return LegacyStreams.stripAfter(in, LegacyStreams.afterHandshake(),
                    LegacyProtocol.HANDSHAKE, new LegacyStreams.Sink() {
                        public void declared(String declaration) {
                            log.info("Server declared where profiles come from: " + declaration);
                            announce(socket, declaration);
                        }
                    });
        } catch (Throwable t) {
            log.debug("Not filtering this socket's input (" + t + ")");
            return in;
        }
    }

    /**
     * Hands what a server declared to the same place the 1.7+ declaration goes, so that from here
     * on there is one override and one set of rules about what it may move.
     */
    private static void announce(java.net.Socket socket, String declaration) {
        try {
            java.net.InetAddress address = socket.getInetAddress();
            if (address == null) return; // closed under us, so there is no server to be on
            // Through the system loader on purpose: this class is on the bootstrap path, and the
            // ProfileRedirect that must hear about it is the one the game own lookups consult.
            Class.forName("org.unmojang.loki.hooks.ProfileRedirect", true,
                            ClassLoader.getSystemClassLoader())
                    .getMethod("noteDeclaredJson", String.class, int.class, String.class)
                    .invoke(null, address.getHostAddress(), Integer.valueOf(socket.getPort()), declaration);
        } catch (Throwable t) {
            log.debug("Could not apply what the server declared (" + t + ")");
        }
    }

    /**
     * Called from {@code Socket.getOutputStream}. On a client this writes the marker into the
     * identification packet; on a server it appends the block for a client that marked itself.
     */
    public static OutputStream wrapOutput(OutputStream out, java.net.Socket socket) {
        if (out == null || disabled()) return out;
        try {
            if (isServer(socket)) {
                if (declaration() == null) return out;
                return LegacyStreams.appendAfter(out, LegacyStreams.afterHandshake(),
                        LegacyProtocol.HANDSHAKE, new LegacyStreams.Source() {
                            public String declaration() {
                                return LegacyAnnounce.declaration();
                            }
                        }, markOf(socket));
            }
            return LegacyStreams.markBetaLogin(out);
        } catch (Throwable t) {
            log.debug("Not filtering this socket's output (" + t + ")");
            return out;
        }
    }
}
