package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

public final class RecognitionAccessControllerTest {
    @Test
    public void authorizationRequiresOptInAllowlistAndVerifiedIdentity() {
        RecognitionAccessController controller =
                new RecognitionAccessController(2, 1_000, () -> 0L);

        assertEquals(
                RecognitionAccessController.Decision.DISABLED,
                controller.authorize(false, true, true, "caller"));
        assertEquals(
                RecognitionAccessController.Decision.CALLER_NOT_ALLOWED,
                controller.authorize(true, false, true, "caller"));
        assertEquals(
                RecognitionAccessController.Decision.CALLER_NOT_ALLOWED,
                controller.authorize(true, true, false, "caller"));
        assertEquals(
                RecognitionAccessController.Decision.ALLOWED,
                controller.authorize(true, true, true, "caller"));
    }

    @Test
    public void rateLimitIsPerCallerAndExpiresAtWindowBoundary() {
        AtomicLong clock = new AtomicLong(100);
        RecognitionAccessController controller =
                new RecognitionAccessController(2, 1_000, clock::get);

        assertEquals(
                RecognitionAccessController.Decision.ALLOWED,
                controller.authorize(true, true, true, "one"));
        assertEquals(
                RecognitionAccessController.Decision.ALLOWED,
                controller.authorize(true, true, true, "one"));
        assertEquals(
                RecognitionAccessController.Decision.RATE_LIMITED,
                controller.authorize(true, true, true, "one"));
        assertEquals(
                RecognitionAccessController.Decision.ALLOWED,
                controller.authorize(true, true, true, "two"));

        clock.set(1_100);
        assertEquals(
                RecognitionAccessController.Decision.ALLOWED,
                controller.authorize(true, true, true, "one"));
    }

    @Test
    public void monotonicClockRewindDoesNotPermanentlyLockCallerOut() {
        AtomicLong clock = new AtomicLong(500);
        RecognitionAccessController controller =
                new RecognitionAccessController(1, 1_000, clock::get);
        controller.authorize(true, true, true, "caller");

        clock.set(100);

        assertEquals(
                RecognitionAccessController.Decision.ALLOWED,
                controller.authorize(true, true, true, "caller"));
    }
}
