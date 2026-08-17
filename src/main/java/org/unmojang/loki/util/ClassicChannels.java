package org.unmojang.loki.util;

import org.unmojang.loki.util.logger.NilLogger;

import java.nio.ByteBuffer;

/**
 * The announcement where there are no streams to filter: on the channel itself.
 * <p>
 * Two things end up here, for the same reason. Classic, because both its ends are non-blocking NIO
 * and always were. And 1.3 to 1.6.4 when a proxy is serving it, a proxy being a Netty program
 * speaking a protocol older than Netty — so the stream filters that handle that era on a real
 * server never meet its connections, and the Netty its handlers would go in may be too old to
 * accept one.
 * <p>
 * The two are told apart by their first byte: Classic opens with an identification, and 1.3 with a
 * handshake. After that only two things differ — where the marker sits, and how the block is framed
 * — and {@link LegacyProtocol} owns both of those.
 * <p>
 * The block goes in front of everything the server writes rather than after its first packet. It
 * can, on both: the client speaks first, so the marker has been read before a byte goes back.
 *
 * <h2>Non-blocking</h2>
 * Which is what most of this class is about. A write may take part of what it was given and a read
 * may hand over half a packet, so the server holds what is left of its block and refuses to let the
 * game's own bytes past it — by narrowing the buffer rather than by lying about what was written —
 * and the client withholds an incomplete block from the game rather than let it see the start of
 * one. Both report zero in the meantime, which is what a non-blocking channel says all day.
 */
public final class ClassicChannels {
    /** Everything one connection needs to remember. One of these per channel. */
    public static final class Conn {
        /** Whether this end accepted the connection, and is therefore the server on it. */
        public boolean server;
        /** Cleared for good the moment this turns out not to be a Classic connection. */
        boolean applicable = true;

        int written;
        int read;

        /** Server: the client marked itself. Client: unused. */
        boolean marked;
        /** Server: there is nothing more to send in front of the game's own bytes. */
        boolean blockSent;
        /** Server: what is left of the block after a write that could not take all of it. */
        ByteBuffer pending;
        /** Server: the limit taken off the game's buffer to stop its bytes overtaking the block. */
        int narrowedTo = -1;

        /** Client: bytes kept back from the game while the block is still arriving. */
        byte[] held;
        int heldLength;
        /** Client: whether the block question has been settled, one way or the other. */
        boolean decided;
        /** Set while the block itself is going out, so the hook does not meet its own bytes. */
        boolean sending;

        /**
         * Which of the two protocols this connection is, once its first byte has said so.
         * <p>
         * Both reach this class for the same reason: neither goes anywhere near a socket's streams.
         * Classic because it never did, and 1.3 to 1.6.4 when a proxy is serving it — a proxy being
         * a Netty program speaking a protocol older than Netty, so the stream filters that handle
         * that era on a real server never meet its connections.
         */
        int shape = UNKNOWN;
        /** Server, 1.3 to 1.6.4: the handshake as it arrives, since it may come in pieces. */
        LegacyProtocol.Handshake handshake = new LegacyProtocol.Handshake();
        /** Client: what the server declared, picked up by whoever asks next. */
        public String declaration;
        /**
         * Whether anything was ever declared on this connection, which stays true after the
         * declaration above has been collected.
         * <p>
         * What it is for is the other end of the visit. Every channel in the process closes through
         * the same hook, and in a Netty program that is every connection it ever makes; asking each
         * one whether it was the server currently in force meant a socket lookup and a logged
         * failure per close, on channels that had never been Classic in the first place.
         */
        public boolean declaredHere;

        /** Server: whether the client at the other end said it was one of ours. */
        public boolean isMarked() {
            return marked;
        }

        /** How much is still owed to the game, which is not zero only mid-decision. */
        public int heldLength() {
            return heldLength;
        }
    }

    private static final NilLogger log = NilLogger.get("Loki");

    /** Not yet said which protocol this is. */
    public static final int UNKNOWN = 0;
    /** Classic: an identification packet, and the marker in its trailing byte. */
    public static final int CLASSIC = 1;
    /** 1.3 to 1.6.4: a handshake, and the marker on the end of its host. */
    public static final int MODERN = 2;

    /** What a client that speaks to us is asked to write, and what a server looks for. */
    private static final int MARKER_AT = LegacyProtocol.CLASSIC_MARKER_OFFSET;

    private ClassicChannels() {}

    // ------------------------------------------------------------------ writing

    /**
     * Before the channel takes the buffer: the client writes its marker, the server gets its block
     * out in front.
     *
     * @return true when the game's own bytes were held back and this write must not carry any
     */
    public static boolean beforeWrite(Conn conn, ByteBuffer src, Writer writer) {
        // The block is sent by calling the channel's own write, which is the method this hangs off,
        // so without this the block's first byte would arrive here and ask to be sent again.
        if (conn.sending || !conn.applicable || !src.hasRemaining()) return false;

        // The server does not ask what its own first packet is. It already knows what it is
        // talking to — it read a Player Identification with a marker in it before it wrote a byte —
        // and what it writes first is not always the identification: a Classic server has a ping
        // going out on a timer, and requiring an identification here meant standing down on every
        // connection that happened to be pinged first.
        if (conn.server) return serverWrite(conn, src, writer);

        if (conn.written == 0 && src.get(src.position()) != LegacyProtocol.CLASSIC_IDENTIFICATION) {
            conn.applicable = false; // whatever this connection is, it is not Classic
            return false;
        }

        // The client's own identification, with the byte nobody reads set to the marker. Written
        // where it lies rather than into a copy: the buffer is the game's, but that byte is one it
        // put there as a zero and never looks at again.
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
            conn.blockSent = true; // a client that said nothing is sent nothing
            log.debug("Nothing to declare to this Classic client: it did not mark itself");
            return false;
        }
        if (conn.pending == null) {
            // Raw bytes on Classic, where nothing sits in the middle of a connection, and a plugin
            // message from 1.3, where something is reading everything that goes past — which on
            // this path is certain, since the only reason a connection of that era gets here is
            // that a proxy is serving it.
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

        // Part of the block is still to go, so nothing of the game's may follow it yet. Narrowing
        // the buffer to nothing makes the write a no-op that reports zero, which is exactly what a
        // non-blocking channel does when it cannot take more, and leaves the game's own position
        // untouched so its next attempt sends the same bytes.
        conn.narrowedTo = src.limit();
        src.limit(src.position());
        return true;
    }

