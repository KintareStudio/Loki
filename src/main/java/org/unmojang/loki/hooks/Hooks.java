package org.unmojang.loki.hooks;

import org.unmojang.loki.util.Base64;
import org.unmojang.loki.util.HttpUtil;
import org.unmojang.loki.util.Json;
import org.unmojang.loki.util.UuidBatcher;
import org.unmojang.loki.util.logger.NilLogger;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.*;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings({"unused", "CallToPrintStackTrace"})
public class Hooks {
    public static boolean OFFLINE_MODE = false;
    public static final Map<String, URLStreamHandler> DEFAULT_HANDLERS = new ConcurrentHashMap<String, URLStreamHandler>();

    private static final NilLogger log = NilLogger.get("Loki");
    private static final int UUID_CACHE_MAX = 256;
    private static final Map<String, String> nameToUUIDCache = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(16, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > UUID_CACHE_MAX;
                }
            });
    private static final ConcurrentHashMap<String, TextureEntry> uuidToTexturesCache = new ConcurrentHashMap<String, TextureEntry>();
    private static final long TEXTURE_CACHE_TTL_MS = 300000L; // 5 minutes
    /** How long a profile lookup will wait for an in-flight server profile API discovery. */
    private static final long DISCOVERY_WAIT_MS = 2000L;
    private static volatile long textureRateLimitUntil = 0L;

    private static final int NEGATIVE_CACHE_MAX = 512;
    private static final Map<String, Long> negativeLookupCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(16, 0.75f) {
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > NEGATIVE_CACHE_MAX;
                }
            });
    private static final long NEGATIVE_CACHE_TTL_MS = 60000L;
    private static final ConcurrentHashMap<String, Boolean> pendingLookups = new ConcurrentHashMap<String, Boolean>();
    private static final ExecutorService TEXTURE_FETCH_POOL = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger threadId = new AtomicInteger(1);
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Loki-TextureFetch-" + threadId.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    private static final HttpUtil.ConnectionFactory ACCOUNT_API = new HttpUtil.ConnectionFactory() {
        public HttpURLConnection open(String pathSuffix) throws Exception {
            // Only ever reached from the UUID batcher's own thread, so waiting here is safe
            ProfileRedirect.awaitDiscovery(DISCOVERY_WAIT_MS);
            String base = ProfileRedirect.accountBase();
            if (base == null) base = System.getProperty("minecraft.api.account.host", "https://api.mojang.com");
            return (HttpURLConnection) new URL(base + pathSuffix).openConnection();
        }
    };

    private static final UuidBatcher uuidBatcher = new UuidBatcher("Loki-Uuid", new UuidBatcher.Resolver() {
        public Map<String, String> batchLookup(List<String> usernames) throws Exception {
            return HttpUtil.batchLookupUUIDs(ACCOUNT_API, usernames);
        }
        public String singleLookup(String username) throws Exception {
            return HttpUtil.singleLookupUUID(ACCOUNT_API, username);
        }
    });

    /**
     * Whether authlib may load a texture from this URL.
     * <p>
     * Resolved at call time rather than baked into the patched method, because the allowlist can
     * grow after the class was loaded: a server that redirects profile queries also contributes the
     * skin domains of the API server it points at.
     * <p>
     * An empty allowlist means the API server never declared {@code skinDomains}, which is Loki's
     * long-standing "allow anything" case and must stay that way.
     */
    public static boolean isAllowedTextureDomain(String url) {
        String host;
        try {
            host = new URL(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host.endsWith(".minecraft.net") || host.endsWith(".mojang.com")) return true;

        String allowlist = System.getProperty(ProfileRedirect.PROP_TEXTURE_DOMAINS, "");
        if (allowlist.length() == 0) return true;
        String[] domains = allowlist.split(",");
        for (String domain : domains) {
            if (domain.length() != 0 && host.endsWith(domain)) return true;
        }
        return false;
    }

    public static Object constantSupplier(final Object value) {
        try {
            Class<?> supplier = Class.forName("java.util.function.Supplier");
            return Proxy.newProxyInstance(Hooks.class.getClassLoader(), new Class<?>[]{supplier},
                    new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                            if ("equals".equals(name)) return proxy == args[0];
                            if ("toString".equals(name)) return "ConstantSupplier(" + value + ")";
                            return value; // Supplier.get()
                        }
                    });
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("java.util.function.Supplier unavailable", e);
        }
    }

    /**
     * The key set 26.3+ asks the discovery service for.
     * <p>
     * A set, unlike what Loki used to hand back: the game asks it for the keys of one
     * {@code ServicesKeyType} and then tries them all, so answering the same single key whatever was
     * asked meant a profile property could be vouched for by a certificate key and the other way
     * round. Each type now gets a key info that answers for that type and no other.
     * <p>
     * One entry per type is enough because that entry consults every published key of its type
     * itself. Returning none is not the same as returning a permissive one, mind: the game's check
     * is {@code noneMatch}, so an empty collection rejects everything.
     */
    public static Object buildServicesKeySet(final ClassLoader cl) {
        try {
            final Class<?> keyInfoClass = Class.forName("com.mojang.authlib.services.ServicesKeyInfo", false, cl);
            Class<?> keySetClass = Class.forName("com.mojang.authlib.services.ServicesKeySet", false, cl);

            return Proxy.newProxyInstance(cl, new Class<?>[]{keySetClass},
                    new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                            if ("equals".equals(name)) return proxy == args[0];
                            if ("toString".equals(name)) return "LokiServicesKeySet";
                            if (!"keys".equals(name)) return Collections.emptyList();

                            String type = args != null && args.length != 0 ? keyTypeName(args[0]) : "";
                            if (!ProfileKeys.enforcing()) {
                                return Collections.singletonList(keyInfo(cl, keyInfoClass, type));
                            }
                            // Fail closed on a type this version of Loki does not know about,
                            // rather than vouch for it with keys meant for something else
                            if (!ProfileKeys.KEY_TYPE_PROPERTY.equals(type) && !ProfileKeys.KEY_TYPE_CERTIFICATE.equals(type)) {
                                log.warn("Not answering for an unknown services key type: " + type);
                                return Collections.emptyList();
                            }
                            return Collections.singletonList(keyInfo(cl, keyInfoClass, type));
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Loki ServicesKeySet", e);
        }
    }


    private static String keyTypeName(Object keyType) {
        if (keyType instanceof Enum) return ((Enum<?>) keyType).name();
        return String.valueOf(keyType);
    }

    /** A key info bound to one key type, which is what keeps the two kinds from vouching for each other. */
    private static Object keyInfo(ClassLoader cl, Class<?> keyInfoClass, final String type) {
        return Proxy.newProxyInstance(cl, new Class<?>[]{keyInfoClass},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("keyBitCount".equals(name) || "signatureBitCount".equals(name)) {
                            return Integer.valueOf(ProfileKeys.keyBitCount(proxy, type));
                        }
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("equals".equals(name)) return proxy == args[0];
                        if ("toString".equals(name)) return "LokiServicesKeyInfo(" + type + ")";

                        // Both of these already answer for the not-checking case themselves, and
                        // both decide it when asked rather than when this proxy was built, since a
                        // server can turn checking on partway through a session
                        if ("validateProperty".equals(name)) {
                            // A property is a property whichever list this info came from
                            return Boolean.valueOf(ProfileKeys.isPropertyValid(proxy, args[0]));
                        }
                        if ("signature".equals(name)) {
                            return ProfileKeys.signatureFor(proxy, type);
                        }
                        return null;
                    }
                });
    }

    public static String getDiscoveryJson() {
        String session = System.getProperty("minecraft.api.session.host", "https://sessionserver.mojang.com");
        if (session.endsWith("/")) session = session.substring(0, session.length() - 1);
        String services = System.getProperty("minecraft.api.services.host", "https://api.minecraftservices.com");
        if (services.endsWith("/")) services = services.substring(0, services.length() - 1);

        Json.JSONObject authentication = newEndpoints();
        putEndpoint(authentication, "getPublicKeys", services + "/publickeys", null);

        Json.JSONObject sessionEp = newEndpoints();
        putEndpoint(sessionEp, "join", session + "/session/minecraft/join", null);
        putEndpoint(sessionEp, "verify", session + "/session/minecraft/hasJoined", null);
        putEndpoint(sessionEp, "getProfileById", session + "/session/minecraft/profile/{profileId}", null);

        Json.JSONObject player = newEndpoints();
        putEndpoint(player, "getCertificates", services + "/player/certificates", null);
        putEndpoint(player, "getBlocklist", services + "/privacy/blocklist", null);
        putEndpoint(player, "getAttributes", services + "/player/attributes", null);
        putEndpoint(player, "updateAttributes", services + "/player/attributes", null);
        putEndpoint(player, "sendReport", services + "/player/report", null);
        putEndpoint(player, "getFriends", services + "/player/friends", null);
        putEndpoint(player, "updateFriends", services + "/player/friends", null);
        putEndpoint(player, "updatePresence", services + "/player/presence", null);

        // Handled in AllowedDomainTransformer, this is a stub
        Json.JSONArray textureUris = new Json.JSONArray();
        textureUris.put("http://textures.minecraft.net/texture/{textureId}");
        textureUris.put("https://textures.minecraft.net/texture/{textureId}");
        Json.JSONObject profiles = newEndpoints();
        putEndpoint(profiles, "getByName", services + "/minecraft/profile/lookup/name/{name}", null);
        putEndpoint(profiles, "getManyByName", services + "/minecraft/profile/lookup/bulk/byname", null);
        putEndpoint(profiles, "getTexture", "http://textures.minecraft.net/texture/{textureId}", textureUris);

        Json.JSONObject telemetry = newEndpoints();
        putEndpoint(telemetry, "sendEvents", services + "/eventlog/v1/events", null);

        Json.JSONObject discovery = new Json.JSONObject();
        discovery.put("product", "minecraft");
        discovery.put("authentication", authentication);
        discovery.put("session", sessionEp);
        discovery.put("player", player);
        discovery.put("profiles", profiles);
        discovery.put("telemetry", telemetry);

        Json.JSONObject root = new Json.JSONObject();
        root.put("environment", "PROD");
        root.put("product", "minecraft");
        root.put("discovery", discovery);
        return root.toString();
    }

    private static Json.JSONObject newEndpoints() {
        Json.JSONObject holder = new Json.JSONObject();
        holder.put("endpoints", new Json.JSONObject());
        return holder;
    }

    private static void putEndpoint(Json.JSONObject holder, String key, String uri, Json.JSONArray validUris) {
        Json.JSONObject endpoint = new Json.JSONObject();
        endpoint.put("uri", uri);
        if (validUris != null) endpoint.put("validUris", validUris);
        holder.getJSONObject("endpoints").put(key, endpoint);
    }

    // thanks yushijinhun!
    // https://github.com/yushijinhun/authlib-injector/blob/aff141877cccaec8c5ffe7a542efa139cc64bcde/src/main/java/moe/yushi/authlibinjector/transform/support/ConcatenateURLTransformUnit.java
    // https://github.com/yushijinhun/authlib-injector/issues/126
    public static URL concatenateURL(URL url, String query) {
        try {
            if (url.getQuery() != null && url.getQuery().length() != 0) {
                return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "&" + query);
            } else {
                return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile() + "?" + query);
            }
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Could not concatenate given URL with GET arguments!", e);
        }
    }

    public static String[] transformMainArgs(String[] args, String serverName) {
        for (int i = 0; i < args.length; i++) {
            if (i + 1 >= args.length) break;
            if ("--userType".equals(args[i]) && "mojang".equals(args[i + 1])) {
                args[i + 1] = "msa";
                log.info("Setting accountType to msa");
            }
            if ("--versionType".equals(args[i]) && serverName.length() != 0) {
                log.info("Setting versionType to server name: " + serverName);
                args[i + 1] = serverName;
            }
        }
        return args;
    }

    public static String transformProfileJson(String json) {
        try {
            Json.JSONObject profileObj = new Json.JSONObject(json);
            Json.JSONArray properties = profileObj.getJSONArray("properties");

            Iterator<Object> iter = properties.iterator();
            while (iter.hasNext()) {
                Object elem = iter.next();
                if (elem instanceof Json.JSONObject) {
                    String name = ((Json.JSONObject) elem).getString("name");
                    if (!"textures".equals(name)) {
                        iter.remove();
                    }
                }
            }

            return profileObj.toString();
        } catch (Exception e) {
            return json;
        }
    }

    // thanks yushijinhun!
    // https://github.com/yushijinhun/authlib-injector/blob/6425a2745264593da7e35896d12c6ea23638d679/src/main/java/moe/yushi/authlibinjector/transform/support/YggdrasilKeyTransformUnit.java#L116-L166
    public static Signature createDummySignature() {
        Signature sig = new Signature("dummy") {
            @Override
            protected boolean engineVerify(byte[] sigBytes) { return true; }
            @Override
            protected void engineUpdate(byte[] b, int off, int len) {}
            @Override
            protected void engineUpdate(byte b) {}
            @Override
            protected byte[] engineSign() { throw new UnsupportedOperationException(); }
            @Override @Deprecated
            protected void engineSetParameter(String param, Object value) {}
            @Override
            protected void engineInitVerify(PublicKey publicKey) {}
            @Override
            protected void engineInitSign(PrivateKey privateKey) { throw new UnsupportedOperationException(); }
            @Override @Deprecated
            protected Object engineGetParameter(String param) { return null; }
        };
        try { sig.initVerify((PublicKey)null); } catch (InvalidKeyException e) { throw new RuntimeException(e); }
        return sig;
    }

    public static String getMpPass(Object applet) {
        if (applet == null) return null;
        String mppass = null;
        try {
            Class<?> appletClass = Class.forName("java.applet.Applet");
            if (!appletClass.isInstance(applet)) return null;
            Method getParameter = appletClass.getMethod("getParameter", String.class);

            // original mppass; returned if we are unable to fetch
            mppass = (String) getParameter.invoke(applet, new Object[] { "mppass" });

            String sessionId = (String) getParameter.invoke(applet, new Object[] { "session" });
            if (sessionId == null) sessionId = (String) getParameter.invoke(applet, new Object[] { "sessionid" });
            String ip = (String) getParameter.invoke(applet, new Object[] { "server" });
            String port = (String) getParameter.invoke(applet, new Object[] { "port" });
            if (sessionId == null || ip == null || port == null)
                return mppass; // singleplayer?

            String accessToken;
            if (!sessionId.contains(":") && !sessionId.contains("%3A")) { // maybe it can be in the raw format here too?
                accessToken = sessionId;
            } else {
                String[] parts = sessionId.split(sessionId.contains(":") ? ":" : "%3A");
                if (parts.length < 3 || parts[1].length() == 0 || parts[2].length() == 0) {
                    log.error("could not parse session ID: " + sessionId);
                    return mppass;
                }

                accessToken = parts[1];
            }

            // Skip getting the mppass if we're offline
            if (OFFLINE_MODE) return mppass;

            URL url = new URL(System.getProperty("minecraft.api.session.host", "https://sessionserver.mojang.com")
                    + "/mppass?ip=" + URLEncoder.encode(ip, "UTF-8")
                    + "&port=" + port);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            if (conn.getResponseCode() != 200) return mppass;
            mppass = HttpUtil.readStream(conn.getInputStream());
        } catch (Exception ignored) {}
        log.debug("Fetched MpPass: " + mppass);
        return mppass;
    }

    public static void injectMCOSELanServerJvmArgs(List<String> command) {
        command.addAll(Arrays.asList(LauncherHooks.getLokiJVMArgs()));
    }

    private static final class TextureEntry {
        final String[] data;
        final long expiry;
        TextureEntry(String[] data, long expiry) {
            this.data = data;
            this.expiry = expiry;
        }
    }

    private static String[] cachedTextures(String uuid) {
        TextureEntry entry = uuidToTexturesCache.get(uuid);
        if (entry == null) return null;
        if (System.currentTimeMillis() >= entry.expiry) {
            uuidToTexturesCache.remove(uuid, entry);
            return null;
        }
        return entry.data;
    }

    private static String[] fetchTexturesData(String uuid) throws Exception {
        // Only ever reached from TEXTURE_FETCH_POOL, so waiting here is safe
        ProfileRedirect.awaitDiscovery(DISCOVERY_WAIT_MS);
        String base = ProfileRedirect.sessionBase();
        if (base == null) base = System.getProperty("minecraft.api.session.host", "https://sessionserver.mojang.com");
        URL url = new URL(base + "/session/minecraft/profile/"
                + URLEncoder.encode(uuid, "UTF-8") + "?unsigned=false");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (conn.getResponseCode() == 429) throw UuidBatcher.rateLimited(conn);
        if (conn.getResponseCode() != 200) return null;
        Json.JSONArray props = new Json.JSONObject(HttpUtil.readStream(conn.getInputStream())).getJSONArray("properties");
        for (int i = 0; i < props.length(); i++) {
            Json.JSONObject prop = props.getJSONObject(i);
            if ("textures".equals(prop.optString("name", ""))) {
                return new String[]{ prop.getString("value"), prop.optString("signature", null) };
            }
        }
        return null;
    }

    private static void submitTextureFetch(final String username, final String uuid) {
        TEXTURE_FETCH_POOL.execute(new Runnable() {
            public void run() {
                try {
                    if (cachedTextures(uuid) == null) {
                        String[] texturesData = fetchTexturesData(uuid);
                        if (texturesData == null) {
                            negativeLookupCache.put(username, System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS);
                            return;
                        }
                        uuidToTexturesCache.put(uuid, new TextureEntry(texturesData, System.currentTimeMillis() + TEXTURE_CACHE_TTL_MS));
                        log.info("Successfully fetched missing textures for player " + username);
                    }
                } catch (UuidBatcher.RateLimitedException e) {
                    textureRateLimitUntil = System.currentTimeMillis() + e.retryAfterMs;
                } catch (Exception e) {
                    negativeLookupCache.put(username, System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS);
                } finally {
                    pendingLookups.remove(username);
                }
            }
        });
    }

    private static String[] resolveTextures(final String username) {
        String uuid = nameToUUIDCache.get(username);
        if (uuid != null) {
            String[] cached = cachedTextures(uuid);
            if (cached != null) return cached;
        }

        Long expiry = negativeLookupCache.get(username);
        if (expiry != null && System.currentTimeMillis() < expiry) return null;

        if (System.currentTimeMillis() < textureRateLimitUntil) return null; // back off after a 429

        if (pendingLookups.putIfAbsent(username, Boolean.TRUE) == null) {
            if (uuid != null) {
                submitTextureFetch(username, uuid);
            } else {
                uuidBatcher.resolve(username, new UuidBatcher.Callback() {
                    public void onResolved(String name, String resolvedUuid) {
                        if (resolvedUuid == null) {
                            negativeLookupCache.put(name, System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS);
                            pendingLookups.remove(name);
                            return;
                        }
                        nameToUUIDCache.put(name, resolvedUuid);
                        submitTextureFetch(name, resolvedUuid);
                    }
                });
            }
        }
        return null;
    }

    private static Object getMissingTexturesProperty(Object profile) {
        try {
            Object propertiesMap;
            try {
                propertiesMap = profile.getClass().getMethod("getProperties").invoke(profile); // ~<=1.21.1
            } catch (NoSuchMethodException e) {
                propertiesMap = profile.getClass().getMethod("properties").invoke(profile); // ~1.21.10+
            }
            Method containsKey = propertiesMap.getClass().getMethod("containsKey", Object.class);
            if (Boolean.TRUE.equals(containsKey.invoke(propertiesMap, "textures"))) return null;

            String username;
            try {
                username = (String) profile.getClass().getMethod("getName").invoke(profile); // ~<=1.21.1
            } catch (NoSuchMethodException e) {
                username = (String) profile.getClass().getMethod("name").invoke(profile); // ~1.21.10+
            }
            if (username == null || username.length() == 0) return null;

            String[] texturesData = resolveTextures(username);
            if (texturesData == null) return null;

            Class<?> propertyClass = profile.getClass().getClassLoader()
                    .loadClass("com.mojang.authlib.properties.Property");
            Constructor<?> ctor = propertyClass.getConstructor(String.class, String.class, String.class);
            return ctor.newInstance("textures", texturesData[0], texturesData[1]);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    public static Object getTextures(Object instance, Object profile, boolean requireSecure) {
        try {
            Object property = getMissingTexturesProperty(profile);
            if (property != null) {
                Object propertiesMap;
                try {
                    propertiesMap = profile.getClass().getMethod("getProperties").invoke(profile); // ~<=1.21.1
                } catch (NoSuchMethodException e) {
                    propertiesMap = profile.getClass().getMethod("properties").invoke(profile); // ~1.21.10+
                }
                Class<?> propertiesMapClass = propertiesMap.getClass();
                propertiesMapClass.getMethod("removeAll", Object.class).invoke(propertiesMap, "textures");
                propertiesMapClass.getMethod("put", Object.class, Object.class).invoke(propertiesMap, "textures", property);
            }
            Method original = instance.getClass().getDeclaredMethod("getTextures$original", profile.getClass(), boolean.class);
            original.setAccessible(true);
            return original.invoke(instance, profile, requireSecure);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    public static Object getPackedTextures(Object instance, Object profile) {
        try {
            Object property = getMissingTexturesProperty(profile);
            if (property != null) return property;
            Method original = instance.getClass().getDeclaredMethod("getPackedTextures$original", profile.getClass());
            original.setAccessible(true);
            return original.invoke(instance, profile);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    public static synchronized void registerExternalFactory(URLStreamHandlerFactory factory) {
        if (factory == null) return;
        try {
            // Protocols that Loki needs to accept from external factories
            String[] protos = new String[] {"http", "https", "modjar"};
            for (String p : protos) {
                try {
                    URLStreamHandler h = factory.createURLStreamHandler(p);
                    if (h != null) {
                        DEFAULT_HANDLERS.put(p, h);
                        log.debug("Registered external handler for " + p + " from factory " + factory.getClass().getName());
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
