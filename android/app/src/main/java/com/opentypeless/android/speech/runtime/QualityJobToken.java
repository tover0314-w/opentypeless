package com.opentypeless.android.speech.runtime;

import com.opentypeless.android.speech.core.SessionId;
import java.util.Objects;

/** Session/generation/segment-bound ownership token for one quality pass. */
public record QualityJobToken(
        SessionId sessionId,
        long generation,
        long segmentId,
        long jobId) {
    public QualityJobToken {
        Objects.requireNonNull(sessionId, "sessionId");
        if (generation <= 0L || segmentId <= 0L || jobId <= 0L) {
            throw new IllegalArgumentException("invalid quality job token");
        }
    }
}
