package com.opentypeless.android.net.streaming;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.recognition.RecognitionEvent;
import com.opentypeless.android.recognition.RecognitionEventValidator;
import com.opentypeless.android.recognition.RecognitionMetadata;
import com.opentypeless.android.speech.core.SessionId;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded v1 JSON wire contract shared by WebSocket text frames and SSE data events.
 *
 * <p>This package-confined codec is not a network client or recognition authority. A caller must
 * use one session-bound {@link Stream}; raw JSON never bypasses the REC-002 sequence, revision, or
 * terminal gate.
 */
final class StreamingRecognitionWireEvent {
    static final String PROTOCOL = "opentypeless.streaming.v1";
    static final String SCHEMA_RESOURCE =
            "schemas/opentypeless-streaming-recognition-event-v1.schema.json";
    static final int MAX_JSON_UTF16_UNITS = 524_288;

    private static final Set<String> COMMON_KEYS =
            Set.of("protocol", "session_id", "sequence", "type");
    private static final Set<String> PARTIAL_KEYS =
            Set.of(
                    "protocol",
                    "session_id",
                    "sequence",
                    "type",
                    "text",
                    "stable_prefix_utf16",
                    "revision_of");
    private static final Set<String> FINAL_KEYS =
            Set.of("protocol", "session_id", "sequence", "type", "text", "metadata");
    private static final Set<String> FAILURE_KEYS =
            Set.of("protocol", "session_id", "sequence", "type", "failure_class");
    private static final Set<String> METADATA_KEYS =
            Set.of("detected_language_tag", "confidence", "audio_duration_ms");

    private StreamingRecognitionWireEvent() {}

    static String encode(RecognitionEvent event) {
        RecognitionEvent value = Objects.requireNonNull(event, "event");
        try {
            JSONObject root = base(value);
            if (value instanceof RecognitionEvent.Preparing) {
                root.put("type", "preparing");
            } else if (value instanceof RecognitionEvent.Ready) {
                root.put("type", "ready");
            } else if (value instanceof RecognitionEvent.SpeechStarted) {
                root.put("type", "speech_started");
            } else if (value instanceof RecognitionEvent.Partial partial) {
                root.put("type", "partial");
                root.put("text", partial.text());
                if (partial.stablePrefixLength() != null) {
                    root.put("stable_prefix_utf16", partial.stablePrefixLength());
                }
                if (partial.revisionOf() != null) {
                    root.put("revision_of", partial.revisionOf());
                }
            } else if (value instanceof RecognitionEvent.Endpoint) {
                root.put("type", "endpoint");
            } else if (value instanceof RecognitionEvent.Final terminal) {
                root.put("type", "final");
                root.put("text", terminal.text());
                root.put("metadata", encodeMetadata(terminal.metadata()));
            } else if (value instanceof RecognitionEvent.Failure failure) {
                root.put("type", "failure");
                root.put("failure_class", failure.failureClass().name());
            } else if (value instanceof RecognitionEvent.Cancelled) {
                root.put("type", "cancelled");
            } else {
                throw new IllegalArgumentException("unsupported recognition event");
            }
            String encoded = root.toString();
            requireJsonBound(encoded);
            return encoded;
        } catch (JSONException error) {
            throw new IllegalStateException("unable to encode streaming recognition event");
        }
    }

    static RecognitionEvent decode(String json) {
        requireJsonBound(json);
        try {
            JSONTokener tokener = new JSONTokener(json);
            Object decoded = tokener.nextValue();
            if (!(decoded instanceof JSONObject root) || tokener.nextClean() != 0) {
                throw invalidWireEvent();
            }
            requireString(root, "protocol", PROTOCOL);
            SessionId sessionId = SessionId.of(requireString(root, "session_id"));
            long sequence = requirePositiveLong(root, "sequence");
            String type = requireString(root, "type");
            return switch (type) {
                case "preparing" -> {
                    requireExactKeys(root, COMMON_KEYS);
                    yield new RecognitionEvent.Preparing(sessionId, sequence);
                }
                case "ready" -> {
                    requireExactKeys(root, COMMON_KEYS);
                    yield new RecognitionEvent.Ready(sessionId, sequence);
                }
                case "speech_started" -> {
                    requireExactKeys(root, COMMON_KEYS);
                    yield new RecognitionEvent.SpeechStarted(sessionId, sequence);
                }
                case "partial" -> decodePartial(root, sessionId, sequence);
                case "endpoint" -> {
                    requireExactKeys(root, COMMON_KEYS);
                    yield new RecognitionEvent.Endpoint(sessionId, sequence);
                }
                case "final" -> decodeFinal(root, sessionId, sequence);
                case "failure" -> decodeFailure(root, sessionId, sequence);
                case "cancelled" -> {
                    requireExactKeys(root, COMMON_KEYS);
                    yield new RecognitionEvent.Cancelled(sessionId, sequence);
                }
                default -> throw invalidWireEvent();
            };
        } catch (IllegalArgumentException error) {
            throw invalidWireEvent();
        } catch (JSONException | RuntimeException error) {
            throw invalidWireEvent();
        }
    }

