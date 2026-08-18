package com.opentypeless.android.config;

import com.opentypeless.android.config.EffectiveProfile.ResolutionExplanation;
import com.opentypeless.android.config.EffectiveProfile.ResolvedValue;
import com.opentypeless.android.config.EffectiveProfile.RuleSource;
import com.opentypeless.android.context.FieldKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The single pure-domain authority for effective configuration precedence. */
public final class EffectiveProfileResolver {
    public static final int MAX_APP_RULES = 256;
    public static final int MAX_FIELD_RULES = 512;

    private EffectiveProfileResolver() {}

    public static EffectiveProfile resolve(Request request) {
        Request safe = Objects.requireNonNull(request, "request");
        ResolvedValue<String> keyboard = EffectiveProfile.resolved(
                OverrideValue.value(safe.globalConfig().keyboard().layoutId()),
                RuleSource.GLOBAL,
                ResolutionExplanation.REQUIRED_GLOBAL_VALUE);
        if (safe.fieldKind() == FieldKind.SENSITIVE) {
            return new EffectiveProfile(
                    keyboard,
                    hardDisabled(),
                    hardValue(ProcessingMode.EXACT),
                    hardDisabled(),
                    hardDisabled(),
                    hardDisabled());
        }

        AppRule application = matchingAppRule(safe.appRules(), safe.packageName());
        FieldRule field = matchingFieldRule(
                safe.fieldRules(),
                safe.packageName(),
                safe.fieldKind());
        RuleOverrides session = safe.sessionOverrides();
        RuleOverrides fieldOverrides = field == null ? null : field.overrides();
        ProviderDefaults defaults = safe.providerDefaults();
        GlobalConfig global = safe.globalConfig();

        return new EffectiveProfile(
                keyboard,
                resolveLeaf(
                        session.voiceRouteId(),
                        fieldOverrides == null ? OverrideValue.inherit() : fieldOverrides.voiceRouteId(),
                        application == null ? OverrideValue.inherit() : application.voiceRouteId(),
                        global.voice().routeId(),
                        defaults.voiceRouteId()),
                resolveLeaf(
                        session.processingMode(),
                        fieldOverrides == null ? OverrideValue.inherit() : fieldOverrides.processingMode(),
                        application == null ? OverrideValue.inherit() : application.processingMode(),
                        global.processing().mode(),
                        defaults.processingMode()),
                resolveLeaf(
                        session.sendContext(),
                        fieldOverrides == null ? OverrideValue.inherit() : fieldOverrides.sendContext(),
                        application == null ? OverrideValue.inherit() : application.sendContext(),
                        global.privacy().sendContext(),
                        defaults.sendContext()),
                resolveLeaf(
                        session.historyEnabled(),
                        fieldOverrides == null ? OverrideValue.inherit() : fieldOverrides.historyEnabled(),
                        application == null ? OverrideValue.inherit() : application.historyEnabled(),
                        global.privacy().historyEnabled(),
                        defaults.historyEnabled()),
                resolveLeaf(
                        session.actionSetId(),
                        fieldOverrides == null ? OverrideValue.inherit() : fieldOverrides.actionSetId(),
                        application == null ? OverrideValue.inherit() : application.actionSetId(),
                        global.automation().actionSetId(),
                        defaults.actionSetId()));
    }

    private static AppRule matchingAppRule(List<AppRule> rules, String packageName) {
        for (AppRule rule : rules) {
            if (rule.packageName().equals(packageName)) return rule;
        }
        return null;
    }

    private static FieldRule matchingFieldRule(
            List<FieldRule> rules,
            String packageName,
            FieldKind fieldKind) {
        for (FieldRule rule : rules) {
            FieldRule.FieldMatcher matcher = rule.matcher();
            if (matcher.packageName().equals(packageName)
                    && matcher.fieldKind() == fieldKind) {
                return rule;
            }
        }
        return null;
    }

