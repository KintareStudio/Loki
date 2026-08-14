package org.unmojang.loki.hooks;

import org.unmojang.loki.util.Base64;
import org.unmojang.loki.util.HttpUtil;
import org.unmojang.loki.util.Json;
import org.unmojang.loki.util.logger.NilLogger;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
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

    private ProfileKeys() {}

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

        List<PublicKey> keys = trusted(owner, refreshServerKeys().profileProperty);
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
     * The first published certificate key, for somewhere that can only hold one.
     * <p>
     * BungeeCord keeps Mojang's key in a static field and verifies player certificates against it,
     * and there is no honest way to make a field hold a set. A proxy therefore trusts one key at a
     * time and has to be restarted across a rotation, which is worth knowing but is still better
     * than trusting Mojang's key for profiles Mojang never signed.
     *
     * @return null when the API server has published none, in which case a caller should keep
     *         whatever key it already had
     */
    public static PublicKey firstCertificateKey(Object owner) {
        List<PublicKey> keys = trusted(owner, refreshServerKeys().playerCertificate);
        return keys.isEmpty() ? null : keys.get(0);
    }

    /**
     * The same, for a caller that knows which kind of key it wants: 26.3+ hands out a key info per
     * {@code ServicesKeyType}, and the verifier it offers has to answer for that type alone.
     *
     * @param keyType {@code PROFILE_PROPERTY} or {@code PROFILE_KEY}, as the enum names them
     */
    public static Signature signatureFor(Object owner, String keyType) {
        KeySet keys = refreshServerKeys();
        List<PublicKey> published = KEY_TYPE_PROPERTY.equals(keyType)
                ? keys.profileProperty
                : keys.playerCertificate;
        return new MultiKeySignature(trusted(owner, published));
    }

    private static boolean verify(PublicKey key, String value, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(value.getBytes("UTF-8"));
            return verifier.verify(signature);
        } catch (Exception e) {
            return false; // wrong key, wrong algorithm for this key, or a corrupt signature
        }
    }

    /**
     * One kind of published key, plus Mojang's.
     * <p>
     * Mojang's is always in the list rather than only when the server's is missing. A server that
     * proxies from a fallback hands over properties and certificates Mojang signed, and those are
     * as genuine as its own; dropping the key would reject them.
     */
    private static List<PublicKey> trusted(Object owner, List<PublicKey> published) {
        List<PublicKey> keys = new ArrayList<PublicKey>(published);
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
     * Prefers what authlib-injector metadata already declared, since Loki reads that document
     * anyway and {@code signaturePublickeys} is the one place in that API where rotation is
     * expressed. Falls back to {@code /publickeys}, which is where a Mojang-shaped API server puts
     * the same thing.
     */
    private static KeySet fetchServerKeys() {
        // authlib-injector has one field for this and no notion of key types, so what it declares
        // has to answer for both. A server that wants them apart publishes /publickeys.
        String declared = System.getProperty(PROP_SIGNATURE_KEYS, "");
        if (declared.length() != 0) {
            List<PublicKey> keys = parseAll(declared.split(","), "authlib-injector metadata");
            return new KeySet(keys, keys);
        }

        String base = System.getProperty("minecraft.api.services.host", "https://api.minecraftservices.com");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(base + "/publickeys").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            if (conn.getResponseCode() != 200) {
                log.debug("publickeys returned HTTP " + conn.getResponseCode());
                return KeySet.EMPTY;
            }

            Json.JSONObject published = new Json.JSONObject(HttpUtil.readStream(conn.getInputStream()));
            return new KeySet(
                    parseList(published, "profilePropertyKeys", base),
                    parseList(published, "playerCertificateKeys", base));
        } catch (Exception e) {
            log.debug("Could not read " + base + "/publickeys (" + e + ")");
            return KeySet.EMPTY;
        }
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
