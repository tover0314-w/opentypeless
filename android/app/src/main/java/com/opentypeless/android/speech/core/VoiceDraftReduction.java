package com.opentypeless.android.speech.core;

import java.util.Objects;

/** Result of one pure reducer operation. Non-applied results preserve the exact input draft. */
public record VoiceDraftReduction(
        VoiceDraft draft, ReductionDisposition disposition, String detail) {
    public VoiceDraftReduction {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(detail, "detail");
    }

    public boolean applied() {
        return disposition == ReductionDisposition.APPLIED;
    }
}
