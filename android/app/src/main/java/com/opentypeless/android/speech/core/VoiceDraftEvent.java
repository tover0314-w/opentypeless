package com.opentypeless.android.speech.core;

import java.util.Objects;

/** Closed event vocabulary accepted by {@link VoiceDraftReducer}. */
public sealed interface VoiceDraftEvent
        permits VoiceDraftEvent.Prepare,
                VoiceDraftEvent.Ready,
                VoiceDraftEvent.StopRequested,
                VoiceDraftEvent.CaptureEnded,
                VoiceDraftEvent.CaptureFailed,
                VoiceDraftEvent.ExplicitDiscard,
                VoiceDraftEvent.OpenSegment,
                VoiceDraftEvent.SoftBoundary,
                VoiceDraftEvent.ReopenSegment,
                VoiceDraftEvent.HardBoundary,
                VoiceDraftEvent.RevisionArrived,
                VoiceDraftEvent.SealSegment,
                VoiceDraftEvent.DeliveryChanged,
                VoiceDraftEvent.TargetDetached {

    SessionId sessionId();

    record Prepare(SessionId sessionId) implements VoiceDraftEvent {
        public Prepare {
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    record Ready(SessionId sessionId) implements VoiceDraftEvent {
        public Ready {
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    record StopRequested(SessionId sessionId) implements VoiceDraftEvent {
        public StopRequested {
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    record CaptureEnded(SessionId sessionId, TerminalReason reason) implements VoiceDraftEvent {
        public CaptureEnded {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(reason, "reason");
            if (reason == TerminalReason.NONE || reason == TerminalReason.EXPLICIT_DISCARD) {
                throw new IllegalArgumentException("invalid capture-ended reason");
            }
        }
    }

    record CaptureFailed(SessionId sessionId, TerminalReason reason) implements VoiceDraftEvent {
        public CaptureFailed {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(reason, "reason");
            if (reason == TerminalReason.NONE || reason == TerminalReason.EXPLICIT_DISCARD) {
                throw new IllegalArgumentException("invalid capture-failed reason");
            }
        }
    }

    record ExplicitDiscard(SessionId sessionId) implements VoiceDraftEvent {
        public ExplicitDiscard {
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    record OpenSegment(SessionId sessionId, long segmentId, SegmentJoin joinBefore)
            implements VoiceDraftEvent {
        public OpenSegment {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(joinBefore, "joinBefore");
            requireSegmentId(segmentId);
        }
    }

    record SoftBoundary(SessionId sessionId, long segmentId) implements VoiceDraftEvent {
        public SoftBoundary {
            Objects.requireNonNull(sessionId, "sessionId");
            requireSegmentId(segmentId);
        }
    }

    record ReopenSegment(SessionId sessionId, long segmentId) implements VoiceDraftEvent {
        public ReopenSegment {
            Objects.requireNonNull(sessionId, "sessionId");
            requireSegmentId(segmentId);
        }
    }

    record HardBoundary(SessionId sessionId, long segmentId) implements VoiceDraftEvent {
        public HardBoundary {
            Objects.requireNonNull(sessionId, "sessionId");
            requireSegmentId(segmentId);
        }
    }

    record RevisionArrived(SegmentRevision revision) implements VoiceDraftEvent {
        public RevisionArrived {
            Objects.requireNonNull(revision, "revision");
        }

        @Override
        public SessionId sessionId() {
            return revision.sessionId();
        }
    }

    record SealSegment(SessionId sessionId, long segmentId) implements VoiceDraftEvent {
        public SealSegment {
            Objects.requireNonNull(sessionId, "sessionId");
            requireSegmentId(segmentId);
        }
    }

    record DeliveryChanged(SessionId sessionId, long segmentId, DeliveryState deliveryState)
            implements VoiceDraftEvent {
        public DeliveryChanged {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(deliveryState, "deliveryState");
            requireSegmentId(segmentId);
        }
    }

    record TargetDetached(SessionId sessionId, long segmentId) implements VoiceDraftEvent {
        public TargetDetached {
            Objects.requireNonNull(sessionId, "sessionId");
            requireSegmentId(segmentId);
        }
    }

    private static void requireSegmentId(long segmentId) {
        if (segmentId <= 0L) {
            throw new IllegalArgumentException("segment id must be positive");
        }
    }
}
