package com.opentypeless.android.speech.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LocalRuntimeSelectorTest {
    private static final RuntimeCapabilities ALL =
            new RuntimeCapabilities(true, true, true);

    @Test
    public void xiaomiLikeHeadroomSelectsTruthfulConcurrentTwoPass() {
        RuntimeResources resources = new RuntimeResources(
                12_288L, 4_096L, 126L, 94L, 230L, 170L, ThermalLevel.NONE, false);

        RuntimeStrategyDecision decision =
                LocalRuntimeSelector.select(ALL, resources, RuntimePolicy.DEFAULT);

        assertEquals(RuntimeStrategy.CONCURRENT_TWO_PASS, decision.strategy());
        assertEquals(1, decision.maximumConcurrentQualityJobs());
        assertEquals(4, decision.maximumPendingQualityJobs());
    }

    @Test
    public void measuredCombinedPssGateForcesSequentialEvenWithFreeRam() {
        RuntimeResources resources = new RuntimeResources(
                12_288L, 4_096L, 400L, 300L, 300L, 170L, ThermalLevel.NONE, false);

        RuntimeStrategyDecision decision =
                LocalRuntimeSelector.select(ALL, resources, RuntimePolicy.DEFAULT);

        assertEquals(RuntimeStrategy.SEQUENTIAL_TWO_PASS, decision.strategy());
        assertTrue(decision.reasons().get(0).contains("memory gate"));
    }

    @Test
    public void severeThermalOrLowMemoryNeverStartsQualityWorker() {
        RuntimeResources hot = new RuntimeResources(
                8_192L, 2_000L, 120L, 100L, 220L, 170L, ThermalLevel.SEVERE, false);
        RuntimeResources low = new RuntimeResources(
                8_192L, 2_000L, 120L, 100L, 220L, 170L, ThermalLevel.NONE, true);

        assertEquals(
                RuntimeStrategy.STREAMING_ONLY,
                LocalRuntimeSelector.select(ALL, hot, RuntimePolicy.DEFAULT).strategy());
        assertEquals(
                RuntimeStrategy.STREAMING_ONLY,
                LocalRuntimeSelector.select(ALL, low, RuntimePolicy.DEFAULT).strategy());
    }

    @Test
    public void missingQualityDoesNotBecomeNetworkFallback() {
        RuntimeStrategyDecision decision = LocalRuntimeSelector.select(
                new RuntimeCapabilities(true, false, false),
                new RuntimeResources(
                        8_192L, 5_000L, 100L, 100L, 0L, 0L, ThermalLevel.NONE, false),
                RuntimePolicy.DEFAULT);

        assertEquals(RuntimeStrategy.STREAMING_ONLY, decision.strategy());
        assertTrue(decision.reasons().get(0).contains("unavailable"));
    }

    @Test
    public void missingStreamingDisablesV2RatherThanPretendingQualityIsRealtime() {
        RuntimeStrategyDecision decision = LocalRuntimeSelector.select(
                new RuntimeCapabilities(false, true, true),
                new RuntimeResources(
                        8_192L, 5_000L, 100L, 0L, 220L, 0L, ThermalLevel.NONE, false),
                RuntimePolicy.DEFAULT);

        assertEquals(RuntimeStrategy.DISABLED, decision.strategy());
        assertEquals(0, decision.maximumConcurrentQualityJobs());
    }
}
