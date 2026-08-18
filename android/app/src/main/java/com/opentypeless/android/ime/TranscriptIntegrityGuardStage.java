package com.opentypeless.android.ime;

import com.opentypeless.android.transform.IntegrityResult;
import com.opentypeless.android.transform.TranscriptIntegrityGuard;

import java.util.Objects;

/** Transcript-integrity implementation of the terminal guard stage. */
final class TranscriptIntegrityGuardStage implements TextProcessingPipeline.IntegrityGuardStage {
    @Override
    public IntegrityResult apply(TextProcessingPipeline.IntegrityRequest request) {
        Objects.requireNonNull(request, "request");
        return TranscriptIntegrityGuard.validate(
                request.sourceText(),
                request.candidateText(),
                request.mode(),
                request.personalization());
    }
}
