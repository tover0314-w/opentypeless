package com.opentypeless.android.speech.journal;

import java.util.List;
import java.util.Objects;

public record JournalSyncReport(
        JournalSyncDisposition disposition,
        int writtenOperations,
        int duplicateOperations,
        List<JournalSyncFailure> failures) {
    public JournalSyncReport {
        Objects.requireNonNull(disposition, "disposition");
        if (writtenOperations < 0 || duplicateOperations < 0) {
            throw new IllegalArgumentException("journal operation counts must be non-negative");
        }
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
    }
}
