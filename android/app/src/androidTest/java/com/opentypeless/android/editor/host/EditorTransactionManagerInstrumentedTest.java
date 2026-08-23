package com.opentypeless.android.editor.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.opentypeless.android.editor.CommitRecord;
import com.opentypeless.android.editor.CommitRecordRequest;
import com.opentypeless.android.editor.EditorAction;
import com.opentypeless.android.editor.CompositionOwner;
import com.opentypeless.android.editor.EditorOperation;
import com.opentypeless.android.editor.EditorOperationKind;
import com.opentypeless.android.editor.EditorSessionLimits;
import com.opentypeless.android.editor.EditorSessionSnapshot;
import com.opentypeless.android.editor.EditorTransactionAudit;
import com.opentypeless.android.editor.EditorTransactionResult;
import com.opentypeless.android.editor.OperationSource;
import com.opentypeless.android.editor.RejectionReason;
import com.opentypeless.android.editor.TargetChangeReason;
import com.opentypeless.android.editor.TransactionReceipt;
import com.opentypeless.android.editor.TransactionFailureKind;
import com.opentypeless.android.editor.TransactionFailureStep;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Android-host contract coverage for EDT-007/009/011/012's synchronous transaction boundary. */
@RunWith(AndroidJUnit4.class)
public final class EditorTransactionManagerInstrumentedTest {
    @Test
    public void keyboardPublicFacadeUsesLiveAndroidEvidenceAndOnlyTransactionMutators() {
        onMain(() -> {
            Harness insertion = Harness.selected("abcd", 1, 3);
            try {
                TestInputMethodService host = new TestInputMethodService(
                        insertion.authorityInfo, insertion.connection);
                EditorTransactionResult result = insertion.manager.insertKeyboardText(
                        host, insertion.snapshot, " ");
                assertTrue(result instanceof EditorTransactionResult.Applied);
                assertEquals("a d", insertion.connection.text());
                assertEquals(1, insertion.connection.commitCalls);
                assertEquals(0, insertion.connection.deleteCalls);
                assertTrue(insertion.connection.extractedTextCalls >= 2);
            } finally {
                insertion.close();
            }

            Harness deletion = Harness.text(
                    "A\uD83D\uDE00", 3, EditorInfo.IME_ACTION_NONE);
            try {
                TestInputMethodService host = new TestInputMethodService(
                        deletion.authorityInfo, deletion.connection);
                EditorTransactionResult result = deletion.manager.deleteKeyboardBackward(
                        host, deletion.snapshot);
                assertTrue(result instanceof EditorTransactionResult.Applied);
                assertEquals("A", deletion.connection.text());
                assertEquals(1, deletion.connection.lastDeleteBeforeCodePoints);
                assertEquals(1, deletion.connection.deleteCalls);
            } finally {
                deletion.close();
            }

            Harness action = Harness.text("go", 2, EditorInfo.IME_ACTION_GO);
            try {
                TestInputMethodService host = new TestInputMethodService(
                        action.authorityInfo, action.connection);
                EditorTransactionResult result = action.manager.performKeyboardEnter(
                        host, action.snapshot);
                assertTrue(result instanceof EditorTransactionResult.Applied);
                assertEquals(EditorInfo.IME_ACTION_GO, action.connection.lastActionId);
                assertEquals(1, action.connection.actionCalls);
            } finally {
                action.close();
            }

            Harness sensitive = Harness.sensitive("secret", 6);
            try {
                TestInputMethodService host = new TestInputMethodService(
                        sensitive.authorityInfo, sensitive.connection);
                EditorTransactionResult result = sensitive.manager.insertKeyboardText(
                        host, sensitive.snapshot, "x");
                assertTrue(result instanceof EditorTransactionResult.Applied);
                assertEquals("secretx", sensitive.connection.text());
                assertEquals(0, sensitive.connection.plaintextGetterCalls);
                assertEquals(0, sensitive.connection.extractedTextCalls);
            } finally {
                sensitive.close();
            }
            return null;
        });
    }

