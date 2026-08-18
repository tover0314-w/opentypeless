package com.opentypeless.android.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.Serializable;
import java.lang.reflect.Modifier;
import org.junit.Test;

public final class SecretRefTest {
    @Test
    public void acceptsEveryClosedKindAtExactOpaqueIdBounds() {
        String minimum = "sec_" + "a".repeat(16);
        String maximum = "sec_" + "z".repeat(124);

        for (SecretRef.Kind kind : SecretRef.Kind.values()) {
            assertEquals(minimum, new SecretRef(kind, minimum).opaqueId());
            assertEquals(maximum, new SecretRef(kind, maximum).opaqueId());
        }
        assertArrayEquals(
                new SecretRef.Kind[]{
                        SecretRef.Kind.ASR,
                        SecretRef.Kind.LLM,
                        SecretRef.Kind.CONNECTOR},
                SecretRef.Kind.values());
    }

    @Test
    public void rejectsNullTruncatedOversizedOrNonOpaqueIdentifiers() {
        assertThrows(NullPointerException.class, () -> new SecretRef(null, validId("a")));
        assertThrows(NullPointerException.class, () -> new SecretRef(SecretRef.Kind.ASR, null));
        assertInvalid("sec_" + "a".repeat(15));
        assertInvalid("sec_" + "a".repeat(125));
        assertInvalid("ref_" + "a".repeat(16));
        assertInvalid("sec_0123456789abcdeF");
        assertInvalid("sec_0123456789abcde.");
        assertInvalid("sec_0123456789abcde/");
        assertInvalid("sec_0123456789abcde ");
        assertInvalid("sec_0123456789abcde\n");
        assertInvalid("sec_0123456789abcde\uD83D\uDE00");
        assertInvalid("sec_0123456789abcde\uD800");
    }

    @Test
    public void isAnImmutableNonSerializableValueWithExactShape() {
        SecretRef first = new SecretRef(SecretRef.Kind.LLM, validId("same"));
        SecretRef second = new SecretRef(SecretRef.Kind.LLM, validId("same"));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertTrue(SecretRef.class.isRecord());
        assertTrue(Modifier.isFinal(SecretRef.class.getModifiers()));
        assertFalse(Serializable.class.isAssignableFrom(SecretRef.class));
        assertEquals(2, SecretRef.class.getRecordComponents().length);
        assertEquals("kind", SecretRef.class.getRecordComponents()[0].getName());
        assertEquals(SecretRef.Kind.class, SecretRef.class.getRecordComponents()[0].getType());
        assertEquals("opaqueId", SecretRef.class.getRecordComponents()[1].getName());
        assertEquals(String.class, SecretRef.class.getRecordComponents()[1].getType());
    }

    @Test
    public void stringRepresentationNeverContainsTheOpaqueIdentifier() {
        String opaqueId = validId("redact");
        String rendered = new SecretRef(SecretRef.Kind.CONNECTOR, opaqueId).toString();

        assertEquals("SecretRef{kind=CONNECTOR, opaqueId=<redacted>}", rendered);
        assertFalse(rendered.contains(opaqueId));
    }

    private static String validId(String seed) {
        return "sec_" + (seed + "0123456789abcdef").substring(0, 16);
    }

    private static void assertInvalid(String value) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SecretRef(SecretRef.Kind.ASR, value));
    }
}
