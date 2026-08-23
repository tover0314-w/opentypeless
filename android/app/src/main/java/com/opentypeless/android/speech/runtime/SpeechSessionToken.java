package com.opentypeless.android.speech.runtime;

import com.opentypeless.android.speech.core.SessionId;
import java.util.Objects;

/** Opaque generation token required by every capture/stream callback. */
public record SpeechSessionToken(SessionId sessionId, long generation) {
    public SpeechSessionToken {
        Objects.requireNonNull(sessionId, "sessionId");
        if (generation <= 0L) throw new IllegalArgumentException("generation must be positive");
    }
}
