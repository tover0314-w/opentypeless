package com.opentypeless.android.recognition;

import com.opentypeless.android.speech.core.SessionId;

import java.util.Objects;

/** Linearizable, content-free sequence and terminal gate for one provider session. */
public final class RecognitionEventValidator {
    private final SessionId sessionId;
    private long lastSequence;
    private long lastPartialSequence;
    private boolean terminal;

    public RecognitionEventValidator(SessionId sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    public synchronized Disposition accept(RecognitionEvent event) {
        RecognitionEvent candidate = Objects.requireNonNull(event, "event");
        if (!sessionId.equals(candidate.sessionId())) {
            return Disposition.REJECTED_SESSION;
        }
        if (terminal) {
            return Disposition.DROPPED_AFTER_TERMINAL;
        }
        if (candidate.sequence() <= lastSequence) {
            return Disposition.REJECTED_SEQUENCE;
        }
        if (candidate instanceof RecognitionEvent.Partial partial
                && partial.revisionOf() != null
                && partial.revisionOf() != lastPartialSequence) {
            return Disposition.REJECTED_REVISION;
        }

        lastSequence = candidate.sequence();
        if (candidate instanceof RecognitionEvent.Partial) {
            lastPartialSequence = candidate.sequence();
        }
        terminal = candidate.terminal();
        return Disposition.ACCEPTED;
    }

    @Override
    public synchronized String toString() {
        return "RecognitionEventValidator{lastSequence=" + lastSequence
                + ", terminal=" + terminal + ", session=<redacted>}";
    }

    public enum Disposition {
        ACCEPTED,
        REJECTED_SESSION,
        REJECTED_SEQUENCE,
        REJECTED_REVISION,
        DROPPED_AFTER_TERMINAL
    }
}
