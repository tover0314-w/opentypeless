package com.opentypeless.android.speech.runtime;

/** Actual locally verified runtime capabilities; no provider is inferred from its name. */
public record RuntimeCapabilities(
        boolean streamingAvailable,
        boolean qualityAvailable,
        boolean isolatedQualityWorkerAvailable) {}
