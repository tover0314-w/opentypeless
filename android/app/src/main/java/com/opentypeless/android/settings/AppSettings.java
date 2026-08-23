package com.opentypeless.android.settings;

public record AppSettings(
        RecognitionBackend recognitionBackend,
        String sttBaseUrl,
        String sttApiKey,
        String sttModel,
        String streamingBaseUrl,
        String streamingApiKey,
        String streamingModel,
        String streamingVocabularyId,
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
        return switch (recognitionBackend) {
            case OPENAI_COMPATIBLE ->
                    !sttBaseUrl.trim().isEmpty() && !sttModel.trim().isEmpty();
            case DASHSCOPE_STREAMING -> !streamingBaseUrl.trim().isEmpty()
                    && !streamingApiKey.trim().isEmpty()
                    && !streamingModel.trim().isEmpty();
            case LOCAL_OFFLINE, SYSTEM_ON_DEVICE, SYSTEM_DEFAULT -> true;
        };
    }

    public int boundedMaxRecordingSeconds() {
        return Math.max(5, Math.min(maxRecordingSeconds, 540));
    }

    @Override
    public String toString() {
        return "AppSettings{backend=" + recognitionBackend
                + ", defaultMode=" + defaultMode
                + ", sttApiKey=<redacted>, streamingApiKey=<redacted>, llmApiKey=<redacted>}";
    }
}
