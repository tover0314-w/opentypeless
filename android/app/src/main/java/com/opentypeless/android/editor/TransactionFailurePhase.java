package com.opentypeless.android.editor;

/** Closed transaction phase; cleanup after endBatchEdit is diagnostic, not an editor outcome. */
public enum TransactionFailurePhase {
    APPLY,
    ROLLBACK
}
