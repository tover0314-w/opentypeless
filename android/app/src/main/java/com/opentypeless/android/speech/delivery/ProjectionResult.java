package com.opentypeless.android.speech.delivery;

import java.util.Objects;
import java.util.Optional;

public record ProjectionResult(
        ProjectionState state,
        ProjectionOutcome outcome,
        Optional<String> recoverableText,
        boolean mutationUncertain,
        String detail) {
    public ProjectionResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(outcome, "outcome");
        recoverableText = Objects.requireNonNull(recoverableText, "recoverableText");
        detail = Objects.requireNonNullElse(detail, "");
    }
}
