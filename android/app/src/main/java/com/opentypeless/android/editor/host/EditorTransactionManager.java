package com.opentypeless.android.editor.host;

import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import com.opentypeless.android.editor.CompositionOwner;
import com.opentypeless.android.editor.CommitRecord;
import com.opentypeless.android.editor.CommitRecordRequest;
import com.opentypeless.android.editor.EditorAction;
import com.opentypeless.android.editor.EditorOperation;
import com.opentypeless.android.editor.EditorOperationKind;
import com.opentypeless.android.editor.EditorSessionLimits;
import com.opentypeless.android.editor.EditorSessionSnapshot;
import com.opentypeless.android.editor.EditorTransactionAudit;
import com.opentypeless.android.editor.EditorTransactionResult;
import com.opentypeless.android.editor.OperationSource;
import com.opentypeless.android.editor.RejectionReason;
import com.opentypeless.android.editor.TargetChangeReason;
import com.opentypeless.android.editor.TextRange;
import com.opentypeless.android.editor.TransactionReceipt;
import com.opentypeless.android.editor.TransactionFailure;
import com.opentypeless.android.editor.TransactionFailureKind;
import com.opentypeless.android.editor.TransactionFailurePhase;
import com.opentypeless.android.editor.TransactionFailureStep;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;

/** Owner-thread, synchronous and package-confined interpreter for the basic editor operations. */
final class EditorTransactionManager {
    private static final int MAX_PROVABLE_DELETE_CODE_POINTS =
            EditorSessionLimits.SURROUNDING_CONTEXT_CODE_POINTS;

    enum CleanupFailure {
        END_BATCH_REJECTED,
        END_BATCH_RUNTIME_FAILURE
    }

    @FunctionalInterface
    interface CleanupSink {
        void record(CleanupFailure failure);
    }

    /** Receives one content-free observation for each transaction that reaches a stable result. */
    @FunctionalInterface
    interface AuditSink {
        void record(EditorTransactionAudit audit);
    }

    private final EditorSessionManager sessions;
    private final CleanupSink cleanupSink;
    private final AuditSink auditSink;
    private final CommitLedger commitLedger;
    private final EnumMap<CompositionOwner, Long> compositionHighWatermarks =
            new EnumMap<>(CompositionOwner.class);
    private boolean applying;
    private boolean sessionRevokePending;
    private long compositionEpoch = Long.MIN_VALUE;
    private long compositionToken = Long.MIN_VALUE;
    private CompositionOwner compositionOwner = CompositionOwner.NONE;
    private long compositionRevision;
    private boolean compositionUncertain;
    private CompositionCommitBasis compositionCommitBasis;

    EditorTransactionManager(EditorSessionManager sessions) {
        this(sessions, failure -> {}, audit -> {}, new CommitLedger());
    }

    EditorTransactionManager(EditorSessionManager sessions, CleanupSink cleanupSink) {
        this(sessions, cleanupSink, audit -> {}, new CommitLedger());
    }

    EditorTransactionManager(
            EditorSessionManager sessions,
            CleanupSink cleanupSink,
            CommitLedger commitLedger) {
        this(sessions, cleanupSink, audit -> {}, commitLedger);
    }

