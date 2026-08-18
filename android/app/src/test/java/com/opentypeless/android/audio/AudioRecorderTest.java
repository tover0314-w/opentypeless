package com.opentypeless.android.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class AudioRecorderTest {
    @Test
    public void activeNegativeReadFailsInsteadOfSpinning() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AudioRecorder.nextEmptyReadCount(-3, 0, true));
        assertTrue(error.getMessage().contains("-3"));
    }

    @Test
    public void stoppedSessionCanIgnoreTerminalReadResult() {
        assertEquals(4, AudioRecorder.nextEmptyReadCount(-3, 4, false));
    }

    @Test
    public void repeatedZeroReadsFailAtBoundedThreshold() {
        int emptyReads = 0;
        for (int index = 1; index < AudioRecorder.MAX_CONSECUTIVE_EMPTY_READS; index++) {
            emptyReads = AudioRecorder.nextEmptyReadCount(0, emptyReads, true);
        }
        final int lastCount = emptyReads;
        assertThrows(IllegalStateException.class,
                () -> AudioRecorder.nextEmptyReadCount(0, lastCount, true));
        assertEquals(0, AudioRecorder.nextEmptyReadCount(10, emptyReads, true));
    }

    @Test
    public void accumulatorIsBoundedAndExposesBackingArrayWithoutFinalCopy() {
        AudioRecorder.PcmAccumulator accumulator =
                new AudioRecorder.PcmAccumulator(10, 2);
        accumulator.append(new byte[]{1, 2, 3, 4}, 0, 4);
        accumulator.append(new byte[]{9, 5, 6, 8}, 1, 2);

        assertEquals(6, accumulator.size());
        assertTrue(accumulator.backingArray().length <= 10);
        assertSame(accumulator.backingArray(), accumulator.backingArray());
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6},
                java.util.Arrays.copyOf(accumulator.backingArray(), accumulator.size()));
        assertThrows(IllegalStateException.class,
                () -> accumulator.append(new byte[5], 0, 5));
    }

    @Test
    public void captureEventsReportReadyThenBeginningExactlyOnce() {
        List<String> events = new ArrayList<>();
        AudioRecorder.CaptureEvents capture = new AudioRecorder.CaptureEvents(
                new AudioRecorder.CaptureListener() {
                    @Override public void onReady() { events.add("ready"); }
                    @Override public void onBeginningOfSpeech() { events.add("begin"); }
                });

        capture.speechDetected(false);
        capture.ready();
        capture.ready();
        capture.speechDetected(true);
        capture.speechDetected(true);

        assertEquals(List.of("ready", "begin"), events);
    }

    @Test
    public void beginningSynthesizesReadyIfCaptureProviderReportsSpeechFirst() {
        List<String> events = new ArrayList<>();
        AudioRecorder.CaptureEvents capture = new AudioRecorder.CaptureEvents(
                new AudioRecorder.CaptureListener() {
                    @Override public void onReady() { events.add("ready"); }
                    @Override public void onBeginningOfSpeech() { events.add("begin"); }
                });

        capture.speechDetected(true);

        assertEquals(List.of("ready", "begin"), events);
    }

    @Test
    public void userControlledDictationDoesNotEndAtATwoSecondThinkingPause() {
        assertTrue(AudioRecorder.shouldAutoStop(
                AdaptiveVad.Decision.END_OF_SPEECH, false));
        org.junit.Assert.assertFalse(AudioRecorder.shouldAutoStop(
                AdaptiveVad.Decision.END_OF_SPEECH, true));
        org.junit.Assert.assertFalse(AudioRecorder.shouldAutoStop(
                AdaptiveVad.Decision.CONTINUE, false));
    }

    @Test
    public void normalStopPreservesAnInFlightTailReadWhileCancelInterruptsIt() {
        assertTrue(AudioRecorder.shouldConsumeRead(
                1_280, RecordingSession.EndState.STOPPED));
        org.junit.Assert.assertFalse(AudioRecorder.shouldConsumeRead(
                1_280, RecordingSession.EndState.CANCELLED));
        org.junit.Assert.assertFalse(AudioRecorder.shouldInterruptActiveRead(
                RecordingSession.EndState.STOPPED));
        assertTrue(AudioRecorder.shouldInterruptActiveRead(
                RecordingSession.EndState.CANCELLED));
    }

    @Test
    public void manualEndpointAllowsShortAudioWithoutWeakeningAutomaticCapture() {
        int oneHundredTwentyMs = AudioRecorder.SAMPLE_RATE * 2 * 120 / 1_000;
        org.junit.Assert.assertFalse(AudioRecorder.hasMinimumAudio(oneHundredTwentyMs, false));
        assertTrue(AudioRecorder.hasMinimumAudio(oneHundredTwentyMs, true));
        org.junit.Assert.assertFalse(AudioRecorder.hasMinimumAudio(
                oneHundredTwentyMs - 2, true));
    }

    @Test
    public void recordingDurationIsBoundedForBatchAndStreamingCapture() {
        assertEquals(5, AudioRecorder.boundedMaximumSeconds(Integer.MIN_VALUE));
        assertEquals(5, AudioRecorder.boundedMaximumSeconds(4));
        assertEquals(5, AudioRecorder.boundedMaximumSeconds(5));
        assertEquals(540, AudioRecorder.boundedMaximumSeconds(540));
        assertEquals(540, AudioRecorder.boundedMaximumSeconds(541));
        assertEquals(540, AudioRecorder.boundedMaximumSeconds(Integer.MAX_VALUE));
    }
}
