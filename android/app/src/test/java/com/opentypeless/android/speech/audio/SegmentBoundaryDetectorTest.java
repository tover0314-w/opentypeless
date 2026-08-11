package com.opentypeless.android.speech.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class SegmentBoundaryDetectorTest {
    private static final int SAMPLE_RATE = 16_000;
    private static final int FRAME_MS = 20;
    private static final int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1_000;

    @Test
    public void oneToThreeWordShortSpeechOpensAfterBounded120MsEvidence() {
        SegmentBoundaryDetector detector = detector();

        List<BoundarySignal> signals = feed(detector, 0, 200, true);

        BoundarySignal.SegmentOpened opened = only(signals, BoundarySignal.SegmentOpened.class);
        assertEquals(1L, opened.segmentId());
        assertEquals(0L, opened.audioStartSample());
        assertEquals(0L, opened.speechStartSample());
        assertTrue(detector.hasOpenSegment());
    }

    @Test
    public void clickOrCoughShorterThanMinimumDoesNotCreateSegment() {
        SegmentBoundaryDetector detector = detector();
        List<BoundarySignal> signals = new ArrayList<>();

        signals.addAll(feed(detector, 0, 40, true));
        signals.addAll(feed(detector, 40, 1_000, false));

        assertTrue(signals.isEmpty());
        assertFalse(detector.hasOpenSegment());
        assertTrue(detector.finish(samples(1_040)).isEmpty());
    }

    @Test
    public void softPauseProducesProvisionalBoundaryAndResumeDoesNotCloseAudio() {
        SegmentBoundaryDetector detector = detector();
        List<BoundarySignal> signals = new ArrayList<>();
        signals.addAll(feed(detector, 0, 400, true));
        signals.addAll(feed(detector, 400, 600, false));

        BoundarySignal.SoftBoundary soft = only(signals, BoundarySignal.SoftBoundary.class);
        assertEquals(samples(400), soft.candidateSample());
        assertTrue(detector.hasOpenSegment());
        assertTrue(signals.stream().noneMatch(BoundarySignal.HardBoundary.class::isInstance));

        List<BoundarySignal> resumed = feed(detector, 1_000, 200, true);
        BoundarySignal.SegmentReopened reopened =
                only(resumed, BoundarySignal.SegmentReopened.class);
        assertEquals(1L, reopened.segmentId());
        assertTrue(detector.hasOpenSegment());
    }

    @Test
    public void hardPauseClosesOneSegmentButCaptureCanOpenTheNext() {
        SegmentBoundaryDetector detector = detector();
        List<BoundarySignal> first = new ArrayList<>();
        first.addAll(feed(detector, 0, 400, true));
        first.addAll(feed(detector, 400, 2_300, false));

        BoundarySignal.HardBoundary hard = only(first, BoundarySignal.HardBoundary.class);
        assertEquals(1L, hard.segmentId());
        assertEquals(HardBoundaryReason.SILENCE, hard.reason());
        assertFalse(detector.hasOpenSegment());

        List<BoundarySignal> second = feed(detector, 2_700, 200, true);
        BoundarySignal.SegmentOpened opened = only(second, BoundarySignal.SegmentOpened.class);
        assertEquals(2L, opened.segmentId());
        assertTrue(detector.hasOpenSegment());
    }

    @Test
    public void longSilenceDoesNotEmitRepeatedHardBoundaries() {
        SegmentBoundaryDetector detector = detector();
        List<BoundarySignal> signals = new ArrayList<>();
        signals.addAll(feed(detector, 0, 400, true));
        signals.addAll(feed(detector, 400, 5_000, false));

        assertEquals(
                1L,
                signals.stream().filter(BoundarySignal.HardBoundary.class::isInstance).count());
        assertEquals(
                1L,
                signals.stream().filter(BoundarySignal.SoftBoundary.class::isInstance).count());
    }

    @Test
    public void maximumSegmentCreatesQualityBoundaryWithoutStoppingContinuousSpeech() {
        SegmentBoundaryDetector detector = detector();

        List<BoundarySignal> signals = feed(detector, 0, 15_200, true);

        BoundarySignal.HardBoundary hard = only(signals, BoundarySignal.HardBoundary.class);
        assertEquals(HardBoundaryReason.MAXIMUM_SEGMENT, hard.reason());
        assertEquals(samples(15_000), hard.boundarySample());
        List<BoundarySignal.SegmentOpened> opened = ofType(
                signals, BoundarySignal.SegmentOpened.class);
        assertEquals(2, opened.size());
        assertEquals(1L, opened.get(0).segmentId());
        assertEquals(2L, opened.get(1).segmentId());
        assertTrue(detector.hasOpenSegment());
    }

    @Test
    public void explicitFinishFlushesConfirmedTailExactlyOnce() {
        SegmentBoundaryDetector detector = detector();
        feed(detector, 0, 240, true);

        List<BoundarySignal> finished = detector.finish(samples(240));

        BoundarySignal.HardBoundary hard = only(finished, BoundarySignal.HardBoundary.class);
        assertEquals(HardBoundaryReason.EXPLICIT_FINISH, hard.reason());
        assertEquals(samples(240), hard.audioEndSample());
        assertFalse(detector.hasOpenSegment());
        assertTrue(detector.finish(samples(240)).isEmpty());
    }

    @Test
    public void discardEmitsNothingAndCannotBeMistakenForFinish() {
        SegmentBoundaryDetector detector = detector();
        feed(detector, 0, 240, true);

        detector.discard();

        assertFalse(detector.hasOpenSegment());
        assertTrue(detector.finish(samples(240)).isEmpty());
    }

    @Test
    public void frameGapIsRejectedInsteadOfInventingAudio() {
        SegmentBoundaryDetector detector = detector();
        detector.accept(new VadFrame(0L, FRAME_SAMPLES, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> detector.accept(new VadFrame(FRAME_SAMPLES * 2L, FRAME_SAMPLES, true)));
    }

    @Test
    public void policyRejectsAmbiguousOrImpossibleThresholds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EndpointPolicy(120, 500, 400, 10_000, 100, 50));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EndpointPolicy(120, 500, 1_000, 10_000, 100, 600));
    }

    private static SegmentBoundaryDetector detector() {
        return new SegmentBoundaryDetector(SAMPLE_RATE, EndpointPolicy.DEFAULT);
    }

    private static List<BoundarySignal> feed(
            SegmentBoundaryDetector detector, int startMs, int durationMs, boolean speech) {
        if (durationMs % FRAME_MS != 0) {
            throw new IllegalArgumentException("test duration must align to frame size");
        }
        ArrayList<BoundarySignal> signals = new ArrayList<>();
        long start = samples(startMs);
        int frameCount = durationMs / FRAME_MS;
        for (int frame = 0; frame < frameCount; frame++) {
            signals.addAll(detector.accept(new VadFrame(
                    start + (long) frame * FRAME_SAMPLES, FRAME_SAMPLES, speech)));
        }
        return signals;
    }

    private static long samples(int milliseconds) {
        return (long) SAMPLE_RATE * milliseconds / 1_000L;
    }

    private static <T extends BoundarySignal> T only(
            List<BoundarySignal> signals, Class<T> type) {
        List<T> matching = ofType(signals, type);
        assertEquals("signals=" + signals, 1, matching.size());
        return matching.get(0);
    }

    private static <T extends BoundarySignal> List<T> ofType(
            List<BoundarySignal> signals, Class<T> type) {
        return signals.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
