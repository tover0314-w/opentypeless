package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.editor.CompositionConflictPolicy;
import com.opentypeless.android.editor.CompositionCoordinator;
import com.opentypeless.android.editor.CompositionState;
import com.opentypeless.android.editor.EditorSessionSnapshot;
import com.opentypeless.android.editor.TextRange;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public final class VoiceEditorTransactionSessionInstrumentedTest {
    @Test
    public void visiblePartialPreemptsToLatinAndOneKeyReleasesTheCoordinator() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession session =
                acquire(101L, coordinator);
        assertTrue(session.markReady(101L));
        session.prepareComposition(1L, "visible");

        OpenTypelessImeService.VoiceTransactionSession.KeyboardPreemption preemption =
                session.beginKeyboardPreemption(CompositionConflictPolicy.defaults(), false);
        assertTrue(preemption != null);
        assertTrue(session.finishKeyboardRelease(
                preemption, CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED));
        assertTrue(coordinator.observe().state() instanceof CompositionState.LatinComposing);

        assertTrue(session.finishKeyboardEvent(preemption, true));
        assertTrue(coordinator.observe().state() instanceof CompositionState.Idle);
        assertTrue(session.coordinatorReleased());
    }

    @Test
    public void finalPendingDropsLateResultAndUncertainReleaseFailsClosed() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession session =
                acquire(102L, coordinator);
        assertTrue(session.markReady(102L));
        session.prepareComposition(1L, "visible");

        OpenTypelessImeService.VoiceTransactionSession.KeyboardPreemption preemption =
                session.beginKeyboardPreemption(CompositionConflictPolicy.defaults(), true);
        assertTrue(preemption != null);
        assertFalse(preemption.routeLateResult());
        assertFalse(session.acceptsPartial(102L, Long.MAX_VALUE));
        assertFalse(session.finishKeyboardRelease(
                preemption, CompositionCoordinator.ReleaseResolution.UNCERTAIN));
        assertTrue(coordinator.observe().phase()
                == CompositionCoordinator.ObservationPhase.PREEMPT_PENDING);
        assertTrue(session.releaseAfterEditorLifecycle());
        assertTrue(coordinator.observe().state() instanceof CompositionState.Idle);

        CompositionCoordinator finalCoordinator = new CompositionCoordinator();
        OpenTypelessImeService.VoiceTransactionSession finalSession =
                acquire(103L, finalCoordinator);
        assertTrue(finalSession.beginTerminal(103L));
        assertFalse(finalSession.beginTerminal(103L));
    }

    @Test
    public void exactRimeOwnerCanHandOffToOneVoiceOwnerAfterReleaseProof() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        CompositionCoordinator.Transition rime = coordinator.acquire(
                coordinator.observe(), new CompositionCoordinator.Acquisition.Rime(1L));
        assertEquals(CompositionCoordinator.Disposition.APPLIED, rime.disposition());
        OpenTypelessImeService.RimeVoicePreemption preemption =
                OpenTypelessImeService.RimeVoicePreemption.begin(
                        coordinator, rime.after(), CompositionConflictPolicy.defaults());
        assertTrue(preemption != null);
        assertEquals(
                OpenTypelessImeService.RimeVoicePreemption.Finish.VOICE_ACQUIRED,
                preemption.finish(CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED));

        OpenTypelessImeService.VoiceTransactionSession voice =
                preemption.claimVoiceSession(104L, snapshot());
        assertTrue(voice != null);
        assertTrue(voice.markReady(104L));
        assertTrue(coordinator.observe().state() instanceof CompositionState.VoiceListening);
        assertFalse(preemption.cancelUnclaimedVoice());
    }

    @Test
    public void screenOffReceiverCancelsExactlyOnceAndIgnoresUnrelatedBroadcasts() {
        AtomicInteger cancellations = new AtomicInteger();
        BroadcastReceiver receiver = OpenTypelessImeService.createScreenOffReceiver(
                cancellations::incrementAndGet);

        receiver.onReceive(
                ApplicationProvider.getApplicationContext(),
                new Intent(Intent.ACTION_SCREEN_OFF));
        receiver.onReceive(
                ApplicationProvider.getApplicationContext(),
                new Intent(Intent.ACTION_USER_PRESENT));
        receiver.onReceive(ApplicationProvider.getApplicationContext(), null);

        assertEquals(1, cancellations.get());
    }

    private static OpenTypelessImeService.VoiceTransactionSession acquire(
            long generation, CompositionCoordinator coordinator) {
        OpenTypelessImeService.VoiceTransactionSession session =
                OpenTypelessImeService.VoiceTransactionSession.acquire(
                        generation, snapshot(), coordinator);
        if (session == null) throw new AssertionError("expected Voice acquisition");
        return session;
    }

    private static EditorSessionSnapshot snapshot() {
        return EditorSessionSnapshot.capture(
                1L,
                1L,
                "instrumentation",
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
