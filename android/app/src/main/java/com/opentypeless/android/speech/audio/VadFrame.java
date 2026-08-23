package com.opentypeless.android.speech.audio;

/** Contiguous VAD decision for one PCM frame, expressed in absolute mono sample frames. */
public record VadFrame(long startSample, int sampleCount, boolean speech) {
    public VadFrame {
        if (startSample < 0L || sampleCount <= 0) {
            throw new IllegalArgumentException("invalid VAD frame span");
        }
    }

    public long endSample() {
        return Math.addExact(startSample, sampleCount);
    }
}
