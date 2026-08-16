package org.unmojang.loki.util;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The four ends of the pre-1.7 announcement, as stream filters.
 * <p>
 * Everything happens in a window at the very start of the connection: the marker sits in the
 * client's first packet and the payload immediately after the server's first one. Past that window
 * the filters hand every byte straight through and never look at another, which is what makes it
 * acceptable to sit on a game connection at all.
 * <p>
 * All four fail open. A short read, an unexpected length, a magic that does not match: the bytes go
 * through untouched and the connection carries on as if Loki were not there. The worst outcome
 * available here is that a declaration is missed, and that is much better than the alternative.
 */
public final class LegacyStreams {
    /** What a {@link Prefix} answers when the bytes it was given are not its packet at all. */
    public static final int NEVER = -2;

    /** Told the declaration a server sent, once. */
    public interface Sink {
        void declared(String declaration);
    }

    /** Asked what to declare, at the moment the server writes its identification. */
    public interface Source {
        String declaration();
    }

    /**
     * Where the block goes: the number of bytes that come before it.
     * <p>
     * Constant in Classic, where the packet it follows is always 131 bytes, and read off the wire in
     * Alpha and Beta, where it follows a handshake carrying a string of whatever length. Both ends
     * of a connection have to agree on it, so it lives here rather than in either filter.
     */
    public interface Prefix {
        /**
         * @param head the first bytes of the stream, up to whatever this needs
         * @param seen how many of them have arrived
         * @return the length, -1 while it cannot be known yet, or {@link #NEVER} when these bytes
         *         are not the packet this reads and it never will be
         */
        int length(byte[] head, int seen);

        /**
         * How many bytes have to arrive before {@link #length} can answer.
         * <p>
         * A reader must not take more than this while it is still asking, or it will have handed
         * the start of the block to the game before knowing there was one.
         */
        int lookahead();
    }

    /** For a packet whose size never varies. */
    public static Prefix constant(final int length) {
        return new Prefix() {
            public int length(byte[] head, int seen) {
                return length;
            }

            public int lookahead() {
                return 0;
            }
        };
    }

    /** For Alpha and Beta, where the block follows a handshake: a packet id and one string. */
    public static Prefix afterHandshake() {
        return new Prefix() {
            public int length(byte[] head, int seen) {
                return LegacyProtocol.handshakeLength(head, seen);
            }

            /** The id, the two bytes of the length, and the first byte of the string itself. */
            public int lookahead() {
                return 4;
            }
        };
    }

    /** Whether the client at the other end marked itself. Shared between one connection's filters. */
    public static final class Marked {
        private volatile boolean marked;

        public boolean isMarked() {
            return marked;
        }

        void mark() {
            marked = true;
        }
    }

    private LegacyStreams() {}

    /**
     * Client side, outgoing: writes the marker into the identification packet as it goes past.
     * <p>
     * The packet keeps its length. A server without Loki reads that byte and ignores it, which is
     * exactly what it did with the zero that was there before.
     */
    public static OutputStream mark(OutputStream out, final int prefixBytes, final int offset,
                                    final byte marker, final byte firstByte) {
        return new FilterOutputStream(out) {
            private int seen;
            private boolean applicable = true;

            public void write(int b) throws IOException {
                if (seen == 0 && (byte) b != firstByte) applicable = false;
                if (applicable && seen == offset) b = marker;
                if (seen < prefixBytes) seen++;
                out.write(b);
            }

            public void write(byte[] bytes, int at, int length) throws IOException {
                if (seen == 0 && length > 0 && bytes[at] != firstByte) applicable = false;
                if (applicable && seen < prefixBytes && offset >= seen && offset < seen + length) {
                    byte[] copy = new byte[length];
                    System.arraycopy(bytes, at, copy, 0, length);
                    copy[offset - seen] = marker;
                    out.write(copy, 0, length);
                } else {
                    out.write(bytes, at, length);
                }
                seen = seen < prefixBytes ? seen + length : seen;
            }
        };
    }

