package com.opentypeless.android.ime;

/**
 * A session-local, monotonic transcript revision.
 *
 * <p>Engines that cannot identify a stable prefix publish the complete hypothesis as
 * {@code unstableText}. Callers must replace, rather than append, successive hypotheses.
 */
public record TranscriptUpdate(
        long sequence,
        String stableText,
        String unstableText,
        boolean finalResult,
        Source source) {

    public enum Source {
        ANDROID_SYSTEM,
        SPEECH_CORE_V2,
        LOCAL_OFFLINE,
        DASHSCOPE_PARAFORMER,
        FUNASR,
        OPENAI_COMPATIBLE_BATCH
    }

    public TranscriptUpdate {
        if (sequence <= 0) throw new IllegalArgumentException("Sequence must be positive");
        stableText = stableText == null ? "" : stableText;
        unstableText = unstableText == null ? "" : unstableText;
        if (source == null) throw new IllegalArgumentException("Transcript source is required");
        if (finalResult && !unstableText.isEmpty()) {
            throw new IllegalArgumentException("A final transcript cannot have an unstable suffix");
        }
    }

    public String text() {
        return stableText + unstableText;
    }

    public static TranscriptUpdate unstable(long sequence, String text, Source source) {
        return new TranscriptUpdate(sequence, "", text, false, source);
    }

    public static TranscriptUpdate finalText(long sequence, String text, Source source) {
        return new TranscriptUpdate(sequence, text, "", true, source);
    }
}
