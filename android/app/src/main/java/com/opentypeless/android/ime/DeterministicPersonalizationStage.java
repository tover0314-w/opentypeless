package com.opentypeless.android.ime;

import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.personalization.PersonalizedTextProcessor;
import com.opentypeless.android.personalization.ProcessingResult;

import java.util.List;
import java.util.Objects;

/**
 * Capability-free deterministic personalization stage.
 *
 * <p>The stage owns the legacy failure policy so {@link VoicePipelineRuntime} only orchestrates stage
 * results. Ordinary insertion preserves a bounded recognition result when a corrupt local rule is
 * rejected; selected-text editing propagates the same failure and remains fail closed.
 */
final class DeterministicPersonalizationStage
        implements TextProcessingPipeline.DeterministicStage {
    private static final int MAX_TRANSCRIPT_CODE_POINTS = 20_000;

    @Override
    public ProcessingResult apply(
            String input,
            PersonalizationSnapshot personalization,
            TextProcessingPipeline.DeterministicFailurePolicy failurePolicy) {
        String exactInput = Objects.requireNonNull(input, "input");
        PersonalizationSnapshot exactPersonalization =
                Objects.requireNonNull(personalization, "personalization");
        TextProcessingPipeline.DeterministicFailurePolicy exactPolicy =
                Objects.requireNonNull(failurePolicy, "failurePolicy");
        try {
            return PersonalizedTextProcessor.apply(exactInput, exactPersonalization);
        } catch (IllegalArgumentException error) {
            if (exactPolicy == TextProcessingPipeline.DeterministicFailurePolicy.PROPAGATE) {
                throw error;
            }
            int codePoints = exactInput.codePointCount(0, exactInput.length());
            String boundedInput = codePoints <= MAX_TRANSCRIPT_CODE_POINTS
                    ? exactInput
                    : exactInput.substring(
                            0, exactInput.offsetByCodePoints(0, MAX_TRANSCRIPT_CODE_POINTS));
            return new ProcessingResult(boundedInput, List.of(), List.of());
        }
    }
}
