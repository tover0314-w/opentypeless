package com.opentypeless.android.net.streaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.ProviderConfig;
import com.opentypeless.android.recognition.RecognitionEvent;
import com.opentypeless.android.recognition.RecognitionMetadata;
import com.opentypeless.android.speech.core.SessionId;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.ByteString;

public final class StreamingRecognitionWebSocketClientTest {
    private static final SessionId SESSION = SessionId.of("websocket-client-session");

    @Test
    public void fakeServerReceivesExactControlAndBinaryFramesAndReturnsTerminalStream()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            CountDownLatch binary = new CountDownLatch(1);
            List<String> commands = new ArrayList<>();
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override
                public void onMessage(WebSocket socket, String text) {
                    JSONObject command = json(text);
                    String type = string(command, "type");
                    commands.add(type);
                    if (type.equals("start")) {
                        assertEquals(StreamingRecognitionWireEvent.PROTOCOL,
                                string(command, "protocol"));
                        assertEquals(SESSION.value(), string(command, "session_id"));
                        assertEquals("test-model", string(command, "model"));
                        assertEquals("zh-Hans-CN", string(command, "language"));
                        assertEquals("pcm_s16le_16000_mono",
                                string(command, "audio_format"));
                        socket.send(StreamingRecognitionWireEvent.encode(
                                new RecognitionEvent.Preparing(SESSION, 1L)));
                        socket.send(StreamingRecognitionWireEvent.encode(
                                new RecognitionEvent.Ready(SESSION, 2L)));
                    } else if (type.equals("finish")) {
                        socket.send(StreamingRecognitionWireEvent.encode(
                                new RecognitionEvent.Endpoint(SESSION, 4L)));
                        socket.send(StreamingRecognitionWireEvent.encode(
                                new RecognitionEvent.Final(
                                        SESSION,
                                        5L,
                                        "最终😀",
                                        new RecognitionMetadata("zh-CN", 0.9f, 500L))));
                    }
                }

                @Override
                public void onMessage(WebSocket socket, ByteString bytes) {
                    assertEquals(ByteString.of(new byte[]{1, 2, 3, 4}), bytes);
                    socket.send(StreamingRecognitionWireEvent.encode(
                            new RecognitionEvent.Partial(
                                    SESSION, 3L, "部分", 2, null)));
                    binary.countDown();
                }

