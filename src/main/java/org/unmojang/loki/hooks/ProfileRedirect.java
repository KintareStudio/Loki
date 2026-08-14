package org.unmojang.loki.hooks;

import org.unmojang.loki.util.HttpUtil;
import org.unmojang.loki.util.Json;
import org.unmojang.loki.util.ServerListPing;
import org.unmojang.loki.util.logger.NilLogger;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lets a game server tell the client which API server to use <em>for profile queries only</em>.
 * <p>
 * A server advertises this in its Server List Ping response, which every 1.7+ server already
 * serves and which unknown-field-tolerant clients ignore:
 * <pre>
 * { "version": {...}, "players": {...}, "description": {...},
 *   "loki": { "profileApi": "https://drasl.example.com/authlib-injector" } }
 * </pre>
 * Loki performs that ping itself (see {@link ServerListPing}) rather than reading the game's, so
 * this works on any client from 1.7 up, obfuscated or not, vanilla or modded.
 *
 * <h2>Why this is deliberately narrow</h2>
 * A redirect declared by a remote party is only safe because of what it structurally cannot reach.
 * The override lives in its own map, is never merged into {@code RequestInterceptor.YGGDRASIL_MAP},
 * and {@link #baseFor} answers only for read-only, unauthenticated profile lookups. Authentication,
 * {@code join}/{@code hasJoined}, certificates, public keys, player attributes, the blocklist,
 * reports and telemetry are not on the list and therefore keep going to the configured API server.
 * Callers additionally refuse to redirect any request carrying credentials, so a future endpoint
 * added to the allowlist by mistake still cannot leak an access token.
 */
public final class ProfileRedirect {
    /** Key the server puts in its status JSON. */
    private static final String STATUS_KEY = "loki";
    private static final String STATUS_FIELD = "profileApi";

    /** Cross-classloader bus. Loki's hooks are duplicated per classloader, system properties are not. */
    private static final String PROP_PEER = "Loki.profile_redirect.peer";
    private static final String PROP_ORIGIN = "Loki.profile_redirect.origin";
    private static final String PROP_SESSION = "Loki.profile_redirect.session";
    private static final String PROP_ACCOUNT = "Loki.profile_redirect.account";
    private static final String PROP_SERVICES = "Loki.profile_redirect.services";
    /** Comma separated. Empty or absent means "allow any texture domain", matching Loki's default. */
    public static final String PROP_TEXTURE_DOMAINS = "Loki.texture_domains";

    private static final int PING_TIMEOUT_MS = 3000;
    private static final int HTTP_TIMEOUT_MS = 5000;

    private static final NilLogger log = NilLogger.get("Loki");
    private static final ConcurrentHashMap<String, Discovery> discoveries = new ConcurrentHashMap<String, Discovery>();
    private static final ExecutorService DISCOVERY_POOL = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger threadId = new AtomicInteger(1);
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Loki-ProfileRedirect-" + threadId.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    private ProfileRedirect() {}

    private static final class Discovery {
        final CountDownLatch done = new CountDownLatch(1);
        volatile String session;
        volatile String account;
        volatile String services;
        volatile List<String> skinDomains = new ArrayList<String>();

        boolean found() {
            return session != null;
        }
    }

    private static boolean disabled() {
        return Boolean.getBoolean("Loki.disable_profile_redirect");
    }

    // ---------------------------------------------------------------- discovery

    /**
     * Called from {@code io.netty.bootstrap.Bootstrap#connect}, which every 1.7+ client funnels
     * both pings and joins through, on every transport.
     * <p>
     * A connect on its own is not an arrival: the multiplayer screen opens one to every server on
     * the list. {@link HandshakeWatcher} reads the handshake this connection is about to write and
     * calls {@link #noteJoin} only for the one that asks to move to the login state.
     *
     * @param future        the {@code ChannelFuture} the connect returned
     * @param socketAddress the connect target. Both are typed as Object so the injected call site
     *                      does not depend on where Netty happens to be relocated to
     */
    public static void noteConnect(Object future, Object socketAddress) {
        if (disabled() || !(socketAddress instanceof InetSocketAddress)) return;
        try {
            InetSocketAddress address = (InetSocketAddress) socketAddress;
            String host = hostOf(address);
            if (host == null || host.length() == 0) return;
            HandshakeWatcher.watch(future, host, address.getPort());
        } catch (Throwable t) {
            log.debug("Failed to note connection for profile redirect: " + t);
        }
    }

    /** Called once the handshake has shown that this connection is the player arriving. */
    static void noteJoin(String host, int port) {
        String peer = host + ":" + port;
        if (peer.equals(System.getProperty(PROP_PEER))) return; // reconnect to the same server

        System.setProperty(PROP_PEER, peer);
        clearActiveOverride();
        log.debug("Joining " + peer + ", checking for a profile API declaration");
        discover(peer, host, port);
    }

    private static String hostOf(InetSocketAddress address) {
        try { // getHostString is Java 7+, and unlike getHostName it never triggers a reverse lookup
            return (String) InetSocketAddress.class.getMethod("getHostString").invoke(address);
        } catch (Throwable ignored) {}
        if (address.isUnresolved() || address.getAddress() == null) return address.getHostName();
        return address.getAddress().getHostAddress();
    }

    private static void discover(final String peer, final String host, final int port) {
        Discovery existing = discoveries.get(peer);
        if (existing != null) {
            if (existing.done.getCount() == 0) publishIfCurrent(peer, existing);
            return; // already resolved or in flight
        }
        final Discovery discovery = new Discovery();
        if (discoveries.putIfAbsent(peer, discovery) != null) return;

        DISCOVERY_POOL.execute(new Runnable() {
            public void run() {
                try {
                    resolve(discovery, host, port);
                } catch (Throwable t) {
                    log.debug("No profile API declared by " + peer + " (" + t + ")");
                } finally {
                    discovery.done.countDown();
                    publishIfCurrent(peer, discovery);
                }
            }
        });
    }

    private static void resolve(Discovery discovery, String host, int port) throws Exception {
        String statusJson = ServerListPing.statusJson(host, port, PING_TIMEOUT_MS);
        Json.JSONObject declaration = new Json.JSONObject(statusJson).optJSONObject(STATUS_KEY);
        if (declaration == null) return;

        String profileApi = declaration.optString(STATUS_FIELD, "");
        if (profileApi.length() == 0) return;
        profileApi = canonicalize(profileApi);
        if (profileApi == null || !allowsCleartext(profileApi)) return;

        String apiRoot = followApiLocation(profileApi);
        if (!allowsCleartext(apiRoot)) return; // the redirect header could point elsewhere
        readSkinDomains(discovery, apiRoot);

        discovery.account = apiRoot + "/api";
        discovery.services = apiRoot + "/minecraftservices";
        discovery.session = apiRoot + "/sessionserver"; // set last, it is the "found" flag
        log.info("Server declared a profile API: " + apiRoot);
    }

    /** Rejects anything that is not plainly an http(s) URL, and defaults a bare host to https. */
    private static String canonicalize(String url) {
        String canonical = url.trim();
        if (canonical.indexOf("://") == -1) canonical = "https://" + canonical;
        if (!canonical.startsWith("http://") && !canonical.startsWith("https://")) {
            log.warn("Ignoring profile API declaration with unsupported scheme: " + url);
            return null;
        }
        while (canonical.endsWith("/")) canonical = canonical.substring(0, canonical.length() - 1);
        try {
            new URL(canonical);
        } catch (Exception e) {
            log.warn("Ignoring malformed profile API declaration: " + url);
            return null;
        }
        return canonical;
    }

    /**
     * Whether a declaration is allowed to be cleartext.
     * <p>
     * A game server must not be able to move profile lookups onto plain HTTP, so an {@code http://}
     * declaration is only honoured where cleartext was already on the table: a private or loopback
     * address, which is how LAN and test servers are reached, or a configured API server that is
     * itself {@code http://}, in which case the user has already accepted it.
     */
    static boolean allowsCleartext(String url) {
        if (url == null || !url.startsWith("http://")) return true;

        String host;
        try {
            host = new URL(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (isPrivateHost(host)) return true;
        if (System.getProperty("minecraft.api.session.host", "https://sessionserver.mojang.com")
                .startsWith("http://")) return true;

        log.warn("Ignoring cleartext profile API declaration: " + url);
        return false;
    }

    private static boolean isPrivateHost(String host) {
        if ("localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".local")) return true;
        if (host.startsWith("127.") || "::1".equals(host) || "[::1]".equals(host)) return true;
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true;
        if (!host.startsWith("172.")) return false;
        try { // 172.16.0.0/12
            int second = Integer.parseInt(host.split("\\.")[1]);
            return second >= 16 && second <= 31;
        } catch (Exception e) {
            return false;
        }
    }

    /** Mirrors what Loki does for the primary API server, so a bare site root also works. */
    private static String followApiLocation(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.connect();
            String location = conn.getHeaderField("X-Authlib-Injector-Api-Location");
            if (location == null) return url;
            String canonical = canonicalize(url.startsWith("http://")
                    ? location.replaceFirst("^https://", "http://") : location);
            return canonical != null ? canonical : url;
        } catch (Exception e) {
            return url;
        }
    }

    private static void readSkinDomains(Discovery discovery, String apiRoot) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(apiRoot).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            if (conn.getResponseCode() != 200) return;

            Json.JSONArray domains = new Json.JSONObject(HttpUtil.readStream(conn.getInputStream()))
                    .optJSONArray("skinDomains");
            if (domains == null) return;
            List<String> parsed = new ArrayList<String>();
            for (int i = 0; i < domains.length(); i++) parsed.add(domains.getString(i));
            discovery.skinDomains = parsed;
        } catch (Exception e) {
            log.debug("Could not read skinDomains from " + apiRoot + " (" + e + ")");
        }
    }

    // ---------------------------------------------------------------- active override

    private static void publishIfCurrent(String peer, Discovery discovery) {
        if (!peer.equals(System.getProperty(PROP_PEER))) return; // we have since moved on
        if (!discovery.found()) return;

        System.setProperty(PROP_SESSION, discovery.session);
        System.setProperty(PROP_ACCOUNT, discovery.account);
        System.setProperty(PROP_SERVICES, discovery.services);
        System.setProperty(PROP_ORIGIN, peer);
        registerTextureDomains(discovery.skinDomains);
        log.info("Profile queries will be answered by " + discovery.session + " while on " + peer);
    }

    private static void clearActiveOverride() {
        System.clearProperty(PROP_SESSION);
        System.clearProperty(PROP_ACCOUNT);
        System.clearProperty(PROP_SERVICES);
        System.clearProperty(PROP_ORIGIN);
    }

    /**
     * Widens the texture allowlist to cover the redirected server's CDN.
     * <p>
     * Only when the primary API server declared domains of its own: if it did not, Loki is in its
     * allow-any mode and narrowing that here would break skins that work today.
     */
    private static void registerTextureDomains(List<String> domains) {
        if (domains.isEmpty()) return;
        String current = System.getProperty(PROP_TEXTURE_DOMAINS, "");
        if (current.length() == 0) return;

        StringBuilder merged = new StringBuilder(current);
        for (int i = 0; i < domains.size(); i++) {
            String domain = domains.get(i);
            if (("," + current + ",").indexOf("," + domain + ",") != -1) continue;
            merged.append(",").append(domain);
        }
        System.setProperty(PROP_TEXTURE_DOMAINS, merged.toString());
    }

    /**
     * Blocks until the in-flight discovery for the current server finishes.
     * <p>
     * Only safe to call from a background thread. Loki's profile lookups already run on their own
     * pool, and without this the first skin request after joining would race the ping and silently
     * fall back to the configured API server.
     */
    public static void awaitDiscovery(long timeoutMs) {
        if (disabled()) return;
        String peer = System.getProperty(PROP_PEER);
        if (peer == null) return;
        Discovery discovery = discoveries.get(peer);
        if (discovery == null) return;
        try {
            discovery.done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- endpoint scoping

    /**
     * The override base URL for a profile <em>read</em>, or null to leave the request alone.
     * <p>
     * This is an allowlist and must stay one. Adding a host or a path here widens what a third
     * party server can point your client at, so anything that authenticates, mints a session or
     * carries a token belongs nowhere near it.
     * <p>
     * Host and path are the whole test, on purpose. Loki decides this from inside
     * {@code openConnection}, before the caller has picked a request method or set a single header,
     * so a method check here would reject the batch lookups without protecting anything. Each of
     * these paths serves exactly one endpoint, which is what makes the path sufficient.
     *
     * @param mojangHost the Mojang host the game asked for, before Loki's own rewriting
     * @param path       the request path
     */
    public static String baseFor(String mojangHost, String path) {
        if (disabled() || mojangHost == null || path == null) return null;

        if ("sessionserver.mojang.com".equals(mojangHost)) {
            // /session/minecraft/profile/<uuid> - the textures lookup, unauthenticated
            if (path.startsWith("/session/minecraft/profile/")) return sessionBase();
            return null;
        }
        if ("api.mojang.com".equals(mojangHost)) {
            // Name to UUID, single and batch. Neither takes credentials.
            if (path.startsWith("/users/profiles/minecraft/")) return accountBase();
            if ("/profiles/minecraft".equals(path)) return accountBase();
            return null;
        }
        if ("api.minecraftservices.com".equals(mojangHost)) {
            // The /lookup/ prefix matters: bare /minecraft/profile is the authenticated own-profile
            // endpoint and must never be redirected.
            if (path.startsWith("/minecraft/profile/lookup/")) return servicesBase();
            return null;
        }
        return null;
    }

    /**
     * Defence in depth for {@link #baseFor}. A profile read is unauthenticated by definition, so a
     * request that carries credentials is either not a profile read or the allowlist has drifted.
     * Either way it goes to the configured API server, not to a server-declared one.
     * <p>
     * Only the query string is reliably visible this early; the header argument covers the case of
     * a connection that was pre-seeded through {@code URLConnection.setDefaultRequestProperty}.
     */
    public static boolean carriesCredentials(String authorizationHeader, String query) {
        if (authorizationHeader != null && authorizationHeader.length() != 0) return true;
        return query != null && query.toLowerCase(Locale.ENGLISH).indexOf("accesstoken") != -1;
    }

    public static String sessionBase() {
        return disabled() ? null : System.getProperty(PROP_SESSION);
    }

    public static String accountBase() {
        return disabled() ? null : System.getProperty(PROP_ACCOUNT);
    }

    public static String servicesBase() {
        return disabled() ? null : System.getProperty(PROP_SERVICES);
    }
}
