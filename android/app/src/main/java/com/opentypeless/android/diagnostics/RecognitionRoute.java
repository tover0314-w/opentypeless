package com.opentypeless.android.diagnostics;

import com.opentypeless.android.settings.RecognitionBackend;

/** Redacted description of where one recognition session actually sent its audio. */
public record RecognitionRoute(
        RecognitionBackend selectedBackend,
        RecognitionBackend actualBackend,
        FallbackReason fallbackReason) {

    public enum FallbackReason {
        NONE,
        ANDROID_MICROPHONE_BLOCKED
    }

    public enum PrivacyBoundary {
        ON_DEVICE,
        PROVIDER_DEPENDENT,
        NETWORK
    }

    public RecognitionRoute {
        if (selectedBackend == null) {
            throw new IllegalArgumentException("Selected recognition backend is required");
        }
        actualBackend = actualBackend == null ? selectedBackend : actualBackend;
        fallbackReason = fallbackReason == null ? FallbackReason.NONE : fallbackReason;
    }

    public static RecognitionRoute direct(RecognitionBackend backend) {
        return new RecognitionRoute(backend, backend, FallbackReason.NONE);
    }

    public PrivacyBoundary privacyBoundary() {
        return switch (actualBackend) {
            case LOCAL_OFFLINE, SYSTEM_ON_DEVICE -> PrivacyBoundary.ON_DEVICE;
            case SYSTEM_DEFAULT -> PrivacyBoundary.PROVIDER_DEPENDENT;
            case OPENAI_COMPATIBLE, DASHSCOPE_STREAMING -> PrivacyBoundary.NETWORK;
        };
    }

    public boolean fellBack() {
        return fallbackReason != FallbackReason.NONE || selectedBackend != actualBackend;
    }
}
