package com.opentypeless.android.recognition;

/** A replaceable timeout whose stale callbacks cannot affect a newer recognition session. */
final class GenerationSafeWatchdog {
    interface Scheduler {
        void postDelayed(Runnable action, long delayMillis);
        void removeCallbacks(Runnable action);
    }

    private final Scheduler scheduler;
    private Runnable pending;
    private long generation;
    private long armToken;

    GenerationSafeWatchdog(Scheduler scheduler) {
        if (scheduler == null) throw new IllegalArgumentException("Scheduler is required");
        this.scheduler = scheduler;
    }

    synchronized void arm(long sessionGeneration, long delayMillis, Runnable timeout) {
        if (timeout == null) throw new IllegalArgumentException("Timeout action is required");
        disarmLocked();
        generation = sessionGeneration;
        long expectedToken = ++armToken;
        Runnable candidate = () -> fire(sessionGeneration, expectedToken, timeout);
        pending = candidate;
        scheduler.postDelayed(candidate, Math.max(0L, delayMillis));
    }

    synchronized void disarm() {
        disarmLocked();
        generation++;
        armToken++;
    }

    private void fire(long expectedGeneration, long expectedToken, Runnable timeout) {
        synchronized (this) {
            if (pending == null
                    || generation != expectedGeneration
                    || armToken != expectedToken) {
                return;
            }
            pending = null;
        }
        timeout.run();
    }

    private void disarmLocked() {
        if (pending == null) return;
        scheduler.removeCallbacks(pending);
        pending = null;
    }
}
