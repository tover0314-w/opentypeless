package com.opentypeless.android.settings;

/** Optional per-app voice behavior. Personal terms remain separately scoped in the dictionary. */
public record AppProfile(
        String packageName,
        ProcessingMode mode,
        String targetLanguage,
        String customInstructions,
        boolean sendContext) {

    public AppProfile {
        packageName = packageName == null ? "" : packageName.trim();
        mode = mode == null ? ProcessingMode.AUTO : mode;
        targetLanguage = targetLanguage == null ? "" : targetLanguage.trim();
        customInstructions = customInstructions == null ? "" : customInstructions.trim();
    }
}
