package com.opentypeless.android.speech.runtime;

import com.opentypeless.android.speech.core.TokenEvidence;
import java.util.List;
import java.util.Objects;

/** Provider-local revision normalized into one full-segment hypothesis. */
public record StreamingRevisionInput(
        SpeechSessionToken session,
        long segmentId,
        long providerRevisionSequence,
        String fullText,
        List<TokenEvidence> tokenEvidence,
        boolean providerFinal) {
    public StreamingRevisionInput {
        Objects.requireNonNull(session, "session");
        if (segmentId <= 0L || providerRevisionSequence <= 0L) {
            throw new IllegalArgumentException("invalid streaming revision identity");
        }
        fullText = Objects.requireNonNull(fullText, "fullText");
        tokenEvidence = List.copyOf(Objects.requireNonNull(tokenEvidence, "tokenEvidence"));
    }

    public static StreamingRevisionInput text(
            SpeechSessionToken session,
            long segmentId,
            long providerRevisionSequence,
            String fullText) {
        return new StreamingRevisionInput(
                session, segmentId, providerRevisionSequence, fullText, List.of(), false);
    }
}
