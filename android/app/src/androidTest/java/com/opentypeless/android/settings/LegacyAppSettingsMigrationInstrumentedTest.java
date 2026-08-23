package com.opentypeless.android.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.config.GlobalConfig;
import com.opentypeless.android.config.OverrideValue;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashMap;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public final class LegacyAppSettingsMigrationInstrumentedTest {
    private static final String STORE = "cfg006_android_0_2_fixture";

    @Test
    public void actualZeroTwoSharedPreferencesUpgradeIsIdempotentAndKeepsOldKeys() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
        try {
            assertTrue(preferences.edit()
                    .putString("recognition_backend", RecognitionBackend.SYSTEM_DEFAULT.name())
                    .putString("stt_base_url", "https://speech.example/v1")
                    .putString("stt_model", "speech-model")
                    .putString("language", "zh-CN")
                    .putString("default_mode", ProcessingMode.VERBATIM.name())
                    .putBoolean("polish_enabled", true)
                    .putString("llm_base_url", "https://language.example/v1")
                    .putString("llm_model", "language-model")
                    .putString("target_language", "English")
                    .putString("custom_instructions", "legacy fixture")
                    .putBoolean("personalization_enabled", false)
                    .putBoolean("history_enabled", true)
                    .putBoolean("send_context", false)
                    .putInt("max_recording_seconds", 222)
                    .putLong("settings_revision", 23L)
                    .commit());

            GlobalConfig first = LegacyAppSettingsMigration.migrate(
                    preferences,
                    RecognitionBackend.SYSTEM_ON_DEVICE);
            Map<String, ?> afterFirst = new LinkedHashMap<>(preferences.getAll());
            GlobalConfig second = LegacyAppSettingsMigration.migrate(
                    preferences,
                    RecognitionBackend.LOCAL_OFFLINE);

            assertEquals(first, second);
            assertEquals(afterFirst, preferences.getAll());
            assertEquals(
                    OverrideValue.value("legacy.system-default"),
                    first.voice().routeId());
            assertEquals(
                    OverrideValue.value(com.opentypeless.android.config.ProcessingMode.EXACT),
                    first.processing().mode());
            assertEquals(OverrideValue.value(false), first.privacy().sendContext());
            assertEquals(OverrideValue.value(true), first.privacy().historyEnabled());
            assertEquals("https://speech.example/v1",
                    preferences.getString("stt_base_url", ""));
            assertEquals("legacy fixture",
                    preferences.getString("custom_instructions", ""));
            assertTrue(preferences.getBoolean(
                    LegacyAppSettingsMigration.KEY_BACKUP_RETAINED,
                    false));
            assertFalse(preferences.getAll().containsKey("stt_api_key"));
            assertFalse(preferences.getAll().containsKey("llm_api_key"));
        } finally {
            assertTrue(preferences.edit().clear().commit());
        }
    }
}
