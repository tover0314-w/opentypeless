package com.opentypeless.android.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.EffectiveProfile.ResolutionExplanation;
import com.opentypeless.android.config.EffectiveProfile.RuleSource;
import com.opentypeless.android.config.RuleExplanationModel.DisplayValue;
import com.opentypeless.android.config.RuleExplanationModel.Feature;
import com.opentypeless.android.context.FieldKind;

import org.junit.Test;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

public final class RuleExplanationModelTest {
    @Test
    public void projectionUsesExactResolverValuesSourcesAndExplanations() {
        RuleOverrides session = overrides(
                OverrideValue.value("route.session"),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit());
        FieldRule field = new FieldRule(
                new FieldRule.FieldMatcher("com.example.app", FieldKind.SEARCH),
                overrides(
                        OverrideValue.inherit(),
                        OverrideValue.value(ProcessingMode.EXACT),
                        OverrideValue.inherit(),
                        OverrideValue.inherit(),
                        OverrideValue.inherit()));
        AppRule app = new AppRule(
                "com.example.app",
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.value(false),
                OverrideValue.inherit(),
                OverrideValue.inherit());
        EffectiveProfile resolved = EffectiveProfileResolver.resolve(request(
                FieldKind.SEARCH, session, List.of(app), List.of(field)));

        RuleExplanationModel model = RuleExplanationModel.from(resolved);

        assertEquals(List.of(Feature.values()), model.items().stream()
                .map(RuleExplanationModel.Item::feature).toList());
        assertItem(model, Feature.KEYBOARD_LAYOUT, RuleSource.GLOBAL,
                ResolutionExplanation.REQUIRED_GLOBAL_VALUE);
        assertItem(model, Feature.VOICE_ROUTE, RuleSource.SESSION,
                ResolutionExplanation.EXPLICIT_VALUE);
        assertItem(model, Feature.PROCESSING_MODE, RuleSource.FIELD,
                ResolutionExplanation.EXPLICIT_VALUE);
        assertItem(model, Feature.SEND_CONTEXT, RuleSource.APPLICATION,
                ResolutionExplanation.EXPLICIT_VALUE);
        assertItem(model, Feature.HISTORY, RuleSource.GLOBAL,
                ResolutionExplanation.EXPLICIT_VALUE);
        assertItem(model, Feature.ACTION_SET, RuleSource.GLOBAL,
                ResolutionExplanation.EXPLICIT_DISABLED);
        assertEquals("route.session", ((DisplayValue.Identifier)
                model.item(Feature.VOICE_ROUTE).value()).value());
        assertEquals(false, ((DisplayValue.BooleanValue)
                model.item(Feature.SEND_CONTEXT).value()).value());
    }

    @Test
    public void disabledAndExplicitFalseRemainDistinctTypedValues() {
        EffectiveProfile resolved = EffectiveProfileResolver.resolve(request(
                FieldKind.GENERAL,
                overrides(
                        OverrideValue.inherit(),
                        OverrideValue.inherit(),
                        OverrideValue.value(false),
                        OverrideValue.disabled(),
                        OverrideValue.inherit()),
                List.of(),
                List.of()));

        RuleExplanationModel model = RuleExplanationModel.from(resolved);

        assertTrue(model.item(Feature.SEND_CONTEXT).value()
                instanceof DisplayValue.BooleanValue);
        assertTrue(model.item(Feature.HISTORY).value() instanceof DisplayValue.Disabled);
        assertEquals(ResolutionExplanation.EXPLICIT_VALUE,
                model.item(Feature.SEND_CONTEXT).explanation());
        assertEquals(ResolutionExplanation.EXPLICIT_DISABLED,
                model.item(Feature.HISTORY).explanation());
    }

    @Test
    public void sensitiveFieldProjectsCompleteHardSafetyProfile() {
        RuleExplanationModel model = RuleExplanationModel.from(
                EffectiveProfileResolver.resolve(request(
                        FieldKind.SENSITIVE,
                        overrides(
                                OverrideValue.value("route.cloud"),
                                OverrideValue.value(ProcessingMode.SMART),
                                OverrideValue.value(true),
                                OverrideValue.value(true),
                                OverrideValue.value("action.send")),
                        List.of(),
                        List.of())));

        for (Feature feature : List.of(
                Feature.VOICE_ROUTE,
                Feature.PROCESSING_MODE,
                Feature.SEND_CONTEXT,
                Feature.HISTORY,
                Feature.ACTION_SET)) {
            assertItem(model, feature, RuleSource.HARD_SAFETY,
                    ResolutionExplanation.HARD_SENSITIVE_FIELD);
        }
        assertEquals(ProcessingMode.EXACT, ((DisplayValue.Processing)
                model.item(Feature.PROCESSING_MODE).value()).value());
        assertTrue(model.item(Feature.VOICE_ROUTE).value() instanceof DisplayValue.Disabled);
    }

