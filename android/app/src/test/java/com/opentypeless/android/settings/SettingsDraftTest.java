package com.opentypeless.android.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public final class SettingsDraftTest {
    @Test
    public void settingsDraftNormalizesNullsAndNeverPrintsSecrets() {
        SettingsFormDraft draft = draft("stt-secret", "llm-secret");

        String rendered = draft.toString();

        assertEquals("", draft.language());
        assertFalse(rendered.contains("stt-secret"));
        assertFalse(rendered.contains("streaming-secret"));
        assertFalse(rendered.contains("llm-secret"));
    }

    @Test
    public void persistedDraftCanRestoreKeysWithoutChangingOtherFields() {
        SettingsFormDraft withoutSecrets = draft("", "");

        SettingsFormDraft restored = withoutSecrets.withSecrets("stt", "streaming", "llm");

        assertEquals("stt", restored.sttApiKey());
        assertEquals("streaming", restored.streamingApiKey());
        assertEquals("llm", restored.llmApiKey());
        assertEquals(withoutSecrets.standardSpeechCallers(), restored.standardSpeechCallers());
        assertEquals(withoutSecrets.customInstructions(), restored.customInstructions());
    }

    @Test
    public void persistedSettingsNeverPrintCredentials() {
        AppSettings settings = new AppSettings(
                RecognitionBackend.DASHSCOPE_STREAMING,
                "https://stt.example/v1",
                "stt-secret",
                "speech-model",
                "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
                "streaming-secret",
                "paraformer-realtime-v2",
                "vocabulary-id",
                "zh-CN",
                ProcessingMode.VERBATIM,
                true,
                "https://llm.example/v1",
                "llm-secret",
                "text-model",
                "Chinese",
                "",
                true,
                false,
                false,
                180);

        String rendered = settings.toString();

        assertFalse(rendered.contains("stt-secret"));
        assertFalse(rendered.contains("streaming-secret"));
        assertFalse(rendered.contains("llm-secret"));
    }

    @Test
    public void appProfileDraftNormalizesNullableTextWithoutLosingSelections() {
        AppProfileDraft draft = new AppProfileDraft(null, 3, null, "tone draft", true);

        assertEquals("", draft.packageName());
        assertEquals("", draft.targetLanguage());
        assertEquals(3, draft.modeIndex());
        assertEquals("tone draft", draft.customInstructions());
        assertEquals(true, draft.sendContext());
    }

    private static SettingsFormDraft draft(String sttKey, String llmKey) {
        return new SettingsFormDraft(
                2,
                3,
                null,
                "321",
                "https://stt.example/v1",
                sttKey,
                "speech-model",
                "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
                "streaming-secret",
                "paraformer-realtime-v2",
                "vocabulary-id",
                true,
                "com.example.one\ncom.example.two",
                true,
                "https://llm.example/v1",
                llmKey,
                "text-model",
                "Chinese",
                "tone draft",
                false,
                true,
                true);
    }
}
