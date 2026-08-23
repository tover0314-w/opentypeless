package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecognitionPreparationStateTest {
    @Test
    public void stopDuringPreparationInvalidatesWorkerAndNextSessionGetsNewToken() {
        RecognitionPreparationState state = new RecognitionPreparationState();
        long stale = state.begin();

        assertEquals(
                RecognitionPreparationState.StopAction.FAIL_PREPARATION,
                state.stop());
        assertFalse(state.beginPipeline(stale));
        long replacement = state.begin();
        assertNotEquals(stale, replacement);
        assertTrue(state.beginPipeline(replacement));
    }

    @Test
    public void stopWhileRunningPreservesSessionForFinalResult() {
        RecognitionPreparationState state = new RecognitionPreparationState();
        long token = state.begin();
        assertTrue(state.beginPipeline(token));

        assertEquals(
                RecognitionPreparationState.StopAction.STOP_PIPELINE,
                state.stop());
        assertTrue(state.isCurrent(token));
        assertTrue(state.finish(token));
        assertFalse(state.finish(token));
    }

    @Test
    public void cancelDropsLatePreparationAndDuplicateStartsAreBusy() {
        RecognitionPreparationState state = new RecognitionPreparationState();
        long token = state.begin();

        assertEquals(0L, state.begin());
        assertTrue(state.cancel());
        assertFalse(state.beginPipeline(token));
        assertFalse(state.cancel());
    }

    @Test
    public void shutdownPermanentlyRejectsPreparation() {
        RecognitionPreparationState state = new RecognitionPreparationState();
        long token = state.begin();

        assertTrue(state.shutdown());
        assertFalse(state.beginPipeline(token));
        assertEquals(0L, state.begin());
        assertFalse(state.shutdown());
        assertEquals(RecognitionPreparationState.Phase.SHUTDOWN, state.phase());
    }
}
