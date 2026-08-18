package com.opentypeless.android.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.opentypeless.android.config.GlobalConfig;
import com.opentypeless.android.config.SecretRef;
import com.opentypeless.android.security.SecretStore;
import com.opentypeless.android.security.SecurePreferences;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class SettingsRepository {
    private static final String STORE = "opentypeless_settings";
    private static final String TRANSACTION_STORE = "opentypeless_settings_transaction";
    private static final String STT_KEY = "stt_api_key";
    private static final String STREAMING_KEY = "streaming_api_key";
    private static final String LLM_KEY = "llm_api_key";
    private static final String REVISION = "settings_revision";
    private static final String TX_PENDING = "pending";
    private static final String TX_PREFIX = "old_";
    private static final String TX_STT_SECRET = "old_stt_secret";
    private static final String TX_STREAMING_SECRET = "old_streaming_secret";
    private static final String TX_LLM_SECRET = "old_llm_secret";
    private static final String TX_STT_REF = "old_stt_ref";
    private static final String TX_STREAMING_REF = "old_streaming_ref";
    private static final String TX_LLM_REF = "old_llm_ref";
    private static final int MAX_STORED_SECRET_UTF16_UNITS = 32_768;
    private static final Set<String> JOURNAL_KEYS = Set.of(
            TX_PENDING,
            key("recognition_backend"),
            key("stt_base_url"),
            key("stt_model"),
            key("streaming_base_url"),
            key("streaming_model"),
            key("streaming_vocabulary_id"),
            key("language"),
            key("default_mode"),
            key("polish_enabled"),
            key("llm_base_url"),
            key("llm_model"),
            key("target_language"),
            key("custom_instructions"),
            key("personalization_enabled"),
            key("history_enabled"),
            key("send_context"),
            key("max_recording_seconds"),
            key(REVISION),
            TX_STT_SECRET,
            TX_STREAMING_SECRET,
            TX_LLM_SECRET,
            TX_STT_REF,
            TX_STREAMING_REF,
            TX_LLM_REF);
    private static final Object TRANSACTION_LOCK = new Object();

    private final SharedPreferences preferences;
    private final SharedPreferences transactionPreferences;
    private final SecurePreferences secrets;
    private final SecretStore secretStore;
    private final Context context;
    private volatile AppSettings cached;
    private volatile long cachedRevision = Long.MIN_VALUE;

    public SettingsRepository(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        transactionPreferences = this.context.getSharedPreferences(
                TRANSACTION_STORE,
                Context.MODE_PRIVATE);
        secrets = new SecurePreferences(this.context);
        secretStore = new SecretStore(this.context);
    }

    public AppSettings load() {
        synchronized (TRANSACTION_LOCK) {
            recoverIfNeeded();
            LegacyAppSettingsMigration.migrate(preferences, defaultBackend());
            secretStore.migrateLegacy();
            long revision = preferences.getLong(REVISION, 0L);
            AppSettings existing = cached;
            if (existing != null && cachedRevision == revision) return existing;
            AppSettings loaded = readSettings(
                    preferences,
                    secrets.get(STT_KEY),
                    secrets.get(STREAMING_KEY),
                    secrets.get(LLM_KEY));
            cached = loaded;
            cachedRevision = revision;
            return loaded;
        }
    }

    /** Returns the validated CFG-006 shadow without making it runtime execution authority. */
    public GlobalConfig loadMigratedGlobalConfig() {
        synchronized (TRANSACTION_LOCK) {
            recoverIfNeeded();
            return LegacyAppSettingsMigration.migrate(preferences, defaultBackend());
        }
    }

    /** Returns the validated CFG-008 refs without exposing or changing runtime credentials. */
    public SecretStore.LegacyRefs loadMigratedSecretRefs() {
        synchronized (TRANSACTION_LOCK) {
            recoverIfNeeded();
            return secretStore.migrateLegacy();
        }
    }

    public ProcessingMode loadDefaultMode() {
        synchronized (TRANSACTION_LOCK) {
            recoverIfNeeded();
            return ProcessingMode.fromStored(preferences.getString(
                    "default_mode", ProcessingMode.AUTO.name()));
        }
    }

    /** Reads the other non-secret default needed by the app-profile editor. */
    public String loadTargetLanguage() {
        synchronized (TRANSACTION_LOCK) {
            recoverIfNeeded();
            return preferences.getString("target_language", "English");
        }
    }

    /** Reads the non-secret history switch without touching Android Keystore. */
    public boolean loadHistoryEnabled() {
        synchronized (TRANSACTION_LOCK) {
            recoverIfNeeded();
            return preferences.getBoolean("history_enabled", false);
        }
    }

    public void save(AppSettings settings) {
        validate(settings);
        AppSettings normalized = normalize(settings);
        // Encryption is prepared before the journal or either durable value store is changed.
        String preparedSttKey = secrets.prepare(normalized.sttApiKey());
        String preparedStreamingKey = secrets.prepare(normalized.streamingApiKey());
        String preparedLlmKey = secrets.prepare(normalized.llmApiKey());

        synchronized (TRANSACTION_LOCK) {
            recoverIfNeeded();
            // Refuse unknown or corrupt migration targets before creating a save journal.
            LegacyAppSettingsMigration.migrate(preferences, defaultBackend());
            long currentRevision = preferences.getLong(REVISION, 0L);
            if (currentRevision < 0L || currentRevision == Long.MAX_VALUE) {
                throw transactionFailure(TransactionFailure.CORRUPT_STATE);
            }
            long newRevision = Math.max(System.nanoTime(), currentRevision + 1L);
            if (newRevision < 0L) {
                throw transactionFailure(TransactionFailure.CORRUPT_STATE);
            }
            EnumMap<SecretStore.LegacySlot, String> preparedSecrets = legacySecrets(
                    preparedSttKey,
                    preparedStreamingKey,
                    preparedLlmKey);
            SettingsSaveTransaction.execute(new SaveSteps(
                    normalized,
                    newRevision,
                    preparedSecrets));
            cached = normalized;
            cachedRevision = newRevision;
        }
    }

    private void recoverIfNeeded() {
        boolean pending = transactionPreferences.getBoolean(TX_PENDING, false);
        SettingsSaveTransaction.recover(pending, new RecoverySteps());
        if (pending) {
            cached = null;
            cachedRevision = Long.MIN_VALUE;
        }
    }

    @SuppressLint("ApplySharedPref") // The journal must be durable before either value store changes.
    private RecoveryState writeRecoveryJournal() {
        RecoveryState before = captureCurrentState();
        SharedPreferences.Editor editor = transactionPreferences.edit().clear()
                .putBoolean(TX_PENDING, true)
                .putString(key("recognition_backend"), before.settings.recognitionBackend().name())
                .putString(key("stt_base_url"), before.settings.sttBaseUrl())
                .putString(key("stt_model"), before.settings.sttModel())
                .putString(key("streaming_base_url"), before.settings.streamingBaseUrl())
                .putString(key("streaming_model"), before.settings.streamingModel())
                .putString(
                        key("streaming_vocabulary_id"),
                        before.settings.streamingVocabularyId())
                .putString(key("language"), before.settings.language())
                .putString(key("default_mode"), before.settings.defaultMode().name())
                .putBoolean(key("polish_enabled"), before.settings.polishEnabled())
                .putString(key("llm_base_url"), before.settings.llmBaseUrl())
                .putString(key("llm_model"), before.settings.llmModel())
                .putString(key("target_language"), before.settings.targetLanguage())
                .putString(key("custom_instructions"), before.settings.customInstructions())
                .putBoolean(
                        key("personalization_enabled"),
                        before.settings.personalizationEnabled())
                .putBoolean(key("history_enabled"), before.settings.historyEnabled())
                .putBoolean(key("send_context"), before.settings.sendContext())
                .putInt(
                        key("max_recording_seconds"),
                        before.settings.maxRecordingSeconds())
                .putLong(key(REVISION), before.revision)
                .putString(TX_STT_SECRET, before.secrets.get(SecretStore.LegacySlot.STT_ASR))
                .putString(
                        TX_STREAMING_SECRET,
                        before.secrets.get(SecretStore.LegacySlot.STREAMING_ASR))
                .putString(TX_LLM_SECRET, before.secrets.get(SecretStore.LegacySlot.LLM))
                .putString(TX_STT_REF, opaqueId(before.refs.sttAsr()))
                .putString(TX_STREAMING_REF, opaqueId(before.refs.streamingAsr()))
                .putString(TX_LLM_REF, opaqueId(before.refs.llm()));
        if (!editor.commit()) throw transactionFailure(TransactionFailure.JOURNAL_COMMIT_FAILED);
        RecoveryState readback = readRecoveryJournal();
        if (!before.sameState(readback)) {
            throw transactionFailure(TransactionFailure.JOURNAL_READBACK_FAILED);
        }
        return before;
    }

    private RecoveryState captureCurrentState() {
        AppSettings settings = readSettings(preferences, "", "", "");
        long revision = preferences.getLong(REVISION, 0L);
        if (revision < 0L) throw transactionFailure(TransactionFailure.CORRUPT_STATE);
        SecretStore.LegacyRefs refs = secretStore.migrateLegacy();
        EnumMap<SecretStore.LegacySlot, String> stored = legacySecrets(
                secretStore.storedLegacyValue(SecretStore.LegacySlot.STT_ASR),
                secretStore.storedLegacyValue(SecretStore.LegacySlot.STREAMING_ASR),
                secretStore.storedLegacyValue(SecretStore.LegacySlot.LLM));
        validateState(settings, stored, refs);
        return new RecoveryState(settings, revision, stored, refs);
    }

    private RecoveryState readRecoveryJournal() {
        Map<String, ?> raw = transactionPreferences.getAll();
        if (!raw.keySet().equals(JOURNAL_KEYS)
                || !Boolean.TRUE.equals(raw.get(TX_PENDING))) {
            throw transactionFailure(TransactionFailure.CORRUPT_JOURNAL);
        }
        AppSettings settings;
        long revision;
        EnumMap<SecretStore.LegacySlot, String> stored;
        SecretStore.LegacyRefs refs;
        try {
            settings = new AppSettings(
                    RecognitionBackend.valueOf(requiredString(raw, key("recognition_backend"))),
                    requiredString(raw, key("stt_base_url")),
                    "",
                    requiredString(raw, key("stt_model")),
                    requiredString(raw, key("streaming_base_url")),
                    "",
                    requiredString(raw, key("streaming_model")),
                    requiredString(raw, key("streaming_vocabulary_id")),
                    requiredString(raw, key("language")),
                    ProcessingMode.valueOf(requiredString(raw, key("default_mode"))),
                    requiredBoolean(raw, key("polish_enabled")),
                    requiredString(raw, key("llm_base_url")),
                    "",
                    requiredString(raw, key("llm_model")),
                    requiredString(raw, key("target_language")),
                    requiredString(raw, key("custom_instructions")),
                    requiredBoolean(raw, key("personalization_enabled")),
                    requiredBoolean(raw, key("history_enabled")),
                    requiredBoolean(raw, key("send_context")),
                    requiredInteger(raw, key("max_recording_seconds")));
            revision = requiredLong(raw, key(REVISION));
            stored = legacySecrets(
                    requiredString(raw, TX_STT_SECRET),
                    requiredString(raw, TX_STREAMING_SECRET),
                    requiredString(raw, TX_LLM_SECRET));
            refs = new SecretStore.LegacyRefs(
                    legacyRef(
                            SecretStore.LegacySlot.STT_ASR,
                            requiredString(raw, TX_STT_REF)),
                    legacyRef(
                            SecretStore.LegacySlot.STREAMING_ASR,
                            requiredString(raw, TX_STREAMING_REF)),
                    legacyRef(
                            SecretStore.LegacySlot.LLM,
                            requiredString(raw, TX_LLM_REF)));
        } catch (RuntimeException error) {
            if (error instanceof SettingsTransactionException) throw error;
            throw transactionFailure(TransactionFailure.CORRUPT_JOURNAL);
        }
        if (revision < 0L) throw transactionFailure(TransactionFailure.CORRUPT_JOURNAL);
        validateState(settings, stored, refs);
        return new RecoveryState(settings, revision, stored, refs);
    }

    private void restoreRecoveryJournal() {
        RecoveryState previous = readRecoveryJournal();
        secretStore.restoreLegacyPrepared(previous.secrets, previous.refs);
        commitSettings(previous.settings, previous.revision);
    }

    private void verifyRestoredJournal() {
        RecoveryState previous = readRecoveryJournal();
        verifyState(previous.settings, previous.revision, previous.secrets, previous.refs);
    }

    @SuppressLint("ApplySharedPref") // Commit is the transaction boundary, not a UI preference write.
    private void clearRecoveryJournal() {
        if (!transactionPreferences.edit().clear().commit()) {
            throw transactionFailure(TransactionFailure.JOURNAL_CLEAR_FAILED);
        }
        if (!transactionPreferences.getAll().isEmpty()) {
            throw transactionFailure(TransactionFailure.JOURNAL_CLEAR_FAILED);
        }
    }

    private AppSettings readSettings(
            SharedPreferences source,
            String sttApiKey,
            String streamingApiKey,
            String llmApiKey) {
        String prefix = source == transactionPreferences ? TX_PREFIX : "";
        return new AppSettings(
                RecognitionBackend.fromStored(source.getString(
                        prefix + "recognition_backend", defaultBackend().name())),
                source.getString(prefix + "stt_base_url", "https://api.openai.com/v1"),
                sttApiKey,
                source.getString(prefix + "stt_model", "whisper-1"),
                source.getString(
                        prefix + "streaming_base_url",
                        "wss://dashscope.aliyuncs.com/api-ws/v1/inference"),
                streamingApiKey,
                source.getString(prefix + "streaming_model", "paraformer-realtime-v2"),
                source.getString(prefix + "streaming_vocabulary_id", ""),
                source.getString(prefix + "language", defaultLanguage()),
                ProcessingMode.fromStored(source.getString(
                        prefix + "default_mode", ProcessingMode.AUTO.name())),
                source.getBoolean(prefix + "polish_enabled", false),
                source.getString(prefix + "llm_base_url", "https://api.openai.com/v1"),
                llmApiKey,
                source.getString(prefix + "llm_model", "gpt-4o-mini"),
                source.getString(prefix + "target_language", "English"),
                source.getString(prefix + "custom_instructions", ""),
                source.getBoolean(prefix + "personalization_enabled", true),
                source.getBoolean(prefix + "history_enabled", false),
                source.getBoolean(prefix + "send_context", false),
                source.getInt(prefix + "max_recording_seconds", 180));
    }

    @SuppressLint("ApplySharedPref") // A failed disk commit must synchronously trigger rollback.
    private void commitSettings(AppSettings settings, long revision) {
        SharedPreferences.Editor editor = preferences.edit()
                .putString("recognition_backend", settings.recognitionBackend().name())
                .putString("stt_base_url", settings.sttBaseUrl())
                .putString("stt_model", settings.sttModel())
                .putString("streaming_base_url", settings.streamingBaseUrl())
                .putString("streaming_model", settings.streamingModel())
                .putString("streaming_vocabulary_id", settings.streamingVocabularyId())
                .putString("language", settings.language())
                .putString("default_mode", settings.defaultMode().name())
                .putBoolean("polish_enabled", settings.polishEnabled())
                .putString("llm_base_url", settings.llmBaseUrl())
                .putString("llm_model", settings.llmModel())
                .putString("target_language", settings.targetLanguage())
                .putString("custom_instructions", settings.customInstructions())
                .putBoolean("personalization_enabled", settings.personalizationEnabled())
                .putBoolean("history_enabled", settings.historyEnabled())
                .putBoolean("send_context", settings.sendContext())
                .putInt("max_recording_seconds", settings.boundedMaxRecordingSeconds())
                .putLong(REVISION, revision);
        LegacyAppSettingsMigration.writeProjection(editor, settings, revision);
        boolean committed = editor.commit();
        if (!committed) throw transactionFailure(TransactionFailure.SETTINGS_COMMIT_FAILED);
    }

    private void verifyState(
            AppSettings expectedSettings,
            long expectedRevision,
            Map<SecretStore.LegacySlot, String> expectedSecrets,
            SecretStore.LegacyRefs expectedRefs) {
        try {
            verifyStoredSettings(expectedSettings, expectedRevision);
            LegacyAppSettingsMigration.readValidated(preferences, defaultBackend());
            secretStore.verifyLegacyPrepared(expectedSecrets, expectedRefs);
        } catch (SettingsTransactionException error) {
            throw error;
        } catch (RuntimeException error) {
            throw transactionFailure(TransactionFailure.STATE_READBACK_FAILED);
        }
    }

    private void verifyStoredSettings(AppSettings expected, long expectedRevision) {
        Map<String, ?> raw = preferences.getAll();
        if (expectedRevision < 0L
                || !Objects.equals(expected.recognitionBackend().name(), raw.get("recognition_backend"))
                || !Objects.equals(expected.sttBaseUrl(), raw.get("stt_base_url"))
                || !Objects.equals(expected.sttModel(), raw.get("stt_model"))
                || !Objects.equals(expected.streamingBaseUrl(), raw.get("streaming_base_url"))
                || !Objects.equals(expected.streamingModel(), raw.get("streaming_model"))
                || !Objects.equals(
                        expected.streamingVocabularyId(),
                        raw.get("streaming_vocabulary_id"))
                || !Objects.equals(expected.language(), raw.get("language"))
                || !Objects.equals(expected.defaultMode().name(), raw.get("default_mode"))
                || !Objects.equals(expected.polishEnabled(), raw.get("polish_enabled"))
                || !Objects.equals(expected.llmBaseUrl(), raw.get("llm_base_url"))
                || !Objects.equals(expected.llmModel(), raw.get("llm_model"))
                || !Objects.equals(expected.targetLanguage(), raw.get("target_language"))
                || !Objects.equals(expected.customInstructions(), raw.get("custom_instructions"))
                || !Objects.equals(
                        expected.personalizationEnabled(),
                        raw.get("personalization_enabled"))
                || !Objects.equals(expected.historyEnabled(), raw.get("history_enabled"))
                || !Objects.equals(expected.sendContext(), raw.get("send_context"))
                || !Objects.equals(expected.boundedMaxRecordingSeconds(), raw.get("max_recording_seconds"))
                || !Objects.equals(expectedRevision, raw.get(REVISION))) {
            throw transactionFailure(TransactionFailure.STATE_READBACK_FAILED);
        }
    }

    private static EnumMap<SecretStore.LegacySlot, String> legacySecrets(
            String stt,
            String streaming,
            String llm) {
        EnumMap<SecretStore.LegacySlot, String> values =
                new EnumMap<>(SecretStore.LegacySlot.class);
        values.put(SecretStore.LegacySlot.STT_ASR, Objects.requireNonNull(stt, "stt"));
        values.put(
                SecretStore.LegacySlot.STREAMING_ASR,
                Objects.requireNonNull(streaming, "streaming"));
        values.put(SecretStore.LegacySlot.LLM, Objects.requireNonNull(llm, "llm"));
        return values;
    }

    private static void validateState(
            AppSettings settings,
            EnumMap<SecretStore.LegacySlot, String> stored,
            SecretStore.LegacyRefs refs) {
        try {
            validate(settings);
            if (settings.maxRecordingSeconds() != settings.boundedMaxRecordingSeconds()) {
                throw transactionFailure(TransactionFailure.CORRUPT_STATE);
            }
            for (SecretStore.LegacySlot slot : SecretStore.LegacySlot.values()) {
                String encoded = Objects.requireNonNull(stored.get(slot), "stored secret");
                if (encoded.length() > MAX_STORED_SECRET_UTF16_UNITS) {
                    throw transactionFailure(TransactionFailure.CORRUPT_STATE);
                }
                boolean expectedRef = !encoded.isEmpty();
                if (reference(refs, slot).isPresent() != expectedRef) {
                    throw transactionFailure(TransactionFailure.CORRUPT_STATE);
                }
            }
        } catch (SettingsTransactionException error) {
            throw error;
        } catch (RuntimeException error) {
            throw transactionFailure(TransactionFailure.CORRUPT_STATE);
        }
    }

    private static Optional<SecretRef> reference(
            SecretStore.LegacyRefs refs,
            SecretStore.LegacySlot slot) {
        Objects.requireNonNull(refs, "refs");
        return switch (slot) {
            case STT_ASR -> refs.sttAsr();
            case STREAMING_ASR -> refs.streamingAsr();
            case LLM -> refs.llm();
        };
    }

    private static String opaqueId(Optional<SecretRef> reference) {
        return Objects.requireNonNull(reference, "reference")
                .map(SecretRef::opaqueId)
                .orElse("");
    }

    private static Optional<SecretRef> legacyRef(
            SecretStore.LegacySlot slot,
            String opaqueId) {
        if (opaqueId.isEmpty()) return Optional.empty();
        try {
            return Optional.of(new SecretRef(slot.kind(), opaqueId));
        } catch (RuntimeException error) {
            throw transactionFailure(TransactionFailure.CORRUPT_JOURNAL);
        }
    }

    private static String requiredString(Map<String, ?> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof String string)) {
            throw transactionFailure(TransactionFailure.CORRUPT_JOURNAL);
        }
        return string;
    }

    private static boolean requiredBoolean(Map<String, ?> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof Boolean bool)) {
            throw transactionFailure(TransactionFailure.CORRUPT_JOURNAL);
        }
        return bool;
    }

    private static int requiredInteger(Map<String, ?> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof Integer integer)) {
            throw transactionFailure(TransactionFailure.CORRUPT_JOURNAL);
        }
        return integer;
    }

    private static long requiredLong(Map<String, ?> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof Long number)) {
            throw transactionFailure(TransactionFailure.CORRUPT_JOURNAL);
        }
        return number;
    }

    private static SettingsTransactionException transactionFailure(TransactionFailure failure) {
        return new SettingsTransactionException(failure);
    }

    private final class SaveSteps implements SettingsSaveTransaction.Steps {
        private final AppSettings settings;
        private final long revision;
        private final EnumMap<SecretStore.LegacySlot, String> preparedSecrets;
        private SecretStore.LegacyRefs committedRefs;

        private SaveSteps(
                AppSettings settings,
                long revision,
                EnumMap<SecretStore.LegacySlot, String> preparedSecrets) {
            this.settings = Objects.requireNonNull(settings, "settings");
            this.revision = revision;
            this.preparedSecrets = new EnumMap<>(preparedSecrets);
        }

        @Override
        public void createJournal() {
            writeRecoveryJournal();
        }

        @Override
        public void writeSecrets() {
            committedRefs = secretStore.commitLegacyPrepared(preparedSecrets);
        }

        @Override
        public void writeSettings() {
            commitSettings(settings, revision);
        }

        @Override
        public void verifyCommitted() {
            if (committedRefs == null) {
                throw transactionFailure(TransactionFailure.STATE_READBACK_FAILED);
            }
            verifyState(settings, revision, preparedSecrets, committedRefs);
        }

        @Override
        public void restoreFromJournal() {
            restoreRecoveryJournal();
        }

        @Override
        public void verifyRestored() {
            verifyRestoredJournal();
        }

        @Override
        public void clearJournal() {
            clearRecoveryJournal();
        }
    }

    private final class RecoverySteps implements SettingsSaveTransaction.Recovery {
        @Override
        public void restoreFromJournal() {
            restoreRecoveryJournal();
        }

        @Override
        public void verifyRestored() {
            verifyRestoredJournal();
        }

        @Override
        public void clearJournal() {
            clearRecoveryJournal();
        }
    }

    private static final class RecoveryState {
        private final AppSettings settings;
        private final long revision;
        private final EnumMap<SecretStore.LegacySlot, String> secrets;
        private final SecretStore.LegacyRefs refs;

        private RecoveryState(
                AppSettings settings,
                long revision,
                EnumMap<SecretStore.LegacySlot, String> secrets,
                SecretStore.LegacyRefs refs) {
            this.settings = Objects.requireNonNull(settings, "settings");
            this.revision = revision;
            this.secrets = new EnumMap<>(secrets);
            this.refs = Objects.requireNonNull(refs, "refs");
        }

        private boolean sameState(RecoveryState other) {
            return other != null
                    && revision == other.revision
                    && settings.equals(other.settings)
                    && secrets.equals(other.secrets)
                    && refs.equals(other.refs);
        }

        @Override
        public String toString() {
            return "RecoveryState{settings=<redacted>, revision=<redacted>, secrets=<redacted>}";
        }
    }

    public enum TransactionFailure {
        CORRUPT_STATE,
        CORRUPT_JOURNAL,
        JOURNAL_COMMIT_FAILED,
        JOURNAL_READBACK_FAILED,
        SETTINGS_COMMIT_FAILED,
        STATE_READBACK_FAILED,
        JOURNAL_CLEAR_FAILED
    }

    /** Stable content-free failure for a settings save or recovery transaction. */
    public static final class SettingsTransactionException extends IllegalStateException {
        private final TransactionFailure failure;

        private SettingsTransactionException(TransactionFailure failure) {
            super(Objects.requireNonNull(failure, "failure").name());
            this.failure = failure;
        }

        public TransactionFailure failure() {
            return failure;
        }

        @Override
        public String toString() {
            return "SettingsTransactionException{failure=" + failure + "}";
        }
    }

    private RecognitionBackend defaultBackend() {
        // Settings are loaded on latency-sensitive paths, including IME startup. Android/OEM
        // speech-service discovery can block for seconds while a vendor service is cold. Pick a
        // deterministic privacy-first default here and inspect real availability asynchronously in
        // the settings screen and at recognition start.
        return defaultBackendForSdk(Build.VERSION.SDK_INT);
    }

    static RecognitionBackend defaultBackendForSdk(int sdkInt) {
        return sdkInt >= Build.VERSION_CODES.S
                ? RecognitionBackend.SYSTEM_ON_DEVICE
                : RecognitionBackend.SYSTEM_DEFAULT;
    }

    private static AppSettings normalize(AppSettings settings) {
        return new AppSettings(
                settings.recognitionBackend(),
                safe(settings.sttBaseUrl()).trim(),
                safe(settings.sttApiKey()).trim(),
                safe(settings.sttModel()).trim(),
                safe(settings.streamingBaseUrl()).trim(),
                safe(settings.streamingApiKey()).trim(),
                safe(settings.streamingModel()).trim(),
                safe(settings.streamingVocabularyId()).trim(),
                safe(settings.language()).trim(),
                settings.defaultMode(),
                settings.polishEnabled(),
                safe(settings.llmBaseUrl()).trim(),
                safe(settings.llmApiKey()).trim(),
                safe(settings.llmModel()).trim(),
                safe(settings.targetLanguage()).trim(),
                safe(settings.customInstructions()).trim(),
                settings.personalizationEnabled(),
                settings.historyEnabled(),
                settings.sendContext(),
                settings.boundedMaxRecordingSeconds());
    }

    private static void validate(AppSettings settings) {
        if (settings == null) throw new IllegalArgumentException("Settings are required");
        if (settings.recognitionBackend() == null) {
            throw new IllegalArgumentException("Recognition backend is required");
        }
        if (settings.defaultMode() == null) {
            throw new IllegalArgumentException("Default mode is required");
        }
        bounded(settings.sttBaseUrl(), 2_048, "STT base URL", false);
        bounded(settings.streamingBaseUrl(), 2_048, "Streaming WebSocket URL", false);
        bounded(settings.llmBaseUrl(), 2_048, "LLM base URL", false);
        bounded(settings.sttModel(), 200, "STT model", true);
        bounded(settings.streamingModel(), 200, "Streaming model", true);
        bounded(settings.streamingVocabularyId(), 200, "Streaming vocabulary ID", true);
        bounded(settings.llmModel(), 200, "LLM model", true);
        bounded(settings.language(), 80, "Language", true);
        bounded(settings.targetLanguage(), 80, "Target language", true);
        bounded(settings.customInstructions(), 1_000, "Writing preference", false);
        bounded(settings.sttApiKey(), 4_096, "STT API key", true);
        bounded(settings.streamingApiKey(), 4_096, "Streaming API key", true);
        bounded(settings.llmApiKey(), 4_096, "LLM API key", true);
    }

    private static void bounded(
            String value,
            int maximumCodePoints,
            String label,
            boolean singleLine) {
        String safe = safe(value);
        if (safe.codePointCount(0, safe.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(label + " is too long");
        }
        if (safe.indexOf('\u0000') >= 0
                || (singleLine && safe.codePoints().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException(label + " contains unsupported control characters");
        }
    }

    private static String key(String name) {
        return TX_PREFIX + name;
    }

    /** Chinese devices default to their concrete locale; other devices keep auto detection. */
    static String defaultLanguage() {
        Locale locale = Locale.getDefault();
        return locale.getLanguage().equalsIgnoreCase("zh") ? locale.toLanguageTag() : "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
