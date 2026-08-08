package com.opentypeless.android.settings;

public record AppSettings(
        String sttBaseUrl,
        String sttApiKey,
        String sttModel,
        String language,
        boolean polishEnabled,
        String llmBaseUrl,
        String llmApiKey,
        String llmModel) {

    public boolean isReady() {
        return !sttBaseUrl.trim().isEmpty() && !sttModel.trim().isEmpty();
    }
}
