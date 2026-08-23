package com.opentypeless.android.ime;

import com.opentypeless.android.net.OpenAiCompatibleClient;
import com.opentypeless.android.personalization.PromptComposer;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** OpenAI-compatible implementation of the optional terminal text-processing stage. */
final class OpenAiOptionalLlmStage implements TextProcessingPipeline.OptionalLlmStage {
    private final OpenAiCompatibleClient client;

    OpenAiOptionalLlmStage(OpenAiCompatibleClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String apply(
            TextProcessingPipeline.LlmRequest request,
            BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancelled, "cancelled");
        return client.complete(
                PromptComposer.systemPrompt(
                        request.mode(),
                        request.inputContext(),
                        request.personalization(),
                        request.settings().targetLanguage(),
                        request.settings().customInstructions()),
                PromptComposer.userPrompt(
                        request.deterministicText(),
                        request.inputContext(),
                        request.settings().sendContext()),
                request.settings(),
                cancelled);
    }
}
