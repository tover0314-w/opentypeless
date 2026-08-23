package com.opentypeless.android.editor;

import java.util.Objects;

/** Content-free classification of a failed transaction step. */
public record TransactionFailure(
        TransactionFailurePhase phase,
        TransactionFailureStep step,
        TransactionFailureKind kind) {
    public TransactionFailure {
        phase = Objects.requireNonNull(phase, "phase");
        step = Objects.requireNonNull(step, "step");
        kind = Objects.requireNonNull(kind, "kind");
        if (step.phase() != phase) {
            throw new IllegalArgumentException("transaction failure phase and step must match");
        }
        if (kind == TransactionFailureKind.NOT_SAFE_TO_ATTEMPT && !step.isRestoreStep()) {
            throw new IllegalArgumentException(
                    "NOT_SAFE_TO_ATTEMPT requires a rollback restore step");
        }
    }
}
