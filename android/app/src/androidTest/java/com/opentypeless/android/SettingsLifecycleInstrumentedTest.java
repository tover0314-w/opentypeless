package com.opentypeless.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.settings.SettingsRepository;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
public final class SettingsLifecycleInstrumentedTest {
    @Test
    public void repositoryCommitsOrdinaryValuesAndBothEncryptedKeysTogether() {
        Context context = ApplicationProvider.getApplicationContext();
        SettingsRepository repository = new SettingsRepository(context);
        AppSettings previous = repository.load();
        AppSettings expected = new AppSettings(
                RecognitionBackend.OPENAI_COMPATIBLE,
                "https://speech.example/v1",
                "instrumented-stt-secret",
                "speech-model",
                "zh-CN",
                ProcessingMode.SMART,
                true,
                "https://language.example/v1",
                "instrumented-llm-secret",
                "language-model",
                "Japanese",
                "Preserve product names",
                false,
                true,
                true,
                222);
        try {
            repository.save(expected);

            AppSettings reloaded = new SettingsRepository(context).load();
            assertEquals(expected, reloaded);
            assertTrue(new SettingsRepository(context).loadHistoryEnabled());
        } finally {
            repository.save(previous);
        }
    }

    @Test
    public void mainRotationKeepsEveryUnsavedValueIncludingSecretsAndAllowlist() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                spinner(activity, "recognitionBackend").setSelection(0);
                spinner(activity, "defaultMode").setSelection(3);
                set(activity, "language", " zh-CN ");
                set(activity, "maxRecordingSeconds", "321");
                set(activity, "sttBaseUrl", "https://draft-stt.example/v1/");
                set(activity, "sttApiKey", "stt-unsaved-secret");
                set(activity, "sttModel", "draft-speech-model");
                checked(activity, "standardSpeechEnabled", true);
                set(activity, "standardSpeechCallers", "com.example.one\ncom.example.two");
                checked(activity, "polishEnabled", true);
                set(activity, "llmBaseUrl", "https://draft-llm.example/v1/");
                set(activity, "llmApiKey", "llm-unsaved-secret");
                set(activity, "llmModel", "draft-text-model");
                set(activity, "targetLanguage", "Japanese");
                set(activity, "customInstructions", "  keep draft whitespace\nsecond line  ");
                checked(activity, "personalizationEnabled", false);
                checked(activity, "historyEnabled", true);
                checked(activity, "sendContext", true);
            });

            scenario.recreate();

            scenario.onActivity(activity -> {
                assertEquals(0, spinner(activity, "recognitionBackend").getSelectedItemPosition());
                assertEquals(3, spinner(activity, "defaultMode").getSelectedItemPosition());
                assertText(activity, "language", " zh-CN ");
                assertText(activity, "maxRecordingSeconds", "321");
                assertText(activity, "sttBaseUrl", "https://draft-stt.example/v1/");
                assertText(activity, "sttApiKey", "stt-unsaved-secret");
                assertText(activity, "sttModel", "draft-speech-model");
                assertTrue(checkbox(activity, "standardSpeechEnabled").isChecked());
                assertText(activity, "standardSpeechCallers", "com.example.one\ncom.example.two");
                assertTrue(checkbox(activity, "polishEnabled").isChecked());
                assertText(activity, "llmBaseUrl", "https://draft-llm.example/v1/");
                assertText(activity, "llmApiKey", "llm-unsaved-secret");
                assertText(activity, "llmModel", "draft-text-model");
                assertText(activity, "targetLanguage", "Japanese");
                assertText(activity, "customInstructions", "  keep draft whitespace\nsecond line  ");
                assertFalse(checkbox(activity, "personalizationEnabled").isChecked());
                assertTrue(checkbox(activity, "historyEnabled").isChecked());
                assertTrue(checkbox(activity, "sendContext").isChecked());
            });
        }
    }

    @Test
    public void appProfileRotationKeepsCompleteUnsavedDraft() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, AppProfileActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try (ActivityScenario<AppProfileActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                set(activity, "packageName", "com.example.unsaved");
                spinner(activity, "mode").setSelection(2);
                set(activity, "targetLanguage", "German");
                set(activity, "instructions", "  unsaved tone\nwith whitespace  ");
                checked(activity, "sendContext", true);
            });

            scenario.recreate();

            scenario.onActivity(activity -> {
                assertText(activity, "packageName", "com.example.unsaved");
                assertEquals(2, spinner(activity, "mode").getSelectedItemPosition());
                assertText(activity, "targetLanguage", "German");
                assertText(activity, "instructions", "  unsaved tone\nwith whitespace  ");
                assertTrue(checkbox(activity, "sendContext").isChecked());
            });
        }
    }

    private static void set(Object owner, String name, String value) {
        editText(owner, name).setText(value);
    }

    private static void checked(Object owner, String name, boolean value) {
        checkbox(owner, name).setChecked(value);
    }

    private static void assertText(Object owner, String name, String expected) {
        assertEquals(expected, editText(owner, name).getText().toString());
    }

    private static EditText editText(Object owner, String name) {
        return field(owner, name, EditText.class);
    }

    private static CheckBox checkbox(Object owner, String name) {
        return field(owner, name, CheckBox.class);
    }

    private static Spinner spinner(Object owner, String name) {
        return field(owner, name, Spinner.class);
    }

    private static <T> T field(Object owner, String name, Class<T> type) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(owner));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Missing test field: " + name, error);
        }
    }
}
