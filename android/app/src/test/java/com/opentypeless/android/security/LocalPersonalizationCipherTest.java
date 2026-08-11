package com.opentypeless.android.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import javax.crypto.spec.SecretKeySpec;

public final class LocalPersonalizationCipherTest {
    private static LocalPersonalizationCipher cipher() {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) key[index] = (byte) (index * 7 + 3);
        return new LocalPersonalizationCipher(new SecretKeySpec(key, "AES"));
    }

    @Test
    public void encryptsRandomlyAndRoundTripsUnicode() {
        LocalPersonalizationCipher cipher = cipher();
        String plaintext = "雪昭 · OpenTypeless · com.example.app";
        String first = cipher.encrypt(plaintext);
        String second = cipher.encrypt(plaintext);

        assertTrue(cipher.isEncrypted(first));
        assertNotEquals(first, second);
        assertEquals(plaintext, cipher.decryptOrLegacy(first));
        assertEquals(plaintext, cipher.decryptOrLegacy(second));
    }

    @Test
    public void legacyPlaintextPassesThroughButTamperingFailsClosed() {
        LocalPersonalizationCipher cipher = cipher();
        assertEquals("legacy term", cipher.decryptOrLegacy("legacy term"));
        String protectedValue = cipher.encrypt("private term");
        char replacement = protectedValue.endsWith("A") ? 'B' : 'A';
        String tampered = protectedValue.substring(0, protectedValue.length() - 1) + replacement;
        assertThrows(IllegalStateException.class, () -> cipher.decryptOrLegacy(tampered));
    }
}
