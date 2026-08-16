import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * An Alpha or Beta client, reduced to the handshake and the login, which is where the announcement
 * lives.
 * <p>
 * The counterpart of {@link LegacyClient}, which speaks Classic. Same idea: run it without the agent
 * and it is a vanilla client writing exactly what a vanilla client writes; run it with the agent and
 * Loki's filters install themselves underneath, without a line of difference here.
 *
 * <h2>What it prints</h2>
 * <ul>
 *   <li>{@code hash} — the connection hash the server answered the handshake with</li>
 *   <li>{@code next} — the packet id right after that. A Loki server puts its block there and a Loki
 *       client takes it back off, so this must read 1, the login reply, in every combination. If it
 *       ever reads fe the block reached the game.</li>
 *   <li>{@code session} — what Loki ended up pointing profile lookups at, or nothing</li>
 * </ul>
 *
 * @param args host, port, and the username to log in as
 */
public class BetaClient {
    private static void writeString(DataOutputStream out, String value) throws Exception {
        out.writeShort(value.length());
        for (int i = 0; i < value.length(); i++) out.writeChar(value.charAt(i));
    }

    private static String readString(DataInputStream in) throws Exception {
        int characters = in.readShort();
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < characters; i++) value.append(in.readChar());
        return value.toString();
    }

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String username = args.length > 2 ? args[2] : "Tester";
        // No ping to ask in this era, so the caller says which protocol the server speaks. Getting
        // it wrong is answered with "Outdated server!" and a disconnect, not with a hint.
        int protocol = Integer.parseInt(System.getProperty("beta.protocol", "17"));

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            out.writeByte(0x02);
            writeString(out, username);
            out.flush();

            int replyId = in.read();
            System.out.println("reply=" + Integer.toHexString(replyId));
            String hash = replyId == 0x02 ? readString(in) : "?";
            System.out.println("hash=" + hash);

            // What the game does between the handshake and the login when the server is in online
            // mode: tell its own session server that it is joining this one. Loki turns this into a
            // modern POST /session/minecraft/join, so a legacy client can authenticate against an
            // API server that has never heard of joinserver.jsp.
            if (args.length > 3 && !"-".equals(hash)) {
                String token = args[3];
                String uuid = args.length > 4 ? args[4] : "";
                String joinUrl = "http://session.minecraft.net/game/joinserver.jsp"
                        + "?user=" + username
                        + "&sessionId=token:" + token + ":" + uuid
                        + "&serverId=" + hash;
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URI(joinUrl).toURL().openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                System.out.println("joined=" + conn.getResponseCode());
                conn.disconnect();
            }

            // The login packet, where the map seed is the eight bytes a Loki client overwrites
            out.writeByte(0x01);
            out.writeInt(protocol);
            writeString(out, username);
            out.writeLong(0L);
            out.writeInt(0);
            out.writeByte(0);
            out.writeByte(0);
            out.writeByte(0);
            out.writeByte(0);
            out.flush();

            System.out.println("next=" + Integer.toHexString(in.read()));
        } catch (Exception e) {
            System.out.println("error=" + e);
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }

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
