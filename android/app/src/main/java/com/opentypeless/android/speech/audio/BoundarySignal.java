package com.opentypeless.android.speech.audio;

/** Signals consumed by the coordinator; none of them implicitly stops the microphone. */
public sealed interface BoundarySignal
        permits BoundarySignal.SegmentOpened,
                BoundarySignal.SoftBoundary,
                BoundarySignal.SegmentReopened,
                BoundarySignal.HardBoundary {

    long segmentId();

    record SegmentOpened(long segmentId, long audioStartSample, long speechStartSample)
            implements BoundarySignal {
        public SegmentOpened {
            requireSegment(segmentId);
            if (audioStartSample < 0L || speechStartSample < audioStartSample) {
                throw new IllegalArgumentException("invalid opened segment span");
            }
        }
    }

    record SoftBoundary(long segmentId, long candidateSample, long observedAtSample)
            implements BoundarySignal {
        public SoftBoundary {
            requireSegment(segmentId);
            if (candidateSample < 0L || observedAtSample < candidateSample) {
                throw new IllegalArgumentException("invalid soft boundary span");
            }
        }
    }

    record SegmentReopened(long segmentId, long speechResumedAtSample)
            implements BoundarySignal {
        public SegmentReopened {
            requireSegment(segmentId);
            if (speechResumedAtSample < 0L) {
                throw new IllegalArgumentException("invalid resume sample");
            }
        }
    }

    record HardBoundary(
            long segmentId,
            long audioStartSample,
            long audioEndSample,
            long boundarySample,
            long nextPreRollStartSample,
            HardBoundaryReason reason)
            implements BoundarySignal {
        public HardBoundary {
            requireSegment(segmentId);
            if (audioStartSample < 0L
                    || audioEndSample <= audioStartSample
                    || boundarySample < audioStartSample
                    || boundarySample > audioEndSample
                    || nextPreRollStartSample < audioStartSample
                    || nextPreRollStartSample > audioEndSample
                    || reason == null) {
                throw new IllegalArgumentException("invalid hard boundary span");
            }
        }
    }

    private static void requireSegment(long segmentId) {
        if (segmentId <= 0L) {
            throw new IllegalArgumentException("segment id must be positive");
        }
    }
}
