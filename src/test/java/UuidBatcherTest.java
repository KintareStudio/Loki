import org.unmojang.loki.util.UuidBatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the batcher does when the batch route answers, but answers nothing.
 * <p>
 * Not a hypothetical. An API server mirroring Mojang's may implement the per-name route and answer
 * {@code 200 []} to every batch it is given, and taking that as the answer turns every player on
 * that server into "no UUID lookup route succeeded" — which is what it did, on a real server, for
 * every name including the player's own.
 * <p>
 * The distinction being tested is between a batch that omits a name, which is Mojang saying it does
 * not know it, and a batch that omits everything, which says nothing at all about any of them.
 */
public class UuidBatcherTest {
    private static int failures = 0;

    private static void check(String label, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + label
                + (detail == null ? "" : "  [" + detail + "]"));
    }

    /** A server that resolves names one at a time and answers every batch with an empty list. */
    private static final class SingleOnly implements UuidBatcher.Resolver {
        final AtomicInteger batches = new AtomicInteger();
        final AtomicInteger singles = new AtomicInteger();
        final Map<String, String> known = new HashMap<String, String>();

        public Map<String, String> batchLookup(List<String> usernames) {
            batches.incrementAndGet();
            return new HashMap<String, String>();
        }

        public String singleLookup(String username) {
            singles.incrementAndGet();
            return known.get(username.toLowerCase());
        }
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.println("UuidBatcherTest: CRASHED");
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        System.out.println();
        System.out.println("== an API server whose batch route answers with nothing ==");

        SingleOnly server = new SingleOnly();
        server.known.put("erin_max_", "297e3f89567945a594b9bcb0924f7582");
        server.known.put("shaki", "73ce137181a54d4a9e377e82f72f1541");
        UuidBatcher batcher = new UuidBatcher("Test-Uuid", server);

        check("a name it knows resolves anyway",
                "297e3f89567945a594b9bcb0924f7582".equals(batcher.getUUID("Erin_Max_")), null);
        check("and so does the next one",
                "73ce137181a54d4a9e377e82f72f1541".equals(batcher.getUUID("Shaki")), null);
        check("the batch route was tried before being given up on", server.batches.get() >= 1,
                String.valueOf(server.batches.get()));

        // And it keeps being tried, because an empty answer is evidence about that call and not
        // about the route forever: the server this was written for answers correctly now and then
        int batchesSoFar = server.batches.get();
        List<String> more = new ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            server.known.put("player" + i, "0000000000000000000000000000000" + i);
            more.add("Player" + i);
        }
        for (int i = 0; i < more.size(); i++) {
            check("a later name resolves too", more.get(i) != null
                    && batcher.getUUID(more.get(i)) != null, more.get(i));
        }
        check("and the batch route is still being attempted", server.batches.get() > batchesSoFar,
                server.batches.get() + " vs " + batchesSoFar);

        // A name nothing knows still fails, since falling back is not the same as inventing
        boolean threwOrNull;
        try {
            threwOrNull = batcher.getUUID("NobodyAtAll") == null;
        } catch (Exception e) {
            threwOrNull = true;
        }
        check("a name that really is unknown still comes back unresolved", threwOrNull, null);

        System.out.println();
        System.out.println(failures == 0 ? "UuidBatcherTest: PASSED"
                : "UuidBatcherTest: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
