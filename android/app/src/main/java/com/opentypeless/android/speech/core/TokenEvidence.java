package com.opentypeless.android.speech.core;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/** Optional native token evidence. Empty optionals mean the engine did not supply a capability. */
public record TokenEvidence(
        String text,
        int startCodePoint,
        int endCodePoint,
        OptionalDouble confidence,
        Optional<Boolean> stable,
        OptionalLong audioStartMs,
        OptionalLong audioEndMs) {

    public TokenEvidence {
        Objects.requireNonNull(text, "text");
        confidence = Objects.requireNonNull(confidence, "confidence");
        stable = Objects.requireNonNull(stable, "stable");
        audioStartMs = Objects.requireNonNull(audioStartMs, "audioStartMs");
        audioEndMs = Objects.requireNonNull(audioEndMs, "audioEndMs");
        if (startCodePoint < 0 || endCodePoint <= startCodePoint) {
            throw new IllegalArgumentException("invalid token text span");
        }
        if (confidence.isPresent()) {
            double value = confidence.getAsDouble();
            if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
                throw new IllegalArgumentException("confidence must be between zero and one");
            }
        }
        if (audioStartMs.isPresent() != audioEndMs.isPresent()) {
            throw new IllegalArgumentException("audio timestamps must be both present or both absent");
        }
        if (audioStartMs.isPresent()
                && (audioStartMs.getAsLong() < 0L
                        || audioEndMs.getAsLong() < audioStartMs.getAsLong())) {
            throw new IllegalArgumentException("invalid token audio span");
        }
    }

    public static TokenEvidence textOnly(String text, int startCodePoint, int endCodePoint) {
        return new TokenEvidence(
                text,
                startCodePoint,
                endCodePoint,
                OptionalDouble.empty(),
                Optional.empty(),
                OptionalLong.empty(),
                OptionalLong.empty());
    }
}