    EditorTransactionManager(
            EditorSessionManager sessions,
            CleanupSink cleanupSink,
            AuditSink auditSink,
            CommitLedger commitLedger) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.cleanupSink = Objects.requireNonNull(cleanupSink, "cleanupSink");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.commitLedger = Objects.requireNonNull(commitLedger, "commitLedger");
    }

    EditorTransactionResult apply(
            EditorSessionSnapshot expected,
            EditorOperation operation,
            EditorSessionManager.LiveAuthoritySupplier authoritySupplier,
            EditorSessionManager.CurrentEvidenceReader evidenceReader) {
        return applyWithReceipt(
                expected,
                operation,
                new CommitRecordRequest.None(),
                authoritySupplier,
                evidenceReader).result();
    }

    TransactionReceipt applyWithReceipt(
            EditorSessionSnapshot expected,
            EditorOperation operation,
            CommitRecordRequest commitRequest,
            EditorSessionManager.LiveAuthoritySupplier authoritySupplier,
            EditorSessionManager.CurrentEvidenceReader evidenceReader) {
        sessions.requireOwnerThreadForHost();
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(commitRequest, "commitRequest");
        Objects.requireNonNull(authoritySupplier, "authoritySupplier");
        Objects.requireNonNull(evidenceReader, "evidenceReader");
        if (applying) throw new IllegalStateException("Editor transaction is already active");
        applying = true;
        try {
            EditorSessionManager.HostValidationResult initial = sessions.validateCurrentSession(
                    expected, authoritySupplier, evidenceReader);
            if (initial instanceof EditorSessionManager.ValidationInvalid invalid) {
                commitLedger.revokeAll();
                return auditReceipt(
                        operation, withoutCommit(targetChanged(invalid.reason())));
            }
            EditorSessionManager.Validated validated = (EditorSessionManager.Validated) initial;
            bindCompositionAuthority(expected);
            RejectionReason policy = policyRejection(expected, operation, validated, true);
            if (policy != null) {
                return auditReceipt(operation, withoutCommit(rejected(policy)));
            }

            CommitPreparation preparation = prepareCommit(
                    expected, operation, commitRequest, validated.lease().sensitive());
            if (preparation.rejection() != null) {
                return auditReceipt(
                        operation, withoutCommit(rejected(preparation.rejection())));
            }

            try {
                EditorSessionManager.ReceiptConnectionUseResult scoped =
                        validated.lease().consumeWithCurrentConnectionForReceipt(connection ->
                                executeBatch(
                                        connection,
                                        expected,
                                        operation,
                                        preparation,
                                        authoritySupplier,
                                        evidenceReader));
                TransactionReceipt receipt = unwrapReceipt(scoped);
                updateLedgerForResult(receipt.result(), preparation);
                return auditReceipt(operation, receipt);
            } finally {
                preparation.abort();
            }
        } finally {
            if (sessionRevokePending) {
                sessionRevokePending = false;
                clearSessionState();
            }
            applying = false;
        }
    }

    /**
     * Applies one exact-ID, collapsed-origin Undo without trusting a public receipt or operation.
     *
     * <p>The caller's snapshot is only a CAS observation. The ledger record is resolved by exact
     * ID, then the current target, the entire committed suffix and the original bounded context are
     * proven twice against live authority before the only content mutator.
     */
    EditorTransactionResult undoCommit(
            String commitId,
            EditorSessionSnapshot expectedCurrent,
            EditorSessionManager.LiveAuthoritySupplier authoritySupplier,
            EditorSessionManager.UndoEvidenceReader evidenceReader) {
        sessions.requireOwnerThreadForHost();
        Objects.requireNonNull(expectedCurrent, "expectedCurrent");
        Objects.requireNonNull(authoritySupplier, "authoritySupplier");
        Objects.requireNonNull(evidenceReader, "evidenceReader");
        if (applying) throw new IllegalStateException("Editor transaction is already active");
        applying = true;
        try {
            Optional<CommitRecord> candidate = commitLedger.resolve(commitId, expectedCurrent);
            if (candidate.isEmpty()) {
                return auditResult(
                        OperationSource.UNDO,
                        EditorOperationKind.REPLACE_LAST_COMMIT,
                        rejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE));
            }
            CommitRecord record = candidate.orElseThrow();
            RejectionReason structural = undoStructuralRejection(record);
            if (structural != null) {
                return auditResult(
                        OperationSource.UNDO,
                        EditorOperationKind.REPLACE_LAST_COMMIT,
                        rejected(structural));
            }

            int expectedCursor = undoCommittedCursor(record);
            if (expectedCursor < 0) {
                return auditResult(
                        OperationSource.UNDO,
                        EditorOperationKind.REPLACE_LAST_COMMIT,
                        rejected(RejectionReason.ROLLBACK_PRECONDITION_UNAVAILABLE));
            }
            TextRange currentSelection = expectedCurrent.selection();
            if (!currentSelection.isKnown()
                    || !currentSelection.isCollapsed()
                    || currentSelection.start() != expectedCursor) {
                commitLedger.revokeAll();
                return auditResult(
                        OperationSource.UNDO,
                        EditorOperationKind.REPLACE_LAST_COMMIT,
                        targetChanged(TargetChangeReason.SELECTION_CHANGED));
            }

            // Construct the closed semantic operation only after exact ledger resolution. Ordinary
            // apply() continues to reject caller-constructed ReplaceLastCommit values.
            EditorOperation.ReplaceLastCommit semantic = new EditorOperation.ReplaceLastCommit(
                    record.commitId(),
                    record.insertedTextFingerprint(),
                    record.originalSession().selectedText(),
                    OperationSource.UNDO);

            EditorSessionManager.UndoValidationResult initial = sessions.validateUndoState(
                    expectedCurrent,
                    record,
                    EditorSessionManager.UndoProofState.COMMITTED,
                    authoritySupplier,
                    evidenceReader);
            if (initial instanceof EditorSessionManager.UndoValidationInvalid invalid) {
                commitLedger.revokeAll();
                return auditResult(
                        OperationSource.UNDO,
                        EditorOperationKind.REPLACE_LAST_COMMIT,
                        targetChanged(invalid.reason()));
            }

            EditorSessionManager.UndoValidated validated =
                    (EditorSessionManager.UndoValidated) initial;
            EditorSessionManager.ConnectionUseResult scoped =
                    validated.lease().consumeWithCurrentConnection(connection ->
                            executeUndoBatch(
                                    connection,
                                    expectedCurrent,
                                    record,
                                    semantic,
                                    authoritySupplier,
                                    evidenceReader));
            EditorTransactionResult result = unwrap(scoped);
            updateLedgerForUndo(result, record.commitId(), expectedCurrent);
            return auditResult(
                    OperationSource.UNDO,
                    EditorOperationKind.REPLACE_LAST_COMMIT,
                    result);
        } finally {
            if (sessionRevokePending) {
                sessionRevokePending = false;
                clearSessionState();
            }
            applying = false;
        }
    }

    /**
     * Replaces one exact committed voice result with its process-local raw transcript.
     *
     * <p>The public value objects and the caller's snapshot are not authorization. The exact
     * ledger record supplies both texts, every proof is bound to live authority and absolute
     * selection evidence, and the two target writes plus any single verified rollback remain
     * inside one owner-thread batch.
     */
    EditorTransactionResult restoreRawCommit(
            String commitId,
            EditorSessionSnapshot expectedCurrent,
            EditorSessionManager.LiveAuthoritySupplier authoritySupplier,
            EditorSessionManager.UndoEvidenceReader evidenceReader) {
        sessions.requireOwnerThreadForHost();
        Objects.requireNonNull(expectedCurrent, "expectedCurrent");
        Objects.requireNonNull(authoritySupplier, "authoritySupplier");
        Objects.requireNonNull(evidenceReader, "evidenceReader");
        if (applying) throw new IllegalStateException("Editor transaction is already active");
        applying = true;
        try {
            Optional<CommitRecord> candidate = commitLedger.resolve(commitId, expectedCurrent);
            if (candidate.isEmpty()) {
                return auditResult(
                        OperationSource.RAW_RESTORE,
                        EditorOperationKind.REPLACE_LAST_COMMIT,
                        rejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE));
            }
            CommitRecord record = candidate.orElseThrow();
            RejectionReason structural = rawStructuralRejection(record);
            if (structural != null) {
                return auditResult(
                        OperationSource.RAW_RESTORE,
                        EditorOperationKind.REPLACE_LAST_COMMIT,
                        rejected(structural));
            }

            int expectedCursor = undoCommittedCursor(record);
            if (expectedCursor < 0) {
                return auditResult(
                        OperationSource.RAW_RESTORE,
                        EditorOperationKind.REPLACE_LAST_COMMIT,
                        rejected(RejectionReason.ROLLBACK_PRECONDITION_UNAVAILABLE));
            }
            TextRange currentSelection = expectedCurrent.selection();
            if (!currentSelection.isKnown()
                    || !currentSelection.isCollapsed()
                    || currentSelection.start() != expectedCursor) {
                commitLedger.revokeAll();
                return auditResult(
                        OperationSource.RAW_RESTORE,
                        EditorOperationKind.REPLACE_LAST_COMMIT,
                        targetChanged(TargetChangeReason.SELECTION_CHANGED));
            }

            String raw = rawText(record);
            if (raw == null) {
                return auditResult(
                        OperationSource.RAW_RESTORE,
                        EditorOperationKind.REPLACE_LAST_COMMIT,
                        rejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE));
            }
            EditorOperation.ReplaceLastCommit semantic = new EditorOperation.ReplaceLastCommit(
                    record.commitId(),
                    record.insertedTextFingerprint(),
                    raw,
                    OperationSource.RAW_RESTORE);

            EditorSessionManager.UndoValidationResult initial = sessions.validateUndoState(
                    expectedCurrent,
                    record,
                    EditorSessionManager.UndoProofState.COMMITTED,
                    authoritySupplier,
                    evidenceReader);
            if (initial instanceof EditorSessionManager.UndoValidationInvalid invalid) {
                commitLedger.revokeAll();
                return auditResult(
                        OperationSource.RAW_RESTORE,
                        EditorOperationKind.REPLACE_LAST_COMMIT,
                        targetChanged(invalid.reason()));
            }

            EditorSessionManager.UndoValidated validated =
                    (EditorSessionManager.UndoValidated) initial;
            EditorSessionManager.ConnectionUseResult scoped =
                    validated.lease().consumeWithCurrentConnection(connection ->
                            executeRawBatch(
                                    connection,
                                    expectedCurrent,
                                    record,
                                    semantic,
                                    authoritySupplier,
                                    evidenceReader));
            EditorTransactionResult result = unwrap(scoped);
            updateLedgerForRaw(result, record.commitId(), expectedCurrent);
            return auditResult(
                    OperationSource.RAW_RESTORE,
                    EditorOperationKind.REPLACE_LAST_COMMIT,
                    result);
        } finally {
            if (sessionRevokePending) {
                sessionRevokePending = false;
                clearSessionState();
            }
            applying = false;
        }
    }

    private EditorTransactionResult executeUndoBatch(
            InputConnection connection,
            EditorSessionSnapshot expectedCurrent,
            CommitRecord record,
            EditorOperation.ReplaceLastCommit semantic,
            EditorSessionManager.LiveAuthoritySupplier authoritySupplier,
            EditorSessionManager.UndoEvidenceReader evidenceReader) {
        boolean began = beginBatch(connection);
        if (!began) return rejected(RejectionReason.BATCH_EDIT_REJECTED);

        try {
            EditorSessionManager.UndoValidationResult second = sessions.validateUndoState(
                    expectedCurrent,
                    record,
                    EditorSessionManager.UndoProofState.COMMITTED,
                    authoritySupplier,
                    evidenceReader);
            if (second instanceof EditorSessionManager.UndoValidationInvalid invalid) {
                return targetChanged(invalid.reason());
            }

            EditorSessionManager.UndoValidated validated =
                    (EditorSessionManager.UndoValidated) second;
            EditorSessionManager.ConnectionUseResult use =
                    validated.lease().consumeWithCurrentConnection(current -> {
                        if (current != connection) {
                            return targetChanged(TargetChangeReason.CONNECTION_CHANGED);
                        }
                        if (!semantic.expectedTextHash()
                                .securelyMatches(record.insertedTextFingerprint())) {
                            return targetChanged(TargetChangeReason.SURROUNDING_TEXT_CHANGED);
                        }
                        EditorOperation.DeleteBeforeCursor physical =
                                new EditorOperation.DeleteBeforeCursor(
                                        record.insertedText().codePointCount(
                                                0, record.insertedText().length()),
                                        OperationSource.UNDO);
                        if (record.originalSession().selection().hasSelection()) {
                            EditorSessionManager.RawTransition originalTransition =
                                    sessions.prepareRawTransition(
                                            record,
                                            EditorSessionManager.RawProofState.COMMITTED,
                                            EditorSessionManager.RawProofState.ORIGINAL);
                            EditorSessionManager.RawTransition undoOutcomeTransition =
                                    sessions.prepareRawTransition(
                                            record,
                                            EditorSessionManager.RawProofState.COMMITTED,
                                            EditorSessionManager.RawProofState.UNDO);
                            if (originalTransition == null || undoOutcomeTransition == null) {
                                return targetChanged(TargetChangeReason.EVIDENCE_UNAVAILABLE);
                            }
                            return mutateRawDeleteAndContinue(
                                    current,
                                    record,
                                    semantic,
                                    physical,
                                    originalTransition,
                                    undoOutcomeTransition,
                                    EditorSessionManager.RawProofState.UNDO,
                                    authoritySupplier,
                                    evidenceReader);
                        }
                        return mutateUndoAndClassify(
                                current,
                                record,
                                physical,
                                authoritySupplier,
                                evidenceReader);
                    });
            return unwrap(use);
        } finally {
            finishBatch(connection);
        }
    }

    private EditorTransactionResult mutateUndoAndClassify(
            InputConnection connection,
            CommitRecord record,
            EditorOperation.DeleteBeforeCursor physical,
            EditorSessionManager.LiveAuthoritySupplier authoritySupplier,
            EditorSessionManager.UndoEvidenceReader evidenceReader) {
        boolean applied;
        TransactionFailureKind failureKind;
        try {
            applied = invokeMutator(connection, physical);
            failureKind = TransactionFailureKind.EDITOR_REJECTED;
        } catch (RuntimeException unavailable) {
            applied = false;
            failureKind = TransactionFailureKind.RUNTIME_FAILURE;
        }
        if (applied) return new EditorTransactionResult.Applied();

        TransactionFailure original = new TransactionFailure(
                TransactionFailurePhase.APPLY,
                TransactionFailureStep.DELETE_TEXT,
                failureKind);
        EditorSessionManager.UndoValidationResult outcome = sessions.validateUndoOriginalState(
                record, authoritySupplier, evidenceReader);
        if (outcome instanceof EditorSessionManager.UndoValidated) {
            return new EditorTransactionResult.Applied();
        }
        TargetChangeReason reason =
                ((EditorSessionManager.UndoValidationInvalid) outcome).reason();
        return rollbackFailed(
                original,
                isTargetInvalidated(reason)
                        ? TransactionFailureKind.TARGET_INVALIDATED
                : TransactionFailureKind.OUTCOME_UNCONFIRMED);
    }

    private EditorTransactionResult executeRawBatch(
            InputConnection connection,
            EditorSessionSnapshot expectedCurrent,
            CommitRecord record,
            EditorOperation.ReplaceLastCommit semantic,
            EditorSessionManager.LiveAuthoritySupplier authoritySupplier,
            EditorSessionManager.UndoEvidenceReader evidenceReader) {
        boolean began = beginBatch(connection);
        if (!began) return rejected(RejectionReason.BATCH_EDIT_REJECTED);

        try {
            EditorSessionManager.UndoValidationResult second = sessions.validateUndoState(
                    expectedCurrent,
                    record,
                    EditorSessionManager.UndoProofState.COMMITTED,
                    authoritySupplier,
                    evidenceReader);
            if (second instanceof EditorSessionManager.UndoValidationInvalid invalid) {
                return targetChanged(invalid.reason());
            }

            EditorSessionManager.UndoValidated validated =
                    (EditorSessionManager.UndoValidated) second;
            EditorSessionManager.ConnectionUseResult use =
                    validated.lease().consumeWithCurrentConnection(current -> {
                        if (current != connection) {
                            return targetChanged(TargetChangeReason.CONNECTION_CHANGED);
                        }
                        if (!semantic.expectedTextHash()
                                .securelyMatches(record.insertedTextFingerprint())) {
                            return targetChanged(TargetChangeReason.SURROUNDING_TEXT_CHANGED);
                        }
                        String raw = rawText(record);
                        if (raw == null || !semantic.text().equals(raw)) {
                            return targetChanged(TargetChangeReason.SURROUNDING_TEXT_CHANGED);
                        }
                        EditorSessionManager.RawTransition originalTransition =
                                sessions.prepareRawTransition(
                                        record,
                                        EditorSessionManager.RawProofState.COMMITTED,
                                        EditorSessionManager.RawProofState.ORIGINAL);
                        EditorSessionManager.RawTransition rawOutcomeTransition =
                                sessions.prepareRawTransition(
                                        record,
                                        EditorSessionManager.RawProofState.COMMITTED,
                                        EditorSessionManager.RawProofState.RAW);
                        if (originalTransition == null || rawOutcomeTransition == null) {
                            return targetChanged(TargetChangeReason.EVIDENCE_UNAVAILABLE);
                        }
                        EditorOperation.DeleteBeforeCursor delete =
                                new EditorOperation.DeleteBeforeCursor(
                                        record.insertedText().codePointCount(
                                                0, record.insertedText().length()),
                                        OperationSource.RAW_RESTORE);
                        return mutateRawDeleteAndContinue(
                                current,
                                record,
                                semantic,
                                delete,
                                originalTransition,
                                rawOutcomeTransition,
                                EditorSessionManager.RawProofState.RAW,
                                authoritySupplier,
                                evidenceReader);
                    });
            return unwrap(use);
        } finally {
            finishBatch(connection);
        }
    }

    private EditorTransactionResult mutateRawDeleteAndContinue(
            InputConnection connection,
            CommitRecord record,
            EditorOperation.ReplaceLastCommit semantic,
            EditorOperation.DeleteBeforeCursor delete,
            EditorSessionManager.RawTransition originalTransition,
            EditorSessionManager.RawTransition rawOutcomeTransition,
            EditorSessionManager.RawProofState targetState,
            EditorSessionManager.LiveAuthoritySupplier authoritySupplier,
            EditorSessionManager.UndoEvidenceReader evidenceReader) {
        boolean deleteAcknowledged;
        TransactionFailureKind deleteFailureKind;
        try {
            deleteAcknowledged = invokeMutator(connection, delete);
            deleteFailureKind = TransactionFailureKind.EDITOR_REJECTED;
        } catch (RuntimeException unavailable) {
            deleteAcknowledged = false;
            deleteFailureKind = TransactionFailureKind.RUNTIME_FAILURE;
        }

        EditorSessionManager.UndoValidationResult intermediate =
                sessions.validateRawTransitionState(
                        originalTransition, record, authoritySupplier, evidenceReader);
        if (intermediate instanceof EditorSessionManager.UndoValidationInvalid invalid) {
            EditorSessionManager.UndoValidationResult intended =
                    sessions.validateRawTransitionState(
                            rawOutcomeTransition, record, authoritySupplier, evidenceReader);
            if (deleteAcknowledged
                    && intended instanceof EditorSessionManager.UndoValidated) {
                return new EditorTransactionResult.Applied();
            }
            TargetChangeReason intendedReason =
                    intended instanceof EditorSessionManager.UndoValidationInvalid invalidIntended
                            ? invalidIntended.reason()
                            : TargetChangeReason.EVIDENCE_UNAVAILABLE;
            TransactionFailureKind classified = deleteAcknowledged
                    ? classificationFor(intendedReason)
                    : deleteFailureKind;
            return rollbackFailed(
                    applyFailure(TransactionFailureStep.DELETE_TEXT, classified),
                    classificationFor(intendedReason));
        }

        if (!deleteAcknowledged) {
            return rollbackFailed(
                    applyFailure(TransactionFailureStep.DELETE_TEXT, deleteFailureKind),
                    TransactionFailureKind.OUTCOME_UNCONFIRMED);
        }

        EditorSessionManager.UndoValidated originalValidated =
                (EditorSessionManager.UndoValidated) intermediate;
        EditorSessionManager.ConnectionUseResult use =
                originalValidated.lease().consumeWithCurrentConnection(current -> {
                    if (current != connection) {
                        return rollbackFailed(
                                applyFailure(
                                        TransactionFailureStep.INSERT_TEXT,
                                        TransactionFailureKind.TARGET_INVALIDATED),
                                TransactionFailureKind.TARGET_INVALIDATED);
                    }
                    EditorSessionManager.RawTransition targetTransition =
                            sessions.prepareRawTransition(
                                    record,
                                    EditorSessionManager.RawProofState.ORIGINAL,
                                    targetState);
                    EditorSessionManager.RawTransition restoreBasisTransition =
                            sessions.prepareRawTransition(
                                    record,
                                    EditorSessionManager.RawProofState.ORIGINAL,
                                    EditorSessionManager.RawProofState.ORIGINAL);
                    if (targetTransition == null || restoreBasisTransition == null) {
                        return rollbackFailed(
                                applyFailure(
                                        TransactionFailureStep.INSERT_TEXT,
                                        TransactionFailureKind.OUTCOME_UNCONFIRMED),
                                TransactionFailureKind.OUTCOME_UNCONFIRMED);
                    }
                    EditorOperation.InsertText insert = new EditorOperation.InsertText(
                            semantic.text(), semantic.source());
                    return mutateRawInsertAndClassify(
                            current,
                            record,
                            insert,
                            targetTransition,
                            restoreBasisTransition,
                            authoritySupplier,
                            evidenceReader);
                });
        if (use instanceof EditorSessionManager.ConnectionUsed used) return used.result();
        TargetChangeReason reason = ((EditorSessionManager.ConnectionInvalid) use).reason();
        return rollbackFailed(
                applyFailure(TransactionFailureStep.INSERT_TEXT, classificationFor(reason)),
                classificationFor(reason));
    }

    private EditorTransactionResult mutateRawInsertAndClassify(
            InputConnection connection,
            CommitRecord record,
            EditorOperation.InsertText insert,
            EditorSessionManager.RawTransition targetTransition,
            EditorSessionManager.RawTransition restoreBasisTransition,
            EditorSessionManager.LiveAuthoritySupplier authoritySupplier,
            EditorSessionManager.UndoEvidenceReader evidenceReader) {
        boolean applied;
        TransactionFailureKind failureKind;
        try {
            applied = invokeMutator(connection, insert);
            failureKind = TransactionFailureKind.EDITOR_REJECTED;
        } catch (RuntimeException unavailable) {
            applied = false;
            failureKind = TransactionFailureKind.RUNTIME_FAILURE;
        }
        EditorSessionManager.UndoValidationResult targetOutcome =
                sessions.validateRawTransitionState(
                        targetTransition, record, authoritySupplier, evidenceReader);
        if (applied && targetOutcome instanceof EditorSessionManager.UndoValidated) {
            return new EditorTransactionResult.Applied();
        }
        TargetChangeReason targetReason =
                targetOutcome instanceof EditorSessionManager.UndoValidationInvalid invalid
                        ? invalid.reason()
                        : TargetChangeReason.EVIDENCE_UNAVAILABLE;
        TransactionFailure original = applyFailure(
                TransactionFailureStep.INSERT_TEXT,
                applied ? classificationFor(targetReason) : failureKind);

        EditorSessionManager.UndoValidationResult restoreBasis =
                sessions.validateRawTransitionState(
                        restoreBasisTransition, record, authoritySupplier, evidenceReader);
        if (!(restoreBasis instanceof EditorSessionManager.UndoValidated validated)) {
            // Never issue a compensating write unless the exact post-delete ORIGINAL state is
            // proven under the same owner/lifecycle/connection authority.
            return rollbackFailed(
                    original,
                    TransactionFailureStep.RESTORE_TEXT,
                    TransactionFailureKind.NOT_SAFE_TO_ATTEMPT);
        }

        EditorSessionManager.RawTransition restoreTransition =
                sessions.prepareRawTransition(
                        record,
                        EditorSessionManager.RawProofState.ORIGINAL,
                        EditorSessionManager.RawProofState.COMMITTED);
        if (restoreTransition == null) {
            return rollbackFailed(
                    original,
                    TransactionFailureStep.RESTORE_TEXT,
                    TransactionFailureKind.NOT_SAFE_TO_ATTEMPT);
        }

        EditorSessionManager.ConnectionUseResult use =
                validated.lease().consumeWithCurrentConnection(current -> {
                    if (current != connection) {
                        return rollbackFailed(
                                original,
                                TransactionFailureStep.RESTORE_TEXT,
                                TransactionFailureKind.NOT_SAFE_TO_ATTEMPT);
                    }
                    EditorOperation.InsertText restore = new EditorOperation.InsertText(
                            record.insertedText(), insert.source());
                    return restoreCommittedAndClassify(
                            current,
                            record,
                            restore,
                            restoreTransition,
                            original,
                            authoritySupplier,
                            evidenceReader);
                });
        if (use instanceof EditorSessionManager.ConnectionUsed used) return used.result();
        return rollbackFailed(
                original,
                TransactionFailureStep.RESTORE_TEXT,
                TransactionFailureKind.NOT_SAFE_TO_ATTEMPT);
    }

    private EditorTransactionResult restoreCommittedAndClassify(
            InputConnection connection,
            CommitRecord record,
            EditorOperation.InsertText restore,
            EditorSessionManager.RawTransition restoreTransition,
            TransactionFailure original,
            EditorSessionManager.LiveAuthoritySupplier authoritySupplier,
            EditorSessionManager.UndoEvidenceReader evidenceReader) {
        boolean restored;
        TransactionFailureKind failureKind;
        try {
            restored = invokeMutator(connection, restore);
            failureKind = TransactionFailureKind.EDITOR_REJECTED;
        } catch (RuntimeException unavailable) {
            restored = false;
            failureKind = TransactionFailureKind.RUNTIME_FAILURE;
        }
        EditorSessionManager.UndoValidationResult outcome =
                sessions.validateRawTransitionState(
                        restoreTransition, record, authoritySupplier, evidenceReader);
        if (restored && outcome instanceof EditorSessionManager.UndoValidated) {
            // RolledBack is stronger than a successful framework acknowledgement: the complete
            // ledger-bound COMMITTED state must also be proven after the compensating write.
            return new EditorTransactionResult.RolledBack(original);
        }
        if (!restored) {
            return rollbackFailed(
                    original, TransactionFailureStep.RESTORE_TEXT, failureKind);
        }
        TargetChangeReason reason =
                ((EditorSessionManager.UndoValidationInvalid) outcome).reason();
        return rollbackFailed(
                original,
                TransactionFailureStep.VERIFY_EDITOR_STATE,
                classificationFor(reason));
    }

    private TransactionReceipt executeBatch(
            InputConnection connection,
            EditorSessionSnapshot expected,
            EditorOperation operation,
            CommitPreparation preparation,
            EditorSessionManager.LiveAuthoritySupplier authoritySupplier,
            EditorSessionManager.CurrentEvidenceReader evidenceReader) {
        boolean began = beginBatch(connection);
        if (!began) return withoutCommit(rejected(RejectionReason.BATCH_EDIT_REJECTED));

        try {
            EditorSessionManager.HostValidationResult second = sessions.validateCurrentSession(
                    expected, authoritySupplier, evidenceReader);
            if (second instanceof EditorSessionManager.ValidationInvalid invalid) {
                return withoutCommit(targetChanged(invalid.reason()));
            }
            EditorSessionManager.Validated validated = (EditorSessionManager.Validated) second;
            RejectionReason policy = policyRejection(expected, operation, validated, false);
            if (policy != null) return withoutCommit(rejected(policy));

            EditorSessionManager.ConnectionUseResult useResult =
                    validated.lease().consumeWithCurrentConnection(current -> {
                        if (current != connection) {
                            return targetChanged(TargetChangeReason.CONNECTION_CHANGED);
                        }
                        IntendedState intended = intendedState(
                                expected, operation, validated.evidence());
                        if (intended == null && requiresIntendedState(operation)) {
                            return rejected(RejectionReason.ROLLBACK_PRECONDITION_UNAVAILABLE);
                        }
                        EditorSessionManager.ReplaceTransition replaceIntended = null;
                        EditorSessionManager.ReplaceTransition replaceOriginal = null;
                        if (operation instanceof EditorOperation.ReplaceSelection replace) {
                            replaceIntended = sessions.prepareReplaceTransition(
                                    expected,
                                    replace,
                                    EditorSessionManager.ReplaceProofState.INTENDED);
                            replaceOriginal = sessions.prepareReplaceTransition(
                                    expected,
                                    replace,
                                    EditorSessionManager.ReplaceProofState.ORIGINAL);
                            if (replaceIntended == null || replaceOriginal == null) {
                                return rejected(
                                        RejectionReason.ROLLBACK_PRECONDITION_UNAVAILABLE);
                            }
                        }
                        return mutateAndClassify(
                                current,
                                expected,
                                operation,
                                intended,
                                replaceIntended,
                                replaceOriginal,
                                validated.lease().sensitive(),
                                authoritySupplier,
                                evidenceReader);
                    });
            EditorTransactionResult result = unwrap(useResult);
            return receiptFor(result, preparation);
        } finally {
            finishBatch(connection);
        }
    }

    private EditorTransactionResult mutateAndClassify(
            InputConnection connection,
            EditorSessionSnapshot expected,
            EditorOperation operation,
            IntendedState intended,
            EditorSessionManager.ReplaceTransition replaceIntended,
            EditorSessionManager.ReplaceTransition replaceOriginal,
            boolean sensitive,
            EditorSessionManager.LiveAuthoritySupplier authoritySupplier,
            EditorSessionManager.CurrentEvidenceReader evidenceReader) {
        TransactionFailureStep step = failureStep(operation);
        boolean applied;
        TransactionFailureKind failureKind;
        try {
            applied = invokeMutator(connection, operation);
            failureKind = TransactionFailureKind.EDITOR_REJECTED;
        } catch (RuntimeException unavailable) {
            applied = false;
            failureKind = TransactionFailureKind.RUNTIME_FAILURE;
        }
        if (applied) {
            recordCompositionSuccess(operation, expected);
            return new EditorTransactionResult.Applied();
        }

        TransactionFailure original = new TransactionFailure(
                TransactionFailurePhase.APPLY, step, failureKind);
        if (isCompositionOperation(operation)) {
            recordCompositionUncertain(operation);
            return outcomeUnconfirmed(original);
        }
        if (operation instanceof EditorOperation.PerformEditorAction || sensitive) {
            return outcomeUnconfirmed(original);
        }

        if (operation instanceof EditorOperation.ReplaceSelection) {
            EditorSessionManager.ReplaceValidationResult intendedOutcome =
                    sessions.validateReplaceTransitionState(
                            replaceIntended, authoritySupplier, evidenceReader);
            EditorSessionManager.ReplaceValidationResult originalOutcome =
                    sessions.validateReplaceTransitionState(
                            replaceOriginal, authoritySupplier, evidenceReader);
            // Even a full replacement hash plus bounded original context cannot prove that a
            // periodic suffix of the old selection was removed. A false/throwing framework call
            // is therefore never promoted to Applied for ReplaceSelection; EDT-013 may add a
            // stronger restoration protocol, but this slice must not publish a false receipt.
            TargetChangeReason intendedReason = intendedOutcome
                    instanceof EditorSessionManager.ReplaceValidated
                    ? TargetChangeReason.EVIDENCE_UNAVAILABLE
                    : ((EditorSessionManager.ReplaceValidationInvalid) intendedOutcome).reason();
            TargetChangeReason originalReason = intendedOutcome
                            instanceof EditorSessionManager.ReplaceValidated
                    ? TargetChangeReason.EVIDENCE_UNAVAILABLE
                    : originalOutcome instanceof EditorSessionManager.ReplaceValidated
                    ? TargetChangeReason.EVIDENCE_UNAVAILABLE
                    : ((EditorSessionManager.ReplaceValidationInvalid) originalOutcome).reason();
            TransactionFailureKind rollbackKind = isTargetInvalidated(intendedReason)
                            || isTargetInvalidated(originalReason)
                    ? TransactionFailureKind.TARGET_INVALIDATED
                    : TransactionFailureKind.OUTCOME_UNCONFIRMED;
            return rollbackFailed(original, rollbackKind);
        }

        EditorSessionManager.HostValidationResult post = sessions.validateCurrentSession(
                intended.snapshot(), authoritySupplier, evidenceReader);
        if (post instanceof EditorSessionManager.Validated) {
            return new EditorTransactionResult.Applied();
        }

        EditorSessionManager.HostValidationResult pre = sessions.validateCurrentSession(
                expected, authoritySupplier, evidenceReader);
        // A bounded fingerprint match proves only the captured window, not that a remote mutator
        // which returned false had no side effect outside that window. EDT-013 adds a richer,
        // verified restoration path; this foundation therefore remains fail closed instead of
        // claiming RolledBack from the original snapshot alone.
        TargetChangeReason postReason = ((EditorSessionManager.ValidationInvalid) post).reason();
        boolean originalWindowStillMatches = pre instanceof EditorSessionManager.Validated;
        TargetChangeReason preReason = originalWindowStillMatches
                ? TargetChangeReason.EVIDENCE_UNAVAILABLE
                : ((EditorSessionManager.ValidationInvalid) pre).reason();
        TransactionFailureKind rollbackKind = !originalWindowStillMatches
                        && (isTargetInvalidated(postReason) || isTargetInvalidated(preReason))
                ? TransactionFailureKind.TARGET_INVALIDATED
                : TransactionFailureKind.OUTCOME_UNCONFIRMED;
        return rollbackFailed(original, rollbackKind);
    }

    private static boolean invokeMutator(InputConnection connection, EditorOperation operation) {
        String committedText = null;
        if (operation instanceof EditorOperation.InsertText insert) {
            committedText = insert.text();
        } else if (operation instanceof EditorOperation.ReplaceSelection replace) {
            committedText = replace.text();
        }
        if (committedText != null) {
            return connection.commitText(committedText, 1);
        }
        if (operation instanceof EditorOperation.DeleteBeforeCursor delete) {
            return connection.deleteSurroundingTextInCodePoints(delete.codePoints(), 0);
        }
        if (operation instanceof EditorOperation.SetComposition composition) {
            return connection.setComposingText(composition.text(), 1);
        }
        if (operation instanceof EditorOperation.CommitComposition) {
            return connection.finishComposingText();
        }
        EditorOperation.PerformEditorAction action = (EditorOperation.PerformEditorAction) operation;
        return connection.performEditorAction(platformAction(action.action()));
    }

    private IntendedState intendedState(
            EditorSessionSnapshot expected,
            EditorOperation operation,
            EditorSessionManager.ValidatedEvidence evidence) {
        if (expected.sensitive()) {
            if (operation instanceof EditorOperation.PerformEditorAction
                    || isCompositionOperation(operation)) return null;
            // Sensitive targets intentionally expose no text evidence. A successful host return is
            // authoritative, but false/exception outcomes cannot be proven by an intended snapshot.
            return new IntendedState(expected);
        }
        if (operation instanceof EditorOperation.PerformEditorAction
                || isCompositionOperation(operation)) return null;

        String before = evidence.before();
        if (operation instanceof EditorOperation.InsertText insert) {
            TextRange intendedSelection = intendedSelection(
                    expected.selection(), operation, insert.text().length());
            if (intendedSelection == null) return null;
            return captureIntended(
                    expected,
                    intendedSelection,
                    appendedTail(before, insert.text()),
                    evidence.after());
        }

        if (operation instanceof EditorOperation.ReplaceSelection replace) {
            TextRange intendedSelection = intendedSelection(
                    expected.selection(), operation, replace.text().length());
            if (intendedSelection == null) return null;
            return captureIntended(
                    expected,
                    intendedSelection,
                    appendedTail(before, replace.text()),
                    evidence.after());
        }

        EditorOperation.DeleteBeforeCursor delete = (EditorOperation.DeleteBeforeCursor) operation;
        if (delete.codePoints() > MAX_PROVABLE_DELETE_CODE_POINTS) return null;
        int available = before.codePointCount(0, before.length());
        if (available < delete.codePoints()) return null;
        int retainedEnd = before.offsetByCodePoints(before.length(), -delete.codePoints());
        int deletedUtf16 = before.length() - retainedEnd;
        TextRange intendedSelection = intendedSelection(
                expected.selection(), operation, deletedUtf16);
        if (intendedSelection == null) return null;
        return captureIntended(
                expected,
                intendedSelection,
                before.substring(0, retainedEnd),
                evidence.after());
    }

    private IntendedState captureIntended(
            EditorSessionSnapshot expected,
            TextRange selection,
            String before,
            String after) {
        try {
            return new IntendedState(EditorSessionSnapshot.capture(
                    expected.epoch(),
                    expected.connectionToken(),
                    expected.packageName(),
                    expected.fieldId(),
                    expected.fieldKind(),
                    expected.inputType(),
                    expected.imeOptions(),
                    selection,
                    "",
                    before,
                    after,
                    expected.learningAllowed(),
                    expected.sensitive(),
                    expected.capturedAtElapsedRealtimeMs()));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static TextRange intendedSelection(
            TextRange current, EditorOperation operation, int textUtf16Units) {
        long value;
        if (operation instanceof EditorOperation.InsertText) {
            value = (long) current.start() + textUtf16Units;
        } else if (operation instanceof EditorOperation.ReplaceSelection) {
            value = (long) Math.min(current.start(), current.end()) + textUtf16Units;
        } else {
            value = (long) current.start() - textUtf16Units;
        }
        if (value < 0 || value > Integer.MAX_VALUE) return null;
        return new TextRange((int) value, (int) value);
    }

    private static String appendedTail(String before, String inserted) {
        int insertedCodePoints = inserted.codePointCount(0, inserted.length());
        int limit = EditorSessionLimits.SURROUNDING_CONTEXT_CODE_POINTS;
        if (insertedCodePoints >= limit) {
            int start = inserted.offsetByCodePoints(0, insertedCodePoints - limit);
            return inserted.substring(start);
        }
        int requiredBefore = limit - insertedCodePoints;
        int beforeCodePoints = before.codePointCount(0, before.length());
        int beforeStart = beforeCodePoints <= requiredBefore
                ? 0
                : before.offsetByCodePoints(0, beforeCodePoints - requiredBefore);
        return before.substring(beforeStart) + inserted;
    }

    private RejectionReason policyRejection(
            EditorSessionSnapshot expected,
            EditorOperation operation,
            EditorSessionManager.Validated validated,
            boolean requireDeleteEvidence) {
        if (!(operation instanceof EditorOperation.InsertText)
                && !(operation instanceof EditorOperation.ReplaceSelection)
                && !(operation instanceof EditorOperation.DeleteBeforeCursor)
                && !(operation instanceof EditorOperation.PerformEditorAction)
                && !isCompositionOperation(operation)) {
            return RejectionReason.OPERATION_NOT_SUPPORTED;
        }
        if (operation instanceof EditorOperation.ReplaceSelection replace) {
            if (validated.lease().sensitive() || expected.sensitive()) {
                return RejectionReason.SENSITIVE_FIELD;
            }
            if (!expected.selection().hasSelection()) {
                return RejectionReason.OPERATION_NOT_SUPPORTED;
            }
            if (!replace.expectedSelection().equals(expected.selection())
                    || !replace.expectedSelection().equals(validated.evidence().selection())
                    || !replace.expectedTextHash().securelyMatches(
                            expected.selectedTextFingerprint())
                    || !replace.expectedTextHash().securelyMatches(
                            com.opentypeless.android.editor.Sha256EditorTextHasher.INSTANCE
                                    .selectedText(validated.evidence().selected()))) {
                return RejectionReason.POLICY_DENIED;
            }
        } else if (!expected.selection().isKnown() || !expected.selection().isCollapsed()) {
            return RejectionReason.OPERATION_NOT_SUPPORTED;
        }
        // UNDO and RAW_RESTORE are authorization-bearing flows, not producer labels that ordinary
        // apply() callers may opt into. Only the exact-ID facades may privately dispatch their
        // physical operations after full committed-text proof. Sensitive Replace is classified
        // first so every source follows the same zero-evidence privacy boundary.
        if (operation.source() == OperationSource.UNDO
                || operation.source() == OperationSource.RAW_RESTORE) {
            return RejectionReason.OPERATION_NOT_SUPPORTED;
        }
        if (validated.lease().sensitive()
                && operation.source() != OperationSource.LATIN
                && operation.source() != OperationSource.RIME) {
            return RejectionReason.SENSITIVE_FIELD;
        }
        RejectionReason compositionPolicy = compositionPolicy(operation);
        if (compositionPolicy != null) return compositionPolicy;
        if (operation instanceof EditorOperation.PerformEditorAction action
                && !actionAvailable(expected.imeOptions(), action.action())) {
            return RejectionReason.EDITOR_ACTION_UNAVAILABLE;
        }
        if (operation instanceof EditorOperation.DeleteBeforeCursor delete
                && !expected.sensitive()
                && (delete.codePoints() > MAX_PROVABLE_DELETE_CODE_POINTS
                        || (requireDeleteEvidence
                                && validated.evidence().before().codePointCount(
                                        0,
                                        validated.evidence().before().length())
                                < delete.codePoints()))) {
            return RejectionReason.ROLLBACK_PRECONDITION_UNAVAILABLE;
        }
        return null;
    }

    private RejectionReason compositionPolicy(EditorOperation operation) {
        if (compositionUncertain) return RejectionReason.POLICY_DENIED;

        if (operation instanceof EditorOperation.SetComposition set) {
            if (compositionOwner != CompositionOwner.NONE
                    && compositionOwner != set.owner()) {
                return RejectionReason.COMPOSITION_OWNER_MISMATCH;
            }
            long highWatermark = compositionHighWatermarks.getOrDefault(set.owner(), 0L);
            if (set.revision() <= highWatermark) {
                return RejectionReason.COMPOSITION_REVISION_MISMATCH;
            }
            return null;
        }
        if (operation instanceof EditorOperation.CommitComposition commit) {
            if (compositionOwner != commit.owner()) {
                return RejectionReason.COMPOSITION_OWNER_MISMATCH;
            }
            return compositionRevision == commit.expectedRevision()
                    ? null
                    : RejectionReason.COMPOSITION_REVISION_MISMATCH;
        }
        return compositionOwner == CompositionOwner.NONE
                ? null
                : RejectionReason.POLICY_DENIED;
    }

    private CommitPreparation prepareCommit(
            EditorSessionSnapshot expected,
            EditorOperation operation,
            CommitRecordRequest request,
            boolean sensitive) {
        if (request instanceof CommitRecordRequest.None) return CommitPreparation.none();
        if (sensitive) {
            return CommitPreparation.rejected(RejectionReason.SENSITIVE_FIELD);
        }
        if (operation.source() != OperationSource.VOICE
                && operation.source() != OperationSource.ACTION) {
            return CommitPreparation.rejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE);
        }

        CommitRecord.RawTranscript raw =
                ((CommitRecordRequest.Requested) request).rawTranscript();
        EditorSessionSnapshot original;
        String inserted;
        if (operation instanceof EditorOperation.InsertText insert) {
            original = expected;
            inserted = insert.text();
        } else if (operation instanceof EditorOperation.ReplaceSelection replace) {
            original = expected;
            inserted = replace.text();
        } else if (operation instanceof EditorOperation.CommitComposition commit) {
            CompositionCommitBasis basis = compositionCommitBasis;
            if (basis == null
                    || basis.owner() != commit.owner()
                    || basis.revision() != commit.expectedRevision()
                    || basis.originalSession().epoch() != expected.epoch()
                    || basis.originalSession().connectionToken() != expected.connectionToken()) {
                return CommitPreparation.rejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE);
            }
            original = basis.originalSession();
            inserted = basis.latestText();
            if (inserted.isEmpty()) {
                return CommitPreparation.rejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE);
            }
        } else {
            return CommitPreparation.rejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE);
        }

        CommitLedger.Reservation reservation = commitLedger.reserve(
                original, operation.source(), inserted, raw);
        return reservation == null
                ? CommitPreparation.rejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE)
                : CommitPreparation.reserved(reservation);
    }

    private TransactionReceipt receiptFor(
            EditorTransactionResult result, CommitPreparation preparation) {
        if (!(result instanceof EditorTransactionResult.Applied)) {
            return withoutCommit(result);
        }
        if (preparation.reservation() == null) {
            return withoutCommit(result);
        }
        CommitRecord record = preparation.publish();
        if (record == null) {
            throw new IllegalStateException("commit reservation was revoked");
        }
        return new TransactionReceipt.Committed((EditorTransactionResult.Applied) result, record);
    }

    private void updateLedgerForResult(
            EditorTransactionResult result, CommitPreparation preparation) {
        if (result instanceof EditorTransactionResult.Applied) {
            if (!preparation.published()) commitLedger.revokeAll();
            return;
        }
        if (result instanceof EditorTransactionResult.TargetChanged
                || result instanceof EditorTransactionResult.RollbackFailed) {
            commitLedger.revokeAll();
        }
    }

    private static RejectionReason undoStructuralRejection(CommitRecord record) {
        if ((record.source() != OperationSource.VOICE
                        && record.source() != OperationSource.ACTION)
                || record.originalSession().sensitive()
                || record.insertedText().isEmpty()) {
            return RejectionReason.COMMIT_RECORD_UNAVAILABLE;
        }
        TextRange origin = record.originalSession().selection();
        String selected = record.originalSession().selectedText();
        if (!origin.isKnown()
                || (origin.isCollapsed() && !selected.isEmpty())
                || (origin.hasSelection() && selected.isEmpty())) {
            return RejectionReason.OPERATION_NOT_SUPPORTED;
        }
        return null;
    }

    private static RejectionReason rawStructuralRejection(CommitRecord record) {
        if (record.source() != OperationSource.VOICE
                || record.originalSession().sensitive()
                || record.insertedText().isEmpty()) {
            return RejectionReason.COMMIT_RECORD_UNAVAILABLE;
        }
        TextRange origin = record.originalSession().selection();
        String selected = record.originalSession().selectedText();
        if (!origin.isKnown()
                || (origin.isCollapsed() && !selected.isEmpty())
                || (origin.hasSelection() && selected.isEmpty())) {
            return RejectionReason.OPERATION_NOT_SUPPORTED;
        }
        String raw = rawText(record);
        if (raw == null || raw.equals(record.insertedText())) {
            return RejectionReason.COMMIT_RECORD_UNAVAILABLE;
        }
        return null;
    }

    private static String rawText(CommitRecord record) {
        if (!(record.rawTranscript() instanceof CommitRecord.RawTranscript.Present present)) {
            return null;
        }
        return present.text();
    }

    private static int undoCommittedCursor(CommitRecord record) {
        try {
            return Math.addExact(
                    Math.min(
                            record.originalSession().selection().start(),
                            record.originalSession().selection().end()),
                    record.insertedText().length());
        } catch (ArithmeticException overflow) {
            return -1;
        }
    }

    private void updateLedgerForUndo(
            EditorTransactionResult result,
            String commitId,
            EditorSessionSnapshot expectedCurrent) {
        if (result instanceof EditorTransactionResult.Applied) {
            commitLedger.consume(commitId, expectedCurrent);
            return;
        }
        if (result instanceof EditorTransactionResult.TargetChanged
                || result instanceof EditorTransactionResult.RollbackFailed) {
            commitLedger.revokeAll();
        }
    }

    private void updateLedgerForRaw(
            EditorTransactionResult result,
            String commitId,
            EditorSessionSnapshot expectedCurrent) {
        if (result instanceof EditorTransactionResult.Applied) {
            commitLedger.consume(commitId, expectedCurrent);
            return;
        }
        if (result instanceof EditorTransactionResult.TargetChanged
                || result instanceof EditorTransactionResult.RollbackFailed) {
            commitLedger.revokeAll();
        }
    }

    void revokeSessionState() {
        sessions.requireOwnerThreadForLifecycle();
        if (applying) {
            sessionRevokePending = true;
            return;
        }
        clearSessionState();
    }

    Optional<CommitRecord> resolveCommitRecord(
            String commitId, EditorSessionSnapshot currentSession) {
        sessions.requireOwnerThreadForLifecycle();
        if (applying || sessionRevokePending) return Optional.empty();
        sessions.requireOwnerThreadForHost();
        return commitLedger.resolve(commitId, currentSession);
    }

    Optional<CommitRecord> consumeCommitRecord(
            String commitId, EditorSessionSnapshot currentSession) {
        sessions.requireOwnerThreadForLifecycle();
        if (applying || sessionRevokePending) return Optional.empty();
        sessions.requireOwnerThreadForHost();
        return commitLedger.consume(commitId, currentSession);
    }

    int commitRecordCountForTest() {
        sessions.requireOwnerThreadForLifecycle();
        return commitLedger.sizeForTest();
    }

    private void clearSessionState() {
        commitLedger.revokeAll();
        compositionEpoch = Long.MIN_VALUE;
        compositionToken = Long.MIN_VALUE;
        compositionOwner = CompositionOwner.NONE;
        compositionRevision = 0L;
        compositionUncertain = false;
        compositionCommitBasis = null;
        compositionHighWatermarks.clear();
    }

    /** Binds logical composition authority only after the corresponding session validated. */
    private void bindCompositionAuthority(EditorSessionSnapshot expected) {
        if (compositionEpoch == expected.epoch()
                && compositionToken == expected.connectionToken()) return;
        compositionEpoch = expected.epoch();
        compositionToken = expected.connectionToken();
        compositionOwner = CompositionOwner.NONE;
        compositionRevision = 0L;
        compositionUncertain = false;
        compositionCommitBasis = null;
        compositionHighWatermarks.clear();
    }

    private void recordCompositionSuccess(
            EditorOperation operation, EditorSessionSnapshot expected) {
        if (operation instanceof EditorOperation.SetComposition set) {
            compositionOwner = set.owner();
            compositionRevision = set.revision();
            compositionHighWatermarks.put(set.owner(), set.revision());
            if (!expected.sensitive()
                    && (set.source() == OperationSource.VOICE
                            || set.source() == OperationSource.ACTION)) {
                EditorSessionSnapshot origin = compositionCommitBasis == null
                        ? expected
                        : compositionCommitBasis.originalSession();
                compositionCommitBasis = new CompositionCommitBasis(
                        origin, set.owner(), set.revision(), set.text());
            } else {
                compositionCommitBasis = null;
            }
        } else if (operation instanceof EditorOperation.CommitComposition) {
            compositionOwner = CompositionOwner.NONE;
            compositionRevision = 0L;
            compositionCommitBasis = null;
        }
    }

    private void recordCompositionUncertain(EditorOperation operation) {
        if (operation instanceof EditorOperation.SetComposition set) {
            compositionOwner = set.owner();
            compositionRevision = set.revision();
            compositionHighWatermarks.put(set.owner(), set.revision());
        }
        compositionCommitBasis = null;
        compositionUncertain = true;
    }

    private static boolean isCompositionOperation(EditorOperation operation) {
        return operation instanceof EditorOperation.SetComposition
                || operation instanceof EditorOperation.CommitComposition;
    }

    private static boolean requiresIntendedState(EditorOperation operation) {
        return operation instanceof EditorOperation.InsertText
                || operation instanceof EditorOperation.ReplaceSelection
                || operation instanceof EditorOperation.DeleteBeforeCursor;
    }

    private static boolean actionAvailable(int imeOptions, EditorAction action) {
        if ((imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return false;
        return (imeOptions & EditorInfo.IME_MASK_ACTION) == platformAction(action);
    }

    private static int platformAction(EditorAction action) {
        return switch (action) {
            case GO -> EditorInfo.IME_ACTION_GO;
            case SEARCH -> EditorInfo.IME_ACTION_SEARCH;
            case SEND -> EditorInfo.IME_ACTION_SEND;
            case NEXT -> EditorInfo.IME_ACTION_NEXT;
            case DONE -> EditorInfo.IME_ACTION_DONE;
            case PREVIOUS -> EditorInfo.IME_ACTION_PREVIOUS;
        };
    }

    private static boolean beginBatch(InputConnection connection) {
        try {
            return connection.beginBatchEdit();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void finishBatch(InputConnection connection) {
        try {
            if (!connection.endBatchEdit()) recordCleanup(CleanupFailure.END_BATCH_REJECTED);
        } catch (RuntimeException unavailable) {
            recordCleanup(CleanupFailure.END_BATCH_RUNTIME_FAILURE);
        }
    }

    private void recordCleanup(CleanupFailure failure) {
        try {
            cleanupSink.record(failure);
        } catch (RuntimeException ignored) {
            // Cleanup diagnostics are best-effort and may never replace the transaction result.
        }
    }

    private TransactionReceipt auditReceipt(
            EditorOperation operation, TransactionReceipt receipt) {
        recordAudit(new EditorTransactionAudit(
                operation.source(), operationKind(operation), receipt.result()));
        return receipt;
    }

    private EditorTransactionResult auditResult(
            OperationSource source,
            EditorOperationKind operationKind,
            EditorTransactionResult result) {
        recordAudit(new EditorTransactionAudit(source, operationKind, result));
        return result;
    }

    private void recordAudit(EditorTransactionAudit audit) {
        try {
            auditSink.record(audit);
        } catch (RuntimeException ignored) {
            // Audit is observation only; a hostile diagnostic consumer cannot alter the outcome.
        }
    }

    private static EditorOperationKind operationKind(EditorOperation operation) {
        if (operation instanceof EditorOperation.SetComposition) {
            return EditorOperationKind.SET_COMPOSITION;
        }
        if (operation instanceof EditorOperation.CommitComposition) {
            return EditorOperationKind.COMMIT_COMPOSITION;
        }
        if (operation instanceof EditorOperation.InsertText) {
            return EditorOperationKind.INSERT_TEXT;
        }
        if (operation instanceof EditorOperation.ReplaceSelection) {
            return EditorOperationKind.REPLACE_SELECTION;
        }
        if (operation instanceof EditorOperation.ReplaceLastCommit) {
            return EditorOperationKind.REPLACE_LAST_COMMIT;
        }
        if (operation instanceof EditorOperation.DeleteBeforeCursor) {
            return EditorOperationKind.DELETE_BEFORE_CURSOR;
        }
        if (operation instanceof EditorOperation.PerformEditorAction) {
            return EditorOperationKind.PERFORM_EDITOR_ACTION;
        }
        throw new AssertionError("unknown closed editor operation");
    }

    private static TransactionFailureStep failureStep(EditorOperation operation) {
        if (operation instanceof EditorOperation.InsertText
                || operation instanceof EditorOperation.ReplaceSelection) {
            return TransactionFailureStep.INSERT_TEXT;
        }
        if (operation instanceof EditorOperation.DeleteBeforeCursor) {
            return TransactionFailureStep.DELETE_TEXT;
        }
        if (operation instanceof EditorOperation.SetComposition) {
            return TransactionFailureStep.SET_COMPOSITION;
        }
        if (operation instanceof EditorOperation.CommitComposition) {
            return TransactionFailureStep.FINISH_COMPOSITION;
        }
        return TransactionFailureStep.PERFORM_EDITOR_ACTION;
    }

    private static EditorTransactionResult unwrap(EditorSessionManager.ConnectionUseResult result) {
        if (result instanceof EditorSessionManager.ConnectionUsed used) return used.result();
        return targetChanged(((EditorSessionManager.ConnectionInvalid) result).reason());
    }

    private static TransactionReceipt unwrapReceipt(
            EditorSessionManager.ReceiptConnectionUseResult result) {
        if (result instanceof EditorSessionManager.ReceiptConnectionUsed used) {
            return used.receipt();
        }
        return withoutCommit(targetChanged(
                ((EditorSessionManager.ReceiptConnectionInvalid) result).reason()));
    }

    private static TransactionReceipt withoutCommit(EditorTransactionResult result) {
        return new TransactionReceipt.WithoutCommit(result);
    }

    private static EditorTransactionResult outcomeUnconfirmed(TransactionFailure original) {
        return rollbackFailed(original, TransactionFailureKind.OUTCOME_UNCONFIRMED);
    }

    private static TransactionFailure applyFailure(
            TransactionFailureStep step, TransactionFailureKind kind) {
        return new TransactionFailure(TransactionFailurePhase.APPLY, step, kind);
    }

    private static TransactionFailureKind classificationFor(TargetChangeReason reason) {
        return isTargetInvalidated(reason)
                ? TransactionFailureKind.TARGET_INVALIDATED
                : TransactionFailureKind.OUTCOME_UNCONFIRMED;
    }

    private static EditorTransactionResult rollbackFailed(
            TransactionFailure original, TransactionFailureKind rollbackKind) {
        return rollbackFailed(
                original,
                TransactionFailureStep.VERIFY_EDITOR_STATE,
                rollbackKind);
    }

    private static EditorTransactionResult rollbackFailed(
            TransactionFailure original,
            TransactionFailureStep rollbackStep,
            TransactionFailureKind rollbackKind) {
        return new EditorTransactionResult.RollbackFailed(
                original,
                new TransactionFailure(
                        TransactionFailurePhase.ROLLBACK,
                        rollbackStep,
                        rollbackKind));
    }

    private static boolean isTargetInvalidated(TargetChangeReason reason) {
        return reason != TargetChangeReason.SELECTED_TEXT_CHANGED
                && reason != TargetChangeReason.SURROUNDING_TEXT_CHANGED
                && reason != TargetChangeReason.EVIDENCE_UNAVAILABLE;
    }

    private static EditorTransactionResult targetChanged(TargetChangeReason reason) {
        return new EditorTransactionResult.TargetChanged(reason);
    }

    private static EditorTransactionResult rejected(RejectionReason reason) {
        return new EditorTransactionResult.Rejected(reason);
    }

    @Override
    public String toString() {
        return "EditorTransactionManager{<redacted>}";
    }

    private record IntendedState(EditorSessionSnapshot snapshot) {
        private IntendedState {
            Objects.requireNonNull(snapshot, "snapshot");
        }

        @Override
        public String toString() {
            return "IntendedState{<redacted>}";
        }
    }

    private record CompositionCommitBasis(
            EditorSessionSnapshot originalSession,
            CompositionOwner owner,
            long revision,
            String latestText) {
        private CompositionCommitBasis {
            Objects.requireNonNull(originalSession, "originalSession");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(latestText, "latestText");
        }

        @Override
        public String toString() {
            return "CompositionCommitBasis{<redacted>}";
        }
    }

    private static final class CommitPreparation {
        private final CommitLedger.Reservation reservation;
        private final RejectionReason rejection;
        private boolean published;

        private CommitPreparation(
                CommitLedger.Reservation reservation, RejectionReason rejection) {
            this.reservation = reservation;
            this.rejection = rejection;
        }

        static CommitPreparation none() {
            return new CommitPreparation(null, null);
        }

        static CommitPreparation reserved(CommitLedger.Reservation reservation) {
            return new CommitPreparation(
                    Objects.requireNonNull(reservation, "reservation"), null);
        }

        static CommitPreparation rejected(RejectionReason rejection) {
            return new CommitPreparation(null, Objects.requireNonNull(rejection, "rejection"));
        }

        RejectionReason rejection() {
            return rejection;
        }

        CommitLedger.Reservation reservation() {
            return reservation;
        }

        CommitRecord publish() {
            if (reservation == null || published) return null;
            CommitRecord record = reservation.publish();
            published = record != null;
            return record;
        }

        boolean published() {
            return published;
        }

        void abort() {
            if (reservation != null && !published) reservation.abort();
        }

        @Override
        public String toString() {
            return "CommitPreparation{<redacted>}";
        }
    }
}
