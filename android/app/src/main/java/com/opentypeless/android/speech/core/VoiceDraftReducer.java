package com.opentypeless.android.speech.core;

import java.util.Objects;
import java.util.Optional;

/** Android-free, deterministic state transition authority for Speech Core v2. */
public final class VoiceDraftReducer {
    private final VoiceDraftLimits limits;

    public VoiceDraftReducer() {
        this(VoiceDraftLimits.DEFAULT);
    }

    public VoiceDraftReducer(VoiceDraftLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public VoiceDraftReduction reduce(VoiceDraft draft, VoiceDraftEvent event) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(event, "event");
        if (!draft.sessionId().equals(event.sessionId())) {
            return keep(draft, ReductionDisposition.REJECTED_SESSION, "event session is stale");
        }
        if (draft.captureState() == CaptureState.DISCARDED) {
            return keep(draft, ReductionDisposition.IGNORED_TERMINAL, "draft was discarded");
        }

        if (event instanceof VoiceDraftEvent.Prepare) {
            return captureTransition(draft, CaptureState.IDLE, CaptureState.PREPARING);
        }
        if (event instanceof VoiceDraftEvent.Ready) {
            return captureTransition(draft, CaptureState.PREPARING, CaptureState.LISTENING);
        }
        if (event instanceof VoiceDraftEvent.StopRequested) {
            if (draft.captureState() == CaptureState.STOPPING) {
                return keep(draft, ReductionDisposition.IGNORED_DUPLICATE, "stop already requested");
            }
            if (draft.captureState() != CaptureState.PREPARING
                    && draft.captureState() != CaptureState.LISTENING) {
                return transitionRejected(draft, "capture cannot stop from current state");
            }
            return applied(
                    draft.withCapture(CaptureState.STOPPING, TerminalReason.NONE),
                    "capture stop requested");
        }
        if (event instanceof VoiceDraftEvent.CaptureEnded ended) {
            if (draft.captureState() == CaptureState.ENDED
                    && draft.terminalReason() == ended.reason()) {
                return keep(draft, ReductionDisposition.IGNORED_DUPLICATE, "capture already ended");
            }
            if (isCaptureTerminal(draft.captureState())) {
                return transitionRejected(draft, "capture already reached another terminal state");
            }
            return applied(
                    draft.withCapture(CaptureState.ENDED, ended.reason()), "capture ended safely");
        }
        if (event instanceof VoiceDraftEvent.CaptureFailed failed) {
            if (draft.captureState() == CaptureState.FAILED
                    && draft.terminalReason() == failed.reason()) {
                return keep(draft, ReductionDisposition.IGNORED_DUPLICATE, "failure already recorded");
            }
            if (isCaptureTerminal(draft.captureState())) {
                return transitionRejected(draft, "capture already reached another terminal state");
            }
            return applied(
                    draft.withCapture(CaptureState.FAILED, failed.reason()),
                    "capture failure preserved draft");
        }
        if (event instanceof VoiceDraftEvent.ExplicitDiscard) {
            return applied(draft.discarded(), "explicit discard cleared the draft");
        }
        if (event instanceof VoiceDraftEvent.OpenSegment opened) {
            return openSegment(draft, opened);
        }
        if (event instanceof VoiceDraftEvent.SoftBoundary boundary) {
            return softBoundary(draft, boundary.segmentId());
        }
        if (event instanceof VoiceDraftEvent.ReopenSegment reopened) {
            return reopenSegment(draft, reopened.segmentId());
        }
        if (event instanceof VoiceDraftEvent.HardBoundary boundary) {
            return hardBoundary(draft, boundary.segmentId());
        }
        if (event instanceof VoiceDraftEvent.RevisionArrived arrived) {
            return revision(draft, arrived.revision());
        }
        if (event instanceof VoiceDraftEvent.SealSegment sealed) {
            return seal(draft, sealed.segmentId());
        }
        if (event instanceof VoiceDraftEvent.DeliveryChanged changed) {
            return delivery(draft, changed.segmentId(), changed.deliveryState());
        }
        if (event instanceof VoiceDraftEvent.TargetDetached detached) {
            return detach(draft, detached.segmentId());
        }
        throw new IllegalArgumentException("unsupported event type: " + event.getClass());
    }

