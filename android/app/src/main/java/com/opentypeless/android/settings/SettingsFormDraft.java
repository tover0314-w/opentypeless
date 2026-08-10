package com.opentypeless.android.settings;

/** In-memory state for the dynamic settings form across configuration changes. */
public record SettingsFormDraft(
        int recognitionBackendIndex,
        int defaultModeIndex,
        String language,
        String maxRecordingSeconds,
        String sttBaseUrl,
        String sttApiKey,
        String sttModel,
        String streamingBaseUrl,
        String streamingApiKey,
        String streamingModel,
        String streamingVocabularyId,
        boolean standardSpeechEnabled,
        String standardSpeechCallers,
        boolean polishEnabled,
        String llmBaseUrl,
        String llmApiKey,
        String llmModel,
        String targetLanguage,
        String customInstructions,
        boolean personalizationEnabled,
        boolean historyEnabled,
        boolean sendContext) {

    public SettingsFormDraft {
        language = safe(language);
        maxRecordingSeconds = safe(maxRecordingSeconds);
        sttBaseUrl = safe(sttBaseUrl);
        sttApiKey = safe(sttApiKey);
        sttModel = safe(sttModel);
        streamingBaseUrl = safe(streamingBaseUrl);
        streamingApiKey = safe(streamingApiKey);
        streamingModel = safe(streamingModel);
        streamingVocabularyId = safe(streamingVocabularyId);
        standardSpeechCallers = safe(standardSpeechCallers);
        llmBaseUrl = safe(llmBaseUrl);
        llmApiKey = safe(llmApiKey);
        llmModel = safe(llmModel);
        targetLanguage = safe(targetLanguage);
        customInstructions = safe(customInstructions);
    }

    /** Uses persisted keys when restoring a process-death Bundle that intentionally excludes them. */
    public SettingsFormDraft withSecrets(
            String persistedSttApiKey,
            String persistedStreamingApiKey,
            String persistedLlmApiKey) {
        return new SettingsFormDraft(
                recognitionBackendIndex,
                defaultModeIndex,
                language,
                maxRecordingSeconds,
                sttBaseUrl,
                persistedSttApiKey,
                sttModel,
                streamingBaseUrl,
                persistedStreamingApiKey,
                streamingModel,
                streamingVocabularyId,
                standardSpeechEnabled,
                standardSpeechCallers,
                polishEnabled,
                llmBaseUrl,
                persistedLlmApiKey,
                llmModel,
                targetLanguage,
                customInstructions,
                personalizationEnabled,
                historyEnabled,
                sendContext);
    }

    @Override
    public String toString() {
        return "SettingsFormDraft{backend=" + recognitionBackendIndex
                + ", mode=" + defaultModeIndex
                + ", sttApiKey=<redacted>, streamingApiKey=<redacted>, llmApiKey=<redacted>}";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
