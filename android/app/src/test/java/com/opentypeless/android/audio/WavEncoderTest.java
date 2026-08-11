package com.opentypeless.android.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class WavEncoderTest {
    @Test
    public void writesCanonicalMonoPcm16Header() {
        byte[] wav = WavEncoder.pcm16Mono(new byte[]{1, 2, 3, 4}, 16_000);
        assertEquals(48, wav.length);
        assertArrayEquals("RIFF".getBytes(StandardCharsets.US_ASCII), slice(wav, 0, 4));
        assertArrayEquals("WAVE".getBytes(StandardCharsets.US_ASCII), slice(wav, 8, 12));
        assertArrayEquals("data".getBytes(StandardCharsets.US_ASCII), slice(wav, 36, 40));
        assertEquals(4, littleEndian32(wav, 40));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, slice(wav, 44, 48));
    }

    @Test
    public void encodesShortSamplesDirectlyWithoutChangingTheirBits() {
        byte[] wav = WavEncoder.pcm16Mono(
                new short[]{(short) 0x1234, (short) 0xfedc}, 16_000);

        assertEquals(48, wav.length);
        assertArrayEquals(
                new byte[]{0x34, 0x12, (byte) 0xdc, (byte) 0xfe},
                slice(wav, 44, 48));
    }

    @Test
    public void rejectsPartialPcm16Sample() {
        assertThrows(IllegalArgumentException.class,
                () -> WavEncoder.pcm16Mono(new byte[]{1}, 16_000));
    }

    @Test
    public void encodesSelectedPcmRangeWithoutIntermediateTrimArray() {
        byte[] backing = new byte[]{99, 98, 1, 2, 3, 4, 97, 96};

        byte[] wav = WavEncoder.pcm16Mono(backing, 2, 4, 16_000);

        assertEquals(48, wav.length);
        assertEquals(4, littleEndian32(wav, 40));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, slice(wav, 44, 48));
        assertArrayEquals(new byte[]{99, 98, 1, 2, 3, 4, 97, 96}, backing);
    }

    @Test
    public void rejectsInvalidPcmRangesAndSampleRates() {
        byte[] pcm = new byte[8];
        assertThrows(IllegalArgumentException.class,
                () -> WavEncoder.pcm16Mono(pcm, -1, 2, 16_000));
        assertThrows(IllegalArgumentException.class,
                () -> WavEncoder.pcm16Mono(pcm, 7, 2, 16_000));
        assertThrows(IllegalArgumentException.class,
                () -> WavEncoder.pcm16Mono(pcm, 0, 3, 16_000));
        assertThrows(IllegalArgumentException.class,
                () -> WavEncoder.pcm16Mono(pcm, 0, 2, 0));
    }

    private static byte[] slice(byte[] value, int from, int to) {
        byte[] result = new byte[to - from];
        System.arraycopy(value, from, result, 0, result.length);
        return result;
    }

    private static int littleEndian32(byte[] value, int offset) {
        return (value[offset] & 0xff)
                | ((value[offset + 1] & 0xff) << 8)
                | ((value[offset + 2] & 0xff) << 16)
                | ((value[offset + 3] & 0xff) << 24);
    }
}
