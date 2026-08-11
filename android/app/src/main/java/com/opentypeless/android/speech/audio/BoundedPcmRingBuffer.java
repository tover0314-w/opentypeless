package com.opentypeless.android.speech.audio;

import java.util.Arrays;

/** Bounded mono PCM16 ring indexed by absolute sample frame. */
public final class BoundedPcmRingBuffer implements AutoCloseable {
    private final short[] ring;
    private long retainedStartSample;
    private long endSample;
    private boolean initialized;
    private boolean closed;

    public BoundedPcmRingBuffer(int sampleRate, int capacityMs) {
        if (sampleRate <= 0 || capacityMs <= 0) {
            throw new IllegalArgumentException("ring sample rate and capacity must be positive");
        }
        long samples = Math.multiplyExact((long) sampleRate, capacityMs) / 1_000L;
        if (samples <= 0L || samples > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("ring capacity is unsupported");
        }
        ring = new short[(int) samples];
    }

    public synchronized void append(Pcm16Chunk chunk) {
        requireOpen();
        if (!initialized) {
            retainedStartSample = chunk.startSample();
            endSample = chunk.startSample();
            initialized = true;
        }
        if (chunk.startSample() != endSample) {
            throw new IllegalArgumentException("PCM chunks must be contiguous");
        }
        short[] source = chunk.rawSamples();
        for (short sample : source) {
            ring[(int) (endSample % ring.length)] = sample;
            endSample++;
        }
        retainedStartSample = Math.max(retainedStartSample, endSample - ring.length);
    }

    public synchronized short[] slice(long startSample, long requestedEndSample) {
        requireOpen();
        if (!initialized
                || startSample < retainedStartSample
                || requestedEndSample > endSample
                || requestedEndSample <= startSample) {
            throw new IllegalArgumentException("requested PCM slice is not retained");
        }
        long length = requestedEndSample - startSample;
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("requested PCM slice is too large");
        }
        short[] result = new short[(int) length];
        for (int index = 0; index < result.length; index++) {
            result[index] = ring[(int) ((startSample + index) % ring.length)];
        }
        return result;
    }

    public synchronized long retainedStartSample() {
        return initialized ? retainedStartSample : 0L;
    }

    public synchronized long endSample() {
        return initialized ? endSample : 0L;
    }

    public synchronized int capacitySamples() {
        return ring.length;
    }

    @Override
    public synchronized void close() {
        Arrays.fill(ring, (short) 0);
        retainedStartSample = 0L;
        endSample = 0L;
        initialized = false;
        closed = true;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("PCM ring is closed");
        }
    }
}
