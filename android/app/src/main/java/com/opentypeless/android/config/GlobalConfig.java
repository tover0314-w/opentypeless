package com.opentypeless.android.config;

import java.util.Objects;

/** Versioned root for pure, non-secret global configuration partitions. */
public record GlobalConfig(
        int formatVersion,
        KeyboardConfig keyboard,
        VoiceConfig voice,
        ProcessingConfig processing,
        PrivacyConfig privacy,
        AutomationConfig automation) {
    public static final int FORMAT_VERSION = 1;

    public GlobalConfig {
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported global config format version");
        }
        keyboard = Objects.requireNonNull(keyboard, "keyboard");
        voice = Objects.requireNonNull(voice, "voice");
        processing = Objects.requireNonNull(processing, "processing");
        privacy = Objects.requireNonNull(privacy, "privacy");
        automation = Objects.requireNonNull(automation, "automation");
    }

    @Override
    public String toString() {
        return "GlobalConfig{formatVersion=" + formatVersion
                + ", partitions=<redacted>}";
    }

    /** Global keyboard base; a usable layout cannot be disabled through rule inheritance. */
    public record KeyboardConfig(String layoutId) {
        public KeyboardConfig {
            layoutId = RuleOverrides.requireConfigId(layoutId, "layoutId");
        }

        @Override
        public String toString() {
            return "KeyboardConfig{layoutId=<redacted>}";
        }
    }

    /** Global voice route override, resolved before a provider/default route. */
    public record VoiceConfig(OverrideValue<String> routeId) {
        public VoiceConfig {
            routeId = RuleOverrides.requireIdentifierOverride(routeId, "routeId");
        }

        @Override
        public String toString() {
            return "VoiceConfig{routeId=" + routeId + "}";
        }
    }

    /** Global processing-mode override. */
    public record ProcessingConfig(OverrideValue<ProcessingMode> mode) {
        public ProcessingConfig {
            mode = RuleOverrides.requireProcessingOverride(mode, "mode");
        }

        @Override
        public String toString() {
            return "ProcessingConfig{mode=" + mode + "}";
        }
    }

    /** Global privacy defaults; hard sensitive-field rules remain outside this value. */
    public record PrivacyConfig(
            OverrideValue<Boolean> sendContext,
            OverrideValue<Boolean> historyEnabled) {
        public PrivacyConfig {
            sendContext = RuleOverrides.requireBooleanOverride(sendContext, "sendContext");
            historyEnabled = RuleOverrides.requireBooleanOverride(
                    historyEnabled,
                    "historyEnabled");
        }

        @Override
        public String toString() {
            return "PrivacyConfig{sendContext=" + sendContext
                    + ", historyEnabled=" + historyEnabled + "}";
        }
    }

    /** Global action-set override; action definitions and execution remain in ACT tasks. */
    public record AutomationConfig(OverrideValue<String> actionSetId) {
        public AutomationConfig {
            actionSetId = RuleOverrides.requireIdentifierOverride(actionSetId, "actionSetId");
        }

        @Override
        public String toString() {
            return "AutomationConfig{actionSetId=" + actionSetId + "}";
        }
    }
}
