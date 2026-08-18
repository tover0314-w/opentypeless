package com.opentypeless.android.recognition;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.ProviderConfig;
import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.config.SecretRef;
import com.opentypeless.android.net.OpenAiCompatibleClient;
import com.opentypeless.android.recognition.OpenAiCompatibleUploadProvider.StartRequest;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.speech.core.SessionId;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public final class OpenAiCompatibleUploadProviderTest {
    private MockWebServer server;

    @Before
    public void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    public void exactDescriptorConfigProbePrepareAndLeastAuthorityRequestAreBounded() {
        FakeBackend backend = new FakeBackend();
        ImmediateWorker worker = new ImmediateWorker();
        OpenAiCompatibleUploadProvider provider = provider(backend, worker);
        byte[] mutableAudio = {1, 2, 3, 4};
        StartRequest request = request("contract", mutableAudio, "zh-CN", "private prompt", 900L);
        mutableAudio[0] = 99;

        assertTrue(Modifier.isFinal(OpenAiCompatibleUploadProvider.class.getModifiers()));
        assertFalse(Modifier.isPublic(OpenAiCompatibleUploadProvider.class.getModifiers()));
        assertEquals(
                ProviderDescriptor.declaredForBackend(RecognitionBackend.OPENAI_COMPATIBLE),
                provider.descriptor());
        assertTrue(provider.probe() instanceof ProviderRegistry.ObservedAvailable);
        assertTrue(provider.prepare(request) instanceof RecognitionProvider.Prepared);

        RecordingSink sink = new RecordingSink();
        RecognitionProvider.Session session = provider.start(request, sink);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, backend.audio);
        assertEquals(List.of("Preparing", "Ready", "Endpoint", "Final"), sink.kinds());
        assertEquals(List.of(1L, 2L, 3L, 4L), sink.sequences());
        assertEquals(900L, ((RecognitionEvent.Final) sink.events.get(3))
                .metadata().audioDurationMs().longValue());
        assertTrue(provider.prepare(request) instanceof RecognitionProvider.NotPrepared);
        assertFalse(request.toString().contains("private prompt"));
        assertFalse(request.toString().contains("contract"));
        assertFalse(provider.toString().contains(config().endpoint().orElseThrow().value()));
        assertReleased(session);

        assertThrows(
                IllegalArgumentException.class,
                () -> request("empty", new byte[0], "", "", 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> request("duration", new byte[]{1}, "", "", 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> request(
                        "prompt",
                        new byte[]{1},
                        "",
                        "x".repeat(OpenAiCompatibleUploadProvider.MAX_PROMPT_CODE_POINTS + 1),
                        1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> request("utf16", new byte[]{1}, "", "\uD800", 1L));

        StartRequest closed = request("closed-request", new byte[]{8}, "", "", 1L);
        closed.close();
        assertEquals(0, closed.audioByteCount());
        assertTrue(provider.prepare(closed) instanceof RecognitionProvider.NotPrepared);
    }

    @Test
    public void mockWebServerReceivesOneBoundedMultipartUploadAndFinalEvent() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"cloud transcript\"}"));
        OpenAiCompatibleUploadProvider provider = new OpenAiCompatibleUploadProvider(
                config(),
                new OpenAiCompatibleUploadProvider.ClientUploadBackend(
                        new OpenAiCompatibleClient(), missingCredentials()),
                new ImmediateWorker());
        RecordingSink sink = new RecordingSink();

        provider.start(
                request("http", new byte[]{9, 8, 7, 6}, "zh-CN", "bounded prompt", 1_200L),
                sink);

        assertEquals(List.of("Preparing", "Ready", "Endpoint", "Final"), sink.kinds());
        assertEquals("cloud transcript", ((RecognitionEvent.Final) sink.events.get(3)).text());
        RecordedRequest upload = server.takeRequest(1L, TimeUnit.SECONDS);
        assertEquals("/v1/audio/transcriptions", upload.getPath());
        assertNull(upload.getHeader("Authorization"));
        String body = upload.getBody().readUtf8();
        assertTrue(body.contains("name=\"model\""));
        assertTrue(body.contains("whisper-test"));
        assertTrue(body.contains("name=\"language\""));
        assertTrue(body.contains("zh-CN"));
        assertTrue(body.contains("bounded prompt"));
        assertTrue(body.contains("filename=\"recording.wav\""));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void credentialLeaseIsExactCallScopedAndClearedByItsOwner() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"text\":\"credential path\"}"));
        SecretRef reference = new SecretRef(
                SecretRef.Kind.ASR,
                "sec_abcdefghijklmnop");
        ProviderConfig.Asr credentialConfig = new ProviderConfig.Asr(
                "builtin.openai-compatible",
                "OpenAI Compatible",
                Optional.of(new ProviderConfig.Endpoint(server.url("/v1").toString())),
                Optional.of("whisper-test"),
                Optional.of(reference),
                true);
        char[] leased = "temporary-token".toCharArray();
        int[] accessCount = {0};
        OpenAiCompatibleUploadProvider.CredentialAccess access = (actual, operation) -> {
            accessCount[0]++;
            assertEquals(reference, actual);
            try {
                return operation.apply(leased);
            } finally {
                Arrays.fill(leased, '\0');
            }
        };
        OpenAiCompatibleUploadProvider provider = new OpenAiCompatibleUploadProvider(
                credentialConfig,
                new OpenAiCompatibleUploadProvider.ClientUploadBackend(
                        new OpenAiCompatibleClient(), access),
                new ImmediateWorker());
        RecordingSink sink = new RecordingSink();

        provider.start(
                request("credential", new byte[]{1, 2}, "", "", 1L),
                sink);

        assertEquals(List.of("Preparing", "Ready", "Endpoint", "Final"), sink.kinds());
        assertEquals(1, accessCount[0]);
        assertTrue(Arrays.equals(new char[leased.length], leased));
        assertEquals("Bearer temporary-token", server.takeRequest().getHeader("Authorization"));
        assertFalse(provider.toString().contains("temporary-token"));
    }

    @Test
    public void httpFailuresMapWithoutProviderBodiesOrCredentials() throws Exception {
        Object[][] cases = {
                {401, RecognitionRoute.FailureClass.AUTHENTICATION},
                {402, RecognitionRoute.FailureClass.QUOTA_EXCEEDED},
                {429, RecognitionRoute.FailureClass.RATE_LIMITED},
                {500, RecognitionRoute.FailureClass.SERVER_ERROR},
                {307, RecognitionRoute.FailureClass.PROTOCOL_ERROR}
        };
        OpenAiCompatibleUploadProvider provider = new OpenAiCompatibleUploadProvider(
                config(),
                new OpenAiCompatibleUploadProvider.ClientUploadBackend(
                        new OpenAiCompatibleClient(), missingCredentials()),
                new ImmediateWorker());
        for (int index = 0; index < cases.length; index++) {
            int status = (Integer) cases[index][0];
            MockResponse response = new MockResponse()
                    .setResponseCode(status)
                    .setBody("provider-secret-body-" + index)
                    .setHeader("x-request-id", "req_safe_" + index);
            if (status == 307) response.setHeader("Location", server.url("/stolen"));
            server.enqueue(response);
            RecordingSink sink = new RecordingSink();

            provider.start(
                    request("status-" + index, new byte[]{1}, "", "", 1L),
                    sink);

            assertFailure(sink, (RecognitionRoute.FailureClass) cases[index][1]);
            for (RecognitionEvent event : sink.events) {
                assertFalse(event.toString().contains("provider-secret-body"));
                assertFalse(event.toString().contains("req_safe"));
            }
        }
        assertEquals(cases.length, server.getRequestCount());
    }

    @Test
    public void cancelBeforeQueuedUploadIsSingleTerminalAndClearsAudioWithoutNetwork() {
        FakeBackend backend = new FakeBackend();
        QueuedWorker worker = new QueuedWorker();
        OpenAiCompatibleUploadProvider provider = provider(backend, worker);
        RecordingSink sink = new RecordingSink();
        RecognitionProvider.Session session = provider.start(
                request("cancel", new byte[]{7, 7, 7}, "", "secret", 1L),
                sink);

        session.cancel();
        session.cancel();
        worker.runAll();

        assertEquals(List.of("Preparing", "Cancelled"), sink.kinds());
        assertEquals(1, backend.cancelCount);
        assertEquals(0, backend.transcribeCount);
        assertReleased(session);
    }

    @Test
    public void cancelDuringUploadDropsLateResultAndCloseIsIdempotent() {
        FakeBackend backend = new FakeBackend();
        QueuedWorker worker = new QueuedWorker();
        OpenAiCompatibleUploadProvider provider = provider(backend, worker);
        RecordingSink sink = new RecordingSink();
        RecognitionProvider.Session[] session = new RecognitionProvider.Session[1];
        backend.onTranscribe = () -> session[0].cancel();
        session[0] = provider.start(
                request("during", new byte[]{4, 5}, "", "", 1L), sink);

        worker.runAll();
        provider.close();
        provider.close();

        assertEquals(List.of("Preparing", "Ready", "Cancelled"), sink.kinds());
        assertEquals(1, backend.transcribeCount);
        assertEquals(1, backend.cancelCount);
        assertEquals(1, backend.closeCount);
        assertEquals(1, worker.closeCount);
        assertTrue(provider.probe() instanceof ProviderRegistry.ObservedUnavailable);
        RecordingSink closedSink = new RecordingSink();
        provider.start(request("after-close", new byte[]{1}, "", "", 1L), closedSink);
        assertFailure(closedSink, RecognitionRoute.FailureClass.UNAVAILABLE);
    }

    @Test
    public void busyAndConsumedStartsFailOnlyTheRejectedSession() {
        FakeBackend backend = new FakeBackend();
        QueuedWorker worker = new QueuedWorker();
        OpenAiCompatibleUploadProvider provider = provider(backend, worker);
        RecordingSink active = new RecordingSink();
        StartRequest firstRequest = request("active", new byte[]{1}, "", "", 1L);
        provider.start(firstRequest, active);

        RecordingSink busy = new RecordingSink();
        StartRequest busyRequest = request("busy", new byte[]{2}, "", "", 1L);
        provider.start(busyRequest, busy);
        assertFailure(busy, RecognitionRoute.FailureClass.RECOGNIZER_BUSY);
        assertTrue(busyRequest.available());

        worker.runAll();
        assertEquals(List.of("Preparing", "Ready", "Endpoint", "Final"), active.kinds());
        RecordingSink consumed = new RecordingSink();
        provider.start(firstRequest, consumed);
        assertFailure(consumed, RecognitionRoute.FailureClass.INTERNAL_ERROR);
    }

    @Test
    public void sinkFailureRevokesSessionCancelsBackendAndDoesNotRunUpload() {
        FakeBackend backend = new FakeBackend();
        QueuedWorker worker = new QueuedWorker();
        OpenAiCompatibleUploadProvider provider = provider(backend, worker);
        RecognitionProvider.Session session = provider.start(
                request("sink", new byte[]{1, 2}, "", "", 1L),
                event -> {
                    throw new IllegalStateException("sink-secret");
                });

        worker.runAll();

        assertEquals(1, backend.cancelCount);
        assertEquals(0, backend.transcribeCount);
        assertReleased(session);
        assertFalse(session.toString().contains("sink-secret"));
    }

    @Test
    public void stableTransportAndProtocolFailuresNeverUseExceptionMessages() {
        Object[][] cases = {
                {new SocketTimeoutException("secret-timeout"),
                        RecognitionRoute.FailureClass.NETWORK_TIMEOUT},
                {new UnknownHostException("secret-host"),
                        RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE},
                {new IOException("secret-io"),
                        RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE},
                {new IllegalArgumentException("secret-protocol"),
                        RecognitionRoute.FailureClass.PROTOCOL_ERROR},
                {new Exception("secret-internal"),
                        RecognitionRoute.FailureClass.INTERNAL_ERROR}
        };
        for (int index = 0; index < cases.length; index++) {
            FakeBackend backend = new FakeBackend();
            backend.failure = (Exception) cases[index][0];
            RecordingSink sink = new RecordingSink();
            provider(backend, new ImmediateWorker()).start(
                    request("failure-" + index, new byte[]{1}, "", "", 1L), sink);
            assertFailure(sink, (RecognitionRoute.FailureClass) cases[index][1]);
            for (RecognitionEvent event : sink.events) {
                assertFalse(event.toString().contains("secret-"));
            }
        }
    }

    @Test
    public void blankMalformedAndOversizedBackendTextFailClosedOnce() {
        Object[][] cases = {
                {" ", RecognitionRoute.FailureClass.NO_MATCH},
                {"\uD800", RecognitionRoute.FailureClass.PROTOCOL_ERROR},
                {"x".repeat(RecognitionEvent.MAX_TEXT_CODE_POINTS + 1),
                        RecognitionRoute.FailureClass.PROTOCOL_ERROR}
        };
        for (int index = 0; index < cases.length; index++) {
            FakeBackend backend = new FakeBackend();
            backend.result = (String) cases[index][0];
            RecordingSink sink = new RecordingSink();
            provider(backend, new ImmediateWorker()).start(
                    request("text-" + index, new byte[]{1}, "", "", 1L), sink);
            assertFailure(sink, (RecognitionRoute.FailureClass) cases[index][1]);
            assertEquals(1, sink.terminalCount());
        }
    }

    @Test
    public void asynchronousCancellationDisconnectsRealClientAndSuppressesLateHttpFailure()
            throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeadersDelay(1L, TimeUnit.SECONDS)
                .setBody("{\"text\":\"too late\"}"));
        AsyncWorker worker = new AsyncWorker();
        OpenAiCompatibleUploadProvider provider = new OpenAiCompatibleUploadProvider(
                config(),
                new OpenAiCompatibleUploadProvider.ClientUploadBackend(
                        new OpenAiCompatibleClient(), missingCredentials()),
                worker);
        RecordingSink sink = new RecordingSink();
        RecognitionProvider.Session session = provider.start(
                request("disconnect", new byte[]{1, 2, 3}, "", "", 1L), sink);
        assertTrue(sink.ready.await(2L, TimeUnit.SECONDS));
        assertTrue(server.takeRequest(2L, TimeUnit.SECONDS) != null);

        session.cancel();

        assertTrue(sink.terminal.await(2L, TimeUnit.SECONDS));
        assertEquals(List.of("Preparing", "Ready", "Cancelled"), sink.kinds());
        assertEquals(1, sink.terminalCount());
        provider.close();
    }

    private OpenAiCompatibleUploadProvider provider(
            FakeBackend backend,
            OpenAiCompatibleUploadProvider.Worker worker) {
        return new OpenAiCompatibleUploadProvider(config(), backend, worker);
    }

    private static OpenAiCompatibleUploadProvider.CredentialAccess missingCredentials() {
        return (reference, operation) -> {
            throw new OpenAiCompatibleUploadProvider.CredentialUnavailableException();
        };
    }

    private ProviderConfig.Asr config() {
        return new ProviderConfig.Asr(
                "builtin.openai-compatible",
                "OpenAI Compatible",
                Optional.of(new ProviderConfig.Endpoint(server.url("/v1").toString())),
                Optional.of("whisper-test"),
                Optional.empty(),
                true);
    }

    private static StartRequest request(
            String id,
            byte[] wav,
            String language,
            String prompt,
            long durationMs) {
        return new StartRequest(SessionId.of(id), wav, language, prompt, durationMs);
    }

    private static void assertFailure(
            RecordingSink sink,
            RecognitionRoute.FailureClass expected) {
        assertEquals(1, sink.terminalCount());
        RecognitionEvent event = sink.events.get(sink.events.size() - 1);
        assertTrue(event instanceof RecognitionEvent.Failure);
        assertEquals(expected, ((RecognitionEvent.Failure) event).failureClass());
    }

    private static void assertReleased(RecognitionProvider.Session session) {
        try {
            for (String fieldName : List.of("audio", "language", "prompt", "sink")) {
                Field field = session.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                assertNull(field.get(session));
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
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

        private synchronized List<Long> sequences() {
            return events.stream().map(RecognitionEvent::sequence).toList();
        }

        private synchronized int terminalCount() {
            return (int) events.stream().filter(RecognitionEvent::terminal).count();
        }
    }

    private static final class FakeBackend
            implements OpenAiCompatibleUploadProvider.UploadBackend {
        private String result = "recognized";
        private Exception failure;
        private Runnable onTranscribe;
        private byte[] audio;
        private int transcribeCount;
        private int cancelCount;
        private int closeCount;

        @Override
        public String transcribe(
                ProviderConfig.Asr config,
                byte[] wav,
                String language,
                String prompt,
                long durationMs,
                BooleanSupplier cancelled) throws Exception {
            transcribeCount++;
            audio = Arrays.copyOf(wav, wav.length);
            if (onTranscribe != null) onTranscribe.run();
            if (failure != null) throw failure;
            return result;
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

    private static final class ImmediateWorker
            implements OpenAiCompatibleUploadProvider.Worker {
        private int closeCount;

        @Override
        public void execute(Runnable action) {
            action.run();
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class QueuedWorker
            implements OpenAiCompatibleUploadProvider.Worker {
        private final List<Runnable> queued = new ArrayList<>();
        private int closeCount;

        @Override
        public void execute(Runnable action) {
            queued.add(action);
        }

        @Override
        public void close() {
            closeCount++;
        }

        private void runAll() {
            List<Runnable> pending = List.copyOf(queued);
            queued.clear();
            pending.forEach(Runnable::run);
        }
    }

    private static final class AsyncWorker
            implements OpenAiCompatibleUploadProvider.Worker {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        @Override
        public void execute(Runnable action) {
            executor.execute(action);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
