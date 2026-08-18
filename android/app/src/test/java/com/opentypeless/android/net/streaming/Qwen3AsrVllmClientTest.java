package com.opentypeless.android.net.streaming;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.ProviderConfig;
import com.opentypeless.android.recognition.RecognitionEvent;
import com.opentypeless.android.speech.core.SessionId;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import okio.ByteString;

public final class Qwen3AsrVllmClientTest {
    private static final SessionId SESSION = SessionId.of("qwen-client-test");
    private static final String MODEL = "Qwen/Qwen3-ASR-0.6B";

    @Test
    public void probesExactConfiguredModelWithBoundedRedirectFreeHttp() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(jsonResponse("""
                    {"object":"list","data":[
                      {"id":"another-model","object":"model"},
                      {"id":"Qwen/Qwen3-ASR-0.6B","object":"model"}
                    ]}
                    """));
            server.start();
            Qwen3AsrVllmClient client = new Qwen3AsrVllmClient();
            try {
                assertEquals(
                        Qwen3AsrVllmClient.ProbeResult.AVAILABLE,
                        client.probe(config(server), "temporary-token".toCharArray()));
                RecordedRequest request = server.takeRequest(2L, TimeUnit.SECONDS);
                assertEquals("/v1/models", request.getPath());
                assertEquals("Bearer temporary-token", request.getHeader("Authorization"));
            } finally {
                client.close();
            }
        }

        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(jsonResponse("{\"object\":\"list\",\"data\":[]}"));
            server.start();
            Qwen3AsrVllmClient client = new Qwen3AsrVllmClient();
            try {
                assertEquals(
                        Qwen3AsrVllmClient.ProbeResult.MODEL_MISSING,
                        client.probe(config(server), new char[0]));
            } finally {
                client.close();
            }
        }

        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "/credential-capture"));
            server.enqueue(jsonResponse("{\"data\":[]}"));
            server.start();
            Qwen3AsrVllmClient client = new Qwen3AsrVllmClient();
            try {
                assertEquals(
                        Qwen3AsrVllmClient.ProbeResult.PROTOCOL_ERROR,
                        client.probe(config(server), "secret-token".toCharArray()));
                assertEquals("/v1/models", server.takeRequest(2L, TimeUnit.SECONDS).getPath());
                assertNull(server.takeRequest(250L, TimeUnit.MILLISECONDS));
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void mapsProbeStatusMalformedOversizeAndDepthWithoutRawDetails() throws Exception {
        Object[][] cases = {
                {new MockResponse().setResponseCode(401),
                        Qwen3AsrVllmClient.ProbeResult.AUTHENTICATION},
                {new MockResponse().setResponseCode(429),
                        Qwen3AsrVllmClient.ProbeResult.RATE_LIMITED},
                {new MockResponse().setResponseCode(503),
                        Qwen3AsrVllmClient.ProbeResult.SERVER_ERROR},
                {jsonResponse("{not-json-secret"),
                        Qwen3AsrVllmClient.ProbeResult.PROTOCOL_ERROR},
                {jsonResponse("{" + "\"a\":{".repeat(17) + "\"data\":[]"
                        + "}".repeat(17) + "}"),
                        Qwen3AsrVllmClient.ProbeResult.PROTOCOL_ERROR},
                {new MockResponse().setBody(new Buffer().write(new byte[
                        Qwen3AsrVllmClient.MAX_PROBE_BYTES + 1])),
                        Qwen3AsrVllmClient.ProbeResult.PROTOCOL_ERROR}
        };
        for (Object[] item : cases) {
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue((MockResponse) item[0]);
                server.start();
                Qwen3AsrVllmClient client = new Qwen3AsrVllmClient();
                try {
                    assertEquals(item[1], client.probe(config(server), new char[0]));
                    assertFalse(client.toString().contains("secret"));
                } finally {
                    client.close();
                }
            }
        }
    }

    @Test
    public void fixedChineseEnglishAndMixedSamplesUseOfficialRealtimeFrames() throws Exception {
        String[] samples = {"今天天气很好", "open the calendar", "明天 review the plan"};
        for (int sampleIndex = 0; sampleIndex < samples.length; sampleIndex++) {
            String transcript = samples[sampleIndex];
            byte[] pcm = {(byte) sampleIndex, 2, 3, 4};
            List<String> clientTypes = new ArrayList<>();
            List<byte[]> audioFrames = new ArrayList<>();
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket socket, okhttp3.Response response) {
                        socket.send("{\"type\":\"session.created\",\"id\":\"srv-1\",\"created\":1}");
                    }

                    @Override
                    public void onMessage(WebSocket socket, String text) {
                        JSONObject frame = json(text);
                        String type = string(frame, "type");
                        clientTypes.add(type);
                        if ("session.update".equals(type)) {
                            assertEquals(MODEL, string(frame, "model"));
                        } else if ("input_audio_buffer.append".equals(type)) {
                            audioFrames.add(Base64.getDecoder().decode(string(frame, "audio")));
                        } else if ("input_audio_buffer.commit".equals(type)) {
                            assertTrue(frame.optBoolean("final"));
                            int split = Math.max(1, transcript.length() / 2);
                            socket.send(object(
                                    "type", "transcription.delta",
                                    "delta", transcript.substring(0, split)));
                            socket.send(object(
                                    "type", "transcription.delta",
                                    "delta", transcript.substring(split)));
                            socket.send(object(
                                    "type", "transcription.done",
                                    "text", transcript,
                                    "usage", json("{\"total_tokens\":7}")));
                        }
                    }

                    @Override
                    public void onClosing(WebSocket socket, int code, String reason) {
                        socket.close(code, reason);
                    }
                }));
                server.start();
                Qwen3AsrVllmClient client = new Qwen3AsrVllmClient();
                RecordingListener listener = new RecordingListener();
                Qwen3AsrVllmClient.Session session = client.open(
                        config(server), new char[0], listener);
                try {
                    assertTrue(listener.ready.await(2L, TimeUnit.SECONDS));
                    assertTrue(session.sendPcm(pcm, 0, pcm.length));
                    assertTrue(session.finish());
                    assertTrue(listener.terminal.await(2L, TimeUnit.SECONDS));
                    assertEquals(
                            List.of("Preparing", "Ready", "Partial", "Partial", "Endpoint", "Final"),
                            listener.kinds());
                    assertEquals(transcript, ((RecognitionEvent.Final)
                            listener.events.get(listener.events.size() - 1)).text());
                    assertEquals(
                            List.of(
                                    "session.update",
                                    "input_audio_buffer.append",
                                    "input_audio_buffer.commit"),
                            clientTypes);
                    assertEquals(1, audioFrames.size());
                    assertArrayEquals(pcm, audioFrames.get(0));
                    assertNull(listener.failure);
                    assertEquals("/v1/realtime", server.takeRequest(2L, TimeUnit.SECONDS).getPath());
                } finally {
                    session.close();
                    client.close();
                }
            }
        }
    }

    @Test
    public void serverErrorMalformedBinaryAndUnexpectedDoneFailClosed() throws Exception {
        Object[] events = {
                "{\"type\":\"error\",\"error\":\"secret backend body\",\"code\":\"model_not_found\"}",
                "{not-json-secret",
                ByteString.of(new byte[]{9, 8, 7}),
                "{\"type\":\"transcription.done\",\"text\":\"unexpected\"}"
        };
        Qwen3AsrVllmClient.Failure[] failures = {
                Qwen3AsrVllmClient.Failure.MODEL_MISSING,
                Qwen3AsrVllmClient.Failure.PROTOCOL_ERROR,
                Qwen3AsrVllmClient.Failure.PROTOCOL_ERROR,
                Qwen3AsrVllmClient.Failure.PROTOCOL_ERROR
        };
        for (int index = 0; index < events.length; index++) {
            Object event = events[index];
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket socket, okhttp3.Response response) {
                        if (event instanceof String text) socket.send(text);
                        else socket.send((ByteString) event);
                    }
                }));
                server.start();
                Qwen3AsrVllmClient client = new Qwen3AsrVllmClient();
                RecordingListener listener = new RecordingListener();
                Qwen3AsrVllmClient.Session session = client.open(
                        config(server), new char[0], listener);
                try {
                    assertTrue(listener.failed.await(2L, TimeUnit.SECONDS));
                    assertEquals(failures[index], listener.failure);
                    assertFalse(session.toString().contains("secret"));
                    assertFalse(listener.toString().contains("secret"));
                } finally {
                    session.close();
                    client.close();
                }
            }
        }
    }

    @Test
    public void boundaryRejectsAmbiguousBaseCredentialAndPcmAbuse() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Qwen3AsrVllmClient.Config(
                        new ProviderConfig.Endpoint("https://example.test/custom"),
                        SESSION,
                        MODEL));
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {}));
            server.start();
            Qwen3AsrVllmClient client = new Qwen3AsrVllmClient();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.open(config(server), "bad\nheader".toCharArray(),
                            new RecordingListener()));
            Qwen3AsrVllmClient.Session session = client.open(
                    config(server), new char[0], new RecordingListener());
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> session.sendPcm(new byte[3], 0, 3));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> session.sendPcm(
                                new byte[Qwen3AsrVllmClient.MAX_PCM_FRAME_BYTES + 2],
                                0,
                                Qwen3AsrVllmClient.MAX_PCM_FRAME_BYTES + 2));
                assertFalse(config(server).toString().contains(MODEL));
                assertFalse(config(server).toString().contains(SESSION.value()));
            } finally {
                session.cancel();
                client.close();
            }
        }
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static Qwen3AsrVllmClient.Config config(MockWebServer server) {
        return new Qwen3AsrVllmClient.Config(
                new ProviderConfig.Endpoint(server.url("/").toString()),
                SESSION,
                MODEL);
    }

    private static JSONObject json(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception error) {
            throw new AssertionError("invalid client frame", error);
        }
    }

    private static String string(JSONObject object, String key) {
        try {
            return object.getString(key);
        } catch (Exception error) {
            throw new AssertionError("missing client field", error);
        }
    }

    private static String object(Object... fields) {
        try {
            JSONObject object = new JSONObject();
            for (int index = 0; index < fields.length; index += 2) {
                object.put((String) fields[index], fields[index + 1]);
            }
            return object.toString();
        } catch (Exception error) {
            throw new AssertionError("unable to encode fake server frame", error);
        }
    }

    private static final class RecordingListener implements Qwen3AsrVllmClient.Listener {
        private final List<RecognitionEvent> events = new ArrayList<>();
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final CountDownLatch failed = new CountDownLatch(1);
        private volatile Qwen3AsrVllmClient.Failure failure;

        @Override
        public void onOpen() {}

        @Override
        public synchronized void onEvent(RecognitionEvent event) {
            events.add(event);
            if (event instanceof RecognitionEvent.Ready) ready.countDown();
            if (event.terminal()) terminal.countDown();
        }

        @Override
        public void onFailure(Qwen3AsrVllmClient.Failure failure) {
            this.failure = failure;
            failed.countDown();
        }

        private synchronized List<String> kinds() {
            return events.stream().map(event -> event.getClass().getSimpleName()).toList();
        }

        @Override
        public String toString() {
            return "RecordingListener{eventCount=" + events.size()
                    + ", failure=" + failure + ", content=<redacted>}";
        }
    }
}
