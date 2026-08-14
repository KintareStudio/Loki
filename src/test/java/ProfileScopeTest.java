import org.unmojang.loki.hooks.ProfileRedirect;

/**
 * Guards the boundary of the server-declared profile API: which endpoints a game server is allowed
 * to point the client at, and which it is not.
 * <p>
 * If a change here needs the expectations relaxed, that change is widening what a third party can
 * redirect. Read doc/profile-redirect.md before touching the list.
 */
public class ProfileScopeTest {
    private static int failures = 0;

    private static void expect(String label, String actual, String expected) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + label + " -> " + actual);
    }

    private static void expect(String label, boolean actual, boolean expected) {
        if (actual != expected) failures++;
        System.out.println((actual == expected ? "  ok   " : "  FAIL ") + label + " -> " + actual);
    }

    public static void main(String[] args) {
        String session = "https://declared.example/sessionserver";
        String account = "https://declared.example/api";
        String services = "https://declared.example/minecraftservices";
        System.setProperty("Loki.profile_redirect.session", session);
        System.setProperty("Loki.profile_redirect.account", account);
        System.setProperty("Loki.profile_redirect.services", services);

        System.out.println("== profile reads are redirected ==");
        expect("textures by uuid",
                ProfileRedirect.baseFor("sessionserver.mojang.com", "/session/minecraft/profile/abc"), session);
        expect("name to uuid",
                ProfileRedirect.baseFor("api.mojang.com", "/users/profiles/minecraft/Notch"), account);
        expect("name to uuid, batch",
                ProfileRedirect.baseFor("api.mojang.com", "/profiles/minecraft"), account);
        expect("services lookup by name",
                ProfileRedirect.baseFor("api.minecraftservices.com", "/minecraft/profile/lookup/name/Notch"), services);
        expect("services lookup, bulk",
                ProfileRedirect.baseFor("api.minecraftservices.com", "/minecraft/profile/lookup/bulk/byname"), services);

        System.out.println();
        System.out.println("== everything else is not ==");
        expect("join", ProfileRedirect.baseFor("sessionserver.mojang.com", "/session/minecraft/join"), null);
        expect("hasJoined", ProfileRedirect.baseFor("sessionserver.mojang.com", "/session/minecraft/hasJoined"), null);
        expect("authserver", ProfileRedirect.baseFor("authserver.mojang.com", "/authenticate"), null);
        expect("own profile", ProfileRedirect.baseFor("api.minecraftservices.com", "/minecraft/profile"), null);
        expect("certificates", ProfileRedirect.baseFor("api.minecraftservices.com", "/player/certificates"), null);
        expect("public keys", ProfileRedirect.baseFor("api.minecraftservices.com", "/publickeys"), null);
        expect("attributes", ProfileRedirect.baseFor("api.minecraftservices.com", "/player/attributes"), null);
        expect("blocklist", ProfileRedirect.baseFor("api.minecraftservices.com", "/privacy/blocklist"), null);
        expect("reports", ProfileRedirect.baseFor("api.minecraftservices.com", "/player/report"), null);
        expect("telemetry", ProfileRedirect.baseFor("api.minecraftservices.com", "/eventlog/v1/events"), null);
        expect("profile prefix without an id",
                ProfileRedirect.baseFor("sessionserver.mojang.com", "/session/minecraft/profile"), null);
        expect("lookup prefix that only looks like one",
                ProfileRedirect.baseFor("api.minecraftservices.com", "/minecraft/profile/lookupfoo"), null);
        expect("a host we do not rewrite",
                ProfileRedirect.baseFor("declared.example", "/session/minecraft/profile/abc"), null);

        System.out.println();
        System.out.println("== requests carrying credentials are refused ==");
        expect("authorization header", ProfileRedirect.carriesCredentials("Bearer tok", null), true);
        expect("accessToken in the query", ProfileRedirect.carriesCredentials(null, "accessToken=tok"), true);
        expect("an ordinary profile query", ProfileRedirect.carriesCredentials(null, "unsigned=false"), false);

        System.out.println();
        System.out.println("== kill switch ==");
        System.setProperty("Loki.disable_profile_redirect", "true");
        expect("nothing is redirected when disabled",
                ProfileRedirect.baseFor("sessionserver.mojang.com", "/session/minecraft/profile/abc"), null);

        System.out.println();
        System.out.println(failures == 0 ? "ProfileScopeTest: PASSED" : "ProfileScopeTest: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
