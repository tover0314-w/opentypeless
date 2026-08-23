package com.opentypeless.android.keyboard.feedback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class KeyboardFeedbackPreferencesInstrumentedTest {
    @Test
    public void savesOnlyVersionedContentFreeFeedbackSettings() {
        Context context = ApplicationProvider.getApplicationContext();
        KeyboardFeedbackPreferences preferences = new KeyboardFeedbackPreferences(context);
        KeyboardFeedbackPreferences.Config original = preferences.load();
        KeyboardFeedbackPreferences.Config expected = new KeyboardFeedbackPreferences.Config(
                KeyboardFeedbackPreferences.Config.CURRENT_VERSION,
                KeyboardFeedbackPreferences.HapticMode.DISABLED,
                KeyboardFeedbackPreferences.HapticStrength.STRONG,
                false,
                17);

        try {
            preferences.save(expected);
            KeyboardFeedbackPreferences.Config actual = preferences.load();

            assertEquals(KeyboardFeedbackPreferences.HapticMode.DISABLED, actual.hapticMode());
            assertEquals(KeyboardFeedbackPreferences.HapticStrength.STRONG, actual.hapticStrength());
            assertFalse(actual.soundEnabled());
            assertEquals(17, actual.soundVolumePercent());
        } finally {
            preferences.save(original);
        }
    }

    @Test
    public void disabledHapticsProduceNoViewFeedbackAndEnabledModeProducesFeedback() {
        Context context = ApplicationProvider.getApplicationContext();
        KeyboardFeedbackPreferences preferences = new KeyboardFeedbackPreferences(context);
        KeyboardFeedbackPreferences.Config original = preferences.load();
        RecordingView view = new RecordingView(context);
        AndroidKeyboardFeedback feedback = new AndroidKeyboardFeedback(context);
        try {
            preferences.save(new KeyboardFeedbackPreferences.Config(
                    KeyboardFeedbackPreferences.Config.CURRENT_VERSION,
                    KeyboardFeedbackPreferences.HapticMode.DISABLED,
                    KeyboardFeedbackPreferences.HapticStrength.LIGHT,
                    false,
                    0));
            feedback.onPress(view);
            feedback.onLongPress(view);
            assertEquals(0, view.calls);

            preferences.save(new KeyboardFeedbackPreferences.Config(
                    KeyboardFeedbackPreferences.Config.CURRENT_VERSION,
                    KeyboardFeedbackPreferences.HapticMode.ENABLED,
                    KeyboardFeedbackPreferences.HapticStrength.MEDIUM,
                    false,
                    0));
            feedback.onPress(view);
            feedback.onLongPress(view);
            assertTrue(view.calls >= 2);
        } finally {
            preferences.save(original);
        }
    }

    private static final class RecordingView extends View {
        int calls;

        RecordingView(Context context) {
            super(context);
        }

        @Override
        public boolean performHapticFeedback(int feedbackConstant) {
            calls++;
            return true;
        }

        @Override
        public boolean performHapticFeedback(int feedbackConstant, int flags) {
            calls++;
            return true;
        }
    }
}
