package com.opentypeless.android.settings;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;

import com.opentypeless.android.config.AppRule;
import com.opentypeless.android.config.OverrideValue;
import com.opentypeless.android.config.OverrideValueCodec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Idempotently projects Android 0.2 AppProfiles into format-1 three-state AppRules. */
final class LegacyAppProfileMigration {
    static final int MIGRATION_VERSION = 1;
    static final int TARGET_FORMAT_VERSION = 1;
    static final String SOURCE_VERSION = "0.2";

    static final String LEGACY_PROFILES = "profiles_v1";
    static final String KEY_MIGRATION_VERSION = "app_rules_v1_migration_version";
    static final String KEY_SOURCE_VERSION = "app_rules_v1_source_version";
    static final String KEY_FORMAT_VERSION = "app_rules_v1_format_version";
    static final String KEY_BACKUP_RETAINED = "app_rules_v1_legacy_backup_retained";
    static final String KEY_RULES = "app_rules_v1_rules";

    static final int MAX_PROFILES = 100;
    static final int MAX_SOURCE_UTF16_UNITS = 1_000_000;
    static final int MAX_TARGET_UTF16_UNITS = 200_000;

    private static final int MAX_PACKAGE_UTF16_UNITS = 200;
    private static final int MAX_TARGET_LANGUAGE_CODE_POINTS = 80;
    private static final int MAX_INSTRUCTIONS_CODE_POINTS = 1_000;
    private static final Pattern PACKAGE =
            Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final Set<String> TARGET_KEYS = Set.of(
            KEY_MIGRATION_VERSION,
            KEY_SOURCE_VERSION,
            KEY_FORMAT_VERSION,
            KEY_BACKUP_RETAINED,
            KEY_RULES);

    private static final OverrideValueCodec<String> STRING_CODEC =
            new OverrideValueCodec<>(new StringScalarCodec());
    private static final OverrideValueCodec<com.opentypeless.android.config.ProcessingMode>
            PROCESSING_CODEC = new OverrideValueCodec<>(new ProcessingScalarCodec());
    private static final OverrideValueCodec<Boolean> BOOLEAN_CODEC =
            new OverrideValueCodec<>(new BooleanScalarCodec());

    private LegacyAppProfileMigration() {}

    static List<AppRule> migrate(SharedPreferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        return migrate(new SharedPreferencesStore(preferences));
    }

    static List<AppRule> migrate(Store store) {
        Objects.requireNonNull(store, "store");
        synchronized (LegacyAppProfileMigration.class) {
            return migrateLocked(store);
        }
    }

    static List<AppProfile> readLegacyProfiles(SharedPreferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        synchronized (LegacyAppProfileMigration.class) {
            SharedPreferencesStore store = new SharedPreferencesStore(preferences);
            migrateLocked(store);
            return readSource(readSnapshot(store));
        }
    }

    static List<AppProfile> readProfilesForUpdate(SharedPreferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        synchronized (LegacyAppProfileMigration.class) {
            SharedPreferencesStore store = new SharedPreferencesStore(preferences);
            Map<String, ?> snapshot = readSnapshot(store);
            List<AppProfile> source = readSource(snapshot);
            inspectTarget(snapshot);
            return source;
        }
    }

    static void writeProfiles(SharedPreferences preferences, List<AppProfile> profiles) {
        Objects.requireNonNull(preferences, "preferences");
        writeProfiles(new SharedPreferencesStore(preferences), profiles);
    }

    static void writeProfiles(Store store, List<AppProfile> profiles) {
        Objects.requireNonNull(store, "store");
        synchronized (LegacyAppProfileMigration.class) {
            Map<String, ?> before = readSnapshot(store);
            readSource(before);
            inspectTarget(before);
            List<AppProfile> source = immutableSource(profiles);
            List<AppRule> rules = project(source);
            String encodedSource = encodeSource(source);
            Map<String, Object> values = targetValues(rules);
            values.put(LEGACY_PROFILES, encodedSource);
            if (!commit(store, values)) throw failure(MigrationFailure.COMMIT_FAILED);
            verifyWrite(store, source, rules, encodedSource);
        }
    }