    private static RecognitionEvent.Partial decodePartial(
            JSONObject root, SessionId sessionId, long sequence) throws JSONException {
        requireAllowedAndRequiredKeys(
                root,
                PARTIAL_KEYS,
                Set.of("protocol", "session_id", "sequence", "type", "text"));
        Integer stablePrefix = root.has("stable_prefix_utf16")
                ? requireInt(root, "stable_prefix_utf16", 0)
                : null;
        Long revision = root.has("revision_of")
                ? requirePositiveLong(root, "revision_of")
                : null;
        return new RecognitionEvent.Partial(
                sessionId, sequence, requireString(root, "text"), stablePrefix, revision);
    }

    private static RecognitionEvent.Final decodeFinal(
            JSONObject root, SessionId sessionId, long sequence) throws JSONException {
        requireExactKeys(root, FINAL_KEYS);
        Object rawMetadata = root.get("metadata");
        if (!(rawMetadata instanceof JSONObject metadata)) {
            throw invalidWireEvent();
        }
        requireAllowedAndRequiredKeys(metadata, METADATA_KEYS, Set.of());
        String language = metadata.has("detected_language_tag")
                ? requireString(metadata, "detected_language_tag")
                : null;
        Float confidence = metadata.has("confidence")
                ? requireConfidence(metadata, "confidence")
                : null;
        Long duration = metadata.has("audio_duration_ms")
                ? requirePositiveLong(metadata, "audio_duration_ms")
                : null;
        return new RecognitionEvent.Final(
                sessionId,
                sequence,
                requireString(root, "text"),
                new RecognitionMetadata(language, confidence, duration));
    }

    private static RecognitionEvent.Failure decodeFailure(
            JSONObject root, SessionId sessionId, long sequence) throws JSONException {
        requireExactKeys(root, FAILURE_KEYS);
        RecognitionRoute.FailureClass failureClass;
        try {
            failureClass = RecognitionRoute.FailureClass.valueOf(
                    requireString(root, "failure_class"));
        } catch (IllegalArgumentException error) {
            throw invalidWireEvent();
        }
        return new RecognitionEvent.Failure(sessionId, sequence, failureClass);
    }

    private static JSONObject base(RecognitionEvent event) throws JSONException {
        return new JSONObject()
                .put("protocol", PROTOCOL)
                .put("session_id", event.sessionId().value())
                .put("sequence", event.sequence());
    }

    private static JSONObject encodeMetadata(RecognitionMetadata metadata) throws JSONException {
        JSONObject value = new JSONObject();
        if (metadata.detectedLanguageTag() != null) {
            value.put("detected_language_tag", metadata.detectedLanguageTag());
        }
        if (metadata.confidence() != null) {
            value.put("confidence", metadata.confidence());
        }
        if (metadata.audioDurationMs() != null) {
            value.put("audio_duration_ms", metadata.audioDurationMs());
        }
        return value;
    }