    @Test
    public void precedenceIsExactImmutablePresentationVocabulary() {
        assertEquals(List.of(
                RuleSource.HARD_SAFETY,
                RuleSource.SESSION,
                RuleSource.FIELD,
                RuleSource.APPLICATION,
                RuleSource.GLOBAL,
                RuleSource.PROVIDER_DEFAULT), RuleExplanationModel.precedence());
        assertThrows(UnsupportedOperationException.class,
                () -> RuleExplanationModel.precedence().add(RuleSource.GLOBAL));
    }

    @Test
    public void projectionRejectsNullAndItemRejectsInconsistentClaims() {
        assertThrows(NullPointerException.class, () -> RuleExplanationModel.from(null));
        assertThrows(IllegalArgumentException.class, () -> new RuleExplanationModel.Item(
                Feature.SEND_CONTEXT,
                new DisplayValue.BooleanValue(true),
                RuleSource.HARD_SAFETY,
                ResolutionExplanation.EXPLICIT_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new RuleExplanationModel.Item(
                Feature.KEYBOARD_LAYOUT,
                new DisplayValue.Identifier("latin.base"),
                RuleSource.SESSION,
                ResolutionExplanation.EXPLICIT_VALUE));
    }

    @Test
    public void diagnosticsRedactEveryIdentifierAndValue() {
        RuleExplanationModel model = RuleExplanationModel.from(
                EffectiveProfileResolver.resolve(request(
                        FieldKind.GENERAL,
                        overrides(
                                OverrideValue.inherit(),
                                OverrideValue.inherit(),
                                OverrideValue.inherit(),
                                OverrideValue.inherit(),
                                OverrideValue.inherit()),
                        List.of(),
                        List.of())));
        String diagnostics = model + " " + model.items();
        assertTrue(diagnostics.contains("<redacted>"));
        for (String secret : List.of(
                "latin.base", "route.global", "action.global", "true")) {
            assertFalse(diagnostics.contains(secret));
        }
    }

    @Test
    public void modelIsPureFinalNonSerializableAndFactoryOnlyConsumesResolvedProfile() {
        assertTrue(Modifier.isFinal(RuleExplanationModel.class.getModifiers()));
        assertFalse(Serializable.class.isAssignableFrom(RuleExplanationModel.class));
        for (Constructor<?> constructor : RuleExplanationModel.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
        Method[] publicMethods = RuleExplanationModel.class.getDeclaredMethods();
        assertEquals(5, java.util.Arrays.stream(publicMethods)
                .filter(method -> Modifier.isPublic(method.getModifiers())).count());
        assertEquals(EffectiveProfile.class,
                assertDoesNotThrowMethod("from", EffectiveProfile.class).getParameterTypes()[0]);
        for (Class<?> nested : RuleExplanationModel.class.getDeclaredClasses()) {
            if (!nested.isEnum()) {
                assertFalse(Serializable.class.isAssignableFrom(nested));
            }
        }
    }

    private static Method assertDoesNotThrowMethod(String name, Class<?>... parameters) {
        try {
            return RuleExplanationModel.class.getDeclaredMethod(name, parameters);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void assertItem(
            RuleExplanationModel model,
            Feature feature,
            RuleSource source,
            ResolutionExplanation explanation) {
        RuleExplanationModel.Item item = model.item(feature);
        assertEquals(source, item.source());
        assertEquals(explanation, item.explanation());
    }

    private static EffectiveProfileResolver.Request request(
            FieldKind fieldKind,
            RuleOverrides session,
            List<AppRule> appRules,
            List<FieldRule> fieldRules) {
        return new EffectiveProfileResolver.Request(
                new GlobalConfig(
                        GlobalConfig.FORMAT_VERSION,
                        new GlobalConfig.KeyboardConfig("latin.base"),
                        new GlobalConfig.VoiceConfig(OverrideValue.value("route.global")),
                        new GlobalConfig.ProcessingConfig(OverrideValue.value(ProcessingMode.SMART)),
                        new GlobalConfig.PrivacyConfig(
                                OverrideValue.value(true), OverrideValue.value(true)),
                        new GlobalConfig.AutomationConfig(OverrideValue.disabled())),
                new EffectiveProfileResolver.ProviderDefaults(
                        OverrideValue.value("route.provider"),
                        OverrideValue.value(ProcessingMode.AUTO),
                        OverrideValue.value(false),
                        OverrideValue.value(false),
                        OverrideValue.disabled()),
                appRules,
                fieldRules,
                session,
                "com.example.app",
                fieldKind);
    }

    private static RuleOverrides overrides(
            OverrideValue<String> voice,
            OverrideValue<ProcessingMode> processing,
            OverrideValue<Boolean> context,
            OverrideValue<Boolean> history,
            OverrideValue<String> action) {
        return new RuleOverrides(voice, processing, context, history, action);
    }
}
