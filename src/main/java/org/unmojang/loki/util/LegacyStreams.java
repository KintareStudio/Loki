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
         * @return the length, or -1 while it cannot be known yet
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

            /** The id and the two bytes of the string's length. */
            public int lookahead() {
                return 3;
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

    /**
     * Server side, outgoing: appends the block once the identification packet has gone out, and
     * only to a client that marked itself.
     */
    public static OutputStream appendAfter(OutputStream out, final Prefix prefix,
                                           final byte firstByte, final Source source,
                                           final Marked marked) {
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
                byte[] block = LegacyProtocol.payload(source.declaration());
                if (block != null) out.write(block);
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
            private int seen;          // within the current packet
            private int handshakeEnd = -1;
            private int seedAt = -1;

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
                if (!inLogin) {
                    if (seen < head.length) head[seen] = b;
                    if (seen == 0 && b != LegacyProtocol.HANDSHAKE) {
                        applicable = false; // not a handshake, so not a game connection of this era
                        return b;
                    }
                    seen++;
                    if (handshakeEnd < 0) handshakeEnd = LegacyProtocol.handshakeLength(head, seen);
                    if (handshakeEnd > 0 && seen >= handshakeEnd) {
                        inLogin = true;
                        seen = 0;
                    }
                    return b;
                }

                if (seen < head.length) head[seen] = b;
                if (seen == 0 && b != LegacyProtocol.LOGIN) {
                    done = true; // whatever this is, the seed is not in it
                    return b;
                }
                if (seedAt < 0) seedAt = LegacyProtocol.loginSeedOffset(head, seen + 1);

                byte written = b;
                if (seedAt > 0 && seen >= seedAt && seen < seedAt + LegacyProtocol.LOGIN_MARKER.length) {
                    written = LegacyProtocol.LOGIN_MARKER[seen - seedAt];
                }
                seen++;
                if (seedAt > 0 && seen >= seedAt + LegacyProtocol.LOGIN_MARKER.length) done = true;
                return written;
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
            private int seedAt = -1;

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
                    if (seen == 0 && b != LegacyProtocol.HANDSHAKE) {
                        applicable = false;
                        return;
                    }
                    seen++;
                    if (handshakeEnd < 0) handshakeEnd = LegacyProtocol.handshakeLength(head, seen);
                    if (handshakeEnd > 0 && seen >= handshakeEnd) {
                        inLogin = true;
                        seen = 0;
                    }
                    return;
                }

                if (seen < head.length) head[seen] = b;
                if (seen == 0 && b != LegacyProtocol.LOGIN) {
                    done = true;
                    return;
                }
                if (seedAt < 0) seedAt = LegacyProtocol.loginSeedOffset(head, seen + 1);

                if (seedAt > 0 && seen >= seedAt && seen < seedAt + seed.length) {
                    seed[seen - seedAt] = b;
                    if (seen == seedAt + seed.length - 1) {
                        if (LegacyProtocol.isLoginMarker(seed, 0)) marked.mark();
                        done = true;
                    }
                }
                seen++;
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
                                         final byte firstByte, final Sink sink) {
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
                in.mark(LegacyProtocol.PAYLOAD_HEADER_BYTES + LegacyProtocol.PAYLOAD_MAX_BYTES);

                byte[] header = new byte[LegacyProtocol.PAYLOAD_HEADER_BYTES];
                int got = fill(header, header.length);
                if (got < header.length
                        || !LegacyProtocol.startsWithMagic(header, 0, got)) {
                    in.reset();
                    return;
                }

                int length = ((header[LegacyProtocol.PAYLOAD_MAGIC.length] & 0xFF) << 8)
                        | (header[LegacyProtocol.PAYLOAD_MAGIC.length + 1] & 0xFF);
                if (length <= 0 || length > LegacyProtocol.PAYLOAD_MAX_BYTES) {
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
