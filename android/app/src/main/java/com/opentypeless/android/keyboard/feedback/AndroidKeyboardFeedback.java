package com.opentypeless.android.keyboard.feedback;

import android.content.Context;
import android.media.AudioManager;
import android.view.HapticFeedbackConstants;
import android.view.View;
import java.util.Objects;

/** Uses Android's system key sound and bounded one-shot haptics; stores no input content. */
public final class AndroidKeyboardFeedback implements KeyboardFeedback {
    private final KeyboardFeedbackPreferences preferences;
    private final AudioManager audioManager;

    public AndroidKeyboardFeedback(Context context) {
        Context application = Objects.requireNonNull(context, "context").getApplicationContext();
        preferences = new KeyboardFeedbackPreferences(application);
        audioManager = application.getSystemService(AudioManager.class);
    }

    @Override
    public void onPress(View key) {
        if (key == null || !key.isEnabled()) return;
        KeyboardFeedbackPreferences.Config config = preferences.load();
        if (config.soundEnabled() && config.soundVolumePercent() > 0 && audioManager != null) {
            audioManager.playSoundEffect(
                    AudioManager.FX_KEY_CLICK, config.soundVolumePercent() / 100f);
        }
        performHaptic(key, config, false);
    }

    @Override
    public void onLongPress(View key) {
        if (key == null || !key.isEnabled()) return;
        performHaptic(key, preferences.load(), true);
    }

    private void performHaptic(
            View key, KeyboardFeedbackPreferences.Config config, boolean longPress) {
        switch (config.hapticMode()) {
            case DISABLED -> { return; }
            case FOLLOW_SYSTEM -> key.performHapticFeedback(longPress
                    ? HapticFeedbackConstants.LONG_PRESS
                    : HapticFeedbackConstants.KEYBOARD_TAP);
            case ENABLED -> key.performHapticFeedback(
                    hapticConstant(config.hapticStrength(), longPress),
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    private static int hapticConstant(
            KeyboardFeedbackPreferences.HapticStrength strength, boolean longPress) {
        if (longPress) return HapticFeedbackConstants.LONG_PRESS;
        return switch (strength) {
            case LIGHT -> HapticFeedbackConstants.KEYBOARD_TAP;
            case MEDIUM -> HapticFeedbackConstants.CONTEXT_CLICK;
            case STRONG -> HapticFeedbackConstants.LONG_PRESS;
        };
    }
}
