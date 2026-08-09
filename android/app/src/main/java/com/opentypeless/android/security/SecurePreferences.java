package com.opentypeless.android.security;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts API keys with a non-exportable key held by Android Keystore. */
public final class SecurePreferences {
    private static final String STORE = "opentypeless_secrets";
    private static final String KEY_ALIAS = "opentypeless_api_key_v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final int TAG_BITS = 128;
    private static volatile SecretKey cachedKey;

    private final SharedPreferences preferences;

    public SecurePreferences(Context context) {
        preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public void put(String name, String value) {
        commitPrepared(Map.of(name, prepare(value)));
    }

    /** Encrypts a value without changing persistent state. An empty value means removal. */
    public String prepare(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                    + "."
                    + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to protect API key", error);
        }
    }

    /** Returns only the encrypted preference representation for a recovery journal. */
    public String storedValue(String name) {
        String encoded = preferences.getString(name, "");
        return encoded == null ? "" : encoded;
    }

    /** Commits already encrypted values together, throwing if durable storage rejects the write. */
    @SuppressLint("ApplySharedPref") // Durable commit is part of the cross-store transaction.
    public void commitPrepared(Map<String, String> values) {
        if (values == null) throw new IllegalArgumentException("Protected values are required");
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Protected value name is required");
            }
            String encoded = entry.getValue();
            if (encoded == null || encoded.isBlank()) editor.remove(name);
            else editor.putString(name, encoded);
        }
        if (!editor.commit()) {
            throw new IllegalStateException("Unable to store protected settings");
        }
    }

    public String get(String name) {
        String encoded = preferences.getString(name, "");
        if (encoded == null || encoded.trim().isEmpty()) return "";
        try {
            String[] parts = encoded.split("\\.", 2);
            if (parts.length != 2) return "";
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception error) {
            // A restored preference cannot be decrypted by a different device's keystore.
            preferences.edit().remove(name).apply();
            return "";
        }
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
}
