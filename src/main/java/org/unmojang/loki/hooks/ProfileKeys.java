package org.unmojang.loki.hooks;

import org.unmojang.loki.util.Base64;
import org.unmojang.loki.util.HttpUtil;
import org.unmojang.loki.util.Json;
import org.unmojang.loki.util.logger.NilLogger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.net.URL;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Verifies profile property signatures against the keys the API server publishes.
 * <p>
 * This is what {@code Property.isSignatureValid} becomes on 1.7.6 through 1.18.2 when
 * {@code Loki.enforce_secure_profile} is set. Without the flag that method keeps returning true, as
 * it has always done, because a client whose API server does not sign at all must still work.
 *
 * <h2>Why a set of keys and not one</h2>
 * The method Loki replaces takes a single {@code PublicKey}, which is why vanilla can only ever
 * trust one. Loki is writing the body, so it is free to try several, and it has to:
 * <ul>
 *   <li>an API server rotating its signing key serves properties signed by the outgoing key for as
 *       long as they are cached anywhere, so both keys have to verify during the overlap</li>
 *   <li>a profile proxied from a fallback API server carries that server's signature, most often
 *       Mojang's, and would otherwise be rejected on a server that is working exactly as intended</li>
 * </ul>
 * A property is accepted when any published key verifies it, which is the same trust decision as
 * before, taken over a list instead of a single value.
 */
public final class ProfileKeys {
    /** Where {@link org.unmojang.loki.LokiUtil} leaves the keys it found in authlib-injector metadata. */
    public static final String PROP_SIGNATURE_KEYS = "Loki.signature_keys";

    /** The names 1.20+ gives the two key types, and the words Loki uses for them throughout. */
    public static final String KEY_TYPE_PROPERTY = "PROFILE_PROPERTY";
    public static final String KEY_TYPE_CERTIFICATE = "PROFILE_KEY";

    /** Mojang ships its own key inside authlib, so the fallback needs no network. */
    private static final String MOJANG_KEY_RESOURCE = "/yggdrasil_session_pubkey.der";

    private static final String ALGORITHM = "SHA1withRSA";
    private static final int HTTP_TIMEOUT_MS = 5000;
    private static final long REFRESH_INTERVAL_MS = 3600000L; // an hour, as rotations are not sudden
    private static final long RETRY_INTERVAL_MS = 60000L;

    private static final NilLogger log = NilLogger.get("Loki");

    private static volatile KeySet serverKeys = KeySet.EMPTY;
    private static volatile long refreshDue = 0L;
    private static volatile PublicKey mojangKey;
    private static volatile boolean mojangKeyResolved;

    /** The API server the current game server declared, and the keys read from it. */
    private static volatile String declaredServices;
    private static volatile String declaredRoot;
    private static volatile String declaredFetchedFor;
    private static volatile long declaredRetryDue;
    private static volatile KeySet declared = KeySet.EMPTY;

    private ProfileKeys() {}

    /**
     * Trusts the profile property keys of an API server a game server declared, for as long as the
     * player is on it.
     * <p>
     * Without this the redirect and {@code Loki.enforce_secure_profile} cannot both be on: profiles
     * come from the declared server, signed by its keys, and are then checked against the
     * configured server's, so every one of them fails.
     * <p>
     * Joining a server already makes its Yggdrasil the authority for that session: it is the one
     * that let you in, and everyone around you authenticated against it too. Their textures and
     * their chat certificates carry its signature, not your API server's, so trusting it is what
     * makes a third party server work at all rather than an extra concession.
     * <p>
     * Worth being plain about the cost anyway. While connected, that server's Yggdrasil can vouch
     * for a texture or for whose chat key is whose, which is the guarantee enforce_secure_profile
     * otherwise gives against the server you are playing on. The bound is time: these are added to
     * the configured keys rather than replacing them, and dropped the moment the player leaves.
     *
     * @param servicesHost where the server said its services live, which is where {@code
     *                     /publickeys} is asked of it
     * @param apiRoot      its authlib-injector root when it named one, for the fallback, or null
     */
    public static void useDeclared(String servicesHost, String apiRoot) {
        if (servicesHost == null && apiRoot == null) {
            if (declaredSource() != null) log.debug("Back to the configured signing keys");
            declaredServices = null;
            declaredRoot = null;
            forgetDeclared();
            return;
        }
        declaredServices = servicesHost;
        declaredRoot = apiRoot;
        forgetDeclared(); // whatever was held belonged to another server
    }

