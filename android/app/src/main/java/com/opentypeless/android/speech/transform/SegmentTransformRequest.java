package com.opentypeless.android.speech.transform;

import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.speech.core.SegmentRevision;
import java.util.Objects;

/** One immutable transform pass over a complete segment revision. */
public record SegmentTransformRequest(
        SegmentRevision source,
        long firstRevisionId,
        TransformPhase phase,
        String punctuationCandidate,
        String inverseTextNormalizationCandidate,
        PersonalizationSnapshot personalization,
        SegmentTransformPolicy policy) {

    public SegmentTransformRequest {
        Objects.requireNonNull(source, "source");
        if (firstRevisionId <= source.revisionId()) {
            throw new IllegalArgumentException("transform revision id must advance the source");
        }
        Objects.requireNonNull(phase, "phase");
        personalization = personalization == null
                ? PersonalizationSnapshot.empty()
                : personalization;
        policy = policy == null ? SegmentTransformPolicy.DEFAULT : policy;
        validatePhase(source, phase);
    }

    private static void validatePhase(SegmentRevision source, TransformPhase phase) {
        switch (phase) {
            case LIVE -> {
                if (source.stage() != com.opentypeless.android.speech.core.RevisionStage.LIVE) {
                    throw new IllegalArgumentException("live transforms require a live source");
                }
            }
            case SOFT_BOUNDARY -> {
                if (source.stage() != com.opentypeless.android.speech.core.RevisionStage.LIVE
                        && source.stage()
                                != com.opentypeless.android.speech.core.RevisionStage.PROVISIONAL) {
                    throw new IllegalArgumentException(
                            "soft-boundary transforms require a revisable source");
                }
            }
            case REFINED -> {
                if (source.stage() != com.opentypeless.android.speech.core.RevisionStage.REFINED) {
                    throw new IllegalArgumentException("refined transforms require a refined source");
                }
            }
        }
    }
}
