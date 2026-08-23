package com.opentypeless.android.recognition;

import com.opentypeless.android.config.RecognitionRoute;

import java.util.Objects;

/** Stable failure view for the legacy Android recognition surfaces. */
public record RecognitionFailure(
        RecognitionRoute.FailureClass failureClass,
        int errorCode,
        String message) {
    private static final int MAX_MESSAGE_CODE_POINTS = 300;

    public RecognitionFailure(int errorCode, String message) {
        this(RecognitionFailureMapper.fromLegacyAndroidError(errorCode), errorCode, message);
    }

    public RecognitionFailure(RecognitionRoute.FailureClass failureClass, String message) {
        this(
                failureClass,
                RecognitionFailureMapper.toAndroidErrorCode(failureClass),
                message);
    }

    public RecognitionFailure {
        failureClass = Objects.requireNonNull(failureClass, "failureClass");
        message = boundedMessage(message, failureClass);
    }

    @Override
    public String toString() {
        return "RecognitionFailure{failureClass=" + failureClass
                + ", errorCode=" + errorCode + ", message=<redacted>}";
    }

    private static String boundedMessage(
            String value,
            RecognitionRoute.FailureClass failureClass) {
        String safe = value == null || value.isBlank()
                ? RecognitionFailureMapper.stableMessage(failureClass)
                : value.trim();
        if (safe.codePointCount(0, safe.length()) > MAX_MESSAGE_CODE_POINTS) {
            return RecognitionFailureMapper.stableMessage(failureClass);
        }
        for (int index = 0; index < safe.length(); ) {
            char unit = safe.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= safe.length()
                        || !Character.isLowSurrogate(safe.charAt(index + 1))) {
                    return RecognitionFailureMapper.stableMessage(failureClass);
                }
                index += 2;
            } else if (Character.isLowSurrogate(unit)) {
                return RecognitionFailureMapper.stableMessage(failureClass);
            } else {
                index++;
            }
        }
        return safe;
    }
}