                @Override
                public void onClosing(WebSocket socket, int code, String reason) {
                    socket.close(code, reason);
                }
            }));
            server.start();
            StreamingRecognitionWebSocketClient client =
                    new StreamingRecognitionWebSocketClient();
            RecordingListener listener = new RecordingListener(4);
            StreamingRecognitionWebSocketClient.Session session = client.open(
                    config(server),
                    "temporary-token".toCharArray(),
                    listener);
            try {
                assertTrue(listener.ready.await(2L, TimeUnit.SECONDS));
                assertTrue(session.sendPcm(new byte[]{1, 2, 3, 4}, 0, 4));
                assertTrue(binary.await(2L, TimeUnit.SECONDS));
                assertTrue(listener.partial.await(2L, TimeUnit.SECONDS));
                assertTrue(session.finish());
                assertTrue(listener.terminal.await(2L, TimeUnit.SECONDS));

                assertEquals(
                        List.of("Preparing", "Ready", "Partial", "Endpoint", "Final"),
                        listener.kinds());
                assertEquals(List.of("start", "finish"), commands);
                assertNull(listener.failure);
                RecordedRequest request = server.takeRequest(2L, TimeUnit.SECONDS);
                assertEquals("Bearer temporary-token", request.getHeader("Authorization"));
            } finally {
                session.close();
                client.close();
            }
        }
    }

    @Test
    public void redirectIsRejectedAndCredentialIsNeverForwarded() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", server.url("/capture")));
            server.enqueue(new MockResponse().setResponseCode(200));
            StreamingRecognitionWebSocketClient client =
                    new StreamingRecognitionWebSocketClient();
            RecordingListener listener = new RecordingListener(0);
            StreamingRecognitionWebSocketClient.Session session = client.open(
                    config(server), "secret-token".toCharArray(), listener);
            try {
                assertTrue(listener.failed.await(2L, TimeUnit.SECONDS));
                assertEquals(
                        StreamingRecognitionWebSocketClient.Failure.PROTOCOL_ERROR,
                        listener.failure);
                RecordedRequest request = server.takeRequest(2L, TimeUnit.SECONDS);
                assertEquals("Bearer secret-token", request.getHeader("Authorization"));
                assertNull(server.takeRequest(250L, TimeUnit.MILLISECONDS));
            } finally {
                session.close();
                client.close();
            }
        }
    }

    @Test
    public void malformedForeignAndBinaryServerEventsFailClosedWithoutRawDetails()
            throws Exception {
        Object[] payloads = {
                "{not-json-secret",
                StreamingRecognitionWireEvent.encode(
                        new RecognitionEvent.Ready(SessionId.of("foreign-secret"), 1L)),
                ByteString.of(new byte[]{9, 8, 7})
        };
        for (Object payload : payloads) {
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                        if (payload instanceof String value) socket.send(value);
                        else socket.send((ByteString) payload);
                    }
                }));
                server.start();
                StreamingRecognitionWebSocketClient client =
                        new StreamingRecognitionWebSocketClient();
                RecordingListener listener = new RecordingListener(0);
                StreamingRecognitionWebSocketClient.Session session = client.open(
                        config(server), new char[0], listener);
                try {
                    assertTrue(listener.failed.await(2L, TimeUnit.SECONDS));
                    assertEquals(
                            StreamingRecognitionWebSocketClient.Failure.PROTOCOL_ERROR,
                            listener.failure);
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
    public void publicBoundaryRejectsCredentialConfigAndPcmFrameAbuse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {}));
            server.start();
            StreamingRecognitionWebSocketClient client =
                    new StreamingRecognitionWebSocketClient();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.open(config(server), " bad".toCharArray(),
                            new RecordingListener(0)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.open(config(server), "bad\nheader".toCharArray(),
                            new RecordingListener(0)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new StreamingRecognitionWebSocketClient.Config(
                            endpoint(server), SESSION, "", ""));

            StreamingRecognitionWebSocketClient.Session session = client.open(
                    config(server), new char[0], new RecordingListener(0));
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> session.sendPcm(new byte[3], 0, 3));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> session.sendPcm(
                                new byte[StreamingRecognitionWebSocketClient.MAX_PCM_FRAME_BYTES + 2],
                                0,
                                StreamingRecognitionWebSocketClient.MAX_PCM_FRAME_BYTES + 2));
                assertFalse(config(server).toString().contains(SESSION.value()));
            } finally {
                session.cancel();
                client.close();
            }
        }
    }

    @Test
    public void handshakeTimeoutMapsToStableTimeoutWithoutRawTransportDetails() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            OkHttpClient transport = new OkHttpClient.Builder()
                    .connectTimeout(100L, TimeUnit.MILLISECONDS)
                    .readTimeout(100L, TimeUnit.MILLISECONDS)
                    .callTimeout(500L, TimeUnit.MILLISECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .build();
            StreamingRecognitionWebSocketClient client =
                    new StreamingRecognitionWebSocketClient(transport);
            RecordingListener listener = new RecordingListener(0);
            StreamingRecognitionWebSocketClient.Session session = client.open(
                    config(server), new char[0], listener);
            try {
                assertTrue(listener.failed.await(2L, TimeUnit.SECONDS));
                assertEquals(
                        StreamingRecognitionWebSocketClient.Failure.NETWORK_TIMEOUT,
                        listener.failure);
                assertFalse(listener.toString().contains("timeout"));
            } finally {
                session.close();
                client.close();
            }
        }
    }

    private static StreamingRecognitionWebSocketClient.Config config(MockWebServer server) {
        return new StreamingRecognitionWebSocketClient.Config(
                endpoint(server), SESSION, "test-model", "zh-Hans-CN");
    }

    private static ProviderConfig.Endpoint endpoint(MockWebServer server) {
        return new ProviderConfig.Endpoint(server.url("/stream").toString());
    }

    private static JSONObject json(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception error) {
            throw new AssertionError("invalid client control frame", error);
        }
    }

    private static String string(JSONObject object, String key) {
        try {
            return object.getString(key);
        } catch (Exception error) {
            throw new AssertionError("missing client control field", error);
        }
    }

    private static final class RecordingListener
            implements StreamingRecognitionWebSocketClient.Listener {
        private final List<RecognitionEvent> events = new ArrayList<>();
        private final CountDownLatch expected;
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch partial = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final CountDownLatch failed = new CountDownLatch(1);
        private volatile StreamingRecognitionWebSocketClient.Failure failure;

        private RecordingListener(int expectedEvents) {
            expected = new CountDownLatch(expectedEvents);
        }

        @Override
        public void onOpen() {}

        @Override
        public synchronized void onEvent(RecognitionEvent event) {
            events.add(event);
            expected.countDown();
            if (event instanceof RecognitionEvent.Ready) ready.countDown();
            if (event instanceof RecognitionEvent.Partial) partial.countDown();
            if (event.terminal()) terminal.countDown();
        }

        @Override
        public void onFailure(StreamingRecognitionWebSocketClient.Failure failure) {
            this.failure = failure;
            failed.countDown();
        }

        private synchronized List<String> kinds() {
            return events.stream().map(event -> event.getClass().getSimpleName()).toList();
        }

        @Override
        public String toString() {
            return "RecordingListener{events=" + events.size() + ", content=<redacted>}";
        }
    }
}
