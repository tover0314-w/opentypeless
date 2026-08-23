package com.opentypeless.android.recognition;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SessionGenerationTokenTest {
    @Test
    public void onlyFirstTerminalCanFinishSession() {
        SessionGenerationToken token = new SessionGenerationToken();
        long run = token.next();

        assertTrue(token.finish(run));
        assertFalse(token.finish(run));
        assertFalse(token.isCurrent(run));
    }

    @Test
    public void cancellationInvalidatesLateTerminal() {
        SessionGenerationToken token = new SessionGenerationToken();
        long run = token.next();

        token.invalidate();

        assertFalse(token.finish(run));
    }

    @Test
    public void queuedCancellationCannotOwnAReplacementSession() {
        SessionGenerationToken token = new SessionGenerationToken();
        token.next();
        long cancellation = token.invalidateAndGet();

        assertTrue(token.isCurrent(cancellation));
        token.next();

        assertFalse(token.isCurrent(cancellation));
    }
}
