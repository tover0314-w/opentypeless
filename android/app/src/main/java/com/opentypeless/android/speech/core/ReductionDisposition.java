package com.opentypeless.android.speech.core;

/** Auditable reason for accepting, ignoring or rejecting an event. */
public enum ReductionDisposition {
    APPLIED,
    IGNORED_DUPLICATE,
    IGNORED_STALE,
    IGNORED_BLANK,
    IGNORED_TERMINAL,
    REJECTED_SESSION,
    REJECTED_TRANSITION,
    REJECTED_BOUNDS,
    REJECTED_MISSING_SEGMENT,
    REJECTED_LOCKED,
    REJECTED_CONFLICT
}
