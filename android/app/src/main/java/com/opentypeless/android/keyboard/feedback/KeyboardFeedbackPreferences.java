package com.opentypeless.android.keyboard.feedback;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Objects;

/** Versioned, text-free preferences for key sound and haptic feedback. */
public final class KeyboardFeedbackPreferences {
    public enum HapticMode { FOLLOW_SYSTEM, ENABLED, DISABLED }

    public enum HapticStrength { LIGHT, MEDIUM, STRONG }

    public record Config(
            int version,
            HapticMode hapticMode,
            HapticStrength hapticStrength,
            boolean soundEnabled,
            int soundVolumePercent) {
        public static final int CURRENT_VERSION = 1;

        public Config {
            Objects.requireNonNull(hapticMode, "hapticMode");
            Objects.requireNonNull(hapticStrength, "hapticStrength");
            if (version != CURRENT_VERSION) throw new IllegalArgumentException("unsupported version");
            if (soundVolumePercent < 0 || soundVolumePercent > 100) {
                throw new IllegalArgumentException("sound volume out of range");
            }
        }

        public static Config defaults() {
            return new Config(CURRENT_VERSION, HapticMode.FOLLOW_SYSTEM,
                    HapticStrength.LIGHT, true, 35);
        }
    }

    private static final String STORE = "opentypeless_keyboard_feedback_v1";
    private static final String VERSION = "format_version";
    private static final String HAPTIC_MODE = "haptic_mode";
    private static final String HAPTIC_STRENGTH = "haptic_strength";
    private static final String SOUND_ENABLED = "sound_enabled";
    private static final String SOUND_VOLUME = "sound_volume_percent";

    private final SharedPreferences preferences;

    public KeyboardFeedbackPreferences(Context context) {
        preferences = Objects.requireNonNull(context, "context")
                .getApplicationContext()
                .getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public Config load() {
        Config defaults = Config.defaults();
        int version = preferences.getInt(VERSION, Config.CURRENT_VERSION);
        if (version != Config.CURRENT_VERSION) return defaults;
        return new Config(
                Config.CURRENT_VERSION,
                parseEnum(HapticMode.class, preferences.getString(
                        HAPTIC_MODE, defaults.hapticMode().name()), defaults.hapticMode()),
                parseEnum(HapticStrength.class, preferences.getString(
                        HAPTIC_STRENGTH, defaults.hapticStrength().name()), defaults.hapticStrength()),
                preferences.getBoolean(SOUND_ENABLED, defaults.soundEnabled()),
                clamp(preferences.getInt(SOUND_VOLUME, defaults.soundVolumePercent())));
    }

    public void save(Config config) {
        Objects.requireNonNull(config, "config");
        preferences.edit()
                .putInt(VERSION, Config.CURRENT_VERSION)
                .putString(HAPTIC_MODE, config.hapticMode().name())
                .putString(HAPTIC_STRENGTH, config.hapticStrength().name())
                .putBoolean(SOUND_ENABLED, config.soundEnabled())
                .putInt(SOUND_VOLUME, config.soundVolumePercent())
                .apply();
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String raw, T fallback) {
        if (raw == null) return fallback;
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
