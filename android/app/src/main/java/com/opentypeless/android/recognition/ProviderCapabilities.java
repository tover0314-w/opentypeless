package com.opentypeless.android.recognition;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.settings.RecognitionBackend;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable provider behavior declared by an implementation rather than inferred from its name.
 *
 * <p>The model is data only. It carries no provider instance, endpoint, credential, Android object,
 * callback, audio, transcript, or routing authority. A future registry must compare these declared
 * capabilities with probe results before starting a provider.
 */
public record ProviderCapabilities(
        boolean supportsStreaming,
        boolean supportsPartialRevision,
        boolean supportsEndpointing,
        boolean supportsOnDevice,
        boolean supportsPrompt,
        boolean supportsBiasingTerms,
        boolean supportsDynamicKeyterms,
        boolean supportsLanguageDetection,
        boolean supportsTimestamps,
        boolean supportsAudioUpload,
        ImplementationKind implementationKind,
        RecognitionRoute.PrivacyClass privacyClass,
        Long maxAudioDurationMs,
        Set<AudioFormat> supportedAudioFormats) {
    public static final long MAX_DECLARED_AUDIO_DURATION_MS = 86_400_000L;
    public static final long APP_CAPTURE_LIMIT_MS = 540_000L;

    private static final Set<AudioFormat> PCM_16_MONO_16_KHZ =
            Collections.unmodifiableSet(EnumSet.of(AudioFormat.PCM_16_MONO_16000_HZ));

    public ProviderCapabilities {
        implementationKind = Objects.requireNonNull(implementationKind, "implementationKind");
        privacyClass = Objects.requireNonNull(privacyClass, "privacyClass");
        supportedAudioFormats = immutableFormats(supportedAudioFormats);
        if (maxAudioDurationMs != null
                && (maxAudioDurationMs <= 0L
                        || maxAudioDurationMs > MAX_DECLARED_AUDIO_DURATION_MS)) {
            throw new IllegalArgumentException("declared audio duration is outside its bound");
        }
        if (supportsPartialRevision
                && !supportsStreaming
                && implementationKind != ImplementationKind.PREFIX_REPLAY) {
            throw new IllegalArgumentException(
                    "non-streaming partial revision requires prefix replay");
        }
        if (supportsEndpointing && !supportsStreaming) {
            throw new IllegalArgumentException("provider endpointing requires streaming");
        }
        if (supportsDynamicKeyterms
                && (!supportsStreaming || !supportsBiasingTerms)) {
            throw new IllegalArgumentException(
                    "dynamic keyterms require streaming biasing support");
        }
        boolean declaresOnDevicePrivacy =
                privacyClass == RecognitionRoute.PrivacyClass.ON_DEVICE;
        if (supportsOnDevice != declaresOnDevicePrivacy) {
            throw new IllegalArgumentException(
                    "on-device capability and privacy class must be declared together");
        }
        if (supportsOnDevice && supportsAudioUpload) {
            throw new IllegalArgumentException(
                    "an on-device provider cannot declare audio upload");
        }
        switch (implementationKind) {
            case BATCH_FINAL -> {
                if (supportsStreaming || supportsPartialRevision || supportsEndpointing) {
                    throw new IllegalArgumentException(
                            "a batch final provider cannot declare streaming events");
                }
            }
            case NATIVE_STREAMING -> {
                if (!supportsStreaming) {
                    throw new IllegalArgumentException(
                            "a native streaming provider must declare streaming");
                }
            }
            case PREFIX_REPLAY -> {
                if (supportsStreaming
                        || !supportsPartialRevision
                        || supportsEndpointing
                        || !supportsOnDevice
                        || supportsAudioUpload) {
                    throw new IllegalArgumentException(
                            "prefix replay must be on-device, revisable, and explicitly non-streaming");
                }
            }
        }
    }

    /** Explicit, reviewed declarations for the five legacy built-in adapters. */
    public static ProviderCapabilities declaredForBackend(RecognitionBackend backend) {
        Objects.requireNonNull(backend, "backend");
        return switch (backend) {
            case OPENAI_COMPATIBLE -> new ProviderCapabilities(
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    false,
                    false,
                    true,
                    ImplementationKind.BATCH_FINAL,
                    RecognitionRoute.PrivacyClass.PUBLIC_NETWORK,
                    APP_CAPTURE_LIMIT_MS,
                    PCM_16_MONO_16_KHZ);
            case LOCAL_OFFLINE -> new ProviderCapabilities(
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    ImplementationKind.BATCH_FINAL,
                    RecognitionRoute.PrivacyClass.ON_DEVICE,
                    APP_CAPTURE_LIMIT_MS,
                    PCM_16_MONO_16_KHZ);
            case DASHSCOPE_STREAMING -> new ProviderCapabilities(
                    true,
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    ImplementationKind.NATIVE_STREAMING,
                    RecognitionRoute.PrivacyClass.PUBLIC_NETWORK,
                    APP_CAPTURE_LIMIT_MS,
                    PCM_16_MONO_16_KHZ);
            case SYSTEM_ON_DEVICE -> new ProviderCapabilities(
                    true,
                    true,
                    true,
                    true,
                    false,
                    true,
                    false,
                    false,
                    false,
                    false,
                    ImplementationKind.NATIVE_STREAMING,
                    RecognitionRoute.PrivacyClass.ON_DEVICE,
                    APP_CAPTURE_LIMIT_MS,
                    Set.of());
            case SYSTEM_DEFAULT -> new ProviderCapabilities(
                    true,
                    true,
                    true,
                    false,
                    false,
                    true,
                    false,
                    false,
                    false,
                    true,
                    ImplementationKind.NATIVE_STREAMING,
                    RecognitionRoute.PrivacyClass.PUBLIC_NETWORK,
                    APP_CAPTURE_LIMIT_MS,
                    Set.of());
        };
    }

    /** Explicit declaration for the bounded 750 ms SenseVoice prefix-replay preview. */
    static ProviderCapabilities prefixReplayPreview() {
        return new ProviderCapabilities(
                false,
                true,
                false,
                true,
                false,
                false,
                false,
                true,
                false,
                false,
                ImplementationKind.PREFIX_REPLAY,
                RecognitionRoute.PrivacyClass.ON_DEVICE,
                30_000L,
                PCM_16_MONO_16_KHZ);
    }

    /** Exact capabilities exposed by the reviewed Qwen3-ASR vLLM realtime adapter. */
    static ProviderCapabilities qwen3AsrVllm(RecognitionRoute.PrivacyClass privacyClass) {
        RecognitionRoute.PrivacyClass privacy =
                Objects.requireNonNull(privacyClass, "privacyClass");
        if (privacy == RecognitionRoute.PrivacyClass.ON_DEVICE) {
            throw new IllegalArgumentException("a self-hosted vLLM endpoint is not on-device");
        }
        return new ProviderCapabilities(
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                ImplementationKind.NATIVE_STREAMING,
                privacy,
                APP_CAPTURE_LIMIT_MS,
                PCM_16_MONO_16_KHZ);
    }

    /** Exact declaration for the STR-004-selected on-device Streaming Paraformer candidate. */
    static ProviderCapabilities localStreamingParaformer() {
        return new ProviderCapabilities(
                true,
                true,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                ImplementationKind.NATIVE_STREAMING,
                RecognitionRoute.PrivacyClass.ON_DEVICE,
                APP_CAPTURE_LIMIT_MS,
                PCM_16_MONO_16_KHZ);
    }

    /** Exact declaration for the on-device STR-006 streaming-preview/finalizer composite. */
    static ProviderCapabilities localTwoStage() {
        return new ProviderCapabilities(
                true,
                true,
                false,
                true,
                false,
                false,
                false,
                true,
                false,
                false,
                ImplementationKind.NATIVE_STREAMING,
                RecognitionRoute.PrivacyClass.ON_DEVICE,
                APP_CAPTURE_LIMIT_MS,
                PCM_16_MONO_16_KHZ);
    }

    /** Closed audio formats accepted directly by current OpenTypeless provider adapters. */
    public enum AudioFormat {
        PCM_16_MONO_16000_HZ
    }

    /** Closed implementation semantics; PREFIX_REPLAY must never be advertised as true streaming. */
    public enum ImplementationKind {
        BATCH_FINAL,
        NATIVE_STREAMING,
        PREFIX_REPLAY
    }

    @Override
    public String toString() {
        return "ProviderCapabilities{implementationKind=" + implementationKind
                + ", privacyClass=" + privacyClass
                + ", capabilityCount=" + enabledCapabilityCount()
                + ", durationBoundDeclared=" + (maxAudioDurationMs != null)
                + ", audioFormatCount=" + supportedAudioFormats.size() + "}";
    }

    private int enabledCapabilityCount() {
        int count = 0;
        if (supportsStreaming) count++;
        if (supportsPartialRevision) count++;
        if (supportsEndpointing) count++;
        if (supportsOnDevice) count++;
        if (supportsPrompt) count++;
        if (supportsBiasingTerms) count++;
        if (supportsDynamicKeyterms) count++;
        if (supportsLanguageDetection) count++;
        if (supportsTimestamps) count++;
        if (supportsAudioUpload) count++;
        return count;
    }

    private static Set<AudioFormat> immutableFormats(Set<AudioFormat> values) {
        Set<AudioFormat> safe = Objects.requireNonNull(values, "supportedAudioFormats");
        EnumSet<AudioFormat> copy = EnumSet.noneOf(AudioFormat.class);
        for (AudioFormat value : safe) {
            copy.add(Objects.requireNonNull(value, "audio format"));
        }
        return Collections.unmodifiableSet(copy);
    }
}
