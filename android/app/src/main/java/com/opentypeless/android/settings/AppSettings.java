package com.opentypeless.android.settings;

public record AppSettings(
        RecognitionBackend recognitionBackend,
        String sttBaseUrl,
        String sttApiKey,
        String sttModel,
        String language,
        ProcessingMode defaultMode,
        boolean polishEnabled,
        String llmBaseUrl,
        String llmApiKey,
        String llmModel,
        String targetLanguage,
        String customInstructions,
        boolean personalizationEnabled,
        boolean historyEnabled,
        boolean sendContext,
        int maxRecordingSeconds) {

    public boolean isReady() {
        if (recognitionBackend != RecognitionBackend.OPENAI_COMPATIBLE) return true;
        return !sttBaseUrl.trim().isEmpty() && !sttModel.trim().isEmpty();
    }

    public int boundedMaxRecordingSeconds() {
        return Math.max(5, Math.min(maxRecordingSeconds, 540));
    }
}
