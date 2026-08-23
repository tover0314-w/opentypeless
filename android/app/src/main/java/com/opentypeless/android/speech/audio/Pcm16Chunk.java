package com.opentypeless.android.speech.audio;

import java.util.Objects;

/** Immutable mono PCM16 chunk with an absolute sample-frame position. */
public record Pcm16Chunk(long startSample, short[] samples) {
    public Pcm16Chunk {
        if (startSample < 0L) {
            throw new IllegalArgumentException("PCM start sample must be non-negative");
        }
        samples = Objects.requireNonNull(samples, "samples").clone();
        if (samples.length == 0) {
            throw new IllegalArgumentException("PCM chunk must not be empty");
        }
    }

    @Override
    public short[] samples() {
        return samples.clone();
    }

    short[] rawSamples() {
        return samples;
    }

    public long endSample() {
        return Math.addExact(startSample, samples.length);
    }
}
