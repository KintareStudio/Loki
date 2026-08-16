import org.unmojang.loki.util.LegacyProtocol;
import org.unmojang.loki.util.LegacyStreams;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The pre-1.7 announcement, byte by byte, in both directions.
 * <p>
 * The thing being tested is that the game gets what it would have got. Whatever Loki adds on one
 * side comes back off on the other, and every combination of a Loki end with a plain one leaves the
 * connection exactly as it was.
 * <p>
 * A socket does not deliver a packet at a time — it delivers whatever happens to have arrived — so
 * every case here is run again with the bytes arriving one at a time, and again in awkward chunks.
 * That is the failure this design is most likely to have.
 */
public class LegacyStreamsTest {
    private static final int IDENTIFICATION = LegacyProtocol.CLASSIC_IDENTIFICATION_BYTES;
    private static final String API = "https://drasl.kintare.studio/authlib-injector";

    private static int failures = 0;

    private static void check(String label, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + label
                + (detail == null ? "" : "  [" + detail + "]"));
    }

    /** A server or client identification packet: 131 bytes, the last one free. */
    private static byte[] identification() {
        byte[] packet = new byte[IDENTIFICATION];
        packet[0] = LegacyProtocol.CLASSIC_IDENTIFICATION;
        packet[1] = 7; // protocol version
        for (int i = 2; i < IDENTIFICATION - 1; i++) packet[i] = ' ';
        return packet;
    }

    /** A string on the Alpha and Beta wire: a count of characters, then UTF-16BE. */
    private static byte[] betaString(String value) {
        byte[] bytes = new byte[2 + value.length() * 2];
        bytes[0] = (byte) (value.length() >> 8);
        bytes[1] = (byte) value.length();
        for (int i = 0; i < value.length(); i++) {
            bytes[2 + i * 2] = (byte) (value.charAt(i) >> 8);
            bytes[3 + i * 2] = (byte) value.charAt(i);
        }
        return bytes;
    }

    /** Handshake: a packet id and one string, the same in both directions since Alpha. */
    private static byte[] betaHandshake(String value) {
        return concat(new byte[]{LegacyProtocol.HANDSHAKE}, betaString(value));
    }

    /** Login: id, an int, a string, the map seed a client sends as zero, and the trailing fields. */
    private static byte[] betaLogin(String value) {
        byte[] head = concat(new byte[]{LegacyProtocol.LOGIN, 0, 0, 0, 17}, betaString(value));
        return concat(head, new byte[]{0, 0, 0, 0, 0, 0, 0, 0,   0, 0, 0, 0,   0, 0, 0});
    }

    /** What the game sends next, and must receive intact whatever Loki did before it. */
    private static byte[] nextPacket() {
        return new byte[]{0x02, 0x03, 0x04, 0x05, 0x06, 0x07};
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

    /** Reads everything, in chunks of the given size, the way a socket would hand it over. */
    private static byte[] readAll(InputStream in, int chunk) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[chunk];
        int read;
        while ((read = in.read(buffer, 0, chunk)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    /** Writes everything, in chunks of the given size, the way a game would. */
    private static void writeAll(OutputStream out, byte[] bytes, int chunk) throws Exception {
        for (int at = 0; at < bytes.length; at += chunk) {
            out.write(bytes, at, Math.min(chunk, bytes.length - at));
        }
        out.flush();
    }

    private static final class Recorder implements LegacyStreams.Sink {
        String declaration;

        public void declared(String declaration) {
            this.declaration = declaration;
        }
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.println("LegacyStreamsTest: CRASHED");
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        int[] chunks = {1, 7, 131, 4096};

        for (int c = 0; c < chunks.length; c++) {
            int chunk = chunks[c];
            System.out.println();
            System.out.println("== arriving " + chunk + " byte(s) at a time ==");

            // ---- the client marks itself, and the packet keeps its shape
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            OutputStream marking = LegacyStreams.mark(raw, IDENTIFICATION,
                    LegacyProtocol.CLASSIC_MARKER_OFFSET, LegacyProtocol.MARKER,
                    LegacyProtocol.CLASSIC_IDENTIFICATION);
            byte[] clientPackets = concat(identification(), nextPacket());
            writeAll(marking, clientPackets, chunk);

            byte[] marked = raw.toByteArray();
            check("the marked packet is the same length", marked.length == clientPackets.length,
                    marked.length + " vs " + clientPackets.length);
            check("with the marker in the byte nobody reads",
                    marked[LegacyProtocol.CLASSIC_MARKER_OFFSET] == LegacyProtocol.MARKER, null);
            check("and everything after it untouched",
                    same(nextPacket(), java.util.Arrays.copyOfRange(marked, IDENTIFICATION,
                            marked.length)), null);

            // ---- the server notices, without changing anything
            LegacyStreams.Marked flag = new LegacyStreams.Marked();
            byte[] seenByServer = readAll(LegacyStreams.watchForMark(
                    new ByteArrayInputStream(marked), LegacyProtocol.CLASSIC_MARKER_OFFSET,
                    LegacyProtocol.MARKER, LegacyProtocol.CLASSIC_IDENTIFICATION, flag), chunk);
            check("the server sees the mark", flag.isMarked(), null);
            check("and reads the bytes exactly as they arrived", same(marked, seenByServer), null);

            // ---- the server answers, appending the block
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            OutputStream appending = LegacyStreams.appendAfter(wire, LegacyStreams.constant(IDENTIFICATION),
                    LegacyProtocol.CLASSIC_IDENTIFICATION,
                    new LegacyStreams.Source() {
                        public String declaration() {
                            return API;
                        }
                    }, flag);
            writeAll(appending, concat(identification(), nextPacket()), chunk);
            check("the block goes out after the identification",
                    wire.toByteArray().length
                            == IDENTIFICATION + nextPacket().length
                            + LegacyProtocol.PAYLOAD_HEADER_BYTES + API.length(),
                    String.valueOf(wire.toByteArray().length));

            // ---- and the client takes it back out
            Recorder recorder = new Recorder();
            byte[] seenByGame = readAll(LegacyStreams.stripAfter(new ByteArrayInputStream(wire.toByteArray()),
                    LegacyStreams.constant(IDENTIFICATION), LegacyProtocol.CLASSIC_IDENTIFICATION, recorder), chunk);
            check("the game reads what a plain server would have sent",
                    same(concat(identification(), nextPacket()), seenByGame),
                    seenByGame.length + " bytes");
            check("and Loki got the declaration", API.equals(recorder.declaration),
                    recorder.declaration);

            // ---- a client that did not mark itself is sent nothing
            LegacyStreams.Marked unmarked = new LegacyStreams.Marked();
            ByteArrayOutputStream plainWire = new ByteArrayOutputStream();
            writeAll(LegacyStreams.appendAfter(plainWire, LegacyStreams.constant(IDENTIFICATION),
                    LegacyProtocol.CLASSIC_IDENTIFICATION,
                    new LegacyStreams.Source() {
                        public String declaration() {
                            return API;
                        }
                    }, unmarked), concat(identification(), nextPacket()), chunk);
            check("an unmarked client is sent the packet and nothing else",
                    same(concat(identification(), nextPacket()), plainWire.toByteArray()), null);

            // ---- and a server that declared nothing leaves the client alone
            Recorder quiet = new Recorder();
            byte[] fromPlainServer = readAll(LegacyStreams.stripAfter(new ByteArrayInputStream(concat(identification(), nextPacket())),
                    LegacyStreams.constant(IDENTIFICATION), LegacyProtocol.CLASSIC_IDENTIFICATION, quiet), chunk);
            check("a plain server's bytes reach the game unchanged",
                    same(concat(identification(), nextPacket()), fromPlainServer), null);
            check("and nothing is reported as declared", quiet.declaration == null,
                    quiet.declaration);
        }

        // Alpha and Beta put the two holes somewhere else: the marker in the login packet's map
        // seed, which a client sends as zero, and the block between the server's handshake reply
        // and its login reply. The boundary is read off the wire rather than assumed, because the
        // handshake carries a string and a string has whatever length it has.
        for (int c = 0; c < chunks.length; c++) {
            int chunk = chunks[c];
            System.out.println();
            System.out.println("== Beta, arriving " + chunk + " byte(s) at a time ==");

            byte[] clientSide = concat(betaHandshake("Tester"), betaLogin("Tester"));
            ByteArrayOutputStream marked = new ByteArrayOutputStream();
            writeAll(LegacyStreams.markBetaLogin(marked), clientSide, chunk);

            check("the login packet keeps its length",
                    marked.toByteArray().length == clientSide.length,
                    marked.toByteArray().length + " vs " + clientSide.length);
            int seedAt = betaHandshake("Tester").length + 1 + 4 + 2 + "Tester".length() * 2;
            check("and the marker sits where the seed was",
                    LegacyProtocol.isLoginMarker(marked.toByteArray(), seedAt), null);
            check("with the handshake before it untouched",
                    same(betaHandshake("Tester"), java.util.Arrays.copyOfRange(
                            marked.toByteArray(), 0, betaHandshake("Tester").length)), null);

            LegacyStreams.Marked flag = new LegacyStreams.Marked();
            byte[] asRead = readAll(LegacyStreams.watchBetaLogin(
                    new ByteArrayInputStream(marked.toByteArray()), flag), chunk);
            check("the server sees it", flag.isMarked(), null);
            check("and reads the bytes as they arrived", same(marked.toByteArray(), asRead), null);

            byte[] serverSide = concat(betaHandshake("-"), betaLogin("-"));
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            writeAll(LegacyStreams.appendAfter(wire, LegacyStreams.afterHandshake(),
                    LegacyProtocol.HANDSHAKE, new LegacyStreams.Source() {
                        public String declaration() {
                            return API;
                        }
                    }, flag), serverSide, chunk);
            check("the block goes between the handshake reply and the login reply",
                    wire.toByteArray()[betaHandshake("-").length] == LegacyProtocol.PAYLOAD_MAGIC[0],
                    null);

            Recorder recorder = new Recorder();
            byte[] seenByGame = readAll(LegacyStreams.stripAfter(
                    new ByteArrayInputStream(wire.toByteArray()), LegacyStreams.afterHandshake(),
                    LegacyProtocol.HANDSHAKE, recorder), chunk);
            check("and the game reads what a plain server would have sent",
                    same(serverSide, seenByGame), seenByGame.length + " vs " + serverSide.length);
            check("while Loki got the declaration", API.equals(recorder.declaration),
                    recorder.declaration);

            LegacyStreams.Marked unmarked = new LegacyStreams.Marked();
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            writeAll(LegacyStreams.appendAfter(plain, LegacyStreams.afterHandshake(),
                    LegacyProtocol.HANDSHAKE, new LegacyStreams.Source() {
                        public String declaration() {
                            return API;
                        }
                    }, unmarked), serverSide, chunk);
            check("an unmarked client is sent nothing extra",
                    same(serverSide, plain.toByteArray()), null);
        }

        // These filters are installed on java.net.Socket, so every socket in the process meets them
        // — an HTTP request Loki itself makes as much as a game connection. The byte the marker
        // would go in is somewhere in the middle of a request body, and overwriting it there is the
        // worst thing this design could do.
        System.out.println();
        System.out.println("== a socket that is not a game connection at all ==");
        StringBuilder request = new StringBuilder("GET /session/minecraft/profile HTTP/1.1\r\n");
        while (request.length() < 400) request.append("X-Padding: aaaaaaaaaaaaaaaaaaaa\r\n");
        byte[] http = request.toString().getBytes("UTF-8");

        for (int c = 0; c < chunks.length; c++) {
            ByteArrayOutputStream sent = new ByteArrayOutputStream();
            writeAll(LegacyStreams.mark(sent, IDENTIFICATION, LegacyProtocol.CLASSIC_MARKER_OFFSET,
                    LegacyProtocol.MARKER, LegacyProtocol.CLASSIC_IDENTIFICATION), http, chunks[c]);
            check("an HTTP request goes out byte for byte, at " + chunks[c] + " at a time",
                    same(http, sent.toByteArray()), null);

            Recorder none = new Recorder();
            byte[] received = readAll(LegacyStreams.stripAfter(new ByteArrayInputStream(http),
                    LegacyStreams.constant(IDENTIFICATION), LegacyProtocol.CLASSIC_IDENTIFICATION, none), chunks[c]);
            check("and a reply comes back byte for byte", same(http, received), null);
            check("with nothing taken for a declaration", none.declaration == null, none.declaration);
        }

        System.out.println();
        System.out.println(failures == 0 ? "LegacyStreamsTest: PASSED"
                : "LegacyStreamsTest: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
