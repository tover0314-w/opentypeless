package com.opentypeless.android.ime;

import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;

import java.util.List;

public record DictationResult(
        String rawText,
        String personalizedText,
        String finalText,
        Outcome outcome,
        ProcessingMode mode,
        RecognitionBackend backend,
        long durationMs,
        boolean reachedRecordingLimit,
        boolean aiOutputAccepted,
        List<Long> matchedTermIds,
        List<Long> matchedCorrectionIds) {
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
