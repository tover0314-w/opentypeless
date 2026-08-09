package com.opentypeless.android.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.speech.SpeechRecognizer;

import com.opentypeless.android.security.SecurePreferences;

import java.util.Map;

public final class SettingsRepository {
    private static final String STORE = "opentypeless_settings";
    private static final String TRANSACTION_STORE = "opentypeless_settings_transaction";
    private static final String STT_KEY = "stt_api_key";
    private static final String LLM_KEY = "llm_api_key";
    private static final String REVISION = "settings_revision";
    private static final String TX_PENDING = "pending";
    private static final String TX_PREFIX = "old_";
    private static final String TX_STT_SECRET = "old_stt_secret";
    private static final String TX_LLM_SECRET = "old_llm_secret";
    private static final Object TRANSACTION_LOCK = new Object();

    private final SharedPreferences preferences;
    private final SharedPreferences transactionPreferences;
    private final SecurePreferences secrets;
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
    }

    public AppSettings load() {
        synchronized (TRANSACTION_LOCK) {
            recoverIfNeeded();
            long revision = preferences.getLong(REVISION, 0L);
            AppSettings existing = cached;
            if (existing != null && cachedRevision == revision) return existing;
            AppSettings loaded = readSettings(preferences, secrets.get(STT_KEY), secrets.get(LLM_KEY));
            cached = loaded;
            cachedRevision = revision;
            return loaded;
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
        String preparedLlmKey = secrets.prepare(normalized.llmApiKey());

        synchronized (TRANSACTION_LOCK) {
            recoverIfNeeded();
            long newRevision = Math.max(
                    System.nanoTime(),
                    preferences.getLong(REVISION, 0L) + 1L);
            SettingsSaveTransaction.execute(new SettingsSaveTransaction.Steps() {
                @Override
                public void createJournal() {
                    writeRecoveryJournal();
                }

                @Override
                public void writeSecrets() {
                    secrets.commitPrepared(Map.of(
                            STT_KEY, preparedSttKey,
                            LLM_KEY, preparedLlmKey));
                }

                @Override
                public void writeSettings() {
                    commitSettings(normalized, newRevision);
                }

                @Override
                public void clearJournal() {
                    clearRecoveryJournal();
                }

                @Override
                public void restoreFromJournal() {
                    restoreRecoveryJournal();
                }
            });
            cached = normalized;
            cachedRevision = newRevision;
        }
    }

    private void recoverIfNeeded() {
        boolean pending = transactionPreferences.getBoolean(TX_PENDING, false);
        SettingsSaveTransaction.recover(
                pending,
                this::restoreRecoveryJournal,
                this::clearRecoveryJournal);
        if (pending) {
            cached = null;
            cachedRevision = Long.MIN_VALUE;
        }
    }

    @SuppressLint("ApplySharedPref") // The journal must be durable before either value store changes.
    private void writeRecoveryJournal() {
        SharedPreferences.Editor editor = transactionPreferences.edit().clear()
                .putBoolean(TX_PENDING, true)
                .putString(key("recognition_backend"), preferences.getString(
                        "recognition_backend", defaultBackend().name()))
                .putString(key("stt_base_url"), preferences.getString(
                        "stt_base_url", "https://api.openai.com/v1"))
                .putString(key("stt_model"), preferences.getString("stt_model", "whisper-1"))
                .putString(key("language"), preferences.getString("language", ""))
                .putString(key("default_mode"), preferences.getString(
                        "default_mode", ProcessingMode.AUTO.name()))
                .putBoolean(key("polish_enabled"), preferences.getBoolean(
                        "polish_enabled", false))
                .putString(key("llm_base_url"), preferences.getString(
                        "llm_base_url", "https://api.openai.com/v1"))
                .putString(key("llm_model"), preferences.getString("llm_model", "gpt-4o-mini"))
                .putString(key("target_language"), preferences.getString(
                        "target_language", "English"))
                .putString(key("custom_instructions"), preferences.getString(
                        "custom_instructions", ""))
                .putBoolean(key("personalization_enabled"), preferences.getBoolean(
                        "personalization_enabled", true))
                .putBoolean(key("history_enabled"), preferences.getBoolean(
                        "history_enabled", false))
                .putBoolean(key("send_context"), preferences.getBoolean("send_context", false))
                .putInt(key("max_recording_seconds"), preferences.getInt(
                        "max_recording_seconds", 180))
                .putLong(key(REVISION), preferences.getLong(REVISION, 0L))
                .putString(TX_STT_SECRET, secrets.storedValue(STT_KEY))
                .putString(TX_LLM_SECRET, secrets.storedValue(LLM_KEY));
        if (!editor.commit()) {
            throw new IllegalStateException("Unable to start settings transaction");
        }
    }

    private void restoreRecoveryJournal() {
        if (!transactionPreferences.getBoolean(TX_PENDING, false)) return;
        secrets.commitPrepared(Map.of(
                STT_KEY, transactionPreferences.getString(TX_STT_SECRET, ""),
                LLM_KEY, transactionPreferences.getString(TX_LLM_SECRET, "")));
        AppSettings previous = readSettings(transactionPreferences, "", "");
        commitSettings(previous, transactionPreferences.getLong(key(REVISION), 0L));
    }

    @SuppressLint("ApplySharedPref") // Commit is the transaction boundary, not a UI preference write.
    private void clearRecoveryJournal() {
        if (!transactionPreferences.edit().clear().commit()) {
            throw new IllegalStateException("Unable to finish settings transaction");
        }
    }

    private AppSettings readSettings(
            SharedPreferences source,
            String sttApiKey,
            String llmApiKey) {
        String prefix = source == transactionPreferences ? TX_PREFIX : "";
        return new AppSettings(
                RecognitionBackend.fromStored(source.getString(
                        prefix + "recognition_backend", defaultBackend().name())),
                source.getString(prefix + "stt_base_url", "https://api.openai.com/v1"),
                sttApiKey,
                source.getString(prefix + "stt_model", "whisper-1"),
                source.getString(prefix + "language", ""),
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
        boolean committed = preferences.edit()
                .putString("recognition_backend", settings.recognitionBackend().name())
                .putString("stt_base_url", settings.sttBaseUrl())
                .putString("stt_model", settings.sttModel())
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
                .putLong(REVISION, revision)
                .commit();
        if (!committed) throw new IllegalStateException("Unable to store settings");
    }

    private RecognitionBackend defaultBackend() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                return RecognitionBackend.SYSTEM_ON_DEVICE;
            }
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                return RecognitionBackend.SYSTEM_DEFAULT;
            }
        } catch (RuntimeException ignored) {
            // Some vendor implementations throw before the speech service has finished booting.
        }
        return RecognitionBackend.OPENAI_COMPATIBLE;
    }

    private static AppSettings normalize(AppSettings settings) {
        return new AppSettings(
                settings.recognitionBackend(),
                safe(settings.sttBaseUrl()).trim(),
                safe(settings.sttApiKey()).trim(),
                safe(settings.sttModel()).trim(),
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
        bounded(settings.llmBaseUrl(), 2_048, "LLM base URL", false);
        bounded(settings.sttModel(), 200, "STT model", true);
        bounded(settings.llmModel(), 200, "LLM model", true);
        bounded(settings.language(), 80, "Language", true);
        bounded(settings.targetLanguage(), 80, "Target language", true);
        bounded(settings.customInstructions(), 1_000, "Writing preference", false);
        bounded(settings.sttApiKey(), 4_096, "STT API key", true);
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
                || (singleLine && (safe.indexOf('\r') >= 0 || safe.indexOf('\n') >= 0))) {
            throw new IllegalArgumentException(label + " contains unsupported control characters");
        }
    }

    private static String key(String name) {
        return TX_PREFIX + name;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
