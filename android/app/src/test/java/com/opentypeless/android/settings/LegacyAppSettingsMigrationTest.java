package com.opentypeless.android.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.GlobalConfig;
import com.opentypeless.android.config.OverrideValue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LegacyAppSettingsMigrationTest {
    private static final String SECRET = "cfg006-secret-sentinel";

    @Test
    public void actualZeroTwoFixtureMigratesOnceAndRetainsLegacyBackupWithoutSecrets() {
        FakeStore store = new FakeStore(zeroTwoFixture(
                RecognitionBackend.OPENAI_COMPATIBLE,
                ProcessingMode.VERBATIM,
                false,
                true,
                41L));
        store.values.put("stt_api_key", SECRET);
        store.values.put("custom_instructions", SECRET);

        GlobalConfig first = LegacyAppSettingsMigration.migrate(
                store,
                RecognitionBackend.SYSTEM_DEFAULT);
        Map<String, Object> afterFirst = Map.copyOf(store.values);
        GlobalConfig second = LegacyAppSettingsMigration.migrate(
                store,
                RecognitionBackend.LOCAL_OFFLINE);

        assertEquals(first, second);
        assertEquals(1, store.commitCount);
        assertEquals(afterFirst, store.values);
        assertEquals(GlobalConfig.FORMAT_VERSION, first.formatVersion());
        assertEquals("latin.base", first.keyboard().layoutId());
        assertEquals(
                OverrideValue.value("legacy.openai-compatible"),
                first.voice().routeId());
        assertEquals(
                OverrideValue.value(com.opentypeless.android.config.ProcessingMode.EXACT),
                first.processing().mode());
        assertEquals(OverrideValue.value(false), first.privacy().sendContext());
        assertEquals(OverrideValue.value(true), first.privacy().historyEnabled());
        assertEquals(OverrideValue.disabled(), first.automation().actionSetId());
        assertEquals(RecognitionBackend.OPENAI_COMPATIBLE.name(),
                store.values.get("recognition_backend"));
        assertEquals(SECRET, store.values.get("stt_api_key"));
        assertEquals(SECRET, store.values.get("custom_instructions"));
        assertTrue((Boolean) store.values.get(
                LegacyAppSettingsMigration.KEY_BACKUP_RETAINED));
        assertFalse(targetValues(store.values).contains(SECRET));
    }

    @Test
    public void everyLegacyBackendAndModeHasOneExactClosedMapping() {
        Map<RecognitionBackend, String> routes = Map.of(
                RecognitionBackend.OPENAI_COMPATIBLE, "legacy.openai-compatible",
                RecognitionBackend.LOCAL_OFFLINE, "legacy.local-offline",
                RecognitionBackend.DASHSCOPE_STREAMING, "legacy.dashscope-streaming",
                RecognitionBackend.SYSTEM_ON_DEVICE, "legacy.system-on-device",
                RecognitionBackend.SYSTEM_DEFAULT, "legacy.system-default");
        Map<ProcessingMode, com.opentypeless.android.config.ProcessingMode> modes = Map.of(
                ProcessingMode.AUTO, com.opentypeless.android.config.ProcessingMode.AUTO,
                ProcessingMode.VERBATIM, com.opentypeless.android.config.ProcessingMode.EXACT,
                ProcessingMode.SMART, com.opentypeless.android.config.ProcessingMode.SMART,
                ProcessingMode.TRANSLATE,
                com.opentypeless.android.config.ProcessingMode.TRANSLATE);

        long revision = 1L;
        for (RecognitionBackend backend : RecognitionBackend.values()) {
            for (ProcessingMode mode : ProcessingMode.values()) {
                FakeStore store = new FakeStore(zeroTwoFixture(
                        backend,
                        mode,
                        true,
                        false,
                        revision++));
                GlobalConfig migrated = LegacyAppSettingsMigration.migrate(
                        store,
                        RecognitionBackend.SYSTEM_DEFAULT);
                assertEquals(OverrideValue.value(routes.get(backend)), migrated.voice().routeId());
                assertEquals(OverrideValue.value(modes.get(mode)), migrated.processing().mode());
                assertEquals(OverrideValue.value(true), migrated.privacy().sendContext());
                assertEquals(OverrideValue.value(false), migrated.privacy().historyEnabled());
            }
        }
    }

    @Test
    public void cleanZeroTwoStoreUsesExplicitSafeDefaults() {
        FakeStore store = new FakeStore(Map.of());

        GlobalConfig migrated = LegacyAppSettingsMigration.migrate(
                store,
                RecognitionBackend.SYSTEM_ON_DEVICE);

        assertEquals(
                OverrideValue.value("legacy.system-on-device"),
                migrated.voice().routeId());
        assertEquals(
                OverrideValue.value(com.opentypeless.android.config.ProcessingMode.AUTO),
                migrated.processing().mode());
        assertEquals(OverrideValue.value(false), migrated.privacy().sendContext());
        assertEquals(OverrideValue.value(false), migrated.privacy().historyEnabled());
        assertEquals(0L, store.values.get(LegacyAppSettingsMigration.KEY_SOURCE_REVISION));
    }

    @Test
    public void newerLegacyRevisionAtomicallyRefreshesTheWholeProjection() {
        FakeStore store = new FakeStore(zeroTwoFixture(
                RecognitionBackend.OPENAI_COMPATIBLE,
                ProcessingMode.AUTO,
                false,
                false,
                7L));
        LegacyAppSettingsMigration.migrate(store, RecognitionBackend.SYSTEM_DEFAULT);

        store.values.put("recognition_backend", RecognitionBackend.LOCAL_OFFLINE.name());
        store.values.put("default_mode", ProcessingMode.TRANSLATE.name());
        store.values.put("send_context", true);
        store.values.put("history_enabled", true);
        store.values.put("settings_revision", 8L);
        GlobalConfig refreshed = LegacyAppSettingsMigration.migrate(
                store,
                RecognitionBackend.SYSTEM_DEFAULT);

        assertEquals(2, store.commitCount);
        assertEquals(8L, store.values.get(LegacyAppSettingsMigration.KEY_SOURCE_REVISION));
        assertEquals(OverrideValue.value("legacy.local-offline"), refreshed.voice().routeId());
        assertEquals(
                OverrideValue.value(com.opentypeless.android.config.ProcessingMode.TRANSLATE),
                refreshed.processing().mode());
        assertEquals(OverrideValue.value(true), refreshed.privacy().sendContext());
        assertEquals(OverrideValue.value(true), refreshed.privacy().historyEnabled());
    }

    @Test
    public void malformedSourceNeverWritesTargetKeys() {
        for (Map<String, Object> malformed : List.of(
                mutable(Map.of("recognition_backend", "UNKNOWN")),
                mutable(Map.of("recognition_backend", 3)),
                mutable(Map.of("default_mode", "UNKNOWN")),
                mutable(Map.of("send_context", "false")),
                mutable(Map.of("history_enabled", 1)),
                mutable(Map.of("settings_revision", -1L)),
                mutable(Map.of("settings_revision", 1)))) {
            FakeStore store = new FakeStore(malformed);
            LegacyAppSettingsMigration.MigrationException error = assertThrows(
                    LegacyAppSettingsMigration.MigrationException.class,
                    () -> LegacyAppSettingsMigration.migrate(
                            store,
                            RecognitionBackend.SYSTEM_DEFAULT));
            assertEquals(
                    LegacyAppSettingsMigration.MigrationFailure.MALFORMED_SOURCE,
                    error.failure());
            assertEquals(0, store.commitCount);
            assertTrue(store.values.keySet().stream()
                    .noneMatch(key -> key.startsWith("config_v1_")));
        }
    }

    @Test
    public void unknownOrPartialTargetFailsClosedWithoutRepairingIt() {
        FakeStore unknown = new FakeStore(Map.of(
                LegacyAppSettingsMigration.KEY_MIGRATION_VERSION, 99));
        assertMigrationFailure(
                LegacyAppSettingsMigration.MigrationFailure.UNKNOWN_TARGET_VERSION,
                unknown);

        FakeStore partial = new FakeStore(Map.of(
                LegacyAppSettingsMigration.KEY_SOURCE_VERSION, "0.2"));
        assertMigrationFailure(
                LegacyAppSettingsMigration.MigrationFailure.PARTIAL_TARGET,
                partial);

        FakeStore wrongType = new FakeStore(Map.of(
                LegacyAppSettingsMigration.KEY_MIGRATION_VERSION, "1"));
        assertMigrationFailure(
                LegacyAppSettingsMigration.MigrationFailure.PARTIAL_TARGET,
                wrongType);

        assertEquals(0, unknown.commitCount);
        assertEquals(0, partial.commitCount);
        assertEquals(0, wrongType.commitCount);
    }

    @Test
    public void commitAndReadbackFailuresPreserveLegacySourceAndStayRedacted() {
        FakeStore commitFailure = new FakeStore(zeroTwoFixture(
                RecognitionBackend.SYSTEM_DEFAULT,
                ProcessingMode.SMART,
                true,
                true,
                3L));
        Map<String, Object> original = Map.copyOf(commitFailure.values);
        commitFailure.failCommit = true;
        LegacyAppSettingsMigration.MigrationException commitError = assertThrows(
                LegacyAppSettingsMigration.MigrationException.class,
                () -> LegacyAppSettingsMigration.migrate(
                        commitFailure,
                        RecognitionBackend.SYSTEM_DEFAULT));
        assertEquals(
                LegacyAppSettingsMigration.MigrationFailure.COMMIT_FAILED,
                commitError.failure());
        assertEquals(original, commitFailure.values);

        FakeStore readbackFailure = new FakeStore(original);
        readbackFailure.corruptAfterCommit = true;
        LegacyAppSettingsMigration.MigrationException readbackError = assertThrows(
                LegacyAppSettingsMigration.MigrationException.class,
                () -> LegacyAppSettingsMigration.migrate(
                        readbackFailure,
                        RecognitionBackend.SYSTEM_DEFAULT));
        assertEquals(
                LegacyAppSettingsMigration.MigrationFailure.PARTIAL_TARGET,
                readbackError.failure());
        assertFalse(commitError.toString().contains(SECRET));
        assertFalse(readbackError.toString().contains(SECRET));
        assertEquals(0, commitError.getSuppressed().length);
        assertEquals(null, commitError.getCause());
    }

    @Test
    public void typedSaveProjectionUsesCanonicalThreeStateEncoding() {
        CapturingEditor editor = new CapturingEditor();
        AppSettings settings = appSettings(
                RecognitionBackend.DASHSCOPE_STREAMING,
                ProcessingMode.VERBATIM,
                false,
                true);

        LegacyAppSettingsMigration.writeProjection(editor, settings, 101L);

        assertEquals("[1,\"value\",true,\"legacy.dashscope-streaming\"]",
                editor.values.get(LegacyAppSettingsMigration.KEY_VOICE_ROUTE));
        assertEquals("[1,\"value\",true,\"EXACT\"]",
                editor.values.get(LegacyAppSettingsMigration.KEY_PROCESSING_MODE));
        assertEquals("[1,\"value\",true,\"false\"]",
                editor.values.get(LegacyAppSettingsMigration.KEY_SEND_CONTEXT));
        assertEquals("[1,\"value\",true,\"true\"]",
                editor.values.get(LegacyAppSettingsMigration.KEY_HISTORY_ENABLED));
        assertEquals("[1,\"disabled\",false]",
                editor.values.get(LegacyAppSettingsMigration.KEY_ACTION_SET));
        assertEquals(101L,
                editor.values.get(LegacyAppSettingsMigration.KEY_SOURCE_REVISION));
    }

    @Test
    public void transactionReadbackNeverRepairsAStaleProjection() {
        FakeStore store = new FakeStore(zeroTwoFixture(
                RecognitionBackend.SYSTEM_DEFAULT,
                ProcessingMode.SMART,
                true,
                false,
                17L));
        GlobalConfig expected = LegacyAppSettingsMigration.migrate(
                store,
                RecognitionBackend.SYSTEM_DEFAULT);
        assertEquals(
                expected,
                LegacyAppSettingsMigration.readValidated(
                        store,
                        RecognitionBackend.SYSTEM_DEFAULT));
        int commits = store.commitCount;

        store.values.put(
                LegacyAppSettingsMigration.KEY_PROCESSING_MODE,
                "[1,\"value\",true,\"TRANSLATE\"]");
        LegacyAppSettingsMigration.MigrationException failure = assertThrows(
                LegacyAppSettingsMigration.MigrationException.class,
                () -> LegacyAppSettingsMigration.readValidated(
                        store,
                        RecognitionBackend.SYSTEM_DEFAULT));

        assertEquals(
                LegacyAppSettingsMigration.MigrationFailure.READBACK_FAILED,
                failure.failure());
        assertEquals(commits, store.commitCount);
    }

    private static void assertMigrationFailure(
            LegacyAppSettingsMigration.MigrationFailure expected,
            FakeStore store) {
        LegacyAppSettingsMigration.MigrationException error = assertThrows(
                LegacyAppSettingsMigration.MigrationException.class,
                () -> LegacyAppSettingsMigration.migrate(
                        store,
                        RecognitionBackend.SYSTEM_DEFAULT));
        assertEquals(expected, error.failure());
    }

    private static Map<String, Object> zeroTwoFixture(
            RecognitionBackend backend,
            ProcessingMode mode,
            boolean sendContext,
            boolean historyEnabled,
            long revision) {
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("recognition_backend", backend.name());
        fixture.put("stt_base_url", "https://speech.example/v1");
        fixture.put("stt_model", "speech-model");
        fixture.put("language", "zh-CN");
        fixture.put("default_mode", mode.name());
        fixture.put("polish_enabled", true);
        fixture.put("llm_base_url", "https://language.example/v1");
        fixture.put("llm_model", "language-model");
        fixture.put("target_language", "English");
        fixture.put("custom_instructions", "legacy preference");
        fixture.put("personalization_enabled", true);
        fixture.put("history_enabled", historyEnabled);
        fixture.put("send_context", sendContext);
        fixture.put("max_recording_seconds", 180);
        fixture.put("settings_revision", revision);
        return fixture;
    }

    private static AppSettings appSettings(
            RecognitionBackend backend,
            ProcessingMode mode,
            boolean sendContext,
            boolean historyEnabled) {
        return new AppSettings(
                backend,
                "https://speech.example/v1",
                SECRET,
                "speech-model",
                "wss://stream.example/ws",
                SECRET,
                "stream-model",
                "vocabulary",
                "zh-CN",
                mode,
                true,
                "https://language.example/v1",
                SECRET,
                "language-model",
                "English",
                "legacy preference",
                true,
                historyEnabled,
                sendContext,
                180);
    }

    private static String targetValues(Map<String, Object> values) {
        StringBuilder joined = new StringBuilder();
        values.forEach((key, value) -> {
            if (key.startsWith("config_v1_")) joined.append(value);
        });
        return joined.toString();
    }

    private static Map<String, Object> mutable(Map<String, ?> values) {
        return new LinkedHashMap<>(values);
    }

    private static final class FakeStore implements LegacyAppSettingsMigration.Store {
        private final Map<String, Object> values;
        private int commitCount;
        private boolean failCommit;
        private boolean corruptAfterCommit;

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
            if (corruptAfterCommit) {
                values.put(LegacyAppSettingsMigration.KEY_FORMAT_VERSION, "1");
            }
            return true;
        }
    }

    private static final class CapturingEditor implements android.content.SharedPreferences.Editor {
        private final Map<String, Object> values = new LinkedHashMap<>();

        @Override public android.content.SharedPreferences.Editor putString(String key, String value) {
            values.put(key, value); return this;
        }
        @Override public android.content.SharedPreferences.Editor putStringSet(
                String key, java.util.Set<String> values) { throw new AssertionError(); }
        @Override public android.content.SharedPreferences.Editor putInt(String key, int value) {
            values.put(key, value); return this;
        }
        @Override public android.content.SharedPreferences.Editor putLong(String key, long value) {
            values.put(key, value); return this;
        }
        @Override public android.content.SharedPreferences.Editor putFloat(String key, float value) {
            throw new AssertionError();
        }
        @Override public android.content.SharedPreferences.Editor putBoolean(String key, boolean value) {
            values.put(key, value); return this;
        }
        @Override public android.content.SharedPreferences.Editor remove(String key) {
            throw new AssertionError();
        }
        @Override public android.content.SharedPreferences.Editor clear() {
            throw new AssertionError();
        }
        @Override public boolean commit() { throw new AssertionError(); }
        @Override public void apply() { throw new AssertionError(); }
    }
}
