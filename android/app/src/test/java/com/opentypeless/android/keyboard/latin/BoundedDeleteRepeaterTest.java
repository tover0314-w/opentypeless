package com.opentypeless.android.keyboard.latin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class BoundedDeleteRepeaterTest {
    @Test
    public void pressDeletesImmediatelyAndScheduledCallbacksRepeatUntilRelease() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger deletes = new AtomicInteger();
        BoundedDeleteRepeater repeater = new BoundedDeleteRepeater(scheduler, 10L, 2L, 8);

        repeater.press(deletes::incrementAndGet);
        assertEquals(1, deletes.get());
        assertEquals(10L, scheduler.nextDelay());

        scheduler.runNext();
        scheduler.runNext();
        assertEquals(3, deletes.get());
        assertTrue(repeater.active());

        repeater.stop();
        scheduler.runAll();
        assertEquals(3, deletes.get());
        assertFalse(repeater.active());
    }

    @Test
    public void staleCallbackCannotDeleteAfterASecondPressOrCancel() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        BoundedDeleteRepeater repeater = new BoundedDeleteRepeater(scheduler, 10L, 2L, 8);

        repeater.press(first::incrementAndGet);
        FakeScheduler.Entry stale = scheduler.removeNext();
        repeater.press(second::incrementAndGet);
        stale.action.run();
        assertEquals(1, first.get());
        assertEquals(1, second.get());

        repeater.stop();
        scheduler.runAll();
        assertEquals(1, second.get());
    }

    @Test
    public void maximumMakesAnUnreleasedGestureFinite() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger deletes = new AtomicInteger();
        BoundedDeleteRepeater repeater = new BoundedDeleteRepeater(scheduler, 10L, 2L, 4);

        repeater.press(deletes::incrementAndGet);
        scheduler.runAll();

        assertEquals(4, deletes.get());
        assertFalse(repeater.active());
        assertTrue(scheduler.entries.isEmpty());
    }

    private static final class FakeScheduler implements BoundedDeleteRepeater.Scheduler {
        final ArrayDeque<Entry> entries = new ArrayDeque<>();

        @Override
        public BoundedDeleteRepeater.Cancellation schedule(Runnable action, long delayMillis) {
            Entry entry = new Entry(action, delayMillis);
            entries.addLast(entry);
            return () -> entry.cancelled = true;
        }

        long nextDelay() {
            return entries.getFirst().delayMillis;
        }

        Entry removeNext() {
            return entries.removeFirst();
        }

        void runNext() {
            Entry entry = entries.removeFirst();
            if (!entry.cancelled) entry.action.run();
        }

        void runAll() {
            while (!entries.isEmpty()) runNext();
        }

        static final class Entry {
            final Runnable action;
            final long delayMillis;
            boolean cancelled;

            Entry(Runnable action, long delayMillis) {
                this.action = action;
                this.delayMillis = delayMillis;
            }
        }
    }
}
