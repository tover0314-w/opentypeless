package com.opentypeless.android.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;

import com.opentypeless.android.settings.RecognitionBackend;

/** Stores only the newest redacted recognition trace in private app preferences. */
public final class RecognitionDiagnosticsStore {
    private static final String STORE = "opentypeless_recognition_diagnostics_v1";
    private static final Object PROCESS_LOCK = new Object();

    private final SharedPreferences preferences;

    public RecognitionDiagnosticsStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                STORE,
                Context.MODE_PRIVATE);
    }

    public void save(RecognitionDiagnostics.Snapshot snapshot) {
        if (snapshot == null) return;
        synchronized (PROCESS_LOCK) {
            long storedStart = preferences.getLong("started_at_epoch_ms", -1L);
            long storedSession = preferences.getLong("session_id", -1L);
            if (storedStart > snapshot.startedAtEpochMs()
                    || (storedStart == snapshot.startedAtEpochMs()
                    && storedSession > snapshot.sessionId())) {
                return;
            }
            preferences.edit()
                    .putInt("schema", 1)
                    .putLong("session_id", snapshot.sessionId())
                    .putLong("started_at_epoch_ms", snapshot.startedAtEpochMs())
                    .putString("selected_backend", snapshot.route().selectedBackend().name())
                    .putString("actual_backend", snapshot.route().actualBackend().name())
                    .putString("fallback_reason", snapshot.route().fallbackReason().name())
                    .putString("language_tag", snapshot.languageTag())
                    .putString("status", snapshot.status().name())
                    .putLong("ready_latency_ms", snapshot.readyLatencyMs())
                    .putLong("first_partial_latency_ms", snapshot.firstPartialLatencyMs())
                    .putLong("terminal_latency_ms", snapshot.terminalLatencyMs())
                    .putLong("audio_duration_ms", snapshot.audioDurationMs())
                    .putInt("final_code_point_count", snapshot.finalCodePointCount())
                    .putBoolean("recovered_partial", snapshot.recoveredPartial())
                    .apply();
        }
    }

    public RecognitionDiagnostics.Snapshot load() {
        synchronized (PROCESS_LOCK) {
            if (preferences.getInt("schema", 0) != 1) return null;
            try {
                RecognitionBackend selected = RecognitionBackend.valueOf(
                        preferences.getString("selected_backend", ""));
                RecognitionBackend actual = RecognitionBackend.valueOf(
                        preferences.getString("actual_backend", ""));
                RecognitionRoute.FallbackReason fallback = RecognitionRoute.FallbackReason.valueOf(
                        preferences.getString("fallback_reason", "NONE"));
                RecognitionDiagnostics.Status status = RecognitionDiagnostics.Status.valueOf(
                        preferences.getString("status", "ACTIVE"));
                return new RecognitionDiagnostics.Snapshot(
                        preferences.getLong("session_id", -1L),
                        preferences.getLong("started_at_epoch_ms", 0L),
                        new RecognitionRoute(selected, actual, fallback),
                        preferences.getString("language_tag", "und"),
                        status,
                        preferences.getLong("ready_latency_ms", -1L),
                        preferences.getLong("first_partial_latency_ms", -1L),
                        preferences.getLong("terminal_latency_ms", -1L),
                        preferences.getLong("audio_duration_ms", -1L),
                        preferences.getInt("final_code_point_count", -1),
                        preferences.getBoolean("recovered_partial", false));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    public void clear() {
        synchronized (PROCESS_LOCK) {
            preferences.edit().clear().apply();
        }
    }
}
