package com.opentypeless.android.speech.core;

import java.util.Objects;

/** Opaque generation identifier. It intentionally carries no editor or user data. */
public record SessionId(String value) {
    private static final int MAX_CODE_POINTS = 128;

    public SessionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("session id must not be blank");
        }
        if (value.codePointCount(0, value.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("session id is too long");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("session id must not contain control characters");
        }
    }

    public static SessionId of(String value) {
        return new SessionId(value);
    }
}
