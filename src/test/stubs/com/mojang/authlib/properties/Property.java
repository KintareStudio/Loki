package com.mojang.authlib.properties;

import java.security.PublicKey;

/**
 * Stand-in for authlib's Property, so SignatureValidTransformer can be exercised without authlib.
 * <p>
 * The field names and the descriptor of {@code isSignatureValid} are the whole point: the patch
 * emits {@code GETFIELD} against them, and they were measured to be identical in every authlib from
 * 1.5.6 to 3.3.39. The body below is never run, since the transformer replaces it outright.
 * <p>
 * Like the Bootstrap stand-in, this is kept off the test classpath so the tests load the patched
 * bytes rather than these.
 */
public class Property {
    private final String value;
    private final String signature;

    public Property(String value, String signature) {
        this.value = value;
        this.signature = signature;
    }

    public String getValue() {
        return value;
    }

    public String getSignature() {
        return signature;
    }

    public boolean hasSignature() {
        return signature != null;
    }

    public boolean isSignatureValid(PublicKey publicKey) {
        throw new UnsupportedOperationException("replaced by Loki");
    }
}
