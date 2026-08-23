package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.ProviderConfig;
import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.config.SecretRef;
import com.opentypeless.android.net.streaming.Qwen3AsrVllmClient;
import com.opentypeless.android.speech.core.SessionId;

import org.json.JSONObject;
import org.junit.Test;

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
import okhttp3.mockwebserver.RecordedRequest;

public final class Qwen3AsrVllmProviderTest {
    private static final String MODEL = "Qwen/Qwen3-ASR-0.6B";
    private static final SessionId SESSION = SessionId.of("qwen-provider-test");

    @Test
    public void capabilityProbeUnlocksLocalProviderAndStreamsOneSession() throws Exception {
        List<String> clientTypes = new ArrayList<>();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(jsonResponse("""
                    {"object":"list","data":[{"id":"Qwen/Qwen3-ASR-0.6B"}]}
                    """));
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override
                public void onOpen(WebSocket socket, okhttp3.Response response) {
                    socket.send("{\"type\":\"session.created\",\"id\":\"srv\",\"created\":1}");
                }

                @Override
                public void onMessage(WebSocket socket, String text) {
                    JSONObject frame = json(text);
                    String type = frame.optString("type");
                    clientTypes.add(type);
                    if ("input_audio_buffer.commit".equals(type)) {
                        socket.send("{\"type\":\"transcription.delta\",\"delta\":\"你好 world\"}");
                        socket.send("{\"type\":\"transcription.done\",\"text\":\"你好 world\",\"usage\":null}");
                    }
                }

                @Override
                public void onClosing(WebSocket socket, int code, String reason) {
                    socket.close(code, reason);
                }
            }));
            server.start();

            Qwen3AsrVllmClient client = new Qwen3AsrVllmClient();
            Qwen3AsrVllmProvider provider = new Qwen3AsrVllmProvider(
                    config(server, Optional.empty()),
                    client,
                    noSecretAccess(),
                    new InlineWorker());
            try {
                assertEquals(
                        RecognitionRoute.PrivacyClass.LOCAL_NETWORK,
                        provider.descriptor().capabilities().privacyClass());
                assertTrue(provider.descriptor().capabilities().supportsStreaming());
                assertFalse(provider.descriptor().capabilities().supportsPartialRevision());
                assertFalse(provider.descriptor().capabilities().supportsEndpointing());
                assertTrue(provider.descriptor().capabilities().supportsAudioUpload());
                assertTrue(provider.probe() instanceof ProviderRegistry.ObservedUnavailable);

                List<ProviderRegistry.ProbeObservation> probes = new ArrayList<>();
                assertTrue(provider.refreshCapabilities(probes::add));
                assertEquals(1, probes.size());
                assertTrue(probes.get(0) instanceof ProviderRegistry.ObservedAvailable);
                assertTrue(provider.probe() instanceof ProviderRegistry.ObservedAvailable);

                WebSocketStreamingProvider.StartRequest request =
                        new WebSocketStreamingProvider.StartRequest(SESSION, "zh-Hans-CN");
                assertTrue(provider.prepare(request) instanceof RecognitionProvider.Prepared);
                RecordingSink sink = new RecordingSink();
                WebSocketStreamingProvider.StreamingSession session =
                        provider.start(request, sink);
                assertTrue(sink.ready.await(2L, TimeUnit.SECONDS));
                assertTrue(session.acceptPcm(new byte[]{1, 2, 3, 4}, 4));
                session.stop();
                assertTrue(sink.terminal.await(2L, TimeUnit.SECONDS));
                assertEquals(
                        List.of("Preparing", "Ready", "Partial", "Endpoint", "Final"),
                        sink.kinds());
                assertEquals("你好 world", ((RecognitionEvent.Final)
                        sink.events.get(sink.events.size() - 1)).text());
                assertEquals(
                        List.of(
                                "session.update",
                                "input_audio_buffer.append",
                                "input_audio_buffer.commit"),
                        clientTypes);

                RecordedRequest probe = server.takeRequest(2L, TimeUnit.SECONDS);
                RecordedRequest realtime = server.takeRequest(2L, TimeUnit.SECONDS);
                assertEquals("/v1/models", probe.getPath());
                assertEquals("/v1/realtime", realtime.getPath());
            } finally {
                provider.close();
            }
        }
    }

    @Test
    public void unavailableProbeBlocksPrepareStartAndMapsStableFailure() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(jsonResponse("{\"object\":\"list\",\"data\":[]}"));
            server.start();
            Qwen3AsrVllmProvider provider = new Qwen3AsrVllmProvider(
                    config(server, Optional.empty()),
                    new Qwen3AsrVllmClient(),
                    noSecretAccess(),
                    new InlineWorker());
            try {
                List<ProviderRegistry.ProbeObservation> probes = new ArrayList<>();
                assertTrue(provider.refreshCapabilities(probes::add));
                assertEquals(
                        RecognitionRoute.FailureClass.MODEL_MISSING,
                        ((ProviderRegistry.ObservedUnavailable) probes.get(0)).failureClass());
                WebSocketStreamingProvider.StartRequest request =
                        new WebSocketStreamingProvider.StartRequest(SESSION, "");
                RecognitionProvider.NotPrepared notPrepared =
                        (RecognitionProvider.NotPrepared) provider.prepare(request);
                assertEquals(
                        RecognitionRoute.FailureClass.MODEL_MISSING,
                        notPrepared.failureClass());
                RecordingSink sink = new RecordingSink();
                WebSocketStreamingProvider.StreamingSession rejected =
                        provider.start(request, sink);
                assertEquals(0, rejected.acceptedPcmBytes());
                assertEquals(1, sink.events.size());
                assertEquals(
                        RecognitionRoute.FailureClass.MODEL_MISSING,
                        ((RecognitionEvent.Failure) sink.events.get(0)).failureClass());
                assertEquals("/v1/models", server.takeRequest(2L, TimeUnit.SECONDS).getPath());
                assertNull(server.takeRequest(250L, TimeUnit.MILLISECONDS));
            } finally {
                provider.close();
            }
        }
    }

    @Test
    public void credentialIsScopedToProbeAndRealtimeWithoutEnteringDiagnostics() throws Exception {
        SecretRef reference = new SecretRef(SecretRef.Kind.ASR, "sec_abcdefghijklmnop");
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(jsonResponse("""
                    {"object":"list","data":[{"id":"Qwen/Qwen3-ASR-0.6B"}]}
                    """));
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override
                public void onOpen(WebSocket socket, okhttp3.Response response) {
                    socket.send("{\"type\":\"error\",\"error\":\"hidden\",\"code\":\"model_not_found\"}");
                }
            }));
            server.start();
            TrackingCredentialAccess access = new TrackingCredentialAccess();
            Qwen3AsrVllmProvider provider = new Qwen3AsrVllmProvider(
                    config(server, Optional.of(reference)),
                    new Qwen3AsrVllmClient(),
                    access,
                    new InlineWorker());
            try {
                assertTrue(provider.refreshCapabilities(ignored -> {}));
                assertEquals(1, access.uses);
                WebSocketStreamingProvider.StartRequest request =
                        new WebSocketStreamingProvider.StartRequest(SESSION, "");
                RecordingSink sink = new RecordingSink();
                provider.start(request, sink);
                assertTrue(sink.terminal.await(2L, TimeUnit.SECONDS));
                assertEquals(2, access.uses);
                assertFalse(provider.toString().contains("temporary-secret"));
                assertFalse(provider.descriptor().toString().contains(MODEL));
                RecordedRequest probe = server.takeRequest(2L, TimeUnit.SECONDS);
                RecordedRequest realtime = server.takeRequest(2L, TimeUnit.SECONDS);
                assertEquals("Bearer temporary-secret", probe.getHeader("Authorization"));
                assertEquals("Bearer temporary-secret", realtime.getHeader("Authorization"));
            } finally {
                provider.close();
            }
        }
    }

    @Test
    public void publicHttpsIsDeclaredPublicAndCloseInvalidatesDeferredProbe() {
        ProviderConfig.Asr remote = new ProviderConfig.Asr(
                "qwen.remote",
                "Qwen Remote",
                Optional.of(new ProviderConfig.Endpoint("https://asr.example.test/v1")),
                Optional.of(MODEL),
                Optional.empty(),
                true);
        DeferredWorker worker = new DeferredWorker();
        Qwen3AsrVllmProvider provider = new Qwen3AsrVllmProvider(
                remote,
                new Qwen3AsrVllmClient(),
                noSecretAccess(),
                worker);
        List<ProviderRegistry.ProbeObservation> callbacks = new ArrayList<>();
        assertEquals(
                RecognitionRoute.PrivacyClass.PUBLIC_NETWORK,
                provider.descriptor().capabilities().privacyClass());
        assertTrue(provider.refreshCapabilities(callbacks::add));
        provider.close();
        worker.runDeferred();
        assertTrue(callbacks.isEmpty());
        assertTrue(provider.probe() instanceof ProviderRegistry.ObservedUnavailable);
        assertFalse(provider.toString().contains("asr.example.test"));
    }

    @Test
    public void oneProbeAtATimeAndWorkerFailureRemainBounded() {
        DeferredWorker worker = new DeferredWorker();
        ProviderConfig.Asr config = new ProviderConfig.Asr(
                "qwen.local",
                "Qwen Local",
                Optional.of(new ProviderConfig.Endpoint("http://127.0.0.1:8000")),
                Optional.of(MODEL),
                Optional.empty(),
                true);
        Qwen3AsrVllmProvider provider = new Qwen3AsrVllmProvider(
                config,
                new Qwen3AsrVllmClient(),
                noSecretAccess(),
                worker);
        try {
            assertTrue(provider.refreshCapabilities(ignored -> {}));
            assertFalse(provider.refreshCapabilities(ignored -> {}));
            assertFalse(provider.toString().contains(MODEL));
        } finally {
            provider.close();
        }
    }

    private static ProviderConfig.Asr config(
            MockWebServer server,
            Optional<SecretRef> secret) {
        return new ProviderConfig.Asr(
                "qwen.local",
                "Qwen Local",
                Optional.of(new ProviderConfig.Endpoint(server.url("/").toString())),
                Optional.of(MODEL),
                secret,
                true);
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static JSONObject json(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception error) {
            throw new AssertionError("invalid client frame", error);
        }
    }

    private static Qwen3AsrVllmProvider.CredentialAccess noSecretAccess() {
        return new Qwen3AsrVllmProvider.CredentialAccess() {
            @Override
            public <T> T use(
                    SecretRef reference,
                    Qwen3AsrVllmProvider.CredentialOperation<T> operation) throws Exception {
                throw new AssertionError("credential access was not expected");
            }
        };
    }

    private static final class TrackingCredentialAccess
            implements Qwen3AsrVllmProvider.CredentialAccess {
        private int uses;

        @Override
        public <T> T use(
                SecretRef reference,
                Qwen3AsrVllmProvider.CredentialOperation<T> operation) throws Exception {
            uses++;
            char[] credential = "temporary-secret".toCharArray();
            try {
                return operation.apply(credential);
            } finally {
                Arrays.fill(credential, '\0');
            }
        }
    }

    private static final class InlineWorker implements Qwen3AsrVllmProvider.ProbeWorker {
        @Override
        public void execute(Runnable action) {
            action.run();
        }

        @Override
        public void close() {}
    }

    private static final class DeferredWorker implements Qwen3AsrVllmProvider.ProbeWorker {
        private Runnable deferred;

        @Override
        public void execute(Runnable action) {
            deferred = action;
        }

        @Override
        public void close() {}

        private void runDeferred() {
            if (deferred != null) deferred.run();
        }
    }

    private static final class RecordingSink implements RecognitionProvider.EventSink {
        private final List<RecognitionEvent> events = new ArrayList<>();
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public synchronized void onEvent(RecognitionEvent event) {
            events.add(event);
            if (event instanceof RecognitionEvent.Ready) ready.countDown();
            if (event.terminal()) terminal.countDown();
        }

        private synchronized List<String> kinds() {
            return events.stream().map(event -> event.getClass().getSimpleName()).toList();
        }
    }
}
