package com.opentypeless.android.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecordingSessionTest {
    @Test
    public void stopBeforeWorkerStartsCannotBeLost() {
        RecordingSession session = new RecordingSession();
        session.stop();
        assertFalse(session.isActive());
        assertFalse(session.isCancelled());
        assertEquals(RecordingSession.EndState.STOPPED, session.endState());
    }

    @Test
    public void cancellationDominatesStopAtAnyTime() {
        RecordingSession session = new RecordingSession();
        session.stop();
        session.cancel();
        assertFalse(session.isActive());
        assertTrue(session.isCancelled());
        assertEquals(RecordingSession.EndState.CANCELLED, session.endState());
    }
}