    private static String requireString(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof String text)) {
            throw invalidWireEvent();
        }
        return text;
    }

    private static void requireString(JSONObject object, String key, String expected)
            throws JSONException {
        if (!expected.equals(requireString(object, key))) {
            throw invalidWireEvent();
        }
    }

    private static long requirePositiveLong(JSONObject object, String key) throws JSONException {
        Object raw = object.get(key);
        if (!(raw instanceof Number number)
                || raw instanceof Double
                || raw instanceof Float) {
            throw invalidWireEvent();
        }
        long value;
        try {
            value = Long.parseLong(number.toString());
        } catch (NumberFormatException error) {
            throw invalidWireEvent();
        }
        if (value <= 0L) {
            throw invalidWireEvent();
        }
        return value;
    }

    private static int requireInt(JSONObject object, String key, int minimum)
            throws JSONException {
        long value = requireNonNegativeIntegralLong(object, key);
        if (value < minimum || value > Integer.MAX_VALUE) {
            throw invalidWireEvent();
        }
        return (int) value;
    }

    private static long requireNonNegativeIntegralLong(JSONObject object, String key)
            throws JSONException {
        Object raw = object.get(key);
        if (!(raw instanceof Number number)
                || raw instanceof Double
                || raw instanceof Float) {
            throw invalidWireEvent();
        }
        long value;
        try {
            value = Long.parseLong(number.toString());
        } catch (NumberFormatException error) {
            throw invalidWireEvent();
        }
        if (value < 0L) {
            throw invalidWireEvent();
        }
        return value;
    }

    private static Float requireConfidence(JSONObject object, String key) throws JSONException {
        Object raw = object.get(key);
        if (!(raw instanceof Number number)) {
            throw invalidWireEvent();
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0d || value > 1d) {
            throw invalidWireEvent();
        }
        return (float) value;
    }

    private static void requireExactKeys(JSONObject object, Set<String> expected)
            throws JSONException {
        requireAllowedAndRequiredKeys(object, expected, expected);
    }

    private static void requireAllowedAndRequiredKeys(
            JSONObject object, Set<String> allowed, Set<String> required) throws JSONException {
        for (String key : required) {
            if (!object.has(key) || object.isNull(key)) {
                throw invalidWireEvent();
            }
        }
        Iterator<String> keys = object.keys();
        int count = 0;
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key) || object.isNull(key)) {
                throw invalidWireEvent();
            }
            count++;
        }
        if (count < required.size()) {
            throw invalidWireEvent();
        }
    }

    private static void requireJsonBound(String json) {
        if (json == null || json.isEmpty() || json.length() > MAX_JSON_UTF16_UNITS) {
            throw invalidWireEvent();
        }
    }

    private static IllegalArgumentException invalidWireEvent() {
        return new IllegalArgumentException("invalid streaming recognition event");
    }

    /** Session-bound decoder that reuses the authoritative REC-002 sequence and terminal gate. */
    static final class Stream {
        private final RecognitionEventValidator validator;

        Stream(SessionId sessionId) {
            validator = new RecognitionEventValidator(
                    Objects.requireNonNull(sessionId, "sessionId"));
        }

        synchronized Result accept(String json) {
            RecognitionEvent event;
            try {
                event = decode(json);
            } catch (IllegalArgumentException error) {
                return new Rejected(Rejection.MALFORMED);
            }
            RecognitionEventValidator.Disposition disposition = validator.accept(event);
            return switch (disposition) {
                case ACCEPTED -> new Accepted(event);
                case REJECTED_SESSION -> new Rejected(Rejection.FOREIGN_SESSION);
                case REJECTED_SEQUENCE -> new Rejected(Rejection.NON_MONOTONIC_SEQUENCE);
                case REJECTED_REVISION -> new Rejected(Rejection.INVALID_REVISION);
                case DROPPED_AFTER_TERMINAL -> new Rejected(Rejection.AFTER_TERMINAL);
            };
        }

        @Override
        public synchronized String toString() {
            return "StreamingRecognitionWireEvent.Stream{state=<redacted>}";
        }
    }

    sealed interface Result permits Accepted, Rejected {}

    record Accepted(RecognitionEvent event) implements Result {
        Accepted {
            event = Objects.requireNonNull(event, "event");
        }

        @Override
        public String toString() {
            return "StreamingRecognitionWireEvent.Accepted{event=<redacted>}";
        }
    }

    record Rejected(Rejection reason) implements Result {
        Rejected {
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    enum Rejection {
        MALFORMED,
        FOREIGN_SESSION,
        NON_MONOTONIC_SEQUENCE,
        INVALID_REVISION,
        AFTER_TERMINAL
    }
}