    /**
     * Server side, incoming: notices the marker without altering anything.
     * <p>
     * Read-only on purpose. The byte stays where it is and reaches the server's own parser, which
     * has ignored it since Classic.
     */
    public static InputStream watchForMark(InputStream in, final int offset, final byte marker,
                                           final byte firstByte, final Marked marked) {
        return new FilterInputStream(in) {
            private int seen;
            private boolean applicable = true;

            public int read() throws IOException {
                int b = in.read();
                if (b >= 0) {
                    if (seen == 0 && (byte) b != firstByte) applicable = false;
                    if (applicable && seen == offset && (byte) b == marker) marked.mark();
                    seen++;
                }
                return b;
            }

            public int read(byte[] bytes, int at, int length) throws IOException {
                int read = in.read(bytes, at, length);
                if (read > 0) {
                    if (seen == 0 && bytes[at] != firstByte) applicable = false;
                    if (applicable && offset >= seen && offset < seen + read
                            && bytes[at + offset - seen] == marker) {
                        marked.mark();
                    }
                    seen += read;
                }
                return read;
            }
        };
    }

    /** For the eras that predate proxies, where the block is raw bytes and always was. */
    public static OutputStream appendAfter(OutputStream out, Prefix prefix, byte firstByte,
                                           Source source, Marked marked) {
        return appendAfter(out, prefix, firstByte, source, marked, LegacyProtocol.RAW);
    }

    /** The reader's half of the same. */
    public static InputStream stripAfter(InputStream source, Prefix prefix, byte firstByte,
                                         Sink sink) {
        return stripAfter(source, prefix, firstByte, sink, LegacyProtocol.RAW);
    }

    /**
     * Server side, outgoing: appends the block once the identification packet has gone out, and
     * only to a client that marked itself.
     */
    public static OutputStream appendAfter(OutputStream out, final Prefix prefix,
                                           final byte firstByte, final Source source,
                                           final Marked marked, final LegacyProtocol.Block block) {
        return new FilterOutputStream(out) {
            private final byte[] head = new byte[8];
            private int seen;
            private int prefixBytes = -1;
            private boolean appended;
            private boolean applicable = true;

            public void write(int b) throws IOException {
                byte[] one = {(byte) b};
                note(one, 0, 1);
                appendIfDue(); // before the byte, so the block precedes what follows the boundary
                out.write(b);
                seen++;
            }

            /**
             * Split at the boundary when a single write carries the packet before the block and
             * whatever follows it. The block has to land exactly where the client will look, and a
             * server is under no obligation to write one packet per call.
             */
            public void write(byte[] bytes, int at, int length) throws IOException {
                note(bytes, at, length);
                appendIfDue();
                if (applicable && !appended && prefixBytes > 0
                        && seen < prefixBytes && seen + length > prefixBytes) {
                    int upToBoundary = prefixBytes - seen;
                    out.write(bytes, at, upToBoundary);
                    seen += upToBoundary;
                    appendIfDue();
                    out.write(bytes, at + upToBoundary, length - upToBoundary);
                    seen += length - upToBoundary;
                    return;
                }
                out.write(bytes, at, length);
                seen += length;
            }

            /**
             * Keeps enough of the start to ask the prefix where the boundary is.
             * <p>
             * All of what fits, not just the first byte: one write can carry the whole handshake,
             * and with only its first byte recorded the length inside it would never be read and
             * the boundary would never be found.
             */
            private void note(byte[] bytes, int at, int length) {
                if (length <= 0) return;
                if (seen == 0 && bytes[at] != firstByte) applicable = false;
                if (!applicable || prefixBytes > 0) return;

                for (int i = 0; i < length && seen + i < head.length; i++) {
                    head[seen + i] = bytes[at + i];
                }
                prefixBytes = prefix.length(head, Math.min(seen + length, head.length));
                if (prefixBytes == NEVER) applicable = false; // not a packet this appends to
            }

            /**
             * The block goes out on the first write past the boundary rather than at it. On Alpha
             * and Beta the server answers the handshake before it has read the client's login, so
             * at the boundary itself it does not yet know whether it is talking to a Loki client.
             * By the next packet it does.
             */
            private void appendIfDue() throws IOException {
                if (!applicable || appended || prefixBytes < 0 || seen < prefixBytes) return;
                if (!marked.isMarked()) return;
                appended = true; // set first: a failure here must not be retried on every write
                byte[] framed = block.frame(source.declaration());
                if (framed != null) out.write(framed);
            }
        };
    }

