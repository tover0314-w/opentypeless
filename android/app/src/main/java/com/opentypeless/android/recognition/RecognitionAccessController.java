package com.opentypeless.android.recognition;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

final class RecognitionAccessController {
    enum Decision { ALLOWED, DISABLED, CALLER_NOT_ALLOWED, RATE_LIMITED }

    private final int maximumStarts;
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Long>> startsByCaller = new HashMap<>();

    RecognitionAccessController(int maximumStarts, long windowMillis, LongSupplier clock) {
        if (maximumStarts < 1 || windowMillis < 1 || clock == null) {
            throw new IllegalArgumentException("A positive rate limit and clock are required");
        }
        this.maximumStarts = maximumStarts;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    synchronized Decision authorize(
            boolean enabled,
            boolean allowlisted,
            boolean identityMatches,
            String rateLimitKey) {
        if (!enabled) return Decision.DISABLED;
        if (!allowlisted || !identityMatches || rateLimitKey == null || rateLimitKey.isBlank()) {
            return Decision.CALLER_NOT_ALLOWED;
        }

        long now = clock.getAsLong();
        ArrayDeque<Long> starts = startsByCaller.computeIfAbsent(
                rateLimitKey,
                ignored -> new ArrayDeque<>());
        while (!starts.isEmpty()
                && (starts.peekFirst() > now || now - starts.peekFirst() >= windowMillis)) {
            starts.removeFirst();
        }
        if (starts.size() >= maximumStarts) return Decision.RATE_LIMITED;
        starts.addLast(now);
        return Decision.ALLOWED;
    }
}
