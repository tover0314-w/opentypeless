package com.opentypeless.android.recognition;

final class RecognitionSupportPlatformPolicy {
    enum Decision { SERVICE_UNAVAILABLE, LEGACY_NOT_VERIFIABLE, CHECK_SUPPORT_API }

    private RecognitionSupportPlatformPolicy() {}

    static Decision decide(int sdkInt, boolean serviceAvailable) {
        if (!serviceAvailable) return Decision.SERVICE_UNAVAILABLE;
        return sdkInt >= 33 ? Decision.CHECK_SUPPORT_API : Decision.LEGACY_NOT_VERIFIABLE;
    }
}
