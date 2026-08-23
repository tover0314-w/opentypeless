package com.opentypeless.android.speech.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable recognition and delivery state for one document segment. */
public record VoiceSegment(
        long segmentId,
        SegmentJoin joinBefore,
        SegmentStage stage,
        DeliveryState deliveryState,
        List<SegmentRevision> revisions,
        int visibleRevisionIndex) {

    public VoiceSegment {
        Objects.requireNonNull(joinBefore, "joinBefore");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(deliveryState, "deliveryState");
        revisions = List.copyOf(Objects.requireNonNull(revisions, "revisions"));
        if (segmentId <= 0L) {
            throw new IllegalArgumentException("segment id must be positive");
        }
        if (revisions.isEmpty()) {
            if (visibleRevisionIndex != -1) {
                throw new IllegalArgumentException("empty segment has no visible revision");
            }
        } else if (visibleRevisionIndex < 0 || visibleRevisionIndex >= revisions.size()) {
            throw new IllegalArgumentException("visible revision index is outside revision history");
        }
        long previousId = 0L;
        for (SegmentRevision revision : revisions) {
            if (revision.segmentId() != segmentId || revision.revisionId() <= previousId) {
                throw new IllegalArgumentException("revision history must match and be monotonic");
            }
            previousId = revision.revisionId();
        }
    }

    public static VoiceSegment open(long segmentId, SegmentJoin joinBefore) {
        return new VoiceSegment(
                segmentId,
                joinBefore,
                SegmentStage.OPEN,
                DeliveryState.NOT_PROJECTED,
                List.of(),
                -1);
    }

    public Optional<SegmentRevision> visibleRevision() {
        return visibleRevisionIndex < 0
                ? Optional.empty()
                : Optional.of(revisions.get(visibleRevisionIndex));
    }

    public String visibleText() {
        return visibleRevision().map(SegmentRevision::fullText).orElse("");
    }

    public long lastRevisionId() {
        return revisions.isEmpty() ? 0L : revisions.get(revisions.size() - 1).revisionId();
    }

    public boolean userLocked() {
        return visibleRevision()
                .map(revision -> revision.stage() == RevisionStage.USER_LOCKED)
                .orElse(false);
    }

    VoiceSegment append(SegmentRevision revision) {
        ArrayList<SegmentRevision> updated = new ArrayList<>(revisions);
        updated.add(revision);
        return new VoiceSegment(
                segmentId, joinBefore, stage, deliveryState, updated, updated.size() - 1);
    }

    VoiceSegment withStage(SegmentStage updatedStage) {
        return new VoiceSegment(
                segmentId,
                joinBefore,
                updatedStage,
                deliveryState,
                revisions,
                visibleRevisionIndex);
    }

    VoiceSegment withDelivery(DeliveryState updatedDelivery) {
        return new VoiceSegment(
                segmentId,
                joinBefore,
                stage,
                updatedDelivery,
                revisions,
                visibleRevisionIndex);
    }

    VoiceSegment reopen() {
        int restoredIndex = visibleRevisionIndex;
        while (restoredIndex >= 0
                && revisions.get(restoredIndex).stage() == RevisionStage.PROVISIONAL) {
            restoredIndex--;
        }
        return new VoiceSegment(
                segmentId,
                joinBefore,
                SegmentStage.OPEN,
                deliveryState,
                revisions,
                restoredIndex);
    }
}
