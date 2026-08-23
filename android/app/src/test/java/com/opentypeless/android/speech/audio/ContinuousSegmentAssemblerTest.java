package com.opentypeless.android.speech.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class ContinuousSegmentAssemblerTest {
    private static final int SAMPLE_RATE = 1_000;
    private static final EndpointPolicy POLICY =
            new EndpointPolicy(120, 300, 800, 2_000, 100, 80);

    @Test
    public void softPauseNeverClosesAudioAndSpeechReopensSameSegment() {
        ContinuousSegmentAssembler assembler = new ContinuousSegmentAssembler(SAMPLE_RATE, POLICY);
        List<BoundarySignal> signals = new ArrayList<>();

        signals.addAll(feed(assembler, 0, 200, true).boundarySignals());
        signals.addAll(feed(assembler, 200, 350, false).boundarySignals());
        assertTrue(signals.stream().anyMatch(BoundarySignal.SoftBoundary.class::isInstance));
        assertFalse(signals.stream().anyMatch(BoundarySignal.HardBoundary.class::isInstance));

        SegmentAudioUpdate resumed = feed(assembler, 550, 100, true);
        assertTrue(resumed.boundarySignals().stream()
                .anyMatch(BoundarySignal.SegmentReopened.class::isInstance));
        assertTrue(resumed.closedSegments().isEmpty());
    }

    @Test
    public void hardPauseClosesOneSegmentButNextSpeechUsesSameCaptureTimeline() {
        ContinuousSegmentAssembler assembler = new ContinuousSegmentAssembler(SAMPLE_RATE, POLICY);
        feed(assembler, 0, 200, true);
        SegmentAudioUpdate closed = feed(assembler, 200, 900, false);

        assertEquals(1, closed.closedSegments().size());
        SegmentAudio first = closed.closedSegments().get(0);
        assertEquals(1L, first.segmentId());
        assertEquals(HardBoundaryReason.SILENCE, first.reason());
        assertTrue(first.audioEndSample() <= assembler.capturedEndSample());

        SegmentAudioUpdate next = feed(assembler, 1_100, 160, true);
        BoundarySignal.SegmentOpened opened = next.boundarySignals().stream()
                .filter(BoundarySignal.SegmentOpened.class::isInstance)
                .map(BoundarySignal.SegmentOpened.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(2L, opened.segmentId());
        assertTrue(opened.audioStartSample() < opened.speechStartSample());
    }

    @Test
    public void maximumSegmentClosesWithOverlapAndDoesNotStopAssembler() {
        ContinuousSegmentAssembler assembler = new ContinuousSegmentAssembler(SAMPLE_RATE, POLICY);
        List<SegmentAudio> closed = new ArrayList<>();
        long cursor = 0L;
        while (cursor < 2_300L) {
            closed.addAll(feed(assembler, cursor, 100, true).closedSegments());
            cursor += 100L;
        }

        assertEquals(1, closed.size());
        SegmentAudio first = closed.get(0);
        assertEquals(HardBoundaryReason.MAXIMUM_SEGMENT, first.reason());
        assertFalse(assembler.terminal());

        SegmentAudioUpdate finish = assembler.finish();
        assertEquals(1, finish.closedSegments().size());
        assertEquals(2L, finish.closedSegments().get(0).segmentId());
    }

    @Test
    public void explicitFinishFlushesExactRetainedSamples() {
        ContinuousSegmentAssembler assembler = new ContinuousSegmentAssembler(SAMPLE_RATE, POLICY);
        short[] first = ramp(0, 80);
        short[] second = ramp(80, 80);
        assembler.accept(new Pcm16Chunk(0L, first), true);
        assembler.accept(new Pcm16Chunk(80L, second), true);

        SegmentAudioUpdate finish = assembler.finish();

        assertEquals(1, finish.closedSegments().size());
        SegmentAudio audio = finish.closedSegments().get(0);
        assertEquals(HardBoundaryReason.EXPLICIT_FINISH, audio.reason());
        short[] expected = new short[160];
        System.arraycopy(first, 0, expected, 0, first.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        assertArrayEquals(expected, audio.samples());
        assertEquals(160L, audio.durationMs());
    }

    @Test
    public void explicitDiscardReturnsNothingAndRejectsLateFrames() {
        ContinuousSegmentAssembler assembler = new ContinuousSegmentAssembler(SAMPLE_RATE, POLICY);
        feed(assembler, 0, 160, true);

        assembler.discard();

        assertTrue(assembler.terminal());
        assertTrue(assembler.discarded());
        assertThrows(
                IllegalStateException.class,
                () -> feed(assembler, 160, 40, true));
        assertThrows(IllegalStateException.class, assembler::finish);
    }

    @Test
    public void chunksMustRemainContiguous() {
        ContinuousSegmentAssembler assembler = new ContinuousSegmentAssembler(SAMPLE_RATE, POLICY);
        feed(assembler, 0, 40, false);
        assertThrows(
                IllegalArgumentException.class,
                () -> feed(assembler, 80, 40, false));
    }

    private static SegmentAudioUpdate feed(
            ContinuousSegmentAssembler assembler,
            long start,
            int samples,
            boolean speech) {
        return assembler.accept(new Pcm16Chunk(start, ramp((int) start, samples)), speech);
    }

    private static short[] ramp(int start, int count) {
        short[] result = new short[count];
        for (int index = 0; index < count; index++) result[index] = (short) (start + index);
        return result;
    }
}
