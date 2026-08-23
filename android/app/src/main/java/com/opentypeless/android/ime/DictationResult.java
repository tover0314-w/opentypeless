package com.opentypeless.android.ime;

import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;

import java.util.List;

public record DictationResult(
        VoiceResult voiceResult,
        Outcome outcome,
        ProcessingMode mode,
        RecognitionBackend backend,
        long durationMs,
        boolean reachedRecordingLimit,
        boolean recoveredPartial,
        List<Long> matchedTermIds,
        List<Long> matchedCorrectionIds,
        String recoveryId) {
    public DictationResult {
        voiceResult = java.util.Objects.requireNonNull(voiceResult, "voiceResult");
        outcome = java.util.Objects.requireNonNull(outcome, "outcome");
        mode = java.util.Objects.requireNonNull(mode, "mode");
        backend = java.util.Objects.requireNonNull(backend, "backend");
        matchedTermIds = List.copyOf(java.util.Objects.requireNonNull(
                matchedTermIds, "matchedTermIds"));
        matchedCorrectionIds = List.copyOf(java.util.Objects.requireNonNull(
                matchedCorrectionIds, "matchedCorrectionIds"));
        recoveryId = recoveryId == null ? "" : recoveryId;
    }

    public DictationResult(
            String rawText,
            String personalizedText,
            String finalText,
            Outcome outcome,
            ProcessingMode mode,
            RecognitionBackend backend,
            long durationMs,
            boolean reachedRecordingLimit,
            boolean aiOutputAccepted,
            boolean recoveredPartial,
            List<Long> matchedTermIds,
            List<Long> matchedCorrectionIds,
            String recoveryId) {
        this(
                VoiceResult.compatible(
                        rawText, personalizedText, finalText, outcome, aiOutputAccepted),
                outcome,
                mode,
                backend,
                durationMs,
                reachedRecordingLimit,
                recoveredPartial,
                matchedTermIds,
                matchedCorrectionIds,
                recoveryId);
        if (aiOutputAccepted != aiOutputAccepted()) {
            throw new IllegalArgumentException("AI acceptance must match voice provenance");
        }
    }

    public DictationResult(
            String rawText,
            String personalizedText,
            String finalText,
            Outcome outcome,
            ProcessingMode mode,
            RecognitionBackend backend,
            long durationMs,
            boolean reachedRecordingLimit,
            boolean aiOutputAccepted,
            boolean recoveredPartial,
            List<Long> matchedTermIds,
            List<Long> matchedCorrectionIds) {
        this(
                rawText,
                personalizedText,
                finalText,
                outcome,
                mode,
                backend,
                durationMs,
                reachedRecordingLimit,
                aiOutputAccepted,
                recoveredPartial,
                matchedTermIds,
                matchedCorrectionIds,
                "");
    }

    public String rawText() {
        return voiceResult.rawText();
    }

    public String personalizedText() {
        return voiceResult.deterministicText();
    }

    public String finalText() {
        return voiceResult.finalText();
    }

    public boolean aiOutputAccepted() {
        return voiceResult.aiOutputAccepted();
    }

    @Override
    public String toString() {
        return "DictationResult{<redacted>}";
    }

    public enum Outcome {
        INSERTED,
        INSERTED_RECORDING_LIMIT,
        INSERTED_AFTER_SILENCE,
        VOICE_COMMAND_INSERTED,
        EXACT_AI_NOT_CONFIGURED,
        SELECTION_UPDATED,
        TRANSLATED,
        SMART_EDITED,
        AI_BLOCKED_EXACT,
        EXACT_AI_FAILED
    }
}
