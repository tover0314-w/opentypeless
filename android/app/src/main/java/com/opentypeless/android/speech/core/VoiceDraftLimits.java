package com.opentypeless.android.speech.core;

/** Fail-closed document limits. Runtime policies may choose smaller values. */
public record VoiceDraftLimits(
        int maxSegments,
        int maxRevisionsPerSegment,
        int maxSegmentCodePoints,
        int maxDraftCodePoints) {

    public static final VoiceDraftLimits DEFAULT = new VoiceDraftLimits(256, 128, 20_000, 100_000);

    public VoiceDraftLimits {
        if (maxSegments <= 0
                || maxRevisionsPerSegment <= 0
                || maxSegmentCodePoints <= 0
                || maxDraftCodePoints <= 0
                || maxDraftCodePoints < maxSegmentCodePoints) {
            throw new IllegalArgumentException("voice draft limits must be positive and coherent");
        }
    }
}
