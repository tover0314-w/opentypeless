package com.opentypeless.android.editor.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
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
import com.opentypeless.android.editor.FingerprintDomain;
import com.opentypeless.android.editor.OperationSource;
import com.opentypeless.android.editor.RejectionReason;
import com.opentypeless.android.editor.Sha256EditorTextHasher;
import com.opentypeless.android.editor.TargetChangeReason;
import com.opentypeless.android.editor.TextRange;
import com.opentypeless.android.editor.TransactionReceipt;
import com.opentypeless.android.editor.TransactionFailureKind;
import com.opentypeless.android.editor.TransactionFailureStep;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class EditorTransactionManagerTest {
    @Test
    public void voiceFacadesRouteCompositionFinalReceiptAndExactIdConsumers() {
        Harness composition = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        assertTrue(composition.voiceSet("partial", 1L)
                instanceof EditorTransactionResult.Applied);
        CommitRecord compositionRecord = committed(composition.voiceCommit(
                1L, new CommitRecord.RawTranscript.Present("raw")));
        assertEquals(OperationSource.VOICE, compositionRecord.source());
        assertEquals("partial", compositionRecord.insertedText());
        assertEquals(1, composition.setCompositionCalls);
        assertEquals(1, composition.finishCompositionCalls);

        Harness selected = Harness.selected("preOLDpost", 3, 6);
        CommitRecord selectedRecord = committed(selected.voiceText(
                "voice", new CommitRecord.RawTranscript.Present("raw voice")));
        assertEquals("prevoicepost", selected.text);
        assertEquals(new TextRange(3, 6), selectedRecord.originalSession().selection());
        selected.recapture();
        assertTrue(selected.voiceUndo(selectedRecord.commitId())
                instanceof EditorTransactionResult.Applied);
        assertEquals("preOLDpost", selected.text);
        assertEquals(0, selected.commitRecordCount());

        Harness raw = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord rawRecord = committed(raw.voiceText(
                "final", new CommitRecord.RawTranscript.Present("raw")));
        raw.recapture();
        assertTrue(raw.voiceRaw(rawRecord.commitId())
                instanceof EditorTransactionResult.Applied);
        assertEquals("preraw", raw.text);
        assertEquals(0, raw.commitRecordCount());
    }

    @Test
    public void keyboardFacadesChooseExactLatinOperationWithoutLegacyFallback() {
        Harness inserted = Harness.normal("ab", 2, EditorInfo.IME_ACTION_NONE);
        assertTrue(inserted.keyboardInsert(" ") instanceof EditorTransactionResult.Applied);
        assertEquals("ab ", inserted.text);
        assertEquals(1, inserted.commitCalls);
        assertEquals(0, inserted.deleteCalls);

        Harness replaced = Harness.selected("abcd", 1, 3);
        assertTrue(replaced.keyboardInsert("。") instanceof EditorTransactionResult.Applied);
        assertEquals("a。d", replaced.text);
        assertEquals(1, replaced.commitCalls);
        assertEquals(0, replaced.deleteCalls);

        Harness deletedSelection = Harness.selected("abcd", 1, 3);
        assertTrue(deletedSelection.keyboardDelete()
                instanceof EditorTransactionResult.Applied);
        assertEquals("ad", deletedSelection.text);
        assertEquals(1, deletedSelection.commitCalls);
        assertEquals(0, deletedSelection.deleteCalls);

        Harness deletedCodePoint = Harness.normal("A\uD83D\uDE00", 3,
                EditorInfo.IME_ACTION_NONE);
        assertTrue(deletedCodePoint.keyboardDelete()
                instanceof EditorTransactionResult.Applied);
        assertEquals("A", deletedCodePoint.text);
        assertEquals(1, deletedCodePoint.lastDeleteBeforeCodePoints);
        assertEquals(0, deletedCodePoint.commitCalls);
        assertEquals(1, deletedCodePoint.deleteCalls);
    }

    @Test
    public void keyboardEnterMapsAllowlistedActionsAndOtherwiseCommitsNewline() {
        EditorAction[] actions = EditorAction.values();
        int[] ids = {
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_NEXT,
                EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_PREVIOUS
        };
        for (int index = 0; index < actions.length; index++) {
            Harness action = Harness.normal("x", 1, ids[index]);
            assertTrue(action.keyboardEnter() instanceof EditorTransactionResult.Applied);
            assertEquals(ids[index], action.lastAction);
            assertEquals(1, action.actionCalls);
            assertEquals(0, action.commitCalls);
        }

        Harness unspecified = Harness.normal("x", 1, EditorInfo.IME_ACTION_NONE);
        assertTrue(unspecified.keyboardEnter() instanceof EditorTransactionResult.Applied);
        assertEquals("x\n", unspecified.text);
        assertEquals(1, unspecified.commitCalls);
        assertEquals(0, unspecified.actionCalls);

        Harness noEnterAction = Harness.normal(
                "x",
                1,
                EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        assertTrue(noEnterAction.keyboardEnter() instanceof EditorTransactionResult.Applied);
        assertEquals("x\n", noEnterAction.text);
        assertEquals(0, noEnterAction.actionCalls);

        Harness selected = Harness.selected(
                "abcd", 1, 3, EditorInfo.IME_ACTION_NONE, Harness.sequentialIds());
        assertTrue(selected.keyboardEnter() instanceof EditorTransactionResult.Applied);
        assertEquals("a\nd", selected.text);
        assertEquals(1, selected.commitCalls);
    }

    @Test
    public void keyboardFacadeKeepsSensitiveAndCompositionGuardsFailClosed() {
        Harness sensitive = Harness.sensitive("secret", 6);
        assertTrue(sensitive.keyboardInsert("x") instanceof EditorTransactionResult.Applied);
        assertEquals("secretx", sensitive.text);
        assertEquals(0, sensitive.evidenceReads);

        Harness selectedSensitive = Harness.sensitiveSelected(
                "secret", 0, 6, Harness.sequentialIds());
        assertRejected(RejectionReason.SENSITIVE_FIELD,
                selectedSensitive.keyboardInsert("x"));
        assertEquals(0, selectedSensitive.evidenceReads);
        assertEquals(0, selectedSensitive.contentMutators());

        Harness composing = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        assertTrue(composing.apply(set(
                        "partial", CompositionOwner.VOICE, 1, OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);
        assertRejected(RejectionReason.POLICY_DENIED, composing.keyboardInsert("x"));
        assertEquals(1, composing.setCompositionCalls);
        assertEquals(0, composing.commitCalls);

        Harness invalidText = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        assertRejected(RejectionReason.POLICY_DENIED, invalidText.keyboardInsert(""));
        assertEquals(0, invalidText.beginCalls);
        assertEquals(0, invalidText.contentMutators());
    }

    @Test
    public void insertDeleteAndAllSemanticActionsUseOneBalancedBatch() {
        Harness insert = Harness.normal("ab", 2, EditorInfo.IME_ACTION_NONE);
        assertTrue(insert.apply(new EditorOperation.InsertText("x", OperationSource.LATIN))
                instanceof EditorTransactionResult.Applied);
        assertEquals("abx", insert.text);
        assertEquals(List.of("authority", "evidence", "authority", "begin", "authority",
                "evidence", "authority", "commit:x:1", "end"), insert.trace);

        Harness delete = Harness.normal("A\uD83D\uDE00", 3, EditorInfo.IME_ACTION_NONE);
        assertTrue(delete.apply(new EditorOperation.DeleteBeforeCursor(1, OperationSource.RIME))
                instanceof EditorTransactionResult.Applied);
        assertEquals("A", delete.text);
        assertEquals(1, delete.deleteCalls);
        assertEquals(1, delete.endCalls);

        EditorAction[] actions = EditorAction.values();
        int[] ids = {2, 3, 4, 5, 6, 7};
        for (int index = 0; index < actions.length; index++) {
            Harness action = Harness.normal("a", 1, ids[index]);
            assertTrue(action.apply(new EditorOperation.PerformEditorAction(
                    actions[index], OperationSource.LATIN))
                    instanceof EditorTransactionResult.Applied);
            assertEquals(ids[index], action.lastAction);
            assertEquals(1, action.actionCalls);
        }
    }

    @Test
    public void unsupportedOperationsAndPolicyFailuresNeverBeginBatch() {
        Harness harness = Harness.normal("ab", 2, EditorInfo.IME_ACTION_NONE);
        List<EditorOperation> unsupported = List.of(
                new EditorOperation.ReplaceSelection(
                        new com.opentypeless.android.editor.TextRange(0, 1),
                        com.opentypeless.android.editor.Sha256EditorTextHasher.INSTANCE
                                .selectedText("a"),
                        "x",
                        OperationSource.LATIN),
                new EditorOperation.ReplaceLastCommit(
                        "id",
                        com.opentypeless.android.editor.Sha256EditorTextHasher.INSTANCE
                                .committedText("a"),
                        "x",
                        OperationSource.UNDO));
        for (EditorOperation operation : unsupported) {
            assertRejected(RejectionReason.OPERATION_NOT_SUPPORTED, harness.apply(operation));
        }
        assertEquals(0, harness.beginCalls);

        Harness actionMismatch = Harness.normal("x", 1, EditorInfo.IME_ACTION_DONE);
        assertRejected(RejectionReason.EDITOR_ACTION_UNAVAILABLE, actionMismatch.apply(
                new EditorOperation.PerformEditorAction(
                        EditorAction.SEND, OperationSource.LATIN)));
        assertEquals(0, actionMismatch.beginCalls);

        Harness longDelete = Harness.normal("x".repeat(70), 70, EditorInfo.IME_ACTION_NONE);
        assertRejected(RejectionReason.ROLLBACK_PRECONDITION_UNAVAILABLE, longDelete.apply(
                new EditorOperation.DeleteBeforeCursor(65, OperationSource.LATIN)));
        assertEquals(0, longDelete.beginCalls);
    }

    @Test
    public void compositionOwnerRevisionAndHighWatermarkAreEnforcedBeforeBatch() {
        Harness harness = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        assertTrue(harness.apply(set("first", CompositionOwner.VOICE, 1, OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);
        assertEquals(1, harness.setCompositionCalls);

        assertRejected(RejectionReason.COMPOSITION_REVISION_MISMATCH,
                harness.apply(set("duplicate", CompositionOwner.VOICE, 1, OperationSource.VOICE)));
        assertRejected(RejectionReason.COMPOSITION_OWNER_MISMATCH,
                harness.apply(set("other", CompositionOwner.RIME, 2, OperationSource.RIME)));
        assertRejected(RejectionReason.POLICY_DENIED,
                harness.apply(new EditorOperation.InsertText("x", OperationSource.LATIN)));
        assertEquals(1, harness.beginCalls);

        assertTrue(harness.apply(new EditorOperation.SetComposition(
                        "new", CompositionOwner.VOICE, Long.MAX_VALUE, OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);
        assertTrue(harness.apply(new EditorOperation.CommitComposition(
                        CompositionOwner.VOICE, Long.MAX_VALUE, OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);
        assertEquals(1, harness.finishCompositionCalls);

        assertRejected(RejectionReason.COMPOSITION_REVISION_MISMATCH,
                harness.apply(set("late", CompositionOwner.VOICE, 2, OperationSource.VOICE)));
        assertEquals(3, harness.beginCalls);
    }

    @Test
    public void compositionCommitRequiresExactOwnerAndRevisionAndEmptySetRemainsOwned() {
        Harness harness = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        assertTrue(harness.apply(set("", CompositionOwner.LATIN, 4, OperationSource.LATIN))
                instanceof EditorTransactionResult.Applied);
        assertEquals("", harness.lastCompositionText);
        assertRejected(RejectionReason.COMPOSITION_OWNER_MISMATCH,
                harness.apply(new EditorOperation.CommitComposition(
                        CompositionOwner.RIME, 4, OperationSource.RIME)));
        assertRejected(RejectionReason.COMPOSITION_REVISION_MISMATCH,
                harness.apply(new EditorOperation.CommitComposition(
                        CompositionOwner.LATIN, 3, OperationSource.LATIN)));
        assertTrue(harness.apply(new EditorOperation.CommitComposition(
                        CompositionOwner.LATIN, 4, OperationSource.LATIN))
                instanceof EditorTransactionResult.Applied);
        assertEquals(2, harness.beginCalls);
    }

    @Test
    public void compositionFalseOrThrowPoisonsOnlyTheValidatedSession() {
        for (Mode mode : List.of(
                Mode.MUTATOR_FALSE,
                Mode.MUTATOR_THROW,
                Mode.MUTATE_THEN_FALSE,
                Mode.MUTATE_THEN_THROW)) {
            Harness harness = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
            harness.mode = mode;
            EditorTransactionResult failed = harness.apply(
                    set("partial", CompositionOwner.VOICE, 1, OperationSource.VOICE));
            assertCompositionFailure(
                    failed,
                    TransactionFailureStep.SET_COMPOSITION,
                    mode == Mode.MUTATOR_THROW || mode == Mode.MUTATE_THEN_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : TransactionFailureKind.EDITOR_REJECTED);
            assertEquals(2, harness.evidenceReads);
            assertEquals(
                    mode == Mode.MUTATE_THEN_FALSE || mode == Mode.MUTATE_THEN_THROW
                            ? "partial"
                            : null,
                    harness.physicalCompositionText);
            assertRejected(RejectionReason.POLICY_DENIED,
                    harness.apply(set("retry", CompositionOwner.VOICE, 2, OperationSource.VOICE)));
            assertRejected(RejectionReason.POLICY_DENIED,
                    harness.apply(new EditorOperation.InsertText("x", OperationSource.LATIN)));

            harness.mode = Mode.NORMAL;
            harness.restartSession();
            assertTrue(harness.apply(
                            set("fresh", CompositionOwner.VOICE, 1, OperationSource.VOICE))
                    instanceof EditorTransactionResult.Applied);
        }
    }

    @Test
    public void compositionCommitFalseOrThrowPoisonsTheSessionWithoutRetry() {
        for (Mode mode : List.of(
                Mode.MUTATOR_FALSE,
                Mode.MUTATOR_THROW,
                Mode.MUTATE_THEN_FALSE,
                Mode.MUTATE_THEN_THROW)) {
            Harness harness = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
            assertTrue(harness.apply(
                            set("partial", CompositionOwner.RIME, 1, OperationSource.RIME))
                    instanceof EditorTransactionResult.Applied);
            harness.mode = mode;
            EditorTransactionResult failed = harness.apply(
                    new EditorOperation.CommitComposition(
                            CompositionOwner.RIME, 1, OperationSource.RIME));
            assertCompositionFailure(
                    failed,
                    TransactionFailureStep.FINISH_COMPOSITION,
                    mode == Mode.MUTATOR_THROW || mode == Mode.MUTATE_THEN_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : TransactionFailureKind.EDITOR_REJECTED);
            assertEquals(4, harness.evidenceReads);
            assertEquals(
                    mode == Mode.MUTATE_THEN_FALSE || mode == Mode.MUTATE_THEN_THROW
                            ? null
                            : "partial",
                    harness.physicalCompositionText);
            assertRejected(RejectionReason.POLICY_DENIED,
                    harness.apply(new EditorOperation.CommitComposition(
                            CompositionOwner.RIME, 1, OperationSource.RIME)));
        }
    }

    @Test
    public void sensitiveCompositionAllowsOnlyLocalOwnersAndReadsNoText() {
        Harness latin = Harness.sensitive("secret", 6);
        assertTrue(latin.apply(set("x", CompositionOwner.LATIN, 1, OperationSource.LATIN))
                instanceof EditorTransactionResult.Applied);
        assertTrue(latin.apply(new EditorOperation.CommitComposition(
                        CompositionOwner.LATIN, 1, OperationSource.LATIN))
                instanceof EditorTransactionResult.Applied);
        assertEquals(0, latin.evidenceReads);

        Harness voice = Harness.sensitive("secret", 6);
        assertRejected(RejectionReason.SENSITIVE_FIELD,
                voice.apply(set("x", CompositionOwner.VOICE, 1, OperationSource.VOICE)));
        assertEquals(0, voice.beginCalls);
        assertEquals(0, voice.evidenceReads);

        for (Mode mode : List.of(Mode.MUTATOR_FALSE, Mode.MUTATOR_THROW)) {
            Harness failedSet = Harness.sensitive("secret", 6);
            failedSet.mode = mode;
            assertCompositionFailure(
                    failedSet.apply(
                            set("x", CompositionOwner.LATIN, 1, OperationSource.LATIN)),
                    TransactionFailureStep.SET_COMPOSITION,
                    mode == Mode.MUTATOR_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : TransactionFailureKind.EDITOR_REJECTED);
            assertEquals(0, failedSet.evidenceReads);

            Harness failedFinish = Harness.sensitive("secret", 6);
            assertTrue(failedFinish.apply(
                            set("x", CompositionOwner.LATIN, 1, OperationSource.LATIN))
                    instanceof EditorTransactionResult.Applied);
            failedFinish.mode = mode;
            assertCompositionFailure(
                    failedFinish.apply(new EditorOperation.CommitComposition(
                            CompositionOwner.LATIN, 1, OperationSource.LATIN)),
                    TransactionFailureStep.FINISH_COMPOSITION,
                    mode == Mode.MUTATOR_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : TransactionFailureKind.EDITOR_REJECTED);
            assertEquals(0, failedFinish.evidenceReads);
        }
    }

    @Test
    public void compositionUsesDoubleValidationAndBeginRestartCannotMutate() {
        Harness success = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        assertTrue(success.apply(set("x", CompositionOwner.VOICE, 1, OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);
        assertEquals(List.of(
                        "authority", "evidence", "authority", "begin",
                        "authority", "evidence", "authority", "setComposition:x:1", "end"),
                success.trace);

        Harness restarted = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        restarted.onBegin = () -> restarted.manager.onStartInput(
                restarted.info, restarted.connection);
        assertTarget(
                TargetChangeReason.EPOCH_CHANGED,
                restarted.apply(set("x", CompositionOwner.VOICE, 1, OperationSource.VOICE)));
        assertEquals(0, restarted.setCompositionCalls);
        assertEquals(1, restarted.beginCalls);
        assertEquals(1, restarted.endCalls);
    }

    @Test
    public void beginFailuresRejectWithoutEndOrContentMutation() {
        for (Mode mode : List.of(Mode.BEGIN_FALSE, Mode.BEGIN_THROW)) {
            Harness harness = Harness.normal("safe", 4, EditorInfo.IME_ACTION_NONE);
            harness.mode = mode;
            assertRejected(RejectionReason.BATCH_EDIT_REJECTED, harness.apply(
                    new EditorOperation.InsertText("x", OperationSource.LATIN)));
            assertEquals(1, harness.beginCalls);
            assertEquals(0, harness.endCalls);
            assertEquals(0, harness.contentMutators());
        }
    }

    @Test
    public void endFailureIsContentFreeAndNeverOverridesApplied() {
        for (Mode mode : List.of(Mode.END_FALSE, Mode.END_THROW)) {
            Harness harness = Harness.normal("a", 1, EditorInfo.IME_ACTION_NONE);
            harness.mode = mode;
            assertTrue(harness.apply(new EditorOperation.InsertText("b", OperationSource.LATIN))
                    instanceof EditorTransactionResult.Applied);
            assertEquals(1, harness.cleanup.size());
            assertEquals(1, harness.endCalls);
        }
        Harness hostileSink = Harness.normal("a", 1, EditorInfo.IME_ACTION_NONE);
        hostileSink.mode = Mode.END_FALSE;
        hostileSink.cleanupThrows = true;
        assertTrue(hostileSink.apply(new EditorOperation.InsertText("b", OperationSource.LATIN))
                instanceof EditorTransactionResult.Applied);
    }

    @Test
    public void beginBatchReentrantRestartEndsOriginalAndNeverMutates() {
        Harness harness = Harness.normal("safe", 4, EditorInfo.IME_ACTION_NONE);
        harness.onBegin = () -> harness.manager.onStartInput(harness.info, harness.connection);
        EditorTransactionResult result = harness.apply(
                new EditorOperation.InsertText("x", OperationSource.LATIN));
        assertTarget(TargetChangeReason.EPOCH_CHANGED, result);
        assertEquals(0, harness.contentMutators());
        assertEquals(1, harness.endCalls);

        Harness closed = Harness.normal("safe", 4, EditorInfo.IME_ACTION_NONE);
        closed.onBegin = closed.manager::close;
        EditorTransactionResult closedResult = closed.apply(
                new EditorOperation.InsertText("x", OperationSource.LATIN));
        assertTarget(TargetChangeReason.NO_ACTIVE_SESSION, closedResult);
        assertEquals(0, closed.contentMutators());
        assertEquals(1, closed.endCalls);
    }

    @Test
    public void mutatorFalseOrThrowIsFailClosedUnlessIntendedStateIsExactlyObserved() {
        for (Mode mode : List.of(Mode.MUTATOR_FALSE, Mode.MUTATOR_THROW)) {
            Harness unchanged = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
            unchanged.mode = mode;
            EditorTransactionResult result = unchanged.apply(
                    new EditorOperation.InsertText("x", OperationSource.LATIN));
            assertTrue(result instanceof EditorTransactionResult.RollbackFailed);
            EditorTransactionResult.RollbackFailed failed =
                    (EditorTransactionResult.RollbackFailed) result;
            assertEquals(mode == Mode.MUTATOR_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : TransactionFailureKind.EDITOR_REJECTED,
                    failed.originalFailure().kind());
            assertEquals(TransactionFailureKind.OUTCOME_UNCONFIRMED,
                    failed.rollbackFailure().kind());
        }

        Harness appliedDespiteFalse = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        appliedDespiteFalse.mode = Mode.MUTATE_THEN_FALSE;
        assertTrue(appliedDespiteFalse.apply(
                new EditorOperation.InsertText("x", OperationSource.LATIN))
                instanceof EditorTransactionResult.Applied);
    }

    @Test
    public void actionsAndSensitiveFailuresRemainOutcomeUnconfirmedWithoutTextRead() {
        Harness action = Harness.normal("a", 1, EditorInfo.IME_ACTION_SEND);
        action.mode = Mode.MUTATOR_FALSE;
        assertTrue(action.apply(new EditorOperation.PerformEditorAction(
                EditorAction.SEND, OperationSource.LATIN))
                instanceof EditorTransactionResult.RollbackFailed);

        Harness sensitive = Harness.sensitive("secret", 6);
        sensitive.mode = Mode.MUTATOR_THROW;
        EditorTransactionResult sensitiveResult = sensitive.apply(
                new EditorOperation.InsertText("x", OperationSource.LATIN));
        assertTrue(sensitiveResult instanceof EditorTransactionResult.RollbackFailed);
        assertEquals(0, sensitive.evidenceReads);

        Harness cloudSensitive = Harness.sensitive("secret", 6);
        assertRejected(RejectionReason.SENSITIVE_FIELD, cloudSensitive.apply(
                new EditorOperation.InsertText("x", OperationSource.VOICE)));
        assertEquals(0, cloudSensitive.beginCalls);
        assertEquals(0, cloudSensitive.evidenceReads);
    }

    @Test
    public void offOwnerAndReentrantApplyFailFastBeforeExtraWrites() throws Exception {
        Harness harness = Harness.normal("a", 1, EditorInfo.IME_ACTION_NONE);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try {
                executor.submit(() -> harness.apply(
                                new EditorOperation.InsertText("x", OperationSource.LATIN)))
                        .get();
                fail("expected owner rejection");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0, harness.beginCalls);

        Harness reentrant = Harness.normal("a", 1, EditorInfo.IME_ACTION_NONE);
        reentrant.onBegin = () -> {
            try {
                reentrant.apply(new EditorOperation.InsertText("z", OperationSource.LATIN));
                fail("expected reentrant rejection");
            } catch (IllegalStateException expected) {
                assertFalse(expected.toString().contains("z"));
            }
        };
        assertTrue(reentrant.apply(new EditorOperation.InsertText("x", OperationSource.LATIN))
                instanceof EditorTransactionResult.Applied);
        assertEquals(1, reentrant.commitCalls);
    }

    @Test
    public void transactionShapeAndDiagnosticsDoNotExposeConnectionOrPlaintext() {
        for (Field field : EditorTransactionManager.class.getDeclaredFields()) {
            assertFalse(InputConnection.class.isAssignableFrom(field.getType()));
        }
        for (Method method : EditorTransactionManager.class.getDeclaredMethods()) {
            assertFalse(InputConnection.class.isAssignableFrom(method.getReturnType()));
        }
        Harness harness = Harness.normal("private-before", 14, EditorInfo.IME_ACTION_NONE);
        harness.mode = Mode.MUTATOR_THROW;
        EditorTransactionResult result = harness.apply(
                new EditorOperation.InsertText("private-insert", OperationSource.LATIN));
        assertFalse(result.toString().contains("private"));
        assertFalse(harness.manager.toString().contains("private"));
    }

    @Test
    public void requestedVoiceInsertReturnsItsExactRecordInTheSameStackReceipt() {
        AtomicInteger ids = new AtomicInteger();
        Harness harness = Harness.normalWithIds(
                "pre", 3, EditorInfo.IME_ACTION_NONE,
                () -> "voice-" + ids.incrementAndGet());
        EditorSessionSnapshot original = harness.snapshot;
        String raw = "raw-private";
        String inserted = "final-private";

        TransactionReceipt receipt = harness.applyWithReceipt(
                new EditorOperation.InsertText(inserted, OperationSource.VOICE),
                requestedRaw(raw));

        CommitRecord record = committed(receipt);
        assertTrue(record.commitId().endsWith("-voice-1"));
        assertFalse(record.commitId().contains(inserted));
        assertFalse(record.commitId().contains(raw));
        assertFalse(record.commitId().contains(record.insertedTextFingerprint().sha256Hex()));
        assertEquals(OperationSource.VOICE, record.source());
        assertEquals(original, record.originalSession());
        assertEquals(inserted, record.insertedText());
        assertEquals(FingerprintDomain.COMMITTED_TEXT,
                record.insertedTextFingerprint().domain());
        assertEquals(Sha256EditorTextHasher.INSTANCE.committedText(inserted),
                record.insertedTextFingerprint());
        assertEquals(raw,
                ((CommitRecord.RawTranscript.Present) record.rawTranscript()).text());
        assertSame(record, harness.resolve(record.commitId()).orElseThrow());
        assertTrue(harness.resolve("voice-wrong").isEmpty());
        assertEquals(1, ids.get());
        assertFalse(receipt.toString().contains("private"));
        assertFalse(record.toString().contains("private"));
    }

    @Test
    public void noRecordRequestReturnsAppliedWithoutAllocatingAnIdentifier() {
        AtomicInteger ids = new AtomicInteger();
        Harness harness = Harness.normalWithIds(
                "pre", 3, EditorInfo.IME_ACTION_NONE,
                () -> "unused-" + ids.incrementAndGet());

        TransactionReceipt receipt = harness.applyWithReceipt(
                new EditorOperation.InsertText("voice", OperationSource.VOICE),
                new CommitRecordRequest.None());

        assertAppliedWithoutCommit(receipt);
        assertEquals(0, ids.get());
        assertEquals(0, harness.commitRecordCount());
    }

    @Test
    public void sensitiveRequestedRecordRejectsBeforeIdentifierOrMutatorAndReadsNoText() {
        AtomicInteger ids = new AtomicInteger();
        Harness harness = new Harness(
                "secret",
                6,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                EditorInfo.IME_ACTION_NONE,
                () -> "forbidden-" + ids.incrementAndGet());

        TransactionReceipt receipt = harness.applyWithReceipt(
                new EditorOperation.InsertText("x", OperationSource.LATIN),
                requestedAbsent());

        assertReceiptRejected(RejectionReason.SENSITIVE_FIELD, receipt);
        assertEquals(0, ids.get());
        assertEquals(0, harness.beginCalls);
        assertEquals(0, harness.contentMutators());
        assertEquals(0, harness.evidenceReads);
        assertEquals(0, harness.commitRecordCount());
    }

    @Test
    public void noLearningVoiceCommitMayRetainOnlyItsShortLivedRecord() {
        AtomicInteger ids = new AtomicInteger();
        Harness harness = Harness.noLearning(
                "", 0, () -> "no-learning-" + ids.incrementAndGet());
        assertFalse(harness.snapshot.learningAllowed());

        CommitRecord record = committed(harness.applyWithReceipt(
                new EditorOperation.InsertText("voice", OperationSource.VOICE),
                requestedAbsent()));

        assertFalse(record.learningAllowed());
        assertEquals(1, harness.commitRecordCount());
        assertSame(record, harness.resolve(record.commitId()).orElseThrow());
    }

    @Test
    public void unavailableAndInvalidIdentifiersRejectWhileRepeatedOpaqueIdsStayUnique() {
        AtomicInteger throwingCalls = new AtomicInteger();
        Harness throwing = Harness.normalWithIds(
                "", 0, EditorInfo.IME_ACTION_NONE,
                () -> {
                    throwingCalls.incrementAndGet();
                    throw new IllegalStateException("PRIVATE_ID_FAILURE");
                });
        assertReceiptRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                throwing.applyWithReceipt(
                        new EditorOperation.InsertText("x", OperationSource.VOICE),
                        requestedAbsent()));
        assertEquals(1, throwingCalls.get());
        assertEquals(0, throwing.beginCalls);
        assertEquals(0, throwing.contentMutators());

        AtomicInteger invalidCalls = new AtomicInteger();
        Harness invalid = Harness.normalWithIds(
                "", 0, EditorInfo.IME_ACTION_NONE,
                () -> {
                    invalidCalls.incrementAndGet();
                    return " ";
                });
        assertReceiptRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                invalid.applyWithReceipt(
                        new EditorOperation.InsertText("x", OperationSource.VOICE),
                        requestedAbsent()));
        assertEquals(1, invalidCalls.get());
        assertEquals(0, invalid.beginCalls);
        assertEquals(0, invalid.contentMutators());

        AtomicInteger repeatedCalls = new AtomicInteger();
        Harness repeated = Harness.normalWithIds(
                "", 0, EditorInfo.IME_ACTION_NONE,
                () -> {
                    repeatedCalls.incrementAndGet();
                    return "same-id";
                });
        CommitRecord first = committed(repeated.applyWithReceipt(
                new EditorOperation.InsertText("first", OperationSource.VOICE),
                requestedAbsent()));
        repeated.recapture();
        CommitRecord second = committed(repeated.applyWithReceipt(
                new EditorOperation.InsertText("second", OperationSource.VOICE),
                requestedAbsent()));
        assertEquals(2, repeatedCalls.get());
        assertNotEquals(first.commitId(), second.commitId());
        assertTrue(first.commitId().endsWith("-same-id"));
        assertTrue(second.commitId().endsWith("-same-id"));
        assertEquals(2, repeated.contentMutators());
        assertEquals(1, repeated.commitRecordCount());
        assertTrue(repeated.resolve(first.commitId()).isEmpty());
        assertSame(second, repeated.resolve(second.commitId()).orElseThrow());
    }

    @Test
    public void everyReachableNonAppliedOutcomePublishesNoRecord() {
        Harness rejected = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        rejected.mode = Mode.BEGIN_FALSE;
        assertReceiptRejected(RejectionReason.BATCH_EDIT_REJECTED,
                rejected.applyWithReceipt(
                        new EditorOperation.InsertText("x", OperationSource.VOICE),
                        requestedAbsent()));
        assertEquals(0, rejected.commitRecordCount());

        Harness changed = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        changed.onBegin = () -> changed.manager.onStartInput(changed.info, changed.connection);
        TransactionReceipt changedReceipt = changed.applyWithReceipt(
                new EditorOperation.InsertText("x", OperationSource.VOICE),
                requestedAbsent());
        assertTrue(changedReceipt.result() instanceof EditorTransactionResult.TargetChanged);
        assertEquals(0, changed.commitRecordCount());

        Harness failed = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        failed.mode = Mode.MUTATOR_FALSE;
        TransactionReceipt failedReceipt = failed.applyWithReceipt(
                new EditorOperation.InsertText("x", OperationSource.VOICE),
                requestedAbsent());
        assertTrue(failedReceipt.result() instanceof EditorTransactionResult.RollbackFailed);
        assertEquals(0, failed.commitRecordCount());
    }

    @Test
    public void mutateThenFalseWithExactIntendedStateStillCreatesTheRecord() {
        Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        harness.mode = Mode.MUTATE_THEN_FALSE;

        TransactionReceipt receipt = harness.applyWithReceipt(
                new EditorOperation.InsertText("x", OperationSource.VOICE),
                requestedAbsent());

        CommitRecord record = committed(receipt);
        assertEquals("prex", harness.text);
        assertEquals("x", record.insertedText());
        assertSame(record, harness.resolve(record.commitId()).orElseThrow());
    }

    @Test
    public void cleanupFailureNeverOverwritesACommittedReceipt() {
        for (Mode mode : List.of(Mode.END_FALSE, Mode.END_THROW)) {
            Harness harness = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
            harness.mode = mode;
            CommitRecord record = committed(harness.applyWithReceipt(
                    new EditorOperation.InsertText("x", OperationSource.VOICE),
                    requestedAbsent()));
            assertEquals(1, harness.cleanup.size());
            assertEquals(1, harness.endCalls);
            assertSame(record, harness.resolve(record.commitId()).orElseThrow());
        }

        Harness hostileSink = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        hostileSink.mode = Mode.END_FALSE;
        hostileSink.cleanupThrows = true;
        committed(hostileSink.applyWithReceipt(
                new EditorOperation.InsertText("x", OperationSource.VOICE),
                requestedAbsent()));
    }

    @Test
    public void compositionRecordUsesFirstOriginAndLatestExactPartial() {
        Harness harness = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        EditorSessionSnapshot firstOrigin = harness.snapshot;
        assertTrue(harness.apply(set(
                        "first", CompositionOwner.VOICE, 1, OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);
        assertTrue(harness.apply(set(
                        "latest", CompositionOwner.VOICE, 2, OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);

        CommitRecord record = committed(harness.applyWithReceipt(
                new EditorOperation.CommitComposition(
                        CompositionOwner.VOICE, 2, OperationSource.VOICE),
                requestedRaw("raw")));

        assertEquals(firstOrigin, record.originalSession());
        assertEquals("latest", record.insertedText());
        assertEquals("raw",
                ((CommitRecord.RawTranscript.Present) record.rawTranscript()).text());
        assertEquals(2, harness.setCompositionCalls);
        assertEquals(1, harness.finishCompositionCalls);
    }

    @Test
    public void emptyCompositionFinalCannotBecomeAnUndoOrRawCommitRecord() {
        AtomicInteger ids = new AtomicInteger();
        Harness harness = Harness.normalWithIds(
                "", 0, EditorInfo.IME_ACTION_NONE,
                () -> "empty-" + ids.incrementAndGet());
        assertTrue(harness.apply(set(
                        "", CompositionOwner.VOICE, 1, OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);

        assertReceiptRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                harness.applyWithReceipt(
                        new EditorOperation.CommitComposition(
                                CompositionOwner.VOICE, 1, OperationSource.VOICE),
                        requestedRaw("raw")));
        assertEquals(0, ids.get());
        assertEquals(0, harness.finishCompositionCalls);
        assertEquals(0, harness.commitRecordCount());
    }

    @Test
    public void compositionMismatchAndPoisonNeverLeakARecordAndNewSessionCanRecover() {
        AtomicInteger ids = new AtomicInteger();
        Harness mismatch = Harness.normalWithIds(
                "", 0, EditorInfo.IME_ACTION_NONE,
                () -> "mismatch-" + ids.incrementAndGet());
        assertTrue(mismatch.apply(set(
                        "partial", CompositionOwner.VOICE, 1, OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);
        assertReceiptRejected(RejectionReason.COMPOSITION_REVISION_MISMATCH,
                mismatch.applyWithReceipt(
                        new EditorOperation.CommitComposition(
                                CompositionOwner.VOICE, 2, OperationSource.VOICE),
                        requestedAbsent()));
        assertEquals(0, ids.get());
        assertEquals(0, mismatch.commitRecordCount());

        Harness poisoned = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        assertTrue(poisoned.apply(set(
                        "partial", CompositionOwner.VOICE, 1, OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);
        poisoned.mode = Mode.MUTATOR_FALSE;
        TransactionReceipt failed = poisoned.applyWithReceipt(
                new EditorOperation.CommitComposition(
                        CompositionOwner.VOICE, 1, OperationSource.VOICE),
                requestedAbsent());
        assertTrue(failed.result() instanceof EditorTransactionResult.RollbackFailed);
        assertEquals(0, poisoned.commitRecordCount());
        assertReceiptRejected(RejectionReason.POLICY_DENIED,
                poisoned.applyWithReceipt(
                        new EditorOperation.CommitComposition(
                                CompositionOwner.VOICE, 1, OperationSource.VOICE),
                        requestedAbsent()));

        poisoned.mode = Mode.NORMAL;
        poisoned.restartSession();
        assertTrue(poisoned.apply(set(
                        "fresh", CompositionOwner.VOICE, 1, OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);
        CommitRecord recovered = committed(poisoned.applyWithReceipt(
                new EditorOperation.CommitComposition(
                        CompositionOwner.VOICE, 1, OperationSource.VOICE),
                requestedAbsent()));
        assertEquals("fresh", recovered.insertedText());
    }

    @Test
    public void startFinishAndCloseRevokeTheExactIdLedgerSlot() {
        Harness started = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        CommitRecord startRecord = committed(started.applyWithReceipt(
                new EditorOperation.InsertText("a", OperationSource.VOICE),
                requestedAbsent()));
        assertTrue(started.resolve(startRecord.commitId()).isPresent());
        started.restartSession();
        assertEquals(0, started.commitRecordCount());
        assertTrue(started.resolve(startRecord.commitId()).isEmpty());

        Harness finished = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        committed(finished.applyWithReceipt(
                new EditorOperation.InsertText("a", OperationSource.VOICE),
                requestedAbsent()));
        finished.manager.onFinishInput();
        assertEquals(0, finished.commitRecordCount());

        Harness closed = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        committed(closed.applyWithReceipt(
                new EditorOperation.InsertText("a", OperationSource.VOICE),
                requestedAbsent()));
        closed.manager.close();
        assertEquals(0, closed.commitRecordCount());
    }

    @Test
    public void reentrantLifecycleReturnsItsSameStackReceiptButLeavesNoLedgerSlot() {
        Harness harness = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        harness.onMutator = harness.manager::onFinishInput;

        TransactionReceipt receipt = harness.applyWithReceipt(
                new EditorOperation.InsertText("x", OperationSource.VOICE),
                requestedRaw("raw"));

        CommitRecord record = committed(receipt);
        assertEquals("x", record.insertedText());
        assertEquals(0, harness.commitRecordCount());
        assertTrue(harness.resolve(record.commitId()).isEmpty());
    }

    @Test
    public void pendingLifecycleRevocationHidesOldSlotInsideTheTransactionStack() {
        Harness harness = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        CommitRecord old = committed(harness.applyWithReceipt(
                new EditorOperation.InsertText("old", OperationSource.VOICE),
                requestedAbsent()));
        harness.recapture();
        harness.onMutator = () -> {
            harness.manager.onFinishInput();
            assertTrue(harness.resolve(old.commitId()).isEmpty());
            assertTrue(harness.consume(old.commitId()).isEmpty());
        };

        CommitRecord current = committed(harness.applyWithReceipt(
                new EditorOperation.InsertText("new", OperationSource.VOICE),
                requestedAbsent()));

        assertEquals("new", current.insertedText());
        assertEquals(0, harness.commitRecordCount());

        Harness closing = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        CommitRecord beforeClose = committed(closing.applyWithReceipt(
                new EditorOperation.InsertText("old", OperationSource.VOICE),
                requestedAbsent()));
        closing.recapture();
        closing.onMutator = () -> {
            closing.manager.close();
            assertTrue(closing.resolve(beforeClose.commitId()).isEmpty());
            assertTrue(closing.consume(beforeClose.commitId()).isEmpty());
        };

        CommitRecord duringClose = committed(closing.applyWithReceipt(
                new EditorOperation.InsertText("new", OperationSource.VOICE),
                requestedAbsent()));

        assertEquals("new", duringClose.insertedText());
        assertEquals(0, closing.commitRecordCount());
    }

    @Test
    public void reentrantLifecycleDuringEndKeepsReceiptButRevokesSlotAndCompositionBasis() {
        Harness insert = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        insert.onEnd = insert.manager::onFinishInput;
        CommitRecord record = committed(insert.applyWithReceipt(
                new EditorOperation.InsertText("x", OperationSource.VOICE),
                requestedAbsent()));
        assertEquals("x", record.insertedText());
        assertEquals(0, insert.commitRecordCount());

        Harness composition = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        composition.onEnd = composition::restartSession;
        assertTrue(composition.apply(set(
                        "old-partial", CompositionOwner.VOICE, 1, OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);
        composition.onEnd = () -> {};
        assertReceiptRejected(RejectionReason.COMPOSITION_OWNER_MISMATCH,
                composition.applyWithReceipt(
                        new EditorOperation.CommitComposition(
                                CompositionOwner.VOICE, 1, OperationSource.VOICE),
                        requestedAbsent()));
        assertEquals(0, composition.commitRecordCount());
    }

    @Test
    public void identifierIsReservedBeforeBeginAndBeforeTheOnlyMutator() {
        AtomicInteger ids = new AtomicInteger();
        Harness harness = Harness.normalWithIds(
                "", 0, EditorInfo.IME_ACTION_NONE,
                () -> "ordered-" + ids.incrementAndGet());
        harness.onBegin = () -> assertEquals(1, ids.get());
        harness.onMutator = () -> assertEquals(1, ids.get());

        CommitRecord record = committed(harness.applyWithReceipt(
                new EditorOperation.InsertText("private", OperationSource.VOICE),
                requestedAbsent()));

        assertEquals(1, ids.get());
        assertFalse(record.commitId().contains("private"));
        assertFalse(record.commitId().contains(record.insertedTextFingerprint().sha256Hex()));
    }

    @Test
    public void laterCommitReplacesTheSlotWithoutChangingTheEarlierReceipt() {
        Harness harness = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        TransactionReceipt firstReceipt = harness.applyWithReceipt(
                new EditorOperation.InsertText("a", OperationSource.VOICE),
                requestedAbsent());
        CommitRecord first = committed(firstReceipt);
        harness.recapture();

        TransactionReceipt secondReceipt = harness.applyWithReceipt(
                new EditorOperation.InsertText("b", OperationSource.VOICE),
                requestedAbsent());
        CommitRecord second = committed(secondReceipt);

        assertNotEquals(first.commitId(), second.commitId());
        assertEquals("a", committed(firstReceipt).insertedText());
        assertEquals("b", committed(secondReceipt).insertedText());
        assertTrue(harness.resolve(first.commitId()).isEmpty());
        assertSame(second, harness.resolve(second.commitId()).orElseThrow());
        assertEquals(1, harness.commitRecordCount());
    }

    @Test
    public void appliedTypingRevokesPriorRecordWhilePreMutatorRejectionRetainsIt() {
        Harness applied = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        CommitRecord old = committed(applied.applyWithReceipt(
                new EditorOperation.InsertText("voice", OperationSource.VOICE),
                requestedAbsent()));
        applied.recapture();
        assertTrue(applied.apply(new EditorOperation.InsertText("k", OperationSource.LATIN))
                instanceof EditorTransactionResult.Applied);
        assertTrue(applied.resolve(old.commitId()).isEmpty());
        assertEquals(0, applied.commitRecordCount());

        Harness rejected = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        CommitRecord retained = committed(rejected.applyWithReceipt(
                new EditorOperation.InsertText("voice", OperationSource.VOICE),
                requestedAbsent()));
        rejected.recapture();
        rejected.mode = Mode.BEGIN_FALSE;
        assertRejected(RejectionReason.BATCH_EDIT_REJECTED,
                rejected.apply(new EditorOperation.InsertText("k", OperationSource.LATIN)));
        assertSame(retained, rejected.resolve(retained.commitId()).orElseThrow());
    }

    @Test
    public void requestedKeyboardOrInvalidRawSourceRejectsBeforeIdentifierAndMutation() {
        AtomicInteger ids = new AtomicInteger();
        Harness keyboard = Harness.normalWithIds(
                "", 0, EditorInfo.IME_ACTION_NONE,
                () -> "keyboard-" + ids.incrementAndGet());
        assertReceiptRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                keyboard.applyWithReceipt(
                        new EditorOperation.InsertText("key", OperationSource.LATIN),
                        requestedAbsent()));
        assertEquals(0, ids.get());
        assertEquals(0, keyboard.beginCalls);

        AtomicInteger actionIds = new AtomicInteger();
        Harness action = Harness.normalWithIds(
                "", 0, EditorInfo.IME_ACTION_NONE,
                () -> "action-" + actionIds.incrementAndGet());
        assertReceiptRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                action.applyWithReceipt(
                        new EditorOperation.InsertText("action", OperationSource.ACTION),
                        requestedRaw("voice-only")));
        assertEquals(1, actionIds.get());
        assertEquals(0, action.beginCalls);
        assertEquals(0, action.contentMutators());
    }

    @Test
    public void receiptApplyIsOwnerThreadConfinedAndReentrantSafeWithoutPlaintextLeak() throws Exception {
        AtomicInteger offOwnerIds = new AtomicInteger();
        Harness offOwner = Harness.normalWithIds(
                "", 0, EditorInfo.IME_ACTION_NONE,
                () -> "off-owner-" + offOwnerIds.incrementAndGet());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try {
                executor.submit(() -> offOwner.applyWithReceipt(
                                new EditorOperation.InsertText(
                                        "off-owner-private", OperationSource.VOICE),
                                requestedRaw("raw-private")))
                        .get();
                fail("expected owner rejection");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
                assertFalse(expected.getCause().toString().contains("private"));
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0, offOwnerIds.get());
        assertEquals(0, offOwner.contentMutators());

        AtomicInteger reentrantIds = new AtomicInteger();
        Harness reentrant = Harness.normalWithIds(
                "", 0, EditorInfo.IME_ACTION_NONE,
                () -> "reentrant-" + reentrantIds.incrementAndGet());
        reentrant.onBegin = () -> {
            try {
                reentrant.applyWithReceipt(
                        new EditorOperation.InsertText(
                                "inner-private", OperationSource.VOICE),
                        requestedRaw("inner-raw-private"));
                fail("expected reentrant rejection");
            } catch (IllegalStateException expected) {
                assertFalse(expected.toString().contains("private"));
            }
        };
        TransactionReceipt outer = reentrant.applyWithReceipt(
                new EditorOperation.InsertText("outer-private", OperationSource.VOICE),
                requestedRaw("outer-raw-private"));
        committed(outer);
        assertEquals(1, reentrantIds.get());
        assertEquals(1, reentrant.contentMutators());
        assertFalse(outer.toString().contains("private"));
    }

    @Test
    public void exactIdUndoDeletesTheExactCommitOnceAndConsumesItsCapability() {
        Harness harness = Harness.normal("prepost", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord record = committed(harness.applyWithReceipt(
                new EditorOperation.InsertText("A\uD83D\uDE00B", OperationSource.VOICE),
                requestedAbsent()));
        harness.recapture();

        int beginsBeforeForged = harness.beginCalls;
        int evidenceBeforeForged = harness.undoEvidenceReads;
        CommitRecord forgedRecord = CommitRecord.create(
                "forged-public-record",
                OperationSource.VOICE,
                record.originalSession(),
                "forged-private-text",
                new CommitRecord.RawTranscript.Absent());
        TransactionReceipt forgedReceipt = new TransactionReceipt.Committed(
                new EditorTransactionResult.Applied(), forgedRecord);
        assertRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                harness.undo(((TransactionReceipt.Committed) forgedReceipt)
                        .record().commitId()));
        assertEquals(beginsBeforeForged, harness.beginCalls);
        assertEquals(evidenceBeforeForged, harness.undoEvidenceReads);
        assertEquals(0, harness.deleteCalls);
        assertSame(record, harness.resolve(record.commitId()).orElseThrow());

        EditorTransactionResult applied = harness.undo(record.commitId());
        assertTrue(applied instanceof EditorTransactionResult.Applied);
        assertEquals("prepost", harness.text);
        assertEquals(3, harness.cursor);
        assertEquals(3, harness.lastDeleteBeforeCodePoints);
        assertEquals(1, harness.deleteCalls);
        assertEquals(0, harness.commitRecordCount());
        assertTrue(harness.resolve(record.commitId()).isEmpty());

        assertRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                harness.undo(record.commitId()));
        assertEquals(1, harness.deleteCalls);
    }

    @Test
    public void replacedIdAndOrdinaryReplaceOperationNeverAuthorizeUndo() {
        Harness harness = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        CommitRecord first = committed(harness.applyWithReceipt(
                new EditorOperation.InsertText("a", OperationSource.VOICE),
                requestedAbsent()));
        harness.recapture();
        CommitRecord second = committed(harness.applyWithReceipt(
                new EditorOperation.InsertText("b", OperationSource.VOICE),
                requestedAbsent()));
        harness.recapture();

        int begins = harness.beginCalls;
        assertRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                harness.undo(first.commitId()));
        assertEquals(begins, harness.beginCalls);
        assertEquals(0, harness.deleteCalls);

        EditorOperation callerConstructed = new EditorOperation.ReplaceLastCommit(
                second.commitId(),
                second.insertedTextFingerprint(),
                "caller-controlled",
                OperationSource.UNDO);
        assertRejected(RejectionReason.OPERATION_NOT_SUPPORTED,
                harness.apply(callerConstructed));
        assertEquals(begins, harness.beginCalls);
        assertEquals(0, harness.deleteCalls);
        assertSame(second, harness.resolve(second.commitId()).orElseThrow());

        assertTrue(harness.undo(second.commitId()) instanceof EditorTransactionResult.Applied);
        assertEquals("a", harness.text);
        assertEquals(1, harness.deleteCalls);
    }

    @Test
    public void ordinaryUndoSourcedDeleteIsRejectedWithoutTouchingTheExactRecord() {
        Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord record = committed(harness.applyWithReceipt(
                new EditorOperation.InsertText("voice", OperationSource.VOICE),
                requestedAbsent()));
        harness.recapture();
        int begins = harness.beginCalls;

        assertRejected(RejectionReason.OPERATION_NOT_SUPPORTED,
                harness.apply(new EditorOperation.DeleteBeforeCursor(5, OperationSource.UNDO)));
        assertEquals(begins, harness.beginCalls);
        assertEquals(0, harness.deleteCalls);
        assertSame(record, harness.resolve(record.commitId()).orElseThrow());

        assertTrue(harness.undo(record.commitId()) instanceof EditorTransactionResult.Applied);
        assertEquals("pre", harness.text);
        assertEquals(1, harness.deleteCalls);
    }

    @Test
    public void overflowingLedgerRecordFailsBeforeEvidenceOrMutation()
            throws Exception {
        Harness harness = Harness.normal("voice", 5, EditorInfo.IME_ACTION_NONE);
        EditorSessionSnapshot overflowingOrigin = snapshotWithSelection(
                harness.snapshot,
                new com.opentypeless.android.editor.TextRange(
                        Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1),
                "",
                "",
                "");
        CommitRecord overflowing = publishExactRecord(
                harness, overflowingOrigin, "xx");

        assertRejected(RejectionReason.ROLLBACK_PRECONDITION_UNAVAILABLE,
                harness.undo(overflowing.commitId()));
        assertEquals(0, harness.undoEvidenceReads);
        assertEquals(0, harness.beginCalls);
        assertEquals(0, harness.deleteCalls);
    }

    @Test
    public void sameCoordinateCommittedPrefixAndAfterChangesAllFailClosed() {
        Harness committedChanged = Harness.normal("prepost", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord committedRecord = committed(committedChanged.applyWithReceipt(
                new EditorOperation.InsertText("voice", OperationSource.VOICE),
                requestedAbsent()));
        committedChanged.recapture();
        committedChanged.text = "prevoXcepost";
        assertTarget(TargetChangeReason.SURROUNDING_TEXT_CHANGED,
                committedChanged.undo(committedRecord.commitId()));
        assertEquals(0, committedChanged.deleteCalls);
        assertEquals(0, committedChanged.commitRecordCount());

        Harness prefixChanged = Harness.normal("prepost", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord prefixRecord = committed(prefixChanged.applyWithReceipt(
                new EditorOperation.InsertText("voice", OperationSource.VOICE),
                requestedAbsent()));
        prefixChanged.recapture();
        prefixChanged.text = "prEvoicepost";
        assertTarget(TargetChangeReason.SURROUNDING_TEXT_CHANGED,
                prefixChanged.undo(prefixRecord.commitId()));
        assertEquals(0, prefixChanged.deleteCalls);
        assertEquals(0, prefixChanged.commitRecordCount());

        Harness afterChanged = Harness.normal("prepost", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord afterRecord = committed(afterChanged.applyWithReceipt(
                new EditorOperation.InsertText("voice", OperationSource.VOICE),
                requestedAbsent()));
        afterChanged.recapture();
        afterChanged.text = "prevoicepoSt";
        assertTarget(TargetChangeReason.SURROUNDING_TEXT_CHANGED,
                afterChanged.undo(afterRecord.commitId()));
        assertEquals(0, afterChanged.deleteCalls);
        assertEquals(0, afterChanged.commitRecordCount());
    }

    @Test
    public void continuedInputAndSecondValidationAbaNeverDelete() {
        Harness continued = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord continuedRecord = committed(continued.applyWithReceipt(
                new EditorOperation.InsertText("voice", OperationSource.VOICE),
                requestedAbsent()));
        continued.recapture();
        continued.text = continued.text.substring(0, continued.cursor)
                + "x" + continued.text.substring(continued.cursor);
        continued.cursor++;
        continued.manager.onSelectionChanged(continued.cursor, continued.cursor);
        continued.recapture();
        assertTarget(TargetChangeReason.SELECTION_CHANGED,
                continued.undo(continuedRecord.commitId()));
        assertEquals(0, continued.undoEvidenceReads);
        assertEquals(0, continued.deleteCalls);
        assertEquals(0, continued.commitRecordCount());

        Harness aba = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord abaRecord = committed(aba.applyWithReceipt(
                new EditorOperation.InsertText("voice", OperationSource.VOICE),
                requestedAbsent()));
        aba.recapture();
        int beginsBefore = aba.beginCalls;
        int endsBefore = aba.endCalls;
        aba.onUndoEvidence = () -> {
            if (aba.undoEvidenceReads == 2) {
                aba.manager.onSelectionChanged(aba.cursor - 1, aba.cursor - 1);
                aba.manager.onSelectionChanged(aba.cursor, aba.cursor);
            }
        };

        assertTarget(TargetChangeReason.SELECTION_CHANGED, aba.undo(abaRecord.commitId()));
        assertEquals(beginsBefore + 1, aba.beginCalls);
        assertEquals(endsBefore + 1, aba.endCalls);
        assertEquals(0, aba.deleteCalls);
        assertEquals(0, aba.commitRecordCount());
    }

    @Test
    public void fullCommittedSpanBeyondOrdinaryWindowIsProvenAndShortSpanIsRejected() {
        String inserted = "x".repeat(1_200);
        Harness complete = Harness.normal("p", 1, EditorInfo.IME_ACTION_NONE);
        CommitRecord completeRecord = committed(complete.applyWithReceipt(
                new EditorOperation.InsertText(inserted, OperationSource.VOICE),
                requestedAbsent()));
        complete.recapture();

        assertTrue(complete.undo(completeRecord.commitId())
                instanceof EditorTransactionResult.Applied);
        assertEquals("p", complete.text);
        assertEquals(inserted.length() + 800,
                complete.lastUndoEvidenceRequest.beforeUtf16Units());
        assertEquals(800, complete.lastUndoEvidenceRequest.afterUtf16Units());
        assertEquals(1, complete.deleteCalls);

        Harness shortRead = Harness.normal("p", 1, EditorInfo.IME_ACTION_NONE);
        CommitRecord shortRecord = committed(shortRead.applyWithReceipt(
                new EditorOperation.InsertText(inserted, OperationSource.VOICE),
                requestedAbsent()));
        shortRead.recapture();
        shortRead.undoBeforeLimit = inserted.length() - 1;

        assertTarget(TargetChangeReason.SURROUNDING_TEXT_CHANGED,
                shortRead.undo(shortRecord.commitId()));
        assertEquals(0, shortRead.deleteCalls);
        assertEquals(0, shortRead.commitRecordCount());
    }

    @Test
    public void maximumNonBmpCommitUsesExactUtf16RequestAndCodePointDelete() {
        String inserted = "\uD83D\uDE00".repeat(EditorOperation.MAX_DELETE_CODE_POINTS);
        Harness complete = Harness.normal("p", 1, EditorInfo.IME_ACTION_NONE);
        CommitRecord record = committed(complete.applyWithReceipt(
                new EditorOperation.InsertText(inserted, OperationSource.VOICE),
                requestedAbsent()));
        complete.recapture();

        assertTrue(complete.undo(record.commitId()) instanceof EditorTransactionResult.Applied);
        assertEquals("p", complete.text);
        assertEquals(80_800, complete.lastUndoEvidenceRequest.beforeUtf16Units());
        assertEquals(800, complete.lastUndoEvidenceRequest.afterUtf16Units());
        assertEquals(EditorOperation.MAX_DELETE_CODE_POINTS,
                complete.lastDeleteBeforeCodePoints);
        assertEquals(1, complete.deleteCalls);
        assertEquals(0, complete.commitRecordCount());

        Harness changed = Harness.normal("p", 1, EditorInfo.IME_ACTION_NONE);
        CommitRecord changedRecord = committed(changed.applyWithReceipt(
                new EditorOperation.InsertText(inserted, OperationSource.VOICE),
                requestedAbsent()));
        changed.recapture();
        int changedOffset = 1 + inserted.length() / 2;
        changed.text = changed.text.substring(0, changedOffset)
                + "\uD83D\uDE01"
                + changed.text.substring(changedOffset + 2);

        assertTarget(TargetChangeReason.SURROUNDING_TEXT_CHANGED,
                changed.undo(changedRecord.commitId()));
        assertEquals(0, changed.deleteCalls);
        assertEquals(0, changed.commitRecordCount());
    }

    @Test
    public void hostileUndoEvidenceCannotLieAboutMaterializedLengthOrLeakContent() {
        String privateText = "private-voice";
        Harness harness = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        CommitRecord record = committed(harness.applyWithReceipt(
                new EditorOperation.InsertText(privateText, OperationSource.VOICE),
                requestedAbsent()));
        harness.recapture();
        harness.undoEvidenceOverride = request -> new EditorSessionManager.UndoEvidence(
                true,
                harness.cursor,
                harness.cursor,
                true,
                "",
                true,
                lyingLengthSequence("x".repeat(request.beforeUtf16Units() + 1)),
                true,
                "");

        int begins = harness.beginCalls;
        EditorTransactionResult result = harness.undo(record.commitId());
        assertTarget(TargetChangeReason.EVIDENCE_UNAVAILABLE, result);
        assertEquals(1, harness.undoEvidenceReads);
        assertEquals(begins, harness.beginCalls);
        assertEquals(0, harness.deleteCalls);
        assertEquals(0, harness.commitRecordCount());
        assertFalse(result.toString().contains("private"));
        assertFalse(harness.manager.toString().contains("private"));
    }

    private static CharSequence lyingLengthSequence(String materialized) {
        return new CharSequence() {
            @Override
            public int length() {
                return 0;
            }

            @Override
            public char charAt(int index) {
                throw new IndexOutOfBoundsException();
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                throw new IndexOutOfBoundsException();
            }

            @Override
            public String toString() {
                return materialized;
            }
        };
    }

    @Test
    public void undoBatchAndDeleteFalseThrowOutcomesHaveSingleAttemptSemantics() {
        for (Mode mode : List.of(Mode.BEGIN_FALSE, Mode.BEGIN_THROW)) {
            Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
            CommitRecord record = committed(harness.applyWithReceipt(
                    new EditorOperation.InsertText("voice", OperationSource.VOICE),
                    requestedAbsent()));
            harness.recapture();
            int begins = harness.beginCalls;
            int ends = harness.endCalls;
            harness.mode = mode;

            assertRejected(RejectionReason.BATCH_EDIT_REJECTED,
                    harness.undo(record.commitId()));
            assertEquals(begins + 1, harness.beginCalls);
            assertEquals(ends, harness.endCalls);
            assertEquals(0, harness.deleteCalls);
            assertSame(record, harness.resolve(record.commitId()).orElseThrow());

            harness.mode = Mode.NORMAL;
            assertTrue(harness.undo(record.commitId()) instanceof EditorTransactionResult.Applied);
            assertEquals(1, harness.deleteCalls);
            assertEquals(0, harness.commitRecordCount());
        }

        for (Mode mode : List.of(Mode.MUTATOR_FALSE, Mode.MUTATOR_THROW)) {
            Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
            CommitRecord record = committed(harness.applyWithReceipt(
                    new EditorOperation.InsertText("voice", OperationSource.VOICE),
                    requestedAbsent()));
            harness.recapture();
            int ends = harness.endCalls;
            harness.mode = mode;

            EditorTransactionResult result = harness.undo(record.commitId());
            assertTrue(result instanceof EditorTransactionResult.RollbackFailed);
            EditorTransactionResult.RollbackFailed failed =
                    (EditorTransactionResult.RollbackFailed) result;
            assertEquals(TransactionFailureStep.DELETE_TEXT,
                    failed.originalFailure().step());
            assertEquals(mode == Mode.MUTATOR_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : TransactionFailureKind.EDITOR_REJECTED,
                    failed.originalFailure().kind());
            assertEquals(TransactionFailureKind.OUTCOME_UNCONFIRMED,
                    failed.rollbackFailure().kind());
            assertEquals(1, harness.deleteCalls);
            assertEquals(ends + 1, harness.endCalls);
            assertEquals(0, harness.commitRecordCount());
            assertRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                    harness.undo(record.commitId()));
            assertEquals(1, harness.deleteCalls);
        }

        for (Mode mode : List.of(Mode.MUTATE_THEN_FALSE, Mode.MUTATE_THEN_THROW)) {
            Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
            CommitRecord record = committed(harness.applyWithReceipt(
                    new EditorOperation.InsertText("voice", OperationSource.VOICE),
                    requestedAbsent()));
            harness.recapture();
            harness.mode = mode;

            assertTrue(harness.undo(record.commitId()) instanceof EditorTransactionResult.Applied);
            assertEquals("pre", harness.text);
            assertEquals(1, harness.deleteCalls);
            assertEquals(0, harness.commitRecordCount());
        }

        for (Mode mode : List.of(Mode.END_FALSE, Mode.END_THROW)) {
            Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
            CommitRecord record = committed(harness.applyWithReceipt(
                    new EditorOperation.InsertText("voice", OperationSource.VOICE),
                    requestedAbsent()));
            harness.recapture();
            int cleanup = harness.cleanup.size();
            harness.mode = mode;

            assertTrue(harness.undo(record.commitId()) instanceof EditorTransactionResult.Applied);
            assertEquals("pre", harness.text);
            assertEquals(1, harness.deleteCalls);
            assertEquals(cleanup + 1, harness.cleanup.size());
            assertEquals(0, harness.commitRecordCount());
        }
    }

    @Test
    public void noLearningUndoWorksButSensitiveAndOffOwnerPathsReadAndWriteNothing()
            throws Exception {
        Harness noLearning = Harness.noLearning("", 0, sequentialIdsForTest());
        CommitRecord noLearningRecord = committed(noLearning.applyWithReceipt(
                new EditorOperation.InsertText("private-no-learning", OperationSource.VOICE),
                requestedAbsent()));
        noLearning.recapture();
        assertFalse(noLearningRecord.learningAllowed());
        assertTrue(noLearningRecord.rawTranscript() instanceof CommitRecord.RawTranscript.Absent);
        EditorTransactionResult noLearningResult = noLearning.undo(noLearningRecord.commitId());
        assertTrue(noLearningResult instanceof EditorTransactionResult.Applied);
        assertEquals("", noLearning.text);
        assertEquals(0, noLearning.commitRecordCount());
        assertFalse(noLearningRecord.toString().contains("private"));
        assertFalse(noLearningResult.toString().contains("private"));

        Harness sensitive = Harness.sensitive("private-secret", 14);
        assertRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                sensitive.undo("forged-sensitive-id"));
        assertEquals(0, sensitive.undoEvidenceReads);
        assertEquals(0, sensitive.beginCalls);
        assertEquals(0, sensitive.contentMutators());

        Harness offOwner = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        CommitRecord offOwnerRecord = committed(offOwner.applyWithReceipt(
                new EditorOperation.InsertText("off-owner-private", OperationSource.VOICE),
                requestedAbsent()));
        offOwner.recapture();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try {
                executor.submit(() -> offOwner.undo(offOwnerRecord.commitId())).get();
                fail("expected owner rejection");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
                assertFalse(expected.getCause().toString().contains("private"));
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0, offOwner.undoEvidenceReads);
        assertEquals(0, offOwner.deleteCalls);
        assertSame(offOwnerRecord, offOwner.resolve(offOwnerRecord.commitId()).orElseThrow());
        assertTrue(offOwner.undo(offOwnerRecord.commitId())
                instanceof EditorTransactionResult.Applied);
        assertEquals(1, offOwner.deleteCalls);
    }

    @Test
    public void exactIdRawRestoreReplacesEmojiCommitOnceAndConsumesItsCapability() {
        Harness harness = Harness.normal("prepost", 3, EditorInfo.IME_ACTION_NONE);
        String inserted = "final-\uD83D\uDE00";
        String raw = "raw-\uD83D\uDE03";
        CommitRecord record = committed(harness.applyWithReceipt(
                new EditorOperation.InsertText(inserted, OperationSource.VOICE),
                requestedRaw(raw)));
        harness.recapture();

        int commitCallsBeforeRestore = harness.commitCalls;
        EditorTransactionResult result = harness.restoreRaw(record.commitId());

        assertTrue(result instanceof EditorTransactionResult.Applied);
        assertEquals("pre" + raw + "post", harness.text);
        assertEquals(3 + raw.length(), harness.cursor);
        assertEquals(inserted.codePointCount(0, inserted.length()),
                harness.lastDeleteBeforeCodePoints);
        assertEquals(1, harness.deleteCalls);
        assertEquals(commitCallsBeforeRestore + 1, harness.commitCalls);
        assertEquals(4, harness.undoEvidenceReads);
        assertEquals(inserted.length() + 800,
                harness.undoEvidenceRequests.get(0).beforeUtf16Units());
        assertEquals(inserted.length() + 800,
                harness.undoEvidenceRequests.get(1).beforeUtf16Units());
        assertEquals(800, harness.undoEvidenceRequests.get(2).beforeUtf16Units());
        assertEquals(raw.length() + 800,
                harness.undoEvidenceRequests.get(3).beforeUtf16Units());
        assertEquals(0, harness.commitRecordCount());

        assertRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                harness.restoreRaw(record.commitId()));
        assertEquals(1, harness.deleteCalls);
        assertEquals(commitCallsBeforeRestore + 1, harness.commitCalls);
    }

    @Test
    public void rawRestoreStructuralRejectionsAndOrdinaryRawOperationsRetainExactRecord()
            throws Exception {
        Harness absent = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        CommitRecord absentRecord = committed(absent.applyWithReceipt(
                new EditorOperation.InsertText("final", OperationSource.VOICE),
                requestedAbsent()));
        absent.recapture();
        assertRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                absent.restoreRaw(absentRecord.commitId()));
        assertSame(absentRecord, absent.resolve(absentRecord.commitId()).orElseThrow());
        assertEquals(0, absent.undoEvidenceReads);
        assertEquals(0, absent.deleteCalls);

        Harness equal = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        CommitRecord equalRecord = committed(equal.applyWithReceipt(
                new EditorOperation.InsertText("same", OperationSource.VOICE),
                requestedRaw("same")));
        equal.recapture();
        assertRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                equal.restoreRaw(equalRecord.commitId()));
        assertSame(equalRecord, equal.resolve(equalRecord.commitId()).orElseThrow());
        assertEquals(0, equal.undoEvidenceReads);
        assertEquals(0, equal.deleteCalls);

        Harness action = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        EditorSessionSnapshot actionOrigin = action.snapshot;
        CommitRecord actionRecord = publishExactRecord(
                action,
                actionOrigin,
                "final",
                OperationSource.ACTION,
                new CommitRecord.RawTranscript.Absent());
        assertRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                action.restoreRaw(actionRecord.commitId()));
        assertSame(actionRecord, action.resolve(actionRecord.commitId()).orElseThrow());
        assertEquals(0, action.undoEvidenceReads);

        Harness bypass = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        CommitRecord bypassRecord = committed(bypass.applyWithReceipt(
                new EditorOperation.InsertText("final", OperationSource.VOICE),
                requestedRaw("raw")));
        bypass.recapture();
        int begins = bypass.beginCalls;
        assertRejected(RejectionReason.OPERATION_NOT_SUPPORTED, bypass.apply(
                new EditorOperation.ReplaceLastCommit(
                        bypassRecord.commitId(),
                        bypassRecord.insertedTextFingerprint(),
                        "raw",
                        OperationSource.RAW_RESTORE)));
        assertRejected(RejectionReason.OPERATION_NOT_SUPPORTED, bypass.apply(
                new EditorOperation.DeleteBeforeCursor(1, OperationSource.RAW_RESTORE)));
        assertRejected(RejectionReason.OPERATION_NOT_SUPPORTED, bypass.apply(
                new EditorOperation.InsertText("raw", OperationSource.RAW_RESTORE)));
        assertEquals(begins, bypass.beginCalls);
        assertSame(bypassRecord, bypass.resolve(bypassRecord.commitId()).orElseThrow());
        assertEquals(0, bypass.deleteCalls);

        assertRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                bypass.restoreRaw("foreign-id"));
        assertSame(bypassRecord, bypass.resolve(bypassRecord.commitId()).orElseThrow());
        assertEquals(0, bypass.undoEvidenceReads);
    }

    @Test
    public void rawRestoreDoubleCommittedProofAndOwnerBoundTransitionFailClosed() {
        Harness changed = Harness.normal("prepost", 3, EditorInfo.IME_ACTION_NONE);
        String inserted = "x".repeat(1_200);
        CommitRecord changedRecord = committed(changed.applyWithReceipt(
                new EditorOperation.InsertText(inserted, OperationSource.VOICE),
                requestedRaw("raw")));
        changed.recapture();
        int changedOffset = 3 + inserted.length() / 2;
        changed.text = changed.text.substring(0, changedOffset)
                + "y" + changed.text.substring(changedOffset + 1);

        assertTarget(TargetChangeReason.SURROUNDING_TEXT_CHANGED,
                changed.restoreRaw(changedRecord.commitId()));
        assertEquals(1, changed.undoEvidenceReads);
        assertEquals(0, changed.deleteCalls);
        assertEquals(0, changed.commitRecordCount());

        Harness secondProof = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord secondRecord = committed(secondProof.applyWithReceipt(
                new EditorOperation.InsertText("final", OperationSource.VOICE),
                requestedRaw("raw")));
        secondProof.recapture();
        secondProof.onUndoEvidence = () -> {
            if (secondProof.undoEvidenceReads == 2) {
                secondProof.manager.onSelectionChanged(
                        secondProof.cursor - 1, secondProof.cursor - 1);
                secondProof.manager.onSelectionChanged(secondProof.cursor, secondProof.cursor);
            }
        };
        assertTarget(TargetChangeReason.SELECTION_CHANGED,
                secondProof.restoreRaw(secondRecord.commitId()));
        assertEquals(2, secondProof.undoEvidenceReads);
        assertEquals(0, secondProof.deleteCalls);
        assertEquals(0, secondProof.commitRecordCount());

        Harness transition = Harness.normal("prepost", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord transitionRecord = committed(transition.applyWithReceipt(
                new EditorOperation.InsertText("final", OperationSource.VOICE),
                requestedRaw("raw")));
        transition.recapture();
        transition.onUndoEvidence = () -> {
            if (transition.undoEvidenceReads == 3) {
                transition.text = "prE" + transition.text.substring(transition.cursor);
            }
        };
        EditorTransactionResult transitionResult =
                transition.restoreRaw(transitionRecord.commitId());
        assertTrue(transitionResult instanceof EditorTransactionResult.RollbackFailed);
        EditorTransactionResult.RollbackFailed transitionFailed =
                (EditorTransactionResult.RollbackFailed) transitionResult;
        assertEquals(TransactionFailureStep.VERIFY_EDITOR_STATE,
                transitionFailed.rollbackFailure().step());
        assertEquals(1, transition.deleteCalls);
        assertEquals(1, transition.commitCalls);
        assertEquals(3, transition.undoEvidenceReads);
        assertEquals(0, transition.commitRecordCount());
        assertFalse(transitionResult.toString().contains("raw"));
        assertFalse(transitionResult.toString().contains("final"));

        for (boolean nonCollapsed : List.of(false, true)) {
            Harness absoluteSelection =
                    Harness.normal("prepost", 3, EditorInfo.IME_ACTION_NONE);
            CommitRecord absoluteRecord = committed(absoluteSelection.applyWithReceipt(
                    new EditorOperation.InsertText("final", OperationSource.VOICE),
                    requestedRaw("raw")));
            absoluteSelection.recapture();
            absoluteSelection.selectionCallbacks = false;
            absoluteSelection.undoSelectionOverride = read -> {
                if (read != 3) {
                    return new TextRange(
                            absoluteSelection.cursor, absoluteSelection.cursor);
                }
                return nonCollapsed
                        ? new TextRange(
                                absoluteSelection.cursor, absoluteSelection.cursor + 1)
                        : new TextRange(
                                absoluteSelection.cursor + 1, absoluteSelection.cursor + 1);
            };

            EditorTransactionResult absoluteResult =
                    absoluteSelection.restoreRaw(absoluteRecord.commitId());
            assertRollbackFailed(
                    absoluteResult,
                    TransactionFailureStep.DELETE_TEXT,
                    TransactionFailureKind.TARGET_INVALIDATED,
                    TransactionFailureKind.TARGET_INVALIDATED);
            assertEquals(1, absoluteSelection.deleteCalls);
            assertEquals(1, absoluteSelection.commitCalls);
            assertEquals(4, absoluteSelection.undoEvidenceReads);
            assertEquals(0, absoluteSelection.commitRecordCount());
        }
    }

    @Test
    public void rawRestoreProvesMaximumNonBmpRawAndRejectsMiddleTampering() {
        String raw = "\uD83D\uDE03".repeat(EditorOperation.MAX_TEXT_CODE_POINTS);
        Harness complete = Harness.normal("prepost", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord completeRecord = committed(complete.applyWithReceipt(
                new EditorOperation.InsertText("final", OperationSource.VOICE),
                requestedRaw(raw)));
        complete.recapture();

        assertTrue(complete.restoreRaw(completeRecord.commitId())
                instanceof EditorTransactionResult.Applied);
        assertEquals("pre" + raw + "post", complete.text);
        assertEquals(3 + raw.length(), complete.cursor);
        assertEquals(80_800,
                complete.undoEvidenceRequests.get(3).beforeUtf16Units());
        assertEquals(0, complete.commitRecordCount());

        Harness tampered = Harness.normal("prepost", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord tamperedRecord = committed(tampered.applyWithReceipt(
                new EditorOperation.InsertText("final", OperationSource.VOICE),
                requestedRaw(raw)));
        tampered.recapture();
        tampered.onUndoEvidence = () -> {
            if (tampered.undoEvidenceReads == 4) {
                int middle = 3 + raw.length() / 2;
                tampered.text = tampered.text.substring(0, middle)
                        + "\uD83D\uDE08"
                        + tampered.text.substring(middle + 2);
            }
        };

        assertRollbackFailed(
                tampered.restoreRaw(tamperedRecord.commitId()),
                TransactionFailureStep.INSERT_TEXT,
                TransactionFailureKind.OUTCOME_UNCONFIRMED,
                TransactionFailureStep.RESTORE_TEXT,
                TransactionFailureKind.NOT_SAFE_TO_ATTEMPT);
        assertEquals(4, tampered.undoEvidenceReads);
        assertEquals(80_800,
                tampered.undoEvidenceRequests.get(3).beforeUtf16Units());
        assertEquals(0, tampered.commitRecordCount());
    }

    @Test
    public void rawRestoreBatchAndCommitOutcomesHaveSingleAttemptLedgerSemantics() {
        for (Mode mode : List.of(Mode.BEGIN_FALSE, Mode.BEGIN_THROW)) {
            Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
            CommitRecord record = committed(harness.applyWithReceipt(
                    new EditorOperation.InsertText("final", OperationSource.VOICE),
                    requestedRaw("raw")));
            harness.recapture();
            int contentBefore = harness.contentMutators();
            harness.mode = mode;

            assertRejected(RejectionReason.BATCH_EDIT_REJECTED,
                    harness.restoreRaw(record.commitId()));
            assertEquals(contentBefore, harness.contentMutators());
            assertSame(record, harness.resolve(record.commitId()).orElseThrow());

            harness.mode = Mode.NORMAL;
            assertTrue(harness.restoreRaw(record.commitId())
                    instanceof EditorTransactionResult.Applied);
            assertEquals("preraw", harness.text);
            assertEquals(0, harness.commitRecordCount());
        }

        for (Mode mode : List.of(
                Mode.MUTATOR_TRUE_NO_MUTATION, Mode.MUTATE_WRONG_THEN_TRUE)) {
            Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
            CommitRecord record = committed(harness.applyWithReceipt(
                    new EditorOperation.InsertText("final", OperationSource.VOICE),
                    requestedRaw("raw")));
            harness.recapture();
            harness.deleteModeOverride = mode;
            harness.commitModeOverride = Mode.NORMAL;

            assertRollbackFailed(
                    harness.restoreRaw(record.commitId()),
                    TransactionFailureStep.DELETE_TEXT,
                    TransactionFailureKind.OUTCOME_UNCONFIRMED,
                    TransactionFailureKind.OUTCOME_UNCONFIRMED);
            assertEquals(mode == Mode.MUTATE_WRONG_THEN_TRUE ? "prebad" : "prefinal",
                    harness.text);
            assertEquals(1, harness.deleteCalls);
            assertEquals(1, harness.commitCalls);
            assertEquals(mode == Mode.MUTATE_WRONG_THEN_TRUE ? 3 : 4,
                    harness.undoEvidenceReads);
            assertEquals(0, harness.commitRecordCount());
        }

        Harness deleteTrueToRaw = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord deleteTrueRecord = committed(deleteTrueToRaw.applyWithReceipt(
                new EditorOperation.InsertText("final", OperationSource.VOICE),
                requestedRaw("raw")));
        deleteTrueToRaw.recapture();
        deleteTrueToRaw.deleteModeOverride = Mode.NORMAL;
        deleteTrueToRaw.deleteReplacementText = "raw";

        assertTrue(deleteTrueToRaw.restoreRaw(deleteTrueRecord.commitId())
                instanceof EditorTransactionResult.Applied);
        assertEquals("preraw", deleteTrueToRaw.text);
        assertEquals(1, deleteTrueToRaw.deleteCalls);
        assertEquals(1, deleteTrueToRaw.commitCalls);
        assertEquals(3, deleteTrueToRaw.undoEvidenceReads);
        assertEquals(0, deleteTrueToRaw.commitRecordCount());

        Harness noCallback = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord noCallbackRecord = committed(noCallback.applyWithReceipt(
                new EditorOperation.InsertText("final", OperationSource.VOICE),
                requestedRaw("raw")));
        noCallback.recapture();
        noCallback.selectionCallbacks = false;
        noCallback.deleteModeOverride = Mode.NORMAL;
        noCallback.commitModeOverride = Mode.MUTATOR_TRUE_NO_MUTATION;

        assertRollbackFailed(
                noCallback.restoreRaw(noCallbackRecord.commitId()),
                TransactionFailureStep.INSERT_TEXT,
                TransactionFailureKind.OUTCOME_UNCONFIRMED,
                TransactionFailureKind.OUTCOME_UNCONFIRMED);
        assertEquals("pre", noCallback.text);
        assertEquals(1, noCallback.deleteCalls);
        assertEquals(3, noCallback.commitCalls);
        assertEquals(6, noCallback.undoEvidenceReads);
        assertEquals(0, noCallback.commitRecordCount());

        for (Mode mode : List.of(Mode.MUTATOR_FALSE, Mode.MUTATOR_THROW)) {
            Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
            CommitRecord record = committed(harness.applyWithReceipt(
                    new EditorOperation.InsertText("final", OperationSource.VOICE),
                    requestedRaw("raw")));
            harness.recapture();
            harness.deleteModeOverride = mode;
            harness.commitModeOverride = Mode.NORMAL;

            EditorTransactionResult failed = harness.restoreRaw(record.commitId());
            assertRollbackFailed(
                    failed,
                    TransactionFailureStep.DELETE_TEXT,
                    mode == Mode.MUTATOR_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : TransactionFailureKind.EDITOR_REJECTED,
                    TransactionFailureKind.OUTCOME_UNCONFIRMED);
            assertEquals("prefinal", harness.text);
            assertEquals(1, harness.deleteCalls);
            assertEquals(1, harness.commitCalls);
            assertEquals(4, harness.undoEvidenceReads);
            assertEquals(0, harness.commitRecordCount());
        }

        for (Mode mode : List.of(Mode.MUTATE_THEN_FALSE, Mode.MUTATE_THEN_THROW)) {
            Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
            CommitRecord record = committed(harness.applyWithReceipt(
                    new EditorOperation.InsertText("final", OperationSource.VOICE),
                    requestedRaw("raw")));
            harness.recapture();
            harness.deleteModeOverride = mode;
            harness.commitModeOverride = Mode.NORMAL;

            assertRollbackFailed(
                    harness.restoreRaw(record.commitId()),
                    TransactionFailureStep.DELETE_TEXT,
                    mode == Mode.MUTATE_THEN_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : TransactionFailureKind.EDITOR_REJECTED,
                    TransactionFailureKind.OUTCOME_UNCONFIRMED);
            assertEquals("pre", harness.text);
            assertEquals(1, harness.deleteCalls);
            assertEquals(1, harness.commitCalls);
            assertEquals(3, harness.undoEvidenceReads);
            assertEquals(0, harness.commitRecordCount());
        }

        for (Mode mode : List.of(Mode.MUTATE_THEN_FALSE, Mode.MUTATE_THEN_THROW)) {
            Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
            CommitRecord record = committed(harness.applyWithReceipt(
                    new EditorOperation.InsertText("final", OperationSource.VOICE),
                    requestedRaw("raw")));
            harness.recapture();
            harness.deleteModeOverride = mode;
            harness.deleteReplacementText = "raw";
            harness.commitModeOverride = Mode.NORMAL;

            assertRollbackFailed(
                    harness.restoreRaw(record.commitId()),
                    TransactionFailureStep.DELETE_TEXT,
                    mode == Mode.MUTATE_THEN_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : TransactionFailureKind.EDITOR_REJECTED,
                    TransactionFailureKind.OUTCOME_UNCONFIRMED);
            assertEquals("preraw", harness.text);
            assertEquals(1, harness.deleteCalls);
            assertEquals(1, harness.commitCalls);
            assertEquals(3, harness.undoEvidenceReads);
            assertEquals(0, harness.commitRecordCount());
        }

        for (Mode mode : List.of(Mode.MUTATOR_FALSE, Mode.MUTATOR_THROW)) {
            Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
            CommitRecord record = committed(harness.applyWithReceipt(
                    new EditorOperation.InsertText("final", OperationSource.VOICE),
                    requestedRaw("raw")));
            harness.recapture();
            harness.deleteModeOverride = Mode.NORMAL;
            harness.commitModeOverride = mode;

            EditorTransactionResult failed = harness.restoreRaw(record.commitId());
            assertRollbackFailed(
                    failed,
                    TransactionFailureStep.INSERT_TEXT,
                    mode == Mode.MUTATOR_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : TransactionFailureKind.EDITOR_REJECTED,
                    TransactionFailureStep.RESTORE_TEXT,
                    mode == Mode.MUTATOR_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : TransactionFailureKind.EDITOR_REJECTED);
            assertEquals("pre", harness.text);
            assertEquals(1, harness.deleteCalls);
            assertEquals(3, harness.commitCalls);
            assertEquals(6, harness.undoEvidenceReads);
            assertEquals(0, harness.commitRecordCount());
            assertRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                    harness.restoreRaw(record.commitId()));
            assertEquals(1, harness.deleteCalls);
        }

        for (Mode mode : List.of(Mode.MUTATE_THEN_FALSE, Mode.MUTATE_THEN_THROW)) {
            Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
            CommitRecord record = committed(harness.applyWithReceipt(
                    new EditorOperation.InsertText("final", OperationSource.VOICE),
                    requestedRaw("raw")));
            harness.recapture();
            harness.deleteModeOverride = Mode.NORMAL;
            harness.commitModeOverride = mode;

            assertRollbackFailed(
                    harness.restoreRaw(record.commitId()),
                    TransactionFailureStep.INSERT_TEXT,
                    mode == Mode.MUTATE_THEN_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : TransactionFailureKind.EDITOR_REJECTED,
                    TransactionFailureStep.RESTORE_TEXT,
                    TransactionFailureKind.NOT_SAFE_TO_ATTEMPT);
            assertEquals("preraw", harness.text);
            assertEquals(1, harness.deleteCalls);
            assertEquals(2, harness.commitCalls);
            assertEquals(4, harness.undoEvidenceReads);
            assertEquals(0, harness.commitRecordCount());
        }

        for (Mode mode : List.of(
                Mode.MUTATOR_TRUE_NO_MUTATION, Mode.MUTATE_WRONG_THEN_TRUE)) {
            Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
            CommitRecord record = committed(harness.applyWithReceipt(
                    new EditorOperation.InsertText("final", OperationSource.VOICE),
                    requestedRaw("raw")));
            harness.recapture();
            harness.deleteModeOverride = Mode.NORMAL;
            harness.commitModeOverride = mode;

            EditorTransactionResult failed = harness.restoreRaw(record.commitId());
            assertRollbackFailed(
                    failed,
                    TransactionFailureStep.INSERT_TEXT,
                    TransactionFailureKind.OUTCOME_UNCONFIRMED,
                    mode == Mode.MUTATE_WRONG_THEN_TRUE
                            ? TransactionFailureStep.RESTORE_TEXT
                            : TransactionFailureStep.VERIFY_EDITOR_STATE,
                    mode == Mode.MUTATE_WRONG_THEN_TRUE
                            ? TransactionFailureKind.NOT_SAFE_TO_ATTEMPT
                            : TransactionFailureKind.OUTCOME_UNCONFIRMED);
            assertEquals(mode == Mode.MUTATE_WRONG_THEN_TRUE ? "prebad" : "pre",
                    harness.text);
            assertEquals(1, harness.deleteCalls);
            assertEquals(mode == Mode.MUTATE_WRONG_THEN_TRUE ? 2 : 3,
                    harness.commitCalls);
            assertEquals(mode == Mode.MUTATE_WRONG_THEN_TRUE ? 4 : 6,
                    harness.undoEvidenceReads);
            assertEquals(0, harness.commitRecordCount());
        }

        Harness lifecycle = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord lifecycleRecord = committed(lifecycle.applyWithReceipt(
                new EditorOperation.InsertText("final", OperationSource.VOICE),
                requestedRaw("raw")));
        lifecycle.recapture();
        lifecycle.deleteModeOverride = Mode.NORMAL;
        lifecycle.commitModeOverride = Mode.NORMAL;
        lifecycle.onMutator = () -> {
            if (lifecycle.commitCalls == 2) {
                lifecycle.info.initialSelStart = lifecycle.cursor;
                lifecycle.info.initialSelEnd = lifecycle.cursor;
                lifecycle.manager.onStartInput(lifecycle.info, lifecycle.connection);
            }
        };

        EditorTransactionResult lifecycleFailed =
                lifecycle.restoreRaw(lifecycleRecord.commitId());
        assertRollbackFailed(
                lifecycleFailed,
                TransactionFailureStep.INSERT_TEXT,
                TransactionFailureKind.TARGET_INVALIDATED,
                TransactionFailureStep.RESTORE_TEXT,
                TransactionFailureKind.NOT_SAFE_TO_ATTEMPT);
        assertEquals("preraw", lifecycle.text);
        assertEquals(1, lifecycle.deleteCalls);
        assertEquals(2, lifecycle.commitCalls);
        assertEquals(3, lifecycle.undoEvidenceReads);
        assertEquals(0, lifecycle.commitRecordCount());
    }

    @Test
    public void noLearningRawRestoreWorksWhileSensitiveAndOffOwnerReadAndWriteNothing()
            throws Exception {
        Harness noLearning = Harness.noLearning("", 0, sequentialIdsForTest());
        CommitRecord noLearningRecord = committed(noLearning.applyWithReceipt(
                new EditorOperation.InsertText("private-final", OperationSource.VOICE),
                requestedRaw("private-raw")));
        noLearning.recapture();
        assertFalse(noLearningRecord.learningAllowed());
        EditorTransactionResult noLearningResult =
                noLearning.restoreRaw(noLearningRecord.commitId());
        assertTrue(noLearningResult instanceof EditorTransactionResult.Applied);
        assertEquals("private-raw", noLearning.text);
        assertEquals(0, noLearning.commitRecordCount());
        assertFalse(noLearningResult.toString().contains("private"));

        Harness sensitive = Harness.sensitive("private-secret", 14);
        assertRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                sensitive.restoreRaw("forged-sensitive-id"));
        assertEquals(0, sensitive.undoEvidenceReads);
        assertEquals(0, sensitive.beginCalls);
        assertEquals(0, sensitive.contentMutators());

        Harness offOwner = Harness.normal("", 0, EditorInfo.IME_ACTION_NONE);
        CommitRecord offOwnerRecord = committed(offOwner.applyWithReceipt(
                new EditorOperation.InsertText("off-owner-final", OperationSource.VOICE),
                requestedRaw("off-owner-raw")));
        offOwner.recapture();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try {
                executor.submit(() -> offOwner.restoreRaw(offOwnerRecord.commitId())).get();
                fail("expected owner rejection");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
                assertFalse(expected.getCause().toString().contains("off-owner"));
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0, offOwner.undoEvidenceReads);
        assertEquals(0, offOwner.deleteCalls);
        assertSame(offOwnerRecord, offOwner.resolve(offOwnerRecord.commitId()).orElseThrow());
    }

    @Test
    public void replaceSelectionProducerRangeAndHashMismatchArePolicyDeniedBeforeBatch() {
        Harness rangeMismatch = Harness.selected("preOLDpost", 3, 6);
        EditorOperation.ReplaceSelection wrongRange = new EditorOperation.ReplaceSelection(
                new TextRange(4, 6),
                rangeMismatch.snapshot.selectedTextFingerprint(),
                "new",
                OperationSource.VOICE);
        assertRejected(RejectionReason.POLICY_DENIED, rangeMismatch.apply(wrongRange));
        assertEquals(0, rangeMismatch.beginCalls);
        assertEquals(0, rangeMismatch.commitCalls);

        Harness hashMismatch = Harness.selected("preOLDpost", 3, 6);
        EditorOperation.ReplaceSelection wrongHash = new EditorOperation.ReplaceSelection(
                hashMismatch.snapshot.selection(),
                Sha256EditorTextHasher.INSTANCE.selectedText("forged"),
                "new",
                OperationSource.ACTION);
        assertRejected(RejectionReason.POLICY_DENIED, hashMismatch.apply(wrongHash));
        assertEquals(0, hashMismatch.beginCalls);
        assertEquals(0, hashMismatch.commitCalls);
    }

    @Test
    public void replaceSelectionSupportsDirectionEmptyEmojiAndMaximumReplacement() {
        Harness forward = Harness.selected("preOLDpost", 3, 6);
        assertTrue(forward.apply(replace(forward, "new", OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);
        assertEquals("prenewpost", forward.text);
        assertEquals(6, forward.cursor);
        assertEquals(1, forward.beginCalls);
        assertEquals(1, forward.endCalls);
        assertEquals(1, forward.commitCalls);

        Harness reverse = Harness.selected("preOLDpost", 6, 3);
        assertTrue(reverse.apply(replace(reverse, "R", OperationSource.ACTION))
                instanceof EditorTransactionResult.Applied);
        assertEquals("preRpost", reverse.text);
        assertEquals(4, reverse.cursor);

        Harness empty = Harness.selected("preOLDpost", 3, 6);
        assertTrue(empty.apply(replace(empty, "", OperationSource.LATIN))
                instanceof EditorTransactionResult.Applied);
        assertEquals("prepost", empty.text);
        assertEquals(3, empty.cursor);

        Harness emoji = Harness.selected("A\uD83D\uDE00B", 1, 3);
        assertTrue(emoji.apply(replace(emoji, "\uD83D\uDE03", OperationSource.RIME))
                instanceof EditorTransactionResult.Applied);
        assertEquals("A\uD83D\uDE03B", emoji.text);
        assertEquals(3, emoji.cursor);

        String maximum = "\uD83D\uDE00".repeat(EditorOperation.MAX_TEXT_CODE_POINTS);
        Harness large = Harness.selected("axb", 1, 2);
        assertTrue(large.apply(replace(large, maximum, OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);
        assertEquals("a" + maximum + "b", large.text);
        assertEquals(1 + maximum.length(), large.cursor);
    }

    @Test
    public void replaceSelectionCursorOverflowFailsBeforeTheContentMutator() {
        Harness harness = Harness.selected("x", 0, 1);
        TextRange nearMaximum = new TextRange(Integer.MAX_VALUE - 1, Integer.MAX_VALUE);
        harness.manager.onSelectionChanged(nearMaximum.start(), nearMaximum.end());
        harness.snapshot = snapshotWithSelection(
                harness.snapshot, nearMaximum, "x", "", "");
        harness.currentEvidenceOverride = (read, request) ->
                new EditorSessionManager.CurrentEvidence(
                        true,
                        nearMaximum.start(),
                        nearMaximum.end(),
                        true,
                        "x",
                        true,
                        "",
                        true,
                        "");

        assertRejected(
                RejectionReason.ROLLBACK_PRECONDITION_UNAVAILABLE,
                harness.apply(replace(harness, "xx", OperationSource.VOICE)));
        assertEquals(1, harness.beginCalls);
        assertEquals(1, harness.endCalls);
        assertEquals(0, harness.commitCalls);
    }

    @Test
    public void replaceSelectionAcceptsFullSelectedLimitAndRejectsHostileEvidence() {
        String fourThousandAscii = "x".repeat(
                EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS);
        Harness ascii = Harness.selected(
                "a" + fourThousandAscii + "b", 1, 1 + fourThousandAscii.length());
        assertTrue(ascii.apply(replace(ascii, "ok", OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);
        assertEquals("aokb", ascii.text);

        String fourThousandEmoji = "\uD83D\uDE00".repeat(
                EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS);
        Harness utf16Maximum = Harness.selected(
                "a" + fourThousandEmoji + "b", 1, 1 + fourThousandEmoji.length());
        assertEquals(8_000, utf16Maximum.snapshot.selectedText().length());
        assertTrue(utf16Maximum.apply(replace(
                        utf16Maximum, "ok", OperationSource.ACTION))
                instanceof EditorTransactionResult.Applied);
        assertEquals("aokb", utf16Maximum.text);

        Harness lying = Harness.selected("preOLDpost", 3, 6);
        lying.currentEvidenceOverride = (read, request) -> evidenceWithSelected(
                lying,
                request,
                new LyingCharSequence("OLD", "x".repeat(8_001)));
        assertTarget(TargetChangeReason.EVIDENCE_UNAVAILABLE,
                lying.apply(replace(lying, "new", OperationSource.VOICE)));
        assertEquals(0, lying.beginCalls);
        assertEquals(0, lying.commitCalls);

        Harness malformed = Harness.selected("preOLDpost", 3, 6);
        malformed.currentEvidenceOverride = (read, request) -> evidenceWithSelected(
                malformed, request, "\uD800xx");
        assertTarget(TargetChangeReason.EVIDENCE_UNAVAILABLE,
                malformed.apply(replace(malformed, "new", OperationSource.VOICE)));
        assertEquals(0, malformed.beginCalls);
        assertEquals(0, malformed.commitCalls);

        Harness unavailable = Harness.selected("preOLDpost", 3, 6);
        unavailable.currentEvidenceOverride =
                (read, request) -> new EditorSessionManager.EvidenceUnavailable();
        assertTarget(TargetChangeReason.EVIDENCE_UNAVAILABLE,
                unavailable.apply(replace(unavailable, "new", OperationSource.VOICE)));
        assertEquals(0, unavailable.beginCalls);
        assertEquals(0, unavailable.commitCalls);
    }

    @Test
    public void replaceSelectionLiveSelectionTextAndAbaRacesNeverWrite() {
        Harness moved = Harness.selected("preOLDpost", 3, 6);
        EditorOperation.ReplaceSelection movedOperation =
                replace(moved, "new", OperationSource.VOICE);
        moved.setPhysicalSelection(0, 3, false);
        assertTarget(TargetChangeReason.SELECTION_CHANGED, moved.apply(movedOperation));
        assertEquals(0, moved.beginCalls);
        assertEquals(0, moved.commitCalls);

        Harness tampered = Harness.selected("preOLDpost", 3, 6);
        EditorOperation.ReplaceSelection tamperedOperation =
                replace(tampered, "new", OperationSource.VOICE);
        tampered.replaceSelectedTextForTest("BAD");
        assertTarget(TargetChangeReason.SELECTED_TEXT_CHANGED,
                tampered.apply(tamperedOperation));
        assertEquals(0, tampered.beginCalls);
        assertEquals(0, tampered.commitCalls);

        Harness beginRace = Harness.selected("preOLDpost", 3, 6);
        beginRace.onBegin = () -> beginRace.replaceSelectedTextForTest("BAD");
        assertTarget(TargetChangeReason.SELECTED_TEXT_CHANGED,
                beginRace.apply(replace(beginRace, "new", OperationSource.VOICE)));
        assertEquals(1, beginRace.beginCalls);
        assertEquals(1, beginRace.endCalls);
        assertEquals(0, beginRace.commitCalls);

        Harness aba = Harness.selected("preOLDpost", 3, 6);
        aba.currentEvidenceOverride = (read, request) -> {
            if (read == 2) {
                aba.manager.onSelectionChanged(0, 3);
                aba.manager.onSelectionChanged(3, 6);
            }
            return aba.defaultCurrentEvidence(request);
        };
        assertTarget(TargetChangeReason.SELECTION_CHANGED,
                aba.apply(replace(aba, "new", OperationSource.VOICE)));
        assertEquals(1, aba.beginCalls);
        assertEquals(1, aba.endCalls);
        assertEquals(0, aba.commitCalls);
    }

    @Test
    public void replaceSelectionSensitiveCompositionAndForbiddenSourcesFailClosed()
            throws Exception {
        for (OperationSource source : OperationSource.values()) {
            AtomicInteger ids = new AtomicInteger();
            Harness sensitive = Harness.sensitiveSelected(
                    "secret", 0, 6, () -> "forbidden-" + ids.incrementAndGet());
            TransactionReceipt receipt = sensitive.applyWithReceipt(
                    replace(sensitive, "x", source), requestedAbsent());
            assertReceiptRejected(RejectionReason.SENSITIVE_FIELD, receipt);
            assertEquals(0, sensitive.evidenceReads);
            assertEquals(0, ids.get());
            assertEquals(0, sensitive.beginCalls);
            assertEquals(0, sensitive.contentMutators());
        }

        Harness active = Harness.normal("preOLDpost", 3, EditorInfo.IME_ACTION_NONE);
        assertTrue(active.apply(set(
                        "partial", CompositionOwner.VOICE, 1, OperationSource.VOICE))
                instanceof EditorTransactionResult.Applied);
        active.setPhysicalSelection(3, 6, true);
        active.recapture();
        int activeBegins = active.beginCalls;
        assertRejected(RejectionReason.POLICY_DENIED,
                active.apply(replace(active, "new", OperationSource.VOICE)));
        assertEquals(activeBegins, active.beginCalls);
        assertEquals(0, active.commitCalls);

        Harness poisoned = Harness.normal("preOLDpost", 3, EditorInfo.IME_ACTION_NONE);
        poisoned.mode = Mode.MUTATOR_FALSE;
        assertTrue(poisoned.apply(set(
                        "partial", CompositionOwner.VOICE, 1, OperationSource.VOICE))
                instanceof EditorTransactionResult.RollbackFailed);
        poisoned.mode = Mode.NORMAL;
        poisoned.setPhysicalSelection(3, 6, true);
        poisoned.recapture();
        int poisonedBegins = poisoned.beginCalls;
        assertRejected(RejectionReason.POLICY_DENIED,
                poisoned.apply(replace(poisoned, "new", OperationSource.ACTION)));
        assertEquals(poisonedBegins, poisoned.beginCalls);
        assertEquals(0, poisoned.commitCalls);

        for (OperationSource source : List.of(
                OperationSource.UNDO, OperationSource.RAW_RESTORE)) {
            Harness forbidden = Harness.selected("preOLDpost", 3, 6);
            CommitRecord retained = publishExactRecord(
                    forbidden,
                    forbidden.snapshot,
                    "already",
                    OperationSource.VOICE,
                    new CommitRecord.RawTranscript.Present("raw"));
            assertRejected(RejectionReason.OPERATION_NOT_SUPPORTED,
                    forbidden.apply(replace(forbidden, "new", source)));
            assertSame(retained, forbidden.resolve(retained.commitId()).orElseThrow());
            assertEquals(1, forbidden.commitRecordCount());
            assertEquals(0, forbidden.beginCalls);
            assertEquals(0, forbidden.commitCalls);
        }
    }

    @Test
    public void replaceSelectionReceiptsRetainExactNonCollapsedOriginAndReplacement() {
        for (Object[] sample : new Object[][]{
                {OperationSource.VOICE, 3, 6},
                {OperationSource.ACTION, 6, 3}}) {
            OperationSource source = (OperationSource) sample[0];
            int start = (int) sample[1];
            int end = (int) sample[2];
            Harness harness = Harness.selected("preOLDpost", start, end);
            EditorSessionSnapshot origin = harness.snapshot;
            String replacement = source == OperationSource.VOICE ? "voice" : "action";

            CommitRecord record = committed(harness.applyWithReceipt(
                    replace(harness, replacement, source), requestedAbsent()));

            assertEquals(origin, record.originalSession());
            assertEquals(new TextRange(start, end), record.originalSession().selection());
            assertEquals("OLD", record.originalSession().selectedText());
            assertEquals(replacement, record.insertedText());
            assertEquals(source, record.source());
            assertSame(record, harness.resolve(record.commitId()).orElseThrow());
        }

        AtomicInteger latinIds = new AtomicInteger();
        Harness latin = Harness.selected(
                "preOLDpost", 3, 6, EditorInfo.IME_ACTION_NONE,
                () -> "latin-" + latinIds.incrementAndGet());
        TransactionReceipt latinReceipt = latin.applyWithReceipt(
                replace(latin, "local", OperationSource.LATIN), requestedAbsent());
        assertReceiptRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE, latinReceipt);
        assertEquals(0, latinIds.get());
        assertEquals(0, latin.commitRecordCount());
        assertEquals(0, latin.commitCalls);

        Harness noLearning = Harness.noLearningSelected(
                "preOLDpost", 3, 6, () -> "private-process-only");
        CommitRecord noLearningRecord = committed(noLearning.applyWithReceipt(
                replace(noLearning, "voice", OperationSource.VOICE), requestedAbsent()));
        assertFalse(noLearningRecord.learningAllowed());
        assertEquals(new TextRange(3, 6), noLearningRecord.originalSession().selection());
        assertEquals("OLD", noLearningRecord.originalSession().selectedText());
        noLearning.recapture();
        assertTrue(noLearning.undo(noLearningRecord.commitId())
                instanceof EditorTransactionResult.Applied);
        assertEquals("preOLDpost", noLearning.text);
        assertEquals(0, noLearning.commitRecordCount());
    }

    @Test
    public void selectedOriginUndoAndRawRestoreConsumeTheExactReplaceReceipt() {
        for (Object[] sample : new Object[][]{
                {OperationSource.VOICE, 3, 6},
                {OperationSource.ACTION, 6, 3}}) {
            OperationSource source = (OperationSource) sample[0];
            int start = (int) sample[1];
            int end = (int) sample[2];
            Harness undo = Harness.selected("preOLDpost", start, end);
            CommitRecord record = committed(undo.applyWithReceipt(
                    replace(undo, "final", source), requestedAbsent()));
            undo.recapture();

            EditorTransactionResult result = undo.undo(record.commitId());

            assertTrue(result instanceof EditorTransactionResult.Applied);
            assertEquals("preOLDpost", undo.text);
            assertEquals(6, undo.cursor);
            assertEquals(1, undo.deleteCalls);
            assertEquals(2, undo.commitCalls);
            assertEquals(4, undo.undoEvidenceReads);
            assertEquals(0, undo.commitRecordCount());
            assertRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                    undo.undo(record.commitId()));
        }

        Harness raw = Harness.selected("preOLDpost", 6, 3);
        CommitRecord rawRecord = committed(raw.applyWithReceipt(
                replace(raw, "final", OperationSource.VOICE), requestedRaw("rough")));
        raw.recapture();

        EditorTransactionResult rawResult = raw.restoreRaw(rawRecord.commitId());

        assertTrue(rawResult instanceof EditorTransactionResult.Applied);
        assertEquals("preroughpost", raw.text);
        assertEquals(8, raw.cursor);
        assertEquals(1, raw.deleteCalls);
        assertEquals(2, raw.commitCalls);
        assertEquals(4, raw.undoEvidenceReads);
        assertEquals(0, raw.commitRecordCount());
        assertRejected(RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                raw.restoreRaw(rawRecord.commitId()));
    }

    @Test
    public void selectedOriginUndoProvesTheFullOriginalAndFailsClosedOnWrongInsertion() {
        String selected = "\uD83D\uDE00".repeat(
                EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS);
        Harness maximum = Harness.selected(
                "pre" + selected + "post", 3, 3 + selected.length());
        CommitRecord maximumRecord = committed(maximum.applyWithReceipt(
                replace(maximum, "final", OperationSource.VOICE), requestedAbsent()));
        maximum.recapture();
        maximum.selectionCallbacks = false;

        EditorTransactionResult restored = maximum.undo(maximumRecord.commitId());

        assertTrue(restored instanceof EditorTransactionResult.Applied);
        assertEquals("pre" + selected + "post", maximum.text);
        assertEquals(3 + selected.length(), maximum.cursor);
        assertEquals(4, maximum.undoEvidenceReads);
        assertEquals(
                selected.length() + EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS,
                maximum.undoEvidenceRequests.get(3).beforeUtf16Units());
        assertEquals(0, maximum.commitRecordCount());

        Harness wrong = Harness.selected("preOLDpost", 3, 6);
        CommitRecord wrongRecord = committed(wrong.applyWithReceipt(
                replace(wrong, "final", OperationSource.VOICE), requestedAbsent()));
        wrong.recapture();
        wrong.commitModeOverride = Mode.MUTATE_WRONG_THEN_TRUE;

        EditorTransactionResult failed = wrong.undo(wrongRecord.commitId());

        assertRollbackFailed(
                failed,
                TransactionFailureStep.INSERT_TEXT,
                TransactionFailureKind.OUTCOME_UNCONFIRMED,
                TransactionFailureStep.RESTORE_TEXT,
                TransactionFailureKind.NOT_SAFE_TO_ATTEMPT);
        assertEquals("prebadpost", wrong.text);
        assertEquals(1, wrong.deleteCalls);
        assertEquals(2, wrong.commitCalls);
        assertEquals(0, wrong.commitRecordCount());
    }

    @Test
    public void selectedRecoveryNeverTrustsPeriodicEvidenceAfterAnUnacknowledgedWrite() {
        String selected = "a".repeat(100);
        String after = "a".repeat(300);
        Harness insertFalse = Harness.selected(
                "pre" + selected + after, 3, 3 + selected.length());
        CommitRecord insertRecord = committed(insertFalse.applyWithReceipt(
                replace(insertFalse, "final", OperationSource.VOICE), requestedAbsent()));
        insertFalse.recapture();
        insertFalse.selectionCallbacks = false;
        insertFalse.deleteModeOverride = Mode.NORMAL;
        insertFalse.commitModeOverride = Mode.MUTATOR_FALSE;
        insertFalse.onMutator = () -> {
            if (insertFalse.deleteCalls == 1 && insertFalse.commitCalls == 2) {
                insertFalse.setPhysicalSelection(
                        3 + selected.length(), 3 + selected.length(), false);
            }
        };

        assertRollbackFailed(
                insertFalse.undo(insertRecord.commitId()),
                TransactionFailureStep.INSERT_TEXT,
                TransactionFailureKind.EDITOR_REJECTED,
                TransactionFailureStep.RESTORE_TEXT,
                TransactionFailureKind.NOT_SAFE_TO_ATTEMPT);
        assertEquals("pre" + after, insertFalse.text);
        assertEquals(1, insertFalse.deleteCalls);
        assertEquals(2, insertFalse.commitCalls);
        assertEquals(0, insertFalse.commitRecordCount());

        Harness deleteFalse = Harness.selected(
                "pre" + selected + after, 3, 3 + selected.length());
        CommitRecord deleteRecord = committed(deleteFalse.applyWithReceipt(
                replace(deleteFalse, "final", OperationSource.VOICE), requestedRaw("rough")));
        deleteFalse.recapture();
        deleteFalse.selectionCallbacks = false;
        deleteFalse.deleteModeOverride = Mode.MUTATOR_FALSE;
        deleteFalse.onMutator = () -> deleteFalse.setPhysicalSelection(3, 3, false);

        assertRollbackFailed(
                deleteFalse.restoreRaw(deleteRecord.commitId()),
                TransactionFailureStep.DELETE_TEXT,
                TransactionFailureKind.EDITOR_REJECTED,
                TransactionFailureKind.TARGET_INVALIDATED);
        assertEquals("prefinal" + after, deleteFalse.text);
        assertEquals(1, deleteFalse.deleteCalls);
        assertEquals(1, deleteFalse.commitCalls);
        assertEquals(0, deleteFalse.commitRecordCount());
    }

    @Test
    public void failedSecondWriteRestoresCommittedStateAndRetainsTheExactRecord() {
        Harness raw = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord record = committed(raw.applyWithReceipt(
                new EditorOperation.InsertText("final", OperationSource.VOICE),
                requestedRaw("rough")));
        raw.recapture();
        raw.deleteModeOverride = Mode.NORMAL;
        raw.queueCommitModes(Mode.MUTATOR_FALSE, Mode.NORMAL);
        raw.audits.clear();

        EditorTransactionResult result = raw.restoreRaw(record.commitId());

        assertTrue(result instanceof EditorTransactionResult.RolledBack);
        EditorTransactionResult.RolledBack rolledBack =
                (EditorTransactionResult.RolledBack) result;
        assertEquals(TransactionFailureStep.INSERT_TEXT,
                rolledBack.originalFailure().step());
        assertEquals(TransactionFailureKind.EDITOR_REJECTED,
                rolledBack.originalFailure().kind());
        assertEquals("prefinal", raw.text);
        assertEquals(8, raw.cursor);
        assertEquals(1, raw.deleteCalls);
        assertEquals(3, raw.commitCalls);
        assertEquals(6, raw.undoEvidenceReads);
        assertSame(record, raw.resolve(record.commitId()).orElseThrow());
        assertOnlyAudit(
                raw,
                OperationSource.RAW_RESTORE,
                EditorOperationKind.REPLACE_LAST_COMMIT,
                result);

        raw.audits.clear();
        EditorTransactionResult retry = raw.restoreRaw(record.commitId());
        assertTrue(retry instanceof EditorTransactionResult.Applied);
        assertEquals("prerough", raw.text);
        assertEquals(0, raw.commitRecordCount());
        assertOnlyAudit(
                raw,
                OperationSource.RAW_RESTORE,
                EditorOperationKind.REPLACE_LAST_COMMIT,
                retry);

        Harness selected = Harness.selected("preOLDpost", 6, 3);
        CommitRecord selectedRecord = committed(selected.applyWithReceipt(
                replace(selected, "final", OperationSource.VOICE), requestedAbsent()));
        selected.recapture();
        selected.deleteModeOverride = Mode.NORMAL;
        selected.queueCommitModes(Mode.MUTATOR_FALSE, Mode.NORMAL);
        selected.audits.clear();

        EditorTransactionResult selectedResult = selected.undo(selectedRecord.commitId());

        assertTrue(selectedResult instanceof EditorTransactionResult.RolledBack);
        assertEquals("prefinalpost", selected.text);
        assertEquals(8, selected.cursor);
        assertSame(selectedRecord, selected.resolve(selectedRecord.commitId()).orElseThrow());
        assertOnlyAudit(
                selected,
                OperationSource.UNDO,
                EditorOperationKind.REPLACE_LAST_COMMIT,
                selectedResult);
    }

    @Test
    public void rollbackAttemptFailureHasExactStepAndNeverClaimsRestoration() {
        for (Mode restoreMode : List.of(
                Mode.MUTATOR_FALSE,
                Mode.MUTATOR_THROW,
                Mode.MUTATOR_TRUE_NO_MUTATION,
                Mode.MUTATE_WRONG_THEN_TRUE)) {
            Harness harness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
            CommitRecord record = committed(harness.applyWithReceipt(
                    new EditorOperation.InsertText("final", OperationSource.VOICE),
                    requestedRaw("rough")));
            harness.recapture();
            harness.deleteModeOverride = Mode.NORMAL;
            harness.queueCommitModes(Mode.MUTATOR_FALSE, restoreMode);
            harness.audits.clear();

            EditorTransactionResult result = harness.restoreRaw(record.commitId());

            assertTrue(result instanceof EditorTransactionResult.RollbackFailed);
            EditorTransactionResult.RollbackFailed failed =
                    (EditorTransactionResult.RollbackFailed) result;
            assertEquals(TransactionFailureStep.INSERT_TEXT,
                    failed.originalFailure().step());
            assertEquals(TransactionFailureKind.EDITOR_REJECTED,
                    failed.originalFailure().kind());
            assertEquals(
                    restoreMode == Mode.MUTATOR_FALSE || restoreMode == Mode.MUTATOR_THROW
                            ? TransactionFailureStep.RESTORE_TEXT
                            : TransactionFailureStep.VERIFY_EDITOR_STATE,
                    failed.rollbackFailure().step());
            assertEquals(
                    restoreMode == Mode.MUTATOR_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : restoreMode == Mode.MUTATOR_FALSE
                                    ? TransactionFailureKind.EDITOR_REJECTED
                                    : restoreMode == Mode.MUTATE_WRONG_THEN_TRUE
                                            ? TransactionFailureKind.TARGET_INVALIDATED
                                    : TransactionFailureKind.OUTCOME_UNCONFIRMED,
                    failed.rollbackFailure().kind());
            assertEquals(0, harness.commitRecordCount());
            assertOnlyAudit(
                    harness,
                    OperationSource.RAW_RESTORE,
                    EditorOperationKind.REPLACE_LAST_COMMIT,
                    result);
        }

        Harness unsafe = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord unsafeRecord = committed(unsafe.applyWithReceipt(
                new EditorOperation.InsertText("final", OperationSource.VOICE),
                requestedRaw("rough")));
        unsafe.recapture();
        unsafe.deleteModeOverride = Mode.NORMAL;
        unsafe.queueCommitModes(Mode.MUTATE_WRONG_THEN_TRUE, Mode.NORMAL);
        unsafe.audits.clear();

        EditorTransactionResult unsafeResult = unsafe.restoreRaw(unsafeRecord.commitId());

        assertTrue(unsafeResult instanceof EditorTransactionResult.RollbackFailed);
        EditorTransactionResult.RollbackFailed unsafeFailure =
                (EditorTransactionResult.RollbackFailed) unsafeResult;
        assertEquals(TransactionFailureStep.RESTORE_TEXT,
                unsafeFailure.rollbackFailure().step());
        assertEquals(TransactionFailureKind.NOT_SAFE_TO_ATTEMPT,
                unsafeFailure.rollbackFailure().kind());
        assertEquals(2, unsafe.commitCalls);
        assertEquals("prebad", unsafe.text);
        assertEquals(0, unsafe.commitRecordCount());
        assertOnlyAudit(
                unsafe,
                OperationSource.RAW_RESTORE,
                EditorOperationKind.REPLACE_LAST_COMMIT,
                unsafeResult);
    }

    @Test
    public void replaceSelectionFalseOrThrowRequiresFullMaximumOutcomeProof() {
        String replacement = "\uD83D\uDE00".repeat(EditorOperation.MAX_TEXT_CODE_POINTS);
        for (Mode mode : List.of(Mode.MUTATE_THEN_FALSE, Mode.MUTATE_THEN_THROW)) {
            Harness harness = Harness.selected("preOLDpost", 3, 6);
            harness.mode = mode;
            harness.selectionCallbacks = false;

            TransactionReceipt receipt = harness.applyWithReceipt(
                    replace(harness, replacement, OperationSource.VOICE), requestedAbsent());

            assertTrue(receipt instanceof TransactionReceipt.WithoutCommit);
            assertRollbackFailed(
                    receipt.result(),
                    TransactionFailureStep.INSERT_TEXT,
                    mode == Mode.MUTATE_THEN_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : TransactionFailureKind.EDITOR_REJECTED,
                    TransactionFailureKind.OUTCOME_UNCONFIRMED);
            assertEquals("pre" + replacement + "post", harness.text);
            assertEquals(0, harness.commitRecordCount());
            assertEquals(4, harness.evidenceReads);
            EditorSessionManager.CurrentEvidenceRequest proofRequest =
                    harness.currentEvidenceRequests.get(2);
            assertEquals(
                    replacement.length()
                            + EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS,
                    proofRequest.beforeUtf16Units());
            assertEquals(
                    EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS,
                    proofRequest.afterUtf16Units());
        }

        Harness delayedCallback = Harness.selected("preOLDpost", 3, 6);
        delayedCallback.mode = Mode.MUTATE_THEN_FALSE;
        delayedCallback.selectionCallbacks = false;
        delayedCallback.currentEvidenceOverride = (read, request) -> {
            if (read == 3) {
                int target = 3 + replacement.length();
                delayedCallback.manager.onSelectionChanged(target, target);
            }
            return delayedCallback.defaultCurrentEvidence(request);
        };
        TransactionReceipt delayedReceipt = delayedCallback.applyWithReceipt(
                replace(delayedCallback, replacement, OperationSource.VOICE),
                requestedAbsent());
        assertTrue(delayedReceipt instanceof TransactionReceipt.WithoutCommit);
        assertRollbackFailed(
                delayedReceipt.result(),
                TransactionFailureStep.INSERT_TEXT,
                TransactionFailureKind.EDITOR_REJECTED,
                TransactionFailureKind.OUTCOME_UNCONFIRMED);
        assertEquals("pre" + replacement + "post", delayedCallback.text);
        assertEquals(3, delayedCallback.evidenceReads);
        assertEquals(0, delayedCallback.commitRecordCount());

        String repeated = "a".repeat(100);
        Harness periodic = Harness.selected("pre" + repeated + repeated, 3, 103);
        periodic.mode = Mode.MUTATOR_FALSE;
        periodic.onMutator = () -> periodic.setPhysicalSelection(13, 13, false);
        TransactionReceipt ambiguous = periodic.applyWithReceipt(
                replace(periodic, "a".repeat(10), OperationSource.VOICE),
                requestedAbsent());
        assertTrue(ambiguous instanceof TransactionReceipt.WithoutCommit);
        assertRollbackFailed(
                ambiguous.result(),
                TransactionFailureStep.INSERT_TEXT,
                TransactionFailureKind.EDITOR_REJECTED,
                TransactionFailureKind.OUTCOME_UNCONFIRMED);
        assertEquals("pre" + repeated + repeated, periodic.text);
        assertEquals(0, periodic.commitRecordCount());

        for (Mode mode : List.of(Mode.MUTATOR_FALSE, Mode.MUTATOR_THROW)) {
            Harness unchanged = Harness.selected("preOLDpost", 3, 6);
            unchanged.mode = mode;
            EditorTransactionResult result = unchanged.apply(
                    replace(unchanged, replacement, OperationSource.VOICE));
            assertRollbackFailed(
                    result,
                    TransactionFailureStep.INSERT_TEXT,
                    mode == Mode.MUTATOR_THROW
                            ? TransactionFailureKind.RUNTIME_FAILURE
                            : TransactionFailureKind.EDITOR_REJECTED,
                    TransactionFailureKind.OUTCOME_UNCONFIRMED);
            assertEquals("preOLDpost", unchanged.text);
            assertEquals(0, unchanged.commitRecordCount());
        }

        Harness middleTamper = Harness.selected("preOLDpost", 3, 6);
        middleTamper.mode = Mode.MUTATE_THEN_FALSE;
        middleTamper.selectionCallbacks = false;
        int middle = replacement.length() / 2;
        middleTamper.commitReplacementText = replacement.substring(0, middle)
                + "\uD83D\uDE03"
                + replacement.substring(middle + 2);
        EditorTransactionResult tampered = middleTamper.apply(
                replace(middleTamper, replacement, OperationSource.VOICE));
        assertRollbackFailed(
                tampered,
                TransactionFailureStep.INSERT_TEXT,
                TransactionFailureKind.EDITOR_REJECTED,
                TransactionFailureKind.OUTCOME_UNCONFIRMED);
        assertFalse(middleTamper.text.equals("pre" + replacement + "post"));
        assertEquals(0, middleTamper.commitRecordCount());
        assertEquals(
                replacement.length()
                        + EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS,
                middleTamper.currentEvidenceRequests.get(2).beforeUtf16Units());
    }

    @Test
    public void replaceTransitionIsOneShotAndBoundToTheMintingManager() {
        Harness owner = Harness.selected("preOLDpost", 3, 6);
        EditorOperation.ReplaceSelection operation =
                replace(owner, "new", OperationSource.VOICE);
        EditorSessionManager.ReplaceTransition transition =
                owner.manager.prepareReplaceTransition(
                        owner.snapshot,
                        operation,
                        EditorSessionManager.ReplaceProofState.INTENDED);
        assertTrue(transition != null);

        owner.text = "prenewpost";
        owner.setPhysicalSelection(6, 6, false);
        owner.currentEvidenceOverride = (read, request) -> {
            owner.manager.onSelectionChanged(6, 6);
            return owner.defaultCurrentEvidence(request);
        };
        assertTrue(owner.manager.validateReplaceTransitionState(
                        transition, owner::authority, owner::evidence)
                instanceof EditorSessionManager.ReplaceValidated);
        owner.currentEvidenceOverride = null;

        EditorSessionManager.ReplaceValidationResult replay =
                owner.manager.validateReplaceTransitionState(
                        transition, owner::authority, owner::evidence);
        assertTrue(replay instanceof EditorSessionManager.ReplaceValidationInvalid);
        assertEquals(
                TargetChangeReason.EVIDENCE_UNAVAILABLE,
                ((EditorSessionManager.ReplaceValidationInvalid) replay).reason());

        Harness aba = Harness.selected("preOLDpost", 3, 6);
        EditorOperation.ReplaceSelection abaOperation =
                replace(aba, "new", OperationSource.VOICE);
        EditorSessionManager.ReplaceTransition abaToken =
                aba.manager.prepareReplaceTransition(
                        aba.snapshot,
                        abaOperation,
                        EditorSessionManager.ReplaceProofState.INTENDED);
        assertTrue(abaToken != null);
        aba.text = "prenewpost";
        aba.setPhysicalSelection(6, 6, false);
        aba.currentEvidenceOverride = (read, request) -> {
            aba.manager.onSelectionChanged(7, 7);
            aba.manager.onSelectionChanged(6, 6);
            return aba.defaultCurrentEvidence(request);
        };
        assertTrue(aba.manager.validateReplaceTransitionState(
                        abaToken, aba::authority, aba::evidence)
                instanceof EditorSessionManager.ReplaceValidationInvalid);

        Harness foreign = Harness.selected("preOLDpost", 3, 6);
        Harness minting = Harness.selected("preOLDpost", 3, 6);
        EditorOperation.ReplaceSelection mintingOperation =
                replace(minting, "new", OperationSource.VOICE);
        EditorSessionManager.ReplaceTransition minted =
                minting.manager.prepareReplaceTransition(
                        minting.snapshot,
                        mintingOperation,
                        EditorSessionManager.ReplaceProofState.INTENDED);
        assertTrue(minted != null);
        EditorSessionManager.ReplaceValidationResult crossOwner =
                foreign.manager.validateReplaceTransitionState(
                        minted, foreign::authority, foreign::evidence);
        assertTrue(crossOwner instanceof EditorSessionManager.ReplaceValidationInvalid);
        assertEquals(
                TargetChangeReason.EVIDENCE_UNAVAILABLE,
                ((EditorSessionManager.ReplaceValidationInvalid) crossOwner).reason());
        assertEquals(0, foreign.evidenceReads);
    }

    @Test
    public void auditTracksEveryClosedOperationKindAndExactTerminalResultOnce() {
        Harness insert = Harness.normal("a", 1, EditorInfo.IME_ACTION_NONE);
        EditorTransactionResult insertResult = insert.apply(
                new EditorOperation.InsertText("AUDIT_SENTINEL", OperationSource.LATIN));
        assertOnlyAudit(
                insert,
                OperationSource.LATIN,
                EditorOperationKind.INSERT_TEXT,
                insertResult);
        assertFalse(insert.audits.get(0).toString().contains("AUDIT_SENTINEL"));

        Harness delete = Harness.normal("ab", 2, EditorInfo.IME_ACTION_NONE);
        EditorTransactionResult deleteResult = delete.apply(
                new EditorOperation.DeleteBeforeCursor(1, OperationSource.RIME));
        assertOnlyAudit(
                delete,
                OperationSource.RIME,
                EditorOperationKind.DELETE_BEFORE_CURSOR,
                deleteResult);

        Harness action = Harness.normal("a", 1, EditorInfo.IME_ACTION_DONE);
        EditorTransactionResult actionResult = action.apply(
                new EditorOperation.PerformEditorAction(
                        EditorAction.DONE, OperationSource.LATIN));
        assertOnlyAudit(
                action,
                OperationSource.LATIN,
                EditorOperationKind.PERFORM_EDITOR_ACTION,
                actionResult);

        Harness set = Harness.normal("a", 1, EditorInfo.IME_ACTION_NONE);
        EditorTransactionResult setResult = set.apply(
                new EditorOperation.SetComposition(
                        "partial", CompositionOwner.VOICE, 1, OperationSource.VOICE));
        assertOnlyAudit(
                set,
                OperationSource.VOICE,
                EditorOperationKind.SET_COMPOSITION,
                setResult);

        Harness finish = Harness.normal("a", 1, EditorInfo.IME_ACTION_NONE);
        EditorTransactionResult finishResult = finish.apply(
                new EditorOperation.CommitComposition(
                        CompositionOwner.RIME, 1, OperationSource.RIME));
        assertOnlyAudit(
                finish,
                OperationSource.RIME,
                EditorOperationKind.COMMIT_COMPOSITION,
                finishResult);

        Harness replace = Harness.selected("old", 0, 3);
        EditorTransactionResult replaceResult = replace.apply(
                replace(replace, "new", OperationSource.ACTION));
        assertOnlyAudit(
                replace,
                OperationSource.ACTION,
                EditorOperationKind.REPLACE_SELECTION,
                replaceResult);

        Harness last = Harness.normal("a", 1, EditorInfo.IME_ACTION_NONE);
        EditorTransactionResult lastResult = last.apply(
                new EditorOperation.ReplaceLastCommit(
                        "caller-id",
                        Sha256EditorTextHasher.INSTANCE.committedText("a"),
                        "replacement",
                        OperationSource.UNDO));
        assertOnlyAudit(
                last,
                OperationSource.UNDO,
                EditorOperationKind.REPLACE_LAST_COMMIT,
                lastResult);
    }

    @Test
    public void receiptUndoAndRawAuditOnlyAuthorityDerivedMetadata() {
        Harness receiptHarness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        TransactionReceipt receipt = receiptHarness.applyWithReceipt(
                new EditorOperation.InsertText("FINAL_SECRET", OperationSource.VOICE),
                requestedRaw("RAW_SECRET"));
        assertEquals(1, receiptHarness.audits.size());
        EditorTransactionAudit receiptAudit = receiptHarness.audits.get(0);
        assertEquals(OperationSource.VOICE, receiptAudit.source());
        assertEquals(EditorOperationKind.INSERT_TEXT, receiptAudit.operationKind());
        assertSame(receipt.result(), receiptAudit.result());
        assertFalse(receiptAudit.toString().contains("FINAL_SECRET"));
        assertFalse(receiptAudit.toString().contains("RAW_SECRET"));
        assertFalse(receiptAudit.toString().contains(committed(receipt).commitId()));

        CommitRecord rawRecord = committed(receipt);
        receiptHarness.recapture();
        receiptHarness.audits.clear();
        EditorTransactionResult rawResult = receiptHarness.restoreRaw(rawRecord.commitId());
        assertOnlyAudit(
                receiptHarness,
                OperationSource.RAW_RESTORE,
                EditorOperationKind.REPLACE_LAST_COMMIT,
                rawResult);

        Harness undoHarness = Harness.normal("pre", 3, EditorInfo.IME_ACTION_NONE);
        CommitRecord undoRecord = committed(undoHarness.applyWithReceipt(
                new EditorOperation.InsertText("voice", OperationSource.VOICE),
                requestedAbsent()));
        undoHarness.recapture();
        undoHarness.audits.clear();
        EditorTransactionResult undoResult = undoHarness.undo(undoRecord.commitId());
        assertOnlyAudit(
                undoHarness,
                OperationSource.UNDO,
                EditorOperationKind.REPLACE_LAST_COMMIT,
                undoResult);
    }

    @Test
    public void auditIsRedactedBestEffortAndCannotReenterOrChangeTheOutcome() {
        Harness stale = Harness.normal("ab", 2, EditorInfo.IME_ACTION_NONE);
        stale.manager.onSelectionChanged(1, 1);
        EditorTransactionResult staleResult = stale.apply(
                new EditorOperation.InsertText("never-written", OperationSource.ACTION));
        assertTrue(staleResult instanceof EditorTransactionResult.TargetChanged);
        assertOnlyAudit(
                stale,
                OperationSource.ACTION,
                EditorOperationKind.INSERT_TEXT,
                staleResult);

        Harness sensitive = Harness.sensitive("PASSWORD_SENTINEL", 17);
        EditorTransactionResult sensitiveResult = sensitive.apply(
                new EditorOperation.InsertText("REMOTE_SENTINEL", OperationSource.VOICE));
        assertTrue(sensitiveResult instanceof EditorTransactionResult.Rejected);
        assertOnlyAudit(
                sensitive,
                OperationSource.VOICE,
                EditorOperationKind.INSERT_TEXT,
                sensitiveResult);
        assertFalse(sensitive.audits.get(0).toString().contains("PASSWORD_SENTINEL"));
        assertFalse(sensitive.audits.get(0).toString().contains("REMOTE_SENTINEL"));
        assertEquals(0, sensitive.evidenceReads);

        Harness hostile = Harness.normal("a", 1, EditorInfo.IME_ACTION_NONE);
        hostile.auditThrows = true;
        EditorTransactionResult hostileResult = hostile.apply(
                new EditorOperation.InsertText("x", OperationSource.LATIN));
        assertTrue(hostileResult instanceof EditorTransactionResult.Applied);
        assertEquals("ax", hostile.text);
        assertOnlyAudit(
                hostile,
                OperationSource.LATIN,
                EditorOperationKind.INSERT_TEXT,
                hostileResult);

        Harness reentrant = Harness.normal("a", 1, EditorInfo.IME_ACTION_NONE);
        AtomicInteger attempts = new AtomicInteger();
        reentrant.onAudit = () -> {
            reentrant.onAudit = () -> {};
            attempts.incrementAndGet();
            reentrant.apply(new EditorOperation.InsertText("nested", OperationSource.LATIN));
        };
        EditorTransactionResult outer = reentrant.apply(
                new EditorOperation.InsertText("outer", OperationSource.LATIN));
        assertTrue(outer instanceof EditorTransactionResult.Applied);
        assertEquals(1, attempts.get());
        assertEquals("aouter", reentrant.text);
        assertEquals(1, reentrant.commitCalls);
        assertOnlyAudit(
                reentrant,
                OperationSource.LATIN,
                EditorOperationKind.INSERT_TEXT,
                outer);
    }

    private static void assertOnlyAudit(
            Harness harness,
            OperationSource source,
            EditorOperationKind operationKind,
            EditorTransactionResult result) {
        assertEquals(1, harness.audits.size());
        EditorTransactionAudit audit = harness.audits.get(0);
        assertEquals(source, audit.source());
        assertEquals(operationKind, audit.operationKind());
        assertSame(result, audit.result());
    }

    private static EditorOperation.ReplaceSelection replace(
            Harness harness, String replacement, OperationSource source) {
        return new EditorOperation.ReplaceSelection(
                harness.snapshot.selection(),
                harness.snapshot.selectedTextFingerprint(),
                replacement,
                source);
    }

    private static EditorSessionManager.CurrentEvidence evidenceWithSelected(
            Harness harness,
            EditorSessionManager.CurrentEvidenceRequest request,
            CharSequence selected) {
        TextRange selection = harness.liveSelection();
        return new EditorSessionManager.CurrentEvidence(
                true,
                selection.start(),
                selection.end(),
                true,
                selected,
                true,
                Harness.tailUtf16(harness.before(), request.beforeUtf16Units()),
                true,
                Harness.headUtf16(harness.after(), request.afterUtf16Units()));
    }

    private static final class LyingCharSequence implements CharSequence {
        private final String reported;
        private final String materialized;

        private LyingCharSequence(String reported, String materialized) {
            this.reported = reported;
            this.materialized = materialized;
        }

        @Override
        public int length() {
            return reported.length();
        }

        @Override
        public char charAt(int index) {
            return reported.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return reported.subSequence(start, end);
        }

        @Override
        public String toString() {
            return materialized;
        }
    }

    private static CommitLedger.CommitIdSource sequentialIdsForTest() {
        AtomicInteger sequence = new AtomicInteger();
        return () -> "test-" + sequence.incrementAndGet();
    }

    private static EditorSessionSnapshot snapshotWithSelection(
            EditorSessionSnapshot basis,
            com.opentypeless.android.editor.TextRange selection,
            String selected,
            String before,
            String after) {
        return EditorSessionSnapshot.capture(
                basis.epoch(),
                basis.connectionToken(),
                basis.packageName(),
                basis.fieldId(),
                basis.fieldKind(),
                basis.inputType(),
                basis.imeOptions(),
                selection,
                selected,
                before,
                after,
                basis.learningAllowed(),
                basis.sensitive(),
                basis.capturedAtElapsedRealtimeMs());
    }

    private static CommitRecord publishExactRecord(
            Harness harness, EditorSessionSnapshot origin, String inserted) throws Exception {
        return publishExactRecord(
                harness,
                origin,
                inserted,
                OperationSource.VOICE,
                new CommitRecord.RawTranscript.Absent());
    }

    private static CommitRecord publishExactRecord(
            Harness harness,
            EditorSessionSnapshot origin,
            String inserted,
            OperationSource source,
            CommitRecord.RawTranscript rawTranscript) throws Exception {
        Field transactionsField = EditorSessionManager.class.getDeclaredField("transactions");
        transactionsField.setAccessible(true);
        EditorTransactionManager transactions =
                (EditorTransactionManager) transactionsField.get(harness.manager);
        Field ledgerField = EditorTransactionManager.class.getDeclaredField("commitLedger");
        ledgerField.setAccessible(true);
        CommitLedger ledger = (CommitLedger) ledgerField.get(transactions);
        CommitLedger.Reservation reservation = ledger.reserve(
                origin,
                source,
                inserted,
                rawTranscript);
        assertTrue(reservation != null);
        CommitRecord record = reservation.publish();
        assertTrue(record != null);
        return record;
    }

    private static CommitRecordRequest requestedAbsent() {
        return new CommitRecordRequest.Requested(new CommitRecord.RawTranscript.Absent());
    }

    private static CommitRecordRequest requestedRaw(String raw) {
        return new CommitRecordRequest.Requested(new CommitRecord.RawTranscript.Present(raw));
    }

    private static CommitRecord committed(TransactionReceipt receipt) {
        assertTrue(receipt instanceof TransactionReceipt.Committed);
        TransactionReceipt.Committed committed = (TransactionReceipt.Committed) receipt;
        assertTrue(committed.result() instanceof EditorTransactionResult.Applied);
        return committed.record();
    }

    private static void assertAppliedWithoutCommit(TransactionReceipt receipt) {
        assertTrue(receipt instanceof TransactionReceipt.WithoutCommit);
        assertTrue(receipt.result() instanceof EditorTransactionResult.Applied);
    }

    private static void assertReceiptRejected(
            RejectionReason reason, TransactionReceipt receipt) {
        assertTrue(receipt instanceof TransactionReceipt.WithoutCommit);
        assertRejected(reason, receipt.result());
    }

    private static void assertRejected(RejectionReason reason, EditorTransactionResult result) {
        assertTrue(result instanceof EditorTransactionResult.Rejected);
        assertEquals(reason, ((EditorTransactionResult.Rejected) result).reason());
    }

    private static void assertTarget(TargetChangeReason reason, EditorTransactionResult result) {
        assertTrue(result instanceof EditorTransactionResult.TargetChanged);
        assertEquals(reason, ((EditorTransactionResult.TargetChanged) result).reason());
    }

    private static void assertCompositionFailure(
            EditorTransactionResult result,
            TransactionFailureStep originalStep,
            TransactionFailureKind originalKind) {
        assertTrue(result instanceof EditorTransactionResult.RollbackFailed);
        EditorTransactionResult.RollbackFailed failed =
                (EditorTransactionResult.RollbackFailed) result;
        assertEquals(originalStep, failed.originalFailure().step());
        assertEquals(originalKind, failed.originalFailure().kind());
        assertEquals(TransactionFailureStep.VERIFY_EDITOR_STATE,
                failed.rollbackFailure().step());
        assertEquals(TransactionFailureKind.OUTCOME_UNCONFIRMED,
                failed.rollbackFailure().kind());
    }

    private static void assertRollbackFailed(
            EditorTransactionResult result,
            TransactionFailureStep originalStep,
            TransactionFailureKind originalKind,
            TransactionFailureKind rollbackKind) {
        assertRollbackFailed(
                result,
                originalStep,
                originalKind,
                TransactionFailureStep.VERIFY_EDITOR_STATE,
                rollbackKind);
    }

    private static void assertRollbackFailed(
            EditorTransactionResult result,
            TransactionFailureStep originalStep,
            TransactionFailureKind originalKind,
            TransactionFailureStep rollbackStep,
            TransactionFailureKind rollbackKind) {
        assertTrue(result instanceof EditorTransactionResult.RollbackFailed);
        EditorTransactionResult.RollbackFailed failed =
                (EditorTransactionResult.RollbackFailed) result;
        assertEquals(originalStep, failed.originalFailure().step());
        assertEquals(originalKind, failed.originalFailure().kind());
        assertEquals(rollbackStep, failed.rollbackFailure().step());
        assertEquals(rollbackKind, failed.rollbackFailure().kind());
    }

    private static EditorOperation.SetComposition set(
            String text, CompositionOwner owner, long revision, OperationSource source) {
        return new EditorOperation.SetComposition(text, owner, revision, source);
    }

    private enum Mode {
        NORMAL,
        BEGIN_FALSE,
        BEGIN_THROW,
        END_FALSE,
        END_THROW,
        MUTATOR_FALSE,
        MUTATOR_THROW,
        MUTATE_THEN_FALSE,
        MUTATE_THEN_THROW,
        MUTATOR_TRUE_NO_MUTATION,
        MUTATE_WRONG_THEN_TRUE
    }

    private static final class Harness {
        final EditorSessionManager manager;
        final EditorInfo info;
        final InputConnection connection;
        EditorSessionSnapshot snapshot;
        final List<String> trace = new ArrayList<>();
        final List<EditorTransactionManager.CleanupFailure> cleanup = new ArrayList<>();
        final List<EditorTransactionAudit> audits = new ArrayList<>();
        String text;
        int cursor;
        TextRange nonCollapsedSelection;
        int beginCalls;
        int endCalls;
        int commitCalls;
        int deleteCalls;
        int actionCalls;
        int setCompositionCalls;
        int finishCompositionCalls;
        int evidenceReads;
        int undoEvidenceReads;
        final List<EditorSessionManager.UndoEvidenceRequest> undoEvidenceRequests =
                new ArrayList<>();
        final List<EditorSessionManager.CurrentEvidenceRequest> currentEvidenceRequests =
                new ArrayList<>();
        int lastAction = Integer.MIN_VALUE;
        int lastDeleteBeforeCodePoints = Integer.MIN_VALUE;
        String lastCompositionText;
        String physicalCompositionText;
        int undoBeforeLimit = Integer.MAX_VALUE;
        boolean undoEvidenceUnavailable;
        boolean undoEvidenceThrows;
        EditorSessionManager.UndoEvidenceRequest lastUndoEvidenceRequest;
        Mode mode = Mode.NORMAL;
        Mode commitModeOverride;
        Mode deleteModeOverride;
        final Deque<Mode> commitModeSequence = new ArrayDeque<>();
        String deleteReplacementText;
        String commitReplacementText;
        boolean selectionCallbacks = true;
        java.util.function.IntFunction<TextRange> undoSelectionOverride;
        Runnable onBegin = () -> {};
        Runnable onMutator = () -> {};
        Runnable onEnd = () -> {};
        Runnable onAudit = () -> {};
        Runnable onUndoEvidence = () -> {};
        java.util.function.Function<
                EditorSessionManager.UndoEvidenceRequest,
                EditorSessionManager.UndoEvidenceReadResult> undoEvidenceOverride;
        java.util.function.BiFunction<
                Integer,
                EditorSessionManager.CurrentEvidenceRequest,
                EditorSessionManager.EvidenceReadResult> currentEvidenceOverride;
        boolean cleanupThrows;
        boolean auditThrows;

        private Harness(String text, int cursor, int inputType, int imeOptions) {
            this(text, new TextRange(cursor, cursor), inputType, imeOptions, sequentialIds());
        }

        private Harness(
                String text,
                int cursor,
                int inputType,
                int imeOptions,
                CommitLedger.CommitIdSource idSource) {
            this(text, new TextRange(cursor, cursor), inputType, imeOptions, idSource);
        }

        private Harness(
                String text,
                TextRange selection,
                int inputType,
                int imeOptions,
                CommitLedger.CommitIdSource idSource) {
            this.text = text;
            this.cursor = selection.end();
            this.nonCollapsedSelection = selection.hasSelection() ? selection : null;
            manager = new EditorSessionManager(
                    () -> {},
                    () -> 1L,
                    idSource,
                    failure -> {
                        cleanup.add(failure);
                        if (cleanupThrows) throw new IllegalStateException("HOSTILE_CLEANUP");
                    },
                    audit -> {
                        audits.add(audit);
                        onAudit.run();
                        if (auditThrows) throw new IllegalStateException("HOSTILE_AUDIT");
                    });
            info = new EditorInfo();
            info.packageName = "app";
            info.fieldId = 1;
            info.inputType = inputType;
            info.imeOptions = imeOptions;
            info.initialSelStart = selection.start();
            info.initialSelEnd = selection.end();
            connection = (InputConnection) Proxy.newProxyInstance(
                    InputConnection.class.getClassLoader(),
                    new Class<?>[]{InputConnection.class},
                    (proxy, method, arguments) -> invoke(proxy, method.getName(), arguments));
            manager.onStartInput(info, connection);
            boolean sensitive = (inputType & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
            EditorSessionManager.CaptureResult captured = manager.captureFromEvidence(
                    connection,
                    sensitive ? "" : selected(),
                    sensitive ? "" : boundedBefore(),
                    sensitive ? "" : boundedAfter());
            assertTrue(captured instanceof EditorSessionManager.Captured);
            snapshot = ((EditorSessionManager.Captured) captured).snapshot();
        }

        static Harness normal(String text, int cursor, int imeOptions) {
            return new Harness(text, cursor, InputType.TYPE_CLASS_TEXT, imeOptions);
        }

        static Harness selected(String text, int start, int end) {
            return selected(text, start, end, EditorInfo.IME_ACTION_NONE, sequentialIds());
        }

        static Harness selected(
                String text,
                int start,
                int end,
                int imeOptions,
                CommitLedger.CommitIdSource idSource) {
            return new Harness(
                    text,
                    new TextRange(start, end),
                    InputType.TYPE_CLASS_TEXT,
                    imeOptions,
                    idSource);
        }

        static Harness sensitiveSelected(
                String text,
                int start,
                int end,
                CommitLedger.CommitIdSource idSource) {
            return new Harness(
                    text,
                    new TextRange(start, end),
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    EditorInfo.IME_ACTION_NONE,
                    idSource);
        }

        static Harness noLearningSelected(
                String text,
                int start,
                int end,
                CommitLedger.CommitIdSource idSource) {
            return new Harness(
                    text,
                    new TextRange(start, end),
                    InputType.TYPE_CLASS_TEXT,
                    EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
                    idSource);
        }

        static Harness sensitive(String text, int cursor) {
            return new Harness(
                    text,
                    cursor,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    EditorInfo.IME_ACTION_NONE);
        }

        static Harness noLearning(
                String text, int cursor, CommitLedger.CommitIdSource idSource) {
            return new Harness(
                    text,
                    cursor,
                    InputType.TYPE_CLASS_TEXT,
                    EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
                    idSource);
        }

        static Harness normalWithIds(
                String text,
                int cursor,
                int imeOptions,
                CommitLedger.CommitIdSource idSource) {
            return new Harness(
                    text, cursor, InputType.TYPE_CLASS_TEXT, imeOptions, idSource);
        }

        EditorTransactionResult apply(EditorOperation operation) {
            return manager.apply(
                    snapshot,
                    operation,
                    this::authority,
                    this::evidence);
        }

        EditorTransactionResult keyboardInsert(String value) {
            return manager.insertKeyboardText(
                    snapshot, value, this::authority, this::evidence);
        }

        EditorTransactionResult keyboardDelete() {
            return manager.deleteKeyboardBackward(
                    snapshot, this::authority, this::evidence);
        }

        EditorTransactionResult keyboardEnter() {
            return manager.performKeyboardEnter(
                    snapshot, this::authority, this::evidence);
        }

        EditorSessionManager.KeyboardHost host() {
            return new EditorSessionManager.KeyboardHost() {
                @Override
                public EditorInfo currentEditorInfo() {
                    return info;
                }

                @Override
                public InputConnection currentInputConnection() {
                    return connection;
                }
            };
        }

        EditorTransactionResult voiceSet(String value, long revision) {
            return manager.setVoiceComposition(host(), snapshot, value, revision);
        }

        TransactionReceipt voiceCommit(
                long revision, CommitRecord.RawTranscript rawTranscript) {
            return manager.commitVoiceComposition(host(), snapshot, revision, rawTranscript);
        }

        TransactionReceipt voiceText(
                String value, CommitRecord.RawTranscript rawTranscript) {
            return manager.commitVoiceText(host(), snapshot, value, rawTranscript);
        }

        EditorTransactionResult voiceUndo(String commitId) {
            return manager.undoVoiceCommit(host(), snapshot, commitId);
        }

        EditorTransactionResult voiceRaw(String commitId) {
            return manager.restoreRawVoiceCommit(host(), snapshot, commitId);
        }

        TransactionReceipt applyWithReceipt(
                EditorOperation operation, CommitRecordRequest request) {
            return manager.applyWithReceipt(
                    snapshot,
                    operation,
                    request,
                    this::authority,
                    this::evidence);
        }

        EditorTransactionResult undo(String commitId) {
            return manager.undoCommit(
                    commitId,
                    snapshot,
                    this::authority,
                    this::undoEvidence);
        }

        EditorTransactionResult restoreRaw(String commitId) {
            return manager.restoreRawCommit(
                    commitId,
                    snapshot,
                    this::authority,
                    this::undoEvidence);
        }

        Optional<CommitRecord> resolve(String commitId) {
            return manager.resolveCommitRecord(commitId, snapshot);
        }

        Optional<CommitRecord> consume(String commitId) {
            return manager.consumeCommitRecord(commitId, snapshot);
        }

        int commitRecordCount() {
            return manager.commitRecordCountForTest();
        }

        private EditorSessionManager.LiveAuthority authority() {
            trace.add("authority");
            return new EditorSessionManager.LiveAuthority(info, connection);
        }

        private EditorSessionManager.EvidenceReadResult evidence(
                InputConnection authorized,
                EditorSessionManager.CurrentEvidenceRequest request) {
            trace.add("evidence");
            evidenceReads++;
            currentEvidenceRequests.add(request);
            assertTrue(authorized == connection);
            if (currentEvidenceOverride != null) {
                return currentEvidenceOverride.apply(evidenceReads, request);
            }
            return defaultCurrentEvidence(request);
        }

        private EditorSessionManager.CurrentEvidence defaultCurrentEvidence(
                EditorSessionManager.CurrentEvidenceRequest request) {
            TextRange selection = liveSelection();
            return new EditorSessionManager.CurrentEvidence(
                    true,
                    selection.start(),
                    selection.end(),
                    true,
                    selected(),
                    true,
                    tailUtf16(before(), request.beforeUtf16Units()),
                    true,
                    headUtf16(after(), request.afterUtf16Units()));
        }

        private EditorSessionManager.UndoEvidenceReadResult undoEvidence(
                InputConnection authorized,
                EditorSessionManager.UndoEvidenceRequest request) {
            trace.add("undoEvidence");
            undoEvidenceReads++;
            lastUndoEvidenceRequest = request;
            undoEvidenceRequests.add(request);
            assertTrue(authorized == connection);
            onUndoEvidence.run();
            if (undoEvidenceThrows) {
                throw new IllegalStateException("HOSTILE_UNDO_EVIDENCE");
            }
            if (undoEvidenceUnavailable) {
                return new EditorSessionManager.UndoEvidenceUnavailable();
            }
            if (undoEvidenceOverride != null) {
                return undoEvidenceOverride.apply(request);
            }
            TextRange liveSelection = undoSelectionOverride == null
                    ? liveSelection()
                    : undoSelectionOverride.apply(undoEvidenceReads);
            int selectionLeft = Math.min(liveSelection.start(), liveSelection.end());
            int selectionRight = Math.max(liveSelection.start(), liveSelection.end());
            String selected = text.substring(selectionLeft, selectionRight);
            String before = text.substring(0, selectionLeft);
            int beforeLimit = Math.min(request.beforeUtf16Units(), undoBeforeLimit);
            before = tailUtf16(before, beforeLimit);
            String after = headUtf16(
                    text.substring(selectionRight), request.afterUtf16Units());
            return new EditorSessionManager.UndoEvidence(
                    true,
                    liveSelection.start(),
                    liveSelection.end(),
                    true,
                    selected,
                    true,
                    before,
                    true,
                    after);
        }

        void restartSession() {
            TextRange selection = liveSelection();
            info.initialSelStart = selection.start();
            info.initialSelEnd = selection.end();
            manager.onStartInput(info, connection);
            boolean sensitive = (info.inputType & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
            snapshot = ((EditorSessionManager.Captured) manager.captureFromEvidence(
                    connection,
                    sensitive ? "" : selected(),
                    sensitive ? "" : boundedBefore(),
                    sensitive ? "" : boundedAfter())).snapshot();
        }

        void recapture() {
            boolean sensitive = (info.inputType & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
            snapshot = ((EditorSessionManager.Captured) manager.captureFromEvidence(
                    connection,
                    sensitive ? "" : selected(),
                    sensitive ? "" : boundedBefore(),
                    sensitive ? "" : boundedAfter())).snapshot();
        }

        void setPhysicalSelection(int start, int end, boolean notifyManager) {
            nonCollapsedSelection = start == end ? null : new TextRange(start, end);
            cursor = end;
            if (notifyManager) manager.onSelectionChanged(start, end);
        }

        void replaceSelectedTextForTest(String replacement) {
            TextRange selection = liveSelection();
            int left = Math.min(selection.start(), selection.end());
            int right = Math.max(selection.start(), selection.end());
            text = text.substring(0, left) + replacement + text.substring(right);
            int delta = replacement.length() - (right - left);
            if (selection.start() <= selection.end()) {
                nonCollapsedSelection = new TextRange(selection.start(), selection.end() + delta);
            } else {
                nonCollapsedSelection = new TextRange(selection.start() + delta, selection.end());
            }
            cursor = nonCollapsedSelection.end();
        }

        private Object invoke(Object proxy, String method, Object[] arguments) {
            return switch (method) {
                case "toString" -> "InputConnection{<redacted>}";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "beginBatchEdit" -> begin();
                case "endBatchEdit" -> end();
                case "commitText" -> commit((CharSequence) arguments[0], (int) arguments[1]);
                case "deleteSurroundingTextInCodePoints" ->
                        delete((int) arguments[0], (int) arguments[1]);
                case "performEditorAction" -> action((int) arguments[0]);
                case "setComposingText" ->
                        setComposition((CharSequence) arguments[0], (int) arguments[1]);
                case "finishComposingText" -> finishComposition();
                case "getExtractedText" -> extractedText();
                case "getSelectedText" -> selected();
                case "getTextBeforeCursor" -> tailUtf16(before(), (int) arguments[0]);
                case "getTextAfterCursor" -> headUtf16(after(), (int) arguments[0]);
                default -> throw new AssertionError("unexpected InputConnection method " + method);
            };
        }

        private ExtractedText extractedText() {
            ExtractedText extracted = new ExtractedText();
            TextRange selection = liveSelection();
            extracted.selectionStart = selection.start();
            extracted.selectionEnd = selection.end();
            extracted.text = text;
            return extracted;
        }

        private boolean begin() {
            trace.add("begin");
            beginCalls++;
            onBegin.run();
            if (mode == Mode.BEGIN_THROW) throw new IllegalStateException("HOSTILE_BEGIN");
            return mode != Mode.BEGIN_FALSE;
        }

        private boolean end() {
            trace.add("end");
            endCalls++;
            onEnd.run();
            if (mode == Mode.END_THROW) throw new IllegalStateException("HOSTILE_END");
            return mode != Mode.END_FALSE;
        }

        private boolean commit(CharSequence value, int newCursorPosition) {
            trace.add("commit:" + value + ":" + newCursorPosition);
            commitCalls++;
            Mode behavior = commitModeSequence.isEmpty()
                    ? commitModeOverride == null ? mode : commitModeOverride
                    : commitModeSequence.removeFirst();
            if (behavior == Mode.MUTATOR_THROW) {
                throw new IllegalStateException("HOSTILE_MUTATOR");
            }
            if (behavior == Mode.MUTATE_THEN_FALSE
                    || behavior == Mode.MUTATE_THEN_THROW
                    || behavior == Mode.MUTATE_WRONG_THEN_TRUE
                    || behavior == Mode.NORMAL) {
                String inserted = commitReplacementText != null
                        ? commitReplacementText
                        : behavior == Mode.MUTATE_WRONG_THEN_TRUE ? "bad" : value.toString();
                TextRange selection = liveSelection();
                int left = Math.min(selection.start(), selection.end());
                int right = Math.max(selection.start(), selection.end());
                text = text.substring(0, left) + inserted + text.substring(right);
                cursor = left + inserted.length();
                nonCollapsedSelection = null;
                if (selectionCallbacks) manager.onSelectionChanged(cursor, cursor);
            }
            onMutator.run();
            if (behavior == Mode.MUTATE_THEN_THROW) {
                throw new IllegalStateException("HOSTILE_MUTATOR");
            }
            return behavior != Mode.MUTATOR_FALSE && behavior != Mode.MUTATE_THEN_FALSE;
        }

        void queueCommitModes(Mode... modes) {
            for (Mode queued : modes) commitModeSequence.addLast(queued);
        }

        private boolean delete(int beforeCodePoints, int afterCodePoints) {
            trace.add("delete:" + beforeCodePoints + ":" + afterCodePoints);
            deleteCalls++;
            lastDeleteBeforeCodePoints = beforeCodePoints;
            Mode behavior = deleteModeOverride == null ? mode : deleteModeOverride;
            if (behavior == Mode.MUTATOR_THROW) {
                throw new IllegalStateException("HOSTILE_MUTATOR");
            }
            if (behavior == Mode.MUTATE_THEN_FALSE
                    || behavior == Mode.MUTATE_THEN_THROW
                    || behavior == Mode.MUTATE_WRONG_THEN_TRUE
                    || behavior == Mode.END_FALSE
                    || behavior == Mode.END_THROW
                    || behavior == Mode.NORMAL) {
                int start = text.offsetByCodePoints(cursor, -beforeCodePoints);
                String replacement = behavior == Mode.MUTATE_WRONG_THEN_TRUE
                        ? "bad"
                        : deleteReplacementText == null ? "" : deleteReplacementText;
                text = text.substring(0, start) + replacement + text.substring(cursor);
                cursor = start + replacement.length();
                if (selectionCallbacks) manager.onSelectionChanged(cursor, cursor);
            }
            onMutator.run();
            if (behavior == Mode.MUTATE_THEN_THROW) {
                throw new IllegalStateException("HOSTILE_MUTATOR");
            }
            return behavior != Mode.MUTATOR_FALSE && behavior != Mode.MUTATE_THEN_FALSE;
        }

        private boolean action(int id) {
            trace.add("action:" + id);
            actionCalls++;
            lastAction = id;
            if (mode == Mode.MUTATOR_THROW) throw new IllegalStateException("HOSTILE_MUTATOR");
            onMutator.run();
            return mode != Mode.MUTATOR_FALSE;
        }


        private boolean setComposition(CharSequence value, int newCursorPosition) {
            trace.add("setComposition:" + value + ":" + newCursorPosition);
            setCompositionCalls++;
            lastCompositionText = value.toString();
            if (mode == Mode.NORMAL
                    || mode == Mode.MUTATE_THEN_FALSE
                    || mode == Mode.MUTATE_THEN_THROW) {
                physicalCompositionText = lastCompositionText;
            }
            if (mode == Mode.MUTATOR_THROW || mode == Mode.MUTATE_THEN_THROW) {
                throw new IllegalStateException("HOSTILE_MUTATOR");
            }
            onMutator.run();
            return mode != Mode.MUTATOR_FALSE && mode != Mode.MUTATE_THEN_FALSE;
        }

        private boolean finishComposition() {
            trace.add("finishComposition");
            finishCompositionCalls++;
            if (mode == Mode.NORMAL
                    || mode == Mode.MUTATE_THEN_FALSE
                    || mode == Mode.MUTATE_THEN_THROW) {
                physicalCompositionText = null;
            }
            if (mode == Mode.MUTATOR_THROW || mode == Mode.MUTATE_THEN_THROW) {
                throw new IllegalStateException("HOSTILE_MUTATOR");
            }
            onMutator.run();
            return mode != Mode.MUTATOR_FALSE && mode != Mode.MUTATE_THEN_FALSE;
        }

        int contentMutators() {
            return commitCalls + deleteCalls + actionCalls
                    + setCompositionCalls + finishCompositionCalls;
        }

        String before() {
            TextRange selection = liveSelection();
            return text.substring(0, Math.min(selection.start(), selection.end()));
        }

        String after() {
            TextRange selection = liveSelection();
            return text.substring(Math.max(selection.start(), selection.end()));
        }

        String selected() {
            TextRange selection = liveSelection();
            int left = Math.min(selection.start(), selection.end());
            int right = Math.max(selection.start(), selection.end());
            return text.substring(left, right);
        }

        TextRange liveSelection() {
            return nonCollapsedSelection == null
                    ? new TextRange(cursor, cursor)
                    : nonCollapsedSelection;
        }

        String boundedBefore() {
            return tailUtf16(before(), 800);
        }

        String boundedAfter() {
            return headUtf16(after(), 800);
        }

        private static String tailUtf16(String value, int maximumUtf16Units) {
            if (value.length() <= maximumUtf16Units) return value;
            int start = value.length() - maximumUtf16Units;
            if (start < value.length() && Character.isLowSurrogate(value.charAt(start))) {
                start++;
            }
            return value.substring(start);
        }

        private static String headUtf16(String value, int maximumUtf16Units) {
            if (value.length() <= maximumUtf16Units) return value;
            int end = maximumUtf16Units;
            if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
                end--;
            }
            return value.substring(0, end);
        }

        private static CommitLedger.CommitIdSource sequentialIds() {
            AtomicInteger sequence = new AtomicInteger();
            return () -> "commit-" + sequence.incrementAndGet();
        }
    }
}
