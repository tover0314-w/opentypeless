package com.opentypeless.android.recognition;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.speech.core.SessionId;

import java.util.Objects;

/**
 * Bounded provider event vocabulary for one recognition session.
 *
 * <p>Events are immutable data, not provider, routing, editor, network, or cancellation authority.
 * Provider adapters remain responsible for mapping their callbacks in later REC tasks.
 */
public sealed interface RecognitionEvent
        permits RecognitionEvent.Preparing,
                RecognitionEvent.Ready,
                RecognitionEvent.SpeechStarted,
                RecognitionEvent.Partial,
                RecognitionEvent.Endpoint,
                RecognitionEvent.Final,
                RecognitionEvent.Failure,
                RecognitionEvent.Cancelled {
    int MAX_TEXT_CODE_POINTS = 20_000;

    SessionId sessionId();

    long sequence();

    default boolean terminal() {
        return this instanceof Final || this instanceof Failure || this instanceof Cancelled;
    }

    record Preparing(SessionId sessionId, long sequence) implements RecognitionEvent {
        public Preparing {
            validateCommon(sessionId, sequence);
        }

        @Override
        public String toString() {
            return redacted("Preparing", sequence);
        }
    }

    record Ready(SessionId sessionId, long sequence) implements RecognitionEvent {
        public Ready {
            validateCommon(sessionId, sequence);
        }

        @Override
        public String toString() {
            return redacted("Ready", sequence);
        }
    }

    record SpeechStarted(SessionId sessionId, long sequence) implements RecognitionEvent {
        public SpeechStarted {
            validateCommon(sessionId, sequence);
        }

        @Override
        public String toString() {
            return redacted("SpeechStarted", sequence);
        }
    }

    record Partial(
            SessionId sessionId,
            long sequence,
            String text,
            Integer stablePrefixLength,
            Long revisionOf)
            implements RecognitionEvent {
        public Partial {
            validateCommon(sessionId, sequence);
            text = validateText(text, true);
            if (stablePrefixLength != null
                    && (stablePrefixLength < 0
                            || stablePrefixLength > text.length()
                            || splitsSurrogate(text, stablePrefixLength))) {
                throw new IllegalArgumentException(
                        "stable prefix must be a UTF-16 boundary within partial text");
            }
            if (revisionOf != null && (revisionOf <= 0L || revisionOf >= sequence)) {
                throw new IllegalArgumentException(
                        "partial revision must reference an earlier positive sequence");
            }
        }

        @Override
        public String toString() {
            return redacted("Partial", sequence);
        }
    }

    record Endpoint(SessionId sessionId, long sequence) implements RecognitionEvent {
        public Endpoint {
            validateCommon(sessionId, sequence);
        }

        @Override
        public String toString() {
            return redacted("Endpoint", sequence);
        }
    }

    record Final(
            SessionId sessionId,
            long sequence,
            String text,
            RecognitionMetadata metadata)
            implements RecognitionEvent {
        public Final {
            validateCommon(sessionId, sequence);
            text = validateText(text, false);
            metadata = Objects.requireNonNull(metadata, "metadata");
        }

        @Override
        public String toString() {
            return redacted("Final", sequence);
        }
    }

    record Failure(
            SessionId sessionId,
            long sequence,
            RecognitionRoute.FailureClass failureClass)
            implements RecognitionEvent {
        public Failure {
            validateCommon(sessionId, sequence);
            failureClass = Objects.requireNonNull(failureClass, "failureClass");
            if (failureClass == RecognitionRoute.FailureClass.CANCELLED) {
                throw new IllegalArgumentException(
                        "cancellation must use the dedicated Cancelled event");
            }
        }

        @Override
        public String toString() {
            return redacted("Failure", sequence);
        }
    }

    record Cancelled(SessionId sessionId, long sequence) implements RecognitionEvent {
        public Cancelled {
            validateCommon(sessionId, sequence);
        }

        @Override
        public String toString() {
            return redacted("Cancelled", sequence);
        }
    }

    private static void validateCommon(SessionId sessionId, long sequence) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (sequence <= 0L) {
            throw new IllegalArgumentException("recognition event sequence must be positive");
        }
    }

    private static String validateText(String value, boolean emptyAllowed) {
        String text = Objects.requireNonNull(value, "text");
        if ((!emptyAllowed && text.isBlank())
                || text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException("recognition text is outside its bound");
        }
        for (int index = 0; index < text.length(); ) {
            char unit = text.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= text.length()
                        || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    throw new IllegalArgumentException("recognition text must be well-formed UTF-16");
                }
                index += 2;
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("recognition text must be well-formed UTF-16");
            } else {
                index++;
            }
        }
        return text;
    }

    private static boolean splitsSurrogate(String value, int index) {
        return index > 0
                && index < value.length()
                && Character.isHighSurrogate(value.charAt(index - 1))
                && Character.isLowSurrogate(value.charAt(index));
    }

    private static String redacted(String kind, long sequence) {
        return "RecognitionEvent{" + kind + ", sequence=" + sequence + ", content=<redacted>}";
    }
}