    /** After the channel has written: puts the buffer back as it was found and counts what went. */
    public static int afterWrite(Conn conn, ByteBuffer src, int written) {
        if (conn.sending) return written; // the block's own write, which counts towards nothing
        if (conn.narrowedTo >= 0) {
            src.limit(conn.narrowedTo);
            conn.narrowedTo = -1;
        }
        if (written > 0) conn.written += written;
        return written;
    }

    /** How the block reaches the wire, and what it says. Implemented by the hook. */
    public interface Writer {
        String declaration();

        /** Writes what it can of the block, leaving the rest for the next attempt. */
        void write(ByteBuffer block);
    }

    // ------------------------------------------------------------------ reading

    /**
     * After the channel has filled the buffer: the server looks for the marker, the client takes
     * the block out.
     *
     * @return how many bytes the game should be told about, which is never more than arrived
     */
    public static int afterRead(Conn conn, ByteBuffer dst, int read) {
        if (read <= 0 || !conn.applicable) return read;
        int from = dst.position() - read;

        if (conn.server) {
            if (conn.read == 0) {
                // Which of the two shapes this is, decided once, by the only byte that tells them
                // apart: Classic opens with an identification and 1.3 to 1.6.4 with a handshake.
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

        // Once the question is settled there may still be bytes owed from when it was not, and
        // those have to go out before this steps aside for good.
        if (conn.decided && conn.heldLength == 0) return read;
        return clientRead(conn, dst, read, from);
    }

    /**
     * Server side, 1.3 to 1.6.4: gathers the handshake and looks for the marker on its host.
     * <p>
     * Read-only, and it has to gather because a handshake carries two strings and does not have to
     * arrive in one read. Nothing is altered: the marker stays on the host and reaches whatever is
     * reading it above, which drops the host either way.
     */
    private static void serverReadModern(Conn conn, ByteBuffer dst, int read, int from) {
        if (conn.handshake == null) return;

        byte[] arrived = new byte[read];
        for (int i = 0; i < read; i++) arrived[i] = dst.get(from + i);

        int end = conn.handshake.add(arrived, 0, read);
        if (end == LegacyProtocol.NOT_THIS_SHAPE) {
            conn.handshake = null;
            return;
        }
        if (end < 0 || conn.handshake.gathered() < end) return; // still arriving

        if (LegacyProtocol.hasHostMarker(conn.handshake.packet(), end)) {
            conn.marked = true;
            log.debug("A 1.3 to 1.6.4 client marked itself on this connection");
        }
        conn.handshake = null;
    }

    private static int clientRead(Conn conn, ByteBuffer dst, int read, int from) {
        // Everything that has arrived and not yet been handed over: what was held back before, and
        // what has just come in.
        int total = conn.heldLength + read;
        byte[] all = new byte[total];
        if (conn.heldLength > 0) System.arraycopy(conn.held, 0, all, 0, conn.heldLength);
        for (int i = 0; i < read; i++) all[conn.heldLength + i] = dst.get(from + i);
        conn.held = null;
        conn.heldLength = 0;

        int at = 0;
        LegacyProtocol.Block framing = conn.decided ? null : LegacyProtocol.blockStartingWith(all[0]);
        if (!conn.decided && framing == null) {
            conn.decided = true; // a server that sent nothing, which is most of them
        } else if (framing != null) {
            int body = total < framing.headerBytes() ? -1 : framing.bodyLength(all, total);
            if (body < 0 || total < framing.headerBytes() + body) {
                // Not all of it yet, and none of it may reach the game: the first byte is the start
                // of something the game has never heard of.
                if (total > framing.headerBytes() + LegacyProtocol.PAYLOAD_MAX_BYTES) {
                    conn.decided = true; // longer than any block can be, so it was never one
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

    /** The declaration out of a block's body, which is UTF-8 in both framings. */
    private static String text(byte[] bytes, int at, int length) {
        try {
            return new String(bytes, at, length, "UTF-8");
        } catch (Exception e) {
            return null; // no UTF-8 in this JVM, which cannot happen, but not worth a crash
        }
    }

    /** Keeps bytes back for the next read, since a channel cannot be given them again. */
    private static void hold(Conn conn, byte[] all, int at, int length) {
        conn.held = new byte[length];
        System.arraycopy(all, at, conn.held, 0, length);
        conn.heldLength = length;
    }

    /**
     * Puts what the game is owed at the front of its buffer and says how much that is.
     * <p>
     * There may be more owed than the buffer has room for, since bytes held back from an earlier
     * read are being handed over alongside a new one. What does not fit waits for the next read,
     * which is no different from what the channel itself would have done with it.
     */
    private static int deliver(Conn conn, ByteBuffer dst, int from, byte[] all, int at, int length) {
        int now = Math.min(length, dst.limit() - from);
        for (int i = 0; i < now; i++) dst.put(from + i, all[at + i]);
        dst.position(from + now);
        if (length > now) hold(conn, all, at + now, length - now);
        return now;
    }
}
