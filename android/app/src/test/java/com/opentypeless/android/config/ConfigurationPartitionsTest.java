package com.opentypeless.android.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.context.FieldKind;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.List;
import org.junit.Test;

public final class ConfigurationPartitionsTest {
    @Test
    public void exactRecordAndEnumShapesFreezeTheVersionedSchema() {
        assertArrayEquals(
                new ProcessingMode[] {
                    ProcessingMode.AUTO,
                    ProcessingMode.EXACT,
                    ProcessingMode.SMART,
                    ProcessingMode.TRANSLATE
                },
                ProcessingMode.values());

        assertComponents(
                GlobalConfig.class,
                "formatVersion:int",
                "keyboard:com.opentypeless.android.config.GlobalConfig$KeyboardConfig",
                "voice:com.opentypeless.android.config.GlobalConfig$VoiceConfig",
                "processing:com.opentypeless.android.config.GlobalConfig$ProcessingConfig",
                "privacy:com.opentypeless.android.config.GlobalConfig$PrivacyConfig",
                "automation:com.opentypeless.android.config.GlobalConfig$AutomationConfig");
        assertComponents(GlobalConfig.KeyboardConfig.class, "layoutId:java.lang.String");
        assertComponents(
                GlobalConfig.VoiceConfig.class,
                "routeId:com.opentypeless.android.config.OverrideValue<java.lang.String>");
        assertComponents(
                GlobalConfig.ProcessingConfig.class,
                "mode:com.opentypeless.android.config.OverrideValue<com.opentypeless.android.config.ProcessingMode>");
        assertComponents(
                GlobalConfig.PrivacyConfig.class,
                "sendContext:com.opentypeless.android.config.OverrideValue<java.lang.Boolean>",
                "historyEnabled:com.opentypeless.android.config.OverrideValue<java.lang.Boolean>");
        assertComponents(
                GlobalConfig.AutomationConfig.class,
                "actionSetId:com.opentypeless.android.config.OverrideValue<java.lang.String>");
        assertComponents(
                AppRule.class,
                "packageName:java.lang.String",
                "voiceRouteId:com.opentypeless.android.config.OverrideValue<java.lang.String>",
                "processingMode:com.opentypeless.android.config.OverrideValue<com.opentypeless.android.config.ProcessingMode>",
                "sendContext:com.opentypeless.android.config.OverrideValue<java.lang.Boolean>",
                "historyEnabled:com.opentypeless.android.config.OverrideValue<java.lang.Boolean>",
                "actionSetId:com.opentypeless.android.config.OverrideValue<java.lang.String>");
        assertComponents(
                RuleOverrides.class,
                "voiceRouteId:com.opentypeless.android.config.OverrideValue<java.lang.String>",
                "processingMode:com.opentypeless.android.config.OverrideValue<com.opentypeless.android.config.ProcessingMode>",
                "sendContext:com.opentypeless.android.config.OverrideValue<java.lang.Boolean>",
                "historyEnabled:com.opentypeless.android.config.OverrideValue<java.lang.Boolean>",
                "actionSetId:com.opentypeless.android.config.OverrideValue<java.lang.String>");
        assertComponents(
                FieldRule.class,
                "matcher:com.opentypeless.android.config.FieldRule$FieldMatcher",
                "overrides:com.opentypeless.android.config.RuleOverrides");
        assertComponents(
                FieldRule.FieldMatcher.class,
                "packageName:java.lang.String",
                "fieldKind:com.opentypeless.android.context.FieldKind");

        assertEquals(
                List.of(
                        GlobalConfig.AutomationConfig.class,
                        GlobalConfig.KeyboardConfig.class,
                        GlobalConfig.PrivacyConfig.class,
                        GlobalConfig.ProcessingConfig.class,
                        GlobalConfig.VoiceConfig.class),
                List.of(GlobalConfig.class.getNestMembers()).stream()
                        .filter(type -> type != GlobalConfig.class)
                        .sorted((left, right) -> left.getName().compareTo(right.getName()))
                        .toList());
        assertEquals(
                List.of(FieldRule.FieldMatcher.class),
                List.of(FieldRule.class.getNestMembers()).stream()
                        .filter(type -> type != FieldRule.class)
                        .toList());
    }

