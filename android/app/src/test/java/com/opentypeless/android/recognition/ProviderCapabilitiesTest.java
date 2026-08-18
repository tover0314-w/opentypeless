package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

import java.lang.reflect.RecordComponent;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public final class ProviderCapabilitiesTest {
    @Test
    public void recordShapeAndClosedAudioFormatMatchTheContract() {
        RecordComponent[] fields = ProviderCapabilities.class.getRecordComponents();
        assertEquals(14, fields.length);
        assertComponent(fields[0], "supportsStreaming", boolean.class);
        assertComponent(fields[1], "supportsPartialRevision", boolean.class);
        assertComponent(fields[2], "supportsEndpointing", boolean.class);
        assertComponent(fields[3], "supportsOnDevice", boolean.class);
        assertComponent(fields[4], "supportsPrompt", boolean.class);
        assertComponent(fields[5], "supportsBiasingTerms", boolean.class);
        assertComponent(fields[6], "supportsDynamicKeyterms", boolean.class);
        assertComponent(fields[7], "supportsLanguageDetection", boolean.class);
        assertComponent(fields[8], "supportsTimestamps", boolean.class);
        assertComponent(fields[9], "supportsAudioUpload", boolean.class);
        assertComponent(
                fields[10],
                "implementationKind",
                ProviderCapabilities.ImplementationKind.class);
        assertComponent(fields[11], "privacyClass", RecognitionRoute.PrivacyClass.class);
        assertComponent(fields[12], "maxAudioDurationMs", Long.class);
        assertComponent(fields[13], "supportedAudioFormats", Set.class);
        assertEquals(
                Set.of(ProviderCapabilities.AudioFormat.PCM_16_MONO_16000_HZ),
                Set.of(ProviderCapabilities.AudioFormat.values()));
    }

    @Test
    public void builtInDeclarationsAreAnExactReviewedMatrix() {
        ProviderCapabilities openAi = capabilities(RecognitionBackend.OPENAI_COMPATIBLE);
        assertCapabilities(
                openAi,
                false, false, false, false, true, false, false, false, false, true,
                ProviderCapabilities.ImplementationKind.BATCH_FINAL,
                RecognitionRoute.PrivacyClass.PUBLIC_NETWORK, true);

        ProviderCapabilities local = capabilities(RecognitionBackend.LOCAL_OFFLINE);
        assertCapabilities(
                local,
                false, false, false, true, false, false, false, true, false, false,
                ProviderCapabilities.ImplementationKind.BATCH_FINAL,
                RecognitionRoute.PrivacyClass.ON_DEVICE, true);

        ProviderCapabilities dashscope = capabilities(RecognitionBackend.DASHSCOPE_STREAMING);
        assertCapabilities(
                dashscope,
                true, true, false, false, false, false, false, false, false, true,
                ProviderCapabilities.ImplementationKind.NATIVE_STREAMING,
                RecognitionRoute.PrivacyClass.PUBLIC_NETWORK, true);

        ProviderCapabilities systemOnDevice =
                capabilities(RecognitionBackend.SYSTEM_ON_DEVICE);
        assertCapabilities(
                systemOnDevice,
                true, true, true, true, false, true, false, false, false, false,
                ProviderCapabilities.ImplementationKind.NATIVE_STREAMING,
                RecognitionRoute.PrivacyClass.ON_DEVICE, false);

        ProviderCapabilities systemDefault = capabilities(RecognitionBackend.SYSTEM_DEFAULT);
        assertCapabilities(
                systemDefault,
                true, true, true, false, false, true, false, false, false, true,
                ProviderCapabilities.ImplementationKind.NATIVE_STREAMING,
                RecognitionRoute.PrivacyClass.PUBLIC_NETWORK, false);

        for (RecognitionBackend backend : RecognitionBackend.values()) {
            assertEquals(
                    Long.valueOf(ProviderCapabilities.APP_CAPTURE_LIMIT_MS),
                    capabilities(backend).maxAudioDurationMs());
        }
    }

    @Test
    public void prefixReplayIsRevisableButExplicitlyNotNativeStreaming() {
        ProviderCapabilities preview = ProviderCapabilities.prefixReplayPreview();

        assertFalse(preview.supportsStreaming());
        assertTrue(preview.supportsPartialRevision());
        assertFalse(preview.supportsEndpointing());
        assertTrue(preview.supportsOnDevice());
        assertFalse(preview.supportsAudioUpload());
        assertEquals(
                ProviderCapabilities.ImplementationKind.PREFIX_REPLAY,
                preview.implementationKind());
        assertEquals(Long.valueOf(30_000L), preview.maxAudioDurationMs());
        assertEquals(
                Set.of(ProviderCapabilities.AudioFormat.PCM_16_MONO_16000_HZ),
                preview.supportedAudioFormats());
    }

    @Test
    public void constructorRejectsContradictoryOrUnboundedClaims() {
        assertThrows(NullPointerException.class, () -> newCapabilities(
                false, false, false, false, false, false, false,
                RecognitionRoute.PrivacyClass.PUBLIC_NETWORK, null, null));
        assertThrows(IllegalArgumentException.class, () -> newCapabilities(
                false, true, false, false, false, false, false,
                RecognitionRoute.PrivacyClass.PUBLIC_NETWORK, 1L, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> newCapabilities(
                false, false, true, false, false, false, false,
                RecognitionRoute.PrivacyClass.PUBLIC_NETWORK, 1L, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> newCapabilities(
                true, false, false, false, false, false, true,
                RecognitionRoute.PrivacyClass.PUBLIC_NETWORK, 1L, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> newCapabilities(
                true, false, false, true, false, false, false,
                RecognitionRoute.PrivacyClass.PUBLIC_NETWORK, 1L, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> newCapabilities(
                true, false, false, false, false, false, false,
                RecognitionRoute.PrivacyClass.ON_DEVICE, 1L, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> newCapabilities(
                true, false, false, true, false, true, false,
                RecognitionRoute.PrivacyClass.ON_DEVICE, 1L, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> newCapabilities(
                true, false, false, false, false, false, false,
                RecognitionRoute.PrivacyClass.PUBLIC_NETWORK, 0L, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> newCapabilities(
                true, false, false, false, false, false, false,
                RecognitionRoute.PrivacyClass.PUBLIC_NETWORK,
                ProviderCapabilities.MAX_DECLARED_AUDIO_DURATION_MS + 1L,
                Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProviderCapabilities(
                true, true, false, true, false, false, false, false, false, false,
                ProviderCapabilities.ImplementationKind.PREFIX_REPLAY,
                RecognitionRoute.PrivacyClass.ON_DEVICE, 1L, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProviderCapabilities(
                false, false, false, true, false, false, false, false, false, false,
                ProviderCapabilities.ImplementationKind.PREFIX_REPLAY,
                RecognitionRoute.PrivacyClass.ON_DEVICE, 1L, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProviderCapabilities(
                false, true, false, false, false, false, false, false, false, false,
                ProviderCapabilities.ImplementationKind.PREFIX_REPLAY,
                RecognitionRoute.PrivacyClass.PUBLIC_NETWORK, 1L, Set.of()));
    }

    @Test
    public void formatSetIsDefensivelyCopiedAndRedacted() {
        Set<ProviderCapabilities.AudioFormat> formats =
                EnumSet.of(ProviderCapabilities.AudioFormat.PCM_16_MONO_16000_HZ);
        ProviderCapabilities capabilities = newCapabilities(
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                RecognitionRoute.PrivacyClass.PUBLIC_NETWORK,
                null,
                formats);
        formats.clear();
        assertEquals(1, capabilities.supportedAudioFormats().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> capabilities.supportedAudioFormats().clear());
        assertFalse(capabilities.toString().contains("PCM_16"));
        assertTrue(capabilities.toString().contains("audioFormatCount=1"));
        assertTrue(capabilities.toString().contains("NATIVE_STREAMING"));

        Set<ProviderCapabilities.AudioFormat> hostile = new HashSet<>();
        hostile.add(null);
        assertThrows(NullPointerException.class, () -> newCapabilities(
                true, false, false, false, false, false, false,
                RecognitionRoute.PrivacyClass.PUBLIC_NETWORK, null, hostile));
    }

    private static ProviderCapabilities capabilities(RecognitionBackend backend) {
        return ProviderCapabilities.declaredForBackend(backend);
    }

    private static ProviderCapabilities newCapabilities(
            boolean streaming,
            boolean partialRevision,
            boolean endpointing,
            boolean onDevice,
            boolean biasing,
            boolean audioUpload,
            boolean dynamicKeyterms,
            RecognitionRoute.PrivacyClass privacyClass,
            Long duration,
            Set<ProviderCapabilities.AudioFormat> formats) {
        return new ProviderCapabilities(
                streaming,
                partialRevision,
                endpointing,
                onDevice,
                false,
                biasing,
                dynamicKeyterms,
                false,
                false,
                audioUpload,
                streaming
                        ? ProviderCapabilities.ImplementationKind.NATIVE_STREAMING
                        : ProviderCapabilities.ImplementationKind.BATCH_FINAL,
                privacyClass,
                duration,
                formats);
    }

    private static void assertCapabilities(
            ProviderCapabilities value,
            boolean streaming,
            boolean partialRevision,
            boolean endpointing,
            boolean onDevice,
            boolean prompt,
            boolean biasing,
            boolean dynamicKeyterms,
            boolean languageDetection,
            boolean timestamps,
            boolean audioUpload,
            ProviderCapabilities.ImplementationKind implementationKind,
            RecognitionRoute.PrivacyClass privacyClass,
            boolean acceptsPcm) {
        assertEquals(streaming, value.supportsStreaming());
        assertEquals(partialRevision, value.supportsPartialRevision());
        assertEquals(endpointing, value.supportsEndpointing());
        assertEquals(onDevice, value.supportsOnDevice());
        assertEquals(prompt, value.supportsPrompt());
        assertEquals(biasing, value.supportsBiasingTerms());
        assertEquals(dynamicKeyterms, value.supportsDynamicKeyterms());
        assertEquals(languageDetection, value.supportsLanguageDetection());
        assertEquals(timestamps, value.supportsTimestamps());
        assertEquals(audioUpload, value.supportsAudioUpload());
        assertEquals(implementationKind, value.implementationKind());
        assertEquals(privacyClass, value.privacyClass());
        assertEquals(acceptsPcm, !value.supportedAudioFormats().isEmpty());
    }

    private static void assertComponent(
            RecordComponent component, String name, Class<?> type) {
        assertEquals(name, component.getName());
        assertEquals(type, component.getType());
    }
}
