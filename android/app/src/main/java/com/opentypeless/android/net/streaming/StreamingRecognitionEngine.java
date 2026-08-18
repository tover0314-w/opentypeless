package com.opentypeless.android.net.streaming;

import com.opentypeless.android.audio.AudioCapture;
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
            AudioCapture audioCapture,
            AudioCapture.Session captureSession,
            AudioCapture.CaptureListener captureListener,
            Listener listener);

    void cancelActiveSession();

    void shutdown();
}
