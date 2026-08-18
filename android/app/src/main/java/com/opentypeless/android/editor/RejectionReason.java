package com.opentypeless.android.editor;

/** Stable reasons for rejecting an operation before any content mutator is invoked. */
public enum RejectionReason {
    OPERATION_NOT_SUPPORTED,
    POLICY_DENIED,
    SENSITIVE_FIELD,
    COMPOSITION_OWNER_MISMATCH,
    COMPOSITION_REVISION_MISMATCH,
    COMMIT_RECORD_UNAVAILABLE,
    EDITOR_ACTION_UNAVAILABLE,
    ROLLBACK_PRECONDITION_UNAVAILABLE,
    BATCH_EDIT_REJECTED
}
