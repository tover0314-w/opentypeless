package com.opentypeless.android.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.AppRule;
import com.opentypeless.android.config.EffectiveProfile;
import com.opentypeless.android.config.EffectiveProfileResolver;
import com.opentypeless.android.config.GlobalConfig;
import com.opentypeless.android.config.OverrideValue;
import com.opentypeless.android.config.RuleOverrides;
import com.opentypeless.android.context.FieldKind;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LegacyAppProfileMigrationTest {
    private static final String PACKAGE = "com.example.editor";
    private static final String UNMAPPED_SENTINEL = "cfg007-unmapped-preference";

    @Test
    public void actualZeroTwoProfilesMigrateOnceWithExplicitFalseAndRetainedBackup() {
        String source = source(profile(
                PACKAGE,
                ProcessingMode.VERBATIM,
                "English",
                UNMAPPED_SENTINEL,
                false));
        FakeStore store = new FakeStore(Map.of(
                LegacyAppProfileMigration.LEGACY_PROFILES,
                source));

        List<AppRule> first = LegacyAppProfileMigration.migrate(store);
        Map<String, Object> afterFirst = Map.copyOf(store.values);
        List<AppRule> second = LegacyAppProfileMigration.migrate(store);

        assertEquals(first, second);
        assertEquals(1, store.commitCount);
        assertEquals(afterFirst, store.values);
        assertEquals(1, first.size());
        AppRule rule = first.get(0);
        assertEquals(PACKAGE, rule.packageName());
        assertEquals(OverrideValue.inherit(), rule.voiceRouteId());
        assertEquals(
                OverrideValue.value(com.opentypeless.android.config.ProcessingMode.EXACT),
                rule.processingMode());
        assertEquals(OverrideValue.value(false), rule.sendContext());
        assertEquals(OverrideValue.inherit(), rule.historyEnabled());
        assertEquals(OverrideValue.inherit(), rule.actionSetId());
        assertEquals(source, store.values.get(LegacyAppProfileMigration.LEGACY_PROFILES));
        assertTrue((Boolean) store.values.get(
                LegacyAppProfileMigration.KEY_BACKUP_RETAINED));
        assertFalse(targetValues(store.values).contains(UNMAPPED_SENTINEL));
    }

    @Test
    public void everyLegacyModeAndBooleanHasOneClosedThreeStateMapping() {
        Map<ProcessingMode, com.opentypeless.android.config.ProcessingMode> modes = Map.of(
                ProcessingMode.AUTO, com.opentypeless.android.config.ProcessingMode.AUTO,
                ProcessingMode.VERBATIM, com.opentypeless.android.config.ProcessingMode.EXACT,
                ProcessingMode.SMART, com.opentypeless.android.config.ProcessingMode.SMART,
                ProcessingMode.TRANSLATE,
                com.opentypeless.android.config.ProcessingMode.TRANSLATE);

        for (ProcessingMode mode : ProcessingMode.values()) {
            for (boolean sendContext : List.of(false, true)) {
                FakeStore store = new FakeStore(Map.of(
                        LegacyAppProfileMigration.LEGACY_PROFILES,
                        source(profile(PACKAGE, mode, "", "", sendContext))));
                AppRule migrated = LegacyAppProfileMigration.migrate(store).get(0);
                assertEquals(
                        OverrideValue.value(modes.get(mode)),
                        migrated.processingMode());
                assertEquals(
                        OverrideValue.value(sendContext),
                        migrated.sendContext());
            }
        }
    }

    @Test
    public void missingLegacyLeavesUseActualZeroTwoDefaultsWithoutCreatingSentinels() {
        JSONArray rows = new JSONArray().put(jsonObject("packageName", PACKAGE));
        FakeStore store = new FakeStore(Map.of(
                LegacyAppProfileMigration.LEGACY_PROFILES,
                rows.toString()));

        AppRule migrated = LegacyAppProfileMigration.migrate(store).get(0);

        assertEquals(
                OverrideValue.value(com.opentypeless.android.config.ProcessingMode.AUTO),
                migrated.processingMode());
        assertEquals(OverrideValue.value(false), migrated.sendContext());
        assertEquals(OverrideValue.inherit(), migrated.voiceRouteId());
        assertEquals(OverrideValue.inherit(), migrated.historyEnabled());
        assertEquals(OverrideValue.inherit(), migrated.actionSetId());
    }

    @Test
    public void representedEffectiveConfigurationMatchesTheLegacyProfileSnapshot() {
        AppSettings base = appSettings(ProcessingMode.SMART, true, true);
        AppProfile legacyProfile = new AppProfile(
                PACKAGE,
                ProcessingMode.VERBATIM,
                "French",
                UNMAPPED_SENTINEL,
                false);
        AppSettings oldEffective = AppProfileRepository.applyProfile(base, legacyProfile);
        FakeStore store = new FakeStore(Map.of(
                LegacyAppProfileMigration.LEGACY_PROFILES,
                source(profile(
                        legacyProfile.packageName(),
                        legacyProfile.mode(),
                        legacyProfile.targetLanguage(),
                        legacyProfile.customInstructions(),
                        legacyProfile.sendContext()))));

        EffectiveProfile migrated = resolve(
                global(
                        configMode(base.defaultMode()),
                        base.sendContext(),
                        base.historyEnabled()),
                LegacyAppProfileMigration.migrate(store));

        assertEquals(
                OverrideValue.value(configMode(oldEffective.defaultMode())),
                migrated.processingMode().value());
        assertEquals(
                OverrideValue.value(oldEffective.sendContext()),
                migrated.sendContext().value());
        assertEquals(
                OverrideValue.value(oldEffective.historyEnabled()),
                migrated.historyEnabled().value());
        assertEquals(EffectiveProfile.RuleSource.APPLICATION,
                migrated.processingMode().source());
        assertEquals(EffectiveProfile.RuleSource.APPLICATION,
                migrated.sendContext().source());
        assertEquals(EffectiveProfile.RuleSource.GLOBAL,
                migrated.historyEnabled().source());
    }

    @Test
    public void sourceChangesRefreshProjectionWithoutInventingARevision() {
        FakeStore store = new FakeStore(Map.of(
                LegacyAppProfileMigration.LEGACY_PROFILES,
                source(profile(PACKAGE, ProcessingMode.AUTO, "", "first", false))));
        LegacyAppProfileMigration.migrate(store);

        store.values.put(
                LegacyAppProfileMigration.LEGACY_PROFILES,
                source(profile(PACKAGE, ProcessingMode.TRANSLATE, "", "second", true)));
        AppRule refreshed = LegacyAppProfileMigration.migrate(store).get(0);
        assertEquals(2, store.commitCount);
        assertEquals(
                OverrideValue.value(com.opentypeless.android.config.ProcessingMode.TRANSLATE),
                refreshed.processingMode());
        assertEquals(OverrideValue.value(true), refreshed.sendContext());

        store.values.put(
                LegacyAppProfileMigration.LEGACY_PROFILES,
                source(profile(PACKAGE, ProcessingMode.TRANSLATE, "German", "third", true)));
        List<AppRule> unchanged = LegacyAppProfileMigration.migrate(store);
        assertEquals(2, store.commitCount);
        assertEquals(List.of(refreshed), unchanged);
        assertEquals("third", decodeSource((String) store.values.get(
                LegacyAppProfileMigration.LEGACY_PROFILES)).get(0).customInstructions());
    }

    @Test
    public void malformedDuplicateOrOversizedSourcesFailBeforeAnyTargetWrite() {
        List<String> malformed = List.of(
                "not-json",
                new JSONArray().put(7).toString(),
                new JSONArray().put(jsonObject("packageName", 7)).toString(),
                new JSONArray().put(jsonObject(
                        "packageName", PACKAGE,
                        "mode", "UNKNOWN")).toString(),
                new JSONArray().put(jsonObject(
                        "packageName", PACKAGE,
                        "sendContext", "false")).toString());
        for (String source : malformed) {
            FakeStore store = new FakeStore(Map.of(
                    LegacyAppProfileMigration.LEGACY_PROFILES,
                    source));
            assertMigrationFailure(
                    LegacyAppProfileMigration.MigrationFailure.MALFORMED_SOURCE,
                    store);
            assertEquals(0, store.commitCount);
            assertTrue(targetValues(store.values).isEmpty());
        }

        FakeStore duplicate = new FakeStore(Map.of(
                LegacyAppProfileMigration.LEGACY_PROFILES,
                source(
                        profile(PACKAGE, ProcessingMode.AUTO, "", "", false),
                        profile(PACKAGE, ProcessingMode.SMART, "", "", true))));
        assertMigrationFailure(
                LegacyAppProfileMigration.MigrationFailure.DUPLICATE_SOURCE,
                duplicate);

        JSONArray tooMany = new JSONArray();
        for (int index = 0; index <= LegacyAppProfileMigration.MAX_PROFILES; index++) {
            tooMany.put(profile(
                    "com.example.app" + index,
                    ProcessingMode.AUTO,
                    "",
                    "",
                    false));
        }
        FakeStore oversized = new FakeStore(Map.of(
                LegacyAppProfileMigration.LEGACY_PROFILES,
                tooMany.toString()));
        assertMigrationFailure(
                LegacyAppProfileMigration.MigrationFailure.SOURCE_LIMIT_EXCEEDED,
                oversized);
        assertEquals(0, duplicate.commitCount);
        assertEquals(0, oversized.commitCount);
    }

    @Test
    public void oneHundredProfilesAreAcceptedSortedAndImmutable() {
        JSONArray source = new JSONArray();
        for (int index = LegacyAppProfileMigration.MAX_PROFILES - 1; index >= 0; index--) {
            source.put(profile(
                    String.format("com.maximum.app%03d", index),
                    ProcessingMode.AUTO,
                    "",
                    "",
                    false));
        }
        FakeStore store = new FakeStore(Map.of(
                LegacyAppProfileMigration.LEGACY_PROFILES,
                source.toString()));

        List<AppRule> rules = LegacyAppProfileMigration.migrate(store);

        assertEquals(LegacyAppProfileMigration.MAX_PROFILES, rules.size());
        assertEquals("com.maximum.app000", rules.get(0).packageName());
        assertEquals("com.maximum.app099", rules.get(99).packageName());
        assertThrows(UnsupportedOperationException.class, rules::clear);
    }

    @Test
    public void unknownPartialOrCorruptTargetFailsClosedWithoutRepair() {
        FakeStore unknown = new FakeStore(Map.of(
                LegacyAppProfileMigration.KEY_MIGRATION_VERSION,
                99));
        assertMigrationFailure(
                LegacyAppProfileMigration.MigrationFailure.UNKNOWN_TARGET_VERSION,
                unknown);

        FakeStore partial = new FakeStore(Map.of(
                LegacyAppProfileMigration.KEY_SOURCE_VERSION,
                LegacyAppProfileMigration.SOURCE_VERSION));
        assertMigrationFailure(
                LegacyAppProfileMigration.MigrationFailure.PARTIAL_TARGET,
                partial);

        FakeStore corrupt = new FakeStore(Map.of());
        LegacyAppProfileMigration.migrate(corrupt);
        corrupt.values.put(LegacyAppProfileMigration.KEY_RULES, "[[\"bad\"]]");
        int commits = corrupt.commitCount;
        assertMigrationFailure(
                LegacyAppProfileMigration.MigrationFailure.PARTIAL_TARGET,
                corrupt);
        assertEquals(0, unknown.commitCount);
        assertEquals(0, partial.commitCount);
        assertEquals(commits, corrupt.commitCount);
    }

    @Test
    public void sourceAndProjectionWriteUseOneCommitAndFailClosedOnCommitOrReadback() {
        FakeStore success = new FakeStore(Map.of());
        List<AppProfile> profiles = List.of(new AppProfile(
                PACKAGE,
                ProcessingMode.SMART,
                "Spanish",
                UNMAPPED_SENTINEL,
                false));

        LegacyAppProfileMigration.writeProfiles(success, profiles);

        assertEquals(1, success.commitCount);
        assertEquals(
                profiles,
                decodeSource((String) success.values.get(
                        LegacyAppProfileMigration.LEGACY_PROFILES)));
        assertEquals(
                OverrideValue.value(false),
                LegacyAppProfileMigration.migrate(success).get(0).sendContext());
        assertEquals(1, success.commitCount);
        assertFalse(targetValues(success.values).contains(UNMAPPED_SENTINEL));

        FakeStore commitFailure = new FakeStore(Map.of());
        Map<String, Object> before = Map.copyOf(commitFailure.values);
        commitFailure.failCommit = true;
        assertMigrationFailure(
                LegacyAppProfileMigration.MigrationFailure.COMMIT_FAILED,
                commitFailure,
                () -> LegacyAppProfileMigration.writeProfiles(commitFailure, profiles));
        assertEquals(before, commitFailure.values);

        FakeStore readbackFailure = new FakeStore(Map.of());
        readbackFailure.corruptSourceAfterCommit = true;
        LegacyAppProfileMigration.MigrationException error = assertThrows(
                LegacyAppProfileMigration.MigrationException.class,
                () -> LegacyAppProfileMigration.writeProfiles(readbackFailure, profiles));
        assertEquals(
                LegacyAppProfileMigration.MigrationFailure.READBACK_FAILED,
                error.failure());
        assertFalse(error.toString().contains(UNMAPPED_SENTINEL));
        assertEquals(null, error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }

    private static void assertMigrationFailure(
            LegacyAppProfileMigration.MigrationFailure expected,
            FakeStore store) {
        assertMigrationFailure(expected, store, () -> LegacyAppProfileMigration.migrate(store));
    }

    private static void assertMigrationFailure(
            LegacyAppProfileMigration.MigrationFailure expected,
            FakeStore store,
            Runnable action) {
        LegacyAppProfileMigration.MigrationException error = assertThrows(
                LegacyAppProfileMigration.MigrationException.class,
                action::run);
        assertEquals(expected, error.failure());
        assertFalse(error.toString().contains(PACKAGE));
        assertFalse(error.toString().contains(UNMAPPED_SENTINEL));
        assertEquals(null, error.getCause());
    }

    private static JSONObject profile(
            String packageName,
            ProcessingMode mode,
            String targetLanguage,
            String customInstructions,
            boolean sendContext) {
        return jsonObject(
                "packageName", packageName,
                "mode", mode.name(),
                "targetLanguage", targetLanguage,
                "customInstructions", customInstructions,
                "sendContext", sendContext);
    }

    private static JSONObject jsonObject(Object... fields) {
        try {
            JSONObject object = new JSONObject();
            for (int index = 0; index < fields.length; index += 2) {
                object.put((String) fields[index], fields[index + 1]);
            }
            return object;
        } catch (Exception error) {
            throw new AssertionError("test fixture creation failed", error);
        }
    }

    private static String source(JSONObject... profiles) {
        JSONArray array = new JSONArray();
        for (JSONObject profile : profiles) array.put(profile);
        return array.toString();
    }

    private static List<AppProfile> decodeSource(String encoded) {
        try {
            JSONArray array = new JSONArray(encoded);
            java.util.ArrayList<AppProfile> profiles = new java.util.ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject profile = array.getJSONObject(index);
                profiles.add(new AppProfile(
                        profile.getString("packageName"),
                        ProcessingMode.valueOf(profile.getString("mode")),
                        profile.getString("targetLanguage"),
                        profile.getString("customInstructions"),
                        profile.getBoolean("sendContext")));
            }
            return List.copyOf(profiles);
        } catch (Exception error) {
            throw new AssertionError("test fixture decode failed", error);
        }
    }

    private static String targetValues(Map<String, Object> values) {
        StringBuilder joined = new StringBuilder();
        values.forEach((key, value) -> {
            if (key.startsWith("app_rules_v1_")) joined.append(value);
        });
        return joined.toString();
    }

    private static EffectiveProfile resolve(GlobalConfig global, List<AppRule> appRules) {
        return EffectiveProfileResolver.resolve(new EffectiveProfileResolver.Request(
                global,
                new EffectiveProfileResolver.ProviderDefaults(
                        OverrideValue.value("route.provider"),
                        OverrideValue.value(com.opentypeless.android.config.ProcessingMode.AUTO),
                        OverrideValue.value(false),
                        OverrideValue.value(false),
                        OverrideValue.disabled()),
                appRules,
                List.of(),
                new RuleOverrides(
                        OverrideValue.inherit(),
                        OverrideValue.inherit(),
                        OverrideValue.inherit(),
                        OverrideValue.inherit(),
                        OverrideValue.inherit()),
                PACKAGE,
                FieldKind.GENERAL));
    }

    private static GlobalConfig global(
            com.opentypeless.android.config.ProcessingMode mode,
            boolean sendContext,
            boolean history) {
        return new GlobalConfig(
                GlobalConfig.FORMAT_VERSION,
                new GlobalConfig.KeyboardConfig("latin.base"),
                new GlobalConfig.VoiceConfig(OverrideValue.value("route.global")),
                new GlobalConfig.ProcessingConfig(OverrideValue.value(mode)),
                new GlobalConfig.PrivacyConfig(
                        OverrideValue.value(sendContext),
                        OverrideValue.value(history)),
                new GlobalConfig.AutomationConfig(OverrideValue.disabled()));
    }

    private static com.opentypeless.android.config.ProcessingMode configMode(
            ProcessingMode mode) {
        return switch (mode) {
            case AUTO -> com.opentypeless.android.config.ProcessingMode.AUTO;
            case VERBATIM -> com.opentypeless.android.config.ProcessingMode.EXACT;
            case SMART -> com.opentypeless.android.config.ProcessingMode.SMART;
            case TRANSLATE -> com.opentypeless.android.config.ProcessingMode.TRANSLATE;
        };
    }

    private static AppSettings appSettings(
            ProcessingMode mode,
            boolean sendContext,
            boolean historyEnabled) {
        return new AppSettings(
                RecognitionBackend.SYSTEM_DEFAULT,
                "https://speech.example/v1",
                "",
                "speech-model",
                "wss://stream.example/ws",
                "",
                "stream-model",
                "",
                "zh-CN",
                mode,
                true,
                "https://language.example/v1",
                "",
                "language-model",
                "English",
                "global preference",
                true,
                historyEnabled,
                sendContext,
                180);
    }

    private static final class FakeStore implements LegacyAppProfileMigration.Store {
        private final Map<String, Object> values;
        private int commitCount;
        private boolean failCommit;
        private boolean corruptSourceAfterCommit;

        private FakeStore(Map<String, ?> initial) {
            values = new LinkedHashMap<>(initial);
        }

        @Override
        public Map<String, ?> readAll() {
            return new LinkedHashMap<>(values);
        }

        @Override
        public boolean commit(Map<String, Object> updates) {
            commitCount++;
            if (failCommit) return false;
            values.putAll(updates);
            if (corruptSourceAfterCommit
                    && updates.containsKey(LegacyAppProfileMigration.LEGACY_PROFILES)) {
                values.put(LegacyAppProfileMigration.LEGACY_PROFILES, "not-json");
            }
            return true;
        }
    }
}
