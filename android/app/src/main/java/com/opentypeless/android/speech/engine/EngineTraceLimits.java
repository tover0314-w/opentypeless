package com.opentypeless.android.speech.engine;

/** Strict limits for untrusted or recorded trace fixtures. */
public record EngineTraceLimits(
        int maxJsonBytes, int maxEvents, int maxTextCodePoints, int maxTokensPerRevision) {

    public static final EngineTraceLimits DEFAULT =
            new EngineTraceLimits(2 * 1024 * 1024, 10_000, 20_000, 10_000);

    public EngineTraceLimits {
        if (maxJsonBytes <= 0
                || maxEvents <= 0
                || maxTextCodePoints <= 0
                || maxTokensPerRevision <= 0) {
            throw new IllegalArgumentException("trace limits must be positive");
        }
    }
}
