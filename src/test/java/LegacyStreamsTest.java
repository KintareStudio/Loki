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
    private static final String API = "https://drasl.kintare.studio/authlib-injector";

    private static int failures = 0;

    private static void check(String label, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + label
                + (detail == null ? "" : "  [" + detail + "]"));
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

    /** A string as a client of this era writes one: a count of characters, then the characters. */
    private static byte[] string(String value, boolean wide) {
        byte[] bytes = new byte[2 + value.length() * (wide ? 2 : 1)];
        bytes[0] = (byte) (value.length() >> 8);
        bytes[1] = (byte) value.length();
        for (int i = 0; i < value.length(); i++) {
            if (wide) {
                bytes[2 + i * 2] = (byte) (value.charAt(i) >> 8);
                bytes[3 + i * 2] = (byte) value.charAt(i);
            } else {
                bytes[2 + i] = (byte) value.charAt(i);
            }
        }
        return bytes;
    }

    private static byte[] handshake(String value, boolean wide) {
        return concat(new byte[]{LegacyProtocol.HANDSHAKE}, string(value, wide));
    }

    /**
     * A login packet of the shape the version's own client sent: the id, the protocol version, the
     * username, whatever second string that version put after it, and then the eight zero bytes —
     * a map seed up to 1.1, two zero ints in 1.2 — followed by the trailing fields.
     */
    private static byte[] login(int protocol, boolean wide, int strings) {
        byte[] packet = concat(new byte[]{LegacyProtocol.LOGIN,
                (byte) (protocol >> 24), (byte) (protocol >> 16),
                (byte) (protocol >> 8), (byte) protocol}, string("Probe", wide));
        // The earliest Alpha ends after the password, with nothing behind it — which is why the
        // password itself is where its marker goes.
        if (strings == 0 || strings == 3) return concat(packet, string("Password", wide));
        if (strings >= 2) packet = concat(packet, string(protocol >= 28 ? "" : "Password", wide));
        return concat(packet, new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    }

    private static int differences(byte[] a, byte[] b) {
        int count = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) count++;
        }
        return count + Math.abs(a.length - b.length);
    }

    private static int nonZero(byte[] bytes) {
        int count = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != 0) count++;
        }
        return count;
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
                    same(LegacyProtocol.LOGIN_MARKER, java.util.Arrays.copyOfRange(
                            marked.toByteArray(), seedAt,
                            seedAt + LegacyProtocol.LOGIN_MARKER.length)), null);
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

            // An Alpha login carries a second string before the seed, and a 1.6 handshake a host
            // and a port, so the offset this computes lands on something else entirely there. The
            // marker only goes over zeros, which is what makes being wrong harmless.
            byte[] notBeta = concat(betaHandshake("Tester"), betaLogin("Tester"));
            notBeta[betaHandshake("Tester").length + 1 + 4 + 2 + "Tester".length() * 2] = 0x37;
            ByteArrayOutputStream untouched = new ByteArrayOutputStream();
            writeAll(LegacyStreams.markBetaLogin(untouched), notBeta, chunk);
            check("a packet shaped differently is left exactly as it was",
                    same(notBeta, untouched.toByteArray()), null);

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

        // One case per version, built from what that version's own client put on the wire —
        // protocol number, string encoding, and what followed the username — as recorded by
        // scripts/login-probe.sh into build/legacy-protocol/login.txt. The shapes below are not a
        // reading of the protocol, they are a transcription of six real captures, which is the
        // only reason to trust that the offsets are where the table says.
        System.out.println();
        System.out.println("== every shape below 1.7, as its own client sends it ==");
        String[][] measured = {
            // version, protocol, wide, strings before the eight bytes, and whether a handshake comes first
            {"a1.2.0",  "3",  "no",  "2", "yes"},
            {"b1.4_01", "10", "no",  "2", "yes"},
            {"b1.5_01", "11", "yes", "1", "yes"},
            {"b1.8.1",  "17", "yes", "1", "yes"},
            {"1.1",     "23", "yes", "1", "yes"},
            {"1.2.5",   "29", "yes", "2", "yes"},
            // The earliest Alpha has no seed at all: its login packet ends after the password, so
            // the password's own eight characters are the space.
            {"a1.1.2_01", "2", "no", "3", "yes"},
            // Protocol 10 twice over, and this pair is the dangerous one: b1.4_01 is 10 and so is
            // a1.0.11, which has no seed and no handshake either. Read as b1.4_01 it would be
            // looking for eight bytes past the end of its own login packet.
            {"a1.0.11", "10", "no", "3", "no"},
            {"a1.0.14", "12", "no", "3", "no"},
            // Protocol 14 twice over: a1.0.16 writes a byte a character and keeps its marker in the
            // password, b1.7.3 writes two and keeps it in the seed. Only the encoding tells them
            // apart, and getting it wrong would put the marker in the wrong field of the wrong era.
            {"a1.0.16", "14", "no",  "3", "yes"},
            {"b1.7.3",  "14", "yes", "1", "yes"},
        };

        for (int v = 0; v < measured.length; v++) {
            String version = measured[v][0];
            int protocol = Integer.parseInt(measured[v][1]);
            boolean wide = "yes".equals(measured[v][2]);
            int strings = Integer.parseInt(measured[v][3]);

            byte[] login = login(protocol, wide, strings);
            // Until a1.0.16 there is no handshake, and the login packet is the first thing sent.
            byte[] whole = "no".equals(measured[v][4]) ? login
                    : concat(handshake(version.startsWith("1.2") ? "Probe;127.0.0.1:25733" : "Probe",
                            wide), login);

            for (int c = 0; c < chunks.length; c++) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                writeAll(LegacyStreams.markBetaLogin(out), whole, chunks[c]);
                byte[] sent = out.toByteArray();

                check(version + ": the packets keep their length", sent.length == whole.length,
                        whole.length + " -> " + sent.length);

                LegacyStreams.Marked flag = new LegacyStreams.Marked();
                readAll(LegacyStreams.watchBetaLogin(new ByteArrayInputStream(sent), flag), chunks[c]);
                if (strings == 0) {
                    check(version + ": has nowhere to put a marker, and none is put",
                            !flag.isMarked() && same(whole, sent), null);
                } else {
                    check(version + ": the marker lands in the field it was meant for, "
                            + "and the server reads it", flag.isMarked(), null);
                    // Over a seed the packet was all zeros there, so exactly the marker's own
                    // non-zero bytes changed; over the password every one of the eight did, since
                    // no letter of "Password" is a letter of the marker. Either way the count is
                    // exact, and one more or one fewer means it landed somewhere else.
                    int expected = strings == 3
                            ? LegacyProtocol.PASSWORD_MARKER.length
                            : nonZero(LegacyProtocol.LOGIN_MARKER);
                    check(version + ": and nothing outside those eight bytes moved",
                            differences(whole, sent) == expected,
                            differences(whole, sent) + " bytes differ, wanted " + expected);
                }
            }
        }

        // From 1.3 the login packet has no room left, and the marker moves to the end of the host
        // in the handshake. The bytes below are the ones a 1.3.2 client really sent, recorded by
        // scripts/login-probe.sh: an id, one byte of protocol version, the username, the address it
        // was told to connect to, and the port.
        System.out.println();
        System.out.println("== 1.3 to 1.6.4, where the marker rides on the host ==");
        byte[] modern = concat(concat(new byte[]{LegacyProtocol.HANDSHAKE, 39},
                string("Probe", true)), concat(string("127.0.0.1", true),
                new byte[]{0, 0, (byte) 0x64, (byte) 0x8a}));

        for (int c = 0; c < chunks.length; c++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeAll(LegacyStreams.markModernHandshake(out), modern, chunks[c]);
            byte[] sent = out.toByteArray();

            check("the handshake grows by exactly the marker",
                    sent.length == modern.length + LegacyProtocol.HOST_MARKER.length() * 2,
                    modern.length + " -> " + sent.length);
            check("and the port still ends it, so the length field was updated too",
                    sent[sent.length - 1] == (byte) 0x8a && sent[sent.length - 2] == (byte) 0x64,
                    null);

            LegacyStreams.Marked flag = new LegacyStreams.Marked();
            byte[] asRead = readAll(LegacyStreams.watchModernHandshake(
                    new ByteArrayInputStream(sent), flag), chunks[c]);
            check("the server finds the marker on the host", flag.isMarked(), null);
            check("and reads the bytes exactly as they arrived", same(sent, asRead), null);

            // A server without Loki reads the host and drops it, so what it does with the marker is
            // nothing. What it must never do is find the next packet in the wrong place.
            LegacyStreams.Marked none = new LegacyStreams.Marked();
            readAll(LegacyStreams.watchModernHandshake(new ByteArrayInputStream(modern), none), chunks[c]);
            check("a client without Loki is not taken for one", !none.isMarked(), null);

            // The two eras' filters are stacked on every connection, so each has to leave the
            // other's handshake alone.
            byte[] beta = concat(betaHandshake("Tester"), betaLogin("Tester"));
            ByteArrayOutputStream untouched = new ByteArrayOutputStream();
            writeAll(LegacyStreams.markModernHandshake(untouched), beta, chunks[c]);
            check("and a Beta handshake goes through it unchanged",
                    same(beta, untouched.toByteArray()), null);
        }

        // From 1.3 the block is a plugin message rather than raw bytes, because from 1.3 there can
        // be a proxy in the middle and a proxy decodes what goes past it. The shape has to be one
        // the protocol allows: an id, a channel, a length and that many bytes.
        System.out.println();
        System.out.println("== the declaration as a packet, for the versions that have proxies ==");
        for (int c = 0; c < chunks.length; c++) {
            byte[] framed = LegacyProtocol.PLUGIN_MESSAGE.frame(API);
            check("it is a plugin message on Loki's own channel",
                    framed[0] == (byte) 0xFA && framed[1] == 0 && framed[2] == 4
                            && framed[4] == 'L' && framed[6] == 'o' && framed[8] == 'k'
                            && framed[10] == 'i', null);
            check("and its length field says how much follows the header",
                    LegacyProtocol.PLUGIN_MESSAGE.bodyLength(framed, framed.length)
                            == framed.length - LegacyProtocol.PLUGIN_MESSAGE.headerBytes(), null);

            // The server writes it in front of its first packet, and the client takes it back off.
            // Marked the way a server really learns it: by reading a handshake with the marker on
            // its host, rather than by being told from the test.
            LegacyStreams.Marked marked = new LegacyStreams.Marked();
            byte[] modernHello = concat(concat(new byte[]{LegacyProtocol.HANDSHAKE, 39},
                    string("Probe", true)), concat(string("127.0.0.1", true),
                    new byte[]{0, 0, (byte) 0x64, (byte) 0x8a}));
            ByteArrayOutputStream sent = new ByteArrayOutputStream();
            writeAll(LegacyStreams.markModernHandshake(sent), modernHello, chunks[c]);
            readAll(LegacyStreams.watchModernHandshake(
                    new ByteArrayInputStream(sent.toByteArray()), marked), chunks[c]);
            check("the server knows who it is talking to before it answers", marked.isMarked(), null);

            byte[] serverSide = concat(new byte[]{LegacyProtocol.ENCRYPTION_REQUEST, 0, 0}, nextPacket());
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            writeAll(LegacyStreams.appendAfter(wire, LegacyStreams.constant(0),
                    LegacyProtocol.ENCRYPTION_REQUEST, new LegacyStreams.Source() {
                        public String declaration() {
                            return API;
                        }
                    }, marked, LegacyProtocol.PLUGIN_MESSAGE), serverSide, chunks[c]);
            check("the packet goes out in front of the server's own",
                    same(concat(framed, serverSide), wire.toByteArray()), null);

            Recorder recorder = new Recorder();
            byte[] seenByGame = readAll(LegacyStreams.stripAfter(
                    new ByteArrayInputStream(wire.toByteArray()), LegacyStreams.constant(0),
                    LegacyProtocol.PLUGIN_MESSAGE.firstByte(), recorder,
                    LegacyProtocol.PLUGIN_MESSAGE), chunks[c]);
            check("the game reads what a plain server would have sent",
                    same(serverSide, seenByGame), null);
            check("and Loki got the declaration", API.equals(recorder.declaration),
                    recorder.declaration);

            // A plugin message on somebody else's channel is somebody else's business.
            byte[] other = LegacyProtocol.PLUGIN_MESSAGE.frame(API);
            other[4] = 'M'; // MC|Brand, FML|HS, anything at all
            Recorder none = new Recorder();
            byte[] untouched = readAll(LegacyStreams.stripAfter(
                    new ByteArrayInputStream(concat(other, serverSide)), LegacyStreams.constant(0),
                    LegacyProtocol.PLUGIN_MESSAGE.firstByte(), none, LegacyProtocol.PLUGIN_MESSAGE),
                    chunks[c]);
            check("another channel's message is left where it is",
                    same(concat(other, serverSide), untouched), null);
            check("and nothing is reported as declared", none.declaration == null, none.declaration);
        }

        // 1.3 changed the handshake into four fields, and reading it as one string walks off the
        // end of the packet. The filter has to recognise that and stand down rather than wait for
        // a boundary that is not coming.
        byte[] modernHandshake = {LegacyProtocol.HANDSHAKE, 39, 0, 5, 0, 'P', 0, 'r', 0, 'o', 0, 'b', 0, 'e'};
        for (int c = 0; c < chunks.length; c++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeAll(LegacyStreams.markBetaLogin(out), modernHandshake, chunks[c]);
            check("a 1.3 handshake goes out untouched", same(modernHandshake, out.toByteArray()), null);

            Recorder none = new Recorder();
            byte[] back = readAll(LegacyStreams.stripAfter(new ByteArrayInputStream(modernHandshake),
                    LegacyStreams.afterHandshake(), LegacyProtocol.HANDSHAKE, none), chunks[c]);
            check("and comes back untouched, with nothing declared",
                    same(modernHandshake, back) && none.declaration == null, none.declaration);
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
            writeAll(LegacyStreams.markBetaLogin(sent), http, chunks[c]);
            check("an HTTP request goes out byte for byte, at " + chunks[c] + " at a time",
                    same(http, sent.toByteArray()), null);

            ByteArrayOutputStream alsoSent = new ByteArrayOutputStream();
            writeAll(LegacyStreams.markModernHandshake(alsoSent), http, chunks[c]);
            check("and again through the filter that rewrites a handshake",
                    same(http, alsoSent.toByteArray()), null);

            Recorder none = new Recorder();
            byte[] received = readAll(LegacyStreams.stripAfter(new ByteArrayInputStream(http),
                    LegacyStreams.afterHandshake(), LegacyProtocol.HANDSHAKE, none), chunks[c]);
            check("and a reply comes back byte for byte", same(http, received), null);
            check("with nothing taken for a declaration", none.declaration == null, none.declaration);
        }

        System.out.println();
        System.out.println(failures == 0 ? "LegacyStreamsTest: PASSED"
                : "LegacyStreamsTest: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
