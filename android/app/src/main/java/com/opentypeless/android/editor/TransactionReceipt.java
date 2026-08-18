package com.opentypeless.android.editor;

import java.util.Objects;

/**
 * Atomic envelope for one terminal transaction result and its exact commit-record association.
 *
 * <p>A commit record is returned alongside an {@link EditorTransactionResult.Applied} from the
 * same transaction. An exact-id process-local ledger may retain that record for later consumers,
 * but callers must not establish this association by querying a mutable "latest commit" slot.
 */
public sealed interface TransactionReceipt permits
        TransactionReceipt.WithoutCommit,
        TransactionReceipt.Committed {

    EditorTransactionResult result();

    /** A terminal transaction result with no associated commit record. */
    record WithoutCommit(EditorTransactionResult result) implements TransactionReceipt {
        public WithoutCommit {
            result = Objects.requireNonNull(result, "result");
        }
    }

    /** An applied transaction and the record created inside that exact transaction. */
    record Committed(
            EditorTransactionResult.Applied result,
            CommitRecord record) implements TransactionReceipt {
        public Committed {
            result = Objects.requireNonNull(result, "result");
            record = Objects.requireNonNull(record, "record");
        }

        @Override
        public String toString() {
            return "TransactionReceipt.Committed{result=" + result
                    + ", record=<redacted>}";
        }
    }
}