    private static <T> ResolvedValue<T> resolveLeaf(
            OverrideValue<T> session,
            OverrideValue<T> field,
            OverrideValue<T> application,
            OverrideValue<T> global,
            OverrideValue<T> providerDefault) {
        if (!(session instanceof OverrideValue.Inherit<?>)) {
            return selected(session, RuleSource.SESSION);
        }
        if (!(field instanceof OverrideValue.Inherit<?>)) {
            return selected(field, RuleSource.FIELD);
        }
        if (!(application instanceof OverrideValue.Inherit<?>)) {
            return selected(application, RuleSource.APPLICATION);
        }
        if (!(global instanceof OverrideValue.Inherit<?>)) {
            return selected(global, RuleSource.GLOBAL);
        }
        return selected(providerDefault, RuleSource.PROVIDER_DEFAULT);
    }

    private static <T> ResolvedValue<T> selected(
            OverrideValue<T> value,
            RuleSource source) {
        return EffectiveProfile.resolved(
                value,
                source,
                value instanceof OverrideValue.Disabled<?>
                        ? ResolutionExplanation.EXPLICIT_DISABLED
                        : ResolutionExplanation.EXPLICIT_VALUE);
    }

    private static <T> ResolvedValue<T> hardDisabled() {
        return EffectiveProfile.resolved(
                OverrideValue.disabled(),
                RuleSource.HARD_SAFETY,
                ResolutionExplanation.HARD_SENSITIVE_FIELD);
    }

    private static <T> ResolvedValue<T> hardValue(T value) {
        return EffectiveProfile.resolved(
                OverrideValue.value(value),
                RuleSource.HARD_SAFETY,
                ResolutionExplanation.HARD_SENSITIVE_FIELD);
    }

    /** Complete lowest-precedence values; Inherit is forbidden so resolution always terminates. */
    public record ProviderDefaults(
            OverrideValue<String> voiceRouteId,
            OverrideValue<ProcessingMode> processingMode,
            OverrideValue<Boolean> sendContext,
            OverrideValue<Boolean> historyEnabled,
            OverrideValue<String> actionSetId) {
        public ProviderDefaults {
            voiceRouteId = requireTerminalIdentifier(voiceRouteId, "voiceRouteId");
            processingMode = requireTerminalProcessing(processingMode, "processingMode");
            sendContext = requireTerminalBoolean(sendContext, "sendContext");
            historyEnabled = requireTerminalBoolean(historyEnabled, "historyEnabled");
            actionSetId = requireTerminalIdentifier(actionSetId, "actionSetId");
        }

        @Override
        public String toString() {
            return "ProviderDefaults{values=<redacted>}";
        }
    }

    /** One immutable resolution input with bounded, duplicate-free rules. */
    public record Request(
            GlobalConfig globalConfig,
            ProviderDefaults providerDefaults,
            List<AppRule> appRules,
            List<FieldRule> fieldRules,
            RuleOverrides sessionOverrides,
            String packageName,
            FieldKind fieldKind) {
        public Request {
            globalConfig = Objects.requireNonNull(globalConfig, "globalConfig");
            providerDefaults = Objects.requireNonNull(providerDefaults, "providerDefaults");
            appRules = immutableAppRules(appRules);
            fieldRules = immutableFieldRules(fieldRules);
            sessionOverrides = Objects.requireNonNull(sessionOverrides, "sessionOverrides");
            packageName = RuleOverrides.requirePackageName(packageName);
            fieldKind = Objects.requireNonNull(fieldKind, "fieldKind");
        }

        @Override
        public String toString() {
            return "Request{target=<redacted>, appRuleCount=" + appRules.size()
                    + ", fieldRuleCount=" + fieldRules.size() + "}";
        }
    }

    /** Stable input failure classification without raw collection or payload messages. */
    public enum ResolutionFailure {
        INVALID_REQUEST,
        APP_RULE_LIMIT_EXCEEDED,
        FIELD_RULE_LIMIT_EXCEEDED,
        DUPLICATE_APP_RULE,
        DUPLICATE_FIELD_RULE,
        PROVIDER_DEFAULT_INHERIT
    }

    /** Content-free exception for malformed or ambiguous resolver inputs. */
    public static final class ResolutionException extends IllegalArgumentException {
        private final ResolutionFailure failure;

