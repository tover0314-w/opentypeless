package com.opentypeless.android.speech.journal;

import com.opentypeless.android.speech.core.SessionId;
import java.util.Objects;

/** Opaque ownership token; filenames are always recomputed and never accepted from callers. */
public record JournalToken(SessionId sessionId, long generation) {
    public JournalToken {
        Objects.requireNonNull(sessionId, "sessionId");
        if (generation <= 0L) {
            throw new IllegalArgumentException("journal generation must be positive");
        }
    }
}
