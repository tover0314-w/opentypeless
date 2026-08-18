package com.opentypeless.android.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.Serializable;
import java.lang.reflect.Modifier;
import org.junit.Test;

public final class OverrideValueTest {
    @Test
    public void sealedFamilyIsExactPureAndNonSerializable() throws Exception {
        assertTrue(OverrideValue.class.isSealed());
        assertArrayEquals(
                new Class<?>[]{
                        OverrideValue.Inherit.class,
                        OverrideValue.Disabled.class,
                        OverrideValue.Value.class},
                OverrideValue.class.getPermittedSubclasses());
        assertTrue(Modifier.isFinal(OverrideValue.Inherit.class.getModifiers()));
        assertTrue(Modifier.isFinal(OverrideValue.Disabled.class.getModifiers()));
        assertTrue(OverrideValue.Value.class.isRecord());
        assertTrue(Modifier.isFinal(OverrideValue.Value.class.getModifiers()));
        assertTrue(Modifier.isPrivate(
                OverrideValue.Inherit.class.getDeclaredConstructor().getModifiers()));
        assertTrue(Modifier.isPrivate(
                OverrideValue.Disabled.class.getDeclaredConstructor().getModifiers()));
        assertArrayEquals(
                new String[]{"value"},
                java.util.Arrays.stream(OverrideValue.Value.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toArray(String[]::new));

        for (Class<?> type : new Class<?>[]{
                OverrideValue.class,
                OverrideValue.Inherit.class,
                OverrideValue.Disabled.class,
                OverrideValue.Value.class}) {
            assertFalse(Serializable.class.isAssignableFrom(type));
            assertFalse(type.getName().startsWith("android."));
        }
    }

    @Test
    public void inheritAndDisabledAreDistinctTypedSingletons() {
        OverrideValue<String> inheritedString = OverrideValue.inherit();
        OverrideValue<Boolean> inheritedBoolean = OverrideValue.inherit();
        OverrideValue<String> disabledString = OverrideValue.disabled();
        OverrideValue<Boolean> disabledBoolean = OverrideValue.disabled();

        assertSame(inheritedString, inheritedBoolean);
        assertSame(disabledString, disabledBoolean);
        assertNotEquals(inheritedString, disabledString);
        assertEquals("OverrideValue.Inherit", inheritedString.toString());
        assertEquals("OverrideValue.Disabled", disabledString.toString());
    }

    @Test
    public void emptyStringAndFalseRemainExplicitValues() {
        OverrideValue<String> empty = OverrideValue.value("");
        OverrideValue<Boolean> falseValue = OverrideValue.value(false);

        assertEquals("", ((OverrideValue.Value<String>) empty).value());
        assertEquals(false, ((OverrideValue.Value<Boolean>) falseValue).value());
        assertNotEquals(empty, OverrideValue.<String>inherit());
        assertNotEquals(empty, OverrideValue.<String>disabled());
        assertNotEquals(falseValue, OverrideValue.<Boolean>inherit());
        assertNotEquals(falseValue, OverrideValue.<Boolean>disabled());
        assertEquals(OverrideValue.value(""), empty);
        assertEquals(OverrideValue.value(false), falseValue);
    }

    @Test
    public void explicitValueRejectsNullAndRedactsDiagnostics() {
        assertThrows(NullPointerException.class, () -> OverrideValue.value(null));
        String secret = "cfg003-private-sentinel";
        String diagnostic = OverrideValue.value(secret).toString();
        assertFalse(diagnostic.contains(secret));
        assertEquals("OverrideValue.Value{value=<redacted>}", diagnostic);
    }
}
