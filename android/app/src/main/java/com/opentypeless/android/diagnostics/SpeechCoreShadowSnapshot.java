package com.opentypeless.android.diagnostics;

import com.opentypeless.android.speech.core.CaptureState;
import java.util.Objects;

/** Redacted counters plus the already-visible lab transcript for one v2 shadow replay. */
public record SpeechCoreShadowSnapshot(
        String renderedText,
        CaptureState captureState,
        int segmentCount,
        int acceptedRevisions,
        int ignoredCallbacks,
        int earlierTextRevisions,
        boolean provisionalPunctuationObserved,
        boolean terminal,
        String detail) {
    public SpeechCoreShadowSnapshot {
        renderedText = Objects.requireNonNullElse(renderedText, "");
        Objects.requireNonNull(captureState, "captureState");
        if (segmentCount < 0
                || acceptedRevisions < 0
                || ignoredCallbacks < 0
                || earlierTextRevisions < 0) {
            throw new IllegalArgumentException("shadow counters cannot be negative");
        }
        detail = Objects.requireNonNullElse(detail, "");
    }
}
