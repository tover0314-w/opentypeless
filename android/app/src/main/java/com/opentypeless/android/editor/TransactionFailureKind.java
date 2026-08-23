package com.opentypeless.android.editor;

/** Stable failure classification that never carries an OEM exception or editor text. */
public enum TransactionFailureKind {
    EDITOR_REJECTED,
    RUNTIME_FAILURE,
    OUTCOME_UNCONFIRMED,
    TARGET_INVALIDATED,
    NOT_SAFE_TO_ATTEMPT
}
