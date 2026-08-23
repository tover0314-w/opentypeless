package com.opentypeless.android.ime;

import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.personalization.ProcessingResult;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.transform.IntegrityResult;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Staged text-processing boundary for one completed recognition result.
 *
 * <p>VOC-003 defines only the connected stage surface. Artifact provenance and independent stage
 * implementations remain in their explicitly dependent tasks.
 */
public interface TextProcessingPipeline {
    enum DeterministicFailurePolicy {
        PRESERVE_INPUT,
        PROPAGATE
    }

    record LlmRequest(
            ProcessingMode mode,
            InputContext inputContext,
            PersonalizationSnapshot personalization,
            AppSettings settings,
            String deterministicText) {
        @Override
        public String toString() {
            return "LlmRequest{<redacted>}";
        }
    }

    record IntegrityRequest(
            String sourceText,
            String candidateText,
            ProcessingMode mode,
            PersonalizationSnapshot personalization) {
        @Override
        public String toString() {
            return "IntegrityRequest{<redacted>}";
        }
    }

    @FunctionalInterface
    interface DeterministicStage {
        ProcessingResult apply(
                String input,
                PersonalizationSnapshot personalization,
                DeterministicFailurePolicy failurePolicy);
    }

    @FunctionalInterface
    interface CommandStage {
        Optional<String> apply(String deterministicText);
    }

    @FunctionalInterface
    interface OptionalLlmStage {
        String apply(LlmRequest request, BooleanSupplier cancelled) throws Exception;
    }

    @FunctionalInterface
    interface IntegrityGuardStage {
        IntegrityResult apply(IntegrityRequest request);
    }

    ProcessingResult deterministic(
            String input,
            PersonalizationSnapshot personalization,
            DeterministicFailurePolicy failurePolicy);

    Optional<String> command(String deterministicText);

    String optionalLlm(LlmRequest request, BooleanSupplier cancelled) throws Exception;

    IntegrityResult integrity(IntegrityRequest request);
}
