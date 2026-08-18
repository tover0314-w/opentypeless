package com.opentypeless.android.config;

/** One immutable application-scoped set of three-state configuration overrides. */
public record AppRule(
        String packageName,
        OverrideValue<String> voiceRouteId,
        OverrideValue<ProcessingMode> processingMode,
        OverrideValue<Boolean> sendContext,
        OverrideValue<Boolean> historyEnabled,
        OverrideValue<String> actionSetId) {

    public AppRule {
        packageName = RuleOverrides.requirePackageName(packageName);
        voiceRouteId = RuleOverrides.requireIdentifierOverride(voiceRouteId, "voiceRouteId");
        processingMode = RuleOverrides.requireProcessingOverride(
                processingMode,
                "processingMode");
        sendContext = RuleOverrides.requireBooleanOverride(sendContext, "sendContext");
        historyEnabled = RuleOverrides.requireBooleanOverride(
                historyEnabled,
                "historyEnabled");
        actionSetId = RuleOverrides.requireIdentifierOverride(actionSetId, "actionSetId");
    }

    @Override
    public String toString() {
        return "AppRule{packageName=<redacted>, overrides=<redacted>}";
    }
}
