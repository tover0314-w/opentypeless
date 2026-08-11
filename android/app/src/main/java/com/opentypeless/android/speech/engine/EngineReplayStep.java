package com.opentypeless.android.speech.engine;

import com.opentypeless.android.speech.core.ReductionDisposition;
import java.util.Objects;
import java.util.Optional;

/** Auditable per-event replay outcome. */
public record EngineReplayStep(
        long eventSequence,
        ReplayDisposition disposition,
        Optional<ReductionDisposition> coreDisposition,
        String detail) {
    public EngineReplayStep {
        if (eventSequence <= 0L) {
            throw new IllegalArgumentException("event sequence must be positive");
        }
        Objects.requireNonNull(disposition, "disposition");
        coreDisposition = Objects.requireNonNull(coreDisposition, "coreDisposition");
        Objects.requireNonNull(detail, "detail");
    }
}
