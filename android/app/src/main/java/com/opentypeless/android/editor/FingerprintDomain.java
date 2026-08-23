package com.opentypeless.android.editor;

/** Stable domain identifiers for the versioned editor fingerprint frame. */
public enum FingerprintDomain {
    SELECTED_TEXT(1),
    BEFORE_CONTEXT(2),
    AFTER_CONTEXT(3),
    CONTEXT_V1(4),
    /** Exact text produced by one committed editor transaction. */
    COMMITTED_TEXT(5);

    private final int stableId;

    FingerprintDomain(int stableId) {
        this.stableId = stableId;
    }

    int stableId() {
        return stableId;
    }
}
