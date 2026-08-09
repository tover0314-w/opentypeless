package com.opentypeless.android.ime;

import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;

import java.util.List;

public record DictationResult(
        String rawText,
        String personalizedText,
        String finalText,
        String message,
        ProcessingMode mode,
        RecognitionBackend backend,
        long durationMs,
        boolean reachedRecordingLimit,
        boolean aiOutputAccepted,
        List<Long> matchedTermIds,
        List<Long> matchedCorrectionIds) {}
