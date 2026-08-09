package com.opentypeless.android.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

public final class LocalTextCipherTest {
    private static LocalTextCipher cipher() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        return new LocalTextCipher(new SecretKeySpec(key, "AES"));
    }

    @Test
    public void encryptsUnicodeTextWithRandomizedAuthenticatedCiphertext() {
        LocalTextCipher cipher = cipher();
        String plaintext = "OpenTypeless 中文 😀\nsecond line";

        String first = cipher.encrypt(plaintext);
        String second = cipher.encrypt(plaintext);

        assertTrue(cipher.isEncrypted(first));
        assertNotEquals(first, second);
        assertEquals(plaintext, cipher.decryptOrLegacy(first));
        assertEquals(plaintext, cipher.decryptOrLegacy(second));
    }

    @Test
    public void readsLegacyV2PlaintextWithoutChangingIt() {
        LocalTextCipher cipher = cipher();

        assertEquals("legacy plaintext", cipher.decryptOrLegacy("legacy plaintext"));
        assertEquals("otx1:bad:value", cipher.decryptOrLegacy("otx1:bad:value"));
        assertEquals("", cipher.decryptOrLegacy(null));
    }

    @Test
    public void rejectsTamperedOrMalformedCiphertextInsteadOfReturningPlaintext() {
        LocalTextCipher cipher = cipher();
        String encrypted = cipher.encrypt("private history");
        char replacement = encrypted.endsWith("A") ? 'B' : 'A';
        String tampered = encrypted.substring(0, encrypted.length() - 1) + replacement;

        assertThrows(IllegalStateException.class, () -> cipher.decryptOrLegacy(tampered));
        assertThrows(IllegalStateException.class, () -> cipher.decryptOrLegacy(encrypted + ":extra"));
    }
}
