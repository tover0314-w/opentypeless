package com.opentypeless.android.audio;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.LockSupport;

public final class AudioRecorder {
    public interface CaptureListener {
        default void onReady() {}
        default void onBeginningOfSpeech() {}
    }

    private static final CaptureListener NO_CAPTURE_LISTENER = new CaptureListener() {};
    public static final int SAMPLE_RATE = 16_000;
    static final int MAX_CONSECUTIVE_EMPTY_READS = 50;
    private static final long EMPTY_READ_BACKOFF_NANOS = 2_000_000L;
    private static final int INITIAL_PCM_CAPACITY = 64 * 1_024;

    private volatile AudioRecord activeRecord;
    private volatile RecordingSession activeSession;
    private volatile Context attributionContext;

    public void setAttributionContext(Context context) {
        attributionContext = context;
    }

    @SuppressLint("MissingPermission")
    public RecordedAudio record(RecordingSession session, int maximumSeconds) {
        return record(session, maximumSeconds, NO_CAPTURE_LISTENER);
    }

    @SuppressLint("MissingPermission")
    public RecordedAudio record(
            RecordingSession session,
            int maximumSeconds,
            CaptureListener listener) {
        if (listener == null) throw new IllegalArgumentException("Capture listener is required");
        if (activeSession != null) {
            throw new IllegalStateException("A recording is already active");
        }
        activeSession = session;
        if (!session.isActive()) {
            activeSession = null;
            if (session.isCancelled()) throw new CancellationException("Recording cancelled");
            throw new IllegalStateException("Recording stopped before audio capture started");
        }
        int minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            activeSession = null;
            throw new IllegalStateException("No compatible microphone input");
        }
        int bufferSize = Math.max(minimum, 4096);
        AudioRecord record;
        try {
            record = createAudioRecord(bufferSize);
        } catch (RuntimeException error) {
            activeSession = null;
            throw error;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            activeSession = null;
            throw new IllegalStateException("Microphone could not be initialized");
        }