    private static void forgetDeclared() {
        declaredFetchedFor = null;
        declaredRetryDue = 0L;
        declared = KeySet.EMPTY;
    }

    /** What the held keys belong to, so a move to a different server is noticed. */
    private static String declaredSource() {
        if (declaredServices == null && declaredRoot == null) return null;
        return declaredServices + " " + declaredRoot;
    }

    /** Reads them now, off a background thread, so the first profile does not wait for it. */
    public static void warmDeclared() {
        declaredKeys();
    }

    private static KeySet declaredKeys() {
        String source = declaredSource();
        if (source == null) return KeySet.EMPTY;
        if (source.equals(declaredFetchedFor)) return declared;
        // A server that was unreachable a moment ago may not be now, and a session lasts hours.
        // Retried on the same interval as the configured server rather than given up on for good.
        if (System.currentTimeMillis() < declaredRetryDue) return declared;

        KeySet fetched = fetchFrom(declaredServices, declaredRoot);
        if (fetched.isEmpty()) {
            declaredRetryDue = System.currentTimeMillis() + RETRY_INTERVAL_MS;
            return declared;
        }
        declared = fetched;
        declaredFetchedFor = source;
        return fetched;
    }

    /**
     * The two kinds of key an API server publishes, kept apart.
     * <p>
     * They are separate for a reason: one says a texture belongs to a profile, the other says a
     * player's chat key belongs to that player. Verifying either against the other's keys would
     * hand whoever can sign one the ability to forge the other. Most deployments sign both with the
     * same key, Kintare included, but that is their choice to make and not one to bake in here.
     */
    private static final class KeySet {
        static final KeySet EMPTY = new KeySet(Collections.<PublicKey>emptyList(),
                Collections.<PublicKey>emptyList());

        final List<PublicKey> profileProperty;
        final List<PublicKey> playerCertificate;

        KeySet(List<PublicKey> profileProperty, List<PublicKey> playerCertificate) {
            this.profileProperty = profileProperty;
            this.playerCertificate = playerCertificate;
        }

        boolean isEmpty() {
            return profileProperty.isEmpty() && playerCertificate.isEmpty();
        }
    }

    /**
     * Whether any trusted key signed this property.
     *
     * @param owner     the {@code Property} being verified, used only to reach authlib's own
     *                  classloader, where Mojang's key is a bundled resource
     * @param value     the property value exactly as it was signed
     * @param signature the Base64 signature, or null on an unsigned property
     */
    public static boolean isSignatureValid(Object owner, String value, String signature) {
        if (value == null || signature == null || signature.length() == 0) return false;

        byte[] signatureBytes;
        try {
            signatureBytes = Base64.decode(signature);
        } catch (Exception e) {
            log.debug("Profile property carries a malformed signature");
            return false;
        }

        List<PublicKey> keys = trusted(owner, KEY_TYPE_PROPERTY);
        for (int i = 0; i < keys.size(); i++) {
            if (verify(keys.get(i), value, signatureBytes)) return true;
        }
        log.warn("Profile property signature matched none of the " + keys.size() + " trusted keys");
        return false;
    }

    /**
     * The same check, for the versions that ask through {@code ServicesKeyInfo.validateProperty}.
     * <p>
     * The property is passed as an Object and read reflectively because its accessors were renamed
     * when it became a record: {@code getValue()} up to 26.2, {@code value()} from 26.3. Two cached
     * lookups on a path that runs once per profile is a better trade than a transformer that has to
     * know which era it is patching.
     */
    public static boolean isPropertyValid(Object owner, Object property) {
        if (property == null) return false;
        String value = read(property, "value", "getValue");
        String signature = read(property, "signature", "getSignature");
        return isSignatureValid(owner, value, signature);
    }

