package com.opentypeless.android.speech.journal;

/** Explicit idempotence/conflict result for one journal mutation. */
public enum JournalWriteResult {
    WRITTEN,
    IGNORED_DUPLICATE,
    REJECTED_STALE,
    REJECTED_CONFLICT,
    REJECTED_TERMINAL,
    REJECTED_BOUNDS
}
