package com.opentypeless.android.recognition;

final class RecognitionTimeoutPolicy {
    private RecognitionTimeoutPolicy() {}

    static long milliseconds(int configuredSeconds) {
        return Math.max(5, Math.min(configuredSeconds, 540)) * 1_000L;
    }
}
