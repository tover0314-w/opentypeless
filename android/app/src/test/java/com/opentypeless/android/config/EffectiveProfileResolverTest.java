package com.opentypeless.android.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.EffectiveProfile.ResolutionExplanation;
import com.opentypeless.android.config.EffectiveProfile.ResolvedValue;
import com.opentypeless.android.config.EffectiveProfile.RuleSource;
import com.opentypeless.android.config.EffectiveProfileResolver.ProviderDefaults;
import com.opentypeless.android.config.EffectiveProfileResolver.Request;
import com.opentypeless.android.config.EffectiveProfileResolver.ResolutionException;
import com.opentypeless.android.config.EffectiveProfileResolver.ResolutionFailure;
import com.opentypeless.android.context.FieldKind;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.junit.Test;

public final class EffectiveProfileResolverTest {
    @Test
    public void exactClosedVocabularyAndSurfacesAreStable() {
        assertArrayEquals(
                new RuleSource[] {
                    RuleSource.HARD_SAFETY,
                    RuleSource.SESSION,
                    RuleSource.FIELD,
                    RuleSource.APPLICATION,
                    RuleSource.GLOBAL,
                    RuleSource.PROVIDER_DEFAULT
                },
                RuleSource.values());
        assertArrayEquals(
                new ResolutionExplanation[] {
                    ResolutionExplanation.HARD_SENSITIVE_FIELD,
                    ResolutionExplanation.REQUIRED_GLOBAL_VALUE,
                    ResolutionExplanation.EXPLICIT_VALUE,
                    ResolutionExplanation.EXPLICIT_DISABLED
                },
                ResolutionExplanation.values());
        assertArrayEquals(
                new ResolutionFailure[] {
                    ResolutionFailure.INVALID_REQUEST,
                    ResolutionFailure.APP_RULE_LIMIT_EXCEEDED,
                    ResolutionFailure.FIELD_RULE_LIMIT_EXCEEDED,
                    ResolutionFailure.DUPLICATE_APP_RULE,
                    ResolutionFailure.DUPLICATE_FIELD_RULE,
                    ResolutionFailure.PROVIDER_DEFAULT_INHERIT
                },
                ResolutionFailure.values());
        assertEquals(256, EffectiveProfileResolver.MAX_APP_RULES);
        assertEquals(512, EffectiveProfileResolver.MAX_FIELD_RULES);
        assertTrue(Modifier.isFinal(EffectiveProfileResolver.class.getModifiers()));
        assertEquals(1, EffectiveProfileResolver.class.getDeclaredConstructors().length);
        assertTrue(Modifier.isPrivate(
                EffectiveProfileResolver.class.getDeclaredConstructors()[0].getModifiers()));

        Constructor<?>[] resolvedConstructors = ResolvedValue.class.getDeclaredConstructors();
        assertEquals(1, resolvedConstructors.length);
        assertTrue(Modifier.isPrivate(resolvedConstructors[0].getModifiers()));
        assertArrayEquals(
                new String[] {
                    "keyboardLayoutId",
                    "voiceRouteId",
                    "processingMode",
                    "sendContext",
                    "historyEnabled",
                    "actionSetId"
                },
                Arrays.stream(EffectiveProfile.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toArray(String[]::new));
    }

    @Test
    public void eachLeafUsesTheFirstNonInheritedLayerAndReportsItsExactSource() {
        String target = "com.example.editor";
        RuleOverrides session = overrides(
                OverrideValue.value("route.session"),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit());
        FieldRule field = fieldRule(
                target,
                FieldKind.SEARCH,
                overrides(
                        OverrideValue.value("route.field.ignored"),
                        OverrideValue.disabled(),
                        OverrideValue.inherit(),
                        OverrideValue.inherit(),
                        OverrideValue.inherit()));
        AppRule app = appRule(
                target,
                OverrideValue.value("route.app.ignored"),
                OverrideValue.value(ProcessingMode.TRANSLATE),
                OverrideValue.value(false),
                OverrideValue.inherit(),
                OverrideValue.inherit());
        GlobalConfig global = global(
                OverrideValue.value("route.global"),
                OverrideValue.value(ProcessingMode.SMART),
                OverrideValue.value(true),
                OverrideValue.value(true),
                OverrideValue.inherit());
        ProviderDefaults defaults = defaults(
                OverrideValue.value("route.provider"),
                OverrideValue.value(ProcessingMode.AUTO),
                OverrideValue.value(true),
                OverrideValue.value(false),
                OverrideValue.value("actions.provider"));

        EffectiveProfile profile = EffectiveProfileResolver.resolve(new Request(
                global,
                defaults,
                List.of(app),
                List.of(field),
                session,
                target,
                FieldKind.SEARCH));

        assertResolved(
                profile.keyboardLayoutId(),
                OverrideValue.value("latin.base"),
                RuleSource.GLOBAL,
                ResolutionExplanation.REQUIRED_GLOBAL_VALUE);
        assertResolved(
                profile.voiceRouteId(),
                OverrideValue.value("route.session"),
                RuleSource.SESSION,
                ResolutionExplanation.EXPLICIT_VALUE);
        assertResolved(
                profile.processingMode(),
                OverrideValue.disabled(),
                RuleSource.FIELD,
                ResolutionExplanation.EXPLICIT_DISABLED);
        assertResolved(
                profile.sendContext(),
                OverrideValue.value(false),
                RuleSource.APPLICATION,
                ResolutionExplanation.EXPLICIT_VALUE);
        assertResolved(
                profile.historyEnabled(),
                OverrideValue.value(true),
                RuleSource.GLOBAL,
                ResolutionExplanation.EXPLICIT_VALUE);
        assertResolved(
                profile.actionSetId(),
                OverrideValue.value("actions.provider"),
                RuleSource.PROVIDER_DEFAULT,
                ResolutionExplanation.EXPLICIT_VALUE);
    }

    @Test
    public void matchingIsExactAndUnmatchedRulesCannotInfluenceTheTarget() {
        AppRule wrongCase = appRule(
                "com.example.Editor",
                OverrideValue.value("route.wrong"),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit());
        FieldRule wrongKind = fieldRule(
                "com.example.editor",
                FieldKind.LONG_TEXT,
                overrides(
                        OverrideValue.value("route.wrong.field"),
                        OverrideValue.inherit(),
                        OverrideValue.inherit(),
                        OverrideValue.inherit(),
                        OverrideValue.inherit()));

        EffectiveProfile profile = resolve(
                List.of(wrongCase),
                List.of(wrongKind),
                inherited(),
                "com.example.editor",
                FieldKind.SEARCH);

        assertResolved(
                profile.voiceRouteId(),
                OverrideValue.value("route.global"),
                RuleSource.GLOBAL,
                ResolutionExplanation.EXPLICIT_VALUE);
    }

    @Test
    public void disabledStopsResolutionWhileExplicitFalseRemainsAValue() {
        AppRule app = appRule(
                "com.example.editor",
                OverrideValue.disabled(),
                OverrideValue.inherit(),
                OverrideValue.value(false),
                OverrideValue.inherit(),
                OverrideValue.inherit());
        EffectiveProfile profile = resolve(
                List.of(app),
                List.of(),
                inherited(),
                "com.example.editor",
                FieldKind.GENERAL);

        assertTrue(profile.voiceRouteId().isDisabled());
        assertSame(OverrideValue.disabled(), profile.voiceRouteId().value());
        assertEquals(RuleSource.APPLICATION, profile.voiceRouteId().source());
        assertFalse(profile.sendContext().isDisabled());
        assertEquals(OverrideValue.value(false), profile.sendContext().value());
        assertEquals(RuleSource.APPLICATION, profile.sendContext().source());
    }

    @Test
    public void sensitiveFieldHardRulesCannotBeRelaxedByAnyConfiguredLayer() {
        String target = "com.private.payments";
        RuleOverrides unsafe = overrides(
                OverrideValue.value("route.public"),
                OverrideValue.value(ProcessingMode.TRANSLATE),
                OverrideValue.value(true),
                OverrideValue.value(true),
                OverrideValue.value("actions.remote"));
        AppRule app = appRule(
                target,
                unsafe.voiceRouteId(),
                unsafe.processingMode(),
                unsafe.sendContext(),
                unsafe.historyEnabled(),
                unsafe.actionSetId());
        FieldRule field = fieldRule(target, FieldKind.SENSITIVE, unsafe);

        EffectiveProfile profile = EffectiveProfileResolver.resolve(new Request(
                global(
                        unsafe.voiceRouteId(),
                        unsafe.processingMode(),
                        unsafe.sendContext(),
                        unsafe.historyEnabled(),
                        unsafe.actionSetId()),
                defaults(
                        unsafe.voiceRouteId(),
                        unsafe.processingMode(),
                        unsafe.sendContext(),
                        unsafe.historyEnabled(),
                        unsafe.actionSetId()),
                List.of(app),
                List.of(field),
                unsafe,
                target,
                FieldKind.SENSITIVE));

        for (ResolvedValue<?> value : List.of(
                profile.voiceRouteId(),
                profile.processingMode(),
                profile.sendContext(),
                profile.historyEnabled(),
                profile.actionSetId())) {
            assertEquals(RuleSource.HARD_SAFETY, value.source());
            assertEquals(
                    ResolutionExplanation.HARD_SENSITIVE_FIELD,
                    value.explanation());
        }
        assertTrue(profile.voiceRouteId().isDisabled());
        assertEquals(OverrideValue.value(ProcessingMode.EXACT), profile.processingMode().value());
        assertTrue(profile.sendContext().isDisabled());
        assertTrue(profile.historyEnabled().isDisabled());
        assertTrue(profile.actionSetId().isDisabled());
    }

    @Test
    public void everyProviderDefaultMustTerminateResolution() {
        ProviderDefaults baseline = providerDefaults();
        for (int index = 0; index < 5; index++) {
            int invalid = index;
            ResolutionException error = assertThrows(
                    ResolutionException.class,
                    () -> new ProviderDefaults(
                            invalid == 0 ? OverrideValue.inherit() : baseline.voiceRouteId(),
                            invalid == 1 ? OverrideValue.inherit() : baseline.processingMode(),
                            invalid == 2 ? OverrideValue.inherit() : baseline.sendContext(),
                            invalid == 3 ? OverrideValue.inherit() : baseline.historyEnabled(),
                            invalid == 4 ? OverrideValue.inherit() : baseline.actionSetId()));
            assertEquals(ResolutionFailure.PROVIDER_DEFAULT_INHERIT, error.failure());
            assertFalse(error.toString().contains("route.provider"));
        }
        ProviderDefaults disabled = defaults(
                OverrideValue.disabled(),
                OverrideValue.disabled(),
                OverrideValue.disabled(),
                OverrideValue.disabled(),
                OverrideValue.disabled());
        EffectiveProfile profile = EffectiveProfileResolver.resolve(request(
                global(
                        OverrideValue.inherit(),
                        OverrideValue.inherit(),
                        OverrideValue.inherit(),
                        OverrideValue.inherit(),
                        OverrideValue.inherit()),
                disabled,
                List.of(),
                List.of(),
                inherited(),
                "com.example.editor",
                FieldKind.GENERAL));
        assertTrue(profile.voiceRouteId().isDisabled());
        assertEquals(RuleSource.PROVIDER_DEFAULT, profile.voiceRouteId().source());
    }

    @Test
    public void duplicateRulesFailClosedInsteadOfUsingListOrder() {
        AppRule first = appRule("com.example.editor", OverrideValue.value("route.first"));
        AppRule second = appRule("com.example.editor", OverrideValue.value("route.second"));
        ResolutionException appError = assertThrows(
                ResolutionException.class,
                () -> request(
                        globalConfig(),
                        providerDefaults(),
                        List.of(first, second),
                        List.of(),
                        inherited(),
                        "com.example.editor",
                        FieldKind.GENERAL));
        assertEquals(ResolutionFailure.DUPLICATE_APP_RULE, appError.failure());

        FieldRule fieldFirst = fieldRule(
                "com.example.editor",
                FieldKind.SEARCH,
                overrides(OverrideValue.value("route.first")));
        FieldRule fieldSecond = fieldRule(
                "com.example.editor",
                FieldKind.SEARCH,
                overrides(OverrideValue.value("route.second")));
        ResolutionException fieldError = assertThrows(
                ResolutionException.class,
                () -> request(
                        globalConfig(),
                        providerDefaults(),
                        List.of(),
                        List.of(fieldFirst, fieldSecond),
                        inherited(),
                        "com.example.editor",
                        FieldKind.SEARCH));
        assertEquals(ResolutionFailure.DUPLICATE_FIELD_RULE, fieldError.failure());
    }

    @Test
    public void boundedCopiesRejectEndlessOrOversizedRuleSources() {
        ArrayList<AppRule> maximumApps = new ArrayList<>();
        for (int index = 0; index < EffectiveProfileResolver.MAX_APP_RULES; index++) {
            maximumApps.add(appRule(
                    "com.maximum.app" + index,
                    OverrideValue.inherit()));
        }
        ArrayList<FieldRule> maximumFields = new ArrayList<>();
        for (int index = 0; index < EffectiveProfileResolver.MAX_FIELD_RULES; index++) {
            maximumFields.add(fieldRule(
                    "com.maximum.field" + index,
                    FieldKind.GENERAL,
                    inherited()));
        }
        Request maximum = request(
                globalConfig(),
                providerDefaults(),
                maximumApps,
                maximumFields,
                inherited(),
                "com.example.editor",
                FieldKind.GENERAL);
        assertEquals(EffectiveProfileResolver.MAX_APP_RULES, maximum.appRules().size());
        assertEquals(EffectiveProfileResolver.MAX_FIELD_RULES, maximum.fieldRules().size());

        ResolutionException appLimit = assertThrows(
                ResolutionException.class,
                () -> request(
                        globalConfig(),
                        providerDefaults(),
                        generatedAppRules(),
                        List.of(),
                        inherited(),
                        "com.example.editor",
                        FieldKind.GENERAL));
        assertEquals(ResolutionFailure.APP_RULE_LIMIT_EXCEEDED, appLimit.failure());

        ResolutionException fieldLimit = assertThrows(
                ResolutionException.class,
                () -> request(
                        globalConfig(),
                        providerDefaults(),
                        List.of(),
                        generatedFieldRules(),
                        inherited(),
                        "com.example.editor",
                        FieldKind.GENERAL));
        assertEquals(ResolutionFailure.FIELD_RULE_LIMIT_EXCEEDED, fieldLimit.failure());

        List<AppRule> throwing = new AbstractList<>() {
            @Override
            public AppRule get(int index) {
                throw new AssertionError("get must not be used");
            }

            @Override
            public int size() {
                throw new IllegalStateException("private.customer.rule");
            }

            @Override
            public Iterator<AppRule> iterator() {
                throw new IllegalStateException("private.customer.rule");
            }
        };
        ResolutionException invalid = assertThrows(
                ResolutionException.class,
                () -> request(
                        globalConfig(),
                        providerDefaults(),
                        throwing,
                        List.of(),
                        inherited(),
                        "com.example.editor",
                        FieldKind.GENERAL));
        assertEquals(ResolutionFailure.INVALID_REQUEST, invalid.failure());
        assertFalse(invalid.toString().contains("private.customer.rule"));
        assertEquals(null, invalid.getCause());
    }

    @Test
    public void requestDefensivelyCopiesInputsAndExposesUnmodifiableRules() {
        ArrayList<AppRule> appRules = new ArrayList<>();
        appRules.add(appRule("com.example.editor", OverrideValue.value("route.original")));
        ArrayList<FieldRule> fieldRules = new ArrayList<>();
        Request request = request(
                globalConfig(),
                providerDefaults(),
                appRules,
                fieldRules,
                inherited(),
                "com.example.editor",
                FieldKind.GENERAL);
        appRules.clear();
        fieldRules.add(fieldRule(
                "com.example.editor",
                FieldKind.GENERAL,
                overrides(OverrideValue.value("route.mutated"))));

        EffectiveProfile profile = EffectiveProfileResolver.resolve(request);
        assertEquals(OverrideValue.value("route.original"), profile.voiceRouteId().value());
        assertThrows(UnsupportedOperationException.class, () -> request.appRules().clear());
        assertThrows(UnsupportedOperationException.class, () -> request.fieldRules().clear());
        assertNotSame(appRules, request.appRules());
        assertNotSame(fieldRules, request.fieldRules());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void erasedPayloadsAndForgedResultShapesRemainInvalid() {
        OverrideValue forged = OverrideValue.value("true");
        assertThrows(
                IllegalArgumentException.class,
                () -> defaults(
                        OverrideValue.value("route.provider"),
                        OverrideValue.value(ProcessingMode.AUTO),
                        forged,
                        OverrideValue.value(false),
                        OverrideValue.disabled()));

        ResolvedValue<String> keyboard = EffectiveProfile.resolved(
                OverrideValue.value("latin.base"),
                RuleSource.GLOBAL,
                ResolutionExplanation.REQUIRED_GLOBAL_VALUE);
        ResolvedValue<String> hardRoute = EffectiveProfile.resolved(
                OverrideValue.disabled(),
                RuleSource.HARD_SAFETY,
                ResolutionExplanation.HARD_SENSITIVE_FIELD);
        assertThrows(
                IllegalArgumentException.class,
                () -> new EffectiveProfile(
                        keyboard,
                        hardRoute,
                        EffectiveProfile.resolved(
                                OverrideValue.value(ProcessingMode.EXACT),
                                RuleSource.GLOBAL,
                                ResolutionExplanation.EXPLICIT_VALUE),
                        EffectiveProfile.resolved(
                                OverrideValue.disabled(),
                                RuleSource.HARD_SAFETY,
                                ResolutionExplanation.HARD_SENSITIVE_FIELD),
                        EffectiveProfile.resolved(
                                OverrideValue.disabled(),
                                RuleSource.HARD_SAFETY,
                                ResolutionExplanation.HARD_SENSITIVE_FIELD),
                        EffectiveProfile.resolved(
                                OverrideValue.disabled(),
                                RuleSource.HARD_SAFETY,
                                ResolutionExplanation.HARD_SENSITIVE_FIELD)));
        assertThrows(
                IllegalArgumentException.class,
                () -> EffectiveProfile.resolved(
                        OverrideValue.inherit(),
                        RuleSource.GLOBAL,
                        ResolutionExplanation.EXPLICIT_VALUE));
    }

    @Test
    public void allResolverModelsArePureNonSerializableAndDiagnosticsAreRedacted() {
        for (Class<?> type : List.of(
                EffectiveProfile.class,
                ResolvedValue.class,
                Request.class,
                ProviderDefaults.class,
                RuleSource.class,
                ResolutionExplanation.class,
                ResolutionFailure.class)) {
            assertTrue(Modifier.isFinal(type.getModifiers()));
            if (!type.isEnum()) {
                assertFalse(Serializable.class.isAssignableFrom(type));
            }
            assertFalse(Arrays.stream(type.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .anyMatch(field -> field.getType().getName().startsWith("android.")));
        }

        String packageSecret = "com.private.customer";
        String routeSecret = "route.private.customer";
        String actionSecret = "actions.private.customer";
        Request request = request(
                global(
                        OverrideValue.value(routeSecret),
                        OverrideValue.value(ProcessingMode.SMART),
                        OverrideValue.value(true),
                        OverrideValue.value(true),
                        OverrideValue.value(actionSecret)),
                providerDefaults(),
                List.of(appRule(packageSecret, OverrideValue.value(routeSecret))),
                List.of(),
                inherited(),
                packageSecret,
                FieldKind.GENERAL);
        EffectiveProfile profile = EffectiveProfileResolver.resolve(request);
        String diagnostic = request + " | " + request.providerDefaults() + " | " + profile
                + " | " + profile.voiceRouteId() + " | " + profile.actionSetId();
        for (String secret : List.of(packageSecret, routeSecret, actionSecret, "private.customer")) {
            assertFalse(diagnostic, diagnostic.contains(secret));
        }
        assertTrue(diagnostic.contains("<redacted>"));
    }

    private static EffectiveProfile resolve(
            List<AppRule> appRules,
            List<FieldRule> fieldRules,
            RuleOverrides session,
            String packageName,
            FieldKind fieldKind) {
        return EffectiveProfileResolver.resolve(request(
                globalConfig(),
                providerDefaults(),
                appRules,
                fieldRules,
                session,
                packageName,
                fieldKind));
    }

    private static Request request(
            GlobalConfig global,
            ProviderDefaults defaults,
            List<AppRule> appRules,
            List<FieldRule> fieldRules,
            RuleOverrides session,
            String packageName,
            FieldKind fieldKind) {
        return new Request(
                global,
                defaults,
                appRules,
                fieldRules,
                session,
                packageName,
                fieldKind);
    }

    private static GlobalConfig globalConfig() {
        return global(
                OverrideValue.value("route.global"),
                OverrideValue.value(ProcessingMode.SMART),
                OverrideValue.value(true),
                OverrideValue.value(true),
                OverrideValue.value("actions.global"));
    }

    private static GlobalConfig global(
            OverrideValue<String> voiceRoute,
            OverrideValue<ProcessingMode> processing,
            OverrideValue<Boolean> sendContext,
            OverrideValue<Boolean> history,
            OverrideValue<String> actionSet) {
        return new GlobalConfig(
                GlobalConfig.FORMAT_VERSION,
                new GlobalConfig.KeyboardConfig("latin.base"),
                new GlobalConfig.VoiceConfig(voiceRoute),
                new GlobalConfig.ProcessingConfig(processing),
                new GlobalConfig.PrivacyConfig(sendContext, history),
                new GlobalConfig.AutomationConfig(actionSet));
    }

    private static ProviderDefaults providerDefaults() {
        return defaults(
                OverrideValue.value("route.provider"),
                OverrideValue.value(ProcessingMode.AUTO),
                OverrideValue.value(false),
                OverrideValue.value(false),
                OverrideValue.disabled());
    }

    private static ProviderDefaults defaults(
            OverrideValue<String> route,
            OverrideValue<ProcessingMode> processing,
            OverrideValue<Boolean> sendContext,
            OverrideValue<Boolean> history,
            OverrideValue<String> actionSet) {
        return new ProviderDefaults(route, processing, sendContext, history, actionSet);
    }

    private static AppRule appRule(String packageName, OverrideValue<String> voiceRoute) {
        return appRule(
                packageName,
                voiceRoute,
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit());
    }

    private static AppRule appRule(
            String packageName,
            OverrideValue<String> voiceRoute,
            OverrideValue<ProcessingMode> processing,
            OverrideValue<Boolean> sendContext,
            OverrideValue<Boolean> history,
            OverrideValue<String> actionSet) {
        return new AppRule(
                packageName,
                voiceRoute,
                processing,
                sendContext,
                history,
                actionSet);
    }

    private static FieldRule fieldRule(
            String packageName,
            FieldKind fieldKind,
            RuleOverrides overrides) {
        return new FieldRule(new FieldRule.FieldMatcher(packageName, fieldKind), overrides);
    }

    private static RuleOverrides inherited() {
        return overrides(OverrideValue.inherit());
    }

    private static RuleOverrides overrides(OverrideValue<String> voiceRoute) {
        return overrides(
                voiceRoute,
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit());
    }

    private static RuleOverrides overrides(
            OverrideValue<String> voiceRoute,
            OverrideValue<ProcessingMode> processing,
            OverrideValue<Boolean> sendContext,
            OverrideValue<Boolean> history,
            OverrideValue<String> actionSet) {
        return new RuleOverrides(voiceRoute, processing, sendContext, history, actionSet);
    }

    private static List<AppRule> generatedAppRules() {
        return new AbstractList<>() {
            @Override
            public AppRule get(int index) {
                throw new AssertionError("get must not be used");
            }

            @Override
            public int size() {
                return 0;
            }

            @Override
            public Iterator<AppRule> iterator() {
                return new Iterator<>() {
                    private int index;

                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public AppRule next() {
                        return appRule(
                                "com.generated.app" + index++,
                                OverrideValue.inherit());
                    }
                };
            }
        };
    }

    private static List<FieldRule> generatedFieldRules() {
        return new AbstractList<>() {
            @Override
            public FieldRule get(int index) {
                throw new AssertionError("get must not be used");
            }

            @Override
            public int size() {
                return 0;
            }

            @Override
            public Iterator<FieldRule> iterator() {
                return new Iterator<>() {
                    private int index;

                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public FieldRule next() {
                        return fieldRule(
                                "com.generated.field" + index++,
                                FieldKind.GENERAL,
                                inherited());
                    }
                };
            }
        };
    }

    private static <T> void assertResolved(
            ResolvedValue<T> resolved,
            OverrideValue<T> expectedValue,
            RuleSource expectedSource,
            ResolutionExplanation expectedExplanation) {
        assertEquals(expectedValue, resolved.value());
        assertEquals(expectedSource, resolved.source());
        assertEquals(expectedExplanation, resolved.explanation());
    }
}
