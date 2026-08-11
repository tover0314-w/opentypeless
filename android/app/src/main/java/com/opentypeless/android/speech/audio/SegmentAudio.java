package com.opentypeless.android.speech.audio;

import java.util.Arrays;
import java.util.Objects;

/** Immutable, bounded PCM16 audio for one hard-closed recognition segment. */
public record SegmentAudio(
        long segmentId,
        long audioStartSample,
        long audioEndSample,
        long boundarySample,
        int sampleRate,
        HardBoundaryReason reason,
        short[] samples) {

    public SegmentAudio {
        if (segmentId <= 0L
                || audioStartSample < 0L
                || audioEndSample <= audioStartSample
                || boundarySample < audioStartSample
                || boundarySample > audioEndSample
                || sampleRate <= 0) {
            throw new IllegalArgumentException("invalid closed segment audio");
        }
        Objects.requireNonNull(reason, "reason");
        samples = Objects.requireNonNull(samples, "samples").clone();
        if (samples.length != audioEndSample - audioStartSample) {
            throw new IllegalArgumentException("segment PCM length does not match its sample span");
        }
    }

    @Override
    public short[] samples() {
        return samples.clone();
    }

    /** Clears this record's private PCM copy after an adapter has copied or persisted it. */
    public void zeroize() {
        Arrays.fill(samples, (short) 0);
    }

    public long durationMs() {
        return Math.multiplyExact((long) samples.length, 1_000L) / sampleRate;
    }
}
