import org.unmojang.loki.LokiUtil;
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

    public static void main(String[] args) throws Exception {
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
        System.out.println("== what a server declares about itself ==");
        expect("nothing to declare when it was pointed nowhere",
                org.unmojang.loki.hooks.ProfileAdvertiser.declaration(), null);

        // Its own hosts, whichever way it was configured. No root is assumed, and none is needed.
        System.setProperty("minecraft.api.session.host", "https://api.example/sessions");
        System.setProperty("minecraft.api.services.host", "https://elsewhere.example/svc");
        expect("the endpoints it actually uses, named outright",
                org.unmojang.loki.hooks.ProfileAdvertiser.declaration(),
                "\"session\":\"https://api.example/sessions\","
                        + "\"services\":\"https://elsewhere.example/svc\"");

        System.setProperty(ProfileRedirect.PROP_TEXTURE_DOMAINS, "cdn.example,other.example");
        expect("with the texture domains it knows, so the client need not go asking",
                org.unmojang.loki.hooks.ProfileAdvertiser.declaration(),
                "\"session\":\"https://api.example/sessions\","
                        + "\"services\":\"https://elsewhere.example/svc\","
                        + "\"skinDomains\":[\"cdn.example\",\"other.example\"]");
        System.clearProperty(ProfileRedirect.PROP_TEXTURE_DOMAINS);

        // Secure profile enforcement is about this server, not about where its profiles live, so it
        // is said on its own and is worth saying even by a server that moves nothing
        System.setProperty("Loki.enforce_secure_profile", "true");
        expect("that it enforces secure profiles, when it does",
                org.unmojang.loki.hooks.ProfileAdvertiser.declaration(),
                "\"session\":\"https://api.example/sessions\","
                        + "\"services\":\"https://elsewhere.example/svc\","
                        + "\"enforceSecureProfile\":true");

        System.clearProperty("minecraft.api.session.host");
        System.clearProperty("minecraft.api.services.host");
        expect("and says so even with no API server of its own to name",
                org.unmojang.loki.hooks.ProfileAdvertiser.declaration(),
                "\"enforceSecureProfile\":true");

        System.clearProperty("Loki.enforce_secure_profile");
        expect("and says nothing at all when it does not",
                org.unmojang.loki.hooks.ProfileAdvertiser.declaration(), null);

        System.out.println();
        System.out.println("== metadata a launcher prefetched ==");
        String document = "{\"meta\":{\"serverName\":\"prefetched\"}}";
        String encoded = java.util.Base64.getEncoder().encodeToString(document.getBytes("UTF-8"));
        expect("nothing to read when no launcher set it", LokiUtil.prefetchedMetadata(), null);

        System.setProperty("org.to2mbn.authlibinjector.config.prefetched", encoded);
        expect("the name authlib-injector deprecated is still read",
                LokiUtil.prefetchedMetadata(), document);

        String newer = "{\"meta\":{\"serverName\":\"newer\"}}";
        System.setProperty("authlibinjector.yggdrasil.prefetched",
                java.util.Base64.getEncoder().encodeToString(newer.getBytes("UTF-8")));
        expect("and the current name wins over it", LokiUtil.prefetchedMetadata(), newer);

        System.setProperty("authlibinjector.yggdrasil.prefetched", "not base64 @@@");
        System.clearProperty("org.to2mbn.authlibinjector.config.prefetched");
        expect("something unreadable is ignored rather than thrown",
                LokiUtil.prefetchedMetadata(), null);
        System.clearProperty("authlibinjector.yggdrasil.prefetched");

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
