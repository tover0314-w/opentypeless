package com.opentypeless.android.editor;

import java.util.Objects;

/**
 * Immutable, content-free terminal result of one editor transaction.
 *
 * <p>This contract intentionally has no CommitRecord payload. EDT-010 may atomically associate an
 * eligible successful transaction with an independent receipt/ledger envelope; consumers must
 * never recover that association by querying a mutable "latest commit" slot.
 */
public sealed interface EditorTransactionResult permits
        EditorTransactionResult.Applied,
        EditorTransactionResult.TargetChanged,
        EditorTransactionResult.Rejected,
        EditorTransactionResult.RolledBack,
        EditorTransactionResult.RollbackFailed {

    /** The transaction succeeded; commit-ledger association is a separate EDT-010 concern. */
    record Applied() implements EditorTransactionResult {}

    /** No write was attempted because the captured target could no longer be proven current. */
    record TargetChanged(TargetChangeReason reason) implements EditorTransactionResult {
        public TargetChanged {
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The operation was refused before any content mutator was invoked.
     *
     * <p>A mutator returning false is not sufficient: once a mutator is invoked, exact readback
     * must classify the outcome as Applied, RolledBack or RollbackFailed.
     */
    record Rejected(RejectionReason reason) implements EditorTransactionResult {
        public Rejected {
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    /** A partial or uncertain apply was fully restored and the restoration was verified. */
    record RolledBack(TransactionFailure originalFailure) implements EditorTransactionResult {
        public RolledBack {
            originalFailure = requirePhase(
                    originalFailure, TransactionFailurePhase.APPLY, "originalFailure");
        }
    }

    /**
     * The final editor state cannot be proven restored.
     *
     * <p>A mutating host call that throws has an uncertain outcome. Exact readback of the intended
     * postcondition may produce Applied; exact readback of the original state may produce
     * RolledBack. Otherwise it must produce RollbackFailed and never Rejected.
     */
    record RollbackFailed(
            TransactionFailure originalFailure,
            TransactionFailure rollbackFailure) implements EditorTransactionResult {
        public RollbackFailed {
            originalFailure = requirePhase(
                    originalFailure, TransactionFailurePhase.APPLY, "originalFailure");
            rollbackFailure = requirePhase(
                    rollbackFailure, TransactionFailurePhase.ROLLBACK, "rollbackFailure");
        }
    }

    private static TransactionFailure requirePhase(
            TransactionFailure failure,
            TransactionFailurePhase expected,
            String name) {
        TransactionFailure safe = Objects.requireNonNull(failure, name);
        if (safe.phase() != expected) {
            throw new IllegalArgumentException(name + " must use " + expected + " phase");
        }
        return safe;
    }
}
