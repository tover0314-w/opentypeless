package com.opentypeless.android.speech.runtime;

import java.util.List;
import java.util.Objects;

/** Truthful route decision rendered in diagnostics and bound to one voice session. */
public record RuntimeStrategyDecision(
        RuntimeStrategy strategy,
        int maximumConcurrentQualityJobs,
        int maximumPendingQualityJobs,
        long qualityDeadlineMs,
        List<String> reasons) {
    public RuntimeStrategyDecision {
        Objects.requireNonNull(strategy, "strategy");
        if (maximumConcurrentQualityJobs < 0
                || maximumPendingQualityJobs < maximumConcurrentQualityJobs
                || qualityDeadlineMs < 0L) {
            throw new IllegalArgumentException("invalid runtime strategy decision");
        }
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }
}
