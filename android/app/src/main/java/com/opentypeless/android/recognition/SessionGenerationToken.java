package com.opentypeless.android.recognition;

import java.util.concurrent.atomic.AtomicLong;

/** Linearizable token: exactly one terminal callback may finish the current session. */
final class SessionGenerationToken {
    private final AtomicLong value = new AtomicLong();

    long next() {
        return value.incrementAndGet();
    }

    void invalidate() {
        invalidateAndGet();
    }

    long invalidateAndGet() {
        return value.incrementAndGet();
    }

    boolean isCurrent(long token) {
        return value.get() == token;
    }

    boolean finish(long token) {
        return value.compareAndSet(token, token + 1);
    }
}
