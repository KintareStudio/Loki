import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A Classic client, reduced to the part that matters: it identifies itself and reports what the
 * server said back.
 * <p>
 * The same program is both ends of the comparison. Run without the agent it is a vanilla client and
 * writes the bytes a vanilla client writes; run with the agent attached, Loki's filters install
 * themselves on the socket underneath it and it becomes a Loki client, without a line of difference
 * here. That is the whole point — if the two runs see different bytes, the feature is wrong.
 *
 * <h2>What it prints</h2>
 * <ul>
 *   <li>{@code identification} — the server's first 131 bytes, as hex, which the game parses</li>
 *   <li>{@code next} — the byte right after them. A Loki server appends its block there and a Loki
 *       client takes it back off, so this must read 0x02, the Level Initialize packet, in every
 *       combination. If it ever reads 0xFE the block reached the game.</li>
 *   <li>{@code session} — what Loki ended up pointing profile lookups at, or nothing</li>
 * </ul>
 *
 * @param args host, port, and the username to identify as
 */
public class LegacyClient {
    private static final int IDENTIFICATION_BYTES = 131;

    /** A Classic string field: 64 bytes, space padded. */
    private static void writeField(DataOutputStream out, String value) throws Exception {
        StringBuilder padded = new StringBuilder(value);
        while (padded.length() < 64) padded.append(' ');
        out.write(padded.toString().substring(0, 64).getBytes("US-ASCII"));
    }

    private static String hex(byte[] bytes, int length) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < length; i++) {
            text.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
            text.append(Character.forDigit(bytes[i] & 0xF, 16));
        }
        return text.toString();
    }

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String username = args.length > 2 ? args[2] : "Tester";

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeByte(0x00); // Player Identification
            out.writeByte(0x07); // protocol version
            writeField(out, username);
            writeField(out, "0"); // verification key, unchecked with verify-names off
            out.writeByte(0x00); // the byte a Loki client overwrites on its way out
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] identification = new byte[IDENTIFICATION_BYTES];
            int got = 0;
            while (got < IDENTIFICATION_BYTES) {
                int read = in.read(identification, got, IDENTIFICATION_BYTES - got);
                if (read < 0) break;
                got += read;
            }
            System.out.println("read=" + got);
            System.out.println("identification=" + hex(identification, got));

            int next = in.read();
            System.out.println("next=" + (next < 0 ? "eof" : Integer.toHexString(next)));
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }

        // Only meaningful with the agent attached; without it this class is not even on the path
        String session = null;
        try {
            session = (String) Class.forName("org.unmojang.loki.hooks.ProfileRedirect")
                    .getMethod("sessionBase").invoke(null);
        } catch (Throwable ignored) {
            // No Loki here, which is the vanilla half of the comparison
        }
        System.out.println("session=" + (session == null ? "" : session));
    }
}
