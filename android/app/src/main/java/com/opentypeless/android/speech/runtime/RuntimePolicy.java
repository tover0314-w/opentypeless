package com.opentypeless.android.speech.runtime;

/** Device-policy thresholds derived from Voice Lab measurements, not model marketing claims. */
public record RuntimePolicy(
        long minimumSequentialAvailableMiB,
        long minimumConcurrentAvailableMiB,
        long maximumConcurrentPssMiB,
        ThermalLevel disableQualityAtThermal,
        int maximumConcurrentQualityJobs,
        int maximumPendingQualityJobs,
        long qualityDeadlineMs) {

    public static final RuntimePolicy DEFAULT = new RuntimePolicy(
            384L,
            768L,
            640L,
            ThermalLevel.SEVERE,
            1,
            4,
            1_200L);

    public RuntimePolicy {
        if (minimumSequentialAvailableMiB <= 0L
                || minimumConcurrentAvailableMiB < minimumSequentialAvailableMiB
                || maximumConcurrentPssMiB <= 0L
                || disableQualityAtThermal == null
                || disableQualityAtThermal == ThermalLevel.UNKNOWN
                || maximumConcurrentQualityJobs <= 0
                || maximumPendingQualityJobs < maximumConcurrentQualityJobs
                || qualityDeadlineMs <= 0L) {
            throw new IllegalArgumentException("runtime policy is incoherent");
        }
    }
}
