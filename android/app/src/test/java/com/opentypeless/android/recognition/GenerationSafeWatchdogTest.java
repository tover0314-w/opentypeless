package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class GenerationSafeWatchdogTest {
    @Test
    public void staleCallbackCannotStopReplacementSession() {
        FakeScheduler scheduler = new FakeScheduler();
        GenerationSafeWatchdog watchdog = new GenerationSafeWatchdog(scheduler);
        AtomicInteger timeouts = new AtomicInteger();

        watchdog.arm(1, 5_000, timeouts::incrementAndGet);
        Runnable stale = scheduler.lastPosted;
        watchdog.arm(2, 9_000, timeouts::incrementAndGet);

        stale.run();
        assertEquals(0, timeouts.get());
        scheduler.lastPosted.run();
        assertEquals(1, timeouts.get());
        assertEquals(List.of(5_000L, 9_000L), scheduler.delays);
    }

    @Test
    public void rearmingSameGenerationStillInvalidatesOldCallback() {
        FakeScheduler scheduler = new FakeScheduler();
        GenerationSafeWatchdog watchdog = new GenerationSafeWatchdog(scheduler);
        AtomicInteger timeouts = new AtomicInteger();

        watchdog.arm(7, 1, timeouts::incrementAndGet);
        Runnable stale = scheduler.lastPosted;
        watchdog.arm(7, 1, timeouts::incrementAndGet);

        stale.run();
        scheduler.lastPosted.run();
        assertEquals(1, timeouts.get());
    }

    @Test
    public void disarmRemovesAndInvalidatesPendingCallback() {
        FakeScheduler scheduler = new FakeScheduler();
        GenerationSafeWatchdog watchdog = new GenerationSafeWatchdog(scheduler);
        AtomicInteger timeouts = new AtomicInteger();

        watchdog.arm(1, 10, timeouts::incrementAndGet);
        Runnable pending = scheduler.lastPosted;
        watchdog.disarm();

        assertSame(pending, scheduler.lastRemoved);
        pending.run();
        assertEquals(0, timeouts.get());
    }

    private static final class FakeScheduler implements GenerationSafeWatchdog.Scheduler {
        final List<Long> delays = new ArrayList<>();
        Runnable lastPosted;
        Runnable lastRemoved;

        @Override
        public void postDelayed(Runnable action, long delayMillis) {
            lastPosted = action;
            delays.add(delayMillis);
        }

        @Override
        public void removeCallbacks(Runnable action) {
            lastRemoved = action;
        }
    }
}
