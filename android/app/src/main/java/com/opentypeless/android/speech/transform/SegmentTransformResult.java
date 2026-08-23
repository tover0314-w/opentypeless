package com.opentypeless.android.speech.transform;

import com.opentypeless.android.speech.core.SegmentRevision;
import java.util.List;
import java.util.Objects;

public record SegmentTransformResult(
        SegmentRevision finalRevision,
        List<SegmentRevision> emittedRevisions,
        List<TransformAudit> audits,
        List<Long> matchedTermIds,
        List<Long> matchedCorrectionIds) {
    public SegmentTransformResult {
        Objects.requireNonNull(finalRevision, "finalRevision");
        emittedRevisions = List.copyOf(Objects.requireNonNull(emittedRevisions, "emittedRevisions"));
        audits = List.copyOf(Objects.requireNonNull(audits, "audits"));
        matchedTermIds = List.copyOf(Objects.requireNonNull(matchedTermIds, "matchedTermIds"));
        matchedCorrectionIds =
                List.copyOf(Objects.requireNonNull(matchedCorrectionIds, "matchedCorrectionIds"));
    }
}
