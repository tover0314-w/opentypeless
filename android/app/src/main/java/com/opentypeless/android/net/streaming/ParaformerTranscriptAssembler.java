package com.opentypeless.android.net.streaming;

import java.util.LinkedHashMap;
import java.util.Map;

/** Turns current-sentence revisions into one stable-prefix plus unstable-suffix transcript. */
final class ParaformerTranscriptAssembler {
    private static final int MAX_TRANSCRIPT_CODE_POINTS = 20_000;
    private static final int MAX_COMPLETED_SENTENCES = 512;
    private static final long NO_SENTENCE = Long.MIN_VALUE;

    record Snapshot(String stableText, String unstableText) {
        String text() {
            return stableText + unstableText;
        }
    }

    private final Map<Long, String> completed = new LinkedHashMap<>();
    private int completedCodePoints;
    private long syntheticSentenceId = NO_SENTENCE + 1L;
    private long currentSentenceId = NO_SENTENCE;
    private String currentText = "";

    synchronized Snapshot accept(ParaformerProtocol.Event event) {
        if (event.type() != ParaformerProtocol.EventType.RESULT) return snapshot();
        long sentenceId = event.sentenceBeginMs() >= 0
                ? event.sentenceBeginMs()
                : currentSentenceId != NO_SENTENCE
                        ? currentSentenceId
                        : syntheticSentenceId++;
        if (event.sentenceEnd()) {
            String previous = completed.get(sentenceId);
            if (previous == null && completed.size() >= MAX_COMPLETED_SENTENCES) {
                throw new IllegalArgumentException("Streaming transcript contained too many sentences");
            }
            int proposedCompleted = completedCodePoints
                    - codePointCount(previous)
                    + codePointCount(event.text());
            int proposedCurrent = currentSentenceId == sentenceId
                    ? 0
                    : codePointCount(currentText);
            requireBounded(proposedCompleted + proposedCurrent);
            completed.put(sentenceId, event.text());
            completedCodePoints = proposedCompleted;
            if (currentSentenceId == sentenceId) {
                currentSentenceId = NO_SENTENCE;
                currentText = "";
            }
        } else {
            int stableCodePoints = completedCodePoints
                    - codePointCount(completed.get(sentenceId));
            requireBounded(stableCodePoints + codePointCount(event.text()));
            currentSentenceId = sentenceId;
            currentText = event.text();
        }
        return snapshot();
    }

    synchronized String finalText() {
        return snapshot().text();
    }

    private Snapshot snapshot() {
        StringBuilder stable = new StringBuilder();
        for (Map.Entry<Long, String> sentence : completed.entrySet()) {
            if (sentence.getKey() != currentSentenceId) stable.append(sentence.getValue());
        }
        return new Snapshot(stable.toString(), currentText);
    }

    private static void requireBounded(int codePoints) {
        if (codePoints > MAX_TRANSCRIPT_CODE_POINTS) {
            throw new IllegalArgumentException("Streaming transcript exceeded the safety limit");
        }
    }

    private static int codePointCount(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }
}
