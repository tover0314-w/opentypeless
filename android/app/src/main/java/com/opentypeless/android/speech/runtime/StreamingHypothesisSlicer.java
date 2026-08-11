package com.opentypeless.android.speech.runtime;

import java.util.Objects;

/**
 * Converts one full-session streaming hypothesis into the active segment's replacement text.
 *
 * <p>Paraformer is normally append-oriented. If a provider rewrites text that has already crossed
 * a hard audio boundary, this adapter refuses to invent a suffix; the segment quality pass remains
 * authoritative and the already visible prefix is retained.
 */
public final class StreamingHypothesisSlicer {
    public record Slice(String segmentText, boolean reliable, boolean earlierRewriteObserved) {
        public Slice {
            segmentText = Objects.requireNonNullElse(segmentText, "");
        }
    }

    private String sealedProviderPrefix = "";
    private String latestProviderFull = "";
    private String activeSegmentText = "";

    public synchronized Slice accept(String fullHypothesis) {
        String full = bounded(fullHypothesis);
        if (sealedProviderPrefix.isEmpty()) {
            latestProviderFull = full;
            activeSegmentText = full;
            return new Slice(activeSegmentText, true, false);
        }
        if (full.startsWith(sealedProviderPrefix)) {
            latestProviderFull = full;
            activeSegmentText = stripOneLeadingSeparator(
                    full.substring(sealedProviderPrefix.length()));
            return new Slice(activeSegmentText, true, false);
        }
        // A provider may transiently emit an empty/shorter hypothesis. Keep the previous visible
        // tail and wait for a later coherent full replacement.
        if (full.isEmpty() || sealedProviderPrefix.startsWith(full)) {
            latestProviderFull = full;
            return new Slice(activeSegmentText, false, true);
        }
        // If only the active tail extended from the immediately preceding callback, append that
        // extension without reinterpreting the rewritten sealed prefix.
        if (!latestProviderFull.isEmpty() && full.startsWith(latestProviderFull)) {
            String extension = full.substring(latestProviderFull.length());
            latestProviderFull = full;
            activeSegmentText += extension;
            return new Slice(activeSegmentText, false, true);
        }
        latestProviderFull = full;
        return new Slice(activeSegmentText, false, true);
    }

    /** Hard audio boundary: all provider text observed so far belongs to the closed segment set. */
    public synchronized void sealAtCurrentHypothesis() {
        if (!latestProviderFull.isEmpty()) sealedProviderPrefix = latestProviderFull;
        activeSegmentText = "";
    }

    public synchronized String activeSegmentText() {
        return activeSegmentText;
    }

    public synchronized String latestProviderFull() {
        return latestProviderFull;
    }

    private static String bounded(String value) {
        String safe = Objects.requireNonNullElse(value, "").trim();
        if (safe.codePointCount(0, safe.length()) > 20_000) {
            throw new IllegalArgumentException("streaming hypothesis is oversized");
        }
        return safe;
    }

    private static String stripOneLeadingSeparator(String value) {
        if (value.isEmpty()) return value;
        int first = value.codePointAt(0);
        return Character.isWhitespace(first)
                ? value.substring(Character.charCount(first))
                : value;
    }
}
