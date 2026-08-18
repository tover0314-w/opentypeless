package com.opentypeless.android.keyboard.latin;

import java.util.Objects;

/** Bounded, cancellation-safe repeat policy for the backspace key. */
final class BoundedDeleteRepeater {
    static final long DEFAULT_INITIAL_DELAY_MILLIS = 320L;
    static final long DEFAULT_REPEAT_INTERVAL_MILLIS = 58L;
    static final int DEFAULT_MAXIMUM_DELETES = 120;

    interface Cancellation {
        void cancel();
    }

    interface Scheduler {
        Cancellation schedule(Runnable action, long delayMillis);
    }

    private final Scheduler scheduler;
    private final long initialDelayMillis;
    private final long repeatIntervalMillis;
    private final int maximumDeletes;
    private Cancellation pending;
    private Runnable deleteAction;
    private int deletes;
    private long generation;
    private boolean active;

    BoundedDeleteRepeater(Scheduler scheduler) {
        this(
                scheduler,
                DEFAULT_INITIAL_DELAY_MILLIS,
                DEFAULT_REPEAT_INTERVAL_MILLIS,
                DEFAULT_MAXIMUM_DELETES);
    }

    BoundedDeleteRepeater(
            Scheduler scheduler,
            long initialDelayMillis,
            long repeatIntervalMillis,
            int maximumDeletes) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (initialDelayMillis < 1L || repeatIntervalMillis < 1L) {
            throw new IllegalArgumentException("repeat delays must be positive");
        }
        if (maximumDeletes < 2 || maximumDeletes > 1_000) {
            throw new IllegalArgumentException("maximum deletes must be 2..1000");
        }
        this.initialDelayMillis = initialDelayMillis;
        this.repeatIntervalMillis = repeatIntervalMillis;
        this.maximumDeletes = maximumDeletes;
    }

    void press(Runnable action) {
        stop();
        deleteAction = Objects.requireNonNull(action, "action");
        active = true;
        deletes = 1;
        deleteAction.run();
        schedule(generation, initialDelayMillis);
    }

    void stop() {
        active = false;
        generation++;
        deleteAction = null;
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }

    boolean active() {
        return active;
    }

    private void schedule(long expectedGeneration, long delayMillis) {
        pending = scheduler.schedule(() -> repeat(expectedGeneration), delayMillis);
    }

    private void repeat(long expectedGeneration) {
        pending = null;
        if (!active || generation != expectedGeneration || deleteAction == null) return;
        deleteAction.run();
        deletes++;
        if (deletes >= maximumDeletes) {
            stop();
            return;
        }
        schedule(expectedGeneration, repeatIntervalMillis);
    }
}
