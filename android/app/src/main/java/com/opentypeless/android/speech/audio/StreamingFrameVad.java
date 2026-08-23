package com.opentypeless.android.speech.audio;

/**
 * Low-cost frame classifier used only for Speech Core v2 segmentation.
 *
 * <p>Recognition remains authoritative for words. This classifier merely distinguishes sustained
 * speech from pauses, with an adaptive pre-speech noise floor and a short release hysteresis so
 * plosives and intra-word gaps do not create sentence boundaries.
 */
public final class StreamingFrameVad {
    private static final double MIN_ONSET_RMS = 550.0;
    private static final double MIN_RELEASE_RMS = 350.0;
    private static final int RELEASE_HANGOVER_MS = 120;

    private final int sampleRate;
    private double noiseFloor = 180.0;
    private int hangoverSamples;
    private boolean speechObserved;

    public StreamingFrameVad(int sampleRate) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sample rate must be positive");
        this.sampleRate = sampleRate;
    }

    public boolean classify(byte[] pcm16, int offset, int length) {
        if (pcm16 == null || offset < 0 || length < 0 || offset > pcm16.length - length) {
            throw new IllegalArgumentException("PCM range is invalid");
        }
        int safeLength = length & ~1;
        if (safeLength == 0) return false;
        double rms = rms16(pcm16, offset, safeLength);
        double onset = Math.max(MIN_ONSET_RMS, noiseFloor * 2.8 + 120.0);
        double release = Math.max(MIN_RELEASE_RMS, onset * 0.68);
        boolean energetic = rms >= (speechObserved ? release : onset);
        int frameSamples = safeLength / 2;
        if (energetic) {
            speechObserved = true;
            hangoverSamples = sampleRate * RELEASE_HANGOVER_MS / 1_000;
            return true;
        }
        if (hangoverSamples > 0) {
            hangoverSamples = Math.max(0, hangoverSamples - frameSamples);
            return true;
        }
        // Update the noise estimate only when no speech tail is being protected.
        noiseFloor = noiseFloor * 0.94 + rms * 0.06;
        return false;
    }

    public void reset() {
        noiseFloor = 180.0;
        hangoverSamples = 0;
        speechObserved = false;
    }

    static double rms16(byte[] pcm16, int offset, int length) {
        long sumSquares = 0L;
        int samples = 0;
        int end = Math.min(pcm16.length, offset + length);
        for (int index = offset; index + 1 < end; index += 2) {
            int sample = (short) ((pcm16[index] & 0xff) | (pcm16[index + 1] << 8));
            sumSquares += (long) sample * sample;
            samples++;
        }
        return samples == 0 ? 0.0 : Math.sqrt((double) sumSquares / samples);
    }
}
