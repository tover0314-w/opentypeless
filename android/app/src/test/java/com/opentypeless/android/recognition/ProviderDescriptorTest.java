package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;

public final class ProviderDescriptorTest {
    @Test
    public void builtInDescriptorsHaveStableIdsAndExplicitCapabilities() {
        assertDescriptor(
                RecognitionBackend.OPENAI_COMPATIBLE,
                "builtin.openai-compatible");
        assertDescriptor(RecognitionBackend.LOCAL_OFFLINE, "builtin.local-offline");
        assertDescriptor(
                RecognitionBackend.DASHSCOPE_STREAMING,
                "builtin.dashscope-streaming");
        assertDescriptor(
                RecognitionBackend.SYSTEM_ON_DEVICE,
                "builtin.system-on-device");
        assertDescriptor(RecognitionBackend.SYSTEM_DEFAULT, "builtin.system-default");
        assertThrows(
                NullPointerException.class,
                () -> ProviderDescriptor.declaredForBackend(null));

        Method[] factories = Arrays.stream(ProviderDescriptor.class.getDeclaredMethods())
                .filter(method -> method.getReturnType() == ProviderDescriptor.class)
                .toArray(Method[]::new);
        assertEquals(1, factories.length);
        assertEquals("declaredForBackend", factories[0].getName());
        assertEquals(
                Set.of(RecognitionBackend.class),
                Set.of(factories[0].getParameterTypes()));
    }

    @Test
    public void descriptorShapeIsMinimalImmutableAndContentFree() {
        RecordComponent[] components = ProviderDescriptor.class.getRecordComponents();
        assertEquals(3, components.length);
        assertEquals("id", components[0].getName());
        assertEquals(String.class, components[0].getType());
        assertEquals("displayName", components[1].getName());
        assertEquals(String.class, components[1].getType());
        assertEquals("capabilities", components[2].getName());
        assertEquals(ProviderCapabilities.class, components[2].getType());
        assertFalse(Serializable.class.isAssignableFrom(ProviderDescriptor.class));
        assertTrue(Arrays.stream(ProviderDescriptor.class.getDeclaredFields())
                .noneMatch(field -> field.getType().getName().startsWith("android.")));
    }

    @Test
    public void strictTextBoundsRejectMalformedOrAmbiguousIdentity() {
        ProviderCapabilities capabilities =
                ProviderCapabilities.declaredForBackend(RecognitionBackend.LOCAL_OFFLINE);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderDescriptor("", "Local", capabilities));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderDescriptor("Upper", "Local", capabilities));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderDescriptor("1provider", "Local", capabilities));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderDescriptor("provider/id", "Local", capabilities));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderDescriptor("a".repeat(129), "Local", capabilities));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderDescriptor("local", " padded ", capabilities));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderDescriptor("local", "line\nbreak", capabilities));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderDescriptor("local", "😀".repeat(81), capabilities));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderDescriptor("local", "\ud800", capabilities));
        assertThrows(
                NullPointerException.class,
                () -> new ProviderDescriptor("local", "Local", null));

        ProviderDescriptor maximum = new ProviderDescriptor(
                "a" + "0".repeat(127),
                "😀".repeat(80),
                capabilities);
        assertEquals(128, maximum.id().length());
        assertEquals(80, maximum.displayName().codePointCount(
                0, maximum.displayName().length()));
    }

    @Test
    public void toStringRedactsStableAndHumanIdentity() {
        ProviderDescriptor descriptor = new ProviderDescriptor(
                "private.provider-id",
                "Private Provider Name",
                ProviderCapabilities.declaredForBackend(
                        RecognitionBackend.OPENAI_COMPATIBLE));
        String rendered = descriptor.toString();
        assertFalse(rendered.contains(descriptor.id()));
        assertFalse(rendered.contains(descriptor.displayName()));
        assertTrue(rendered.contains("id=<redacted>"));
        assertTrue(rendered.contains("displayName=<redacted>"));
    }

    private static void assertDescriptor(RecognitionBackend backend, String expectedId) {
        ProviderDescriptor descriptor = ProviderDescriptor.declaredForBackend(backend);
        assertEquals(expectedId, descriptor.id());
        assertEquals(backend.label(), descriptor.displayName());
        assertEquals(
                ProviderCapabilities.declaredForBackend(backend),
                descriptor.capabilities());
    }
}