    /**
     * Client side, outgoing, for Alpha and Beta: puts the marker in the login packet's map seed.
     * <p>
     * Two packets have to be walked to get there — the handshake, then the login — and the seed sits
     * behind a username whose length is not known until it arrives. So while it is still looking
     * this works a byte at a time, and once the seed has gone past it hands whole chunks over
     * without inspecting them, which is where all the traffic actually is.
     */
    public static OutputStream markBetaLogin(OutputStream out) {
        return new FilterOutputStream(out) {
            private final byte[] head = new byte[8];
            private boolean done;
            private boolean applicable = true;
            private boolean inLogin;
            private int seen;          // within the handshake, which is all this counts itself
            private int handshakeEnd = -1;
            private LegacyProtocol.LoginWalk walk;

            public void write(int b) throws IOException {
                out.write(done || !applicable ? b : filter((byte) b));
            }

            public void write(byte[] bytes, int at, int length) throws IOException {
                if (done || !applicable) {
                    out.write(bytes, at, length);
                    return;
                }
                byte[] copy = new byte[length];
                for (int i = 0; i < length; i++) copy[i] = filter(bytes[at + i]);
                out.write(copy, 0, length);
            }

            /** Returns the byte to write, which is the one given unless it is part of the seed. */
            private byte filter(byte b) {
                // Checked here and not only in write: one write can carry the whole login, and
                // giving up partway through it has to stop this loop too
                if (done || !applicable) return b;
                if (!inLogin) {
                    if (seen < head.length) head[seen] = b;
                    if (seen == 0 && b == LegacyProtocol.LOGIN) {
                        // No handshake at all, which is how it was until a1.0.16: the login packet
                        // is the first thing sent. Those versions write a byte a character, so the
                        // walk is told so rather than reading it off a handshake that never came.
                        inLogin = true;
                        walk = new LegacyProtocol.LoginWalk(false, false);
                        return filter(b);
                    }
                    if (seen == 0 && b != LegacyProtocol.HANDSHAKE) {
                        applicable = false; // not a handshake, so not a game connection of this era
                        return b;
                    }
                    seen++;
                    if (handshakeEnd < 0) handshakeEnd = LegacyProtocol.handshakeLength(head, seen);
                    if (handshakeEnd == NEVER) {
                        applicable = false; // a handshake of a shape this does not read: 1.3 and up
                        return b;
                    }
                    if (handshakeEnd > 0 && seen >= handshakeEnd) {
                        inLogin = true;
                        // The login packet writes its strings the way the handshake wrote its own,
                        // so the walk is told which of the two that was.
                        walk = new LegacyProtocol.LoginWalk(LegacyProtocol.isWide(head), true);
                        seen = 0;
                    }
                    return b;
                }

                // Only over what was expected to be there: zeros for a seed a client has no value
                // for, and the letters of "Password" for the field that stands in its place in the
                // earliest Alpha. Anything else means this packet is not shaped the way the walk
                // read it, and writing into it would corrupt the connection. Refusing costs a
                // declaration; being wrong costs the login.
                int index = walk.step(b);
                byte written = b;
                if (index >= 0) {
                    if (b != walk.expectedAt(index)) {
                        done = true; // not the field this was looking for
                        return b;
                    }
                    written = walk.markerAt(index);
                }
                if (walk.isStopped()) done = true;
                return written;
            }
        };
    }

    /**
     * Client side, outgoing, 1.3 to 1.6.4: puts the marker on the end of the host in the handshake.
     * <p>
     * The one filter here that changes a packet's length, and the only one that may: the host is a
     * counted string, so a server reads exactly as many characters as it is told and finds the next
     * packet where it expects to. That is also why the whole handshake is held back until it is
     * complete — the count comes before the characters, and it cannot be written until they are
     * all known.
     */
    public static OutputStream markModernHandshake(OutputStream out) {
        return new FilterOutputStream(out) {
            private byte[] held = new byte[64];
            private int seen;
            private boolean done;
            private boolean applicable = true;

            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            public void write(byte[] bytes, int at, int length) throws IOException {
                if (done || !applicable) {
                    out.write(bytes, at, length);
                    return;
                }
                if (seen == 0 && length > 0 && bytes[at] != LegacyProtocol.HANDSHAKE) {
                    applicable = false;
                    out.write(bytes, at, length);
                    return;
                }

                if (seen + length > held.length) {
                    byte[] bigger = new byte[Math.max(held.length * 2, seen + length)];
                    System.arraycopy(held, 0, bigger, 0, seen);
                    held = bigger;
                }
                System.arraycopy(bytes, at, held, seen, length);
                seen += length;

                int end = LegacyProtocol.modernHandshakeLength(held, seen);
                if (end == LegacyProtocol.NOT_THIS_SHAPE || seen > LegacyProtocol.PAYLOAD_MAX_BYTES) {
                    // Not the packet this reads, so hand back everything held and stand down
                    applicable = false;
                    out.write(held, 0, seen);
                    return;
                }
                if (end < 0 || seen < end) return; // still arriving

                byte[] marked = LegacyProtocol.withHostMarker(held, end);
                out.write(marked, 0, marked.length);
                if (seen > end) out.write(held, end, seen - end); // whatever came after it
                done = true;
            }

            public void flush() throws IOException {
                // Held bytes are deliberately not flushed: half a handshake is not a packet, and
                // the rest of it is already on its way from the game.
                if (done || !applicable) out.flush();
            }
        };
    }

