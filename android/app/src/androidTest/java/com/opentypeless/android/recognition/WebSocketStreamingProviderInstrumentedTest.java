package com.opentypeless.android.recognition;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.config.ProviderConfig;
import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.recognition.WebSocketStreamingProvider.ClientFailure;
import com.opentypeless.android.recognition.WebSocketStreamingProvider.StartRequest;
import com.opentypeless.android.speech.core.SessionId;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@RunWith(AndroidJUnit4.class)
public final class WebSocketStreamingProviderInstrumentedTest {
    @Test
    public void boundedPcmAndTerminalLifecycleRunOnAndroidRuntime() {
        FakeBackend backend = new FakeBackend();
        WebSocketStreamingProvider provider = provider(backend);
        RecordingSink sink = new RecordingSink();
        WebSocketStreamingProvider.StreamingSession session = provider.start(
                request("android-stream"), sink);
        try {
            FakeConnection connection = backend.connection(0);
            connection.event(new RecognitionEvent.Ready(session.sessionId(), 1L));

            byte[] mutable = {1, 2, 3, 4};
            assertTrue(session.acceptPcm(mutable, mutable.length));
            mutable[0] = 99;
            assertArrayEquals(new byte[]{1, 2, 3, 4}, connection.frames.get(0));
            assertEquals(4, session.acceptedPcmBytes());

            session.stop();
            assertEquals(1, connection.finishCount);
            connection.event(new RecognitionEvent.Final(
                    session.sessionId(),
                    2L,
                    "device-final",
                    RecognitionMetadata.empty()));
            connection.event(new RecognitionEvent.Final(
                    session.sessionId(),
                    3L,
                    "late-final",
                    RecognitionMetadata.empty()));

            assertEquals(List.of("Ready", "Final"), sink.kinds());
            assertEquals(1L, sink.events.stream().filter(RecognitionEvent::terminal).count());
            assertFalse(session.acceptPcm(new byte[]{5, 6}, 2));
            assertEquals(1, connection.closeCount);
        } finally {
            provider.close();
        }
    }

    @Test
    public void reconnectIsSingleAndStopsAfterServerEvidenceOnAndroidRuntime() {
        FakeBackend backend = new FakeBackend();
        WebSocketStreamingProvider provider = provider(backend);
        RecordingSink sink = new RecordingSink();
        WebSocketStreamingProvider.StreamingSession session = provider.start(
                request("android-reconnect"), sink);
        try {
            backend.connection(0).fail(ClientFailure.NETWORK_UNAVAILABLE);
            assertEquals(2, backend.openCount);

            FakeConnection second = backend.connection(1);
            second.event(new RecognitionEvent.Ready(session.sessionId(), 1L));
            second.fail(ClientFailure.SERVER_ERROR);

            assertEquals(2, backend.openCount);
            assertTrue(sink.events.get(sink.events.size() - 1)
                    instanceof RecognitionEvent.Failure);
            RecognitionEvent.Failure failure =
                    (RecognitionEvent.Failure) sink.events.get(sink.events.size() - 1);
            assertEquals(RecognitionRoute.FailureClass.SERVER_ERROR, failure.failureClass());
            assertEquals(1L, sink.events.stream().filter(RecognitionEvent::terminal).count());
            assertEquals(1, backend.connection(0).cancelCount);
            assertEquals(1, second.cancelCount);
        } finally {
            provider.close();
        }
    }

    private static WebSocketStreamingProvider provider(FakeBackend backend) {
        ProviderConfig.Asr config = new ProviderConfig.Asr(
                "builtin.android-streaming-test",
                "Android Streaming Test",
                Optional.of(new ProviderConfig.Endpoint("https://stream.example.test/v1")),
                Optional.of("stream-model"),
                Optional.empty(),
                true);
        return new WebSocketStreamingProvider(config, backend, new NoOpTimer());
    }

    private static StartRequest request(String value) {
        return new StartRequest(SessionId.of(value), "zh-CN");
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
        private int finishCount;
        private int cancelCount;
        private int closeCount;

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
            return 0L;
        }

        @Override
        public void cancel() {
            cancelCount++;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class NoOpTimer implements WebSocketStreamingProvider.Timer {
        @Override
        public WebSocketStreamingProvider.Ticket schedule(Runnable action, long delayMillis) {
            return () -> {};
        }

        @Override
        public void close() {}
    }

    private static final class RecordingSink implements RecognitionProvider.EventSink {
        private final List<RecognitionEvent> events = new ArrayList<>();

        @Override
        public void onEvent(RecognitionEvent event) {
            events.add(event);
        }

        private List<String> kinds() {
            List<String> kinds = new ArrayList<>(events.size());
            for (RecognitionEvent event : events) {
                kinds.add(event.getClass().getSimpleName());
            }
            return kinds;
        }
    }
}
