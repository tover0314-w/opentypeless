package com.opentypeless.android.speech.runtime;

public enum CoordinatorDisposition {
    APPLIED,
    APPLIED_STREAMING_FALLBACK,
    IGNORED_DUPLICATE,
    IGNORED_STALE,
    REJECTED_SESSION,
    REJECTED_CAPABILITY,
    REJECTED_STATE,
    REJECTED_BOUNDS
}
