package com.opentypeless.android.diagnostics;

import android.util.Log;

/** Transcript-free milestone log for ADB-assisted device diagnosis. */
public final class VoiceDiagnosticsLog {
    public static final String TAG = "OpenTypelessVoice";

    private VoiceDiagnosticsLog() {}

    public static void emit(RecognitionDiagnostics.Snapshot snapshot) {
        if (snapshot == null) return;
        Log.i(TAG,
                "session=" + snapshot.sessionId()
                        + " status=" + snapshot.status()
                        + " selected=" + snapshot.route().selectedBackend()
                        + " actual=" + snapshot.route().actualBackend()
                        + " fallback=" + snapshot.route().fallbackReason()
                        + " ready_ms=" + snapshot.readyLatencyMs()
                        + " first_partial_ms=" + snapshot.firstPartialLatencyMs()
                        + " release_to_raw_ms=" + snapshot.releaseToRawFinalLatencyMs()
                        + " text_processing_ms=" + snapshot.textProcessingLatencyMs()
                        + " release_to_terminal_ms=" + snapshot.releaseToTerminalLatencyMs()
                        + " terminal_ms=" + snapshot.terminalLatencyMs());
    }
}
