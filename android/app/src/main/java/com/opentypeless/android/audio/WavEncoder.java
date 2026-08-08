package com.opentypeless.android.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class WavEncoder {
    private WavEncoder() {}

    public static byte[] pcm16Mono(byte[] pcm, int sampleRate) {
        if (pcm == null || (pcm.length & 1) != 0) {
            throw new IllegalArgumentException("PCM16 data must contain complete samples");
        }
        if (sampleRate <= 0) throw new IllegalArgumentException("Sample rate must be positive");

        try {
            ByteArrayOutputStream wav = new ByteArrayOutputStream(44 + pcm.length);
            writeAscii(wav, "RIFF");
            writeInt32(wav, 36 + pcm.length);
            writeAscii(wav, "WAVE");
            writeAscii(wav, "fmt ");
            writeInt32(wav, 16);
            writeInt16(wav, 1); // PCM
            writeInt16(wav, 1); // mono
            writeInt32(wav, sampleRate);
            writeInt32(wav, sampleRate * 2);
            writeInt16(wav, 2);
            writeInt16(wav, 16);
            writeAscii(wav, "data");
            writeInt32(wav, pcm.length);
            wav.write(pcm);
            return wav.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) throws IOException {
        for (int i = 0; i < value.length(); i++) out.write(value.charAt(i));
    }

    private static void writeInt16(ByteArrayOutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeInt32(ByteArrayOutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }
}