    @Test
    public void voicePublicFacadesUseOneManagerForCompositionReceiptUndoAndRaw() {
        onMain(() -> {
            Harness composition = Harness.text("pre", 3, EditorInfo.IME_ACTION_NONE);
            try {
                TestInputMethodService host = new TestInputMethodService(
                        composition.authorityInfo, composition.connection);
                EditorTransactionResult partial = composition.manager.setVoiceComposition(
                        host, composition.snapshot, "voice", 1L);
                assertTrue(partial instanceof EditorTransactionResult.Applied);
                assertEquals("prevoice", composition.connection.text());
                assertTrue(composition.connection.composingStart() >= 0);
                composition.recapture();

                TransactionReceipt terminal = composition.manager.commitVoiceComposition(
                        host,
                        composition.snapshot,
                        1L,
                        new CommitRecord.RawTranscript.Present("raw"));
                assertTrue(terminal instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) terminal).record();
                assertEquals("voice", record.insertedText());
                assertEquals(1, composition.connection.setCompositionCalls);
                assertEquals(1, composition.connection.finishCompositionCalls);

                composition.recapture();
                EditorTransactionResult undone = composition.manager.undoVoiceCommit(
                        host, composition.snapshot, record.commitId());
                assertTrue(undone instanceof EditorTransactionResult.Applied);
                assertEquals("pre", composition.connection.text());
            } finally {
                composition.close();
            }

            Harness selected = Harness.selected("preOLDpost", 3, 6);
            try {
                TestInputMethodService host = new TestInputMethodService(
                        selected.authorityInfo, selected.connection);
                TransactionReceipt terminal = selected.manager.commitVoiceText(
                        host,
                        selected.snapshot,
                        "final",
                        new CommitRecord.RawTranscript.Present("raw"));
                assertTrue(terminal instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) terminal).record();
                assertEquals("prefinalpost", selected.connection.text());

                selected.recapture();
                EditorTransactionResult restored = selected.manager.restoreRawVoiceCommit(
                        host, selected.snapshot, record.commitId());
                assertTrue(restored instanceof EditorTransactionResult.Applied);
                assertEquals("prerawpost", selected.connection.text());
            } finally {
                selected.close();
            }
            return null;
        });
    }

    @Test
    public void rimePublicFacadesRequireFreshExactTargetAndFinishOneRevision() {
        onMain(() -> {
            Harness composition = Harness.text("pre", 3, EditorInfo.IME_ACTION_NONE);
            try {
                TestInputMethodService host = new TestInputMethodService(
                        composition.authorityInfo, composition.connection);
                EditorTransactionResult first = composition.manager.setRimeComposition(
                        host, composition.snapshot, "ni", 2L);
                assertTrue(first instanceof EditorTransactionResult.Applied);
                assertEquals("preni", composition.connection.text());
                assertTrue(composition.connection.composingStart() >= 0);

                composition.recapture();
                EditorTransactionResult second = composition.manager.setRimeComposition(
                        host, composition.snapshot, "你", 3L);
                assertTrue(second instanceof EditorTransactionResult.Applied);
                assertEquals("pre你", composition.connection.text());

                composition.recapture();
                EditorTransactionResult finished = composition.manager.finishRimeComposition(
                        host, composition.snapshot, 3L);
                assertTrue(finished instanceof EditorTransactionResult.Applied);
                assertEquals("pre你", composition.connection.text());
                assertEquals(2, composition.connection.setCompositionCalls);
                assertEquals(1, composition.connection.finishCompositionCalls);
            } finally {
                composition.close();
            }
            return null;
        });
    }

    @Test
    public void rimeToVoiceCommitAndCancelPathsNeverOverlapOrLoseVisibleText() {
        onMain(() -> {
            Harness committed = Harness.text("pre", 3, EditorInfo.IME_ACTION_NONE);
            try {
                TestInputMethodService host = new TestInputMethodService(
                        committed.authorityInfo, committed.connection);
                assertTrue(committed.manager.setRimeComposition(
                        host, committed.snapshot, "ni", 2L)
                        instanceof EditorTransactionResult.Applied);
                committed.recapture();
                assertTrue(committed.manager.finishRimeComposition(
                        host, committed.snapshot, 2L)
                        instanceof EditorTransactionResult.Applied);
                assertEquals("preni", committed.connection.text());
                assertEquals(-1, committed.connection.composingStart());

                committed.recapture();
                assertTrue(committed.manager.setVoiceComposition(
                        host, committed.snapshot, "voice", 1L)
                        instanceof EditorTransactionResult.Applied);
                committed.recapture();
                assertTrue(committed.manager.commitVoiceComposition(
                        host,
                        committed.snapshot,
                        1L,
                        new CommitRecord.RawTranscript.Present("voice"))
                        instanceof TransactionReceipt.Committed);
                assertEquals("prenivoice", committed.connection.text());
                assertEquals(-1, committed.connection.composingStart());
            } finally {
                committed.close();
            }

            Harness cancelled = Harness.text("pre", 3, EditorInfo.IME_ACTION_NONE);
            try {
                TestInputMethodService host = new TestInputMethodService(
                        cancelled.authorityInfo, cancelled.connection);
                assertTrue(cancelled.manager.setRimeComposition(
                        host, cancelled.snapshot, "ni", 2L)
                        instanceof EditorTransactionResult.Applied);
                cancelled.recapture();
                assertTrue(cancelled.manager.setRimeComposition(
                        host, cancelled.snapshot, "", 3L)
                        instanceof EditorTransactionResult.Applied);
                cancelled.recapture();
                assertTrue(cancelled.manager.finishRimeComposition(
                        host, cancelled.snapshot, 3L)
                        instanceof EditorTransactionResult.Applied);
                assertEquals("pre", cancelled.connection.text());
                assertEquals(-1, cancelled.connection.composingStart());

                cancelled.recapture();
                assertTrue(cancelled.manager.setVoiceComposition(
                        host, cancelled.snapshot, "voice", 1L)
                        instanceof EditorTransactionResult.Applied);
                cancelled.recapture();
                assertTrue(cancelled.manager.commitVoiceComposition(
                        host,
                        cancelled.snapshot,
                        1L,
                        new CommitRecord.RawTranscript.Present("voice"))
                        instanceof TransactionReceipt.Committed);
                assertEquals("prevoice", cancelled.connection.text());
                assertEquals(-1, cancelled.connection.composingStart());
            } finally {
                cancelled.close();
            }
            return null;
        });
    }

    @Test
    public void insertUsesRealEditableAndOneBalancedBatch() {
        onMain(() -> {
            Harness harness = Harness.text("ab", 2, EditorInfo.IME_ACTION_NONE);
            try {
                EditorTransactionResult result = harness.apply(
                        new EditorOperation.InsertText("\uD83D\uDE00x", OperationSource.LATIN));

                assertTrue(result instanceof EditorTransactionResult.Applied);
                assertEquals("ab\uD83D\uDE00x", harness.connection.text());
                assertEquals(5, harness.connection.cursor());
                assertEquals(1, harness.connection.beginCalls);
                assertEquals(1, harness.connection.endCalls);
                assertEquals(1, harness.connection.commitCalls);
            } finally {
                harness.close();
            }
            return null;
        });
    }

    @Test
    public void deleteUsesCodePointsWithoutSplittingEmojiAndActionUsesAllowlistedId() {
        onMain(() -> {
            Harness deletion = Harness.text("A\uD83D\uDE00B", 3, EditorInfo.IME_ACTION_NONE);
            try {
                EditorTransactionResult result = deletion.apply(
                        new EditorOperation.DeleteBeforeCursor(1, OperationSource.LATIN));
                assertTrue(result instanceof EditorTransactionResult.Applied);
                assertEquals("AB", deletion.connection.text());
                assertEquals(1, deletion.connection.cursor());
                assertEquals(1, deletion.connection.deleteCalls);
                assertEquals(1, deletion.connection.beginCalls);
                assertEquals(1, deletion.connection.endCalls);
            } finally {
                deletion.close();
            }

            Harness action = Harness.text("go", 2, EditorInfo.IME_ACTION_DONE);
            try {
                EditorTransactionResult result = action.apply(
                        new EditorOperation.PerformEditorAction(
                                EditorAction.DONE, OperationSource.RIME));
                assertTrue(result instanceof EditorTransactionResult.Applied);
                assertEquals(EditorInfo.IME_ACTION_DONE, action.connection.lastActionId);
                assertEquals(1, action.connection.actionCalls);
                assertEquals(1, action.connection.beginCalls);
                assertEquals(1, action.connection.endCalls);
            } finally {
                action.close();
            }
            return null;
        });
    }

    @Test
    public void workerApplyFailsFastBeforeCallbacksBatchOrContentWrite() throws Exception {
        Harness harness = onMain(() -> Harness.text("safe", 4, EditorInfo.IME_ACTION_NONE));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> harness.apply(
                    new EditorOperation.InsertText("x", OperationSource.LATIN)));
            try {
                future.get();
                fail("expected owner-thread rejection");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
                assertFalse(String.valueOf(expected.getCause()).contains("safe"));
            }

            assertEquals(0, harness.evidenceReads);
            assertEquals(0, harness.connection.beginCalls);
            assertEquals(0, harness.connection.totalMutatorCalls());
        } finally {
            executor.shutdownNow();
            onMain(() -> {
                harness.close();
                return null;
            });
        }
    }

    @Test
    public void restartDuringBatchEndsOriginalConnectionWithoutMutating() {
        onMain(() -> {
            Harness sameConnectionRestart =
                    Harness.text("first", 5, EditorInfo.IME_ACTION_NONE);
            try {
                sameConnectionRestart.connection.onBeginBatch = () ->
                        sameConnectionRestart.manager.onStartInput(
                                sameConnectionRestart.authorityInfo,
                                sameConnectionRestart.connection);

                EditorTransactionResult result = sameConnectionRestart.apply(
                        new EditorOperation.InsertText("x", OperationSource.LATIN));
                assertTargetChanged(result, TargetChangeReason.EPOCH_CHANGED);
                assertEquals(0, sameConnectionRestart.connection.totalMutatorCalls());
                assertEquals(1, sameConnectionRestart.connection.beginCalls);
                assertEquals(1, sameConnectionRestart.connection.endCalls);
            } finally {
                sameConnectionRestart.close();
            }

            Harness fieldSwitch = Harness.text("old", 3, EditorInfo.IME_ACTION_NONE);
            ControlledInputConnection replacement = ControlledInputConnection.create("new", 3);
            try {
                EditorInfo replacementInfo = info(
                        2, 3, InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NONE);
                fieldSwitch.connection.onBeginBatch = () -> {
                    fieldSwitch.authorityInfo = replacementInfo;
                    fieldSwitch.authorityConnection = replacement;
                    replacement.manager = fieldSwitch.manager;
                    fieldSwitch.manager.onStartInput(replacementInfo, replacement);
                };

                EditorTransactionResult result = fieldSwitch.apply(
                        new EditorOperation.DeleteBeforeCursor(1, OperationSource.LATIN));
                assertTargetChanged(result, TargetChangeReason.EPOCH_CHANGED);
                assertEquals(0, fieldSwitch.connection.totalMutatorCalls());
                assertEquals(0, replacement.totalMutatorCalls());
                assertEquals(1, fieldSwitch.connection.beginCalls);
                assertEquals(1, fieldSwitch.connection.endCalls);
                assertEquals(0, replacement.endCalls);
            } finally {
                fieldSwitch.close();
            }
            return null;
        });
    }

    @Test
    public void mutatorRuntimeFailuresReturnStableRedactedResults() {
        onMain(() -> {
            Harness insert = Harness.text("pre", 3, EditorInfo.IME_ACTION_NONE);
            try {
                insert.connection.commitThrows = true;
                EditorTransactionResult result = insert.apply(
                        new EditorOperation.InsertText("x", OperationSource.LATIN));
                assertRuntimeOutcomeUnconfirmed(result);
                assertEquals("pre", insert.connection.text());
                assertRedacted(result);
            } finally {
                insert.close();
            }

            Harness delete = Harness.text("A\uD83D\uDE00", 3, EditorInfo.IME_ACTION_NONE);
            try {
                delete.connection.deleteThrows = true;
                EditorTransactionResult result = delete.apply(
                        new EditorOperation.DeleteBeforeCursor(1, OperationSource.LATIN));
                assertRuntimeOutcomeUnconfirmed(result);
                assertEquals("A\uD83D\uDE00", delete.connection.text());
                assertRedacted(result);
            } finally {
                delete.close();
            }

            Harness action = Harness.text("act", 3, EditorInfo.IME_ACTION_SEND);
            try {
                action.connection.actionThrows = true;
                EditorTransactionResult result = action.apply(
                        new EditorOperation.PerformEditorAction(
                                EditorAction.SEND, OperationSource.LATIN));
                assertTrue(result instanceof EditorTransactionResult.RollbackFailed);
                EditorTransactionResult.RollbackFailed failed =
                        (EditorTransactionResult.RollbackFailed) result;
                assertEquals(TransactionFailureKind.RUNTIME_FAILURE,
                        failed.originalFailure().kind());
                assertEquals(TransactionFailureKind.OUTCOME_UNCONFIRMED,
                        failed.rollbackFailure().kind());
                assertRedacted(result);
            } finally {
                action.close();
            }
            return null;
        });
    }

    @Test
    public void sensitiveInsertNeverReadsPlaintextEvidenceOrFrameworkTextGetters() {
        onMain(() -> {
            Harness sensitive = Harness.sensitive("xx", 2);
            try {
                EditorTransactionResult result = sensitive.apply(
                        new EditorOperation.InsertText("y", OperationSource.LATIN));

                assertTrue(result instanceof EditorTransactionResult.Applied);
                assertEquals("xxy", sensitive.connection.text());
                assertEquals(0, sensitive.evidenceReads);
                assertEquals(0, sensitive.connection.plaintextGetterCalls);
                assertEquals(1, sensitive.connection.commitCalls);
            } finally {
                sensitive.close();
            }
            return null;
        });
    }

    @Test
    public void compositionCreatesAndFinishesARealEditableComposingSpan() {
        onMain(() -> {
            Harness harness = Harness.text("", 0, EditorInfo.IME_ACTION_NONE);
            try {
                EditorTransactionResult set = harness.apply(
                        new EditorOperation.SetComposition(
                                "voice", CompositionOwner.VOICE, 1, OperationSource.VOICE));
                assertTrue(set instanceof EditorTransactionResult.Applied);
                assertEquals("voice", harness.connection.text());
                assertTrue(harness.connection.composingStart() >= 0);
                assertTrue(harness.connection.composingEnd() > harness.connection.composingStart());
                assertEquals(1, harness.connection.setCompositionCalls);

                harness.recapture();
                EditorTransactionResult finish = harness.apply(
                        new EditorOperation.CommitComposition(
                                CompositionOwner.VOICE, 1, OperationSource.VOICE));
                assertTrue(finish instanceof EditorTransactionResult.Applied);
                assertEquals(-1, harness.connection.composingStart());
                assertEquals(-1, harness.connection.composingEnd());
                assertEquals(1, harness.connection.finishCompositionCalls);
                assertEquals(2, harness.connection.beginCalls);
                assertEquals(2, harness.connection.endCalls);
            } finally {
                harness.close();
            }
            return null;
        });
    }

    @Test
    public void sensitiveLocalCompositionUsesNoPlaintextGetterAndCloudOwnerIsRejected() {
        onMain(() -> {
            Harness local = Harness.sensitive("", 0);
            try {
                assertTrue(local.apply(new EditorOperation.SetComposition(
                                "x", CompositionOwner.LATIN, 1, OperationSource.LATIN))
                        instanceof EditorTransactionResult.Applied);
                local.recapture();
                assertTrue(local.apply(new EditorOperation.CommitComposition(
                                CompositionOwner.LATIN, 1, OperationSource.LATIN))
                        instanceof EditorTransactionResult.Applied);
                assertEquals(0, local.evidenceReads);
                assertEquals(0, local.connection.plaintextGetterCalls);
            } finally {
                local.close();
            }

            Harness cloud = Harness.sensitive("", 0);
            try {
                EditorTransactionResult rejected = cloud.apply(
                        new EditorOperation.SetComposition(
                                "x", CompositionOwner.VOICE, 1, OperationSource.VOICE));
                assertTrue(rejected instanceof EditorTransactionResult.Rejected);
                assertEquals(0, cloud.connection.beginCalls);
                assertEquals(0, cloud.connection.setCompositionCalls);
                assertEquals(0, cloud.connection.plaintextGetterCalls);
            } finally {
                cloud.close();
            }
            return null;
        });
    }

    @Test
    public void compositionRuntimeFailuresAreContentFreePoisonedAndUnconfirmed() {
        onMain(() -> {
            Harness setFailure = Harness.text("", 0, EditorInfo.IME_ACTION_NONE);
            try {
                setFailure.connection.setCompositionThrows = true;
                EditorTransactionResult failedSet = setFailure.apply(
                        new EditorOperation.SetComposition(
                                "private-voice", CompositionOwner.VOICE, 1,
                                OperationSource.VOICE));
                assertCompositionRuntimeFailure(
                        failedSet, TransactionFailureStep.SET_COMPOSITION);
                assertRedacted(failedSet);
                setFailure.connection.setCompositionThrows = false;
                EditorTransactionResult retry = setFailure.apply(
                        new EditorOperation.SetComposition(
                                "retry", CompositionOwner.VOICE, 2,
                                OperationSource.VOICE));
                assertTrue(retry instanceof EditorTransactionResult.Rejected);
            } finally {
                setFailure.close();
            }

            Harness finishFailure = Harness.text("", 0, EditorInfo.IME_ACTION_NONE);
            try {
                assertTrue(finishFailure.apply(new EditorOperation.SetComposition(
                                "voice", CompositionOwner.VOICE, 1, OperationSource.VOICE))
                        instanceof EditorTransactionResult.Applied);
                finishFailure.recapture();
                finishFailure.connection.finishCompositionThrows = true;
                EditorTransactionResult failedFinish = finishFailure.apply(
                        new EditorOperation.CommitComposition(
                                CompositionOwner.VOICE, 1, OperationSource.VOICE));
                assertCompositionRuntimeFailure(
                        failedFinish, TransactionFailureStep.FINISH_COMPOSITION);
                assertTrue(finishFailure.connection.composingStart() >= 0);
                assertRedacted(failedFinish);
            } finally {
                finishFailure.close();
            }

            Harness sensitiveFailure = Harness.sensitive("", 0);
            try {
                sensitiveFailure.connection.setCompositionThrows = true;
                EditorTransactionResult result = sensitiveFailure.apply(
                        new EditorOperation.SetComposition(
                                "private", CompositionOwner.LATIN, 1,
                                OperationSource.LATIN));
                assertCompositionRuntimeFailure(
                        result, TransactionFailureStep.SET_COMPOSITION);
                assertEquals(0, sensitiveFailure.evidenceReads);
                assertEquals(0, sensitiveFailure.connection.plaintextGetterCalls);
            } finally {
                sensitiveFailure.close();
            }
            return null;
        });
    }

    @Test
    public void undoExactVoiceReceiptDeletesEmojiByCodePointAndConsumesTheSlot() {
        onMain(() -> {
            Harness harness = Harness.text("prefix", 6, EditorInfo.IME_ACTION_NONE);
            try {
                String inserted = "\uD83D\uDE00x";
                TransactionReceipt receipt = harness.applyWithReceipt(
                        new EditorOperation.InsertText(inserted, OperationSource.VOICE));
                assertTrue(receipt instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) receipt).record();
                assertEquals(1, harness.manager.commitRecordCountForTest());
                assertEquals("prefix\uD83D\uDE00x", harness.connection.text());

                harness.recapture();
                EditorTransactionResult result = harness.undo(record.commitId());

                assertTrue(result instanceof EditorTransactionResult.Applied);
                assertEquals("prefix", harness.connection.text());
                assertEquals(6, harness.connection.cursor());
                assertEquals(1, harness.connection.deleteCalls);
                assertEquals(2, harness.connection.lastDeleteBeforeCodePoints);
                assertEquals(2, harness.undoEvidenceReads);
                assertEquals(6, harness.connection.plaintextGetterCalls);
                assertEquals(2, harness.connection.beginCalls);
                assertEquals(2, harness.connection.endCalls);
                assertEquals(0, harness.manager.commitRecordCountForTest());

                int evidenceReads = harness.undoEvidenceReads;
                EditorTransactionResult replay = harness.undo(record.commitId());
                assertRejected(replay, RejectionReason.COMMIT_RECORD_UNAVAILABLE);
                assertEquals(evidenceReads, harness.undoEvidenceReads);
                assertEquals(1, harness.connection.deleteCalls);
            } finally {
                harness.close();
            }
            return null;
        });
    }

    @Test
    public void undoRejectsSameCoordinateCommittedTextTamperingBeforeDelete() {
        onMain(() -> {
            Harness harness = Harness.text("pre", 3, EditorInfo.IME_ACTION_NONE);
            try {
                TransactionReceipt receipt = harness.applyWithReceipt(
                        new EditorOperation.InsertText("voice", OperationSource.VOICE));
                assertTrue(receipt instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) receipt).record();

                harness.connection.replaceTextForTest(3, 8, "other");
                harness.recapture();
                int beginCalls = harness.connection.beginCalls;
                EditorTransactionResult result = harness.undo(record.commitId());

                assertTargetChanged(result, TargetChangeReason.SURROUNDING_TEXT_CHANGED);
                assertEquals("preother", harness.connection.text());
                assertEquals(8, harness.connection.cursor());
                assertEquals(0, harness.connection.deleteCalls);
                assertEquals(beginCalls, harness.connection.beginCalls);
                assertEquals(0, harness.manager.commitRecordCountForTest());
            } finally {
                harness.close();
            }
            return null;
        });
    }

    @Test
    public void undoProvesAndDeletesCommittedTextLongerThanSnapshotEvidenceWindow() {
        onMain(() -> {
            Harness harness = Harness.text("pre", 3, EditorInfo.IME_ACTION_NONE);
            try {
                String inserted = "v".repeat(1_200);
                TransactionReceipt receipt = harness.applyWithReceipt(
                        new EditorOperation.InsertText(inserted, OperationSource.VOICE));
                assertTrue(receipt instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) receipt).record();

                harness.recapture();
                EditorTransactionResult result = harness.undo(record.commitId());

                assertTrue(result instanceof EditorTransactionResult.Applied);
                assertEquals("pre", harness.connection.text());
                assertEquals(1_200, harness.connection.lastDeleteBeforeCodePoints);
                assertEquals(2, harness.undoEvidenceReads);
                assertTrue(harness.lastUndoEvidenceRequest.beforeUtf16Units()
                        > EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS);
                assertEquals(
                        inserted.length()
                                + EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS,
                        harness.lastUndoEvidenceRequest.beforeUtf16Units());
                assertEquals(0, harness.manager.commitRecordCountForTest());
            } finally {
                harness.close();
            }
            return null;
        });
    }

    @Test
    public void undoRejectsLongCommitTamperingOutsideThe800UnitSnapshotWindow() {
        onMain(() -> {
            Harness harness = Harness.text("pre", 3, EditorInfo.IME_ACTION_NONE);
            try {
                String inserted = "v".repeat(1_200);
                TransactionReceipt receipt = harness.applyWithReceipt(
                        new EditorOperation.InsertText(inserted, OperationSource.VOICE));
                assertTrue(receipt instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) receipt).record();

                // This changes the first committed unit but leaves the trailing 800-unit snapshot
                // window, selection and total length unchanged.
                harness.connection.replaceTextForTest(3, 4, "w");
                harness.recapture();
                int beginCalls = harness.connection.beginCalls;
                EditorTransactionResult result = harness.undo(record.commitId());

                assertTargetChanged(result, TargetChangeReason.SURROUNDING_TEXT_CHANGED);
                assertEquals(0, harness.connection.deleteCalls);
                assertEquals(beginCalls, harness.connection.beginCalls);
                assertEquals(1, harness.undoEvidenceReads);
                assertEquals(
                        inserted.length()
                                + EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS,
                        harness.lastUndoEvidenceRequest.beforeUtf16Units());
                assertEquals(0, harness.manager.commitRecordCountForTest());
            } finally {
                harness.close();
            }
            return null;
        });
    }

    @Test
    public void sensitiveSessionCreatesNoRecordAndUndoReadsNoEvidence() {
        onMain(() -> {
            Harness harness = Harness.sensitive("secret", 6);
            try {
                TransactionReceipt receipt = harness.applyWithReceipt(
                        new EditorOperation.InsertText("voice", OperationSource.VOICE));
                assertTrue(receipt instanceof TransactionReceipt.WithoutCommit);
                assertRejected(receipt.result(), RejectionReason.SENSITIVE_FIELD);
                assertEquals(0, harness.manager.commitRecordCountForTest());
                assertEquals(0, harness.evidenceReads);
                assertEquals(0, harness.connection.plaintextGetterCalls);
                assertEquals(0, harness.connection.totalMutatorCalls());

                EditorTransactionResult undo = harness.undo("forged-sensitive-id");
                assertRejected(undo, RejectionReason.COMMIT_RECORD_UNAVAILABLE);
                assertEquals(0, harness.undoEvidenceReads);
                assertEquals(0, harness.connection.plaintextGetterCalls);
                assertEquals(0, harness.connection.deleteCalls);
            } finally {
                harness.close();
            }
            return null;
        });
    }

    @Test
    public void restartRevokesOldCommitIdBeforeUndoEvidenceOrDelete() {
        onMain(() -> {
            Harness harness = Harness.text("pre", 3, EditorInfo.IME_ACTION_NONE);
            try {
                TransactionReceipt receipt = harness.applyWithReceipt(
                        new EditorOperation.InsertText("voice", OperationSource.VOICE));
                assertTrue(receipt instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) receipt).record();
                harness.recapture();
                assertEquals(1, harness.manager.commitRecordCountForTest());

                harness.restart();
                EditorTransactionResult result = harness.undo(record.commitId());

                assertRejected(result, RejectionReason.COMMIT_RECORD_UNAVAILABLE);
                assertEquals("prevoice", harness.connection.text());
                assertEquals(0, harness.undoEvidenceReads);
                assertEquals(0, harness.connection.deleteCalls);
                assertEquals(0, harness.manager.commitRecordCountForTest());
            } finally {
                harness.close();
            }
            return null;
        });
    }

    @Test
    public void rawRestoreReplacesEmojiVoiceCommitAndConsumesTheSlot() {
        onMain(() -> {
            Harness harness = Harness.text("prepost", 3, EditorInfo.IME_ACTION_NONE);
            try {
                String inserted = "final-\uD83D\uDE00";
                String raw = "raw-\uD83D\uDE03";
                TransactionReceipt receipt = harness.applyWithReceipt(
                        new EditorOperation.InsertText(inserted, OperationSource.VOICE),
                        new CommitRecord.RawTranscript.Present(raw));
                assertTrue(receipt instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) receipt).record();
                harness.recapture();

                EditorTransactionResult result = harness.restoreRaw(record.commitId());

                assertTrue(result instanceof EditorTransactionResult.Applied);
                assertEquals("pre" + raw + "post", harness.connection.text());
                assertEquals(3 + raw.length(), harness.connection.cursor());
                assertEquals(1, harness.connection.deleteCalls);
                assertEquals(inserted.codePointCount(0, inserted.length()),
                        harness.connection.lastDeleteBeforeCodePoints);
                assertEquals(2, harness.connection.commitCalls);
                assertEquals(4, harness.undoEvidenceReads);
                assertEquals(0, harness.manager.commitRecordCountForTest());

                EditorTransactionResult replay = harness.restoreRaw(record.commitId());
                assertRejected(replay, RejectionReason.COMMIT_RECORD_UNAVAILABLE);
                assertEquals(1, harness.connection.deleteCalls);
                assertEquals(2, harness.connection.commitCalls);
            } finally {
                harness.close();
            }
            return null;
        });
    }

    @Test
    public void failedRawAndSelectedSecondWritesRestoreFinalAndRetainTheExactSlot() {
        onMain(() -> {
            Harness raw = Harness.text("prepost", 3, EditorInfo.IME_ACTION_NONE);
            try {
                TransactionReceipt receipt = raw.applyWithReceipt(
                        new EditorOperation.InsertText("final", OperationSource.VOICE),
                        new CommitRecord.RawTranscript.Present("rough"));
                assertTrue(receipt instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) receipt).record();
                raw.recapture();
                raw.connection.rejectCommitCall = raw.connection.commitCalls + 1;

                EditorTransactionResult result = raw.restoreRaw(record.commitId());

                assertTrue(result instanceof EditorTransactionResult.RolledBack);
                assertEquals("prefinalpost", raw.connection.text());
                assertEquals(8, raw.connection.cursor());
                assertEquals(1, raw.connection.deleteCalls);
                assertEquals(3, raw.connection.commitCalls);
                assertEquals(6, raw.undoEvidenceReads);
                assertEquals(1, raw.manager.commitRecordCountForTest());

                raw.connection.rejectCommitCall = -1;
                assertTrue(raw.restoreRaw(record.commitId())
                        instanceof EditorTransactionResult.Applied);
                assertEquals("preroughpost", raw.connection.text());
                assertEquals(0, raw.manager.commitRecordCountForTest());
            } finally {
                raw.close();
            }

            Harness selected = Harness.selected("preOLDpost", 6, 3);
            try {
                TransactionReceipt receipt = selected.applyWithReceipt(
                        replace(selected, "final", OperationSource.VOICE));
                assertTrue(receipt instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) receipt).record();
                selected.recapture();
                selected.connection.rejectCommitCall = selected.connection.commitCalls + 1;

                EditorTransactionResult result = selected.undo(record.commitId());

                assertTrue(result instanceof EditorTransactionResult.RolledBack);
                assertEquals("prefinalpost", selected.connection.text());
                assertEquals(8, selected.connection.cursor());
                assertEquals(1, selected.connection.deleteCalls);
                assertEquals(3, selected.connection.commitCalls);
                assertEquals(6, selected.undoEvidenceReads);
                assertEquals(1, selected.manager.commitRecordCountForTest());
            } finally {
                selected.close();
            }
            return null;
        });
    }

    @Test
    public void rawRestoreRejectsLongCommitTamperingOutsideThe800UnitWindowWithoutWriting() {
        onMain(() -> {
            Harness harness = Harness.text("pre", 3, EditorInfo.IME_ACTION_NONE);
            try {
                String inserted = "v".repeat(1_200);
                TransactionReceipt receipt = harness.applyWithReceipt(
                        new EditorOperation.InsertText(inserted, OperationSource.VOICE),
                        new CommitRecord.RawTranscript.Present("raw"));
                assertTrue(receipt instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) receipt).record();

                harness.connection.replaceTextForTest(3, 4, "w");
                harness.recapture();
                int beginCalls = harness.connection.beginCalls;
                int mutatorCalls = harness.connection.totalMutatorCalls();

                EditorTransactionResult result = harness.restoreRaw(record.commitId());

                assertTargetChanged(result, TargetChangeReason.SURROUNDING_TEXT_CHANGED);
                assertEquals(beginCalls, harness.connection.beginCalls);
                assertEquals(mutatorCalls, harness.connection.totalMutatorCalls());
                assertEquals(0, harness.connection.deleteCalls);
                assertEquals(1, harness.undoEvidenceReads);
                assertEquals(
                        inserted.length()
                                + EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS,
                        harness.lastUndoEvidenceRequest.beforeUtf16Units());
                assertEquals(0, harness.manager.commitRecordCountForTest());
            } finally {
                harness.close();
            }
            return null;
        });
    }

    @Test
    public void restartRevokesRawRestoreBeforeEvidenceOrReplacementWrites() {
        onMain(() -> {
            Harness harness = Harness.text("pre", 3, EditorInfo.IME_ACTION_NONE);
            try {
                TransactionReceipt receipt = harness.applyWithReceipt(
                        new EditorOperation.InsertText("final", OperationSource.VOICE),
                        new CommitRecord.RawTranscript.Present("raw"));
                assertTrue(receipt instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) receipt).record();
                harness.recapture();

                harness.restart();
                int mutatorCalls = harness.connection.totalMutatorCalls();
                EditorTransactionResult result = harness.restoreRaw(record.commitId());

                assertRejected(result, RejectionReason.COMMIT_RECORD_UNAVAILABLE);
                assertEquals("prefinal", harness.connection.text());
                assertEquals(0, harness.undoEvidenceReads);
                assertEquals(mutatorCalls, harness.connection.totalMutatorCalls());
                assertEquals(0, harness.manager.commitRecordCountForTest());
            } finally {
                harness.close();
            }
            return null;
        });
    }

    @Test
    public void sensitiveRawRestoreReadsNoEvidenceOrPlaintextAndWritesNothing() {
        onMain(() -> {
            Harness harness = Harness.sensitive("secret", 6);
            try {
                EditorTransactionResult result = harness.restoreRaw("forged-sensitive-id");

                assertRejected(result, RejectionReason.COMMIT_RECORD_UNAVAILABLE);
                assertEquals(0, harness.undoEvidenceReads);
                assertEquals(0, harness.connection.plaintextGetterCalls);
                assertEquals(0, harness.connection.totalMutatorCalls());
                assertEquals(0, harness.manager.commitRecordCountForTest());
            } finally {
                harness.close();
            }
            return null;
        });
    }

    @Test
    public void replaceSelectionUsesRealBaseInputConnectionForDirectionEmptyAndEmoji() {
        onMain(() -> {
            Harness forward = Harness.selected("preOLDpost", 3, 6);
            try {
                assertTrue(forward.apply(replace(
                                forward, "new", OperationSource.VOICE))
                        instanceof EditorTransactionResult.Applied);
                assertEquals("prenewpost", forward.connection.text());
                assertEquals(6, forward.connection.cursor());
                assertEquals(1, forward.connection.commitCalls);
                assertEquals(1, forward.connection.beginCalls);
                assertEquals(1, forward.connection.endCalls);
            } finally {
                forward.close();
            }

            Harness reverse = Harness.selected("preOLDpost", 6, 3);
            try {
                assertTrue(reverse.apply(replace(
                                reverse, "R", OperationSource.ACTION))
                        instanceof EditorTransactionResult.Applied);
                assertEquals("preRpost", reverse.connection.text());
                assertEquals(4, reverse.connection.cursor());
                assertEquals(1, reverse.connection.commitCalls);
            } finally {
                reverse.close();
            }

            Harness empty = Harness.selected("preOLDpost", 3, 6);
            try {
                assertTrue(empty.apply(replace(
                                empty, "", OperationSource.LATIN))
                        instanceof EditorTransactionResult.Applied);
                assertEquals("prepost", empty.connection.text());
                assertEquals(3, empty.connection.cursor());
                assertEquals(1, empty.connection.commitCalls);
            } finally {
                empty.close();
            }

            Harness emoji = Harness.selected("A\uD83D\uDE00B", 1, 3);
            try {
                assertTrue(emoji.apply(replace(
                                emoji, "\uD83D\uDE03", OperationSource.RIME))
                        instanceof EditorTransactionResult.Applied);
                assertEquals("A\uD83D\uDE03B", emoji.connection.text());
                assertEquals(3, emoji.connection.cursor());
                assertEquals(1, emoji.connection.commitCalls);
            } finally {
                emoji.close();
            }
            return null;
        });
    }

    @Test
    public void replaceSelectionRejectsUnreportedSelectionAndSameRangeTamperingWithoutWrite() {
        onMain(() -> {
            Harness moved = Harness.selected("preOLDpost", 3, 6);
            try {
                EditorOperation.ReplaceSelection operation =
                        replace(moved, "new", OperationSource.VOICE);
                moved.connection.setSelectionForTest(0, 3, false);

                assertTargetChanged(
                        moved.apply(operation), TargetChangeReason.SELECTION_CHANGED);
                assertEquals("preOLDpost", moved.connection.text());
                assertEquals(0, moved.connection.beginCalls);
                assertEquals(0, moved.connection.commitCalls);
            } finally {
                moved.close();
            }

            Harness tampered = Harness.selected("preOLDpost", 3, 6);
            try {
                EditorOperation.ReplaceSelection operation =
                        replace(tampered, "new", OperationSource.VOICE);
                tampered.connection.replaceTextForTest(3, 6, "BAD");

                assertTargetChanged(
                        tampered.apply(operation), TargetChangeReason.SELECTED_TEXT_CHANGED);
                assertEquals("preBADpost", tampered.connection.text());
                assertEquals(0, tampered.connection.beginCalls);
                assertEquals(0, tampered.connection.commitCalls);
            } finally {
                tampered.close();
            }
            return null;
        });
    }

    @Test
    public void selectedOriginReceiptSupportsExactUndoAndRawRestoreOnRealEditable() {
        onMain(() -> {
            Harness undo = Harness.selected("preOLDpost", 3, 6);
            try {
                TransactionReceipt receipt = undo.applyWithReceipt(
                        replace(undo, "final", OperationSource.VOICE));
                assertTrue(receipt instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) receipt).record();
                undo.recapture();

                EditorTransactionResult result = undo.undo(record.commitId());

                assertTrue(result instanceof EditorTransactionResult.Applied);
                assertEquals("preOLDpost", undo.connection.text());
                assertEquals(6, undo.connection.cursor());
                assertEquals(1, undo.connection.deleteCalls);
                assertEquals(2, undo.connection.commitCalls);
                assertEquals(4, undo.undoEvidenceReads);
                assertEquals(0, undo.manager.commitRecordCountForTest());
            } finally {
                undo.close();
            }

            Harness raw = Harness.selected("preOLDpost", 3, 6);
            try {
                TransactionReceipt receipt = raw.applyWithReceipt(
                        replace(raw, "final", OperationSource.VOICE),
                        new CommitRecord.RawTranscript.Present("rough"));
                assertTrue(receipt instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) receipt).record();
                raw.recapture();

                EditorTransactionResult result = raw.restoreRaw(record.commitId());

                assertTrue(result instanceof EditorTransactionResult.Applied);
                assertEquals("preroughpost", raw.connection.text());
                assertEquals(8, raw.connection.cursor());
                assertEquals(1, raw.connection.deleteCalls);
                assertEquals(2, raw.connection.commitCalls);
                assertEquals(4, raw.undoEvidenceReads);
                assertEquals(0, raw.manager.commitRecordCountForTest());
            } finally {
                raw.close();
            }
            return null;
        });
    }

    @Test
    public void auditEnvelopeTracksRealResultAndSourceWithoutEditablePayload() {
        onMain(() -> {
            Harness harness = Harness.text("PRIVATE_PREFIX", 14, EditorInfo.IME_ACTION_NONE);
            try {
                TransactionReceipt receipt = harness.applyWithReceipt(
                        new EditorOperation.InsertText(
                                "PRIVATE_FINAL", OperationSource.VOICE));
                assertTrue(receipt instanceof TransactionReceipt.Committed);
                CommitRecord record = ((TransactionReceipt.Committed) receipt).record();
                assertEquals(1, harness.audits.size());
                EditorTransactionAudit commitAudit = harness.audits.get(0);
                assertEquals(OperationSource.VOICE, commitAudit.source());
                assertEquals(EditorOperationKind.INSERT_TEXT, commitAudit.operationKind());
                assertSame(receipt.result(), commitAudit.result());
                assertFalse(commitAudit.toString().contains("PRIVATE_PREFIX"));
                assertFalse(commitAudit.toString().contains("PRIVATE_FINAL"));
                assertFalse(commitAudit.toString().contains(record.commitId()));

                harness.recapture();
                harness.audits.clear();
                EditorTransactionResult undo = harness.undo(record.commitId());
                assertTrue(undo instanceof EditorTransactionResult.Applied);
                assertEquals(1, harness.audits.size());
                EditorTransactionAudit undoAudit = harness.audits.get(0);
                assertEquals(OperationSource.UNDO, undoAudit.source());
                assertEquals(
                        EditorOperationKind.REPLACE_LAST_COMMIT,
                        undoAudit.operationKind());
                assertSame(undo, undoAudit.result());
                assertFalse(undoAudit.toString().contains(record.commitId()));
            } finally {
                harness.close();
            }

            Harness sensitive = Harness.sensitive("PASSWORD_SENTINEL", 17);
            try {
                EditorTransactionResult rejected = sensitive.apply(
                        new EditorOperation.InsertText(
                                "REMOTE_SENTINEL", OperationSource.VOICE));
                assertTrue(rejected instanceof EditorTransactionResult.Rejected);
                assertEquals(0, sensitive.evidenceReads);
                assertEquals(1, sensitive.audits.size());
                EditorTransactionAudit audit = sensitive.audits.get(0);
                assertEquals(OperationSource.VOICE, audit.source());
                assertSame(rejected, audit.result());
                assertFalse(audit.toString().contains("PASSWORD_SENTINEL"));
                assertFalse(audit.toString().contains("REMOTE_SENTINEL"));
            } finally {
                sensitive.close();
            }
            return null;
        });
    }

    private static EditorOperation.ReplaceSelection replace(
            Harness harness, String replacement, OperationSource source) {
        return new EditorOperation.ReplaceSelection(
                harness.snapshot.selection(),
                harness.snapshot.selectedTextFingerprint(),
                replacement,
                source);
    }

    private static void assertRuntimeOutcomeUnconfirmed(EditorTransactionResult result) {
        assertTrue(result instanceof EditorTransactionResult.RollbackFailed);
        EditorTransactionResult.RollbackFailed failed =
                (EditorTransactionResult.RollbackFailed) result;
        assertEquals(TransactionFailureKind.RUNTIME_FAILURE,
                failed.originalFailure().kind());
        assertEquals(TransactionFailureKind.OUTCOME_UNCONFIRMED,
                failed.rollbackFailure().kind());
    }

    private static void assertCompositionRuntimeFailure(
            EditorTransactionResult result, TransactionFailureStep step) {
        assertTrue(result instanceof EditorTransactionResult.RollbackFailed);
        EditorTransactionResult.RollbackFailed failed =
                (EditorTransactionResult.RollbackFailed) result;
        assertEquals(step, failed.originalFailure().step());
        assertEquals(TransactionFailureKind.RUNTIME_FAILURE,
                failed.originalFailure().kind());
        assertEquals(TransactionFailureStep.VERIFY_EDITOR_STATE,
                failed.rollbackFailure().step());
        assertEquals(TransactionFailureKind.OUTCOME_UNCONFIRMED,
                failed.rollbackFailure().kind());
    }

    private static void assertRedacted(EditorTransactionResult result) {
        String diagnostic = result.toString();
        assertFalse(diagnostic.contains(ControlledInputConnection.HOSTILE_EXCEPTION_TEXT));
        assertFalse(diagnostic.contains("pre"));
        assertFalse(diagnostic.contains("private"));
    }

    private static void assertTargetChanged(
            EditorTransactionResult result, TargetChangeReason reason) {
        assertTrue(result instanceof EditorTransactionResult.TargetChanged);
        assertEquals(reason, ((EditorTransactionResult.TargetChanged) result).reason());
    }

    private static void assertRejected(
            EditorTransactionResult result, RejectionReason reason) {
        assertTrue(result instanceof EditorTransactionResult.Rejected);
        assertEquals(reason, ((EditorTransactionResult.Rejected) result).reason());
    }

    private static EditorInfo info(int fieldId, int cursor, int inputType, int imeOptions) {
        return info(fieldId, cursor, cursor, inputType, imeOptions);
    }

    private static EditorInfo info(
            int fieldId,
            int selectionStart,
            int selectionEnd,
            int inputType,
            int imeOptions) {
        EditorInfo info = new EditorInfo();
        info.packageName = "com.opentypeless.testhost";
        info.fieldId = fieldId;
        info.inputType = inputType;
        info.imeOptions = imeOptions;
        info.initialSelStart = selectionStart;
        info.initialSelEnd = selectionEnd;
        return info;
    }

    private static <T> T onMain(Callable<T> callable) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                value.set(callable.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        Throwable thrown = failure.get();
        if (thrown instanceof AssertionError assertion) throw assertion;
        if (thrown instanceof RuntimeException runtime) throw runtime;
        if (thrown != null) throw new RuntimeException("main-thread test callback failed", thrown);
        return value.get();
    }

    private static final class Harness {
        final EditorSessionManager manager;
        final ControlledInputConnection connection;
        final boolean sensitive;
        final List<EditorTransactionAudit> audits = new ArrayList<>();
        EditorSessionSnapshot snapshot;
        EditorInfo authorityInfo;
        InputConnection authorityConnection;
        int evidenceReads;
        int undoEvidenceReads;
        EditorSessionManager.UndoEvidenceRequest lastUndoEvidenceRequest;

        private Harness(String text, int cursor, int inputType, int imeOptions, boolean sensitive) {
            this(text, cursor, cursor, inputType, imeOptions, sensitive);
        }

        private Harness(
                String text,
                int selectionStart,
                int selectionEnd,
                int inputType,
                int imeOptions,
                boolean sensitive) {
            this.sensitive = sensitive;
            manager = new EditorSessionManager(
                    () -> {},
                    () -> 1L,
                    () -> "instrumented-id",
                    failure -> {},
                    audits::add);
            connection = ControlledInputConnection.create(text, selectionStart, selectionEnd);
            connection.manager = manager;
            authorityInfo = info(1, selectionStart, selectionEnd, inputType, imeOptions);
            authorityConnection = connection;
            manager.onStartInput(authorityInfo, connection);

            EditorSessionManager.CaptureResult captured = manager.captureFromEvidence(
                    connection,
                    sensitive ? "" : connection.selected(),
                    sensitive ? "" : captureBefore(),
                    sensitive ? "" : captureAfter());
            assertTrue(captured instanceof EditorSessionManager.Captured);
            snapshot = ((EditorSessionManager.Captured) captured).snapshot();
        }

        static Harness text(String text, int cursor, int imeOptions) {
            return new Harness(
                    text, cursor, InputType.TYPE_CLASS_TEXT, imeOptions, false);
        }

        static Harness selected(String text, int start, int end) {
            return new Harness(
                    text,
                    start,
                    end,
                    InputType.TYPE_CLASS_TEXT,
                    EditorInfo.IME_ACTION_NONE,
                    false);
        }

        static Harness sensitive(String text, int cursor) {
            return new Harness(
                    text,
                    cursor,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    EditorInfo.IME_ACTION_NONE,
                    true);
        }

        EditorTransactionResult apply(EditorOperation operation) {
            return manager.apply(
                    snapshot,
                    operation,
                    () -> new EditorSessionManager.LiveAuthority(
                            authorityInfo, authorityConnection),
                    (authorized, request) -> {
                        evidenceReads++;
                        if (authorized != authorityConnection
                                || !(authorized instanceof ControlledInputConnection current)) {
                            return new EditorSessionManager.EvidenceUnavailable();
                        }
                        return new EditorSessionManager.CurrentEvidence(
                                true,
                                current.selectionStart(),
                                current.selectionEnd(),
                                true,
                                current.selected(),
                                true,
                                captureBefore(request.beforeUtf16Units()),
                                true,
                                captureAfter(request.afterUtf16Units()));
                    });
        }

        TransactionReceipt applyWithReceipt(EditorOperation operation) {
            return applyWithReceipt(operation, new CommitRecord.RawTranscript.Absent());
        }

        TransactionReceipt applyWithReceipt(
                EditorOperation operation, CommitRecord.RawTranscript rawTranscript) {
            return manager.applyWithReceipt(
                    snapshot,
                    operation,
                    new CommitRecordRequest.Requested(rawTranscript),
                    () -> new EditorSessionManager.LiveAuthority(
                            authorityInfo, authorityConnection),
                    (authorized, request) -> {
                        evidenceReads++;
                        if (authorized != authorityConnection
                                || !(authorized instanceof ControlledInputConnection current)) {
                            return new EditorSessionManager.EvidenceUnavailable();
                        }
                        return new EditorSessionManager.CurrentEvidence(
                                true,
                                current.selectionStart(),
                                current.selectionEnd(),
                                true,
                                current.selected(),
                                true,
                                captureBefore(request.beforeUtf16Units()),
                                true,
                                captureAfter(request.afterUtf16Units()));
                    });
        }

        EditorTransactionResult undo(String commitId) {
            return manager.undoCommit(
                    commitId,
                    snapshot,
                    () -> new EditorSessionManager.LiveAuthority(
                            authorityInfo, authorityConnection),
                    this::undoEvidence);
        }

        EditorTransactionResult restoreRaw(String commitId) {
            return manager.restoreRawCommit(
                    commitId,
                    snapshot,
                    () -> new EditorSessionManager.LiveAuthority(
                            authorityInfo, authorityConnection),
                    this::undoEvidence);
        }

        private EditorSessionManager.UndoEvidenceReadResult undoEvidence(
                InputConnection authorized,
                EditorSessionManager.UndoEvidenceRequest request) {
            undoEvidenceReads++;
            lastUndoEvidenceRequest = request;
            if (authorized != authorityConnection
                    || !(authorized instanceof ControlledInputConnection current)) {
                return new EditorSessionManager.UndoEvidenceUnavailable();
            }
            int selectionStart = current.selectionStart();
            int selectionEnd = current.selectionEnd();
            CharSequence selected = current.getSelectedText(0);
            CharSequence before = current.getTextBeforeCursor(request.beforeUtf16Units(), 0);
            CharSequence after = current.getTextAfterCursor(request.afterUtf16Units(), 0);
            return new EditorSessionManager.UndoEvidence(
                    selectionStart >= 0 && selectionEnd >= 0,
                    selectionStart,
                    selectionEnd,
                    true,
                    selected == null ? "" : selected,
                    before != null,
                    before,
                    after != null,
                    after);
        }

        void restart() {
            authorityInfo.initialSelStart = connection.selectionStart();
            authorityInfo.initialSelEnd = connection.selectionEnd();
            manager.onStartInput(authorityInfo, connection);
            recapture();
        }

        void recapture() {
            EditorSessionManager.CaptureResult captured = manager.captureFromEvidence(
                    connection,
                    sensitive ? "" : connection.selected(),
                    sensitive ? "" : captureBefore(),
                    sensitive ? "" : captureAfter());
            assertTrue(captured instanceof EditorSessionManager.Captured);
            snapshot = ((EditorSessionManager.Captured) captured).snapshot();
        }

        private String captureBefore() {
            return captureBefore(EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS);
        }

        private String captureBefore(int limit) {
            String before = connection.before();
            if (limit == 0) return "";
            if (before.length() <= limit) return before;
            int start = before.length() - limit;
            if (Character.isLowSurrogate(before.charAt(start))) start++;
            return before.substring(start);
        }

        private String captureAfter() {
            return captureAfter(EditorSessionLimits.MAX_SURROUNDING_INPUT_UTF16_UNITS);
        }

        private String captureAfter(int limit) {
            String after = connection.after();
            if (limit == 0) return "";
            if (after.length() <= limit) return after;
            int end = limit;
            if (Character.isHighSurrogate(after.charAt(end - 1))) end--;
            return after.substring(0, end);
        }

        void close() {
            manager.close();
        }
    }

    private static final class TestInputMethodService
            implements EditorSessionManager.KeyboardHost {
        private final EditorInfo editorInfo;
        private final InputConnection connection;

        private TestInputMethodService(EditorInfo editorInfo, InputConnection connection) {
            this.editorInfo = editorInfo;
            this.connection = connection;
        }

        @Override
        public EditorInfo currentEditorInfo() {
            return editorInfo;
        }

        @Override
        public InputConnection currentInputConnection() {
            return connection;
        }
    }

    private static final class ControlledInputConnection extends BaseInputConnection {
        static final String HOSTILE_EXCEPTION_TEXT = "HOSTILE_EDITOR_PLAINTEXT";

        private final Editable editable;
        EditorSessionManager manager;
        Runnable onBeginBatch = () -> {};
        boolean commitThrows;
        boolean deleteThrows;
        boolean actionThrows;
        boolean setCompositionThrows;
        boolean finishCompositionThrows;
        int rejectCommitCall = -1;
        int beginCalls;
        int endCalls;
        int commitCalls;
        int deleteCalls;
        int actionCalls;
        int setCompositionCalls;
        int finishCompositionCalls;
        int plaintextGetterCalls;
        int extractedTextCalls;
        int lastActionId = Integer.MIN_VALUE;
        int lastDeleteBeforeCodePoints = Integer.MIN_VALUE;
        boolean insideFrameworkMutator;

        private ControlledInputConnection(
                View target, String text, int selectionStart, int selectionEnd) {
            super(target, true);
            editable = new SpannableStringBuilder(text);
            Selection.setSelection(editable, selectionStart, selectionEnd);
        }

        static ControlledInputConnection create(String text, int cursor) {
            return create(text, cursor, cursor);
        }

        static ControlledInputConnection create(String text, int selectionStart, int selectionEnd) {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            return new ControlledInputConnection(
                    new View(context), text, selectionStart, selectionEnd);
        }

        @Override
        public Editable getEditable() {
            return editable;
        }

        @Override
        public boolean beginBatchEdit() {
            if (!insideFrameworkMutator) {
                beginCalls++;
                onBeginBatch.run();
            }
            return true;
        }

        @Override
        public boolean endBatchEdit() {
            if (!insideFrameworkMutator) endCalls++;
            return true;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            commitCalls++;
            if (commitCalls == rejectCommitCall) return false;
            if (commitThrows) throw new IllegalStateException(HOSTILE_EXCEPTION_TEXT);
            insideFrameworkMutator = true;
            try {
                boolean result = super.commitText(text, newCursorPosition);
                synchronizeSelection();
                return result;
            } finally {
                insideFrameworkMutator = false;
            }
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            deleteCalls++;
            lastDeleteBeforeCodePoints = beforeLength;
            if (deleteThrows) throw new IllegalStateException(HOSTILE_EXCEPTION_TEXT);
            insideFrameworkMutator = true;
            try {
                boolean result = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
                synchronizeSelection();
                return result;
            } finally {
                insideFrameworkMutator = false;
            }
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            actionCalls++;
            lastActionId = actionCode;
            if (actionThrows) throw new IllegalStateException(HOSTILE_EXCEPTION_TEXT);
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            setCompositionCalls++;
            if (setCompositionThrows) {
                throw new IllegalStateException(HOSTILE_EXCEPTION_TEXT);
            }
            insideFrameworkMutator = true;
            try {
                boolean result = super.setComposingText(text, newCursorPosition);
                synchronizeSelection();
                return result;
            } finally {
                insideFrameworkMutator = false;
            }
        }

        @Override
        public boolean finishComposingText() {
            finishCompositionCalls++;
            if (finishCompositionThrows) {
                throw new IllegalStateException(HOSTILE_EXCEPTION_TEXT);
            }
            insideFrameworkMutator = true;
            try {
                return super.finishComposingText();
            } finally {
                insideFrameworkMutator = false;
            }
        }

        @Override
        public CharSequence getTextBeforeCursor(int length, int flags) {
            plaintextGetterCalls++;
            return super.getTextBeforeCursor(length, flags);
        }

        @Override
        public CharSequence getTextAfterCursor(int length, int flags) {
            plaintextGetterCalls++;
            return super.getTextAfterCursor(length, flags);
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            plaintextGetterCalls++;
            return super.getSelectedText(flags);
        }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            extractedTextCalls++;
            ExtractedText extracted = new ExtractedText();
            extracted.startOffset = 0;
            extracted.partialStartOffset = -1;
            extracted.partialEndOffset = -1;
            extracted.selectionStart = selectionStart();
            extracted.selectionEnd = selectionEnd();
            return extracted;
        }

        String text() {
            return editable.toString();
        }

        int cursor() {
            return selectionStart();
        }

        int selectionStart() {
            return Selection.getSelectionStart(editable);
        }

        int selectionEnd() {
            return Selection.getSelectionEnd(editable);
        }

        String selected() {
            int start = Selection.getSelectionStart(editable);
            int end = Selection.getSelectionEnd(editable);
            return editable.subSequence(Math.min(start, end), Math.max(start, end)).toString();
        }

        String before() {
            int start = Selection.getSelectionStart(editable);
            int end = Selection.getSelectionEnd(editable);
            return editable.subSequence(0, Math.min(start, end)).toString();
        }

        String after() {
            int start = Selection.getSelectionStart(editable);
            int end = Selection.getSelectionEnd(editable);
            return editable.subSequence(Math.max(start, end), editable.length())
                    .toString();
        }

        void setSelectionForTest(int start, int end, boolean notifyManager) {
            Selection.setSelection(editable, start, end);
            if (notifyManager && manager != null) manager.onSelectionChanged(start, end);
        }

        void replaceTextForTest(int start, int end, String replacement) {
            int selectionStart = Selection.getSelectionStart(editable);
            int selectionEnd = Selection.getSelectionEnd(editable);
            editable.replace(start, end, replacement);
            Selection.setSelection(editable, selectionStart, selectionEnd);
        }

        int totalMutatorCalls() {
            return commitCalls + deleteCalls + actionCalls
                    + setCompositionCalls + finishCompositionCalls;
        }

        int composingStart() {
            return android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editable);
        }

        int composingEnd() {
            return android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(editable);
        }

        private void synchronizeSelection() {
            if (manager != null) manager.onSelectionChanged(cursor(), cursor());
        }

        @Override
        public String toString() {
            return "ControlledInputConnection{<redacted>}";
        }
    }
}
