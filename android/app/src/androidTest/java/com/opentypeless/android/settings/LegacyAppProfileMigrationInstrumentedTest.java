package com.opentypeless.android.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.config.AppRule;
import com.opentypeless.android.config.OverrideValue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public final class LegacyAppProfileMigrationInstrumentedTest {
    private static final String FIXTURE_STORE = "cfg007_android_0_2_profiles_fixture";
    private static final String PRODUCTION_STORE = "opentypeless_app_profiles";

    @Test
    public void actualZeroTwoSharedPreferencesUpgradeIsIdempotentAndKeepsUnmappedBackup()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences(
                FIXTURE_STORE,
                Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
        try {
            String source = new JSONArray().put(new JSONObject()
                    .put("packageName", "com.example.editor")
                    .put("mode", ProcessingMode.VERBATIM.name())
                    .put("targetLanguage", "English")
                    .put("customInstructions", "cfg007-unmapped-device-sentinel")
                    .put("sendContext", false)).toString();
            assertTrue(preferences.edit()
                    .putString(LegacyAppProfileMigration.LEGACY_PROFILES, source)
                    .commit());

            List<AppRule> first = LegacyAppProfileMigration.migrate(preferences);
            Map<String, ?> afterFirst = new LinkedHashMap<>(preferences.getAll());
            List<AppRule> second = LegacyAppProfileMigration.migrate(preferences);

            assertEquals(first, second);
            assertEquals(afterFirst, preferences.getAll());
            assertEquals(OverrideValue.value(false), first.get(0).sendContext());
            assertEquals(
                    OverrideValue.value(
                            com.opentypeless.android.config.ProcessingMode.EXACT),
                    first.get(0).processingMode());
            assertTrue(preferences.getString(
                    LegacyAppProfileMigration.LEGACY_PROFILES,
                    "").contains("cfg007-unmapped-device-sentinel"));
            assertFalse(preferences.getString(
                    LegacyAppProfileMigration.KEY_RULES,
                    "").contains("cfg007-unmapped-device-sentinel"));
            assertTrue(preferences.getBoolean(
                    LegacyAppProfileMigration.KEY_BACKUP_RETAINED,
                    false));
        } finally {
            assertTrue(preferences.edit().clear().commit());
        }
    }

    @Test
    public void repositorySaveAndDeleteKeepLegacyAndRuleShadowInOneReadableState()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences(
                PRODUCTION_STORE,
                Context.MODE_PRIVATE);
        Map<String, ?> backup = new LinkedHashMap<>(preferences.getAll());
        assertTrue(preferences.edit().clear().commit());
        try {
            AppProfileRepository repository = new AppProfileRepository(context);
            repository.save(new AppProfile(
                    "com.example.editor",
                    ProcessingMode.TRANSLATE,
                    "French",
                    "cfg007-repository-backup",
                    false));

            List<AppRule> rules = repository.loadMigratedAppRules();
            assertEquals(1, repository.list().size());
            assertEquals(1, rules.size());
            assertEquals(OverrideValue.value(false), rules.get(0).sendContext());
            assertEquals(
                    OverrideValue.value(
                            com.opentypeless.android.config.ProcessingMode.TRANSLATE),
                    rules.get(0).processingMode());
            assertTrue(preferences.getString(
                    LegacyAppProfileMigration.LEGACY_PROFILES,
                    "").contains("cfg007-repository-backup"));
            assertFalse(preferences.getString(
                    LegacyAppProfileMigration.KEY_RULES,
                    "").contains("cfg007-repository-backup"));

            repository.delete("com.example.editor");
            assertEquals(List.of(), repository.list());
            assertEquals(List.of(), repository.loadMigratedAppRules());
            assertEquals("[]", preferences.getString(
                    LegacyAppProfileMigration.LEGACY_PROFILES,
                    "missing"));
        } finally {
            restore(preferences, backup);
        }
    }

    private static void restore(SharedPreferences preferences, Map<String, ?> backup) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : backup.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String string) {
                editor.putString(entry.getKey(), string);
            } else if (value instanceof Integer integer) {
                editor.putInt(entry.getKey(), integer);
            } else if (value instanceof Long number) {
                editor.putLong(entry.getKey(), number);
            } else if (value instanceof Boolean flag) {
                editor.putBoolean(entry.getKey(), flag);
            } else if (value instanceof Float number) {
                editor.putFloat(entry.getKey(), number);
            } else if (value instanceof Set<?> values) {
                @SuppressWarnings("unchecked")
                Set<String> strings = (Set<String>) values;
                editor.putStringSet(entry.getKey(), strings);
            } else {
                throw new AssertionError("unexpected SharedPreferences value type");
            }
        }
        assertTrue(editor.commit());
    }
}
