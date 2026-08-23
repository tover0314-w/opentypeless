package com.opentypeless.android.net.streaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.recognition.RecognitionEvent;
import com.opentypeless.android.recognition.RecognitionMetadata;
import com.opentypeless.android.speech.core.SessionId;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StreamingRecognitionWireEventTest {
    private static final SessionId SESSION = SessionId.of("wire-session");

    @Test
    public void everyRec002VariantRoundTripsThroughOneVersionedEnvelope() throws Exception {
        RecognitionMetadata metadata = new RecognitionMetadata("zh-hans-cn", 0.75f, 1_234L);
        List<RecognitionEvent> events = List.of(
                new RecognitionEvent.Preparing(SESSION, 1L),
                new RecognitionEvent.Ready(SESSION, 2L),
                new RecognitionEvent.SpeechStarted(SESSION, 3L),
                new RecognitionEvent.Partial(SESSION, 4L, "A😀B", 3, null),
                new RecognitionEvent.Endpoint(SESSION, 5L),
                new RecognitionEvent.Final(SESSION, 6L, "最终文本", metadata),
                new RecognitionEvent.Failure(
                        SESSION, 7L, RecognitionRoute.FailureClass.NETWORK_TIMEOUT),
                new RecognitionEvent.Cancelled(SESSION, 8L));

        for (RecognitionEvent event : events) {
            String json = StreamingRecognitionWireEvent.encode(event);
            JSONObject root = new JSONObject(json);
            assertEquals(StreamingRecognitionWireEvent.PROTOCOL, root.getString("protocol"));
            assertEquals(SESSION.value(), root.getString("session_id"));
            assertEquals(event, StreamingRecognitionWireEvent.decode(json));
        }

        JSONObject partial = new JSONObject(StreamingRecognitionWireEvent.encode(events.get(3)));
        assertEquals(3, partial.getInt("stable_prefix_utf16"));
        assertFalse(partial.has("revision_of"));
        JSONObject terminal = new JSONObject(StreamingRecognitionWireEvent.encode(events.get(5)));
        assertEquals("zh-Hans-CN", terminal.getJSONObject("metadata")
                .getString("detected_language_tag"));
    }

    @Test
    public void streamEnforcesMonotonicSequenceExactRevisionAndOneTerminal() {
        StreamingRecognitionWireEvent.Stream stream =
                new StreamingRecognitionWireEvent.Stream(SESSION);
        assertAccepted(stream, new RecognitionEvent.Preparing(SESSION, 2L));
        assertAccepted(stream, new RecognitionEvent.Partial(SESSION, 4L, "one", 1, null));
        assertRejected(
                stream,
                new RecognitionEvent.Partial(SESSION, 5L, "bad", 1, 3L),
                StreamingRecognitionWireEvent.Rejection.INVALID_REVISION);
        assertAccepted(stream, new RecognitionEvent.Partial(SESSION, 6L, "two", 1, 4L));
        assertRejected(
                stream,
                new RecognitionEvent.Ready(SESSION, 6L),
                StreamingRecognitionWireEvent.Rejection.NON_MONOTONIC_SEQUENCE);
        assertAccepted(
                stream,
                new RecognitionEvent.Final(
                        SESSION, 9L, "final", RecognitionMetadata.empty()));
        assertRejected(
                stream,
                new RecognitionEvent.Partial(SESSION, 10L, "late", 0, 6L),
                StreamingRecognitionWireEvent.Rejection.AFTER_TERMINAL);
        assertRejected(
                stream,
                new RecognitionEvent.Cancelled(SESSION, 11L),
                StreamingRecognitionWireEvent.Rejection.AFTER_TERMINAL);
    }

    @Test
    public void malformedAndForeignEventsFailClosedWithoutPoisoningTheStream() {
        StreamingRecognitionWireEvent.Stream stream =
                new StreamingRecognitionWireEvent.Stream(SESSION);
        assertRejected(
                stream,
                "{\"protocol\":\"opentypeless.streaming.v2\"}",
                StreamingRecognitionWireEvent.Rejection.MALFORMED);
        assertRejected(
                stream,
                new RecognitionEvent.Ready(SessionId.of("foreign-secret"), 99L),
                StreamingRecognitionWireEvent.Rejection.FOREIGN_SESSION);
        assertAccepted(stream, new RecognitionEvent.Ready(SESSION, 1L));

        String secret = "secret transcript";
        StreamingRecognitionWireEvent.Result result = stream.accept(
                "{\"protocol\":\"opentypeless.streaming.v1\","
                        + "\"session_id\":\"wire-session\",\"sequence\":2,"
                        + "\"type\":\"partial\",\"text\":\"" + secret
                        + "\",\"unexpected\":true}");
        assertEquals(
                new StreamingRecognitionWireEvent.Rejected(
                        StreamingRecognitionWireEvent.Rejection.MALFORMED),
                result);
        assertFalse(result.toString().contains(secret));
        assertFalse(stream.toString().contains(SESSION.value()));
    }

    @Test
    public void strictDecoderRejectsCoercionUnknownFieldsNullsAndAmbiguousCancellation() {
        String base = "{\"protocol\":\"opentypeless.streaming.v1\","
                + "\"session_id\":\"wire-session\",\"sequence\":";
        assertInvalid(base + "\"1\",\"type\":\"ready\"}");
        assertInvalid(base + "1.0,\"type\":\"ready\"}");
        assertInvalid(base + "1,\"type\":\"ready\",\"extra\":0}");
        assertInvalid(base + "1,\"type\":\"partial\",\"text\":null}");
        assertInvalid(base + "1,\"type\":\"unknown\"}");
        assertInvalid(base + "1,\"type\":\"failure\",\"failure_class\":\"CANCELLED\"}");
        assertInvalid(base + "1,\"type\":\"final\",\"text\":\"ok\","
                + "\"metadata\":{\"raw_message\":\"secret\"}}");
        assertInvalid(base + "1,\"type\":\"ready\",\"sequence\":2}");
        assertInvalid(base + "1,\"type\":\"ready\"} trailing");
        assertInvalid("[]");
    }

    @Test
    public void textMetadataAndJsonBoundsMatchTheDomainWithoutLeakingPayloads() {
        RecognitionEvent.Partial maximum = new RecognitionEvent.Partial(
                SESSION,
                Long.MAX_VALUE,
                "😀".repeat(RecognitionEvent.MAX_TEXT_CODE_POINTS),
                RecognitionEvent.MAX_TEXT_CODE_POINTS * 2,
                1L);
        assertEquals(maximum, StreamingRecognitionWireEvent.decode(
                StreamingRecognitionWireEvent.encode(maximum)));

        String base = "{\"protocol\":\"opentypeless.streaming.v1\","
                + "\"session_id\":\"wire-session\",\"sequence\":2,"
                + "\"type\":\"partial\",\"text\":";
        assertInvalid(base + JSONObject.quote("x".repeat(20_001)) + "}");
        assertInvalid(base + JSONObject.quote("\ud800") + "}");
        assertInvalid(base + JSONObject.quote("😀") + ",\"stable_prefix_utf16\":1}");
        assertInvalid("{\"protocol\":\"opentypeless.streaming.v1\","
                + "\"session_id\":\"wire-session\",\"sequence\":2,\"type\":\"final\","
                + "\"text\":\"ok\",\"metadata\":{\"confidence\":1.01}}");

        String oversized = "x".repeat(StreamingRecognitionWireEvent.MAX_JSON_UTF16_UNITS + 1);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> StreamingRecognitionWireEvent.decode(oversized));
        assertEquals("invalid streaming recognition event", failure.getMessage());
        assertFalse(failure.toString().contains(oversized.substring(0, 100)));
    }

    @Test
    public void schemaResourceFreezesEightClosedVariantsAndFailureVocabulary() throws Exception {
        ClassLoader loader = StreamingRecognitionWireEventTest.class.getClassLoader();
        assertNotNull(loader);
        try (InputStream input = loader.getResourceAsStream(
                StreamingRecognitionWireEvent.SCHEMA_RESOURCE)) {
            assertNotNull(input);
            JSONObject schema = new JSONObject(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(
                    "https://json-schema.org/draft/2020-12/schema",
                    schema.getString("$schema"));
            assertEquals(
                    "https://opentypeless.local/schema/streaming-recognition-event-v1.json",
                    schema.getString("$id"));
            assertEquals(8, schema.getJSONArray("oneOf").length());

            JSONObject definitions = schema.getJSONObject("$defs");
            assertEquals(
                    StreamingRecognitionWireEvent.PROTOCOL,
                    definitions.getJSONObject("protocol").getString("const"));
            Set<String> variants = Set.of(
                    "preparing",
                    "ready",
                    "speech_started",
                    "partial",
                    "endpoint",
                    "final",
                    "failure",
                    "cancelled");
            for (String variant : variants) {
                JSONObject definition = definitions.getJSONObject(variant);
                assertFalse(definition.getBoolean("additionalProperties"));
                assertEquals(
                        variant,
                        definition.getJSONObject("properties")
                                .getJSONObject("type")
                                .getString("const"));
            }

            JSONArray failureValues = definitions.getJSONObject("failure")
                    .getJSONObject("properties")
                    .getJSONObject("failure_class")
                    .getJSONArray("enum");
            Set<String> schemaFailures = new HashSet<>();
            for (int index = 0; index < failureValues.length(); index++) {
                schemaFailures.add(failureValues.getString(index));
            }
            Set<String> domainFailures = new HashSet<>(Arrays.stream(
                            RecognitionRoute.FailureClass.values())
                    .map(Enum::name)
                    .toList());
            domainFailures.remove(RecognitionRoute.FailureClass.CANCELLED.name());
            assertEquals(domainFailures, schemaFailures);
        }
    }

    @Test
    public void resultSurfaceIsClosedAndDiagnosticsRemainRedacted() {
        assertEquals(
                Set.of(
                        StreamingRecognitionWireEvent.Accepted.class,
                        StreamingRecognitionWireEvent.Rejected.class),
                Set.of(StreamingRecognitionWireEvent.Result.class.getPermittedSubclasses()));
        assertEquals(
                Set.of(
                        "MALFORMED",
                        "FOREIGN_SESSION",
                        "NON_MONOTONIC_SEQUENCE",
                        "INVALID_REVISION",
                        "AFTER_TERMINAL"),
                Arrays.stream(StreamingRecognitionWireEvent.Rejection.values())
                        .map(Enum::name)
                        .collect(java.util.stream.Collectors.toSet()));
        String secret = "private result";
        StreamingRecognitionWireEvent.Result accepted = new StreamingRecognitionWireEvent.Accepted(
                new RecognitionEvent.Final(
                        SessionId.of("private-session"),
                        1L,
                        secret,
                        RecognitionMetadata.empty()));
        assertTrue(accepted instanceof StreamingRecognitionWireEvent.Accepted);
        assertFalse(accepted.toString().contains(secret));
        assertThrows(
                NullPointerException.class,
                () -> new StreamingRecognitionWireEvent.Accepted(null));
        assertThrows(
                NullPointerException.class,
                () -> new StreamingRecognitionWireEvent.Rejected(null));
    }

    private static void assertAccepted(
            StreamingRecognitionWireEvent.Stream stream, RecognitionEvent event) {
        StreamingRecognitionWireEvent.Result result =
                stream.accept(StreamingRecognitionWireEvent.encode(event));
        assertTrue(result instanceof StreamingRecognitionWireEvent.Accepted);
        StreamingRecognitionWireEvent.Accepted accepted =
                (StreamingRecognitionWireEvent.Accepted) result;
        assertEquals(event, accepted.event());
    }

    private static void assertRejected(
            StreamingRecognitionWireEvent.Stream stream,
            RecognitionEvent event,
            StreamingRecognitionWireEvent.Rejection expected) {
        assertRejected(stream, StreamingRecognitionWireEvent.encode(event), expected);
    }

    private static void assertRejected(
            StreamingRecognitionWireEvent.Stream stream,
            String json,
            StreamingRecognitionWireEvent.Rejection expected) {
        assertEquals(
                new StreamingRecognitionWireEvent.Rejected(expected),
                stream.accept(json));
    }

    private static void assertInvalid(String json) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> StreamingRecognitionWireEvent.decode(json));
        assertEquals("invalid streaming recognition event", failure.getMessage());
        assertFalse(failure.getMessage().contains("wire-session"));
    }
}