    private static List<AppRule> migrateLocked(Store store) {
        Map<String, ?> before = readSnapshot(store);
        List<AppProfile> source = readSource(before);
        List<AppRule> expected = project(source);
        ExistingTarget existing = inspectTarget(before);
        if (existing != null && existing.rules().equals(expected)) return existing.rules();

        if (!commit(store, targetValues(expected))) {
            throw failure(MigrationFailure.COMMIT_FAILED);
        }
        try {
            ExistingTarget migrated = inspectTarget(readSnapshot(store));
            if (migrated == null || !migrated.rules().equals(expected)) {
                throw failure(MigrationFailure.READBACK_FAILED);
            }
            return migrated.rules();
        } catch (MigrationException error) {
            throw failure(MigrationFailure.READBACK_FAILED);
        }
    }

    private static void verifyWrite(
            Store store,
            List<AppProfile> expectedSource,
            List<AppRule> expectedRules,
            String expectedEncodedSource) {
        try {
            Map<String, ?> after = readSnapshot(store);
            Object encoded = after.get(LEGACY_PROFILES);
            ExistingTarget target = inspectTarget(after);
            if (!(encoded instanceof String actualEncoded)
                    || !actualEncoded.equals(expectedEncodedSource)
                    || !readSource(after).equals(expectedSource)
                    || target == null
                    || !target.rules().equals(expectedRules)) {
                throw failure(MigrationFailure.READBACK_FAILED);
            }
        } catch (MigrationException error) {
            if (error.failure() == MigrationFailure.READBACK_FAILED) throw error;
            throw failure(MigrationFailure.READBACK_FAILED);
        }
    }

    private static List<AppProfile> readSource(Map<String, ?> snapshot) {
        Object value = snapshot.get(LEGACY_PROFILES);
        if (value == null && !snapshot.containsKey(LEGACY_PROFILES)) return List.of();
        if (!(value instanceof String encoded)) throw failure(MigrationFailure.MALFORMED_SOURCE);
        requireWellFormed(encoded, MAX_SOURCE_UTF16_UNITS, MigrationFailure.SOURCE_LIMIT_EXCEEDED);
        try {
            JSONTokener tokener = new JSONTokener(encoded);
            Object root = tokener.nextValue();
            if (!(root instanceof JSONArray array) || tokener.nextClean() != 0) {
                throw failure(MigrationFailure.MALFORMED_SOURCE);
            }
            if (array.length() > MAX_PROFILES) {
                throw failure(MigrationFailure.SOURCE_LIMIT_EXCEEDED);
            }
            List<AppProfile> profiles = new ArrayList<>(array.length());
            Set<String> packages = new HashSet<>();
            for (int index = 0; index < array.length(); index++) {
                Object row = array.get(index);
                if (!(row instanceof JSONObject item)) {
                    throw failure(MigrationFailure.MALFORMED_SOURCE);
                }
                AppProfile profile = readProfile(item);
                if (!packages.add(profile.packageName())) {
                    throw failure(MigrationFailure.DUPLICATE_SOURCE);
                }
                profiles.add(profile);
            }
            return List.copyOf(profiles);
        } catch (MigrationException error) {
            throw error;
        } catch (JSONException | RuntimeException | StackOverflowError error) {
            throw failure(MigrationFailure.MALFORMED_SOURCE);
        }
    }

