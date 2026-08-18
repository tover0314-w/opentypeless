package com.opentypeless.android.editor;

import java.util.Objects;

/**
 * Immutable, content-free observation of one terminal editor transaction.
 *
 * <p>This value is diagnostic data, never write authorization. It deliberately contains no
 * operation payload, session, selection, fingerprint, commit ID, receipt, timestamp or Android
 * capability. A host may forward it to a bounded diagnostic sink in a later task.
 */
public record EditorTransactionAudit(
        OperationSource source,
        EditorOperationKind operationKind,
        EditorTransactionResult result) {

    public EditorTransactionAudit {
        source = Objects.requireNonNull(source, "source");
        operationKind = Objects.requireNonNull(operationKind, "operationKind");
        result = Objects.requireNonNull(result, "result");
    }

    @Override
    public String toString() {
        return "EditorTransactionAudit{source=" + source
                + ", operationKind=" + operationKind
                + ", result=" + result + '}';
    }
}
