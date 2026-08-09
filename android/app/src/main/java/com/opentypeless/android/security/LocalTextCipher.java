package com.opentypeless.android.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Field-level AES-GCM protection for locally persisted dictation text. */
public final class LocalTextCipher {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "opentypeless_history_text_v1";
    // Deliberately verbose so ordinary legacy dictation cannot be mistaken for ciphertext.
    private static final String PREFIX = "opentypeless-encrypted-history:v1:";
    private static final byte[] AAD = "OpenTypelessHistory:v1".getBytes(StandardCharsets.UTF_8);
    private static final int TAG_BITS = 128;
    private static final int EXPECTED_IV_BYTES = 12;
    private static volatile SecretKey cachedKey;

    private final SecretKey suppliedKey;

    public LocalTextCipher() {
        this(null);
    }

    LocalTextCipher(SecretKey suppliedKey) {
        this.suppliedKey = suppliedKey;
    }

    public String encrypt(String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            cipher.updateAAD(AAD);
            byte[] ciphertext = cipher.doFinal(
                    (plaintext == null ? "" : plaintext).getBytes(StandardCharsets.UTF_8));
            return PREFIX
                    + encode(cipher.getIV())
                    + ":"
                    + encode(ciphertext);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to protect local history", error);
        }
    }

    /** Returns legacy plaintext unchanged; authenticated ciphertext must decrypt successfully. */
    public String decryptOrLegacy(String stored) {
        if (stored == null) return "";
        if (!isEncrypted(stored)) return stored;
        try {
            String[] parts = stored.substring(PREFIX.length()).split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid encrypted history");
            byte[] iv = decode(parts[0]);
            byte[] ciphertext = decode(parts[1]);
            if (iv.length != EXPECTED_IV_BYTES || ciphertext.length < TAG_BITS / 8) {
                throw new IllegalArgumentException("Invalid encrypted history");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read protected local history", error);
        }
    }

    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    private SecretKey key() throws Exception {
        return suppliedKey == null ? getOrCreateKey() : suppliedKey;
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

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        SecretKey generated = generator.generateKey();
        cachedKey = generated;
        return generated;
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!encode(decoded).equals(value)) {
            throw new IllegalArgumentException("Non-canonical encrypted history");
        }
        return decoded;
    }
}
