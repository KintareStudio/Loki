package com.mojang.authlib.yggdrasil;

import com.mojang.authlib.properties.Property;

import java.security.PublicKey;
import java.security.Signature;

/**
 * Stand-in for authlib's YggdrasilServicesKeyInfo, the single key holder of 1.19 to 1.19.4.
 * <p>
 * There is no ServicesKeySet in that range: it arrives in 1.20. The two kinds of key are reached
 * through these two methods instead, which is why both are patched — {@code validateProperty} for
 * profile properties and {@code signature()} for player certificates.
 * <p>
 * The constructor and {@code keyBitCount} are here in their real shape on purpose. Loki used to
 * patch the constructor to overwrite {@code publicKey}, which fetched a key over the network and
 * threw if that failed; nothing reads the field once the two methods below are replaced, so the
 * tests construct through it in every mode, including the one with nothing reachable. Bringing that
 * patch back would fail them.
 */
public class YggdrasilServicesKeyInfo {
    private final PublicKey publicKey;

    public YggdrasilServicesKeyInfo(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    public int keyBitCount() {
        return 4096;
    }

    public Signature signature() {
        throw new UnsupportedOperationException("replaced by Loki");
    }

    public boolean validateProperty(Property property) {
        throw new UnsupportedOperationException("replaced by Loki");
    }
}
