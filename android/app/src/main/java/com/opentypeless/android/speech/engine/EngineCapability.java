package com.opentypeless.android.speech.engine;

/** Capabilities are explicit; absence must never be inferred from an engine name. */
public enum EngineCapability {
    LIVE_REVISIONS,
    SEGMENT_FINALS,
    TOKEN_TIMESTAMPS,
    TOKEN_STABILITY,
    CONFIDENCE,
    CONTEXT_BIAS,
    HOTWORDS,
    AUTOMATIC_PUNCTUATION,
    INVERSE_TEXT_NORMALIZATION,
    ON_DEVICE
}
