package com.opentypeless.android.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdaptiveVadTest {
    @Test
    public void endsOnlyAfterConfirmedSpeechAndSustainedSilence() {
        AdaptiveVad vad = new AdaptiveVad(16_000);
        long bytes = 0L;
        byte[] quiet = pcm(2_048, 40);
        byte[] voice = pcm(2_048, 4_000);

        for (int index = 0; index < 4; index++) {
            bytes += quiet.length;
            assertEquals(AdaptiveVad.Decision.CONTINUE, vad.accept(quiet, quiet.length, bytes));
        }
        for (int index = 0; index < 3; index++) {
            bytes += voice.length;
            assertEquals(AdaptiveVad.Decision.CONTINUE, vad.accept(voice, voice.length, bytes));
        }
        assertTrue(vad.heardSpeech());

        AdaptiveVad.Decision decision = AdaptiveVad.Decision.CONTINUE;
        while (decision == AdaptiveVad.Decision.CONTINUE) {
            bytes += quiet.length;
            decision = vad.accept(quiet, quiet.length, bytes);
        }
        assertEquals(AdaptiveVad.Decision.END_OF_SPEECH, decision);
        assertTrue(vad.recommendedStart((int) bytes) > 0);
        assertTrue(vad.recommendedEnd((int) bytes, true) < bytes);
    }

    @Test
    public void timesOutWhenThereIsNoSpeech() {
        AdaptiveVad vad = new AdaptiveVad(16_000);
        byte[] quiet = pcm(3_200, 20);
        long bytes = 0L;
        AdaptiveVad.Decision decision = AdaptiveVad.Decision.CONTINUE;
        for (int index = 0; index < 120 && decision == AdaptiveVad.Decision.CONTINUE; index++) {
            bytes += quiet.length;
            decision = vad.accept(quiet, quiet.length, bytes);
        }
        assertEquals(AdaptiveVad.Decision.NO_SPEECH_TIMEOUT, decision);
    }

    @Test
    public void shortHighEnergyTransientDoesNotCountAsSpeech() {
        AdaptiveVad vad = new AdaptiveVad(16_000);
        byte[] noise = pcm(1_600, 8_000); // 100 ms
        byte[] quiet = pcm(1_600, 20);
        long bytes = 0L;

        for (int index = 0; index < 2; index++) {
            bytes += noise.length;
            assertEquals(AdaptiveVad.Decision.CONTINUE, vad.accept(noise, noise.length, bytes));
        }
        for (int index = 0; index < 3; index++) {
            bytes += quiet.length;
            assertEquals(AdaptiveVad.Decision.CONTINUE, vad.accept(quiet, quiet.length, bytes));
        }

        assertTrue("A 200 ms click/cough-like transient must not open the VAD", !vad.heardSpeech());
    }

    @Test
    public void explicitManualEndpointAcceptsAThreeFrameShortUtterance() {
        AdaptiveVad vad = new AdaptiveVad(16_000);
        byte[] voice = pcm(640, 4_000); // 40 ms, matching the streaming capture frame.
        long bytes = 0L;

        for (int index = 0; index < 3; index++) {
            bytes += voice.length;
            assertEquals(AdaptiveVad.Decision.CONTINUE, vad.accept(voice, voice.length, bytes));
        }

        assertTrue("120 ms must remain below automatic confirmation", !vad.heardSpeech());
        assertTrue("An explicit hold release supplies the missing endpoint intent",
                vad.confirmAtManualEndpoint());
        assertTrue(vad.heardSpeech());
    }

    @Test
    public void manualEndpointAccumulatesThreeShortSyllablesAcrossOnsetGaps() {
        AdaptiveVad vad = new AdaptiveVad(16_000);
        byte[] syllable = pcm(640, 4_000); // 40 ms voiced evidence.
        byte[] gap = pcm(640, 20);
        long bytes = 0L;

        for (int syllableIndex = 0; syllableIndex < 3; syllableIndex++) {
            bytes += syllable.length;
            vad.accept(syllable, syllable.length, bytes);
            if (syllableIndex == 2) continue;
            for (int gapIndex = 0; gapIndex < 5; gapIndex++) { // 200 ms resets the onset candidate.
                bytes += gap.length;
                vad.accept(gap, gap.length, bytes);
            }
        }

        assertTrue(!vad.heardSpeech());
        assertTrue(vad.confirmAtManualEndpoint());
    }

    @Test
    public void manualEndpointStillRejectsSilenceAndASingleTransientFrame() {
        AdaptiveVad silenceVad = new AdaptiveVad(16_000);
        byte[] silence = pcm(640, 20);
        silenceVad.accept(silence, silence.length, silence.length);
        assertTrue(!silenceVad.confirmAtManualEndpoint());

        AdaptiveVad transientVad = new AdaptiveVad(16_000);
        byte[] transientFrame = pcm(640, 8_000);
        transientVad.accept(transientFrame, transientFrame.length, transientFrame.length);
        assertTrue("A 40 ms transient must remain below the manual endpoint floor",
                !transientVad.confirmAtManualEndpoint());
    }

    @Test
    public void naturalPauseDoesNotEndDictationAndFollowingSpeechResetsSilence() {
        AdaptiveVad vad = new AdaptiveVad(16_000);
        byte[] voice = pcm(1_600, 4_000); // 100 ms
        byte[] quiet = pcm(1_600, 20);
        long bytes = 0L;

        for (int index = 0; index < 4; index++) {
            bytes += voice.length;
            assertEquals(AdaptiveVad.Decision.CONTINUE, vad.accept(voice, voice.length, bytes));
        }
        assertTrue(vad.heardSpeech());

        for (int index = 0; index < 18; index++) { // 1.8 seconds
            bytes += quiet.length;
            assertEquals(AdaptiveVad.Decision.CONTINUE, vad.accept(quiet, quiet.length, bytes));
        }
        bytes += voice.length;
        assertEquals(AdaptiveVad.Decision.CONTINUE, vad.accept(voice, voice.length, bytes));

        AdaptiveVad.Decision decision = AdaptiveVad.Decision.CONTINUE;
        for (int index = 0; index < 20; index++) {
            bytes += quiet.length;
            decision = vad.accept(quiet, quiet.length, bytes);
        }
        assertEquals(AdaptiveVad.Decision.END_OF_SPEECH, decision);
    }

    @Test
    public void releaseHysteresisKeepsQuietWordEndingAlive() {
        AdaptiveVad vad = new AdaptiveVad(16_000);
        byte[] voice = pcm(1_600, 4_000);
        byte[] quietEnding = pcm(1_600, 450); // Below onset, above post-onset release threshold.
        byte[] silence = pcm(1_600, 20);
        long bytes = 0L;

        for (int index = 0; index < 4; index++) {
            bytes += voice.length;
            vad.accept(voice, voice.length, bytes);
        }
        for (int index = 0; index < 19; index++) {
            bytes += silence.length;
            assertEquals(AdaptiveVad.Decision.CONTINUE, vad.accept(silence, silence.length, bytes));
        }

        bytes += quietEnding.length;
        assertEquals(AdaptiveVad.Decision.CONTINUE,
                vad.accept(quietEnding, quietEnding.length, bytes));

        for (int index = 0; index < 19; index++) {
            bytes += silence.length;
            assertEquals(AdaptiveVad.Decision.CONTINUE, vad.accept(silence, silence.length, bytes));
        }
    }

    @Test
    public void rejectsInvalidSampleRate() {
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveVad(0));
    }

    private static byte[] pcm(int samples, int amplitude) {
        byte[] result = new byte[samples * 2];
        for (int index = 0; index < samples; index++) {
            short value = (short) (index % 2 == 0 ? amplitude : -amplitude);
            result[index * 2] = (byte) value;
            result[index * 2 + 1] = (byte) (value >>> 8);
        }
        return result;
    }
}
