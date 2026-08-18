package com.opentypeless.android.audio;

import android.content.Context;

import java.util.Objects;

/** Android {@link AudioRecorder} adapter that keeps its mutable session type behind AudioCapture. */
public final class AndroidAudioCapture implements AudioCapture {
    private final AudioRecorder recorder;

    public AndroidAudioCapture() {
        this(new AudioRecorder());
    }

    AndroidAudioCapture(AudioRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @Override
    public void setAttributionContext(Context context) {
        recorder.setAttributionContext(context);
    }

    @Override
    public Session createSession(boolean userControlledEndpointing) {
        return new RecorderSession(
                this,
                new RecordingSession(userControlledEndpointing));
    }

    @Override
    public RecordedAudio record(
            Session session,
            int maximumSeconds,
            CaptureListener listener) {
        requireListener(listener);
        return recorder.record(
                requireOwned(session),
                maximumSeconds,
                bridge(listener));
    }

    @Override
    public StreamingAudioResult stream(
            Session session,
            int maximumSeconds,
            CaptureListener listener,
            FrameConsumer consumer) {
        requireListener(listener);
        if (consumer == null) throw new IllegalArgumentException("Frame consumer is required");
        return recorder.stream(
                requireOwned(session),
                maximumSeconds,
                bridge(listener),
                consumer::onPcm16Frame);
    }

    @Override
    public void stop(Session session) {
        recorder.stop(requireOwned(session));
    }

    @Override
    public void cancel(Session session) {
        recorder.cancel(requireOwned(session));
    }

    private RecordingSession requireOwned(Session session) {
        if (!(session instanceof RecorderSession owned) || owned.owner != this) {
            throw new IllegalArgumentException("Capture session is invalid");
        }
        return owned.delegate;
    }

    private static void requireListener(CaptureListener listener) {
        if (listener == null) throw new IllegalArgumentException("Capture listener is required");
    }

    private static AudioRecorder.CaptureListener bridge(CaptureListener listener) {
        return new AudioRecorder.CaptureListener() {
            @Override
            public void onReady() {
                listener.onReady();
            }

            @Override
            public void onBeginningOfSpeech() {
                listener.onBeginningOfSpeech();
            }

            @Override
            public void onAudio(byte[] pcm16, int length) {
                listener.onAudio(pcm16, length);
            }
        };
    }

    private static final class RecorderSession implements Session {
        private final AndroidAudioCapture owner;
        private final RecordingSession delegate;

        private RecorderSession(AndroidAudioCapture owner, RecordingSession delegate) {
            this.owner = owner;
            this.delegate = delegate;
        }

        @Override
        public boolean userControlledEndpointing() {
            return delegate.userControlledEndpointing();
        }

        @Override
        public String toString() {
            return "AudioCapture.Session";
        }
    }
}
