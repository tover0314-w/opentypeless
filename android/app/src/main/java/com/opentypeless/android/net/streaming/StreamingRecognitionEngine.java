package com.opentypeless.android.net.streaming;

import com.opentypeless.android.audio.AudioRecorder;
import com.opentypeless.android.audio.RecordingSession;
import com.opentypeless.android.settings.AppSettings;

/** Provider-neutral realtime recognition boundary used by Voice Core. */
public interface StreamingRecognitionEngine {
    interface Listener {
        default void onFinishing() {}
        void onTranscript(String stableText, String unstableText);
    }

    record Result(
            String text,
            long durationMs,
            boolean reachedLimit,
            boolean autoStopped) {}

    Result recognize(
            AppSettings settings,
            AudioRecorder recorder,
            RecordingSession recordingSession,
            AudioRecorder.CaptureListener captureListener,
            Listener listener);

    void cancelActiveSession();

    void shutdown();
}