    private static String read(Object property, String name, String legacyName) {
        try {
            return (String) property.getClass().getMethod(name).invoke(property);
        } catch (Throwable ignored) {
            // Not a record, so try the accessor it had before it became one
        }
        try {
            return (String) property.getClass().getMethod(legacyName).invoke(property);
        } catch (Throwable t) {
            log.debug("Cannot read " + name + " off a profile property (" + t + ")");
            return null;
        }
    }

    /**
     * A verifier for player certificates, which is what {@code ServicesKeyInfo.signature()} is
     * asked for.
     * <p>
     * That method hands back a single {@link Signature}, so a caller can only ever check one key
     * with it. The one returned here checks the whole certificate key set instead: it buffers what
     * it is given and, when asked to verify, tries each published key in turn. That keeps a
     * rotation from invalidating certificates issued minutes earlier, for the same reason the
     * property path trusts a list.
     */
    public static Signature certificateSignature(Object owner) {
        return signatureFor(owner, KEY_TYPE_CERTIFICATE);
    }

    /**
     * How many bits the key of this type has, for the key info that reports it.
     * <p>
     * Measured rather than assumed. Mojang's is 4096 and so is most everyone's, but a caller may use
     * this to size or sanity check a signature, and answering 4096 for a 2048 bit key would have it
     * reject signatures that are perfectly good.
     * <p>
     * An API server may publish keys of several sizes — nothing stops a 2048 bit key sitting beside
     * a 4096 bit one through a rotation — and one number cannot describe them all. The largest is
     * the answer that costs least: this is asked in order to size or bound a signature, and a bound
     * that is too small rejects the signatures made by every key above it, while one that is too
     * large only fails to reject something no verification here would have accepted anyway. Which
     * key actually signed is not knowable from here, and is not this method's question: verification
     * tries them all.
     *
     * @return the largest trusted key of this type, or Mojang's 4096 when nothing is published
     */
    public static int keyBitCount(Object owner, String keyType) {
        int largest = 0;
        List<PublicKey> published = trusted(owner, keyType);
        for (int i = 0; i < published.size(); i++) {
            if (!(published.get(i) instanceof RSAPublicKey)) continue;
            int bits = ((RSAPublicKey) published.get(i)).getModulus().bitLength();
            if (bits > largest) largest = bits;
        }
        return largest != 0 ? largest : 4096;
    }

