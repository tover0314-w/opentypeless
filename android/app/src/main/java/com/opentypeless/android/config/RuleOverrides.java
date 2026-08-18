package com.opentypeless.android.config;

import java.util.Objects;

/** The exact five three-state leaves shared by app and field configuration rules. */
public record RuleOverrides(
        OverrideValue<String> voiceRouteId,
        OverrideValue<ProcessingMode> processingMode,
        OverrideValue<Boolean> sendContext,
        OverrideValue<Boolean> historyEnabled,
        OverrideValue<String> actionSetId) {
    public static final int MAX_CONFIG_ID_CODE_POINTS = 128;
    public static final int MAX_PACKAGE_NAME_CODE_POINTS = 255;

    public RuleOverrides {
        voiceRouteId = requireIdentifierOverride(voiceRouteId, "voiceRouteId");
        processingMode = requireProcessingOverride(processingMode, "processingMode");
        sendContext = requireBooleanOverride(sendContext, "sendContext");
        historyEnabled = requireBooleanOverride(historyEnabled, "historyEnabled");
        actionSetId = requireIdentifierOverride(actionSetId, "actionSetId");
    }

    @Override
    public String toString() {
        return "RuleOverrides{voiceRouteId=" + voiceRouteId
                + ", processingMode=" + processingMode
                + ", sendContext=" + sendContext
                + ", historyEnabled=" + historyEnabled
                + ", actionSetId=" + actionSetId + "}";
    }

    static OverrideValue<String> requireIdentifierOverride(
            OverrideValue<String> value,
            String name) {
        OverrideValue<String> safe = Objects.requireNonNull(value, name);
        if (safe instanceof OverrideValue.Value<?> explicit) {
            Object payload = explicit.value();
            if (!(payload instanceof String identifier)) {
                throw new IllegalArgumentException(name + " has an invalid value type");
            }
            requireConfigId(identifier, name);
        }
        return safe;
    }

    static OverrideValue<ProcessingMode> requireProcessingOverride(
            OverrideValue<ProcessingMode> value,
            String name) {
        OverrideValue<ProcessingMode> safe = Objects.requireNonNull(value, name);
        if (safe instanceof OverrideValue.Value<?> explicit
                && !(explicit.value() instanceof ProcessingMode)) {
            throw new IllegalArgumentException(name + " has an invalid value type");
        }
        return safe;
    }

    static OverrideValue<Boolean> requireBooleanOverride(
            OverrideValue<Boolean> value,
            String name) {
        OverrideValue<Boolean> safe = Objects.requireNonNull(value, name);
        if (safe instanceof OverrideValue.Value<?> explicit
                && !(explicit.value() instanceof Boolean)) {
            throw new IllegalArgumentException(name + " has an invalid value type");
        }
        return safe;
    }

    static String requireConfigId(String value, String name) {
        String safe = Objects.requireNonNull(value, name);
        if (safe.isEmpty() || safe.length() > MAX_CONFIG_ID_CODE_POINTS) {
            throw new IllegalArgumentException(name + " is outside its bound");
        }
        for (int index = 0; index < safe.length(); index++) {
            char character = safe.charAt(index);
            boolean lower = character >= 'a' && character <= 'z';
            boolean allowed = lower
                    || (index > 0 && character >= '0' && character <= '9')
                    || (index > 0 && (character == '.'
                            || character == '_'
                            || character == '-'));
            if (!allowed) {
                throw new IllegalArgumentException(name + " has an invalid shape");
            }
        }
        return safe;
    }

    static String requirePackageName(String value) {
        String safe = Objects.requireNonNull(value, "packageName");
        if (safe.isEmpty() || safe.length() > MAX_PACKAGE_NAME_CODE_POINTS) {
            throw new IllegalArgumentException("packageName is outside its bound");
        }
        int segmentLength = 0;
        int segments = 1;
        for (int index = 0; index < safe.length(); index++) {
            char character = safe.charAt(index);
            if (character == '.') {
                if (segmentLength == 0) {
                    throw new IllegalArgumentException("packageName has an invalid shape");
                }
                segments++;
                segmentLength = 0;
                continue;
            }
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_';
            if (!allowed) {
                throw new IllegalArgumentException("packageName has an invalid shape");
            }
            segmentLength++;
        }
        if (segments < 2 || segmentLength == 0) {
            throw new IllegalArgumentException("packageName has an invalid shape");
        }
        return safe;
    }
}
