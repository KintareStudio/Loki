import org.unmojang.loki.util.ServerListPing;

/**
 * Prints a server's status JSON, using the same ping client Loki uses on a joining player.
 * <p>
 * Driven by {@code scripts/real-server-test.sh}, which stands up a real Minecraft server to check
 * that Loki declared its API server in the response. Not part of {@code ant test}: that one stands
 * up its own servers and needs no downloads.
 */
public class ServerPing {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25565;
        int timeoutMs = args.length > 2 ? Integer.parseInt(args[2]) : 5000;
        System.out.println(ServerListPing.statusJson(host, port, timeoutMs));
    }
}