        int safeSeconds = Math.max(5, Math.min(maximumSeconds, 540));
        int maximumBytes = SAMPLE_RATE * 2 * safeSeconds;
        activeRecord = record;
        PcmAccumulator pcm = new PcmAccumulator(maximumBytes, INITIAL_PCM_CAPACITY);
        byte[] buffer = new byte[bufferSize];
        boolean reachedLimit = false;
        boolean autoStopped = false;
        boolean noSpeechTimeout = false;
        int consecutiveEmptyReads = 0;
        AdaptiveVad vad = new AdaptiveVad(SAMPLE_RATE);
        CaptureEvents captureEvents = new CaptureEvents(listener);
        try {
            if (!session.isActive() || Thread.currentThread().isInterrupted()) {
                if (session.isCancelled()) throw new CancellationException("Recording cancelled");
                throw new IllegalStateException("Recording stopped before it started");
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Microphone did not enter the recording state");
            }
            captureEvents.ready();
            while (session.isActive() && !Thread.currentThread().isInterrupted()
                    && pcm.size() < maximumBytes) {
                int read = record.read(buffer, 0, Math.min(buffer.length, maximumBytes - pcm.size()));
                if (read > 0) {
                    consecutiveEmptyReads = 0;
                    pcm.append(buffer, 0, read);
                    AdaptiveVad.Decision decision = vad.accept(buffer, read, pcm.size());
                    captureEvents.speechDetected(vad.heardSpeech());
                    if (decision == AdaptiveVad.Decision.END_OF_SPEECH) {
                        autoStopped = true;
                        session.stop();
                    } else if (decision == AdaptiveVad.Decision.NO_SPEECH_TIMEOUT) {
                        noSpeechTimeout = true;
                        session.stop();
                    }
                } else if (!session.isActive()) {
                    break;
                } else {
                    consecutiveEmptyReads = nextEmptyReadCount(read, consecutiveEmptyReads, true);
                    LockSupport.parkNanos(EMPTY_READ_BACKOFF_NANOS);
                }
            }
            reachedLimit = pcm.size() >= maximumBytes;
            if (session.isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Recording cancelled");
            }
        } finally {
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
            } catch (IllegalStateException ignored) {
                // stop() can race with the UI stop request.
            }
            record.release();
            activeRecord = null;
            activeSession = null;
        }
        if (noSpeechTimeout || !vad.heardSpeech()) {
            throw new IllegalStateException("No speech was detected");
        }
        int trimStart = vad.recommendedStart(pcm.size());
        int trimEnd = vad.recommendedEnd(pcm.size(), autoStopped);
        int trimmedLength = trimEnd - trimStart;
        if (trimmedLength < SAMPLE_RATE / 2) {
            throw new IllegalStateException("Recording was too short");
        }
        long durationMs = (trimmedLength * 1_000L) / (SAMPLE_RATE * 2L);
        return new RecordedAudio(
                WavEncoder.pcm16Mono(pcm.backingArray(), trimStart, trimmedLength, SAMPLE_RATE),
                durationMs,
                reachedLimit,
                autoStopped);
    }

    public void stop(RecordingSession session) {
        if (session != null) session.stop();
        stopActiveRecord();
    }

    public void cancel(RecordingSession session) {
        if (session != null) session.cancel();
        stopActiveRecord();
    }

    private void stopActiveRecord() {
        AudioRecord record = activeRecord;
        if (record != null) {
            try {
                record.stop();
            } catch (IllegalStateException ignored) {
                // The capture loop owns final cleanup.
            }
        }
    }

    public boolean isRecording() {
        RecordingSession session = activeSession;
        return session != null && session.isActive();
    }

    static int nextEmptyReadCount(int read, int previousEmptyReads, boolean sessionActive) {
        if (read > 0) return 0;
        if (!sessionActive) return previousEmptyReads;
        if (read < 0) throw new IllegalStateException("Microphone read failed: " + read);
        int next = previousEmptyReads + 1;
        if (next >= MAX_CONSECUTIVE_EMPTY_READS) {
            throw new IllegalStateException("Microphone repeatedly returned no audio");
        }
        return next;
    }

    @SuppressLint("MissingPermission")
    private AudioRecord createAudioRecord(int bufferSize) {
        Context context = attributionContext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context != null) {
            return createAttributedAudioRecord(context, bufferSize);
        }
        return new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2);
    }

    @TargetApi(Build.VERSION_CODES.S)
    @SuppressLint({"MissingPermission", "UseRequiresApi"})
    private static AudioRecord createAttributedAudioRecord(Context context, int bufferSize) {
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        return new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize * 2)
                .setContext(context)
                .build();
    }

    /** Keeps capture lifecycle callbacks ordered and exactly-once. */
    static final class CaptureEvents {
        private final CaptureListener listener;
        private boolean ready;
        private boolean beginning;

        CaptureEvents(CaptureListener listener) {
            if (listener == null) throw new IllegalArgumentException("Capture listener is required");
            this.listener = listener;
        }

        void ready() {
            if (ready) return;
            ready = true;
            listener.onReady();
        }

        void speechDetected(boolean heardSpeech) {
            if (!heardSpeech || beginning) return;
            if (!ready) ready();
            beginning = true;
            listener.onBeginningOfSpeech();
        }
    }

    /** A bounded growing buffer that exposes its backing array to avoid a final PCM copy. */
    static final class PcmAccumulator {
        private final int maximumCapacity;
        private byte[] data;
        private int size;

        PcmAccumulator(int maximumCapacity, int preferredInitialCapacity) {
            if (maximumCapacity <= 0) throw new IllegalArgumentException("Maximum must be positive");
            this.maximumCapacity = maximumCapacity;
            int initial = Math.max(1, Math.min(maximumCapacity, preferredInitialCapacity));
            data = new byte[initial];
        }

        void append(byte[] source, int offset, int length) {
            if (source == null || offset < 0 || length < 0 || offset > source.length - length) {
                throw new IllegalArgumentException("Invalid PCM source range");
            }
            if (length > maximumCapacity - size) {
                throw new IllegalStateException("PCM buffer capacity exceeded");
            }
            ensureCapacity(size + length);
            System.arraycopy(source, offset, data, size, length);
            size += length;
        }

        int size() {
            return size;
        }

        byte[] backingArray() {
            return data;
        }

        private void ensureCapacity(int required) {
            if (required <= data.length) return;
            int grown = data.length;
            while (grown < required) {
                long candidate = grown + Math.max(4_096, grown / 2L);
                grown = (int) Math.min(maximumCapacity, candidate);
            }
            data = Arrays.copyOf(data, grown);
        }
    }
}
