package com.opentypeless.android.settings;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;

import com.opentypeless.android.config.GlobalConfig;
import com.opentypeless.android.config.OverrideValue;
import com.opentypeless.android.config.OverrideValueCodec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Idempotently projects the representable Android 0.2 settings into GlobalConfig format 1.
 *
 * <p>The projection is an inert compatibility shadow until later configuration tasks select it as
 * runtime authority. Legacy keys are deliberately retained as the rollback source; provider
 * metadata and secrets are never copied into this projection.
 */
final class LegacyAppSettingsMigration {
    static final int MIGRATION_VERSION = 1;
    static final String SOURCE_VERSION = "0.2";
    static final String LEGACY_LAYOUT_ID = "latin.base";

    static final String KEY_MIGRATION_VERSION = "config_v1_migration_version";
    static final String KEY_SOURCE_VERSION = "config_v1_source_version";
    static final String KEY_SOURCE_REVISION = "config_v1_source_revision";
    static final String KEY_BACKUP_RETAINED = "config_v1_legacy_backup_retained";
    static final String KEY_FORMAT_VERSION = "config_v1_global_format_version";
    static final String KEY_KEYBOARD_LAYOUT = "config_v1_keyboard_layout";
    static final String KEY_VOICE_ROUTE = "config_v1_voice_route";
    static final String KEY_PROCESSING_MODE = "config_v1_processing_mode";
    static final String KEY_SEND_CONTEXT = "config_v1_send_context";
    static final String KEY_HISTORY_ENABLED = "config_v1_history_enabled";
    static final String KEY_ACTION_SET = "config_v1_action_set";

    private static final String LEGACY_RECOGNITION_BACKEND = "recognition_backend";
    private static final String LEGACY_PROCESSING_MODE = "default_mode";
    private static final String LEGACY_SEND_CONTEXT = "send_context";
    private static final String LEGACY_HISTORY_ENABLED = "history_enabled";
    private static final String LEGACY_REVISION = "settings_revision";

    private static final Set<String> TARGET_KEYS = Set.of(
            KEY_MIGRATION_VERSION,
            KEY_SOURCE_VERSION,
            KEY_SOURCE_REVISION,
            KEY_BACKUP_RETAINED,
            KEY_FORMAT_VERSION,
            KEY_KEYBOARD_LAYOUT,
            KEY_VOICE_ROUTE,
            KEY_PROCESSING_MODE,
            KEY_SEND_CONTEXT,
            KEY_HISTORY_ENABLED,
            KEY_ACTION_SET);
    private static final Object MIGRATION_LOCK = new Object();

    private static final OverrideValueCodec<String> STRING_CODEC =
            new OverrideValueCodec<>(new StringScalarCodec());
    private static final OverrideValueCodec<com.opentypeless.android.config.ProcessingMode>
            PROCESSING_CODEC = new OverrideValueCodec<>(new ProcessingScalarCodec());
    private static final OverrideValueCodec<Boolean> BOOLEAN_CODEC =
            new OverrideValueCodec<>(new BooleanScalarCodec());

    private LegacyAppSettingsMigration() {}

    static GlobalConfig migrate(
            SharedPreferences preferences,
            RecognitionBackend defaultBackend) {
        Objects.requireNonNull(preferences, "preferences");
        return migrate(new SharedPreferencesStore(preferences), defaultBackend);
    }