    @Test
    public void allModelsAreFinalImmutableNonSerializableValues() {
        for (Class<?> type : modelTypes()) {
            assertTrue(type.isRecord() || type.isEnum());
            assertTrue(Modifier.isFinal(type.getModifiers()));
            if (type.isRecord()) {
                assertFalse(Serializable.class.isAssignableFrom(type));
                for (var field : type.getDeclaredFields()) {
                    if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                        assertTrue(Modifier.isPrivate(field.getModifiers()));
                        assertTrue(Modifier.isFinal(field.getModifiers()));
                    }
                }
            } else {
                assertEquals(List.of(), List.of(type.getInterfaces()));
            }
        }
        for (Constructor<?> constructor : GlobalConfig.class.getDeclaredConstructors()) {
            assertFalse(constructor.isVarArgs());
        }
    }

    @Test
    public void globalVersionAndPartitionsAreExactAndNonNull() {
        GlobalConfig global = globalConfig();
        assertEquals(GlobalConfig.FORMAT_VERSION, global.formatVersion());
        assertEquals("latin", global.keyboard().layoutId());
        assertEquals(OverrideValue.value("local.voice"), global.voice().routeId());
        assertEquals(OverrideValue.value(ProcessingMode.EXACT), global.processing().mode());
        assertEquals(OverrideValue.value(false), global.privacy().sendContext());
        assertEquals(OverrideValue.disabled(), global.privacy().historyEnabled());
        assertEquals(OverrideValue.inherit(), global.automation().actionSetId());

        assertThrows(IllegalArgumentException.class, () -> globalConfig(0));
        assertThrows(IllegalArgumentException.class, () -> globalConfig(2));
        assertThrows(NullPointerException.class, () -> new GlobalConfig(
                1,
                null,
                global.voice(),
                global.processing(),
                global.privacy(),
                global.automation()));
    }

    @Test
    public void appAndFieldRulesPreserveEveryThreeStateIncludingFalse() {
        AppRule app = new AppRule(
                "com.example.editor",
                OverrideValue.inherit(),
                OverrideValue.disabled(),
                OverrideValue.value(false),
                OverrideValue.value(true),
                OverrideValue.value("knowledge.actions"));
        assertSame(OverrideValue.inherit(), app.voiceRouteId());
        assertSame(OverrideValue.disabled(), app.processingMode());
        assertEquals(OverrideValue.value(false), app.sendContext());
        assertEquals(OverrideValue.value(true), app.historyEnabled());

        RuleOverrides overrides = new RuleOverrides(
                OverrideValue.disabled(),
                OverrideValue.value(ProcessingMode.SMART),
                OverrideValue.value(false),
                OverrideValue.inherit(),
                OverrideValue.disabled());
        FieldRule field = new FieldRule(
                new FieldRule.FieldMatcher("com.example.editor", FieldKind.SEARCH),
                overrides);
        assertSame(overrides, field.overrides());
        assertEquals(FieldKind.SEARCH, field.matcher().fieldKind());
        assertNotEquals(app, field);
    }

    @Test
    public void identifiersAreBoundedLowerAsciiAndNeverUseEmptyAsAState() {
        String maximum = "a" + "b".repeat(127);
        assertEquals(maximum, new GlobalConfig.KeyboardConfig(maximum).layoutId());
        assertEquals(
                OverrideValue.value(maximum),
                new GlobalConfig.VoiceConfig(OverrideValue.value(maximum)).routeId());

        for (String invalid : List.of(
                "",
                "1route",
                "Route",
                " route",
                "route ",
                "route/one",
                "route:one",
                "路由",
                "a".repeat(129))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GlobalConfig.KeyboardConfig(invalid));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GlobalConfig.VoiceConfig(OverrideValue.value(invalid)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GlobalConfig.AutomationConfig(OverrideValue.value(invalid)));
        }
        assertSame(
                OverrideValue.inherit(),
                new GlobalConfig.VoiceConfig(OverrideValue.inherit()).routeId());
        assertSame(
                OverrideValue.disabled(),
                new GlobalConfig.AutomationConfig(OverrideValue.disabled()).actionSetId());
    }

    @Test
    public void packageNamesAreExactBoundedAndNotTrimmedOrWildcarded() {
        String maximum = "a." + "b".repeat(253);
        assertEquals(
                maximum,
                new FieldRule.FieldMatcher(maximum, FieldKind.GENERAL).packageName());
        for (String invalid : List.of(
                "",
                "single",
                ".com.example",
                "com..example",
                "com.example.",
                " com.example",
                "com.example ",
                "com.example-*",
                "com.example/field",
                "a." + "b".repeat(254))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new FieldRule.FieldMatcher(invalid, FieldKind.GENERAL));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> appRule(invalid));
        }
        assertThrows(
                NullPointerException.class,
                () -> new FieldRule.FieldMatcher("com.example", null));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void erasedGenericPayloadsCannotForgeTypedRuleValues() {
        OverrideValue forgedNumber = OverrideValue.value(42);
        OverrideValue forgedText = OverrideValue.value("false");
        assertThrows(IllegalArgumentException.class, () -> new RuleOverrides(
                forgedNumber,
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit()));
        assertThrows(IllegalArgumentException.class, () -> new RuleOverrides(
                OverrideValue.inherit(),
                forgedText,
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit()));
        assertThrows(IllegalArgumentException.class, () -> new RuleOverrides(
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                forgedText,
                OverrideValue.inherit(),
                OverrideValue.inherit()));
        assertThrows(IllegalArgumentException.class, () -> new AppRule(
                "com.example",
                forgedNumber,
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit()));
    }

    @Test
    public void nullRulesAndOverridesFailAtConstruction() {
        RuleOverrides inherited = inheritedOverrides();
        assertThrows(NullPointerException.class, () -> new RuleOverrides(
                null,
                inherited.processingMode(),
                inherited.sendContext(),
                inherited.historyEnabled(),
                inherited.actionSetId()));
        assertThrows(NullPointerException.class, () -> new FieldRule(null, inherited));
        assertThrows(NullPointerException.class, () -> new FieldRule(
                new FieldRule.FieldMatcher("com.example", FieldKind.GENERAL),
                null));
        assertThrows(NullPointerException.class, () -> new GlobalConfig.PrivacyConfig(
                null,
                OverrideValue.inherit()));
    }

    @Test
    public void diagnosticsRedactEveryIdentityAndExplicitPayload() {
        String packageSecret = "com.private.customer";
        String layoutSecret = "private.layout";
        String routeSecret = "private.route";
        String actionSecret = "private.actions";
        GlobalConfig global = new GlobalConfig(
                1,
                new GlobalConfig.KeyboardConfig(layoutSecret),
                new GlobalConfig.VoiceConfig(OverrideValue.value(routeSecret)),
                new GlobalConfig.ProcessingConfig(OverrideValue.value(ProcessingMode.SMART)),
                new GlobalConfig.PrivacyConfig(
                        OverrideValue.value(false),
                        OverrideValue.value(true)),
                new GlobalConfig.AutomationConfig(OverrideValue.value(actionSecret)));
        AppRule app = new AppRule(
                packageSecret,
                OverrideValue.value(routeSecret),
                OverrideValue.value(ProcessingMode.SMART),
                OverrideValue.value(false),
                OverrideValue.value(true),
                OverrideValue.value(actionSecret));
        FieldRule field = new FieldRule(
                new FieldRule.FieldMatcher(packageSecret, FieldKind.LONG_TEXT),
                new RuleOverrides(
                        OverrideValue.value(routeSecret),
                        OverrideValue.value(ProcessingMode.SMART),
                        OverrideValue.value(false),
                        OverrideValue.value(true),
                        OverrideValue.value(actionSecret)));

        String diagnostic = global + " | " + global.keyboard() + " | " + global.voice()
                + " | " + global.processing() + " | " + global.privacy() + " | "
                + global.automation() + " | " + app + " | " + field + " | "
                + field.matcher() + " | " + field.overrides();
        for (String secret : List.of(
                packageSecret,
                layoutSecret,
                routeSecret,
                actionSecret,
                "private")) {
            assertFalse(diagnostic, diagnostic.contains(secret));
        }
        assertTrue(diagnostic.contains("<redacted>"));
        assertTrue(diagnostic.contains("LONG_TEXT"));
    }

    private static GlobalConfig globalConfig() {
        return globalConfig(GlobalConfig.FORMAT_VERSION);
    }

    private static GlobalConfig globalConfig(int version) {
        return new GlobalConfig(
                version,
                new GlobalConfig.KeyboardConfig("latin"),
                new GlobalConfig.VoiceConfig(OverrideValue.value("local.voice")),
                new GlobalConfig.ProcessingConfig(OverrideValue.value(ProcessingMode.EXACT)),
                new GlobalConfig.PrivacyConfig(
                        OverrideValue.value(false),
                        OverrideValue.disabled()),
                new GlobalConfig.AutomationConfig(OverrideValue.inherit()));
    }

    private static AppRule appRule(String packageName) {
        RuleOverrides inherited = inheritedOverrides();
        return new AppRule(
                packageName,
                inherited.voiceRouteId(),
                inherited.processingMode(),
                inherited.sendContext(),
                inherited.historyEnabled(),
                inherited.actionSetId());
    }

    private static RuleOverrides inheritedOverrides() {
        return new RuleOverrides(
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit());
    }

    private static List<Class<?>> modelTypes() {
        return List.of(
                ProcessingMode.class,
                GlobalConfig.class,
                GlobalConfig.KeyboardConfig.class,
                GlobalConfig.VoiceConfig.class,
                GlobalConfig.ProcessingConfig.class,
                GlobalConfig.PrivacyConfig.class,
                GlobalConfig.AutomationConfig.class,
                AppRule.class,
                RuleOverrides.class,
                FieldRule.class,
                FieldRule.FieldMatcher.class);
    }

    private static void assertComponents(Class<?> type, String... expected) {
        assertTrue(type.isRecord());
        RecordComponent[] components = type.getRecordComponents();
        String[] observed = new String[components.length];
        for (int index = 0; index < components.length; index++) {
            Type genericType = components[index].getGenericType();
            observed[index] = components[index].getName() + ":" + genericType.getTypeName();
        }
        assertArrayEquals(type.getName(), expected, observed);
    }
}
