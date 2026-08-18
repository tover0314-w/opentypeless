package com.opentypeless.android.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.config.SecretRef;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.SettingsRepository;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.KeyStore;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class SecretStoreInstrumentedTest {
    private static final String TEST_STORE = "opentypeless_cfg008_test";
    private static final String TEST_ALIAS = "opentypeless_cfg008_test_key_v1";

    @Test
    public void androidKeystoreCreateUseRotateAndLegacyMigrationAreEncryptedAndIdempotent()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences raw = context.getSharedPreferences(TEST_STORE, Context.MODE_PRIVATE);
        raw.edit().clear().commit();
        deleteAlias();
        SecurePreferences secure = new SecurePreferences(context, TEST_STORE, TEST_ALIAS);
        SecretStore store = new SecretStore(
                new AndroidTestStorage(secure),
                new SequenceIds());
        char[] firstSecret = "cfg008-android-secret-one".toCharArray();
        char[] secondSecret = "cfg008-android-secret-two".toCharArray();
        try {
            SecretRef first = store.create(SecretRef.Kind.CONNECTOR, firstSecret);
            AtomicReference<char[]> borrowed = new AtomicReference<>();
            store.use(first, value -> {
                assertArrayEquals(firstSecret, value);
                borrowed.set(value);
            });
            assertTrue(allZero(borrowed.get()));
            assertStoreDoesNotContain(raw, firstSecret, secondSecret);

            SecretRef second = store.rotate(first, secondSecret);
            assertNotEquals(first, second);
            store.use(second, value -> assertArrayEquals(secondSecret, value));
            assertStoreDoesNotContain(raw, firstSecret, secondSecret);

            secure.put("stt_api_key", "legacy-secret");
            int entriesBefore = raw.getAll().size();
            SecretStore.LegacyRefs migrated = store.migrateLegacy();
            assertTrue(migrated.sttAsr().isPresent());
            assertEquals(migrated, store.migrateLegacy());
            assertEquals(entriesBefore + 3, raw.getAll().size());
            store.use(migrated.sttAsr().orElseThrow(), value -> assertArrayEquals(
                    "legacy-secret".toCharArray(), value));
            assertStoreDoesNotContain(
                    raw,
                    firstSecret,
                    secondSecret,
                    "legacy-secret".toCharArray());
        } finally {
            raw.edit().clear().commit();
            deleteAlias();
        }
    }

    @Test
    public void repositorySaveRefreshesOpaqueShadowAndProductionPreferencesAreRestored()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences settings = context.getSharedPreferences(
                "opentypeless_settings",
                Context.MODE_PRIVATE);
        SharedPreferences transaction = context.getSharedPreferences(
                "opentypeless_settings_transaction",
                Context.MODE_PRIVATE);
        SharedPreferences secrets = context.getSharedPreferences(
                "opentypeless_secrets",
                Context.MODE_PRIVATE);
        Map<String, ?> settingsBefore = Map.copyOf(settings.getAll());
        Map<String, ?> transactionBefore = Map.copyOf(transaction.getAll());
        Map<String, ?> secretsBefore = Map.copyOf(secrets.getAll());
        char[] sentinel = "cfg008-repository-sentinel".toCharArray();
        try {
            SettingsRepository repository = new SettingsRepository(context);
            AppSettings existing = repository.load();
            repository.save(withSecrets(existing, new String(sentinel), "", "llm-sentinel"));
            SecretStore.LegacyRefs first = repository.loadMigratedSecretRefs();
            assertTrue(first.sttAsr().isPresent());
            assertTrue(first.streamingAsr().isEmpty());
            assertTrue(first.llm().isPresent());
            new SecretStore(context).use(first.sttAsr().orElseThrow(), value ->
                    assertArrayEquals(sentinel, value));
            assertStoreDoesNotContain(
                    secrets,
                    sentinel,
                    "llm-sentinel".toCharArray());

            repository.save(withSecrets(existing, "replacement", "", ""));
            SecretStore.LegacyRefs second = repository.loadMigratedSecretRefs();
            assertEquals(first.sttAsr(), second.sttAsr());
            assertTrue(second.llm().isEmpty());
            new SecretStore(context).use(second.sttAsr().orElseThrow(), value ->
                    assertArrayEquals("replacement".toCharArray(), value));
        } finally {
            restore(settings, settingsBefore);
            restore(transaction, transactionBefore);
            restore(secrets, secretsBefore);
        }
    }

    @Test
    public void pendingJournalRestoresExactSettingsCiphertextAndRetiredRefIdentity()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences settings = context.getSharedPreferences(
                "opentypeless_settings",
                Context.MODE_PRIVATE);
        SharedPreferences transaction = context.getSharedPreferences(
                "opentypeless_settings_transaction",
                Context.MODE_PRIVATE);
        SharedPreferences secrets = context.getSharedPreferences(
                "opentypeless_secrets",
                Context.MODE_PRIVATE);
        Map<String, ?> settingsBefore = Map.copyOf(settings.getAll());
        Map<String, ?> transactionBefore = Map.copyOf(transaction.getAll());
        Map<String, ?> secretsBefore = Map.copyOf(secrets.getAll());
        try {
            SettingsRepository repository = new SettingsRepository(context);
            AppSettings initial = repository.load();
            repository.save(withSecrets(initial, "cfg011-old-secret", "", ""));
            AppSettings oldState = repository.load();
            SecretStore.LegacyRefs oldRefs = repository.loadMigratedSecretRefs();
            String oldCiphertext = secrets.getString("stt_api_key", "");
            long oldRevision = settings.getLong("settings_revision", -1L);
            assertFalse(oldCiphertext.isEmpty());
            assertTrue(oldRefs.sttAsr().isPresent());

            repository.save(withSecrets(oldState, "", "", ""));
            assertTrue(repository.loadMigratedSecretRefs().sttAsr().isEmpty());
            writePendingJournal(
                    transaction,
                    oldState,
                    oldRevision,
                    oldCiphertext,
                    oldRefs.sttAsr().orElseThrow().opaqueId());

            SettingsRepository recovered = new SettingsRepository(context);
            assertEquals(oldState, recovered.load());
            assertEquals(oldRefs, recovered.loadMigratedSecretRefs());
            assertTrue(transaction.getAll().isEmpty());
            new SecretStore(context).use(oldRefs.sttAsr().orElseThrow(), value ->
                    assertArrayEquals("cfg011-old-secret".toCharArray(), value));
            assertStoreDoesNotContain(secrets, "cfg011-old-secret".toCharArray());
        } finally {
            restore(settings, settingsBefore);
            restore(transaction, transactionBefore);
            restore(secrets, secretsBefore);
        }
    }

    private static AppSettings withSecrets(
            AppSettings source,
            String stt,
            String streaming,
            String llm) {
        return new AppSettings(
                source.recognitionBackend(),
                source.sttBaseUrl(),
                stt,
                source.sttModel(),
                source.streamingBaseUrl(),
                streaming,
                source.streamingModel(),
                source.streamingVocabularyId(),
                source.language(),
                source.defaultMode(),
                source.polishEnabled(),
                source.llmBaseUrl(),
                llm,
                source.llmModel(),
                source.targetLanguage(),
                source.customInstructions(),
                source.personalizationEnabled(),
                source.historyEnabled(),
                source.sendContext(),
                source.maxRecordingSeconds());
    }

    private static void writePendingJournal(
            SharedPreferences transaction,
            AppSettings old,
            long oldRevision,
            String oldSttCiphertext,
            String oldSttRef) {
        SharedPreferences.Editor editor = transaction.edit().clear()
                .putBoolean("pending", true)
                .putString("old_recognition_backend", old.recognitionBackend().name())
                .putString("old_stt_base_url", old.sttBaseUrl())
                .putString("old_stt_model", old.sttModel())
                .putString("old_streaming_base_url", old.streamingBaseUrl())
                .putString("old_streaming_model", old.streamingModel())
                .putString("old_streaming_vocabulary_id", old.streamingVocabularyId())
                .putString("old_language", old.language())
                .putString("old_default_mode", old.defaultMode().name())
                .putBoolean("old_polish_enabled", old.polishEnabled())
                .putString("old_llm_base_url", old.llmBaseUrl())
                .putString("old_llm_model", old.llmModel())
                .putString("old_target_language", old.targetLanguage())
                .putString("old_custom_instructions", old.customInstructions())
                .putBoolean("old_personalization_enabled", old.personalizationEnabled())
                .putBoolean("old_history_enabled", old.historyEnabled())
                .putBoolean("old_send_context", old.sendContext())
                .putInt("old_max_recording_seconds", old.maxRecordingSeconds())
                .putLong("old_settings_revision", oldRevision)
                .putString("old_stt_secret", oldSttCiphertext)
                .putString("old_streaming_secret", "")
                .putString("old_llm_secret", "")
                .putString("old_stt_ref", oldSttRef)
                .putString("old_streaming_ref", "")
                .putString("old_llm_ref", "");
        assertTrue(editor.commit());
    }

    private static void assertStoreDoesNotContain(
            SharedPreferences preferences,
            char[]... secrets) {
        String rendered = preferences.getAll().toString();
        for (char[] secret : secrets) assertFalse(rendered.contains(new String(secret)));
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences preferences, Map<String, ?> snapshot) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> item : snapshot.entrySet()) {
            Object value = item.getValue();
            if (value instanceof String string) editor.putString(item.getKey(), string);
            else if (value instanceof Boolean bool) editor.putBoolean(item.getKey(), bool);
            else if (value instanceof Integer integer) editor.putInt(item.getKey(), integer);
            else if (value instanceof Long number) editor.putLong(item.getKey(), number);
            else if (value instanceof Float number) editor.putFloat(item.getKey(), number);
            else if (value instanceof Set<?>) {
                editor.putStringSet(item.getKey(), (Set<String>) value);
            } else {
                throw new AssertionError("unsupported preference type");
            }
        }
        assertTrue(editor.commit());
    }

    private static boolean allZero(char[] value) {
        for (char character : value) if (character != '\0') return false;
        return true;
    }

    private static void deleteAlias() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(TEST_ALIAS)) keyStore.deleteEntry(TEST_ALIAS);
    }

    private static final class SequenceIds implements SecretStore.IdSource {
        private int next;

        @Override
        public String nextId() {
            return "sec_" + String.format("%032x", ++next);
        }
    }

    private static final class AndroidTestStorage implements SecretStore.Storage {
        private final SecurePreferences preferences;

        private AndroidTestStorage(SecurePreferences preferences) {
            this.preferences = preferences;
        }

        @Override
        public Map<String, ?> snapshot() {
            return preferences.snapshot();
        }

        @Override
        public String protect(char[] plaintext) {
            return preferences.prepareStored(plaintext);
        }

        @Override
        public char[] decrypt(String encoded) throws Exception {
            return preferences.decryptStored(encoded);
        }

        @Override
        public void commit(Map<String, String> values, Set<String> removals) {
            preferences.commitStored(values, removals);
        }
    }
}
