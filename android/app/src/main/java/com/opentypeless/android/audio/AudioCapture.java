package com.opentypeless.android.audio;

import android.content.Context;

/**
 * Bounded microphone-capture boundary used by the voice runtime.
 *
 * <p>The boundary owns microphone attribution, VAD-backed PCM capture, duration limits, and
 * session stop/cancel semantics. It deliberately has no network, text-processing, persistence,
 * or editor-writing surface.
 */
public interface AudioCapture {
    int SAMPLE_RATE = 16_000;

    /** Opaque, capture-owned lifecycle handle. */
    interface Session {
        boolean userControlledEndpointing();
    }

    interface CaptureListener {
        default void onReady() {}
        default void onBeginningOfSpeech() {}

        /** Called synchronously on the capture thread; implementations must copy retained data. */
        default void onAudio(byte[] pcm16, int length) {}
    }

    @FunctionalInterface
    interface FrameConsumer {
        void onPcm16Frame(byte[] bytes, int offset, int length);
    }

    /** Updates Android microphone attribution while no capture is active. */
    void setAttributionContext(Context context);

    Session createSession(boolean userControlledEndpointing);

    RecordedAudio record(Session session, int maximumSeconds, CaptureListener listener);

    StreamingAudioResult stream(
            Session session,
            int maximumSeconds,
            CaptureListener listener,
            FrameConsumer consumer);

    void stop(Session session);

    void cancel(Session session);
}