    private static AppProfile readProfile(JSONObject item) throws JSONException {
        String packageName = cleanPackage(requiredString(item, "packageName"));
        ProcessingMode mode = item.has("mode")
                ? processingMode(requiredString(item, "mode"))
                : ProcessingMode.AUTO;
        String targetLanguage = item.has("targetLanguage")
                ? boundedText(
                        requiredString(item, "targetLanguage"),
                        MAX_TARGET_LANGUAGE_CODE_POINTS)
                : "";
        String customInstructions = item.has("customInstructions")
                ? boundedText(
                        requiredString(item, "customInstructions"),
                        MAX_INSTRUCTIONS_CODE_POINTS)
                : "";
        boolean sendContext = item.has("sendContext")
                ? requiredBoolean(item, "sendContext")
                : false;
        return new AppProfile(
                packageName,
                mode,
                targetLanguage,
                customInstructions,
                sendContext);
    }

    private static String requiredString(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof String text)) throw failure(MigrationFailure.MALFORMED_SOURCE);
        return text;
    }

    private static boolean requiredBoolean(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Boolean flag)) throw failure(MigrationFailure.MALFORMED_SOURCE);
        return flag;
    }

    private static String cleanPackage(String value) {
        String clean = boundedText(value, MAX_PACKAGE_UTF16_UNITS).trim();
        if (clean.isEmpty()
                || clean.length() > MAX_PACKAGE_UTF16_UNITS
                || !PACKAGE.matcher(clean).matches()) {
            throw failure(MigrationFailure.MALFORMED_SOURCE);
        }
        return clean;
    }

    private static String boundedText(String value, int maximumCodePoints) {
        requireWellFormed(value, Integer.MAX_VALUE, MigrationFailure.MALFORMED_SOURCE);
        String clean = value.trim();
        if (clean.codePointCount(0, clean.length()) > maximumCodePoints) {
            throw failure(MigrationFailure.SOURCE_LIMIT_EXCEEDED);
        }
        return clean;
    }

    private static ProcessingMode processingMode(String stored) {
        try {
            return ProcessingMode.valueOf(stored);
        } catch (RuntimeException error) {
            throw failure(MigrationFailure.MALFORMED_SOURCE);
        }
    }

    private static List<AppProfile> immutableSource(List<AppProfile> profiles) {
        if (profiles == null || profiles.size() > MAX_PROFILES) {
            throw failure(MigrationFailure.SOURCE_LIMIT_EXCEEDED);
        }
        List<AppProfile> copy = new ArrayList<>(profiles.size());
        Set<String> packages = new HashSet<>();
        try {
            for (AppProfile profile : profiles) {
                if (profile == null) throw failure(MigrationFailure.MALFORMED_SOURCE);
                AppProfile normalized = new AppProfile(
                        cleanPackage(profile.packageName()),
                        Objects.requireNonNull(profile.mode(), "mode"),
                        boundedText(
                                Objects.requireNonNull(profile.targetLanguage(), "targetLanguage"),
                                MAX_TARGET_LANGUAGE_CODE_POINTS),
                        boundedText(
                                Objects.requireNonNull(
                                        profile.customInstructions(),
                                        "customInstructions"),
                                MAX_INSTRUCTIONS_CODE_POINTS),
                        profile.sendContext());
                if (!packages.add(normalized.packageName())) {
                    throw failure(MigrationFailure.DUPLICATE_SOURCE);
                }
                copy.add(normalized);
            }
            return List.copyOf(copy);
        } catch (MigrationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(MigrationFailure.MALFORMED_SOURCE);
        }
    }

    private static List<AppRule> project(List<AppProfile> profiles) {
        List<AppRule> rules = new ArrayList<>(profiles.size());
        for (AppProfile profile : profiles) {
            rules.add(new AppRule(
                    profile.packageName(),
                    OverrideValue.inherit(),
                    OverrideValue.value(processingMode(profile.mode())),
                    OverrideValue.value(profile.sendContext()),
                    OverrideValue.inherit(),
                    OverrideValue.inherit()));
        }
        rules.sort(Comparator.comparing(AppRule::packageName));
        return List.copyOf(rules);
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

    private static Map<String, Object> targetValues(List<AppRule> rules) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(KEY_MIGRATION_VERSION, MIGRATION_VERSION);
        values.put(KEY_SOURCE_VERSION, SOURCE_VERSION);
        values.put(KEY_FORMAT_VERSION, TARGET_FORMAT_VERSION);
        values.put(KEY_BACKUP_RETAINED, true);
        values.put(KEY_RULES, encodeRules(rules));
        return values;
    }

    private static String encodeRules(List<AppRule> rules) {
        JSONArray array = new JSONArray();
        for (AppRule rule : rules) {
            array.put(new JSONArray()
                    .put(rule.packageName())
                    .put(STRING_CODEC.toJson(rule.voiceRouteId()))
                    .put(PROCESSING_CODEC.toJson(rule.processingMode()))
                    .put(BOOLEAN_CODEC.toJson(rule.sendContext()))
                    .put(BOOLEAN_CODEC.toJson(rule.historyEnabled()))
                    .put(STRING_CODEC.toJson(rule.actionSetId())));
        }
        String encoded = array.toString();
        requireWellFormed(encoded, MAX_TARGET_UTF16_UNITS, MigrationFailure.PARTIAL_TARGET);
        return encoded;
    }

    private static List<AppRule> decodeRules(String encoded) {
        requireWellFormed(encoded, MAX_TARGET_UTF16_UNITS, MigrationFailure.PARTIAL_TARGET);
        try {
            JSONTokener tokener = new JSONTokener(encoded);
            Object root = tokener.nextValue();
            if (!(root instanceof JSONArray array) || tokener.nextClean() != 0) {
                throw failure(MigrationFailure.PARTIAL_TARGET);
            }
            if (array.length() > MAX_PROFILES) {
                throw failure(MigrationFailure.PARTIAL_TARGET);
            }
            List<AppRule> rules = new ArrayList<>(array.length());
            Set<String> packages = new HashSet<>();
            String previousPackage = null;
            for (int index = 0; index < array.length(); index++) {
                Object row = array.get(index);
                if (!(row instanceof JSONArray fields) || fields.length() != 6) {
                    throw failure(MigrationFailure.PARTIAL_TARGET);
                }
                for (int field = 0; field < 6; field++) {
                    if (!(fields.get(field) instanceof String)) {
                        throw failure(MigrationFailure.PARTIAL_TARGET);
                    }
                }
                String packageName = (String) fields.get(0);
                AppRule rule = new AppRule(
                        packageName,
                        STRING_CODEC.fromJson((String) fields.get(1)),
                        PROCESSING_CODEC.fromJson((String) fields.get(2)),
                        BOOLEAN_CODEC.fromJson((String) fields.get(3)),
                        BOOLEAN_CODEC.fromJson((String) fields.get(4)),
                        STRING_CODEC.fromJson((String) fields.get(5)));
                if (!packages.add(packageName)
                        || (previousPackage != null
                                && previousPackage.compareTo(packageName) >= 0)) {
                    throw failure(MigrationFailure.PARTIAL_TARGET);
                }
                previousPackage = packageName;
                rules.add(rule);
            }
            return List.copyOf(rules);
        } catch (MigrationException error) {
            throw error;
        } catch (JSONException | RuntimeException | StackOverflowError error) {
            throw failure(MigrationFailure.PARTIAL_TARGET);
        }
    }

    private static ExistingTarget inspectTarget(Map<String, ?> snapshot) {
        boolean containsAny = TARGET_KEYS.stream().anyMatch(snapshot::containsKey);
        if (!containsAny) return null;
        if (!snapshot.containsKey(KEY_MIGRATION_VERSION)) {
            throw failure(MigrationFailure.PARTIAL_TARGET);
        }
        int migration = requiredInt(snapshot, KEY_MIGRATION_VERSION);
        if (migration != MIGRATION_VERSION) {
            throw failure(MigrationFailure.UNKNOWN_TARGET_VERSION);
        }
        for (String key : TARGET_KEYS) {
            if (!snapshot.containsKey(key)) throw failure(MigrationFailure.PARTIAL_TARGET);
        }
        if (!SOURCE_VERSION.equals(requiredStoredString(snapshot, KEY_SOURCE_VERSION))) {
            throw failure(MigrationFailure.UNKNOWN_TARGET_VERSION);
        }
        if (requiredInt(snapshot, KEY_FORMAT_VERSION) != TARGET_FORMAT_VERSION
                || !requiredStoredBoolean(snapshot, KEY_BACKUP_RETAINED)) {
            throw failure(MigrationFailure.PARTIAL_TARGET);
        }
        return new ExistingTarget(decodeRules(requiredStoredString(snapshot, KEY_RULES)));
    }

    private static int requiredInt(Map<String, ?> snapshot, String key) {
        Object value = snapshot.get(key);
        if (!(value instanceof Integer number)) throw failure(MigrationFailure.PARTIAL_TARGET);
        return number;
    }

    private static String requiredStoredString(Map<String, ?> snapshot, String key) {
        Object value = snapshot.get(key);
        if (!(value instanceof String text)) throw failure(MigrationFailure.PARTIAL_TARGET);
        return text;
    }

    private static boolean requiredStoredBoolean(Map<String, ?> snapshot, String key) {
        Object value = snapshot.get(key);
        if (!(value instanceof Boolean flag)) throw failure(MigrationFailure.PARTIAL_TARGET);
        return flag;
    }

    private static String encodeSource(List<AppProfile> profiles) {
        JSONArray array = new JSONArray();
        for (AppProfile profile : profiles) {
            try {
                array.put(new JSONObject()
                        .put("packageName", profile.packageName())
                        .put("mode", profile.mode().name())
                        .put("targetLanguage", profile.targetLanguage())
                        .put("customInstructions", profile.customInstructions())
                        .put("sendContext", profile.sendContext()));
            } catch (JSONException error) {
                throw failure(MigrationFailure.MALFORMED_SOURCE);
            }
        }
        String encoded = array.toString();
        requireWellFormed(encoded, MAX_SOURCE_UTF16_UNITS, MigrationFailure.SOURCE_LIMIT_EXCEEDED);
        return encoded;
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
            return store.commit(Map.copyOf(values));
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static void requireWellFormed(
            String value,
            int maximumUtf16Units,
            MigrationFailure failure) {
        if (value == null || value.length() > maximumUtf16Units) throw failure(failure);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw failure(failure);
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw failure(failure);
            }
        }
    }

    private static MigrationException failure(MigrationFailure failure) {
        return new MigrationException(failure);
    }

    interface Store {
        Map<String, ?> readAll();

        boolean commit(Map<String, Object> values);
    }

    enum MigrationFailure {
        MALFORMED_SOURCE,
        SOURCE_LIMIT_EXCEEDED,
        DUPLICATE_SOURCE,
        UNKNOWN_TARGET_VERSION,
        PARTIAL_TARGET,
        COMMIT_FAILED,
        READBACK_FAILED
    }

    /** Stable content-free failure for malformed or ambiguous persisted app rules. */
    static final class MigrationException extends IllegalStateException {
        private final MigrationFailure failure;

        private MigrationException(MigrationFailure failure) {
            super(Objects.requireNonNull(failure, "failure").name());
            this.failure = failure;
        }

        MigrationFailure failure() {
            return failure;
        }

        @Override
        public String toString() {
            return "MigrationException{failure=" + failure + "}";
        }
    }

    private record ExistingTarget(List<AppRule> rules) {
        private ExistingTarget {
            rules = List.copyOf(rules);
        }

        @Override
        public String toString() {
            return "ExistingTarget{rules=<redacted>}";
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
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String string) {
                    editor.putString(entry.getKey(), string);
                } else if (value instanceof Integer number) {
                    editor.putInt(entry.getKey(), number);
                } else if (value instanceof Boolean flag) {
                    editor.putBoolean(entry.getKey(), flag);
                } else {
                    throw failure(MigrationFailure.PARTIAL_TARGET);
                }
            }
            return editor.commit();
        }
    }
}
