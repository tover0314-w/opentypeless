package com.opentypeless.android.ime;

import java.util.Objects;

/** Content-free provenance for one terminal voice text-processing stage. */
public record StageProvenance(Stage stage, Disposition disposition) {
    public StageProvenance {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(disposition, "disposition");
        if (!isAllowed(stage, disposition)) {
            throw new IllegalArgumentException("Unsupported voice stage disposition");
        }
    }

    private static boolean isAllowed(Stage stage, Disposition disposition) {
        return switch (stage) {
            case RECOGNITION -> disposition == Disposition.CAPTURED
                    || disposition == Disposition.RECOVERED;
            case DETERMINISTIC -> disposition == Disposition.APPLIED
                    || disposition == Disposition.SKIPPED;
            case LOCAL_COMMAND -> disposition == Disposition.APPLIED
                    || disposition == Disposition.SKIPPED;
            case OPTIONAL_LLM -> disposition == Disposition.APPLIED
                    || disposition == Disposition.SKIPPED
                    || disposition == Disposition.FAILED;
            case INTEGRITY_GUARD -> disposition == Disposition.ACCEPTED
                    || disposition == Disposition.REJECTED
                    || disposition == Disposition.FAILED
                    || disposition == Disposition.SKIPPED;
            case FINALIZATION -> disposition == Disposition.PUBLISHED
                    || disposition == Disposition.FALLBACK;
        };
    }

    public enum Stage {
        RECOGNITION,
        DETERMINISTIC,
        LOCAL_COMMAND,
        OPTIONAL_LLM,
        INTEGRITY_GUARD,
        FINALIZATION
    }

    public enum Disposition {
        CAPTURED,
        RECOVERED,
        APPLIED,
        SKIPPED,
        FAILED,
        ACCEPTED,
        REJECTED,
        PUBLISHED,
        FALLBACK
    }
}
