package com.opentypeless.android.recognition;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.ProviderConfig;
import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.net.streaming.StreamingRecognitionWebSocketClient;
import com.opentypeless.android.recognition.WebSocketStreamingProvider.ClientFailure;
import com.opentypeless.android.recognition.WebSocketStreamingProvider.StartRequest;
import com.opentypeless.android.speech.core.SessionId;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;

public final class WebSocketStreamingProviderTest {
    @Test
    public void fakeServerProviderStreamsPcmPartialEndpointAndFinalExactlyOnce()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            SessionId sessionId = SessionId.of("provider-integration");
            CountDownLatch binary = new CountDownLatch(1);
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override
                public void onMessage(WebSocket socket, String text) {
                    String type = string(json(text), "type");
                    if (type.equals("start")) {
                        socket.send(event(sessionId, 1L, "preparing", ""));
                        socket.send(event(sessionId, 2L, "ready", ""));
                    } else if (type.equals("finish")) {
                        socket.send(event(sessionId, 4L, "endpoint", ""));
                        socket.send(event(sessionId, 5L, "final", ",\"text\":\"done\",\"metadata\":{}"));
                    }
                }

                @Override
                public void onMessage(WebSocket socket, ByteString bytes) {
                    assertEquals(ByteString.of(new byte[]{1, 2, 3, 4}), bytes);
                    socket.send(event(
                            sessionId,
                            3L,
                            "partial",
                            ",\"text\":\"part\",\"stable_prefix_utf16\":2"));
                    binary.countDown();
                }

