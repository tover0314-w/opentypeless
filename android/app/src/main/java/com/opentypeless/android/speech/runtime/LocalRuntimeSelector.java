package com.opentypeless.android.speech.runtime;

import java.util.List;
import java.util.Objects;

/** Deterministic local model strategy selector. It never falls back to a network route. */
public final class LocalRuntimeSelector {
    private LocalRuntimeSelector() {}

    public static RuntimeStrategyDecision select(
            RuntimeCapabilities capabilities,
            RuntimeResources resources,
            RuntimePolicy policy) {
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(resources, "resources");
        RuntimePolicy safePolicy = Objects.requireNonNull(policy, "policy");

        if (!capabilities.streamingAvailable()) {
            return decision(RuntimeStrategy.DISABLED, safePolicy,
                    "streaming model is unavailable; v2 remains disabled");
        }
        if (!capabilities.qualityAvailable()) {
            return decision(RuntimeStrategy.STREAMING_ONLY, safePolicy,
                    "quality model is unavailable; streaming result remains authoritative");
        }
        if (resources.lowMemorySignal()) {
            return decision(RuntimeStrategy.STREAMING_ONLY, safePolicy,
                    "Android reported low memory; quality worker is not started");
        }
        if (resources.thermalLevel() != ThermalLevel.UNKNOWN
                && resources.thermalLevel().atLeast(safePolicy.disableQualityAtThermal())) {
            return decision(RuntimeStrategy.STREAMING_ONLY, safePolicy,
                    "thermal severity forbids the quality pass");
        }

        boolean concurrentMemory =
                resources.availableMemoryMiB() >= safePolicy.minimumConcurrentAvailableMiB()
                        && resources.expectedConcurrentPssMiB()
                                <= safePolicy.maximumConcurrentPssMiB();
        if (capabilities.isolatedQualityWorkerAvailable() && concurrentMemory) {
            return new RuntimeStrategyDecision(
                    RuntimeStrategy.CONCURRENT_TWO_PASS,
                    safePolicy.maximumConcurrentQualityJobs(),
                    safePolicy.maximumPendingQualityJobs(),
                    safePolicy.qualityDeadlineMs(),
                    List.of("measured memory and thermal headroom permit an isolated quality worker"));
        }
        if (resources.availableMemoryMiB() >= safePolicy.minimumSequentialAvailableMiB()) {
            String reason = capabilities.isolatedQualityWorkerAvailable()
                    ? "concurrent memory gate failed; quality runs sequentially"
                    : "quality isolation is unavailable; models run sequentially";
            return new RuntimeStrategyDecision(
                    RuntimeStrategy.SEQUENTIAL_TWO_PASS,
                    1,
                    safePolicy.maximumPendingQualityJobs(),
                    safePolicy.qualityDeadlineMs(),
                    List.of(reason));
        }
        return decision(RuntimeStrategy.STREAMING_ONLY, safePolicy,
                "available memory is below the quality-pass gate");
    }

    private static RuntimeStrategyDecision decision(
            RuntimeStrategy strategy,
            RuntimePolicy policy,
            String reason) {
        boolean quality = strategy == RuntimeStrategy.SEQUENTIAL_TWO_PASS
                || strategy == RuntimeStrategy.CONCURRENT_TWO_PASS;
        return new RuntimeStrategyDecision(
                strategy,
                quality ? 1 : 0,
                quality ? policy.maximumPendingQualityJobs() : 0,
                quality ? policy.qualityDeadlineMs() : 0L,
                List.of(reason));
    }
}