    private VoiceDraftReduction captureTransition(
            VoiceDraft draft, CaptureState expected, CaptureState updated) {
        if (draft.captureState() == updated) {
            return keep(draft, ReductionDisposition.IGNORED_DUPLICATE, "capture state already applied");
        }
        if (draft.captureState() != expected) {
            return transitionRejected(draft, "invalid capture transition");
        }
        return applied(draft.withCapture(updated, TerminalReason.NONE), "capture state advanced");
    }

    private VoiceDraftReduction openSegment(
            VoiceDraft draft, VoiceDraftEvent.OpenSegment event) {
        if (draft.captureState() != CaptureState.LISTENING) {
            return transitionRejected(draft, "segment requires listening capture");
        }
        if (draft.activeSegment().isPresent()) {
            return transitionRejected(draft, "another segment is already active");
        }
        if (event.segmentId() <= draft.maxSegmentId()) {
            return keep(draft, ReductionDisposition.IGNORED_STALE, "segment id is not monotonic");
        }
        if (draft.segments().size() >= limits.maxSegments()) {
            return boundsRejected(draft, "segment limit reached");
        }
        if (draft.segments().isEmpty() && event.joinBefore() != SegmentJoin.NONE) {
            return transitionRejected(draft, "first segment cannot have a leading separator");
        }
        return applied(
                draft.addSegment(VoiceSegment.open(event.segmentId(), event.joinBefore())),
                "segment opened");
    }

    private VoiceDraftReduction softBoundary(VoiceDraft draft, long segmentId) {
        Optional<VoiceSegment> found = activeSegment(draft, segmentId);
        if (found.isEmpty()) {
            return missingOrInactive(draft, segmentId);
        }
        VoiceSegment segment = found.get();
        if (segment.stage() == SegmentStage.SOFT_BOUNDARY) {
            return keep(draft, ReductionDisposition.IGNORED_DUPLICATE, "soft boundary already active");
        }
        if (segment.stage() != SegmentStage.OPEN) {
            return transitionRejected(draft, "soft boundary requires an open segment");
        }
        return applied(
                draft.replaceSegment(segment.withStage(SegmentStage.SOFT_BOUNDARY)),
                "soft boundary recorded");
    }

    private VoiceDraftReduction reopenSegment(VoiceDraft draft, long segmentId) {
        Optional<VoiceSegment> found = activeSegment(draft, segmentId);
        if (found.isEmpty()) {
            return missingOrInactive(draft, segmentId);
        }
        VoiceSegment segment = found.get();
        if (segment.stage() == SegmentStage.OPEN) {
            return keep(draft, ReductionDisposition.IGNORED_DUPLICATE, "segment is already open");
        }
        if (segment.stage() != SegmentStage.SOFT_BOUNDARY) {
            return transitionRejected(draft, "only a soft boundary can reopen");
        }
        return applied(draft.replaceSegment(segment.reopen()), "provisional boundary reopened");
    }

    private VoiceDraftReduction hardBoundary(VoiceDraft draft, long segmentId) {
        Optional<VoiceSegment> found = activeSegment(draft, segmentId);
        if (found.isEmpty()) {
            return missingOrInactive(draft, segmentId);
        }
        VoiceSegment segment = found.get();
        if (segment.stage() != SegmentStage.OPEN
                && segment.stage() != SegmentStage.SOFT_BOUNDARY) {
            return transitionRejected(draft, "hard boundary requires an active segment");
        }
        VoiceDraft updated =
                draft.replaceSegmentAndClearActive(segment.withStage(SegmentStage.REFINING));
        return applied(updated, "hard boundary closed segment audio");
    }

