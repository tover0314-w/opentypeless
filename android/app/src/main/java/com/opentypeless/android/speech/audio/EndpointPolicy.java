package com.opentypeless.android.speech.audio;

/** Measurable endpoint policy. A hard boundary closes a segment, never the capture session. */
public record EndpointPolicy(
        int minimumInitialSpeechMs,
        int softSilenceMs,
        int hardSilenceMs,
        int maximumSegmentMs,
        int preRollMs,
        int overlapMs) {

    public static final EndpointPolicy DEFAULT =
            new EndpointPolicy(120, 550, 2_200, 15_000, 160, 120);

    public EndpointPolicy {
        if (minimumInitialSpeechMs <= 0
                || softSilenceMs <= 0
                || hardSilenceMs <= softSilenceMs
                || maximumSegmentMs <= hardSilenceMs
                || preRollMs < 0
                || overlapMs < 0
                || overlapMs >= hardSilenceMs / 2) {
            throw new IllegalArgumentException("endpoint policy is incoherent");
        }
    }
}
