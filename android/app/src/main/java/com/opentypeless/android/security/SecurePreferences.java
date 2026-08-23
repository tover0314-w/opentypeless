package com.opentypeless.android.security;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    private final SharedPreferences preferences;
    private final String keyAlias;
    private volatile SecretKey cachedKey;

    public SecurePreferences(Context context) {
        this(context, STORE, KEY_ALIAS);
    }

    SecurePreferences(Context context, String storeName, String keyAlias) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        if (storeName == null || storeName.isBlank()) {
            throw new IllegalArgumentException("Protected store name is required");
        }
        if (keyAlias == null || keyAlias.isBlank()) {
            throw new IllegalArgumentException("Protected key alias is required");
        }
        preferences = context.getApplicationContext().getSharedPreferences(
                storeName,
                Context.MODE_PRIVATE);
        this.keyAlias = keyAlias;
    }

    public void put(String name, String value) {
        commitPrepared(Map.of(name, prepare(value)));
    }

    /** Encrypts a value without changing persistent state. An empty value means removal. */
    public String prepare(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
        try {
            return encryptUtf8(plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Encrypts a transient character buffer without materializing an immutable plaintext String. */
    String prepareStored(char[] value) {
        if (value == null || value.length == 0) return "";
        byte[] plaintext = null;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            plaintext = new byte[encoded.remaining()];
            encoded.get(plaintext);
            if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
            return encryptUtf8(plaintext);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to protect API key", error);
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private String encryptUtf8(byte[] plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(plaintext);
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
        Map<String, String> stored = new HashMap<>();
        Set<String> removals = new HashSet<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Protected value name is required");
            }
            String encoded = entry.getValue();
            if (encoded == null || encoded.isBlank()) removals.add(name);
            else stored.put(name, encoded);
        }
        commitStored(stored, removals);
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

    Map<String, ?> snapshot() {
        return Map.copyOf(preferences.getAll());
    }

    @SuppressLint("ApplySharedPref")
    void commitStored(Map<String, String> values, Set<String> removals) {
        if (values == null || removals == null) {
            throw new IllegalArgumentException("Stored value mutation is required");
        }
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = requireStoredName(entry.getKey());
            String value = entry.getValue();
            if (value == null) throw new IllegalArgumentException("Stored value is required");
            if (removals.contains(name)) {
                throw new IllegalArgumentException("Stored value mutation is contradictory");
            }
            editor.putString(name, value);
        }
        for (String name : removals) editor.remove(requireStoredName(name));
        if (!editor.commit()) throw new IllegalStateException("Unable to store protected settings");
    }

    char[] decryptStored(String encoded) throws Exception {
        if (encoded == null || encoded.isBlank()) return new char[0];
        byte[] plaintext = null;
        try {
            String[] parts = encoded.split("\\.", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid protected value");
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(TAG_BITS, iv));
            plaintext = cipher.doFinal(ciphertext);
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintext));
            char[] result = new char[decoded.remaining()];
            decoded.get(result);
            return result;
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static String requireStoredName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Protected value name is required");
        }
        return name;
    }

    private synchronized SecretKey getOrCreateKey() throws Exception {
        SecretKey cached = cachedKey;
        if (cached != null) return cached;
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(keyAlias, null);
        if (existing != null) {
            cachedKey = existing;
            return existing;
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                keyAlias,
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
