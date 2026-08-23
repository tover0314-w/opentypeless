package com.opentypeless.android.speech.transform;

/** Explicit policy; risky ITN is disabled until its locale-specific evidence gate is implemented. */
public record SegmentTransformPolicy(
        boolean provisionalPunctuation,
        boolean refinedPunctuation,
        boolean inverseTextNormalization,
        boolean personalization) {
    public static final SegmentTransformPolicy DEFAULT =
            new SegmentTransformPolicy(true, true, false, true);
}
