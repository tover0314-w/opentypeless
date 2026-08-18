package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

public final class RimeVoicePreemptionTest {
    @Test
    public void provenCommitPublishesOneVoiceOwnerThatCanBeClaimedOnce() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        CompositionCoordinator.Observation rime = acquireRime(coordinator);

        OpenTypelessImeService.RimeVoicePreemption preemption =
                OpenTypelessImeService.RimeVoicePreemption.begin(
                        coordinator, rime, CompositionConflictPolicy.defaults());

        assertTrue(preemption != null);
        assertEquals(
                CompositionCoordinator.ReleaseDirective.COMMIT_CURRENT,
                preemption.directive());
        assertEquals(
                CompositionCoordinator.ObservationPhase.PREEMPT_PENDING,
                coordinator.observe().phase());
        assertEquals(
                OpenTypelessImeService.RimeVoicePreemption.Finish.VOICE_ACQUIRED,
                preemption.finish(CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED));

        OpenTypelessImeService.VoiceTransactionSession voice =
                preemption.claimVoiceSession(9L, snapshot());
        assertTrue(voice != null);
        assertNull(preemption.claimVoiceSession(10L, snapshot()));
        assertTrue(voice.markReady(9L));
        assertTrue(coordinator.observe().state() instanceof CompositionState.VoiceListening);
    }

    @Test
    public void provenUnchangedRestoresExactRimeOwnerAndDoesNotPublishVoice() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        CompositionCoordinator.Observation rime = acquireRime(coordinator);
        CompositionConflictPolicy cancelPolicy = new CompositionConflictPolicy(
                CompositionConflictPolicy.RimeToVoice.CANCEL_RIME,
                CompositionConflictPolicy.VoicePartialToKeyboard.CANCEL_VOICE,
                CompositionConflictPolicy.ActionToVoice.PRESERVE_RESULT_PANEL);
        OpenTypelessImeService.RimeVoicePreemption preemption =
                OpenTypelessImeService.RimeVoicePreemption.begin(
                        coordinator, rime, cancelPolicy);

        assertTrue(preemption != null);
        assertEquals(
                CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT,
                preemption.directive());
        assertEquals(
                OpenTypelessImeService.RimeVoicePreemption.Finish.RIME_UNCHANGED,
                preemption.finish(CompositionCoordinator.ReleaseResolution.PROVEN_UNCHANGED));
        CompositionCoordinator.Observation restored = preemption.restoredRimeObservation();
        assertTrue(restored.state() instanceof CompositionState.RimeComposing);
        assertEquals(CompositionCoordinator.ObservationPhase.STABLE, restored.phase());
        assertNull(preemption.claimVoiceSession(11L, snapshot()));
    }

    @Test
    public void uncertainReleaseStaysPendingAndCannotBeClaimedOrRetried() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        OpenTypelessImeService.RimeVoicePreemption preemption =
                OpenTypelessImeService.RimeVoicePreemption.begin(
                        coordinator, acquireRime(coordinator), CompositionConflictPolicy.defaults());

        assertEquals(
                OpenTypelessImeService.RimeVoicePreemption.Finish.UNCERTAIN,
                preemption.finish(CompositionCoordinator.ReleaseResolution.UNCERTAIN));
        assertEquals(
                CompositionCoordinator.ObservationPhase.PREEMPT_PENDING,
                coordinator.observe().phase());
        assertNull(preemption.claimVoiceSession(12L, snapshot()));
        assertFalse(preemption.cancelUnclaimedVoice());
        try {
            preemption.finish(CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED);
            throw new AssertionError("expected terminal preemption");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    @Test
    public void unclaimedVoiceCanBeCancelledWithoutStartingRecognition() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        OpenTypelessImeService.RimeVoicePreemption preemption =
                OpenTypelessImeService.RimeVoicePreemption.begin(
                        coordinator, acquireRime(coordinator), CompositionConflictPolicy.defaults());
        assertEquals(
                OpenTypelessImeService.RimeVoicePreemption.Finish.VOICE_ACQUIRED,
                preemption.finish(CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED));

        assertTrue(preemption.cancelUnclaimedVoice());
        assertTrue(coordinator.observe().state() instanceof CompositionState.Idle);
        assertFalse(preemption.cancelUnclaimedVoice());
    }

    @Test
    public void idleForeignAndStaleObservationsCannotStartRimeVoiceHandoff() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        assertNull(OpenTypelessImeService.RimeVoicePreemption.begin(
                coordinator, coordinator.observe(), CompositionConflictPolicy.defaults()));

        CompositionCoordinator foreign = new CompositionCoordinator();
        CompositionCoordinator.Observation foreignRime = acquireRime(foreign);
        assertNull(OpenTypelessImeService.RimeVoicePreemption.begin(
                coordinator, foreignRime, CompositionConflictPolicy.defaults()));

        CompositionCoordinator.Observation stale = acquireRime(coordinator);
        assertEquals(
                CompositionCoordinator.Disposition.APPLIED,
                coordinator.cancel(stale).disposition());
        assertNull(OpenTypelessImeService.RimeVoicePreemption.begin(
                coordinator, stale, CompositionConflictPolicy.defaults()));
    }

    private static CompositionCoordinator.Observation acquireRime(
            CompositionCoordinator coordinator) {
        CompositionCoordinator.Transition acquired = coordinator.acquire(
                coordinator.observe(), new CompositionCoordinator.Acquisition.Rime(1L));
        if (acquired.disposition() != CompositionCoordinator.Disposition.APPLIED) {
            throw new AssertionError("expected Rime acquisition");
        }
        return acquired.after();
    }

    private static EditorSessionSnapshot snapshot() {
        return EditorSessionSnapshot.capture(
                1L,
                1L,
                "rime-voice",
                1,
                FieldKind.GENERAL,
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_NONE,
                new TextRange(0, 0),
                "",
                "pre",
                "post",
                true,
                false,
                1L);
    }
}
