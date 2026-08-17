package org.unmojang.loki.util;

import org.unmojang.loki.util.logger.NilLogger;

import java.nio.ByteBuffer;

public final class ClassicChannels {
    public static final class Conn {
        public boolean server;
        boolean applicable = true;

        int written;
        int read;

        boolean marked;
        boolean blockSent;
        ByteBuffer pending;
        int narrowedTo = -1;

        byte[] held;
        int heldLength;
        boolean decided;
        boolean sending;

        int shape = UNKNOWN;
        LegacyProtocol.Handshake handshake = new LegacyProtocol.Handshake();
        public String declaration;
        public boolean declaredHere;

        public boolean isMarked() {
            return marked;
        }

        public int heldLength() {
            return heldLength;
        }
    }

    private static final NilLogger log = NilLogger.get("Loki");

    public static final int UNKNOWN = 0;
    public static final int CLASSIC = 1;
    public static final int MODERN = 2;

    private static final int MARKER_AT = LegacyProtocol.CLASSIC_MARKER_OFFSET;

    private ClassicChannels() {}

    public static boolean beforeWrite(Conn conn, ByteBuffer src, Writer writer) {
        if (conn.sending || !conn.applicable || !src.hasRemaining()) return false;

        if (conn.server) return serverWrite(conn, src, writer);

        if (conn.written == 0 && src.get(src.position()) != LegacyProtocol.CLASSIC_IDENTIFICATION) {
            conn.applicable = false;
            return false;
        }

        int ahead = MARKER_AT - conn.written;
        if (ahead >= 0 && ahead < src.remaining()) {
            int index = src.position() + ahead;
            if (src.get(index) == 0) src.put(index, LegacyProtocol.MARKER);
        }
        return false;
    }

    private static boolean serverWrite(Conn conn, ByteBuffer src, Writer writer) {
        if (conn.blockSent) return false;
        if (!conn.marked) {
            conn.blockSent = true;
            log.debug("Nothing to declare to this Classic client: it did not mark itself");
            return false;
        }
        if (conn.pending == null) {
            LegacyProtocol.Block framing = conn.shape == MODERN
                    ? LegacyProtocol.PLUGIN_MESSAGE : LegacyProtocol.RAW;
            byte[] block = framing.frame(writer.declaration());
            if (block == null) {
                conn.blockSent = true;
                return false;
            }
            conn.pending = ByteBuffer.wrap(block);
            log.debug("Declaring to a client that marked itself, on a connection with no streams");
        }

        conn.sending = true;
        try {
            writer.write(conn.pending);
        } finally {
            conn.sending = false;
        }
        if (!conn.pending.hasRemaining()) {
            conn.blockSent = true;
            conn.pending = null;
            return false;
        }

        conn.narrowedTo = src.limit();
        src.limit(src.position());
        return true;
    }

    public static int afterWrite(Conn conn, ByteBuffer src, int written) {
        if (conn.sending) return written;
        if (conn.narrowedTo >= 0) {
            src.limit(conn.narrowedTo);
            conn.narrowedTo = -1;
        }
        if (written > 0) conn.written += written;
        return written;
    }

    public interface Writer {
        String declaration();

        void write(ByteBuffer block);
    }

    public static int afterRead(Conn conn, ByteBuffer dst, int read) {
        if (read <= 0 || !conn.applicable) return read;
        int from = dst.position() - read;

        if (conn.server) {
            if (conn.read == 0) {
                byte first = dst.get(from);
                if (first == LegacyProtocol.CLASSIC_IDENTIFICATION) {
                    conn.shape = CLASSIC;
                } else if (first == LegacyProtocol.HANDSHAKE) {
                    conn.shape = MODERN;
                } else {
                    conn.applicable = false;
                    return read;
                }
            }

            if (conn.shape == MODERN) {
                serverReadModern(conn, dst, read, from);
                conn.read += read;
                return read;
            }

            int ahead = MARKER_AT - conn.read;
            if (ahead >= 0 && ahead < read && dst.get(from + ahead) == LegacyProtocol.MARKER) {
                conn.marked = true;
                log.debug("A Classic client marked itself on this connection");
            }
            conn.read += read;
            return read;
        }

        if (conn.decided && conn.heldLength == 0) return read;
        return clientRead(conn, dst, read, from);
    }

    private static void serverReadModern(Conn conn, ByteBuffer dst, int read, int from) {
        if (conn.handshake == null) return;

        byte[] arrived = new byte[read];
        for (int i = 0; i < read; i++) arrived[i] = dst.get(from + i);

        int end = conn.handshake.add(arrived, 0, read);
        if (end == LegacyProtocol.NOT_THIS_SHAPE) {
            conn.handshake = null;
            return;
        }
        if (end < 0 || conn.handshake.gathered() < end) return;

        if (LegacyProtocol.hasHostMarker(conn.handshake.packet(), end)) {
            conn.marked = true;
            log.debug("A 1.3 to 1.6.4 client marked itself on this connection");
        }
        conn.handshake = null;
    }

    private static int clientRead(Conn conn, ByteBuffer dst, int read, int from) {
        int total = conn.heldLength + read;
        byte[] all = new byte[total];
        if (conn.heldLength > 0) System.arraycopy(conn.held, 0, all, 0, conn.heldLength);
        for (int i = 0; i < read; i++) all[conn.heldLength + i] = dst.get(from + i);
        conn.held = null;
        conn.heldLength = 0;

        int at = 0;
        LegacyProtocol.Block framing = conn.decided ? null : LegacyProtocol.blockStartingWith(all[0]);
        if (!conn.decided && framing == null) {
            conn.decided = true;
        } else if (framing != null) {
            int body = total < framing.headerBytes() ? -1 : framing.bodyLength(all, total);
            if (body < 0 || total < framing.headerBytes() + body) {
                if (total > framing.headerBytes() + LegacyProtocol.PAYLOAD_MAX_BYTES) {
                    conn.decided = true;
                } else {
                    hold(conn, all, 0, total);
                    dst.position(from);
                    return 0;
                }
            } else {
                conn.declaration = text(all, framing.headerBytes(), body);
                conn.declaredHere = conn.declaration != null;
                conn.decided = true;
                at = framing.headerBytes() + body;
            }
        }
        return deliver(conn, dst, from, all, at, total - at);
    }

    private static String text(byte[] bytes, int at, int length) {
        try {
            return new String(bytes, at, length, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void hold(Conn conn, byte[] all, int at, int length) {
        conn.held = new byte[length];
        System.arraycopy(all, at, conn.held, 0, length);
        conn.heldLength = length;
    }

    private static int deliver(Conn conn, ByteBuffer dst, int from, byte[] all, int at, int length) {
        int now = Math.min(length, dst.limit() - from);
        for (int i = 0; i < now; i++) dst.put(from + i, all[at + i]);
        dst.position(from + now);
        if (length > now) hold(conn, all, at + now, length - now);
        return now;
    }
}
