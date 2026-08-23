package com.opentypeless.android.config;

import java.util.Objects;

/** Fully resolved, immutable configuration for one exact application field. */
public record EffectiveProfile(
        ResolvedValue<String> keyboardLayoutId,
        ResolvedValue<String> voiceRouteId,
        ResolvedValue<ProcessingMode> processingMode,
        ResolvedValue<Boolean> sendContext,
        ResolvedValue<Boolean> historyEnabled,
        ResolvedValue<String> actionSetId) {

    public EffectiveProfile {
        keyboardLayoutId = requireIdentifier(
                keyboardLayoutId,
                "keyboardLayoutId",
                false);
        if (keyboardLayoutId.source() != RuleSource.GLOBAL
                || keyboardLayoutId.explanation()
                        != ResolutionExplanation.REQUIRED_GLOBAL_VALUE) {
            throw new IllegalArgumentException("keyboard layout must come from global config");
        }
        voiceRouteId = requireIdentifier(voiceRouteId, "voiceRouteId", true);
        processingMode = requireProcessing(processingMode, "processingMode");
        sendContext = requireBoolean(sendContext, "sendContext");
        historyEnabled = requireBoolean(historyEnabled, "historyEnabled");
        actionSetId = requireIdentifier(actionSetId, "actionSetId", true);

        boolean hardSafety = voiceRouteId.source() == RuleSource.HARD_SAFETY
                || processingMode.source() == RuleSource.HARD_SAFETY
                || sendContext.source() == RuleSource.HARD_SAFETY
                || historyEnabled.source() == RuleSource.HARD_SAFETY
                || actionSetId.source() == RuleSource.HARD_SAFETY;
        if (hardSafety && !(voiceRouteId.source() == RuleSource.HARD_SAFETY
                && processingMode.source() == RuleSource.HARD_SAFETY
                && sendContext.source() == RuleSource.HARD_SAFETY
                && historyEnabled.source() == RuleSource.HARD_SAFETY
                && actionSetId.source() == RuleSource.HARD_SAFETY)) {
            throw new IllegalArgumentException("hard safety must resolve the complete policy set");
        }
        if (hardSafety
                && (!(voiceRouteId.value() instanceof OverrideValue.Disabled<?>)
                        || !OverrideValue.value(ProcessingMode.EXACT)
                                .equals(processingMode.value())
                        || !(sendContext.value() instanceof OverrideValue.Disabled<?>)
                        || !(historyEnabled.value() instanceof OverrideValue.Disabled<?>)
                        || !(actionSetId.value() instanceof OverrideValue.Disabled<?>))) {
            throw new IllegalArgumentException("hard safety values are invalid");
        }
    }

    @Override
    public String toString() {
        return "EffectiveProfile{values=<redacted>, sources=["
                + keyboardLayoutId.source() + ","
                + voiceRouteId.source() + ","
                + processingMode.source() + ","
                + sendContext.source() + ","
                + historyEnabled.source() + ","
                + actionSetId.source() + "]}";
    }

    static <T> ResolvedValue<T> resolved(
            OverrideValue<T> value,
            RuleSource source,
            ResolutionExplanation explanation) {
        return new ResolvedValue<>(value, source, explanation);
    }

    private static ResolvedValue<String> requireIdentifier(
            ResolvedValue<String> resolved,
            String name,
            boolean allowDisabled) {
        ResolvedValue<String> safe = Objects.requireNonNull(resolved, name);
        OverrideValue<String> value = safe.value();
        if (value instanceof OverrideValue.Disabled<?>) {
            if (!allowDisabled) {
                throw new IllegalArgumentException(name + " cannot be disabled");
            }
            return safe;
        }
        if (!(value instanceof OverrideValue.Value<?> explicit)
                || !(explicit.value() instanceof String identifier)) {
            throw new IllegalArgumentException(name + " has an invalid value type");
        }
        RuleOverrides.requireConfigId(identifier, name);
        return safe;
    }

    private static ResolvedValue<ProcessingMode> requireProcessing(
            ResolvedValue<ProcessingMode> resolved,
            String name) {
        ResolvedValue<ProcessingMode> safe = Objects.requireNonNull(resolved, name);
        OverrideValue<ProcessingMode> value = safe.value();
        if (!(value instanceof OverrideValue.Disabled<?>)
                && (!(value instanceof OverrideValue.Value<?> explicit)
                        || !(explicit.value() instanceof ProcessingMode))) {
            throw new IllegalArgumentException(name + " has an invalid value type");
        }
        return safe;
    }

    private static ResolvedValue<Boolean> requireBoolean(
            ResolvedValue<Boolean> resolved,
            String name) {
        ResolvedValue<Boolean> safe = Objects.requireNonNull(resolved, name);
        OverrideValue<Boolean> value = safe.value();
        if (!(value instanceof OverrideValue.Disabled<?>)
                && (!(value instanceof OverrideValue.Value<?> explicit)
                        || !(explicit.value() instanceof Boolean))) {
            throw new IllegalArgumentException(name + " has an invalid value type");
        }
        return safe;
    }

    /** Audited source layer for one effective value. */
    public enum RuleSource {
        HARD_SAFETY,
        SESSION,
        FIELD,
        APPLICATION,
        GLOBAL,
        PROVIDER_DEFAULT
    }

    /** Stable, content-free explanation that a UI may localize. */
    public enum ResolutionExplanation {
        HARD_SENSITIVE_FIELD,
        REQUIRED_GLOBAL_VALUE,
        EXPLICIT_VALUE,
        EXPLICIT_DISABLED
    }

    /** One non-inherited terminal value plus its exact source and stable explanation. */
    public static final class ResolvedValue<T> {
        private final OverrideValue<T> value;
        private final RuleSource source;
        private final ResolutionExplanation explanation;

        private ResolvedValue(
                OverrideValue<T> value,
                RuleSource source,
                ResolutionExplanation explanation) {
            this.value = Objects.requireNonNull(value, "value");
            this.source = Objects.requireNonNull(source, "source");
            this.explanation = Objects.requireNonNull(explanation, "explanation");
            if (value instanceof OverrideValue.Inherit<?>) {
                throw new IllegalArgumentException("resolved value cannot inherit");
            }
            boolean disabled = value instanceof OverrideValue.Disabled<?>;
            boolean valid;
            if (explanation == ResolutionExplanation.HARD_SENSITIVE_FIELD) {
                valid = source == RuleSource.HARD_SAFETY;
            } else if (explanation == ResolutionExplanation.REQUIRED_GLOBAL_VALUE) {
                valid = source == RuleSource.GLOBAL && !disabled;
            } else if (explanation == ResolutionExplanation.EXPLICIT_VALUE) {
                valid = source != RuleSource.HARD_SAFETY && !disabled;
            } else {
                valid = source != RuleSource.HARD_SAFETY && disabled;
            }
            if (!valid) {
                throw new IllegalArgumentException("resolved explanation does not match state");
            }
        }

        public OverrideValue<T> value() {
            return value;
        }

        public RuleSource source() {
            return source;
        }

        public ResolutionExplanation explanation() {
            return explanation;
        }

        public boolean isDisabled() {
            return value instanceof OverrideValue.Disabled<?>;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ResolvedValue<?> resolved
                    && value.equals(resolved.value)
                    && source == resolved.source
                    && explanation == resolved.explanation;
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, source, explanation);
        }

        @Override
        public String toString() {
            return "ResolvedValue{state=" + (isDisabled() ? "DISABLED" : "VALUE")
                    + ", source=" + source
                    + ", explanation=" + explanation + "}";
        }
    }
}
