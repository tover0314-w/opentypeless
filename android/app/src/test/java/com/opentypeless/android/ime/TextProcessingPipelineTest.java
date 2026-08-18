package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.data.CorrectionRule;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.personalization.ProcessingResult;
import com.opentypeless.android.personalization.VoiceCommandProcessor;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.transform.TranscriptIntegrityGuard;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class TextProcessingPipelineTest {
    @Test
    public void stagedSurfaceIsExactDataOnlyAndRequestsAreRedacted() {
        assertEquals(
                List.of("command", "deterministic", "integrity", "optionalLlm"),
                Arrays.stream(TextProcessingPipeline.class.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .map(Method::getName)
                        .sorted()
                        .toList());
        assertEquals(
                List.of(
                    "CommandStage",
                    "DeterministicFailurePolicy",
                    "DeterministicStage",
                    "IntegrityGuardStage",
                    "IntegrityRequest",
                    "LlmRequest",
                    "OptionalLlmStage"),
                Arrays.stream(TextProcessingPipeline.class.getDeclaredClasses())
                        .map(Class::getSimpleName)
                        .sorted()
                        .toList());

        AppSettings settings = settings("super-secret-key");
        InputContext context = new InputContext(
                "secret.package", FieldKind.GENERAL, "selected secret", "before secret", true);
        TextProcessingPipeline.LlmRequest llm = new TextProcessingPipeline.LlmRequest(
                ProcessingMode.SMART,
                context,
                PersonalizationSnapshot.empty(),
                settings,
                "deterministic secret");
        TextProcessingPipeline.IntegrityRequest integrity =
                new TextProcessingPipeline.IntegrityRequest(
                        "source secret",
                        "candidate secret",
                        ProcessingMode.SMART,
                        PersonalizationSnapshot.empty());

        assertEquals("LlmRequest{<redacted>}", llm.toString());
        assertEquals("IntegrityRequest{<redacted>}", integrity.toString());
        for (String secret : List.of(
                "super-secret-key",
                "secret.package",
                "selected secret",
                "before secret",
                "deterministic secret",
                "source secret",
                "candidate secret")) {
            assertFalse(llm.toString().contains(secret));
            assertFalse(integrity.toString().contains(secret));
        }
    }

    @Test
    public void exactFourStageDispatcherPreservesArgumentsResultsAndFailures() throws Exception {
        PersonalizationSnapshot snapshot = PersonalizationSnapshot.empty();
        AtomicReference<String> observed = new AtomicReference<>();
        AtomicBoolean cancellationObserved = new AtomicBoolean();
        ProcessingResult deterministicResult = new ProcessingResult(
                "deterministic", List.of(1L), List.of(2L));
        TextProcessingPipeline pipeline = new StagedTextProcessingPipeline(
                (input, personalization, failurePolicy) -> {
                    assertEquals("raw", input);
                    assertSame(snapshot, personalization);
                    assertSame(
                            TextProcessingPipeline.DeterministicFailurePolicy.PRESERVE_INPUT,
                            failurePolicy);
                    return deterministicResult;
                },
                text -> {
                    assertEquals("deterministic", text);
                    return Optional.of("\n");
                },
                (request, cancelled) -> {
                    observed.set(request.deterministicText());
                    cancellationObserved.set(cancelled.getAsBoolean());
                    return "candidate";
                },
                request -> {
                    assertEquals("deterministic", request.sourceText());
                    assertEquals("candidate", request.candidateText());
                    return com.opentypeless.android.transform.IntegrityResult.ok();
                });

        assertSame(
                deterministicResult,
                pipeline.deterministic(
                        "raw",
                        snapshot,
                        TextProcessingPipeline.DeterministicFailurePolicy.PRESERVE_INPUT));
        assertEquals(Optional.of("\n"), pipeline.command("deterministic"));
        assertEquals(
                "candidate",
                pipeline.optionalLlm(
                        new TextProcessingPipeline.LlmRequest(
                                ProcessingMode.SMART,
                                new InputContext("pkg", FieldKind.GENERAL, "", "", true),
                                snapshot,
                                settings(""),
                                "deterministic"),
                        () -> true));
        assertEquals("deterministic", observed.get());
        assertTrue(cancellationObserved.get());
        assertTrue(pipeline.integrity(new TextProcessingPipeline.IntegrityRequest(
                "deterministic", "candidate", ProcessingMode.SMART, snapshot)).safe());

        assertThrows(
                NullPointerException.class,
                () -> new StagedTextProcessingPipeline(null, value -> Optional.empty(),
                        (request, cancelled) -> "", request ->
                                com.opentypeless.android.transform.IntegrityResult.ok()));
        TextProcessingPipeline throwing = new StagedTextProcessingPipeline(
                (input, personalization, policy) -> deterministicResult,
                value -> Optional.empty(),
                (request, cancelled) -> { throw new StageFailure(); },
                request -> com.opentypeless.android.transform.IntegrityResult.ok());
        assertThrows(
                StageFailure.class,
                () -> throwing.optionalLlm(
                        new TextProcessingPipeline.LlmRequest(
                                ProcessingMode.SMART,
                                new InputContext("", FieldKind.GENERAL, "", "", true),
                                snapshot,
                                settings(""),
                                "text"),
                        () -> false));
    }

    @Test
    public void legacyStageAdaptersKeepDeterministicCommandAndIntegrityOutputsEquivalent()
            throws Exception {
        PersonalizationSnapshot explosive = new PersonalizationSnapshot(
                List.of(),
                List.of(new CorrectionRule(
                        1L, "a", "x".repeat(1_000), "", 0, true)));
        TextProcessingPipeline pipeline = new StagedTextProcessingPipeline(
                new DeterministicPersonalizationStage(),
                text -> Optional.ofNullable(VoiceCommandProcessor.exactReplacement(text)),
                (request, cancelled) -> "candidate",
                request -> TranscriptIntegrityGuard.validate(
                        request.sourceText(),
                        request.candidateText(),
                        request.mode(),
                        request.personalization()));
        String raw = "a ".repeat(100);

        assertEquals(
                raw,
                pipeline.deterministic(
                        raw,
                        explosive,
                        TextProcessingPipeline.DeterministicFailurePolicy.PRESERVE_INPUT).text());
        assertThrows(
                IllegalArgumentException.class,
                () -> pipeline.deterministic(
                        raw,
                        explosive,
                        TextProcessingPipeline.DeterministicFailurePolicy.PROPAGATE));
        assertEquals(Optional.of("\n"), pipeline.command("换行。"));
        assertEquals(Optional.empty(), pipeline.command("不要换行"));
        assertTrue(pipeline.integrity(new TextProcessingPipeline.IntegrityRequest(
                "Meeting at 10:30",
                "Meeting at 10:30",
                ProcessingMode.SMART,
                PersonalizationSnapshot.empty())).safe());
        assertFalse(pipeline.integrity(new TextProcessingPipeline.IntegrityRequest(
                "Meeting at 10:30",
                "Meeting at 11:30",
                ProcessingMode.SMART,
                PersonalizationSnapshot.empty())).safe());
    }

    private static AppSettings settings(String llmKey) {
        return new AppSettings(
                RecognitionBackend.LOCAL_OFFLINE,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "zh",
                ProcessingMode.SMART,
                true,
                "https://example.invalid/v1",
                llmKey,
                "model",
                "English",
                "",
                true,
                true,
                true,
                60);
    }

    private static final class StageFailure extends Exception {}
}
