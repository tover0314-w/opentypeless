package com.opentypeless.android.diagnostics;

import java.util.Locale;

/** Stable, redacted JSON export for one diagnostics snapshot. */
public final class RecognitionDiagnosticsJson {
    private RecognitionDiagnosticsJson() {}

    public static String encode(RecognitionDiagnostics.Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("Diagnostics snapshot is required");
        RecognitionRoute route = snapshot.route();
        return "{\n"
                + "  \"schema\": 1,\n"
                + "  \"session_id\": " + snapshot.sessionId() + ",\n"
                + "  \"started_at_epoch_ms\": " + snapshot.startedAtEpochMs() + ",\n"
                + "  \"selected_backend\": \"" + route.selectedBackend().name() + "\",\n"
                + "  \"actual_backend\": \"" + route.actualBackend().name() + "\",\n"
                + "  \"fallback_reason\": \"" + route.fallbackReason().name() + "\",\n"
                + "  \"privacy_boundary\": \"" + route.privacyBoundary().name() + "\",\n"
                + "  \"language_tag\": \"" + escape(snapshot.languageTag()) + "\",\n"
                + "  \"status\": \"" + snapshot.status().name() + "\",\n"
                + "  \"ready_latency_ms\": " + snapshot.readyLatencyMs() + ",\n"
                + "  \"first_partial_latency_ms\": "
                + snapshot.firstPartialLatencyMs() + ",\n"
                + "  \"terminal_latency_ms\": " + snapshot.terminalLatencyMs() + ",\n"
                + "  \"audio_duration_ms\": " + snapshot.audioDurationMs() + ",\n"
                + "  \"final_code_point_count\": " + snapshot.finalCodePointCount() + ",\n"
                + "  \"recovered_partial\": " + snapshot.recoveredPartial() + "\n"
                + "}\n";
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
