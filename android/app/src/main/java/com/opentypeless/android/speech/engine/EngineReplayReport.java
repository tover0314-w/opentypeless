package com.opentypeless.android.speech.engine;

import com.opentypeless.android.speech.core.VoiceDraft;
import java.util.List;
import java.util.Objects;

/** Final document plus route/provenance and every replay decision. */
public record EngineReplayReport(
        EngineDescriptor actualEngine, VoiceDraft draft, List<EngineReplayStep> steps) {
    public EngineReplayReport {
        Objects.requireNonNull(actualEngine, "actualEngine");
        Objects.requireNonNull(draft, "draft");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
    }

    public long rejectedCount() {
        return steps.stream()
                .filter(step -> step.disposition() != ReplayDisposition.APPLIED
                        && step.disposition() != ReplayDisposition.IGNORED)
                .count();
    }
}
