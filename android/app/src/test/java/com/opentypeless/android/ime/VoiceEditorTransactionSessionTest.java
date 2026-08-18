package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.editor.CompositionConflictPolicy;
import com.opentypeless.android.editor.CompositionCoordinator;
import com.opentypeless.android.editor.CompositionState;
import com.opentypeless.android.editor.EditorSessionSnapshot;
import com.opentypeless.android.editor.TextRange;
import org.junit.Test;

public final class VoiceEditorTransactionSessionTest {
    @Test
    public void partialsUseStrictGenerationSequenceAndOwnedSelectionSet() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession session =
                acquire(7L, snapshot(new TextRange(3, 3), ""), coordinator);

        assertFalse(session.acceptsPartial(6L, 1L));
        assertTrue(session.markReady(7L));
        assertTrue(session.acceptsPartial(7L, 1L));
        session.prepareComposition(1L, "one");
        assertTrue(coordinator.observe().state() instanceof CompositionState.VoicePartial);
        assertTrue(session.acceptsSelection(6, 6, -1, -1));

        session.prepareComposition(2L, "longer");
        assertTrue(session.acceptsSelection(6, 6, 3, 6));
        assertTrue(session.acceptsSelection(9, 9, 3, 9));
        assertFalse(session.acceptsSelection(8, 8, -1, -1));
        assertFalse(session.acceptsPartial(7L, 2L));
        assertTrue(session.acceptsPartial(7L, 3L));
    }

    @Test
    public void terminalGateDropsEvenLargerLatePartialAndKeepsFinalCallbackOwned() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession session =
                acquire(9L, snapshot(new TextRange(4, 4), ""), coordinator);
        assertTrue(session.markReady(9L));
        session.prepareComposition(4L, "draft");

        assertTrue(session.beginTerminal(9L));
        assertTrue(session.beginFinalizing());
        assertTrue(coordinator.observe().state() instanceof CompositionState.VoiceFinalizing);
        assertFalse(session.acceptsPartial(9L, Long.MAX_VALUE));
        assertFalse(session.beginTerminal(9L));
        assertTrue(session.acceptsSelection(9, 9, -1, -1));
        assertFalse(session.toString().contains("draft"));
        assertTrue(session.completeCoordinatorAfterCommit());
        assertTrue(coordinator.observe().state() instanceof CompositionState.Idle);
    }

    @Test
    public void selectedFinalRegistersOnlyItsExactCollapsedTarget() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession session =
                acquire(11L, snapshot(new TextRange(8, 5), "old"), coordinator);

        assertTrue(session.acceptsSelection(8, 5, -1, -1));
        assertTrue(session.markReady(11L));
        assertTrue(session.beginTerminal(11L));
        assertTrue(session.beginFinalizing());
        session.prepareFinalSelection("new text");
        assertTrue(session.acceptsSelection(13, 13, -1, -1));
        assertFalse(session.acceptsSelection(12, 12, -1, -1));
        assertTrue(session.completeCoordinatorAfterCommit());
    }

    @Test
    public void revisionOverflowAndCloseFailClosedWithoutRetainingDraftOrOldSelections() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession session =
                acquire(13L, snapshot(new TextRange(0, 0), ""), coordinator);
        assertTrue(session.markReady(13L));
        for (int length = 1; length <= 9; length++) {
            session.prepareComposition(length, "x".repeat(length));
        }
        assertFalse(session.acceptsSelection(1, 1, -1, -1));
        assertTrue(session.acceptsSelection(9, 9, -1, -1));

        session.revision = Long.MAX_VALUE;
        try {
            session.prepareComposition(10L, "must-not-be-retained");
            throw new AssertionError("expected revision exhaustion");
        } catch (ArithmeticException expected) {
            assertFalse(session.toString().contains("must-not-be-retained"));
        }

        assertTrue(session.cancelCoordinatorAfterCleanup());
        session.close();
        assertFalse(session.acceptsPartial(13L, Long.MAX_VALUE));
        assertFalse(session.acceptsSelection(9, 9, -1, -1));
        assertTrue(session.compositionText.isEmpty());
    }

    @Test
    public void oneCoordinatorRejectsASecondVoiceOwnerUntilTheFirstIsReleased() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession first =
                acquire(17L, snapshot(new TextRange(1, 1), ""), coordinator);

        assertTrue(coordinator.observe().state() instanceof CompositionState.VoicePreparing);
        assertTrue(OpenTypelessImeService.VoiceTransactionSession.acquire(
                18L, snapshot(new TextRange(2, 2), ""), coordinator) == null);
        assertTrue(first.cancelCoordinatorAfterCleanup());

        OpenTypelessImeService.VoiceTransactionSession second =
                acquire(18L, snapshot(new TextRange(2, 2), ""), coordinator);
        assertTrue(second.markReady(18L));
        assertTrue(coordinator.observe().state() instanceof CompositionState.VoiceListening);
        assertTrue(second.cancelCoordinatorAfterCleanup());
    }

    @Test
    public void errorPreserveAndLifecycleRevokeBothReleaseTheExactVoiceGeneration() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession preserving =
                acquire(21L, snapshot(new TextRange(0, 0), ""), coordinator);
        assertTrue(preserving.markReady(21L));
        preserving.prepareComposition(1L, "visible");
        assertTrue(preserving.beginPreserving());
        assertTrue(preserving.completeCoordinatorAfterCommit());
        assertTrue(coordinator.observe().state() instanceof CompositionState.Idle);

        OpenTypelessImeService.VoiceTransactionSession revoked =
                acquire(22L, snapshot(new TextRange(0, 0), ""), coordinator);
        assertTrue(revoked.releaseAfterEditorLifecycle());
        assertTrue(coordinator.observe().state() instanceof CompositionState.Idle);
        assertFalse(revoked.acceptsPartial(22L, 1L));
    }

    @Test
    public void keyboardPreemptionCancelsPreparingVoiceThenReleasesLatinLeaseAfterTheKey() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession session =
                acquire(23L, snapshot(new TextRange(2, 2), ""), coordinator);

        OpenTypelessImeService.VoiceTransactionSession.KeyboardPreemption preemption =
                session.beginKeyboardPreemption(CompositionConflictPolicy.defaults(), false);
        assertTrue(preemption != null);
        assertEquals(
                CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT,
                preemption.directive());
        assertFalse(preemption.routeLateResult());
        assertEquals(
                CompositionCoordinator.ObservationPhase.PREEMPT_PENDING,
                coordinator.observe().phase());
        assertFalse(session.acceptsPartial(23L, 1L));

        assertTrue(session.finishKeyboardRelease(
                preemption, CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED));
        assertTrue(coordinator.observe().state() instanceof CompositionState.LatinComposing);
        assertTrue(session.keyboardPreemptionActive());
        assertTrue(session.finishKeyboardEvent(preemption, true));
        assertTrue(coordinator.observe().state() instanceof CompositionState.Idle);
        assertTrue(session.coordinatorReleased());
    }

    @Test
    public void visiblePartialUsesFrozenCommitOrCancelPolicyAndNeverAcceptsAnotherPartial() {
        CompositionCoordinator commitCoordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession committing =
                acquire(24L, snapshot(new TextRange(0, 0), ""), commitCoordinator);
        assertTrue(committing.markReady(24L));
        committing.prepareComposition(1L, "visible");
        OpenTypelessImeService.VoiceTransactionSession.KeyboardPreemption commit =
                committing.beginKeyboardPreemption(CompositionConflictPolicy.defaults(), false);
        assertTrue(commit != null);
        assertEquals(
                CompositionCoordinator.ReleaseDirective.COMMIT_CURRENT,
                commit.directive());
        assertFalse(committing.acceptsPartial(24L, 2L));
        assertTrue(committing.finishKeyboardRelease(
                commit, CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED));
        assertTrue(committing.finishKeyboardEvent(commit, false));

        CompositionCoordinator cancelCoordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession cancelling =
                acquire(25L, snapshot(new TextRange(0, 0), ""), cancelCoordinator);
        assertTrue(cancelling.markReady(25L));
        cancelling.prepareComposition(1L, "visible");
        CompositionConflictPolicy cancelPolicy = new CompositionConflictPolicy(
                CompositionConflictPolicy.RimeToVoice.COMMIT_RIME,
                CompositionConflictPolicy.VoicePartialToKeyboard.CANCEL_VOICE,
                CompositionConflictPolicy.ActionToVoice.PRESERVE_RESULT_PANEL);
        OpenTypelessImeService.VoiceTransactionSession.KeyboardPreemption cancel =
                cancelling.beginKeyboardPreemption(cancelPolicy, false);
        assertTrue(cancel != null);
        assertEquals(
                CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT,
                cancel.directive());
        long cancellationRevision = cancelling.prepareKeyboardCancellation(cancel);
        assertEquals(2L, cancellationRevision);
        cancelling.completeKeyboardCancellation(cancel, cancellationRevision);
        assertTrue(cancelling.finishKeyboardRelease(
                cancel, CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED));
        assertTrue(cancelling.finishKeyboardEvent(cancel, true));
        assertTrue(cancelCoordinator.observe().state() instanceof CompositionState.Idle);
    }

    @Test
    public void awaitingFinalRoutesTheLateResultAndClaimsTheFinalCallbackOnlyOnce() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession session =
                acquire(26L, snapshot(new TextRange(0, 0), ""), coordinator);
        assertTrue(session.markReady(26L));
        session.prepareComposition(1L, "visible");
        OpenTypelessImeService.VoiceTransactionSession.KeyboardPreemption preemption =
                session.beginKeyboardPreemption(CompositionConflictPolicy.defaults(), true);
        assertTrue(preemption != null);
        assertEquals(
                CompositionCoordinator.ReleaseDirective.COMMIT_CURRENT,
                preemption.directive());
        assertTrue(preemption.routeLateResult());
        assertFalse(session.acceptsPartial(26L, Long.MAX_VALUE));
        assertTrue(session.finishKeyboardRelease(
                preemption, CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED));
        assertTrue(session.finishKeyboardEvent(preemption, true));

        CompositionCoordinator finalCoordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession finalSession =
                acquire(27L, snapshot(new TextRange(0, 0), ""), finalCoordinator);
        assertTrue(finalSession.markReady(27L));
        assertTrue(finalSession.beginTerminal(27L));
        assertTrue(finalSession.beginFinalizing());
        assertFalse(finalSession.beginTerminal(27L));
    }

    @Test
    public void uncertainKeyboardReleaseStaysPendingUntilEditorLifecycleRevokesIt() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession session =
                acquire(28L, snapshot(new TextRange(0, 0), ""), coordinator);
        assertTrue(session.markReady(28L));
        session.prepareComposition(1L, "visible");
        OpenTypelessImeService.VoiceTransactionSession.KeyboardPreemption preemption =
                session.beginKeyboardPreemption(CompositionConflictPolicy.defaults(), false);
        assertTrue(preemption != null);

        assertFalse(session.finishKeyboardRelease(
                preemption, CompositionCoordinator.ReleaseResolution.UNCERTAIN));
        assertEquals(
                CompositionCoordinator.ObservationPhase.PREEMPT_PENDING,
                coordinator.observe().phase());
        assertFalse(session.keyboardPreemptionActive());
        assertTrue(session.releaseAfterEditorLifecycle());
        assertTrue(coordinator.observe().state() instanceof CompositionState.Idle);
        assertTrue(session.coordinatorReleased());
    }

    private static OpenTypelessImeService.VoiceTransactionSession acquire(
            long generation,
            EditorSessionSnapshot snapshot,
            CompositionCoordinator coordinator) {
        OpenTypelessImeService.VoiceTransactionSession session =
                OpenTypelessImeService.VoiceTransactionSession.acquire(
                        generation, snapshot, coordinator);
        if (session == null) throw new AssertionError("expected Voice acquisition");
        return session;
    }

    private static EditorSessionSnapshot snapshot(TextRange selection, String selected) {
        return EditorSessionSnapshot.capture(
                1L,
                1L,
                "app",
                1,
                FieldKind.GENERAL,
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_NONE,
                selection,
                selected,
                "pre",
                "post",
                true,
                false,
                1L);
    }
}