    private VoiceDraftReduction revision(VoiceDraft draft, SegmentRevision revision) {
        Optional<VoiceSegment> found = draft.segment(revision.segmentId());
        if (found.isEmpty()) {
            return keep(
                    draft,
                    ReductionDisposition.REJECTED_MISSING_SEGMENT,
                    "revision segment is missing");
        }
        VoiceSegment segment = found.get();
        long lastRevisionId = segment.lastRevisionId();
        if (revision.revisionId() < lastRevisionId) {
            return keep(draft, ReductionDisposition.IGNORED_STALE, "revision id is stale");
        }
        if (revision.revisionId() == lastRevisionId) {
            SegmentRevision previous = segment.revisions().get(segment.revisions().size() - 1);
            ReductionDisposition disposition = previous.equals(revision)
                    ? ReductionDisposition.IGNORED_DUPLICATE
                    : ReductionDisposition.REJECTED_CONFLICT;
            return keep(draft, disposition, "revision id was already consumed");
        }
        if (segment.userLocked() && revision.stage() != RevisionStage.USER_LOCKED) {
            return keep(draft, ReductionDisposition.REJECTED_LOCKED, "user revision owns segment");
        }
        if (revision.stage() != RevisionStage.USER_LOCKED && isBlank(revision.fullText())) {
            return keep(draft, ReductionDisposition.IGNORED_BLANK, "blank model revision ignored");
        }
        if (!stageAccepts(segment, revision)) {
            return transitionRejected(draft, "revision stage does not match segment stage");
        }
        if (segment.revisions().size() >= limits.maxRevisionsPerSegment()) {
            return boundsRejected(draft, "revision history limit reached");
        }
        int segmentCodePoints = revision.fullText().codePointCount(0, revision.fullText().length());
        if (segmentCodePoints > limits.maxSegmentCodePoints()) {
            return boundsRejected(draft, "segment text limit reached");
        }

        VoiceSegment updatedSegment = segment.append(revision);
        VoiceDraft updated;
        if (revision.stage() == RevisionStage.USER_LOCKED) {
            updatedSegment = updatedSegment.withStage(SegmentStage.SEALED);
            updated = draft.activeSegment().isPresent()
                            && draft.activeSegment().getAsLong() == segment.segmentId()
                    ? draft.replaceSegmentAndClearActive(updatedSegment)
                    : draft.replaceSegment(updatedSegment);
        } else {
            updated = draft.replaceSegment(updatedSegment);
        }
        if (updated.visibleCodePoints() > limits.maxDraftCodePoints()) {
            return boundsRejected(draft, "draft text limit reached");
        }
        return applied(updated, "revision accepted");
    }

    private VoiceDraftReduction seal(VoiceDraft draft, long segmentId) {
        Optional<VoiceSegment> found = draft.segment(segmentId);
        if (found.isEmpty()) {
            return keep(
                    draft,
                    ReductionDisposition.REJECTED_MISSING_SEGMENT,
                    "sealed segment is missing");
        }
        VoiceSegment segment = found.get();
        if (segment.stage() == SegmentStage.SEALED) {
            return keep(draft, ReductionDisposition.IGNORED_DUPLICATE, "segment already sealed");
        }
        if (segment.stage() != SegmentStage.REFINING || isBlank(segment.visibleText())) {
            return transitionRejected(draft, "segment is not ready to seal");
        }
        return applied(
                draft.replaceSegment(segment.withStage(SegmentStage.SEALED)), "segment sealed");
    }

    private VoiceDraftReduction delivery(
            VoiceDraft draft, long segmentId, DeliveryState requested) {
        Optional<VoiceSegment> found = draft.segment(segmentId);
        if (found.isEmpty()) {
            return keep(
                    draft,
                    ReductionDisposition.REJECTED_MISSING_SEGMENT,
                    "delivery segment is missing");
        }
        VoiceSegment segment = found.get();
        if (segment.deliveryState() == requested) {
            return keep(draft, ReductionDisposition.IGNORED_DUPLICATE, "delivery state unchanged");
        }
        if (!deliveryAccepts(segment.deliveryState(), requested)) {
            return transitionRejected(draft, "invalid delivery transition");
        }
        return applied(
                draft.replaceSegment(segment.withDelivery(requested)), "delivery state advanced");
    }

