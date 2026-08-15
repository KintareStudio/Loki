import org.unmojang.loki.util.LegacyServerListPing;

import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The pre-1.7 ping, against a server that speaks it.
 * <p>
 * The numbers here are not chosen, they are what the clients of that era do: 256 characters from
 * 1.3, 64 in Beta, fields read by index, and the two separators that survive the character filter.
 * They were read out of the 1.3.2, 1.4.7, 1.5.2, 1.6.4 and Beta 1.8.1 client jars.
 * <p>
 * The case that matters most is the one that refuses: a response over the limit does not lose a
 * field, it makes the whole ping throw, and the server disappears from the player's list. Declaring
 * has to be the thing that gives way.
 */
public class LegacyPingTest {
    private static final char SECTION = (char) 0xA7;
    private static final char NUL = (char) 0x00;

    private static int failures = 0;

    private static void check(String label, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + label
                + (detail == null ? "" : "  [" + detail + "]"));
    }

    /** A server of that era: one byte in, 0xFF and a UTF-16BE string out. */
    private static final class LegacyServer extends Thread {
        private final ServerSocket socket;
        private final String response;

        LegacyServer(String response) throws Exception {
            this.socket = new ServerSocket(0);
            this.response = response;
            setDaemon(true);
        }

        int port() {
            return socket.getLocalPort();
        }

        public void run() {
            try {
                while (true) {
                    Socket client = socket.accept();
                    client.getInputStream().read(); // the 0xFE
                    OutputStream out = client.getOutputStream();
                    DataOutputStream data = new DataOutputStream(out);
                    data.write(0xFF);
                    data.writeShort(response.length());
                    for (int i = 0; i < response.length(); i++) data.writeChar(response.charAt(i));
                    data.flush();
                    client.close();
                }
            } catch (Exception e) {
                // The test finished and closed us
            }
        }
    }

    /** The bytes a server writes: 0xFF, a character count, and that many UTF-16BE characters. */
    private static byte[] frameOf(String response) {
        byte[] frame = new byte[3 + response.length() * 2];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) (response.length() >> 8);
        frame[2] = (byte) response.length();
        for (int i = 0; i < response.length(); i++) {
            frame[3 + i * 2] = (byte) (response.charAt(i) >> 8);
            frame[4 + i * 2] = (byte) response.charAt(i);
        }
        return frame;
    }

    private static String stringOf(byte[] frame) {
        int count = ((frame[1] & 0xFF) << 8) | (frame[2] & 0xFF);
        char[] chars = new char[count];
        for (int i = 0; i < count; i++) {
            chars[i] = (char) (((frame[3 + i * 2] & 0xFF) << 8) | (frame[4 + i * 2] & 0xFF));
        }
        return new String(chars);
    }

    private static String extended(String motd) {
        return SECTION + "1" + NUL + "78" + NUL + "1.6.4" + NUL + motd + NUL + "0" + NUL + "20";
    }

    private static String plain(String motd) {
        return motd + SECTION + "0" + SECTION + "20";
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.println("LegacyPingTest: CRASHED");
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        String api = "https://drasl.kintare.studio/authlib-injector";

        System.out.println();
        System.out.println("== the extended response, as 1.4 and later send it ==");
        String declared = LegacyServerListPing.append(extended("A Minecraft Server"), api,
                LegacyServerListPing.MAX_RESPONSE_CHARS);
        check("the declaration is appended after a NUL, where the fields are",
                declared.indexOf(NUL + "loki=" + api) > 0, null);
        check("and comes back out", api.equals(LegacyServerListPing.declaration(declared)),
                LegacyServerListPing.declaration(declared));
        check("the fields the game reads are untouched",
                declared.startsWith(extended("A Minecraft Server")), null);

        System.out.println();
        System.out.println("== the plain response, as 1.3 and Beta send it ==");
        String plainDeclared = LegacyServerListPing.append(plain("A Minecraft Server"), api,
                LegacyServerListPing.MAX_RESPONSE_CHARS);
        check("the declaration is appended after a section sign",
                plainDeclared.indexOf(SECTION + "loki=" + api) > 0, null);
        check("and comes back out", api.equals(LegacyServerListPing.declaration(plainDeclared)),
                LegacyServerListPing.declaration(plainDeclared));

        System.out.println();
        System.out.println("== a server that declared nothing ==");
        check("is not read as declaring something",
                LegacyServerListPing.declaration(extended("A Minecraft Server")) == null, null);
        check("nor is an empty response", LegacyServerListPing.declaration("") == null, null);

        System.out.println();
        System.out.println("== when it does not fit ==");
        StringBuilder longMotd = new StringBuilder();
        for (int i = 0; i < 200; i++) longMotd.append("x");
        String tooLong = extended(longMotd.toString());
        check("the declaration gives way rather than the ping",
                tooLong.equals(LegacyServerListPing.append(tooLong, api,
                        LegacyServerListPing.MAX_RESPONSE_CHARS)), null);
        // With Beta's 64 and a MOTD anyone would actually write, a bare host is what is left
        String beta = plain("A Minecraft Server");
        check("a host still fits on Beta's 64",
                LegacyServerListPing.append(beta, "kintare.studio",
                        LegacyServerListPing.MAX_RESPONSE_CHARS_BETA).length()
                        <= LegacyServerListPing.MAX_RESPONSE_CHARS_BETA, null);
        check("but a URL does not, and is refused rather than truncated",
                beta.equals(LegacyServerListPing.append(beta, api,
                        LegacyServerListPing.MAX_RESPONSE_CHARS_BETA)), null);

        System.out.println();
        System.out.println("== rewriting the frame a server is about to write ==");
        String original = plain("A Minecraft Server");
        byte[] frame = frameOf(original);
        byte[] rewritten = LegacyServerListPing.rewriteResponse(frame, api,
                LegacyServerListPing.MAX_RESPONSE_CHARS);
        check("the count in the header is updated, not just the text",
                ((rewritten[1] & 0xFF) << 8 | (rewritten[2] & 0xFF)) == original.length()
                        + 1 + "loki=".length() + api.length(),
                String.valueOf((rewritten[1] & 0xFF) << 8 | (rewritten[2] & 0xFF)));
        check("and what comes out parses back to the declaration",
                api.equals(LegacyServerListPing.declaration(stringOf(rewritten))), null);

        check("a frame that is not a response is passed through",
                LegacyServerListPing.rewriteResponse(new byte[]{0x02, 0, 0}, api, 256)[0] == 0x02,
                null);
        byte[] truncated = new byte[]{(byte) 0xFF, 0, 9, 0, 'x'};
        check("so is one whose length does not match what arrived",
                LegacyServerListPing.rewriteResponse(truncated, api, 256) == truncated, null);
        check("and one that would not fit is left exactly as it was",
                LegacyServerListPing.rewriteResponse(frame, api,
                        LegacyServerListPing.MAX_RESPONSE_CHARS_BETA) == frame, null);

        System.out.println();
        System.out.println("== over the wire ==");
        LegacyServer server = new LegacyServer(declared);
        server.start();
        String received = LegacyServerListPing.response("127.0.0.1", server.port(), 5000);
        check("the response survives the round trip", declared.equals(received), null);
        check("and the declaration is recovered from it",
                api.equals(LegacyServerListPing.declaration(received)), null);

        System.out.println();
        System.out.println(failures == 0 ? "LegacyPingTest: PASSED"
                : "LegacyPingTest: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
