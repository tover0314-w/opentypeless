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
        assertFalse(rendered.contains("llm-secret"));
    }

    @Test
    public void persistedDraftCanRestoreKeysWithoutChangingOtherFields() {
        SettingsFormDraft withoutSecrets = draft("", "");

        SettingsFormDraft restored = withoutSecrets.withSecrets("stt", "llm");

        assertEquals("stt", restored.sttApiKey());
        assertEquals("llm", restored.llmApiKey());
        assertEquals(withoutSecrets.standardSpeechCallers(), restored.standardSpeechCallers());
        assertEquals(withoutSecrets.customInstructions(), restored.customInstructions());
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
