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
                // Both, nested, rather than a guess about which era is connecting: each stands
                // itself down on a handshake of the shape the other reads, so whichever arrives
                // meets exactly one filter that is still listening.
                return LegacyStreams.watchModernHandshake(
                        LegacyStreams.watchBetaLogin(in, markOf(socket)), markOf(socket));
            }
            final boolean[] declared = new boolean[1];
            LegacyStreams.Sink sink = new LegacyStreams.Sink() {
                public void declared(String declaration) {
                    log.info("Server declared where profiles come from: " + declaration);
                    declared[0] = true;
                    announce(socket, declaration);
                }
            };
            // From 1.3 the block is the first thing a server writes, because by then it has read
            // the handshake the marker is in; before that it can only come after the handshake
            // reply, which is written before the login packet carrying the marker has been read.
            InputStream stripped = LegacyStreams.stripAfter(in, LegacyStreams.constant(0),
                    LegacyProtocol.PAYLOAD_MAGIC[0], sink);
            stripped = LegacyStreams.stripAfter(stripped, LegacyStreams.afterHandshake(),
                    LegacyProtocol.HANDSHAKE, sink);
            return restoreOnClose(stripped, socket, declared);
        } catch (Throwable t) {
            log.debug("Not filtering this socket's input (" + t + ")");
            return in;
        }
    }

    /**
     * Puts the configured API back when the connection ends.
     * <p>
     * An override is meant to last exactly as long as the visit, and on 1.7 and up the game says
     * when that is over. Down here nothing does: the only thing that happens when a player is
     * kicked, disconnects or closes the game is that this socket stops. So that is what is watched
     * — the end of the stream and its close, whichever comes first — and either one restores.
     * <p>
     * The address is taken now rather than then, because a closed socket is not obliged to remember
     * who it was talking to. Restoring is left to {@code noteLeave}, which does nothing unless this
     * is still the server whose declaration is in force, so a stale close cannot undo a newer visit.
     */
    private static InputStream restoreOnClose(InputStream in, java.net.Socket socket,
                                              final boolean[] declared) {
        java.net.InetAddress address = socket.getInetAddress();
        if (address == null) return in;
        final String host = address.getHostAddress();
        final int port = socket.getPort();

        return new java.io.FilterInputStream(in) {
            private boolean left;

            public int read() throws java.io.IOException {
                int b = in.read();
                if (b < 0) leave();
                return b;
            }

            public int read(byte[] bytes, int at, int length) throws java.io.IOException {
                int read = in.read(bytes, at, length);
                if (read < 0) leave();
                return read;
            }

            public void close() throws java.io.IOException {
                try {
                    in.close();
                } finally {
                    leave();
                }
            }

            private void leave() {
                if (left || !declared[0]) return;
                left = true;
                LegacyAnnounce.leave(host, port);
            }
        };
    }

    /**
     * Called from {@code Socket.close}, which from 1.3 is how a visit ends.
     * <p>
     * Unconditional on purpose: whether this socket is the one that declared anything is not a
     * question worth tracking here, because {@code noteLeave} already asks the only version of it
     * that matters — is this the server whose declaration is in force. A socket to anywhere else
     * closing changes nothing.
     */
    public static void closing(java.net.Socket socket) {
        if (socket == null || disabled() || isServer(socket)) return;
        try {
            java.net.InetAddress address = socket.getInetAddress();
            if (address != null) leave(address.getHostAddress(), socket.getPort());
        } catch (Throwable t) {
            log.debug("Could not tell whether that connection was the current server (" + t + ")");
        }
    }

    /** The other half of {@link #announce}, and reached the same way and for the same reason. */
    private static void leave(String host, int port) {
        try {
            Class.forName("org.unmojang.loki.hooks.ProfileRedirect", true,
                            ClassLoader.getSystemClassLoader())
                    .getMethod("noteLeave", String.class, int.class)
                    .invoke(null, host, Integer.valueOf(port));
        } catch (Throwable t) {
            log.debug("Could not put the configured profile API back (" + t + ")");
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
                LegacyStreams.Source source = new LegacyStreams.Source() {
                    public String declaration() {
                        return LegacyAnnounce.declaration();
                    }
                };
                OutputStream appended = LegacyStreams.appendAfter(out, LegacyStreams.constant(0),
                        LegacyProtocol.ENCRYPTION_REQUEST, source, markOf(socket));
                // Until a1.0.16 there is no handshake either way, so the server's first packet is
                // its login reply and the block goes in front of it. It knows by then who it is
                // talking to, because the client's login is the first thing it read.
                appended = LegacyStreams.appendAfter(appended, LegacyStreams.constant(0),
                        LegacyProtocol.LOGIN, source, markOf(socket));
                return LegacyStreams.appendAfter(appended, LegacyStreams.afterHandshake(),
                        LegacyProtocol.HANDSHAKE, source, markOf(socket));
            }
            if (Boolean.getBoolean("Loki.no_legacy_handshake_marker")) {
                return LegacyStreams.markBetaLogin(out);
            }
            return LegacyStreams.markModernHandshake(LegacyStreams.markBetaLogin(out));
        } catch (Throwable t) {
            log.debug("Not filtering this socket's output (" + t + ")");
            return out;
        }
    }
}
