package org.unmojang.loki.legacy;

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
                                    final byte marker) {
        return new FilterOutputStream(out) {
            private int seen;

            public void write(int b) throws IOException {
                if (seen == offset) b = marker;
                if (seen < prefixBytes) seen++;
                out.write(b);
            }

            public void write(byte[] bytes, int at, int length) throws IOException {
                if (seen < prefixBytes && offset >= seen && offset < seen + length) {
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
                                           final Marked marked) {
        return new FilterInputStream(in) {
            private int seen;

            public int read() throws IOException {
                int b = in.read();
                if (b >= 0) {
                    if (seen == offset && (byte) b == marker) marked.mark();
                    seen++;
                }
                return b;
            }

            public int read(byte[] bytes, int at, int length) throws IOException {
                int read = in.read(bytes, at, length);
                if (read > 0) {
                    if (offset >= seen && offset < seen + read
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
    public static OutputStream appendAfter(OutputStream out, final int prefixBytes,
                                           final Source source, final Marked marked) {
        return new FilterOutputStream(out) {
            private int seen;
            private boolean appended;

            public void write(int b) throws IOException {
                out.write(b);
                seen++;
                appendIfDue();
            }

            /**
             * Split at the boundary when a single write carries the identification and what comes
             * after it. The block has to land exactly where the client will look for it, and a
             * server is under no obligation to write one packet per call.
             */
            public void write(byte[] bytes, int at, int length) throws IOException {
                if (!appended && seen < prefixBytes && seen + length > prefixBytes) {
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
                appendIfDue();
            }

            private void appendIfDue() throws IOException {
                if (appended || seen < prefixBytes || !marked.isMarked()) return;
                appended = true; // set first: a failure here must not be retried on every write
                byte[] block = LegacyProtocol.payload(source.declaration());
                if (block != null) out.write(block);
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
    public static InputStream stripAfter(InputStream source, final int prefixBytes, final Sink sink) {
        // Buffered because deciding requires reading ahead and putting back what turned out not to
        // be ours, and a socket's own stream cannot be put back into.
        InputStream in = new BufferedInputStream(source,
                LegacyProtocol.PAYLOAD_HEADER_BYTES + LegacyProtocol.PAYLOAD_MAX_BYTES);
        return new FilterInputStream(in) {
            private int seen;
            private boolean decided;

            public int read() throws IOException {
                byte[] one = new byte[1];
                int read = read(one, 0, 1);
                return read <= 0 ? -1 : one[0] & 0xFF;
            }

            public int read(byte[] bytes, int at, int length) throws IOException {
                if (!decided && seen >= prefixBytes) removeBlock();

                int room = decided || seen >= prefixBytes ? length : Math.min(length, prefixBytes - seen);
                int read = in.read(bytes, at, room);
                if (read > 0) seen += read;
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
