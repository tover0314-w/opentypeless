package com.opentypeless.android.speech.core;

import java.util.List;
import java.util.Objects;

/** A complete replacement for one segment, never a provider-specific token delta. */
public record SegmentRevision(
        SessionId sessionId,
        long segmentId,
        long revisionId,
        RevisionStage stage,
        String fullText,
        List<TokenEvidence> tokenEvidence,
        long audioStartMs,
        long audioEndMs,
        RevisionOrigin origin,
        boolean providerFinal) {

    public static final long UNKNOWN_AUDIO_TIME = -1L;

    public SegmentRevision {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(fullText, "fullText");
        tokenEvidence = List.copyOf(Objects.requireNonNull(tokenEvidence, "tokenEvidence"));
        Objects.requireNonNull(origin, "origin");
        if (segmentId <= 0L || revisionId <= 0L) {
            throw new IllegalArgumentException("segment and revision ids must be positive");
        }
        if ((audioStartMs == UNKNOWN_AUDIO_TIME) != (audioEndMs == UNKNOWN_AUDIO_TIME)) {
            throw new IllegalArgumentException("audio times must be both known or both unknown");
        }
        if (audioStartMs != UNKNOWN_AUDIO_TIME
                && (audioStartMs < 0L || audioEndMs < audioStartMs)) {
            throw new IllegalArgumentException("invalid revision audio span");
        }
        int textCodePoints = fullText.codePointCount(0, fullText.length());
        int previousEnd = 0;
        for (TokenEvidence token : tokenEvidence) {
            if (token.startCodePoint() < previousEnd || token.endCodePoint() > textCodePoints) {
                throw new IllegalArgumentException("token evidence is outside or overlaps fullText");
            }
            previousEnd = token.endCodePoint();
        }
        if (stage == RevisionStage.USER_LOCKED && origin != RevisionOrigin.USER) {
            throw new IllegalArgumentException("user-locked revisions must have USER origin");
        }
        if (origin == RevisionOrigin.USER && stage != RevisionStage.USER_LOCKED) {
            throw new IllegalArgumentException("USER origin must be user-locked");
        }
    }

    public static SegmentRevision text(
            SessionId sessionId,
            long segmentId,
            long revisionId,
            RevisionStage stage,
            String fullText,
            RevisionOrigin origin,
            boolean providerFinal) {
        return new SegmentRevision(
                sessionId,
                segmentId,
                revisionId,
                stage,
                fullText,
                List.of(),
                UNKNOWN_AUDIO_TIME,
                UNKNOWN_AUDIO_TIME,
                origin,
                providerFinal);
    }
}