    /**
     * Server side, incoming, 1.3 to 1.6.4: reads the host to see whether the marker is on it.
     * <p>
     * Read-only, like its Beta counterpart. The marker stays on the string and reaches the server's
     * own parser, which has dropped the host on the floor since 1.3.
     */
    public static InputStream watchModernHandshake(InputStream in, final Marked marked) {
        return new FilterInputStream(in) {
            private byte[] held = new byte[64];
            private int seen;
            private boolean done;
            private boolean applicable = true;

            public int read() throws IOException {
                byte[] one = new byte[1];
                int read = read(one, 0, 1);
                return read <= 0 ? -1 : one[0] & 0xFF;
            }

            public int read(byte[] bytes, int at, int length) throws IOException {
                int read = in.read(bytes, at, length);
                if (read <= 0 || done || !applicable) return read;

                if (seen == 0 && bytes[at] != LegacyProtocol.HANDSHAKE) {
                    applicable = false;
                    return read;
                }
                if (seen + read > held.length) {
                    byte[] bigger = new byte[Math.max(held.length * 2, seen + read)];
                    System.arraycopy(held, 0, bigger, 0, seen);
                    held = bigger;
                }
                System.arraycopy(bytes, at, held, seen, read);
                seen += read;

                int end = LegacyProtocol.modernHandshakeLength(held, seen);
                if (end == LegacyProtocol.NOT_THIS_SHAPE || seen > LegacyProtocol.PAYLOAD_MAX_BYTES) {
                    applicable = false;
                    return read;
                }
                if (end < 0 || seen < end) return read;

                if (LegacyProtocol.hasHostMarker(held, end)) marked.mark();
                done = true;
                return read;
            }
        };
    }

    /** Server side, incoming: the same walk, reading the seed instead of writing it. */
    public static InputStream watchBetaLogin(InputStream in, final Marked marked) {
        return new FilterInputStream(in) {
            private final byte[] head = new byte[8];
            private final byte[] seed = new byte[LegacyProtocol.LOGIN_MARKER.length];
            private boolean done;
            private boolean applicable = true;
            private boolean inLogin;
            private int seen;
            private int handshakeEnd = -1;
            private LegacyProtocol.LoginWalk walk;

            public int read() throws IOException {
                int b = in.read();
                if (b >= 0) inspect((byte) b);
                return b;
            }

            public int read(byte[] bytes, int at, int length) throws IOException {
                int read = in.read(bytes, at, length);
                if (read > 0 && !done && applicable) {
                    for (int i = 0; i < read; i++) inspect(bytes[at + i]);
                }
                return read;
            }

            private void inspect(byte b) {
                if (done || !applicable) return;
                if (!inLogin) {
                    if (seen < head.length) head[seen] = b;
                    if (seen == 0 && b == LegacyProtocol.LOGIN) {
                        inLogin = true; // no handshake, as it was until a1.0.16
                        walk = new LegacyProtocol.LoginWalk(false, false);
                        inspect(b);
                        return;
                    }
                    if (seen == 0 && b != LegacyProtocol.HANDSHAKE) {
                        applicable = false;
                        return;
                    }
                    seen++;
                    if (handshakeEnd < 0) handshakeEnd = LegacyProtocol.handshakeLength(head, seen);
                    if (handshakeEnd == NEVER) {
                        applicable = false; // a handshake of a shape this does not read: 1.3 and up
                        return;
                    }
                    if (handshakeEnd > 0 && seen >= handshakeEnd) {
                        inLogin = true;
                        // The login packet writes its strings the way the handshake wrote its own,
                        // so the walk is told which of the two that was.
                        walk = new LegacyProtocol.LoginWalk(LegacyProtocol.isWide(head), true);
                        seen = 0;
                    }
                    return;
                }

                int index = walk.step(b);
                if (index >= 0) {
                    seed[index] = b;
                    if (index == seed.length - 1 && walk.isMarker(seed)) marked.mark();
                }
                if (walk.isStopped()) done = true;
            }
        };
    }

