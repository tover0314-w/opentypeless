package com.opentypeless.android.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class Pcm16WaveDecoderTest {
    @Test
    public void decodesCanonicalWave() {
        byte[] pcm = new byte[]{0, 0, -1, 127, 0, -128, -1, -1};
        Pcm16WaveDecoder.Waveform result = Pcm16WaveDecoder.decode(
                WavEncoder.pcm16Mono(pcm, 16_000));

        assertEquals(16_000, result.sampleRate());
        assertArrayEquals(
                new float[]{0f, 32767f / 32768f, -1f, -1f / 32768f},
                result.samples(),
                0.000001f);
    }

    @Test
    public void rejectsTruncatedAndTrailingData() {
        assertThrows(IllegalArgumentException.class,
                () -> Pcm16WaveDecoder.decode(new byte[43]));
        byte[] wav = WavEncoder.pcm16Mono(new byte[]{0, 0}, 16_000);
        byte[] trailing = java.util.Arrays.copyOf(wav, wav.length + 1);
        assertThrows(IllegalArgumentException.class,
                () -> Pcm16WaveDecoder.decode(trailing));
    }

    @Test
    public void rejectsWrongFormatAndRate() {
        byte[] stereo = WavEncoder.pcm16Mono(new byte[]{0, 0}, 16_000);
        stereo[22] = 2;
        assertThrows(IllegalArgumentException.class,
                () -> Pcm16WaveDecoder.decode(stereo));

        byte[] wrongRate = WavEncoder.pcm16Mono(new byte[]{0, 0}, 8_000);
        assertThrows(IllegalArgumentException.class,
                () -> Pcm16WaveDecoder.decode(wrongRate));
    }
}
