package com.opentypeless.android.speech.engine;

import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SegmentRevision;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.speech.core.TerminalReason;
import com.opentypeless.android.speech.core.VoiceDraftEvent;
import java.util.Objects;

/** Normalized orchestration/engine events suitable for deterministic replay. */
public sealed interface EngineEvent
        permits EngineEvent.Prepare,
                EngineEvent.Ready,
                EngineEvent.StopRequested,
                EngineEvent.OpenSegment,
                EngineEvent.SoftBoundary,
                EngineEvent.ReopenSegment,
                EngineEvent.HardBoundary,
                EngineEvent.Transcript,
                EngineEvent.SealSegment,
                EngineEvent.CaptureEnded,
                EngineEvent.CaptureFailed {

    SessionId sessionId();

    String engineId();

    long eventSequence();

    VoiceDraftEvent toCoreEvent();

    record Prepare(SessionId sessionId, String engineId, long eventSequence)
            implements EngineEvent {
        public Prepare {
            validate(sessionId, engineId, eventSequence);
        }

        @Override
        public VoiceDraftEvent toCoreEvent() {
            return new VoiceDraftEvent.Prepare(sessionId);
        }
    }

    record Ready(SessionId sessionId, String engineId, long eventSequence) implements EngineEvent {
        public Ready {
            validate(sessionId, engineId, eventSequence);
        }

        @Override
        public VoiceDraftEvent toCoreEvent() {
            return new VoiceDraftEvent.Ready(sessionId);
        }
    }

    record StopRequested(SessionId sessionId, String engineId, long eventSequence)
            implements EngineEvent {
        public StopRequested {
            validate(sessionId, engineId, eventSequence);
        }

        @Override
        public VoiceDraftEvent toCoreEvent() {
            return new VoiceDraftEvent.StopRequested(sessionId);
        }
    }

    record OpenSegment(
            SessionId sessionId,
            String engineId,
            long eventSequence,
            long segmentId,
            SegmentJoin joinBefore)
            implements EngineEvent {
        public OpenSegment {
            validate(sessionId, engineId, eventSequence);
            requireSegmentId(segmentId);
            Objects.requireNonNull(joinBefore, "joinBefore");
        }

        @Override
        public VoiceDraftEvent toCoreEvent() {
            return new VoiceDraftEvent.OpenSegment(sessionId, segmentId, joinBefore);
        }
    }

    record SoftBoundary(
            SessionId sessionId, String engineId, long eventSequence, long segmentId)
            implements EngineEvent {
        public SoftBoundary {
            validate(sessionId, engineId, eventSequence);
            requireSegmentId(segmentId);
        }

        @Override
        public VoiceDraftEvent toCoreEvent() {
            return new VoiceDraftEvent.SoftBoundary(sessionId, segmentId);
        }
    }

    record ReopenSegment(
            SessionId sessionId, String engineId, long eventSequence, long segmentId)
            implements EngineEvent {
        public ReopenSegment {
            validate(sessionId, engineId, eventSequence);
            requireSegmentId(segmentId);
        }

        @Override
        public VoiceDraftEvent toCoreEvent() {
            return new VoiceDraftEvent.ReopenSegment(sessionId, segmentId);
        }
    }

    record HardBoundary(
            SessionId sessionId, String engineId, long eventSequence, long segmentId)
            implements EngineEvent {
        public HardBoundary {
            validate(sessionId, engineId, eventSequence);
            requireSegmentId(segmentId);
        }

        @Override
        public VoiceDraftEvent toCoreEvent() {
            return new VoiceDraftEvent.HardBoundary(sessionId, segmentId);
        }
    }

    record Transcript(String engineId, long eventSequence, SegmentRevision revision)
            implements EngineEvent {
        public Transcript {
            EngineDescriptor.requireSafeText(
                    engineId, "engineId", EngineDescriptor.MAX_ID_CODE_POINTS);
            requireSequence(eventSequence);
            Objects.requireNonNull(revision, "revision");
        }

        @Override
        public SessionId sessionId() {
            return revision.sessionId();
        }

        @Override
        public VoiceDraftEvent toCoreEvent() {
            return new VoiceDraftEvent.RevisionArrived(revision);
        }
    }

    record SealSegment(
            SessionId sessionId, String engineId, long eventSequence, long segmentId)
            implements EngineEvent {
        public SealSegment {
            validate(sessionId, engineId, eventSequence);
            requireSegmentId(segmentId);
        }

        @Override
        public VoiceDraftEvent toCoreEvent() {
            return new VoiceDraftEvent.SealSegment(sessionId, segmentId);
        }
    }

    record CaptureEnded(
            SessionId sessionId,
            String engineId,
            long eventSequence,
            TerminalReason reason)
            implements EngineEvent {
        public CaptureEnded {
            validate(sessionId, engineId, eventSequence);
            Objects.requireNonNull(reason, "reason");
            requireNonDiscardTerminalReason(reason);
        }

        @Override
        public VoiceDraftEvent toCoreEvent() {
            return new VoiceDraftEvent.CaptureEnded(sessionId, reason);
        }
    }

    record CaptureFailed(
            SessionId sessionId,
            String engineId,
            long eventSequence,
            TerminalReason reason)
            implements EngineEvent {
        public CaptureFailed {
            validate(sessionId, engineId, eventSequence);
            Objects.requireNonNull(reason, "reason");
            requireNonDiscardTerminalReason(reason);
        }

        @Override
        public VoiceDraftEvent toCoreEvent() {
            return new VoiceDraftEvent.CaptureFailed(sessionId, reason);
        }
    }

    private static void validate(SessionId sessionId, String engineId, long eventSequence) {
        Objects.requireNonNull(sessionId, "sessionId");
        EngineDescriptor.requireSafeText(
                engineId, "engineId", EngineDescriptor.MAX_ID_CODE_POINTS);
        requireSequence(eventSequence);
    }

    private static void requireSequence(long eventSequence) {
        if (eventSequence <= 0L) {
            throw new IllegalArgumentException("event sequence must be positive");
        }
    }

    private static void requireSegmentId(long segmentId) {
        if (segmentId <= 0L) {
            throw new IllegalArgumentException("segment id must be positive");
        }
    }

    private static void requireNonDiscardTerminalReason(TerminalReason reason) {
        if (reason == TerminalReason.NONE || reason == TerminalReason.EXPLICIT_DISCARD) {
            throw new IllegalArgumentException("engine event has invalid terminal reason");
        }
    }
}
