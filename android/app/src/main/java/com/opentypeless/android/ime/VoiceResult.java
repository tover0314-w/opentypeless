package com.opentypeless.android.ime;

import java.util.List;
import java.util.Objects;

/**
 * Immutable terminal text artifact for one voice run.
 *
 * <p>The four text stages are intentionally kept together so Raw restore, integrity decisions and
 * history persistence cannot reconstruct different versions from independent callbacks. Diagnostic
 * rendering must use the redacted {@link #toString()} rather than any text accessor.
 */
public record VoiceResult(
        String rawText,
        String deterministicText,
        String candidateText,
        String finalText,
        List<StageProvenance> provenance) {
    static final int MAX_TEXT_CODE_POINTS = 20_000;

    public VoiceResult {
        rawText = requireText(rawText, "rawText");
        deterministicText = requireText(deterministicText, "deterministicText");
        candidateText = requireText(candidateText, "candidateText");
        finalText = requireText(finalText, "finalText");
        provenance = List.copyOf(Objects.requireNonNull(provenance, "provenance"));
        validateProvenance(
                rawText, deterministicText, candidateText, finalText, provenance);
    }

    static VoiceResult processed(
            String rawText,
            String deterministicText,
            String candidateText,
            String finalText,
            StageProvenance.Disposition command,
            StageProvenance.Disposition optionalLlm,
            StageProvenance.Disposition integrity,
            StageProvenance.Disposition finalization) {
        return new VoiceResult(
                rawText,
                deterministicText,
                candidateText,
                finalText,
                List.of(
                        provenance(
                                StageProvenance.Stage.RECOGNITION,
                                StageProvenance.Disposition.CAPTURED),
                        provenance(
                                StageProvenance.Stage.DETERMINISTIC,
                                StageProvenance.Disposition.APPLIED),
                        provenance(StageProvenance.Stage.LOCAL_COMMAND, command),
                        provenance(StageProvenance.Stage.OPTIONAL_LLM, optionalLlm),
                        provenance(StageProvenance.Stage.INTEGRITY_GUARD, integrity),
                        provenance(StageProvenance.Stage.FINALIZATION, finalization)));
    }

    static VoiceResult recovered(String text) {
        String safe = requireText(text, "text");
        return new VoiceResult(
                safe,
                safe,
                safe,
                safe,
                List.of(
                        provenance(
                                StageProvenance.Stage.RECOGNITION,
                                StageProvenance.Disposition.RECOVERED),
                        provenance(
                                StageProvenance.Stage.DETERMINISTIC,
                                StageProvenance.Disposition.SKIPPED),
                        provenance(
                                StageProvenance.Stage.LOCAL_COMMAND,
                                StageProvenance.Disposition.SKIPPED),
                        provenance(
                                StageProvenance.Stage.OPTIONAL_LLM,
                                StageProvenance.Disposition.SKIPPED),
                        provenance(
                                StageProvenance.Stage.INTEGRITY_GUARD,
                                StageProvenance.Disposition.SKIPPED),
                        provenance(
                                StageProvenance.Stage.FINALIZATION,
                                StageProvenance.Disposition.PUBLISHED)));
    }

    static VoiceResult compatible(
            String rawText,
            String deterministicText,
            String finalText,
            DictationResult.Outcome outcome,
            boolean aiOutputAccepted) {
        Objects.requireNonNull(outcome, "outcome");
        StageProvenance.Disposition command = StageProvenance.Disposition.SKIPPED;
        StageProvenance.Disposition optionalLlm = StageProvenance.Disposition.SKIPPED;
        StageProvenance.Disposition integrity = StageProvenance.Disposition.SKIPPED;
        StageProvenance.Disposition finalization = StageProvenance.Disposition.PUBLISHED;
        String candidate = deterministicText;
        if (outcome == DictationResult.Outcome.VOICE_COMMAND_INSERTED) {
            command = StageProvenance.Disposition.APPLIED;
            candidate = finalText;
        } else if (aiOutputAccepted
                || outcome == DictationResult.Outcome.SELECTION_UPDATED
                || outcome == DictationResult.Outcome.TRANSLATED
                || outcome == DictationResult.Outcome.SMART_EDITED) {
            optionalLlm = StageProvenance.Disposition.APPLIED;
            integrity = StageProvenance.Disposition.ACCEPTED;
            candidate = finalText;
        } else if (outcome == DictationResult.Outcome.AI_BLOCKED_EXACT) {
            optionalLlm = StageProvenance.Disposition.APPLIED;
            integrity = StageProvenance.Disposition.REJECTED;
            finalization = StageProvenance.Disposition.FALLBACK;
        } else if (outcome == DictationResult.Outcome.EXACT_AI_FAILED) {
            optionalLlm = StageProvenance.Disposition.FAILED;
            finalization = StageProvenance.Disposition.FALLBACK;
        } else if (outcome == DictationResult.Outcome.EXACT_AI_NOT_CONFIGURED) {
            finalization = StageProvenance.Disposition.FALLBACK;
        }
        return processed(
                rawText,
                deterministicText,
                candidate,
                finalText,
                command,
                optionalLlm,
                integrity,
                finalization);
    }

    @Override
    public String toString() {
        return "VoiceResult{<redacted>}";
    }

    public boolean aiOutputAccepted() {
        return disposition(provenance, StageProvenance.Stage.OPTIONAL_LLM)
                        == StageProvenance.Disposition.APPLIED
                && disposition(provenance, StageProvenance.Stage.INTEGRITY_GUARD)
                        == StageProvenance.Disposition.ACCEPTED;
    }

    private static StageProvenance provenance(
            StageProvenance.Stage stage, StageProvenance.Disposition disposition) {
        return new StageProvenance(stage, disposition);
    }

    private static void validateProvenance(
            String rawText,
            String deterministicText,
            String candidateText,
            String finalText,
            List<StageProvenance> provenance) {
        StageProvenance.Stage[] stages = StageProvenance.Stage.values();
        if (provenance.size() != stages.length) {
            throw new IllegalArgumentException("Voice provenance must contain every stage once");
        }
        for (int index = 0; index < stages.length; index++) {
            StageProvenance entry = Objects.requireNonNull(
                    provenance.get(index), "provenance[" + index + "]");
            if (entry.stage() != stages[index]) {
                throw new IllegalArgumentException("Voice provenance stage order is invalid");
            }
        }

        StageProvenance.Disposition recognition = disposition(
                provenance, StageProvenance.Stage.RECOGNITION);
        StageProvenance.Disposition deterministic = disposition(
                provenance, StageProvenance.Stage.DETERMINISTIC);
        StageProvenance.Disposition command = disposition(
                provenance, StageProvenance.Stage.LOCAL_COMMAND);
        StageProvenance.Disposition optionalLlm = disposition(
                provenance, StageProvenance.Stage.OPTIONAL_LLM);
        StageProvenance.Disposition integrity = disposition(
                provenance, StageProvenance.Stage.INTEGRITY_GUARD);
        StageProvenance.Disposition finalization = disposition(
                provenance, StageProvenance.Stage.FINALIZATION);

        if (recognition == StageProvenance.Disposition.RECOVERED) {
            if (deterministic != StageProvenance.Disposition.SKIPPED
                    || command != StageProvenance.Disposition.SKIPPED
                    || optionalLlm != StageProvenance.Disposition.SKIPPED
                    || integrity != StageProvenance.Disposition.SKIPPED
                    || finalization != StageProvenance.Disposition.PUBLISHED
                    || !rawText.equals(deterministicText)
                    || !rawText.equals(candidateText)
                    || !rawText.equals(finalText)) {
                throw new IllegalArgumentException("Recovered voice provenance is inconsistent");
            }
            return;
        }
        if (deterministic != StageProvenance.Disposition.APPLIED) {
            throw new IllegalArgumentException("Captured voice must run deterministic processing");
        }
        if (command == StageProvenance.Disposition.APPLIED) {
            if (optionalLlm != StageProvenance.Disposition.SKIPPED
                    || integrity != StageProvenance.Disposition.SKIPPED
                    || !candidateText.equals(finalText)) {
                throw new IllegalArgumentException("Command provenance is inconsistent");
            }
            return;
        }
        if (optionalLlm == StageProvenance.Disposition.APPLIED) {
            if (integrity == StageProvenance.Disposition.ACCEPTED) {
                if (!candidateText.equals(finalText)
                        || finalization != StageProvenance.Disposition.PUBLISHED) {
                    throw new IllegalArgumentException("Accepted candidate provenance is inconsistent");
                }
            } else if (integrity == StageProvenance.Disposition.REJECTED) {
                if (!deterministicText.equals(finalText)
                        || finalization != StageProvenance.Disposition.FALLBACK) {
                    throw new IllegalArgumentException("Rejected candidate provenance is inconsistent");
                }
            } else if (integrity == StageProvenance.Disposition.FAILED) {
                if (!deterministicText.equals(finalText)
                        || finalization != StageProvenance.Disposition.FALLBACK) {
                    throw new IllegalArgumentException("Failed integrity provenance is inconsistent");
                }
            } else {
                throw new IllegalArgumentException("LLM candidate requires an integrity decision");
            }
            return;
        }
        if (integrity != StageProvenance.Disposition.SKIPPED
                || !deterministicText.equals(candidateText)
                || !deterministicText.equals(finalText)) {
            throw new IllegalArgumentException("Exact fallback provenance is inconsistent");
        }
        if (optionalLlm == StageProvenance.Disposition.FAILED
                && finalization != StageProvenance.Disposition.FALLBACK) {
            throw new IllegalArgumentException("Failed LLM must publish an exact fallback");
        }
    }

    private static StageProvenance.Disposition disposition(
            List<StageProvenance> provenance, StageProvenance.Stage stage) {
        return provenance.get(stage.ordinal()).disposition();
    }

    private static String requireText(String value, String name) {
        String safe = Objects.requireNonNull(value, name);
        requireWellFormedUtf16(safe, name);
        if (safe.codePointCount(0, safe.length()) > MAX_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException(name + " exceeds the voice text limit");
        }
        return safe;
    }

    private static void requireWellFormedUtf16(String value, String name) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(name + " contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(name + " contains an unpaired surrogate");
            }
        }
    }
}