        private ResolutionException(ResolutionFailure failure) {
            super(message(failure));
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        public ResolutionFailure failure() {
            return failure;
        }

        private static String message(ResolutionFailure failure) {
            Objects.requireNonNull(failure, "failure");
            if (failure == ResolutionFailure.INVALID_REQUEST) {
                return "resolver request is invalid";
            }
            if (failure == ResolutionFailure.APP_RULE_LIMIT_EXCEEDED) {
                return "app rule limit exceeded";
            }
            if (failure == ResolutionFailure.FIELD_RULE_LIMIT_EXCEEDED) {
                return "field rule limit exceeded";
            }
            if (failure == ResolutionFailure.DUPLICATE_APP_RULE) {
                return "duplicate app rule";
            }
            if (failure == ResolutionFailure.DUPLICATE_FIELD_RULE) {
                return "duplicate field rule";
            }
            return "provider default cannot inherit";
        }

        @Override
        public String toString() {
            return "ResolutionException{failure=" + failure + "}";
        }
    }

    private static List<AppRule> immutableAppRules(List<AppRule> rules) {
        List<AppRule> copy = new ArrayList<>(Math.min(MAX_APP_RULES, 16));
        Set<String> packages = new HashSet<>();
        Iterator<AppRule> iterator = safeIterator(rules);
        try {
            while (iterator.hasNext()) {
                if (copy.size() == MAX_APP_RULES) {
                    throw failure(ResolutionFailure.APP_RULE_LIMIT_EXCEEDED);
                }
                AppRule rule = iterator.next();
                if (rule == null) throw failure(ResolutionFailure.INVALID_REQUEST);
                if (!packages.add(rule.packageName())) {
                    throw failure(ResolutionFailure.DUPLICATE_APP_RULE);
                }
                copy.add(rule);
            }
        } catch (ResolutionException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(ResolutionFailure.INVALID_REQUEST);
        }
        return List.copyOf(copy);
    }

    private static List<FieldRule> immutableFieldRules(List<FieldRule> rules) {
        List<FieldRule> copy = new ArrayList<>(Math.min(MAX_FIELD_RULES, 16));
        Set<FieldRule.FieldMatcher> matchers = new HashSet<>();
        Iterator<FieldRule> iterator = safeIterator(rules);
        try {
            while (iterator.hasNext()) {
                if (copy.size() == MAX_FIELD_RULES) {
                    throw failure(ResolutionFailure.FIELD_RULE_LIMIT_EXCEEDED);
                }
                FieldRule rule = iterator.next();
                if (rule == null) throw failure(ResolutionFailure.INVALID_REQUEST);
                if (!matchers.add(rule.matcher())) {
                    throw failure(ResolutionFailure.DUPLICATE_FIELD_RULE);
                }
                copy.add(rule);
            }
        } catch (ResolutionException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(ResolutionFailure.INVALID_REQUEST);
        }
        return List.copyOf(copy);
    }

    private static <T> Iterator<T> safeIterator(List<T> values) {
        if (values == null) throw failure(ResolutionFailure.INVALID_REQUEST);
        try {
            Iterator<T> iterator = values.iterator();
            if (iterator == null) throw failure(ResolutionFailure.INVALID_REQUEST);
            return iterator;
        } catch (ResolutionException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(ResolutionFailure.INVALID_REQUEST);
        }
    }

    private static OverrideValue<String> requireTerminalIdentifier(
            OverrideValue<String> value,
            String name) {
        OverrideValue<String> safe = RuleOverrides.requireIdentifierOverride(value, name);
        requireTerminal(safe);
        return safe;
    }

    private static OverrideValue<ProcessingMode> requireTerminalProcessing(
            OverrideValue<ProcessingMode> value,
            String name) {
        OverrideValue<ProcessingMode> safe = RuleOverrides.requireProcessingOverride(value, name);
        requireTerminal(safe);
        return safe;
    }

    private static OverrideValue<Boolean> requireTerminalBoolean(
            OverrideValue<Boolean> value,
            String name) {
        OverrideValue<Boolean> safe = RuleOverrides.requireBooleanOverride(value, name);
        requireTerminal(safe);
        return safe;
    }

    private static void requireTerminal(OverrideValue<?> value) {
        if (value instanceof OverrideValue.Inherit<?>) {
            throw failure(ResolutionFailure.PROVIDER_DEFAULT_INHERIT);
        }
    }

    private static ResolutionException failure(ResolutionFailure failure) {
        return new ResolutionException(failure);
    }
}
