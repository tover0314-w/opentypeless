package com.opentypeless.android.recognition;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

import java.util.Set;

public final class StandardRecognitionSettingsRouteTest {
    private static final StandardRecognitionSettings.Snapshot ENABLED =
            new StandardRecognitionSettings.Snapshot(true, Set.of("com.example.caller"));

    @Test
    public void enabledEntryRequiresReadyOpenAiCompatibleRoute() {
        assertTrue(StandardRecognitionSettings.isSupportedRoute(
                ENABLED,
                settings(RecognitionBackend.OPENAI_COMPATIBLE, "https://speech.example/v1", "model")));
        assertFalse(StandardRecognitionSettings.isSupportedRoute(
                ENABLED,
                settings(RecognitionBackend.SYSTEM_ON_DEVICE, "", "")));
        assertFalse(StandardRecognitionSettings.isSupportedRoute(
                ENABLED,
                settings(RecognitionBackend.SYSTEM_DEFAULT, "", "")));
        assertFalse(StandardRecognitionSettings.isSupportedRoute(
                ENABLED,
                settings(RecognitionBackend.OPENAI_COMPATIBLE, "", "model")));
        assertFalse(StandardRecognitionSettings.isSupportedRoute(
                ENABLED,
                settings(RecognitionBackend.OPENAI_COMPATIBLE, "https://speech.example/v1", "")));
    }

    @Test
    public void disabledEntryDoesNotConstrainKeyboardBackend() {
        StandardRecognitionSettings.Snapshot disabled =
                new StandardRecognitionSettings.Snapshot(false, Set.of());
        assertTrue(StandardRecognitionSettings.isSupportedRoute(
                disabled,
                settings(RecognitionBackend.SYSTEM_DEFAULT, "", "")));
    }

    private static AppSettings settings(
            RecognitionBackend backend,
            String baseUrl,
            String model) {
        return new AppSettings(
                backend,
                baseUrl,
                "",
                model,
                "",
                ProcessingMode.VERBATIM,
                false,
                "",
                "",
                "",
                "",
                "",
                false,
                false,
                false,
                180);
    }
}
