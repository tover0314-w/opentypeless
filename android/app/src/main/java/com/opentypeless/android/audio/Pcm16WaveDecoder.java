package com.opentypeless.android.audio;

/** Decodes only the canonical mono PCM16 WAV shape produced by {@link WavEncoder}. */
public final class Pcm16WaveDecoder {
    public record Waveform(float[] samples, int sampleRate) {}

    private Pcm16WaveDecoder() {}

    public static Waveform decode(byte[] wav) {
        if (wav == null || wav.length < 44) {
            throw new IllegalArgumentException("A complete WAV recording is required");
        }
        requireAscii(wav, 0, "RIFF");
        requireAscii(wav, 8, "WAVE");
        requireAscii(wav, 12, "fmt ");
        requireAscii(wav, 36, "data");
        if (int32(wav, 4) != wav.length - 8 || int32(wav, 16) != 16) {
            throw new IllegalArgumentException("Unsupported WAV container");
        }
        if (uint16(wav, 20) != 1 || uint16(wav, 22) != 1
                || uint16(wav, 34) != 16 || uint16(wav, 32) != 2) {
            throw new IllegalArgumentException("WAV must be mono PCM16");
        }
        int sampleRate = int32(wav, 24);
        if (sampleRate != AudioRecorder.SAMPLE_RATE
                || int32(wav, 28) != sampleRate * 2) {
            throw new IllegalArgumentException("WAV must use 16 kHz PCM16 audio");
        }
        int dataBytes = int32(wav, 40);
        if (dataBytes < 0 || (dataBytes & 1) != 0 || dataBytes != wav.length - 44) {
            throw new IllegalArgumentException("WAV data length is invalid");
        }
        float[] samples = new float[dataBytes / 2];
        for (int index = 0; index < samples.length; index++) {
            int offset = 44 + index * 2;
            short value = (short) ((wav[offset] & 0xff) | (wav[offset + 1] << 8));
            samples[index] = value / 32768.0f;
        }
        return new Waveform(samples, sampleRate);
    }

    private static void requireAscii(byte[] data, int offset, String expected) {
        for (int index = 0; index < expected.length(); index++) {
            if (data[offset + index] != (byte) expected.charAt(index)) {
                throw new IllegalArgumentException("Unsupported WAV container");
            }
        }
    }

    private static int uint16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static int int32(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | (data[offset + 3] << 24);
    }
}
