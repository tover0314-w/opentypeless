package com.opentypeless.android.ime;

import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.personalization.ProcessingResult;
import com.opentypeless.android.transform.IntegrityResult;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Exact four-stage dispatcher used while the legacy implementations are incrementally split. */
final class StagedTextProcessingPipeline implements TextProcessingPipeline {
    private final DeterministicStage deterministicStage;
    private final CommandStage commandStage;
    private final OptionalLlmStage optionalLlmStage;
    private final IntegrityGuardStage integrityGuardStage;

    StagedTextProcessingPipeline(
            DeterministicStage deterministicStage,
            CommandStage commandStage,
            OptionalLlmStage optionalLlmStage,
            IntegrityGuardStage integrityGuardStage) {
        this.deterministicStage = Objects.requireNonNull(
                deterministicStage, "deterministicStage");
        this.commandStage = Objects.requireNonNull(commandStage, "commandStage");
        this.optionalLlmStage = Objects.requireNonNull(optionalLlmStage, "optionalLlmStage");
        this.integrityGuardStage = Objects.requireNonNull(
                integrityGuardStage, "integrityGuardStage");
    }

    @Override
    public ProcessingResult deterministic(
            String input,
            PersonalizationSnapshot personalization,
            DeterministicFailurePolicy failurePolicy) {
        return Objects.requireNonNull(
                deterministicStage.apply(
                        Objects.requireNonNull(input, "input"),
                        Objects.requireNonNull(personalization, "personalization"),
                        Objects.requireNonNull(failurePolicy, "failurePolicy")),
                "deterministic result");
    }

    @Override
    public Optional<String> command(String deterministicText) {
        return Objects.requireNonNull(
                commandStage.apply(Objects.requireNonNull(
                        deterministicText, "deterministicText")),
                "command result");
    }

    @Override
    public String optionalLlm(LlmRequest request, BooleanSupplier cancelled) throws Exception {
        return Objects.requireNonNull(
                optionalLlmStage.apply(
                        Objects.requireNonNull(request, "request"),
                        Objects.requireNonNull(cancelled, "cancelled")),
                "LLM result");
    }

    @Override
    public IntegrityResult integrity(IntegrityRequest request) {
        return Objects.requireNonNull(
                integrityGuardStage.apply(Objects.requireNonNull(request, "request")),
                "integrity result");
    }
}
