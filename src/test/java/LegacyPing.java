import org.unmojang.loki.util.LegacyServerListPing;

/**
 * The pre-1.7 ping, from the command line, so a shell script can ask a real server what it says.
 * <p>
 * The counterpart of {@link ServerPing}, which speaks the protocol from 1.7 onwards. A server of
 * this era answers with one string and no JSON, so the two lines printed here are the raw response,
 * with its separators made visible because a NUL does not survive a shell, and whatever Loki finds
 * declared in it.
 *
 * @param args host, port, then a timeout in milliseconds
 */
public class LegacyPing {
    public static void main(String[] args) throws Exception {
        String response = LegacyServerListPing.response(args[0], Integer.parseInt(args[1]),
                Integer.parseInt(args[2]));

        System.out.println("raw=" + response.replace((char) 0x00, '|').replace((char) 0xA7, '$'));
        String declared = LegacyServerListPing.declaration(response);
        System.out.println("declaration=" + (declared == null ? "" : declared));
    }
}