    /**
     * Whether a player's key certificate was signed by any trusted certificate key.
     * <p>
     * This is what BungeeCord's {@code EncryptionUtil.check} does, done over a set. Its own version
     * verifies against one static field, so a proxy could only ever trust a single key: no
     * rotation, and no profile proxied from a fallback API server, which is signed by Mojang. The
     * bytes signed are rebuilt here exactly as Mojang lays them out, since a signature check over
     * nearly the right bytes is worth nothing.
     *
     * @param playerPublicKey Bungee's PlayerPublicKey, read reflectively so Loki needs none of its
     *                        types on the build path
     * @param uuid            the player's UUID for the 1.19.1+ format, or null for 1.19.0's
     */
    public static boolean isCertificateValid(Object playerPublicKey, Object uuid) {
        try {
            long expiry = ((Long) invoke(playerPublicKey, "getExpiry")).longValue();
            byte[] declaredKey = (byte[]) invoke(playerPublicKey, "getKey");
            byte[] signature = (byte[]) invoke(playerPublicKey, "getSignature");
            if (declaredKey == null || signature == null) return false;

            byte[] encoded = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(declaredKey)).getEncoded();
            byte[] signed = uuid != null
                    ? certificatePayload(uuid, expiry, encoded)
                    : legacyCertificatePayload(expiry, encoded);

            List<PublicKey> keys = trusted(playerPublicKey, KEY_TYPE_CERTIFICATE);
            for (int i = 0; i < keys.size(); i++) {
                if (verify(keys.get(i), signed, signature)) return true;
            }
            log.warn("Player key certificate matched none of the " + keys.size() + " trusted keys");
            return false;
        } catch (Throwable t) {
            log.error("Could not check a player key certificate", t);
            return false;
        }
    }

    /** 1.19.1 and later: the UUID, the expiry and the key, big endian, back to back. */
    private static byte[] certificatePayload(Object uuid, long expiry, byte[] encoded) throws Exception {
        long most = ((Long) invoke(uuid, "getMostSignificantBits")).longValue();
        long least = ((Long) invoke(uuid, "getLeastSignificantBits")).longValue();
        ByteBuffer buffer = ByteBuffer.allocate(24 + encoded.length).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(most).putLong(least).putLong(expiry).put(encoded);
        return buffer.array();
    }

    /** 1.19.0: the expiry followed by the key as PEM, as ASCII. */
    private static byte[] legacyCertificatePayload(long expiry, byte[] encoded) throws Exception {
        String pem = expiry + "-----BEGIN RSA PUBLIC KEY-----\n"
                + Base64.encodeMime(encoded) + "\n-----END RSA PUBLIC KEY-----\n";
        return pem.getBytes("US-ASCII");
    }

    private static Object invoke(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    /**
     * The same, for a caller that knows which kind of key it wants: 26.3+ hands out a key info per
     * {@code ServicesKeyType}, and the verifier it offers has to answer for that type alone.
     *
     * @param keyType {@code PROFILE_PROPERTY} or {@code PROFILE_KEY}, as the enum names them
     */
    public static Signature signatureFor(Object owner, String keyType) {
        return new MultiKeySignature(trusted(owner, keyType));
    }

    private static boolean verify(PublicKey key, String value, byte[] signature) {
        try {
            return verify(key, value.getBytes("UTF-8"), signature);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean verify(PublicKey key, byte[] signed, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(signed);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false; // wrong key, wrong algorithm for this key, or a corrupt signature
        }
    }

    /**
     * Every key trusted for this kind of signature right now.
     * <p>
     * The configured API server's, plus Mojang's, plus — for profile properties only — those of an
     * API server the game server declared.
     * <p>
     * Mojang's is always here rather than only when the server's is missing. A server that proxies
     * from a fallback hands over properties and certificates Mojang signed, and those are as genuine
     * as its own; dropping the key would reject them.
     * <p>
     * A declared server's keys count for both kinds, because on that server they are what signed
     * both. The players around you authenticated against its Yggdrasil, so their chat certificates
     * carry its signature just as their textures do; checking either against the keys your own API
     * server publishes would reject every player on a server working exactly as intended. The bound
     * on this is time, not type: it lasts while you are connected and no longer.
     */
    private static List<PublicKey> trusted(Object owner, String keyType) {
        boolean forProperties = KEY_TYPE_PROPERTY.equals(keyType);
        KeySet configured = refreshServerKeys();
        KeySet declaredSet = declaredKeys();

        List<PublicKey> keys = new ArrayList<PublicKey>(
                forProperties ? configured.profileProperty : configured.playerCertificate);
        keys.addAll(forProperties ? declaredSet.profileProperty : declaredSet.playerCertificate);

        PublicKey mojang = mojangKey(owner);
        if (mojang != null) keys.add(mojang);
        return keys;
    }

    /**
     * Re-reads the API server's keys when they are due, and keeps the previous set if that fails.
     * <p>
     * Serving a stale key beats serving none: the alternative to an unreachable API server is every
     * player rendering as Steve, and the keys it last published are far more likely to still be
     * right than to have been rotated during the outage.
     */
    private static KeySet refreshServerKeys() {
        long now = System.currentTimeMillis();
        KeySet current = serverKeys;
        if (now < refreshDue) return current;

        KeySet fetched = fetchServerKeys();
        if (fetched.isEmpty() && !current.isEmpty()) {
            refreshDue = now + RETRY_INTERVAL_MS;
            log.debug("Keeping the previous signing keys, the API server did not answer");
            return current;
        }
        refreshDue = now + (fetched.isEmpty() ? RETRY_INTERVAL_MS : REFRESH_INTERVAL_MS);
        serverKeys = fetched;
        return fetched;
    }

    /**
     * The configured API server's keys: {@code /publickeys} first, then authlib-injector metadata.
     * <p>
     * That order, and not the other way round, because the endpoint says which keys are for
     * properties and which are for certificates while the metadata has a single field and no notion
     * of the difference. Falling back to it means treating what it declares as answering for both,
     * which is the best that can be done with it and worse than being told.
     */
    private static KeySet fetchServerKeys() {
        String base = System.getProperty("minecraft.api.services.host", "https://api.minecraftservices.com");
        KeySet published = fromPublicKeys(base);
        if (!published.isEmpty()) return published;

        String declared = System.getProperty(PROP_SIGNATURE_KEYS, "");
        if (declared.length() != 0) {
            List<PublicKey> keys = parseAll(declared.split(","), "authlib-injector metadata");
            return new KeySet(keys, keys);
        }
        return KeySet.EMPTY;
    }

    /**
     * A declared server's keys, in the same order and for the same reason as the configured ones.
     * <p>
     * Asked of the services endpoint it named, which is the only place it has to have one; the
     * authlib-injector document is consulted after, and only if it named a root at all.
     */
    private static KeySet fetchFrom(String servicesHost, String apiRoot) {
        KeySet published = servicesHost == null ? KeySet.EMPTY : fromPublicKeys(servicesHost);
        if (!published.isEmpty()) {
            log.info("Also trusting " + published.profileProperty.size() + " property and "
                    + published.playerCertificate.size() + " certificate key(s) declared by the server");
            return published;
        }

        // One field in the authlib-injector document, so what it declares answers for both kinds
        List<PublicKey> both = apiRoot == null ? Collections.<PublicKey>emptyList() : fromMetadata(apiRoot);
        if (!both.isEmpty()) {
            log.info("Also trusting " + both.size() + " key(s) declared by the server");
            return new KeySet(both, both);
        }

        log.debug("The declared API server publishes no signing keys");
        return KeySet.EMPTY;
    }

    private static List<PublicKey> fromMetadata(String apiRoot) {
        try {
            Json.JSONObject root = new Json.JSONObject(readDocument(apiRoot));
            List<String> encoded = new ArrayList<String>();
            Json.JSONArray declaredKeys = root.optJSONArray("signaturePublickeys");
            if (declaredKeys != null) {
                for (int i = 0; i < declaredKeys.length(); i++) encoded.add(declaredKeys.getString(i));
            }
            if (encoded.isEmpty()) encoded.add(root.optString("signaturePublickey", ""));

            List<PublicKey> keys = new ArrayList<PublicKey>();
            for (int i = 0; i < encoded.size(); i++) {
                // Armoured PEM in this document, unlike the Base64 DER /publickeys uses
                PublicKey key = parse(encoded.get(i).replaceAll("-----[A-Z ]+-----", "")
                        .replaceAll("\\s", ""));
                if (key != null) keys.add(key);
            }
            return keys;
        } catch (Exception e) {
            log.debug("No signing keys in the declared server's metadata (" + e + ")");
            return Collections.emptyList();
        }
    }

    private static KeySet fromPublicKeys(String servicesRoot) {
        try {
            Json.JSONObject published = new Json.JSONObject(readDocument(servicesRoot + "/publickeys"));
            return new KeySet(
                    parseList(published, "profilePropertyKeys", servicesRoot),
                    parseList(published, "playerCertificateKeys", servicesRoot));
        } catch (Exception e) {
            log.debug("No signing keys at " + servicesRoot + "/publickeys (" + e + ")");
            return KeySet.EMPTY;
        }
    }

    private static String readDocument(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
        return HttpUtil.readStream(conn.getInputStream());
    }

    private static List<PublicKey> parseList(Json.JSONObject published, String field, String base) {
        Json.JSONArray keys = published.optJSONArray(field);
        if (keys == null) return Collections.emptyList();

        String[] encoded = new String[keys.length()];
        for (int i = 0; i < encoded.length; i++) {
            encoded[i] = keys.getJSONObject(i).optString("publicKey", "");
        }
        return parseAll(encoded, base + "/publickeys " + field);
    }

    private static List<PublicKey> parseAll(String[] encoded, String source) {
        List<PublicKey> parsed = new ArrayList<PublicKey>();
        for (int i = 0; i < encoded.length; i++) {
            PublicKey key = parse(encoded[i]);
            if (key != null) parsed.add(key);
        }
        if (!parsed.isEmpty()) log.info("Trusting " + parsed.size() + " signing key(s) from " + source);
        return parsed;
    }

    private static PublicKey parse(String base64Der) {
        if (base64Der == null) return null;
        String trimmed = base64Der.trim();
        if (trimmed.length() == 0) return null;
        try {
            byte[] der = Base64.decode(trimmed);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            log.warn("Ignoring an unreadable signing key");
            return null;
        }
    }

    /**
     * Mojang's key, read out of authlib itself.
     * <p>
     * Every authlib from 1.5.6 to 3.18.38 ships {@code yggdrasil_session_pubkey.der} in its jar
     * root, which covers 1.7.6 through 1.19.4 and therefore every version this hook runs on. Taking
     * it from there rather than hardcoding it means Loki cannot disagree with the game about what
     * Mojang's key is, and costs no request.
     */
    private static PublicKey mojangKey(Object owner) {
        if (mojangKeyResolved) return mojangKey;
        synchronized (ProfileKeys.class) {
            if (mojangKeyResolved) return mojangKey;
            mojangKey = readMojangKey(owner);
            mojangKeyResolved = true;
            return mojangKey;
        }
    }

    /**
     * A {@link Signature} that verifies against a list of keys rather than the one it was
     * initialised with.
     * <p>
     * Callers use a Signature the same way whichever key is behind it: update, then verify. This
     * buffers the updates and, when the answer is due, replays them against each trusted key until
     * one accepts. The key handed to {@code initVerify} is ignored, exactly as it is on the
     * property path, since the whole point is to stop trusting a single hardcoded key.
     */
    private static final class MultiKeySignature extends Signature {
        private final List<PublicKey> keys;
        private java.io.ByteArrayOutputStream buffered = new java.io.ByteArrayOutputStream();

        MultiKeySignature(List<PublicKey> keys) {
            super(ALGORITHM);
            this.keys = keys;
            try {
                initVerify((PublicKey) null);
            } catch (Exception e) {
                throw new RuntimeException("Could not put a verifier into the verify state", e);
            }
        }

        protected void engineInitVerify(PublicKey ignored) {
            buffered = new java.io.ByteArrayOutputStream();
        }

        protected void engineUpdate(byte b) {
            buffered.write(b);
        }

        protected void engineUpdate(byte[] b, int off, int len) {
            buffered.write(b, off, len);
        }

        protected boolean engineVerify(byte[] signature) {
            byte[] signed = buffered.toByteArray();
            buffered = new java.io.ByteArrayOutputStream(); // a Signature resets after verifying
            for (int i = 0; i < keys.size(); i++) {
                try {
                    Signature verifier = Signature.getInstance(ALGORITHM);
                    verifier.initVerify(keys.get(i));
                    verifier.update(signed);
                    if (verifier.verify(signature)) return true;
                } catch (Exception ignored) {
                    // Wrong key for this signature, which is the ordinary case while rotating
                }
            }
            log.warn("Signature matched none of the " + keys.size() + " trusted certificate keys");
            return false;
        }

        protected void engineInitSign(java.security.PrivateKey privateKey) {
            throw new UnsupportedOperationException("Loki only verifies");
        }

        protected byte[] engineSign() {
            throw new UnsupportedOperationException("Loki only verifies");
        }

        @Deprecated
        protected void engineSetParameter(String param, Object value) {}

        @Deprecated
        protected Object engineGetParameter(String param) {
            return null;
        }
    }

    private static PublicKey readMojangKey(Object owner) {
        if (owner == null) return null;
        InputStream in = owner.getClass().getResourceAsStream(MOJANG_KEY_RESOURCE);
        if (in == null) {
            log.debug("authlib does not bundle " + MOJANG_KEY_RESOURCE + ", no Mojang key to fall back on");
            return null;
        }
        try {
            byte[] der = HttpUtil.readAllBytes(in);
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            log.debug("Also trusting Mojang's own key, as bundled in authlib");
            return key;
        } catch (Exception e) {
            log.warn("Could not read Mojang's bundled key", e);
            return null;
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }
}
