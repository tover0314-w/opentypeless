package com.opentypeless.android.settings;

/** Unsaved state for the per-app profile editor. */
public record AppProfileDraft(
        String packageName,
        int modeIndex,
        String targetLanguage,
        String customInstructions,
        boolean sendContext) {

    public AppProfileDraft {
        packageName = safe(packageName);
        targetLanguage = safe(targetLanguage);
        customInstructions = safe(customInstructions);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
