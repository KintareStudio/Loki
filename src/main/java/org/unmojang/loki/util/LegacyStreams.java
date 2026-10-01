package org.unmojang.loki.util;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class LegacyStreams {
    public static final int NEVER = -2;

    public interface Sink {
        void declared(String declaration);
    }

    public interface Source {
        String declaration();
    }

    public interface Prefix {
        int length(byte[] head, int seen);

        int lookahead();
    }

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

    public static Prefix afterHandshake() {
        return new Prefix() {
            public int length(byte[] head, int seen) {
                return LegacyProtocol.handshakeLength(head, seen);
            }

            public int lookahead() {
                return 4;
            }
        };
    }

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

    public static OutputStream appendAfter(OutputStream out, Prefix prefix, byte firstByte,
                                           Source source, Marked marked) {
        return appendAfter(out, prefix, firstByte, source, marked, LegacyProtocol.RAW);
    }

    public static InputStream stripAfter(InputStream source, Prefix prefix, byte firstByte,
                                         Sink sink) {
        return stripAfter(source, prefix, firstByte, sink, LegacyProtocol.RAW);
    }

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
                appendIfDue();
                out.write(b);
                seen++;
            }

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

            private void note(byte[] bytes, int at, int length) {
                if (length <= 0) return;
                if (seen == 0 && bytes[at] != firstByte) applicable = false;
                if (!applicable || prefixBytes > 0) return;

                for (int i = 0; i < length && seen + i < head.length; i++) {
                    head[seen + i] = bytes[at + i];
                }
                prefixBytes = prefix.length(head, Math.min(seen + length, head.length));
                if (prefixBytes == NEVER) applicable = false;
            }

            private void appendIfDue() throws IOException {
                if (!applicable || appended || prefixBytes < 0 || seen < prefixBytes) return;
                if (!marked.isMarked()) return;
                appended = true;
                byte[] framed = block.frame(source.declaration());
                if (framed != null) out.write(framed);
            }
        };
    }

    public static OutputStream markBetaLogin(OutputStream out) {
        return new FilterOutputStream(out) {
            private final LegacyProtocol.BeforeLogin before = new LegacyProtocol.BeforeLogin();
            private boolean done;
            private boolean applicable = true;

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

            private byte filter(byte b) {
                if (done || !applicable) return b;

                LegacyProtocol.LoginWalk walk = before.step(b);
                if (before.isImpossible()) applicable = false;
                if (walk == null) return b;

                int index = walk.step(b);
                byte written = b;
                if (index >= 0) {
                    if (b != walk.expectedAt(index)) {
                        done = true;
                        return b;
                    }
                    written = walk.markerAt(index);
                }
                if (walk.isStopped()) done = true;
                return written;
            }
        };
    }

    public static OutputStream markModernHandshake(OutputStream out) {
        return new FilterOutputStream(out) {
            private final LegacyProtocol.Handshake held = new LegacyProtocol.Handshake();
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

                int end = held.add(bytes, at, length);
                if (end == LegacyProtocol.NOT_THIS_SHAPE) {
                    applicable = false;
                    out.write(held.packet(), 0, held.gathered());
                    return;
                }
                if (end < 0 || held.gathered() < end) return;

                byte[] marked = LegacyProtocol.withHostMarker(held.packet(), end);
                out.write(marked, 0, marked.length);
                int after = held.gathered() - end;
                if (after > 0) out.write(held.packet(), end, after);
                done = true;
            }

            public void flush() throws IOException {
                if (done || !applicable) out.flush();
            }
        };
    }

    public static InputStream watchModernHandshake(InputStream in, final Marked marked) {
        return new FilterInputStream(in) {
            private final LegacyProtocol.Handshake held = new LegacyProtocol.Handshake();
            private boolean done;

            public int read() throws IOException {
                byte[] one = new byte[1];
                int read = read(one, 0, 1);
                return read <= 0 ? -1 : one[0] & 0xFF;
            }

            public int read(byte[] bytes, int at, int length) throws IOException {
                int read = in.read(bytes, at, length);
                if (read <= 0 || done) return read;

                int end = held.add(bytes, at, read);
                if (end == LegacyProtocol.NOT_THIS_SHAPE) {
                    done = true;
                    return read;
                }
                if (end < 0 || held.gathered() < end) return read;

                if (LegacyProtocol.hasHostMarker(held.packet(), end)) marked.mark();
                done = true;
                return read;
            }
        };
    }

    public static InputStream watchBetaLogin(InputStream in, final Marked marked) {
        return new FilterInputStream(in) {
            private final LegacyProtocol.BeforeLogin before = new LegacyProtocol.BeforeLogin();
            private final byte[] seed = new byte[LegacyProtocol.LOGIN_MARKER.length];
            private boolean done;
            private boolean applicable = true;

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

                LegacyProtocol.LoginWalk walk = before.step(b);
                if (before.isImpossible()) applicable = false;
                if (walk == null) return;

                int index = walk.step(b);
                if (index >= 0) {
                    seed[index] = b;
                    if (index == seed.length - 1 && walk.isMarker(seed)) marked.mark();
                }
                if (walk.isStopped()) done = true;
            }
        };
    }

    public static InputStream stripAfter(InputStream source, final Prefix prefix,
                                         final byte firstByte, final Sink sink,
                                         final LegacyProtocol.Block block) {
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
                if (applicable && prefixBytes < 0) prefixBytes = prefix.length(head, seen);
                if (prefixBytes == NEVER) applicable = false;
                if (applicable && !decided && prefixBytes >= 0 && seen >= prefixBytes) removeBlock();

                int room = length;
                if (applicable && !decided) {
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

            private void removeBlock() throws IOException {
                decided = true;
                in.mark(block.headerBytes() + LegacyProtocol.PAYLOAD_MAX_BYTES);

                // One byte first, and a whole header only once that byte says a block is starting.
                // Waiting for a header from a server that is not sending one is how this deadlocks:
                // it answers with its own next packet, which can be shorter than a header and can be
                // the last thing it says until the client speaks again. Both ends then wait, and the
                // server gives up on a login that never finished. Any server sends something here,
                // so the one byte is safe to wait for; the rest of a header is not.
                byte[] header = new byte[block.headerBytes()];
                int lead = in.read();
                if (lead < 0 || (byte) lead != block.firstByte()) {
                    in.reset();
                    return;
                }
                header[0] = (byte) lead;

                int got = 1 + fill(header, 1, header.length - 1);
                int length = got < header.length ? -1 : block.bodyLength(header, got);
                if (length < 0) {
                    in.reset();
                    return;
                }

                byte[] body = new byte[length];
                if (fill(body, 0, length) < length) {
                    in.reset();
                    return;
                }
                try {
                    sink.declared(new String(body, "UTF-8"));
                } catch (Exception e) {
                }
            }

            private int fill(byte[] into, int at, int length) throws IOException {
                int got = 0;
                while (got < length) {
                    int read = in.read(into, at + got, length - got);
                    if (read < 0) break;
                    got += read;
                }
                return got;
            }
        };
    }
}