    static GlobalConfig migrate(Store store, RecognitionBackend defaultBackend) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(defaultBackend, "defaultBackend");
        synchronized (MIGRATION_LOCK) {
            Map<String, ?> before = readSnapshot(store);
            long sourceRevision = legacyLong(before, LEGACY_REVISION, 0L);
            ExistingTarget existing = inspectTarget(before);
            if (existing != null && existing.sourceRevision() == sourceRevision) {
                return existing.config();
            }

            LegacyValues legacy = readLegacy(before, defaultBackend);
            Projection projection = projection(legacy, sourceRevision);
            if (!commit(store, projection.values())) {
                throw failure(MigrationFailure.COMMIT_FAILED);
            }

            Map<String, ?> after = readSnapshot(store);
            ExistingTarget migrated = inspectTarget(after);
            if (migrated == null
                    || migrated.sourceRevision() != sourceRevision
                    || !migrated.config().equals(projection.config())) {
                throw failure(MigrationFailure.READBACK_FAILED);
            }
            return migrated.config();
        }
    }

    /** Reads the existing projection without repairing or writing any target key. */
    static GlobalConfig readValidated(
            SharedPreferences preferences,
            RecognitionBackend defaultBackend) {
        Objects.requireNonNull(preferences, "preferences");
        return readValidated(new SharedPreferencesStore(preferences), defaultBackend);
    }

    static GlobalConfig readValidated(Store store, RecognitionBackend defaultBackend) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(defaultBackend, "defaultBackend");
        synchronized (MIGRATION_LOCK) {
            Map<String, ?> snapshot = readSnapshot(store);
            long sourceRevision = legacyLong(snapshot, LEGACY_REVISION, 0L);
            Projection expected = projection(readLegacy(snapshot, defaultBackend), sourceRevision);
            ExistingTarget actual = inspectTarget(snapshot);
            if (actual == null
                    || actual.sourceRevision() != sourceRevision
                    || !actual.config().equals(expected.config())) {
                throw failure(MigrationFailure.READBACK_FAILED);
            }
            return actual.config();
        }
    }

    /** Adds the complete projection to the caller's existing single-file settings transaction. */
    static void writeProjection(
            SharedPreferences.Editor editor,
            AppSettings settings,
            long sourceRevision) {
        Objects.requireNonNull(editor, "editor");
        Projection projection = projection(
                new LegacyValues(
                        Objects.requireNonNull(settings, "settings").recognitionBackend(),
                        settings.defaultMode(),
                        settings.sendContext(),
                        settings.historyEnabled()),
                sourceRevision);
        putValues(editor, projection.values());
    }

    private static Projection projection(LegacyValues legacy, long sourceRevision) {
        if (sourceRevision < 0L) {
            throw failure(MigrationFailure.MALFORMED_SOURCE);
        }
        RecognitionBackend backend = Objects.requireNonNull(
                legacy.recognitionBackend(),
                "recognitionBackend");
        ProcessingMode legacyMode = Objects.requireNonNull(
                legacy.processingMode(),
                "processingMode");

        OverrideValue<String> route = OverrideValue.value(routeId(backend));
        OverrideValue<com.opentypeless.android.config.ProcessingMode> mode =
                OverrideValue.value(processingMode(legacyMode));
        OverrideValue<Boolean> sendContext = OverrideValue.value(legacy.sendContext());
        OverrideValue<Boolean> historyEnabled = OverrideValue.value(legacy.historyEnabled());
        OverrideValue<String> actionSet = OverrideValue.disabled();

        GlobalConfig config = new GlobalConfig(
                GlobalConfig.FORMAT_VERSION,
                new GlobalConfig.KeyboardConfig(LEGACY_LAYOUT_ID),
                new GlobalConfig.VoiceConfig(route),
                new GlobalConfig.ProcessingConfig(mode),
                new GlobalConfig.PrivacyConfig(sendContext, historyEnabled),
                new GlobalConfig.AutomationConfig(actionSet));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put(KEY_MIGRATION_VERSION, MIGRATION_VERSION);
        values.put(KEY_SOURCE_VERSION, SOURCE_VERSION);
        values.put(KEY_SOURCE_REVISION, sourceRevision);
        values.put(KEY_BACKUP_RETAINED, true);
        values.put(KEY_FORMAT_VERSION, GlobalConfig.FORMAT_VERSION);
        values.put(KEY_KEYBOARD_LAYOUT, LEGACY_LAYOUT_ID);
        values.put(KEY_VOICE_ROUTE, STRING_CODEC.toJson(route));
        values.put(KEY_PROCESSING_MODE, PROCESSING_CODEC.toJson(mode));
        values.put(KEY_SEND_CONTEXT, BOOLEAN_CODEC.toJson(sendContext));
        values.put(KEY_HISTORY_ENABLED, BOOLEAN_CODEC.toJson(historyEnabled));
        values.put(KEY_ACTION_SET, STRING_CODEC.toJson(actionSet));
        return new Projection(config, Map.copyOf(values));
    }

    private static ExistingTarget inspectTarget(Map<String, ?> snapshot) {
        boolean containsAny = TARGET_KEYS.stream().anyMatch(snapshot::containsKey);
        if (!containsAny) return null;
        if (!snapshot.containsKey(KEY_MIGRATION_VERSION)) {
            throw failure(MigrationFailure.PARTIAL_TARGET);
        }
        int migrationVersion = requiredInt(snapshot, KEY_MIGRATION_VERSION);
        if (migrationVersion != MIGRATION_VERSION) {
            throw failure(MigrationFailure.UNKNOWN_TARGET_VERSION);
        }
        if (!SOURCE_VERSION.equals(requiredString(snapshot, KEY_SOURCE_VERSION))
                || !requiredBoolean(snapshot, KEY_BACKUP_RETAINED)
                || requiredInt(snapshot, KEY_FORMAT_VERSION) != GlobalConfig.FORMAT_VERSION) {
            throw failure(MigrationFailure.PARTIAL_TARGET);
        }
        for (String key : TARGET_KEYS) {
            if (!snapshot.containsKey(key)) {
                throw failure(MigrationFailure.PARTIAL_TARGET);
            }
        }

        try {
            GlobalConfig config = new GlobalConfig(
                    requiredInt(snapshot, KEY_FORMAT_VERSION),
                    new GlobalConfig.KeyboardConfig(requiredString(
                            snapshot,
                            KEY_KEYBOARD_LAYOUT)),
                    new GlobalConfig.VoiceConfig(STRING_CODEC.fromJson(requiredString(
                            snapshot,
                            KEY_VOICE_ROUTE))),
                    new GlobalConfig.ProcessingConfig(PROCESSING_CODEC.fromJson(requiredString(
                            snapshot,
                            KEY_PROCESSING_MODE))),
                    new GlobalConfig.PrivacyConfig(
                            BOOLEAN_CODEC.fromJson(requiredString(snapshot, KEY_SEND_CONTEXT)),
                            BOOLEAN_CODEC.fromJson(requiredString(
                                    snapshot,
                                    KEY_HISTORY_ENABLED))),
                    new GlobalConfig.AutomationConfig(STRING_CODEC.fromJson(requiredString(
                            snapshot,
                            KEY_ACTION_SET))));
            return new ExistingTarget(config, requiredLong(snapshot, KEY_SOURCE_REVISION));
        } catch (MigrationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(MigrationFailure.PARTIAL_TARGET);
        }
    }

    private static LegacyValues readLegacy(
            Map<String, ?> snapshot,
            RecognitionBackend defaultBackend) {
        try {
            RecognitionBackend backend = RecognitionBackend.valueOf(legacyString(
                    snapshot,
                    LEGACY_RECOGNITION_BACKEND,
                    defaultBackend.name()));
            ProcessingMode mode = ProcessingMode.valueOf(legacyString(
                    snapshot,
                    LEGACY_PROCESSING_MODE,
                    ProcessingMode.AUTO.name()));
            return new LegacyValues(
                    backend,
                    mode,
                    legacyBoolean(snapshot, LEGACY_SEND_CONTEXT, false),
                    legacyBoolean(snapshot, LEGACY_HISTORY_ENABLED, false));
        } catch (MigrationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(MigrationFailure.MALFORMED_SOURCE);
        }
    }

    private static String routeId(RecognitionBackend backend) {
        if (backend == RecognitionBackend.OPENAI_COMPATIBLE) {
            return "legacy.openai-compatible";
        }
        if (backend == RecognitionBackend.LOCAL_OFFLINE) return "legacy.local-offline";
        if (backend == RecognitionBackend.DASHSCOPE_STREAMING) {
            return "legacy.dashscope-streaming";
        }
        if (backend == RecognitionBackend.SYSTEM_ON_DEVICE) {
            return "legacy.system-on-device";
        }
        if (backend == RecognitionBackend.SYSTEM_DEFAULT) return "legacy.system-default";
        throw new AssertionError("unhandled recognition backend");
    }

    private static com.opentypeless.android.config.ProcessingMode processingMode(
            ProcessingMode mode) {
        if (mode == ProcessingMode.AUTO) {
            return com.opentypeless.android.config.ProcessingMode.AUTO;
        }
        if (mode == ProcessingMode.VERBATIM) {
            return com.opentypeless.android.config.ProcessingMode.EXACT;
        }
        if (mode == ProcessingMode.SMART) {
            return com.opentypeless.android.config.ProcessingMode.SMART;
        }
        if (mode == ProcessingMode.TRANSLATE) {
            return com.opentypeless.android.config.ProcessingMode.TRANSLATE;
        }
        throw new AssertionError("unhandled processing mode");
    }

    private static Map<String, ?> readSnapshot(Store store) {
        try {
            Map<String, ?> snapshot = store.readAll();
            if (snapshot == null) throw failure(MigrationFailure.MALFORMED_SOURCE);
            return Map.copyOf(snapshot);
        } catch (MigrationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(MigrationFailure.MALFORMED_SOURCE);
        }
    }

    private static boolean commit(Store store, Map<String, Object> values) {
        try {
            return store.commit(values);
        } catch (RuntimeException error) {
            throw failure(MigrationFailure.COMMIT_FAILED);
        }
    }

    private static String legacyString(Map<String, ?> values, String key, String fallback) {
        if (!values.containsKey(key)) return fallback;
        Object value = values.get(key);
        if (!(value instanceof String string)) {
            throw failure(MigrationFailure.MALFORMED_SOURCE);
        }
        return string;
    }

    private static boolean legacyBoolean(
            Map<String, ?> values,
            String key,
            boolean fallback) {
        if (!values.containsKey(key)) return fallback;
        Object value = values.get(key);
        if (!(value instanceof Boolean bool)) {
            throw failure(MigrationFailure.MALFORMED_SOURCE);
        }
        return bool;
    }

    private static long legacyLong(Map<String, ?> values, String key, long fallback) {
        if (!values.containsKey(key)) return fallback;
        Object value = values.get(key);
        if (!(value instanceof Long result)) {
            throw failure(MigrationFailure.MALFORMED_SOURCE);
        }
        if (result < 0L) throw failure(MigrationFailure.MALFORMED_SOURCE);
        return result;
    }

    private static String requiredString(Map<String, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String string)) {
            throw failure(MigrationFailure.PARTIAL_TARGET);
        }
        return string;
    }

    private static boolean requiredBoolean(Map<String, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Boolean bool)) {
            throw failure(MigrationFailure.PARTIAL_TARGET);
        }
        return bool;
    }

    private static int requiredInt(Map<String, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Integer integer)) {
            throw failure(MigrationFailure.PARTIAL_TARGET);
        }
        return integer;
    }

    private static long requiredLong(Map<String, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Long number)) {
            throw failure(MigrationFailure.PARTIAL_TARGET);
        }
        return number;
    }

    private static MigrationException failure(MigrationFailure failure) {
        return new MigrationException(failure);
    }

    private static void putValues(
            SharedPreferences.Editor editor,
            Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String string) {
                editor.putString(entry.getKey(), string);
            } else if (value instanceof Boolean bool) {
                editor.putBoolean(entry.getKey(), bool);
            } else if (value instanceof Integer integer) {
                editor.putInt(entry.getKey(), integer);
            } else if (value instanceof Long number) {
                editor.putLong(entry.getKey(), number);
            } else {
                throw failure(MigrationFailure.PARTIAL_TARGET);
            }
        }
    }

    interface Store {
        Map<String, ?> readAll();

        boolean commit(Map<String, Object> values);
    }

    public enum MigrationFailure {
        MALFORMED_SOURCE,
        UNKNOWN_TARGET_VERSION,
        PARTIAL_TARGET,
        COMMIT_FAILED,
        READBACK_FAILED
    }

    /** Stable content-free failure surfaced when the old data cannot be migrated safely. */
    public static final class MigrationException extends IllegalStateException {
        private final MigrationFailure failure;

        private MigrationException(MigrationFailure failure) {
            super(Objects.requireNonNull(failure, "failure").name());
            this.failure = failure;
        }

        public MigrationFailure failure() {
            return failure;
        }

        @Override
        public String toString() {
            return "MigrationException{failure=" + failure + "}";
        }
    }

    private record LegacyValues(
            RecognitionBackend recognitionBackend,
            ProcessingMode processingMode,
            boolean sendContext,
            boolean historyEnabled) {
        @Override
        public String toString() {
            return "LegacyValues{values=<redacted>}";
        }
    }

    private record Projection(GlobalConfig config, Map<String, Object> values) {
        @Override
        public String toString() {
            return "Projection{config=<redacted>, values=<redacted>}";
        }
    }

    private record ExistingTarget(GlobalConfig config, long sourceRevision) {
        @Override
        public String toString() {
            return "ExistingTarget{config=<redacted>, sourceRevision=<redacted>}";
        }
    }

    private static final class StringScalarCodec
            implements OverrideValueCodec.ScalarCodec<String> {
        @Override
        public String encode(String value) {
            return Objects.requireNonNull(value, "value");
        }

        @Override
        public String decode(String encodedValue) {
            return Objects.requireNonNull(encodedValue, "encodedValue");
        }
    }

    private static final class ProcessingScalarCodec
            implements OverrideValueCodec.ScalarCodec<
                    com.opentypeless.android.config.ProcessingMode> {
        @Override
        public String encode(com.opentypeless.android.config.ProcessingMode value) {
            return Objects.requireNonNull(value, "value").name();
        }

        @Override
        public com.opentypeless.android.config.ProcessingMode decode(String encodedValue) {
            return com.opentypeless.android.config.ProcessingMode.valueOf(encodedValue);
        }
    }

    private static final class BooleanScalarCodec
            implements OverrideValueCodec.ScalarCodec<Boolean> {
        @Override
        public String encode(Boolean value) {
            return Objects.requireNonNull(value, "value") ? "true" : "false";
        }

        @Override
        public Boolean decode(String encodedValue) {
            if ("true".equals(encodedValue)) return true;
            if ("false".equals(encodedValue)) return false;
            throw new IllegalArgumentException("invalid boolean scalar");
        }
    }

    private static final class SharedPreferencesStore implements Store {
        private final SharedPreferences preferences;

        private SharedPreferencesStore(SharedPreferences preferences) {
            this.preferences = preferences;
        }

        @Override
        public Map<String, ?> readAll() {
            return preferences.getAll();
        }

        @Override
        @SuppressLint("ApplySharedPref")
        public boolean commit(Map<String, Object> values) {
            SharedPreferences.Editor editor = preferences.edit();
            putValues(editor, values);
            return editor.commit();
        }
    }
}
