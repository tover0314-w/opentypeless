package com.opentypeless.android.recognition;

import com.opentypeless.android.settings.RecognitionBackend;

/** Declares behavior rather than assuming every speech provider supports the same request fields. */
public record ProviderCapabilities(
        boolean guaranteedOnDevice,
        boolean partialResults,
        boolean asrPrompt,
        boolean biasingStrings,
        boolean cancellable) {

    public static ProviderCapabilities forBackend(RecognitionBackend backend) {
        return switch (backend) {
            case OPENAI_COMPATIBLE -> new ProviderCapabilities(false, false, true, false, true);
            case LOCAL_OFFLINE -> new ProviderCapabilities(true, false, false, false, false);
            case SYSTEM_ON_DEVICE -> new ProviderCapabilities(true, true, false, true, true);
            case SYSTEM_DEFAULT -> new ProviderCapabilities(false, true, false, true, true);
        };
    }
}
