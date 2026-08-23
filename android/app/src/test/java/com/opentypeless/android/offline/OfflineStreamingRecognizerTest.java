package com.opentypeless.android.offline;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class OfflineStreamingRecognizerTest {
    @Test
    public void convertsLittleEndianPcm16WithoutOverflow() {
        byte[] pcm = {
                0x00, (byte) 0x80,
                0x00, 0x00,
                (byte) 0xff, 0x7f,
                0x55
        };
        assertArrayEquals(
                new float[] {-1.0f, 0.0f, 32767.0f / 32768.0f},
                OfflineStreamingRecognizer.pcm16ToFloat(pcm, pcm.length),
                0.000_001f);
    }

    @Test
    public void conversionHonorsBoundedEvenLength() {
        assertArrayEquals(
                new float[] {1.0f / 32768.0f},
                OfflineStreamingRecognizer.pcm16ToFloat(
                        new byte[] {0x01, 0x00, 0x7f, 0x7f}, 3),
                0.000_001f);
    }
}
