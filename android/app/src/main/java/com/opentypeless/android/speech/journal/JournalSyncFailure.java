package com.opentypeless.android.speech.journal;

import java.util.Objects;

/** Redacted journal failure; operation names contain no transcript or editor context. */
public record JournalSyncFailure(String operation, String reason) {
    public JournalSyncFailure {
        operation = Objects.requireNonNullElse(operation, "unknown");
        reason = Objects.requireNonNullElse(reason, "unknown");
        if (operation.length() > 80 || reason.length() > 160) {
            throw new IllegalArgumentException("journal failure description is too long");
        }
    }
}
