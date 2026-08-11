package com.opentypeless.android.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** Non-reversible equality key used by encrypted personalization SQLite indices. */
public final class LocalLookupKey {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "opentypeless_personalization_lookup_v1";
    private static final String PREFIX = "h1:";
    private static volatile SecretKey cachedKey;

    public String digest(String namespace, String normalizedValue) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("Lookup namespace is required");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(getOrCreateKey());
            mac.update(namespace.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update((normalizedValue == null ? "" : normalizedValue)
                    .getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (Exception error) {
            throw new IllegalStateException("Unable to protect personalization lookup", error);
        }
    }

    public boolean isDigest(String value) {
        return value != null && value.startsWith(PREFIX) && value.length() == 46;
    }

    private static synchronized SecretKey getOrCreateKey() throws Exception {
        SecretKey cached = cachedKey;
        if (cached != null) return cached;
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) {
            cachedKey = existing;
            return existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build());
        SecretKey generated = generator.generateKey();
        cachedKey = generated;
        return generated;
    }
}
