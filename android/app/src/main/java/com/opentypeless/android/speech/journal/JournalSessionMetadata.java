package com.opentypeless.android.speech.journal;

import com.opentypeless.android.speech.core.SessionId;
import java.util.Objects;

/** Redacted session metadata. It must not contain an endpoint, credential or editor context. */
public record JournalSessionMetadata(
        SessionId sessionId,
        long generation,
        long createdAtMillis,
        String engineId,
        String modelRevision,
        String languageTag,
        int sampleRate) {

    static final int MAX_LABEL_BYTES = 256;

    public JournalSessionMetadata {
        Objects.requireNonNull(sessionId, "sessionId");
        if (generation <= 0L || createdAtMillis < 0L || sampleRate <= 0) {
            throw new IllegalArgumentException("invalid journal session metadata");
        }
        engineId = safeLabel(engineId, "engineId");
        modelRevision = safeLabel(modelRevision, "modelRevision");
        languageTag = safeLabel(languageTag, "languageTag");
    }

    static String safeLabel(String value, String label) {
        Objects.requireNonNull(value, label);
        byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > MAX_LABEL_BYTES) {
            throw new IllegalArgumentException(label + " has an invalid encoded length");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " contains control characters");
        }
        return value;
    }
}
