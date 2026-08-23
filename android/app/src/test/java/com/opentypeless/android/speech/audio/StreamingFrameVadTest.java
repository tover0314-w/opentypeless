package com.opentypeless.android.speech.audio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StreamingFrameVadTest {
    @Test
    public void sustainedSpeechTriggersAndReleaseHangoverProtectsWordGaps() {
        StreamingFrameVad vad = new StreamingFrameVad(16_000);

        assertFalse(vad.classify(frame(100), 0, 1_280));
        assertTrue(vad.classify(frame(2_000), 0, 1_280));
        assertTrue(vad.classify(frame(0), 0, 1_280));
        assertTrue(vad.classify(frame(0), 0, 1_280));
        assertTrue(vad.classify(frame(0), 0, 1_280));
        assertFalse(vad.classify(frame(0), 0, 1_280));
    }

    @Test
    public void steadyModerateNoiseRaisesTheFloorWithoutBecomingSpeech() {
        StreamingFrameVad vad = new StreamingFrameVad(16_000);

        for (int frame = 0; frame < 100; frame++) {
            assertFalse(vad.classify(frame(400), 0, 1_280));
        }
        assertTrue(vad.classify(frame(2_000), 0, 1_280));
    }

    @Test
    public void resetRemovesSpeechHangover() {
        StreamingFrameVad vad = new StreamingFrameVad(16_000);
        assertTrue(vad.classify(frame(2_000), 0, 1_280));

        vad.reset();

        assertFalse(vad.classify(frame(0), 0, 1_280));
    }

    private static byte[] frame(int amplitude) {
        byte[] pcm = new byte[1_280];
        for (int index = 0; index < pcm.length / 2; index++) {
            int value = (index & 1) == 0 ? amplitude : -amplitude;
            pcm[index * 2] = (byte) (value & 0xff);
            pcm[index * 2 + 1] = (byte) ((value >>> 8) & 0xff);
        }
        return pcm;
    }
}
