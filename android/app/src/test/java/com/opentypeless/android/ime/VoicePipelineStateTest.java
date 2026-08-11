package com.opentypeless.android.ime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.speech.SpeechRecognizer;

import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.data.CorrectionRule;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.personalization.ProcessingResult;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class VoicePipelineStateTest {
    @Test
    public void staleCancellationCannotResetAnImmediatelyRestartedRun() {
        Object cancelled = new Object();
        Object restarted = new Object();
        AtomicReference<Object> active = new AtomicReference<>(restarted);
        AtomicReference<VoicePipeline.State> state =
                new AtomicReference<>(VoicePipeline.State.RECORDING);

        assertFalse(VoicePipeline.clearCancelledRun(new Object(), active, state, cancelled));
        assertSame(restarted, active.get());
        assertSame(VoicePipeline.State.RECORDING, state.get());
    }

    @Test
    public void currentCancellationReturnsPipelineToIdle() {
        Object cancelled = new Object();
        AtomicReference<Object> active = new AtomicReference<>(cancelled);
        AtomicReference<VoicePipeline.State> state =
                new AtomicReference<>(VoicePipeline.State.RECORDING);

        assertTrue(VoicePipeline.clearCancelledRun(new Object(), active, state, cancelled));
        assertSame(null, active.get());
        assertSame(VoicePipeline.State.IDLE, state.get());
    }

    @Test
    public void cancelledRunCannotResetAStartWaitingOnTheSameLifecycleLock() throws Exception {
        Object lock = new Object();
        Object cancelled = new Object();
        Object restarted = new Object();
        AtomicReference<Object> active = new AtomicReference<>(cancelled);
        AtomicReference<VoicePipeline.State> state =
                new AtomicReference<>(VoicePipeline.State.TRANSCRIBING);
        AtomicBoolean cleared = new AtomicBoolean(true);
        CountDownLatch attempting = new CountDownLatch(1);
        Thread finisher;

        synchronized (lock) {
            finisher = new Thread(() -> {
                attempting.countDown();
                cleared.set(VoicePipeline.clearCancelledRun(
                        lock, active, state, cancelled));
            });
            finisher.start();
            assertTrue(attempting.await(1, TimeUnit.SECONDS));
            active.set(restarted);
            state.set(VoicePipeline.State.RECORDING);
        }
        finisher.join(1_000L);

        assertFalse(finisher.isAlive());
        assertFalse(cleared.get());
        assertSame(restarted, active.get());
        assertSame(VoicePipeline.State.RECORDING, state.get());
    }

    @Test
    public void unsafeSelectedEditMustPreserveTheOriginalSelection() {
        assertSame(
                VoicePipeline.AiCandidateDisposition.PRESERVE_SELECTION,
                VoicePipeline.aiCandidateDisposition(false, true));
        assertSame(
                VoicePipeline.AiCandidateDisposition.INSERT_EXACT_TRANSCRIPT,
                VoicePipeline.aiCandidateDisposition(false, false));
        assertSame(
                VoicePipeline.AiCandidateDisposition.ACCEPT,
                VoicePipeline.aiCandidateDisposition(true, true));
    }

    @Test
    public void fallsBackOnlyForGrantedVendorPermissionFailureWithInstalledModel() {
        assertTrue(VoicePipeline.shouldFallbackToLocal(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                true,
                true,
                true,
                false,
                false));
        assertFalse(VoicePipeline.shouldFallbackToLocal(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                false,
                true,
                true,
                false,
                false));
        assertFalse(VoicePipeline.shouldFallbackToLocal(
                SpeechRecognizer.ERROR_AUDIO,
                true,
                true,
                true,
                false,
                false));
        assertFalse(VoicePipeline.shouldFallbackToLocal(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                true,
                true,
                false,
                false,
                false));
        assertFalse(VoicePipeline.shouldFallbackToLocal(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                true,
                true,
                true,
                true,
                false));
        assertFalse(VoicePipeline.shouldFallbackToLocal(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                true,
                true,
                true,
                false,
                true));
    }

    @Test
    public void anyTerminalFailureKeepsVisibleDictationUnlessTheUserExplicitlyCancelled() {
        assertTrue(VoicePipeline.shouldRecoverVisiblePartial(false, "已经识别的文字"));
        assertFalse(VoicePipeline.shouldRecoverVisiblePartial(false, "  "));
        assertFalse(VoicePipeline.shouldRecoverVisiblePartial(true, "帮我改写"));
    }

    @Test
    public void continuousSystemSegmentsUseNaturalChineseAndEnglishBoundaries() {
        assertEquals("第一段第二段", VoicePipeline.joinTranscriptSegments("第一段", "第二段"));
        assertEquals("hello world", VoicePipeline.joinTranscriptSegments("hello", "world"));
        assertEquals("第一段 OpenTypeless", VoicePipeline.joinTranscriptSegments(
                "第一段 ", "OpenTypeless"));
    }

    @Test
    public void systemFinalKeepsSafePunctuationFromTheLatestVisiblePartial() {
        assertEquals(
                "实时预览有标点，但是最终上屏连在一起，只剩下最后一个句号。",
                VoicePipeline.reconcileSystemFinal(
                        "",
                        "实时预览有标点但是最终上屏连在一起只剩下最后一个句号。",
                        "实时预览有标点，但是最终上屏连在一起，只剩下最后一个句号。",
                        FieldKind.LONG_TEXT));
        assertEquals(
                "第一段。第二段，也保留逗号。",
                VoicePipeline.reconcileSystemFinal(
                        "第一段。",
                        "第二段也保留逗号",
                        "第一段。第二段，也保留逗号。",
                        FieldKind.LONG_TEXT));
    }

    @Test
    public void systemFinalNeverRestoresPunctuationFromLexicallyStalePartial() {
        assertEquals(
                "会议改到周三。",
                VoicePipeline.reconcileSystemFinal(
                        "",
                        "会议改到周三",
                        "会议，改到周四。",
                        FieldKind.GENERAL));
        assertEquals(
                "小米15 输入法",
                VoicePipeline.reconcileSystemFinal(
                        "",
                        "小米15 输入法",
                        "小米15，输入法。",
                        FieldKind.SEARCH));
    }

    @Test
    public void accumulatedTranscriptLimitNeverSplitsASurrogatePairs() {
        assertEquals("A😀", VoicePipeline.limitCodePoints("A😀B", 2));
        assertEquals("A😀B", VoicePipeline.limitCodePoints("A😀B", 3));
        assertEquals("", VoicePipeline.limitCodePoints("A", 0));
    }

    @Test
    public void holdReleaseNeverCancelsAResultAlreadyQueuedForTheUiThread() {
        assertSame(
                OpenTypelessImeService.HoldReleaseAction.WAIT_FOR_RESULT,
                OpenTypelessImeService.holdReleaseAction(
                        VoicePipeline.State.IDLE, true, false));
        assertSame(
                OpenTypelessImeService.HoldReleaseAction.CANCEL_PREPARATION,
                OpenTypelessImeService.holdReleaseAction(
                        VoicePipeline.State.IDLE, true, true));
        assertSame(
                OpenTypelessImeService.HoldReleaseAction.STOP_AND_COMMIT,
                OpenTypelessImeService.holdReleaseAction(
                        VoicePipeline.State.RECORDING, true, false));
    }

    @Test
    public void releaseBeforeTrueMicrophoneReadyCancelsEarlyRecordingState() {
        assertSame(
                OpenTypelessImeService.HoldReleaseAction.CANCEL_PREPARATION,
                OpenTypelessImeService.holdReleaseAction(
                        VoicePipeline.State.RECORDING, true, true));
    }

    @Test
    public void readyOnlyActivatesTheCurrentUnfinishedVoiceTarget() {
        Object currentTarget = new Object();

        assertTrue(OpenTypelessImeService.shouldHandleSpeechReady(
                currentTarget, currentTarget, false));
        assertFalse(OpenTypelessImeService.shouldHandleSpeechReady(
                currentTarget, new Object(), false));
        assertFalse(OpenTypelessImeService.shouldHandleSpeechReady(
                currentTarget, currentTarget, true));
        assertFalse(OpenTypelessImeService.shouldHandleSpeechReady(
                null, null, false));
    }

    @Test
    public void staleQueuedResultCannotMutateAReplacementVoiceSession() {
        Object oldTarget = new Object();
        Object newTarget = new Object();
        AtomicBoolean mutated = new AtomicBoolean();

        assertFalse(OpenTypelessImeService.runIfCurrent(
                newTarget, oldTarget, () -> mutated.set(true)));
        assertFalse(mutated.get());

        assertTrue(OpenTypelessImeService.runIfCurrent(
                newTarget, newTarget, () -> mutated.set(true)));
        assertTrue(mutated.get());
    }

    @Test
    public void preparingHoldGestureKeepsItsTouchTargetUntilRelease() {
        assertTrue(OpenTypelessImeService.shouldEnableHoldKey(false, false, true));
        assertTrue(OpenTypelessImeService.shouldEnableHoldKey(false, true, false));
        assertTrue(OpenTypelessImeService.shouldEnableHoldKey(true, false, false));
        assertFalse(OpenTypelessImeService.shouldEnableHoldKey(false, false, false));
    }

    @Test
    public void serviceShutdownWaitsOnlyWhenAStoppedRunStillOwesAFinalResult() {
        assertTrue(OpenTypelessImeService.shouldDeferServiceShutdown(new Object()));
        assertFalse(OpenTypelessImeService.shouldDeferServiceShutdown(null));
    }

    @Test
    public void terminalArrivalBeatsTimeoutEvenBeforeItsMainThreadHandlerRuns() {
        OpenTypelessImeService.DetachedFinalizationGate gate =
                new OpenTypelessImeService.DetachedFinalizationGate();
        gate.begin();

        assertTrue(gate.terminalArrived());
        assertFalse(gate.claimTimeout());
        assertTrue(gate.claimTerminalHandler());
        assertFalse(gate.claimTerminalHandler());
    }

    @Test
    public void timeoutWinningMakesEveryLateTerminalANoOp() {
        OpenTypelessImeService.DetachedFinalizationGate gate =
                new OpenTypelessImeService.DetachedFinalizationGate();
        gate.begin();

        assertTrue(gate.claimTimeout());
        assertFalse(gate.terminalArrived());
        assertFalse(gate.claimTerminalHandler());
        assertFalse(gate.claimTimeout());
    }

    @Test
    public void detachedDiscardTombstoneSuppressesAQueuedTerminalResult() {
        Object target = new Object();
        AtomicBoolean ownerCancelled = new AtomicBoolean();
        OpenTypelessImeService.PendingDetachedSession<Object> session =
                new OpenTypelessImeService.PendingDetachedSession<>(
                        target, new Object(), () -> ownerCancelled.set(true));

        assertSame(target, session.pendingTarget());
        Runnable cancelOwner = session.discard();
        assertTrue(session.discarded());
        assertSame(null, session.pendingTarget());
        cancelOwner.run();
        assertTrue(ownerCancelled.get());

        session.complete();
        assertTrue(session.discarded());
    }

    @Test
    public void completedDetachedResultCanStillBeExplicitlyDiscardedWithoutRecancellingOwner() {
        Object target = new Object();
        AtomicBoolean ownerCancelled = new AtomicBoolean();
        OpenTypelessImeService.PendingDetachedSession<Object> session =
                new OpenTypelessImeService.PendingDetachedSession<>(
                        target, new Object(), () -> ownerCancelled.set(true));

        session.complete();
        assertSame(null, session.pendingTarget());
        assertSame(null, session.discard());
        assertTrue(session.discarded());
        assertFalse(ownerCancelled.get());
    }

    @Test
    public void brokenPersonalizationFallsBackToSuccessfulRawDictation() {
        String raw = "a ".repeat(100);
        PersonalizationSnapshot explosive = new PersonalizationSnapshot(
                List.of(),
                List.of(new CorrectionRule(
                        1L, "a", "x".repeat(1_000), "", 0, true)));

        ProcessingResult result = VoicePipeline.applyPersonalizationFailSafe(
                raw, explosive, false);

        assertEquals(raw, result.text());
        assertTrue(result.matchedTermIds().isEmpty());
        assertTrue(result.matchedCorrectionIds().isEmpty());
        assertThrows(IllegalArgumentException.class, () ->
                VoicePipeline.applyPersonalizationFailSafe(raw, explosive, true));
    }
}