    /**
     * Client side, incoming: takes the block back out, so the game reads the bytes it would have
     * read from a server that never heard of Loki.
     * <p>
     * The block can only be at one place, right after the identification packet, so this reads that
     * far, decides once, and then stops looking. Anything it cannot make sense of is passed on.
     */
    public static InputStream stripAfter(InputStream source, final Prefix prefix,
                                         final byte firstByte, final Sink sink,
                                         final LegacyProtocol.Block block) {
        // Buffered because deciding requires reading ahead and putting back what turned out not to
        // be ours, and a socket's own stream cannot be put back into.
        InputStream in = new BufferedInputStream(source,
                LegacyProtocol.PAYLOAD_HEADER_BYTES + LegacyProtocol.PAYLOAD_MAX_BYTES);
        return new FilterInputStream(in) {
            private final byte[] head = new byte[8];
            private int seen;
            private int prefixBytes = -1;
            private boolean decided;
            private boolean applicable = true;

            public int read() throws IOException {
                byte[] one = new byte[1];
                int read = read(one, 0, 1);
                return read <= 0 ? -1 : one[0] & 0xFF;
            }

            public int read(byte[] bytes, int at, int length) throws IOException {
                // Asked before reading, not after: a prefix that never varies answers straight
                // away, and a reader that waited for bytes first would already have overshot it
                if (applicable && prefixBytes < 0) prefixBytes = prefix.length(head, seen);
                // A prefix that says it will never know — a packet of a shape this does not read —
                // stands the filter down. Left as "not yet", it would go on capping every read at
                // a boundary it is not going to find, and eventually cap them at nothing.
                if (prefixBytes == NEVER) applicable = false;
                if (applicable && !decided && prefixBytes >= 0 && seen >= prefixBytes) removeBlock();

                // Never read past the boundary in one go, or the block would be handed to the game
                // before there was a chance to look at it
                int room = length;
                if (applicable && !decided) {
                    // While the boundary is still unknown, read only as far as knowing it requires
                    int limit = prefixBytes < 0 ? prefix.lookahead() - seen : prefixBytes - seen;
                    if (limit > 0) room = Math.min(length, limit);
                }

                int read = in.read(bytes, at, room);
                if (read > 0) {
                    if (seen == 0 && bytes[at] != firstByte) applicable = false;
                    if (applicable && prefixBytes < 0) {
                        for (int i = 0; i < read && seen + i < head.length; i++) {
                            head[seen + i] = bytes[at + i];
                        }
                        prefixBytes = prefix.length(head, seen + read);
                    }
                    seen += read;
                }
                return read;
            }

            /**
             * Reads exactly the header, and the body if the header is ours. Nothing is consumed
             * unless the magic matches, so a server that appended nothing is not disturbed: the
             * bytes read here are the game's next packet and are pushed back by being handed on.
             */
            private void removeBlock() throws IOException {
                decided = true;
                in.mark(block.headerBytes() + LegacyProtocol.PAYLOAD_MAX_BYTES);

                byte[] header = new byte[block.headerBytes()];
                int got = fill(header, header.length);
                int length = got < header.length ? -1 : block.bodyLength(header, got);
                if (length < 0) {
                    in.reset();
                    return;
                }

                byte[] body = new byte[length];
                if (fill(body, length) < length) {
                    in.reset();
                    return;
                }
                try {
                    sink.declared(new String(body, "UTF-8"));
                } catch (Exception e) {
                    // Unreadable payload. The bytes are gone either way, and the alternative is
                    // handing the game a block it would choke on.
                }
            }

            private int fill(byte[] into, int length) throws IOException {
                int got = 0;
                while (got < length) {
                    int read = in.read(into, got, length - got);
                    if (read < 0) break;
                    got += read;
                }
                return got;
            }
        };
    }
}