                @Override
                public void onClosing(WebSocket socket, int code, String reason) {
                    socket.close(code, reason);
                }
            }));
            server.start();
            ProviderConfig.Asr config = config(server);
            StreamingRecognitionWebSocketClient client =
                    new StreamingRecognitionWebSocketClient();
            ManualTimer timer = new ManualTimer();
            WebSocketStreamingProvider provider = new WebSocketStreamingProvider(
                    config,
                    new WebSocketStreamingProvider.ClientBackend(
                            config,
                            client,
                            (reference, operation) -> operation.apply(new char[0])),
                    timer);
            RecordingSink sink = new RecordingSink();
            WebSocketStreamingProvider.StreamingSession session = provider.start(
                    new StartRequest(sessionId, "zh-CN"), sink);
            try {
                assertTrue(sink.ready.await(2L, TimeUnit.SECONDS));
                assertTrue(session.acceptPcm(new byte[]{1, 2, 3, 4}, 4));
                assertTrue(binary.await(2L, TimeUnit.SECONDS));
                assertTrue(sink.partial.await(2L, TimeUnit.SECONDS));
                session.stop();
                assertTrue(sink.terminal.await(2L, TimeUnit.SECONDS));

                assertEquals(
                        List.of("Preparing", "Ready", "Partial", "Endpoint", "Final"),
                        sink.kinds());
                assertEquals(List.of(1L, 2L, 3L, 4L, 5L), sink.sequences());
                assertEquals(4, session.acceptedPcmBytes());
                assertFalse(session.acceptPcm(new byte[]{1, 2}, 2));
                assertTrue(provider.probe() instanceof ProviderRegistry.ObservedAvailable);
            } finally {
                provider.close();
            }
        }
    }

    @Test
    public void exactDescriptorOneUseRequestAndBoundsAreFailClosed() {
        FakeBackend backend = new FakeBackend();
        ManualTimer timer = new ManualTimer();
        WebSocketStreamingProvider provider = provider(backend, timer);
        StartRequest request = request("shape");

        assertTrue(Modifier.isFinal(WebSocketStreamingProvider.class.getModifiers()));
        assertFalse(Modifier.isPublic(WebSocketStreamingProvider.class.getModifiers()));
        assertEquals("builtin.test-streaming", provider.descriptor().id());
        assertTrue(provider.descriptor().capabilities().supportsStreaming());
        assertTrue(provider.prepare(request) instanceof RecognitionProvider.Prepared);
        RecordingSink sink = new RecordingSink();
        provider.start(request, sink);
        assertTrue(provider.prepare(request) instanceof RecognitionProvider.NotPrepared);
        assertFalse(request.toString().contains("shape"));
        assertFalse(provider.toString().contains("http"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new StartRequest(SessionId.of("bad-language"), " bad"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StartRequest(SessionId.of("bad-utf16"), "\uD800"));
        assertThrows(
                IllegalArgumentException.class,
                () -> provider(new FakeBackend(), new ManualTimer()).start(
                        request("frame"), new RecordingSink()).acceptPcm(new byte[3], 3));
        provider.close();
    }

    @Test
    public void reconnectsOnlyOnceBeforeAnyServerEventOrAudio() {
        FakeBackend backend = new FakeBackend();
        WebSocketStreamingProvider provider = provider(backend, new ManualTimer());
        RecordingSink sink = new RecordingSink();
        provider.start(request("retry"), sink);

        backend.connection(0).fail(ClientFailure.NETWORK_UNAVAILABLE);
        assertEquals(2, backend.openCount);
        backend.connection(1).fail(ClientFailure.SERVER_ERROR);

        assertEquals(2, backend.openCount);
        assertFailure(sink, RecognitionRoute.FailureClass.SERVER_ERROR);
        assertEquals(1, backend.connection(0).cancelCount);
        assertEquals(1, backend.connection(1).cancelCount);
        provider.close();
    }

    @Test
    public void eventOrAudioMakesReconnectUnsafeAndLateCallbacksAreDropped() {
        FakeBackend backend = new FakeBackend();
        WebSocketStreamingProvider provider = provider(backend, new ManualTimer());
        RecordingSink first = new RecordingSink();
        WebSocketStreamingProvider.StreamingSession firstSession =
                provider.start(request("event"), first);
        FakeConnection firstConnection = backend.connection(0);
        firstConnection.event(new RecognitionEvent.Preparing(firstSession.sessionId(), 1L));
        firstConnection.fail(ClientFailure.NETWORK_UNAVAILABLE);
        assertEquals(1, backend.openCount);
        assertFailure(first, RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE);
        firstConnection.event(new RecognitionEvent.Ready(firstSession.sessionId(), 2L));
        assertEquals(2, first.events.size());

        RecordingSink second = new RecordingSink();
        WebSocketStreamingProvider.StreamingSession secondSession =
                provider.start(request("audio"), second);
        FakeConnection secondConnection = backend.connection(1);
        secondConnection.event(new RecognitionEvent.Ready(secondSession.sessionId(), 1L));
        assertTrue(secondSession.acceptPcm(new byte[]{4, 3, 2, 1}, 4));
        secondConnection.fail(ClientFailure.NETWORK_TIMEOUT);
        assertEquals(2, backend.openCount);
        assertFailure(second, RecognitionRoute.FailureClass.NETWORK_TIMEOUT);
        provider.close();
    }

    @Test
    public void protocolAuthRateAndInternalFailuresMapWithoutBodiesOrIds() {
        Object[][] cases = {
                {ClientFailure.AUTHENTICATION, RecognitionRoute.FailureClass.AUTHENTICATION},
                {ClientFailure.RATE_LIMITED, RecognitionRoute.FailureClass.RATE_LIMITED},
                {ClientFailure.PROTOCOL_ERROR, RecognitionRoute.FailureClass.PROTOCOL_ERROR},
                {ClientFailure.INTERNAL_ERROR, RecognitionRoute.FailureClass.INTERNAL_ERROR}
        };
        FakeBackend backend = new FakeBackend();
        WebSocketStreamingProvider provider = provider(backend, new ManualTimer());
        for (int index = 0; index < cases.length; index++) {
            RecordingSink sink = new RecordingSink();
            provider.start(request("mapping-" + index), sink);
            backend.connection(index).fail((ClientFailure) cases[index][0]);
            assertFailure(sink, (RecognitionRoute.FailureClass) cases[index][1]);
            assertFalse(sink.toString().contains("mapping"));
        }
        assertEquals(cases.length, backend.openCount);
        provider.close();
    }

    @Test
    public void readyAndFinishTimeoutsAreSingleTerminalAndReleaseTheSession() {
        FakeBackend backend = new FakeBackend();
        ManualTimer timer = new ManualTimer();
        WebSocketStreamingProvider provider = provider(backend, timer);
        RecordingSink readyTimeout = new RecordingSink();
        provider.start(request("ready-timeout"), readyTimeout);
        timer.run(WebSocketStreamingProvider.READY_TIMEOUT_MS);
        timer.run(WebSocketStreamingProvider.READY_TIMEOUT_MS);
        assertFailure(readyTimeout, RecognitionRoute.FailureClass.NETWORK_TIMEOUT);

        RecordingSink finishTimeout = new RecordingSink();
        WebSocketStreamingProvider.StreamingSession session =
                provider.start(request("finish-timeout"), finishTimeout);
        backend.connection(2).event(new RecognitionEvent.Ready(session.sessionId(), 1L));
        session.stop();
        timer.run(WebSocketStreamingProvider.FINISH_TIMEOUT_MS);
        assertFailure(finishTimeout, RecognitionRoute.FailureClass.NETWORK_TIMEOUT);
        assertEquals(1, backend.connection(2).finishCount);
        provider.close();
    }

    @Test
    public void cancelIsOneTerminalAndCloseRejectsNewSessions() {
        FakeBackend backend = new FakeBackend();
        WebSocketStreamingProvider provider = provider(backend, new ManualTimer());
        RecordingSink sink = new RecordingSink();
        WebSocketStreamingProvider.StreamingSession session =
                provider.start(request("cancel"), sink);
        session.cancel();
        session.cancel();
        backend.connection(0).event(new RecognitionEvent.Final(
                session.sessionId(), 2L, "late", RecognitionMetadata.empty()));

        assertEquals(List.of("Cancelled"), sink.kinds());
        assertEquals(1, backend.connection(0).cancelCount);
        provider.close();
        provider.close();
        RecordingSink closed = new RecordingSink();
        provider.start(request("closed"), closed);
        assertFailure(closed, RecognitionRoute.FailureClass.UNAVAILABLE);
    }

    @Test
    public void pcmCopiesFramesCapsQueueAndRejectsTotalOverflow() {
        FakeBackend backend = new FakeBackend();
        WebSocketStreamingProvider provider = provider(backend, new ManualTimer());
        RecordingSink sink = new RecordingSink();
        WebSocketStreamingProvider.StreamingSession session =
                provider.start(request("pcm"), sink);
        FakeConnection connection = backend.connection(0);
        connection.event(new RecognitionEvent.Ready(session.sessionId(), 1L));
        byte[] mutable = {1, 2, 3, 4};
        assertTrue(session.acceptPcm(mutable, mutable.length));
        mutable[0] = 99;
        assertArrayEquals(new byte[]{1, 2, 3, 4}, connection.frames.get(0));

        int beforeRejectedFrame = session.acceptedPcmBytes();
        connection.queuedBytes =
                StreamingRecognitionWebSocketClient.MAX_OUTGOING_QUEUE_BYTES - 1L;
        assertFalse(session.acceptPcm(new byte[]{1, 2}, 2));
        assertEquals(beforeRejectedFrame, session.acceptedPcmBytes());
        assertFailure(sink, RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE);

        FakeBackend overflowBackend = new FakeBackend();
        WebSocketStreamingProvider overflowProvider =
                provider(overflowBackend, new ManualTimer());
        RecordingSink overflowSink = new RecordingSink();
        WebSocketStreamingProvider.StreamingSession overflow =
                overflowProvider.start(request("overflow"), overflowSink);
        FakeConnection overflowConnection = overflowBackend.connection(0);
        overflowConnection.event(new RecognitionEvent.Ready(overflow.sessionId(), 1L));
        byte[] frame = new byte[WebSocketStreamingProvider.MAX_PCM_FRAME_BYTES];
        while (overflow.acceptedPcmBytes()
                <= WebSocketStreamingProvider.MAX_TOTAL_PCM_BYTES - frame.length) {
            assertTrue(overflow.acceptPcm(frame, frame.length));
        }
        assertFalse(overflow.acceptPcm(frame, frame.length));
        assertFailure(overflowSink, RecognitionRoute.FailureClass.AUDIO_ERROR);
        provider.close();
        overflowProvider.close();
    }

    private static WebSocketStreamingProvider provider(
            FakeBackend backend, ManualTimer timer) {
        return new WebSocketStreamingProvider(config(), backend, timer);
    }

    private static ProviderConfig.Asr config() {
        return new ProviderConfig.Asr(
                "builtin.test-streaming",
                "Test Streaming",
                Optional.of(new ProviderConfig.Endpoint("https://stream.example.test/v1")),
                Optional.of("stream-model"),
                Optional.empty(),
                true);
    }

    private static ProviderConfig.Asr config(MockWebServer server) {
        return new ProviderConfig.Asr(
                "builtin.test-streaming",
                "Test Streaming",
                Optional.of(new ProviderConfig.Endpoint(server.url("/stream").toString())),
                Optional.of("stream-model"),
                Optional.empty(),
                true);
    }

    private static StartRequest request(String id) {
        return new StartRequest(SessionId.of(id), "zh-CN");
    }

    private static void assertFailure(
            RecordingSink sink, RecognitionRoute.FailureClass expected) {
        assertTrue(sink.events.get(sink.events.size() - 1) instanceof RecognitionEvent.Failure);
        RecognitionEvent.Failure failure =
                (RecognitionEvent.Failure) sink.events.get(sink.events.size() - 1);
        assertEquals(expected, failure.failureClass());
        assertEquals(1L, sink.events.stream().filter(RecognitionEvent::terminal).count());
    }

    private static String event(
            SessionId sessionId, long sequence, String type, String extra) {
        return "{\"protocol\":\"opentypeless.streaming.v1\",\"session_id\":"
                + JSONObject.quote(sessionId.value())
                + ",\"sequence\":" + sequence
                + ",\"type\":" + JSONObject.quote(type)
                + extra + "}";
    }

    private static JSONObject json(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception error) {
            throw new AssertionError("invalid control frame", error);
        }
    }

    private static String string(JSONObject object, String key) {
        try {
            return object.getString(key);
        } catch (Exception error) {
            throw new AssertionError("missing control field", error);
        }
    }

    private static final class FakeBackend implements WebSocketStreamingProvider.Backend {
        private final List<FakeConnection> connections = new ArrayList<>();
        private int openCount;

        @Override
        public WebSocketStreamingProvider.Connection open(
                SessionId sessionId,
                String language,
                WebSocketStreamingProvider.AttemptListener listener) {
            openCount++;
            FakeConnection connection = new FakeConnection(listener);
            connections.add(connection);
            listener.onOpen();
            return connection;
        }

        private FakeConnection connection(int index) {
            return connections.get(index);
        }

        @Override
        public void close() {}
    }

    private static final class FakeConnection implements WebSocketStreamingProvider.Connection {
        private final WebSocketStreamingProvider.AttemptListener listener;
        private final List<byte[]> frames = new ArrayList<>();
        private long queuedBytes;
        private int finishCount;
        private int cancelCount;

        private FakeConnection(WebSocketStreamingProvider.AttemptListener listener) {
            this.listener = listener;
        }

        private void event(RecognitionEvent event) {
            listener.onEvent(event);
        }

        private void fail(ClientFailure failure) {
            listener.onFailure(failure);
        }

        @Override
        public boolean sendPcm(byte[] pcm, int length) {
            frames.add(Arrays.copyOf(pcm, length));
            return true;
        }

        @Override
        public boolean finish() {
            finishCount++;
            return true;
        }

        @Override
        public long queuedBytes() {
            return queuedBytes;
        }

        @Override
        public void cancel() {
            cancelCount++;
        }

        @Override
        public void close() {}
    }

    private static final class ManualTimer implements WebSocketStreamingProvider.Timer {
        private final List<Task> tasks = new ArrayList<>();

        @Override
        public WebSocketStreamingProvider.Ticket schedule(Runnable action, long delayMillis) {
            Task task = new Task(action, delayMillis);
            tasks.add(task);
            return () -> task.cancelled = true;
        }

        private void run(long delayMillis) {
            List<Task> snapshot = new ArrayList<>(tasks);
            for (Task task : snapshot) {
                if (!task.cancelled && !task.ran && task.delayMillis == delayMillis) {
                    task.ran = true;
                    task.action.run();
                }
            }
        }

        @Override
        public void close() {
            for (Task task : tasks) task.cancelled = true;
        }

        private static final class Task {
            private final Runnable action;
            private final long delayMillis;
            private boolean cancelled;
            private boolean ran;

            private Task(Runnable action, long delayMillis) {
                this.action = action;
                this.delayMillis = delayMillis;
            }
        }
    }

    private static final class RecordingSink implements RecognitionProvider.EventSink {
        private final List<RecognitionEvent> events = new ArrayList<>();
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch partial = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public synchronized void onEvent(RecognitionEvent event) {
            events.add(event);
            if (event instanceof RecognitionEvent.Ready) ready.countDown();
            if (event instanceof RecognitionEvent.Partial) partial.countDown();
            if (event.terminal()) terminal.countDown();
        }

        private synchronized List<String> kinds() {
            return events.stream().map(event -> event.getClass().getSimpleName()).toList();
        }

        private synchronized List<Long> sequences() {
            return events.stream().map(RecognitionEvent::sequence).toList();
        }

        @Override
        public String toString() {
            return "RecordingSink{events=" + events.size() + ", content=<redacted>}";
        }
    }
}
