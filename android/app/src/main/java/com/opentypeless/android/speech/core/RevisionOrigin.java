package com.opentypeless.android.speech.core;

/** Provenance of a full-segment revision. */
public enum RevisionOrigin {
    STREAM_ASR,
    QUALITY_ASR,
    PUNCTUATION,
    INVERSE_TEXT_NORMALIZATION,
    PERSONALIZATION,
    USER,
    /** Refined-stage promotion of the last safe streaming text when quality is unavailable. */
    STREAMING_FALLBACK
}
