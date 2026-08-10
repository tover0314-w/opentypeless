package com.opentypeless.android.ime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.speech.SpeechRecognizer;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

public final class VoicePipelineStateTest {
    @Test
    public void staleCancellationCannotResetAnImmediatelyRestartedRun() {
        Object cancelled = new Object();
        Object restarted = new Object();
        AtomicReference<Object> active = new AtomicReference<>(restarted);
        AtomicReference<VoicePipeline.State> state =
                new AtomicReference<>(VoicePipeline.State.RECORDING);

        assertFalse(VoicePipeline.clearCancelledRun(active, state, cancelled));
        assertSame(restarted, active.get());
        assertSame(VoicePipeline.State.RECORDING, state.get());
    }

    @Test
    public void currentCancellationReturnsPipelineToIdle() {
        Object cancelled = new Object();
        AtomicReference<Object> active = new AtomicReference<>(cancelled);
        AtomicReference<VoicePipeline.State> state =
                new AtomicReference<>(VoicePipeline.State.RECORDING);

        assertTrue(VoicePipeline.clearCancelledRun(active, state, cancelled));
        assertSame(null, active.get());
        assertSame(VoicePipeline.State.IDLE, state.get());
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
                false));
        assertFalse(VoicePipeline.shouldFallbackToLocal(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                false,
                true,
                true,
                false));
        assertFalse(VoicePipeline.shouldFallbackToLocal(
                SpeechRecognizer.ERROR_AUDIO,
                true,
                true,
                true,
                false));
        assertFalse(VoicePipeline.shouldFallbackToLocal(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                true,
                true,
                false,
                false));
        assertFalse(VoicePipeline.shouldFallbackToLocal(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                true,
                true,
                true,
                true));
    }
}
