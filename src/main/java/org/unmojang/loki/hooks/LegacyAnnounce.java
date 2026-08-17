package org.unmojang.loki.hooks;

import org.unmojang.loki.util.LegacyProtocol;
import org.unmojang.loki.util.LegacyStreams;

import org.unmojang.loki.util.logger.NilLogger;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class LegacyAnnounce {
    private static final NilLogger log = NilLogger.get("Loki");

    private static final Map<Object, Boolean> accepted =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static final Map<Object, LegacyStreams.Marked> marks =
            Collections.synchronizedMap(new WeakHashMap<Object, LegacyStreams.Marked>());

    private LegacyAnnounce() {}

    private static boolean disabled() {
        return Boolean.getBoolean("Loki.disable_legacy_announce");
    }

    public static Object accepted(Object socket) {
        if (socket != null && !disabled()) {
            accepted.put(socket, Boolean.TRUE);
            nowServing(1);
        }
        return socket;
    }

    private static int servingCount;

    private static void nowServing(int change) {
        synchronized (accepted) {
            servingCount += change;
            if (servingCount < 0) servingCount = 0;
        }
        System.setProperty(SERVING, servingCount > 0 ? "true" : "false");
    }

    public static final String SERVING = "Loki.serving";

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

    public static InputStream wrapInput(InputStream in, final java.net.Socket socket) {
        if (in == null || disabled()) return in;
        try {
            if (isServer(socket)) {
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
            InputStream stripped = LegacyStreams.stripAfter(in, LegacyStreams.constant(0),
                    LegacyProtocol.PLUGIN_MESSAGE.firstByte(), sink, LegacyProtocol.PLUGIN_MESSAGE);
            stripped = LegacyStreams.stripAfter(stripped, LegacyStreams.constant(0),
                    LegacyProtocol.RAW.firstByte(), sink, LegacyProtocol.RAW);
            stripped = LegacyStreams.stripAfter(stripped, LegacyStreams.afterHandshake(),
                    LegacyProtocol.HANDSHAKE, sink, LegacyProtocol.RAW);
            return restoreOnClose(stripped, socket, declared);
        } catch (Throwable t) {
            log.debug("Not filtering this socket's input (" + t + ")");
            return in;
        }
    }

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

    private static final Map<Object, org.unmojang.loki.util.ClassicChannels.Conn> channels =
            Collections.synchronizedMap(
                    new WeakHashMap<Object, org.unmojang.loki.util.ClassicChannels.Conn>());

    private static org.unmojang.loki.util.ClassicChannels.Conn connOf(Object channel) {
        synchronized (channels) {
            org.unmojang.loki.util.ClassicChannels.Conn conn = channels.get(channel);
            if (conn == null) {
                conn = new org.unmojang.loki.util.ClassicChannels.Conn();
                channels.put(channel, conn);
            }
            return conn;
        }
    }

    public static Object acceptedChannel(Object channel) {
        if (channel != null && !disabled()) {
            connOf(channel).server = true;
            nowServing(1);
        }
        return channel;
    }

    public static void beforeChannelWrite(final Object channel, java.nio.ByteBuffer src) {
        if (channel == null || src == null || disabled()) return;
        try {
            org.unmojang.loki.util.ClassicChannels.beforeWrite(connOf(channel), src,
                    new org.unmojang.loki.util.ClassicChannels.Writer() {
                        public String declaration() {
                            return LegacyAnnounce.declaration();
                        }

                        public void write(java.nio.ByteBuffer block) {
                            try {
                                ((java.nio.channels.SocketChannel) channel).write(block);
                            } catch (Throwable t) {
                                log.debug("Could not send the declaration on this channel (" + t + ")");
                            }
                        }
                    });
        } catch (Throwable t) {
            log.debug("Not filtering this channel's writes (" + t + ")");
        }
    }

    public static int afterChannelWrite(int written, Object channel, java.nio.ByteBuffer src) {
        if (channel == null || src == null || disabled()) return written;
        try {
            return org.unmojang.loki.util.ClassicChannels.afterWrite(connOf(channel), src, written);
        } catch (Throwable t) {
            log.debug("Not accounting for this channel's writes (" + t + ")");
            return written;
        }
    }

    public static int afterChannelRead(int read, Object channel, java.nio.ByteBuffer dst) {
        if (channel == null || dst == null || disabled()) return read;
        try {
            org.unmojang.loki.util.ClassicChannels.Conn conn = connOf(channel);
            int given = org.unmojang.loki.util.ClassicChannels.afterRead(conn, dst, read);
            if (conn.declaration != null) {
                String declaration = conn.declaration;
                conn.declaration = null;
                log.info("Server declared where profiles come from: " + declaration);
                announceChannel(channel, declaration);
            }
            return given;
        } catch (Throwable t) {
            log.debug("Not filtering this channel's reads (" + t + ")");
            return read;
        }
    }

    public static void closingChannel(Object channel) {
        if (channel == null || disabled()) return;
        org.unmojang.loki.util.ClassicChannels.Conn conn;
        synchronized (channels) {
            conn = channels.remove(channel);
        }
        if (conn == null) return;
        if (conn.server) {
            nowServing(-1);
            return;
        }
        if (!conn.declaredHere) return;

        try {
            java.net.Socket socket = ((java.nio.channels.SocketChannel) channel).socket();
            if (socket != null) closing(socket);
        } catch (Throwable t) {
            log.debug("Could not tell whether that channel was the current server (" + t + ")");
        }
    }

    private static void announceChannel(Object channel, String declaration) {
        try {
            java.net.Socket socket = ((java.nio.channels.SocketChannel) channel).socket();
            if (socket != null) announce(socket, declaration);
        } catch (Throwable t) {
            log.debug("Could not apply what the server declared (" + t + ")");
        }
    }

    public static void closing(java.net.Socket socket) {
        if (socket == null || disabled()) return;
        if (accepted.remove(socket) != null) {
            nowServing(-1);
            return;
        }
        try {
            java.net.InetAddress address = socket.getInetAddress();
            if (address != null) leave(address.getHostAddress(), socket.getPort());
        } catch (Throwable t) {
            log.debug("Could not tell whether that connection was the current server (" + t + ")");
        }
    }

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

    private static void announce(java.net.Socket socket, String declaration) {
        try {
            java.net.InetAddress address = socket.getInetAddress();
            if (address == null) return;
            Class.forName("org.unmojang.loki.hooks.ProfileRedirect", true,
                            ClassLoader.getSystemClassLoader())
                    .getMethod("noteDeclaredJson", String.class, int.class, String.class)
                    .invoke(null, address.getHostAddress(), Integer.valueOf(socket.getPort()), declaration);
        } catch (Throwable t) {
            log.debug("Could not apply what the server declared (" + t + ")");
        }
    }

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
                        LegacyProtocol.ENCRYPTION_REQUEST, source, markOf(socket),
                        LegacyProtocol.PLUGIN_MESSAGE);
                appended = LegacyStreams.appendAfter(appended, LegacyStreams.constant(0),
                        LegacyProtocol.LOGIN, source, markOf(socket), LegacyProtocol.RAW);
                return LegacyStreams.appendAfter(appended, LegacyStreams.afterHandshake(),
                        LegacyProtocol.HANDSHAKE, source, markOf(socket), LegacyProtocol.RAW);
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
