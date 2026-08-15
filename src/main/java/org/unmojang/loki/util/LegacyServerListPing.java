package org.unmojang.loki.util;

import org.unmojang.loki.util.logger.NilLogger;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The Server List Ping as it was before 1.7, which is the only channel those versions offer.
 * <p>
 * A client sends the single byte {@code 0xFE} and the server answers {@code 0xFF} followed by one
 * length-prefixed UTF-16BE string. There is no JSON and no room to spare: the whole answer is capped
 * at 256 characters from 1.3 and at 64 in Beta, and that budget is shared with the server's own MOTD.
 * <p>
 * Two shapes exist, and a server may answer either:
 * <ul>
 *   <li><b>extended</b>, from 1.4: section sign, then {@code 1}, then the fields separated by NUL:
 *       protocol, version, motd, online, max</li>
 *   <li><b>plain</b>, before that: motd, online and max separated by section signs</li>
 * </ul>
 * Both are read positionally by the game — it takes the fields it knows by index and never checks
 * how many arrived — so a server may append one more and every vanilla client of the era ignores it.
 * That was verified by reading the ping code of the 1.3.2, 1.4.7, 1.5.2, 1.6.4 and Beta 1.8.1
 * clients rather than assumed, because the whole approach rests on it.
 * <p>
 * Both separators are also the two characters those clients exempt from the filter that replaces
 * anything unrecognised with a question mark, which is why a declaration can be appended after one
 * and arrive intact.
 */
public final class LegacyServerListPing {
    /** What every client from 1.3 refuses to read past. Ours must fit inside it, MOTD included. */
    public static final int MAX_RESPONSE_CHARS = 256;
    /** Beta reads only this much, which is why a declaration there can be a host and nothing more. */
    public static final int MAX_RESPONSE_CHARS_BETA = 64;

    /** Marks the field as ours, so a server that appends something else is not mistaken for one. */
    public static final String MARKER = "loki=";

    private static final int PACKET_PING = 0xFE;
    private static final int PACKET_RESPONSE = 0xFF;

    /** Starts an extended response, and separates the fields of a plain one. */
    private static final char SECTION = (char) 0xA7;
    /** Separates the fields of an extended response. */
    private static final char NUL = (char) 0x00;

    private static final NilLogger log = NilLogger.get("Loki");

    private LegacyServerListPing() {}

    /**
     * Pings a pre-1.7 server and returns the raw response string, separators and all.
     *
     * @param timeoutMs applies to the connect and to the read, since a server of this era that
     *                  dislikes the request tends to answer by saying nothing at all
     */
    public static String response(String host, int port, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            OutputStream out = socket.getOutputStream();
            out.write(PACKET_PING);
            out.flush();

            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            int packetId = in.read();
            if (packetId != PACKET_RESPONSE) {
                throw new IOException("Expected 0xFF from a legacy server, got 0x"
                        + Integer.toHexString(packetId));
            }

            int length = in.readShort() & 0xFFFF;
            if (length > MAX_RESPONSE_CHARS) {
                // Not fatal here, since we are not the game, but worth saying: this server is
                // already sending more than any client of its era will read
                log.warn("Legacy server answered with " + length + " characters, more than the "
                        + MAX_RESPONSE_CHARS + " its clients accept");
            }
            char[] chars = new char[length];
            for (int i = 0; i < length; i++) chars[i] = in.readChar();
            return new String(chars);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Recovers what Loki appended, or null if this server appended nothing of ours.
     * <p>
     * Looked for after the last separator only. Everything before it belongs to the server's own
     * status and is none of our business, and a marker sitting inside a MOTD is a MOTD.
     */
    public static String declaration(String response) {
        if (response == null || response.length() == 0) return null;

        char separator = separatorOf(response);
        int at = response.lastIndexOf(separator + MARKER);
        if (at < 0) return null;

        String declared = response.substring(at + 1 + MARKER.length());
        return declared.length() == 0 ? null : declared;
    }

    /**
     * The response a server should send, with the declaration appended, or the response untouched
     * when it does not fit.
     * <p>
     * Fitting is not a nicety. A client of this era reads the response with a hard limit and throws
     * when it is exceeded, and a thrown ping is not a missing field — it is the server showing up as
     * unreachable in the list. So the declaration is the first thing to go, always.
     *
     * @param budget {@link #MAX_RESPONSE_CHARS}, or {@link #MAX_RESPONSE_CHARS_BETA} for a server
     *               old enough that its clients read only 64
     */
    public static String append(String response, String declaration, int budget) {
        if (response == null || declaration == null || declaration.length() == 0) return response;

        String appended = response + separatorOf(response) + MARKER + declaration;
        if (appended.length() <= budget) return appended;

        log.debug("Not declaring to legacy clients: " + appended.length() + " characters would not"
                + " fit in " + budget + ", and an oversized ping is a server that looks offline");
        return response;
    }

    /** An extended response separates by NUL; the plain one that came before it, by the section. */
    private static char separatorOf(String response) {
        return response.length() != 0 && response.charAt(0) == SECTION ? NUL : SECTION;
    }
}