    private VoiceDraftReduction detach(VoiceDraft draft, long segmentId) {
        Optional<VoiceSegment> found = draft.segment(segmentId);
        if (found.isEmpty()) {
            return keep(
                    draft,
                    ReductionDisposition.REJECTED_MISSING_SEGMENT,
                    "detached segment is missing");
        }
        VoiceSegment segment = found.get();
        if (segment.deliveryState() == DeliveryState.COMPOSING) {
            return applied(
                    draft.replaceSegment(segment.withDelivery(DeliveryState.FROZEN)),
                    "composing projection frozen");
        }
        return keep(
                draft,
                ReductionDisposition.IGNORED_DUPLICATE,
                "segment has no mutable editor projection");
    }

    private Optional<VoiceSegment> activeSegment(VoiceDraft draft, long segmentId) {
        if (draft.activeSegment().isEmpty() || draft.activeSegment().getAsLong() != segmentId) {
            return Optional.empty();
        }
        return draft.segment(segmentId);
    }

    private VoiceDraftReduction missingOrInactive(VoiceDraft draft, long segmentId) {
        return draft.segment(segmentId).isEmpty()
                ? keep(
                        draft,
                        ReductionDisposition.REJECTED_MISSING_SEGMENT,
                        "segment is missing")
                : transitionRejected(draft, "segment is no longer active");
    }

    private static boolean stageAccepts(VoiceSegment segment, SegmentRevision revision) {
        if (revision.stage() == RevisionStage.USER_LOCKED) {
            return true;
        }
        return switch (segment.stage()) {
            case OPEN -> revision.stage() == RevisionStage.LIVE;
            case SOFT_BOUNDARY -> revision.stage() == RevisionStage.PROVISIONAL;
            case REFINING -> revision.stage() == RevisionStage.REFINED
                    || (revision.stage() == RevisionStage.LIVE && revision.providerFinal());
            case SEALED -> false;
        };
    }

    private static boolean deliveryAccepts(DeliveryState current, DeliveryState requested) {
        return switch (current) {
            case NOT_PROJECTED -> requested == DeliveryState.COMPOSING
                    || requested == DeliveryState.RECOVERABLE;
            case COMPOSING -> requested == DeliveryState.FROZEN
                    || requested == DeliveryState.COMMITTED
                    || requested == DeliveryState.RECOVERABLE;
            case FROZEN -> requested == DeliveryState.RECOVERABLE;
            case RECOVERABLE -> requested == DeliveryState.COMMITTED;
            case COMMITTED -> false;
        };
    }

    private static boolean isCaptureTerminal(CaptureState state) {
        return state == CaptureState.ENDED
                || state == CaptureState.FAILED
                || state == CaptureState.DISCARDED;
    }

    private static boolean isBlank(String text) {
        return text.isEmpty() || text.codePoints().allMatch(Character::isWhitespace);
    }

    private static VoiceDraftReduction applied(VoiceDraft draft, String detail) {
        return new VoiceDraftReduction(draft, ReductionDisposition.APPLIED, detail);
    }

    private static VoiceDraftReduction keep(
            VoiceDraft draft, ReductionDisposition disposition, String detail) {
        return new VoiceDraftReduction(draft, disposition, detail);
    }

    private static VoiceDraftReduction transitionRejected(VoiceDraft draft, String detail) {
        return keep(draft, ReductionDisposition.REJECTED_TRANSITION, detail);
    }

    private static VoiceDraftReduction boundsRejected(VoiceDraft draft, String detail) {
        return keep(draft, ReductionDisposition.REJECTED_BOUNDS, detail);
    }
}
