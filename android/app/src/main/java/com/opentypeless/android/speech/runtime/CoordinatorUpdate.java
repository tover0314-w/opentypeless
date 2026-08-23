package com.opentypeless.android.speech.runtime;

import com.opentypeless.android.speech.core.VoiceDraft;
import com.opentypeless.android.speech.core.VoiceDraftEvent;
import com.opentypeless.android.speech.transform.TransformAudit;
import java.util.List;
import java.util.Objects;

/** Pure coordinator output. Android shells persist/project/start jobs only after receiving it. */
public record CoordinatorUpdate(
        VoiceDraft draft,
        CoordinatorDisposition disposition,
        String detail,
        List<VoiceDraftEvent> acceptedEvents,
        List<TransformAudit> transformAudits,
        List<QualityJobToken> qualityJobsToStart,
        boolean projectionChanged) {
    public CoordinatorUpdate {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(disposition, "disposition");
        detail = Objects.requireNonNullElse(detail, "");
        acceptedEvents = List.copyOf(Objects.requireNonNull(acceptedEvents, "acceptedEvents"));
        transformAudits =
                List.copyOf(Objects.requireNonNull(transformAudits, "transformAudits"));
        qualityJobsToStart =
                List.copyOf(Objects.requireNonNull(qualityJobsToStart, "qualityJobsToStart"));
    }
}
