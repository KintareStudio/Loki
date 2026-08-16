import org.unmojang.loki.util.ClassicChannels;
import org.unmojang.loki.util.LegacyProtocol;

import java.nio.ByteBuffer;

/**
 * The Classic announcement, byte by byte, over buffers instead of streams.
 * <p>
 * The thing being tested is the same as everywhere else — the game gets what it would have got —
 * but the failure available here is different. A channel does not hand over a packet at a time and
 * cannot be given bytes back, so every case runs again with the connection taking and giving only a
 * few bytes at a time, which is what a non-blocking channel does under load.
 */
public class ClassicChannelsTest {
    private static final int IDENTIFICATION = LegacyProtocol.CLASSIC_IDENTIFICATION_BYTES;
    private static final String API = "https://drasl.kintare.studio/authlib-injector";

    private static int failures = 0;

    private static void check(String label, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + label
                + (detail == null ? "" : "  [" + detail + "]"));
    }

    /** Player or Server Identification: 131 bytes, the last of them unused. */
    private static byte[] identification() {
        byte[] packet = new byte[IDENTIFICATION];
        packet[0] = LegacyProtocol.CLASSIC_IDENTIFICATION;
        packet[1] = 7;
        for (int i = 2; i < IDENTIFICATION - 1; i++) packet[i] = ' ';
        return packet;
    }

    /** Whatever the game sends next, which must arrive exactly as it left. */
    private static byte[] nextPacket() {
        return new byte[]{0x08, 0x01, 0x02, 0x03, 0x04, 0x05};
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    private static boolean same(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    /**
     * A channel that takes and gives only so much at a time, which is the whole point: everything
     * here has to work when a packet arrives in pieces and leaves in pieces.
     */
    private static final class Wire {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        private final int atMost;

        Wire(int atMost) {
            this.atMost = atMost;
        }

        int take(ByteBuffer src) {
            int n = Math.min(atMost, src.remaining());
            for (int i = 0; i < n; i++) out.write(src.get());
            return n;
        }

        byte[] written() {
            return out.toByteArray();
        }
    }

    /** Runs a whole write through the hooks the way the patched channel does. */
    private static byte[] writeAll(ClassicChannels.Conn conn, byte[] bytes, int atMost,
                                   final Wire wire, final String declaration) {
        ByteBuffer src = ByteBuffer.wrap(bytes.clone());
        ClassicChannels.Writer writer = new ClassicChannels.Writer() {
            public String declaration() {
                return declaration;
            }

            public void write(ByteBuffer block) {
                wire.take(block);
            }
        };
        int guard = 0;
        while (src.hasRemaining() && guard++ < 100000) {
            ClassicChannels.beforeWrite(conn, src, writer);
            ClassicChannels.afterWrite(conn, src, wire.take(src));
        }
        return wire.written();
    }

    /** Runs a whole read through the hooks, returning only what the game was allowed to see. */
    private static byte[] readAll(ClassicChannels.Conn conn, byte[] bytes, int atMost, int room) {
        java.io.ByteArrayOutputStream seen = new java.io.ByteArrayOutputStream();
        int at = 0;
        int guard = 0;
        while ((at < bytes.length || conn.heldLength() > 0) && guard++ < 100000) {
            ByteBuffer dst = ByteBuffer.allocate(room);
            int n = Math.min(Math.min(atMost, room), bytes.length - at);
            for (int i = 0; i < n; i++) dst.put(bytes[at + i]);
            at += n;

            int given = ClassicChannels.afterRead(conn, dst, n);
            for (int i = 0; i < given; i++) seen.write(dst.get(i));
            if (n == 0 && given == 0) break;
        }
        return seen.toByteArray();
    }

    public static void main(String[] args) {
        int[] chunks = {1, 7, 131, 4096};

        for (int c = 0; c < chunks.length; c++) {
            int chunk = chunks[c];
            System.out.println();
            System.out.println("== " + chunk + " byte(s) at a time ==");

            // ---- the client marks itself, and the packet keeps its length
            byte[] clientSide = concat(identification(), nextPacket());
            ClassicChannels.Conn client = new ClassicChannels.Conn();
            byte[] marked = writeAll(client, clientSide, chunk, new Wire(chunk), null);

            check("the identification keeps its length", marked.length == clientSide.length,
                    clientSide.length + " -> " + marked.length);
            check("with the marker in the byte nobody reads",
                    marked[LegacyProtocol.CLASSIC_MARKER_OFFSET] == LegacyProtocol.MARKER, null);
            byte[] withoutMarker = marked.clone();
            withoutMarker[LegacyProtocol.CLASSIC_MARKER_OFFSET] = 0;
            check("and everything else untouched", same(clientSide, withoutMarker), null);

            // ---- the server reads it and finds the marker, without altering a byte
            ClassicChannels.Conn server = new ClassicChannels.Conn();
            server.server = true;
            byte[] asRead = readAll(server, marked, chunk, 8192);
            check("the server sees the mark", server.isMarked(), null);
            check("and reads the bytes exactly as they arrived", same(marked, asRead), null);

            // ---- and answers with the block in front of its own identification
            byte[] serverSide = concat(identification(), nextPacket());
            byte[] sent = writeAll(server, serverSide, chunk, new Wire(chunk), API);
            byte[] block = LegacyProtocol.payload(API);
            check("the block goes out in front of everything",
                    same(concat(block, serverSide), sent), sent.length + " bytes");

            // ---- which the client takes back off before the game sees any of it
            ClassicChannels.Conn reader = new ClassicChannels.Conn();
            byte[] game = readAll(reader, sent, chunk, 8192);
            check("the game reads what a plain server would have sent",
                    same(serverSide, game), game.length + " bytes");
            check("and Loki got the declaration", API.equals(reader.declaration), reader.declaration);

            // ---- a server without Loki: nothing is taken, nothing is reported
            ClassicChannels.Conn plain = new ClassicChannels.Conn();
            byte[] untouched = readAll(plain, serverSide, chunk, 8192);
            check("a plain server's bytes reach the game unchanged",
                    same(serverSide, untouched), null);
            check("with nothing reported as declared", plain.declaration == null, plain.declaration);

            // ---- an unmarked client is answered with nothing extra
            ClassicChannels.Conn quiet = new ClassicChannels.Conn();
            quiet.server = true;
            readAll(quiet, concat(identification(), nextPacket()), chunk, 8192);
            byte[] bare = writeAll(quiet, serverSide, chunk, new Wire(chunk), API);
            check("an unmarked client is sent nothing extra", same(serverSide, bare), null);
        }

        // Every channel in the process goes through these hooks, Netty's included. What is not a
        // Classic connection has to come out the other side byte for byte.
        System.out.println();
        System.out.println("== a channel that is not a Classic connection ==");
        StringBuilder request = new StringBuilder("GET /session/minecraft/profile HTTP/1.1\r\n");
        while (request.length() < 400) request.append("X-Padding: aaaaaaaaaaaaaaaaaaaa\r\n");
        byte[] http;
        try {
            http = request.toString().getBytes("UTF-8");
        } catch (Exception e) {
            http = request.toString().getBytes();
        }

        for (int c = 0; c < chunks.length; c++) {
            ClassicChannels.Conn conn = new ClassicChannels.Conn();
            byte[] out = writeAll(conn, http, chunks[c], new Wire(chunks[c]), API);
            check("a request goes out byte for byte, at " + chunks[c] + " at a time",
                    same(http, out), null);

            ClassicChannels.Conn back = new ClassicChannels.Conn();
            byte[] in = readAll(back, http, chunks[c], 8192);
            check("and a reply comes back byte for byte", same(http, in), null);
            check("with nothing taken for a declaration", back.declaration == null, back.declaration);
        }

        // A read that hands over less than the game asked for is the case this is most likely to
        // get wrong: the block has to be assembled across reads, and what follows it has to arrive
        // whole even though it no longer fits where it was put.
        System.out.println();
        System.out.println("== a game buffer smaller than what is owed ==");
        byte[] block = LegacyProtocol.payload(API);
        byte[] serverSide = concat(identification(), nextPacket());
        for (int room = 8; room <= 64; room *= 2) {
            ClassicChannels.Conn conn = new ClassicChannels.Conn();
            byte[] game = readAll(conn, concat(block, serverSide), 5, room);
            check("the game gets everything with only " + room + " bytes of room at a time",
                    same(serverSide, game), game.length + " of " + serverSide.length);
            check("and the declaration still arrived", API.equals(conn.declaration), conn.declaration);
        }

        System.out.println();
        System.out.println(failures == 0 ? "ClassicChannelsTest: PASSED"
                : "ClassicChannelsTest: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
