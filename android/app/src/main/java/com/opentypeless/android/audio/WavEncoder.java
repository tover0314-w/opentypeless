package com.opentypeless.android.audio;

public final class WavEncoder {
    private WavEncoder() {}

    public static byte[] pcm16Mono(byte[] pcm, int sampleRate) {
        if (pcm == null) throw new IllegalArgumentException("PCM data is required");
        return pcm16Mono(pcm, 0, pcm.length, sampleRate);
    }

    /**
     * Encodes one slice of a PCM backing buffer directly into the final WAV allocation. This lets
     * callers trim a recording without first copying the selected PCM range into another array.
     */
    public static byte[] pcm16Mono(byte[] pcm, int offset, int length, int sampleRate) {
        if (pcm == null) throw new IllegalArgumentException("PCM data is required");
        if (offset < 0 || length < 0 || offset > pcm.length - length) {
            throw new IllegalArgumentException("PCM range is outside the backing buffer");
        }
        if ((length & 1) != 0) {
            throw new IllegalArgumentException("PCM16 data must contain complete samples");
        }
        if (sampleRate <= 0 || sampleRate > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("Sample rate must be positive and supported");
        }
        if (length > Integer.MAX_VALUE - 44) {
            throw new IllegalArgumentException("PCM data is too large for a WAV byte array");
        }

        byte[] wav = new byte[44 + length];
        writeAscii(wav, 0, "RIFF");
        writeInt32(wav, 4, 36 + length);
        writeAscii(wav, 8, "WAVE");
        writeAscii(wav, 12, "fmt ");
        writeInt32(wav, 16, 16);
        writeInt16(wav, 20, 1); // PCM
        writeInt16(wav, 22, 1); // mono
        writeInt32(wav, 24, sampleRate);
        writeInt32(wav, 28, sampleRate * 2);
        writeInt16(wav, 32, 2);
        writeInt16(wav, 34, 16);
        writeAscii(wav, 36, "data");
        writeInt32(wav, 40, length);
        System.arraycopy(pcm, offset, wav, 44, length);
        return wav;
    }

    private static void writeAscii(byte[] out, int offset, String value) {
        for (int index = 0; index < value.length(); index++) {
            out[offset + index] = (byte) value.charAt(index);
        }
    }

    private static void writeInt16(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xff);
        out[offset + 1] = (byte) ((value >>> 8) & 0xff);
    }

    private static void writeInt32(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xff);
        out[offset + 1] = (byte) ((value >>> 8) & 0xff);
        out[offset + 2] = (byte) ((value >>> 16) & 0xff);
        out[offset + 3] = (byte) ((value >>> 24) & 0xff);
    }
}
