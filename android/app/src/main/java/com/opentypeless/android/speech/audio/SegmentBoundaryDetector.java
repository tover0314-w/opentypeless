package com.opentypeless.android.speech.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure endpoint state machine. It consumes a trusted VAD stream and emits provisional/segment
 * boundaries while capture continues independently.
 */
public final class SegmentBoundaryDetector {
    private final int sampleRate;
    private final long minimumInitialSpeechSamples;
    private final long softSilenceSamples;
    private final long hardSilenceSamples;
    private final long maximumSegmentSamples;
    private final long preRollSamples;
    private final long overlapSamples;

    private long expectedNextSample = -1L;
    private long nextSegmentId = 1L;
    private long currentSegmentId;
    private long currentAudioStart;
    private long candidateSpeechStart = -1L;
    private long candidateSpeechSamples;
    private long lastSpeechEnd;
    private boolean softBoundaryEmitted;
    private long forcedNextAudioStart = -1L;

    public SegmentBoundaryDetector(int sampleRate, EndpointPolicy policy) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sample rate must be positive");
        }
        this.sampleRate = sampleRate;
        EndpointPolicy safePolicy = Objects.requireNonNull(policy, "policy");
        minimumInitialSpeechSamples = samples(safePolicy.minimumInitialSpeechMs());
        softSilenceSamples = samples(safePolicy.softSilenceMs());
        hardSilenceSamples = samples(safePolicy.hardSilenceMs());
        maximumSegmentSamples = samples(safePolicy.maximumSegmentMs());
        preRollSamples = samples(safePolicy.preRollMs());
        overlapSamples = samples(safePolicy.overlapMs());
    }

    public List<BoundarySignal> accept(VadFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (expectedNextSample >= 0L && frame.startSample() != expectedNextSample) {
            throw new IllegalArgumentException("VAD frames must be contiguous");
        }
        expectedNextSample = frame.endSample();
        ArrayList<BoundarySignal> signals = new ArrayList<>(2);

        if (currentSegmentId == 0L) {
            collectInitialSpeech(frame, signals);
            return List.copyOf(signals);
        }

        if (frame.speech()) {
            if (softBoundaryEmitted) {
                signals.add(new BoundarySignal.SegmentReopened(
                        currentSegmentId, frame.startSample()));
                softBoundaryEmitted = false;
            }
            lastSpeechEnd = frame.endSample();
        } else {
            long silence = frame.endSample() - lastSpeechEnd;
            if (!softBoundaryEmitted && silence >= softSilenceSamples) {
                softBoundaryEmitted = true;
                signals.add(new BoundarySignal.SoftBoundary(
                        currentSegmentId, lastSpeechEnd, frame.endSample()));
            }
            if (silence >= hardSilenceSamples) {
                long boundary = lastSpeechEnd + hardSilenceSamples / 2L;
                signals.add(closeCurrent(
                        Math.min(frame.endSample(), boundary + overlapSamples),
                        boundary,
                        HardBoundaryReason.SILENCE));
                forcedNextAudioStart = -1L;
                return List.copyOf(signals);
            }
        }

        if (currentSegmentId != 0L
                && frame.endSample() - currentAudioStart >= maximumSegmentSamples) {
            long boundary = Math.min(frame.endSample(), currentAudioStart + maximumSegmentSamples);
            BoundarySignal.HardBoundary hard = closeCurrent(
                    frame.endSample(), boundary, HardBoundaryReason.MAXIMUM_SEGMENT);
            signals.add(hard);
            forcedNextAudioStart = Math.max(0L, boundary - overlapSamples);
            if (frame.speech()) {
                candidateSpeechStart = boundary;
                candidateSpeechSamples = Math.max(0L, frame.endSample() - boundary);
                maybeOpenCandidate(frame.endSample(), signals);
            }
        }
        return List.copyOf(signals);
    }

    /** Flushes the currently confirmed segment. It does not represent discard. */
    public List<BoundarySignal> finish(long endSample) {
        if (endSample < 0L || (expectedNextSample >= 0L && endSample != expectedNextSample)) {
            throw new IllegalArgumentException("finish sample must equal captured audio end");
        }
        if (currentSegmentId == 0L) {
            resetCandidate();
            return List.of();
        }
        long safeEnd = Math.max(lastSpeechEnd, endSample);
        BoundarySignal.HardBoundary boundary =
                closeCurrent(safeEnd, safeEnd, HardBoundaryReason.EXPLICIT_FINISH);
        forcedNextAudioStart = -1L;
        return List.of(boundary);
    }

    /** Explicit discard resets detector state and deliberately emits no recoverable boundary. */
    public void discard() {
        currentSegmentId = 0L;
        currentAudioStart = 0L;
        lastSpeechEnd = 0L;
        softBoundaryEmitted = false;
        forcedNextAudioStart = -1L;
        resetCandidate();
    }

    public boolean hasOpenSegment() {
        return currentSegmentId != 0L;
    }

    public long expectedNextSample() {
        return Math.max(0L, expectedNextSample);
    }

    private void collectInitialSpeech(VadFrame frame, List<BoundarySignal> signals) {
        if (!frame.speech()) {
            resetCandidate();
            return;
        }
        if (candidateSpeechStart < 0L) {
            candidateSpeechStart = frame.startSample();
            candidateSpeechSamples = 0L;
        }
        candidateSpeechSamples += frame.sampleCount();
        maybeOpenCandidate(frame.endSample(), signals);
    }

    private void maybeOpenCandidate(long speechEndSample, List<BoundarySignal> signals) {
        if (candidateSpeechStart < 0L || candidateSpeechSamples < minimumInitialSpeechSamples) {
            return;
        }
        long normalAudioStart = Math.max(0L, candidateSpeechStart - preRollSamples);
        currentAudioStart = forcedNextAudioStart >= 0L
                ? Math.min(normalAudioStart, forcedNextAudioStart)
                : normalAudioStart;
        currentSegmentId = nextSegmentId++;
        lastSpeechEnd = speechEndSample;
        softBoundaryEmitted = false;
        signals.add(new BoundarySignal.SegmentOpened(
                currentSegmentId, currentAudioStart, candidateSpeechStart));
        forcedNextAudioStart = -1L;
        resetCandidate();
    }

    private BoundarySignal.HardBoundary closeCurrent(
            long requestedAudioEnd, long boundary, HardBoundaryReason reason) {
        long audioEnd = Math.max(currentAudioStart + 1L, requestedAudioEnd);
        long safeBoundary = Math.max(currentAudioStart, Math.min(boundary, audioEnd));
        long nextPreRoll = Math.max(currentAudioStart, safeBoundary - overlapSamples);
        BoundarySignal.HardBoundary result = new BoundarySignal.HardBoundary(
                currentSegmentId,
                currentAudioStart,
                audioEnd,
                safeBoundary,
                nextPreRoll,
                reason);
        currentSegmentId = 0L;
        currentAudioStart = 0L;
        lastSpeechEnd = 0L;
        softBoundaryEmitted = false;
        resetCandidate();
        return result;
    }

    private void resetCandidate() {
        candidateSpeechStart = -1L;
        candidateSpeechSamples = 0L;
    }

    private long samples(int milliseconds) {
        return Math.max(1L, Math.multiplyExact((long) sampleRate, milliseconds) / 1_000L);
    }
}
