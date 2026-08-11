package com.opentypeless.android.speech.runtime;

import java.util.Objects;

/** One measured resource snapshot used to select an explicit local execution strategy. */
public record RuntimeResources(
        long totalMemoryMiB,
        long availableMemoryMiB,
        long appPssMiB,
        long streamingWorkerPssMiB,
        long expectedQualityWorkerPssMiB,
        ThermalLevel thermalLevel,
        boolean lowMemorySignal) {
    public RuntimeResources {
        if (totalMemoryMiB <= 0L
                || availableMemoryMiB < 0L
                || availableMemoryMiB > totalMemoryMiB
                || appPssMiB < 0L
                || streamingWorkerPssMiB < 0L
                || expectedQualityWorkerPssMiB < 0L) {
            throw new IllegalArgumentException("invalid runtime resource snapshot");
        }
        Objects.requireNonNull(thermalLevel, "thermalLevel");
    }

    public long expectedConcurrentPssMiB() {
        return Math.addExact(
                Math.addExact(appPssMiB, streamingWorkerPssMiB),
                expectedQualityWorkerPssMiB);
    }
}
