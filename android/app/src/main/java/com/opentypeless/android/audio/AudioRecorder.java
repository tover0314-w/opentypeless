package com.opentypeless.android.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AudioRecorder {
    public static final int SAMPLE_RATE = 16_000;
    private static final int MAX_SECONDS = 60;

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private volatile AudioRecord activeRecord;

    @SuppressLint("MissingPermission")
    public byte[] recordUntilStopped() {
        if (!recording.compareAndSet(false, true)) {
            throw new IllegalStateException("A recording is already active");
        }
        int minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            recording.set(false);
            throw new IllegalStateException("No compatible microphone input");
        }
        int bufferSize = Math.max(minimum, 4096);
        AudioRecord record;
        try {
            record = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2);
        } catch (RuntimeException error) {
            recording.set(false);
            throw error;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            recording.set(false);
            throw new IllegalStateException("Microphone could not be initialized");
        }

        activeRecord = record;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        byte[] buffer = new byte[bufferSize];
        int maximumBytes = SAMPLE_RATE * 2 * MAX_SECONDS;
        try {
            record.startRecording();
            while (recording.get() && pcm.size() < maximumBytes) {
                int read = record.read(buffer, 0, Math.min(buffer.length, maximumBytes - pcm.size()));
                if (read > 0) {
                    pcm.write(buffer, 0, read);
                } else if (read != AudioRecord.ERROR_INVALID_OPERATION && recording.get()) {
                    throw new IllegalStateException("Microphone read failed: " + read);
                }
            }
        } finally {
            recording.set(false);
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
            } catch (IllegalStateException ignored) {
                // stop() can race with the UI stop request.
            }
            record.release();
            activeRecord = null;
        }
        if (pcm.size() < SAMPLE_RATE / 2) {
            throw new IllegalStateException("Recording was too short");
        }
        return WavEncoder.pcm16Mono(pcm.toByteArray(), SAMPLE_RATE);
    }

    public void stop() {
        recording.set(false);
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
        return recording.get();
    }
}
