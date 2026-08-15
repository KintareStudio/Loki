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

    /**
     * What a server may put under that key. Each endpoint can be named on its own, which is the
     * form that assumes nothing about how a server arranges itself; {@code profileApi} is the
     * shorthand for one laid out the authlib-injector way, and is expanded into the others.
     */
    private static final String FIELD_API_ROOT = "profileApi";
    private static final String FIELD_SESSION = "session";
    private static final String FIELD_ACCOUNT = "account";
    private static final String FIELD_SERVICES = "services";
    private static final String FIELD_SKIN_DOMAINS = "skinDomains";

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
    private static volatile String ownTextureDomains;
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
        volatile String root;
        volatile String session;
        volatile String account;
        volatile String services;
        volatile List<String> skinDomains = new ArrayList<String>();

        /** Any one of them is a declaration; a server need not name endpoints it does not move. */
        boolean found() {
            return session != null || account != null || services != null;
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

    /**
     * Called when the connection that was the arrival closes, whichever way it ended.
     * <p>
     * Only for the server the client is actually on: a stale close arriving after the player has
     * moved elsewhere would otherwise undo the arrival they are in the middle of. Joining the same
     * address again re-discovers rather than being taken for the reconnect it is not, which is also
     * what makes the declared keys be read afresh instead of served out of the last session's cache.
     */
    static void noteLeave(String host, int port) {
        String peer = host + ":" + port;
        if (!peer.equals(System.getProperty(PROP_PEER))) return;

        System.clearProperty(PROP_PEER);
        clearActiveOverride();
        log.debug("Left " + peer + ", back to the configured profile API");
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
                    publishIfCurrent(peer, discovery);
                    // Read the declared keys here, where blocking is free, rather than leaving the
                    // first profile of the session to wait for them
                    ProfileKeys.warmDeclared();
                    // Last, so that whoever was waiting finds the result in place rather than
                    // merely finished. Counting down first let a lookup run against the state the
                    // discovery was about to replace.
                    discovery.done.countDown();
                }
            }
        });
    }

    /**
     * Reads what the server declared, however it chose to say it.
     * <p>
     * The endpoints can be named outright, one field each, because a Yggdrasil server is under no
     * obligation to lay its paths out the way authlib-injector does. {@code profileApi} stays as a
     * shorthand for one that does: it is expanded here, so that from this point on there is only
     * ever a set of endpoints and nothing downstream needs to know which form it arrived in.
     */
    private static void resolve(Discovery discovery, String host, int port) throws Exception {
        String statusJson = ServerListPing.statusJson(host, port, PING_TIMEOUT_MS);
        Json.JSONObject declaration = new Json.JSONObject(statusJson).optJSONObject(STATUS_KEY);
        if (declaration == null) return;

        String root = canonicalize(declaration.optString(FIELD_API_ROOT, ""));
        if (root != null) {
            root = followApiLocation(root);
            discovery.root = root;
            discovery.session = root + "/sessionserver";
            discovery.account = root + "/api";
            discovery.services = root + "/minecraftservices";
            readSkinDomains(discovery, root);
        }

        // Named endpoints win over the shorthand, since they say what the shorthand only assumes
        String session = canonicalize(declaration.optString(FIELD_SESSION, ""));
        String account = canonicalize(declaration.optString(FIELD_ACCOUNT, ""));
        String services = canonicalize(declaration.optString(FIELD_SERVICES, ""));
        if (session != null) discovery.session = session;
        if (account != null) discovery.account = account;
        if (services != null) discovery.services = services;

        Json.JSONArray domains = declaration.optJSONArray(FIELD_SKIN_DOMAINS);
        if (domains != null) {
            List<String> declared = new ArrayList<String>();
            for (int i = 0; i < domains.length(); i++) declared.add(domains.getString(i));
            discovery.skinDomains = declared;
        }

        if (!discovery.found()) return;
        noteIfCleartext(discovery.session);
        noteIfCleartext(discovery.account);
        noteIfCleartext(discovery.services);
        log.info("Server declared where profiles come from: " + declared(discovery));
    }

    /** What was actually named, since a server may move one endpoint and leave the others alone. */
    private static String declared(Discovery discovery) {
        StringBuilder said = new StringBuilder();
        append(said, FIELD_SESSION, discovery.session);
        append(said, FIELD_ACCOUNT, discovery.account);
        append(said, FIELD_SERVICES, discovery.services);
        return said.toString();
    }

    private static void append(StringBuilder said, String name, String url) {
        if (url == null) return;
        if (said.length() != 0) said.append(", ");
        said.append(name).append("=").append(url);
    }

    /** Rejects anything that is not plainly an http(s) URL, and defaults a bare host to https. */
    private static String canonicalize(String url) {
        if (url == null) return null;
        String canonical = url.trim();
        if (canonical.length() == 0) return null; // a field left out, not a field to complain about
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
     * Says so when a declaration is cleartext, and honours it anyway.
     * <p>
     * A declared API server is allowed plain HTTP for the same reason a configured one is: Loki
     * does not decide for an operator which transport their API server runs on, and refusing here
     * while accepting it there would be an inconsistency dressed up as a policy. What travels over
     * it is profile reads, never credentials, which is enforced separately and unconditionally.
     */
    private static void noteIfCleartext(String url) {
        if (url != null && url.startsWith("http://")) {
            log.warn("The declared profile API is cleartext: " + url);
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

        // Only what was declared. An endpoint left unnamed keeps going where it was already going.
        setOrClear(PROP_SESSION, discovery.session);
        setOrClear(PROP_ACCOUNT, discovery.account);
        setOrClear(PROP_SERVICES, discovery.services);
        System.setProperty(PROP_ORIGIN, peer);
        applyTextureDomains(discovery.skinDomains);
        // Its keys as well as its profiles. What it serves is signed by them, so checking against
        // the configured server's alone would reject every profile it answers with. Only recorded
        // here, never fetched: this can run on the connection's own thread.
        ProfileKeys.useDeclared(discovery.services, discovery.root);
        log.info("Profiles will be answered by " + discovery.session + " while on " + peer);
    }

    private static void setOrClear(String property, String value) {
        if (value != null) System.setProperty(property, value);
        else System.clearProperty(property);
    }

    private static void clearActiveOverride() {
        ProfileKeys.useDeclared(null, null); // back to the keys the client was configured with
        restoreTextureDomains();
        System.clearProperty(PROP_SESSION);
        System.clearProperty(PROP_ACCOUNT);
        System.clearProperty(PROP_SERVICES);
        System.clearProperty(PROP_ORIGIN);
    }

    /**
     * Makes the texture allowlist fit the server being played on, until it is left.
     * <p>
     * A server that names its texture domains has them added to the client's own. One that names
     * none is taken to be saying nothing about textures, and the allowlist is stood down for the
     * duration rather than blocking a CDN it was never going to have heard of. Set
     * {@code Loki.strict_texture_domains} to keep the client's list enforced regardless, which is
     * the client's call to make and not the server's: the list is what protects the client.
     * <p>
     * None of it applies to a client with no list of its own, which is Loki's default and already
     * allows anything.
     */
    private static void applyTextureDomains(List<String> declared) {
        String own = ownTextureDomains();
        if (own.length() == 0) return; // allowing everything already

        if (declared.isEmpty()) {
            if (Boolean.getBoolean("Loki.strict_texture_domains")) {
                log.debug("Server declared no texture domains, keeping this client's list");
                return;
            }
            log.debug("Server declared no texture domains, allowing any while on it");
            System.clearProperty(PROP_TEXTURE_DOMAINS);
            return;
        }

        StringBuilder merged = new StringBuilder(own);
        for (int i = 0; i < declared.size(); i++) {
            String domain = declared.get(i);
            if (("," + own + ",").indexOf("," + domain + ",") != -1) continue;
            merged.append(",").append(domain);
        }
        System.setProperty(PROP_TEXTURE_DOMAINS, merged.toString());
    }

    /**
     * The client's own allowlist, remembered before any server is allowed to alter it.
     * <p>
     * Kept separately because the property is what gets altered: without this, leaving a server
     * would either strand its domains in the client's list for the rest of the session or lose the
     * client's own along with them.
     */
    private static String ownTextureDomains() {
        String own = ownTextureDomains;
        if (own == null) {
            own = System.getProperty(PROP_TEXTURE_DOMAINS, "");
            ownTextureDomains = own;
        }
        return own;
    }

    private static void restoreTextureDomains() {
        String own = ownTextureDomains;
        if (own == null) return; // never touched
        if (own.length() == 0) System.clearProperty(PROP_TEXTURE_DOMAINS);
        else System.setProperty(PROP_TEXTURE_DOMAINS, own);
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
