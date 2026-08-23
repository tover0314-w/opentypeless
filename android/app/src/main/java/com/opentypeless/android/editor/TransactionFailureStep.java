package com.opentypeless.android.editor;

import java.util.Objects;

/** Exact editor mutation or rollback-verification step that failed. */
public enum TransactionFailureStep {
    DELETE_TEXT(TransactionFailurePhase.APPLY),
    INSERT_TEXT(TransactionFailurePhase.APPLY),
    SET_COMPOSITION(TransactionFailurePhase.APPLY),
    FINISH_COMPOSITION(TransactionFailurePhase.APPLY),
    SET_SELECTION(TransactionFailurePhase.APPLY),
    PERFORM_EDITOR_ACTION(TransactionFailurePhase.APPLY),
    RESTORE_TEXT(TransactionFailurePhase.ROLLBACK),
    RESTORE_SELECTION(TransactionFailurePhase.ROLLBACK),
    RESTORE_COMPOSITION(TransactionFailurePhase.ROLLBACK),
    VERIFY_EDITOR_STATE(TransactionFailurePhase.ROLLBACK);

    private final TransactionFailurePhase phase;

    TransactionFailureStep(TransactionFailurePhase phase) {
        this.phase = Objects.requireNonNull(phase, "phase");
    }

    public TransactionFailurePhase phase() {
        return phase;
    }

    boolean isRestoreStep() {
        return this == RESTORE_TEXT
                || this == RESTORE_SELECTION
                || this == RESTORE_COMPOSITION;
    }
}
