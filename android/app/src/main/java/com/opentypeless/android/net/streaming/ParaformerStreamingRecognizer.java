package com.opentypeless.android.net.streaming;

import com.opentypeless.android.audio.AudioCapture;
import com.opentypeless.android.audio.StreamingAudioResult;
import com.opentypeless.android.net.EndpointNormalizer;
import com.opentypeless.android.settings.AppSettings;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Bounded, cancellable DashScope Paraformer realtime transport. */
public final class ParaformerStreamingRecognizer implements StreamingRecognitionEngine {
    private static final long START_TIMEOUT_SECONDS = 10L;
    private static final long FINISH_TIMEOUT_SECONDS = 15L;
    private static final long MAX_OUTGOING_QUEUE_BYTES = 256L * 1_024L;

    private final OkHttpClient client;
    private final AtomicReference<Session> active = new AtomicReference<>();

    public ParaformerStreamingRecognizer() {
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build();
    }

    @Override
    public Result recognize(
            AppSettings settings,
            AudioCapture audioCapture,
            AudioCapture.Session captureSession,
            AudioCapture.CaptureListener captureListener,
            StreamingRecognitionEngine.Listener listener) {
        if (settings == null
                || audioCapture == null
                || captureSession == null
                || captureListener == null
                || listener == null) {
            throw new IllegalArgumentException("Streaming recognition arguments are required");
        }
        String endpoint = EndpointNormalizer.dashScopeWebSocket(settings.streamingBaseUrl());
        String apiKey = requireApiKey(settings.streamingApiKey());
        Session session = new Session(
                UUID.randomUUID().toString(),
                settings,
                listener);
        if (!active.compareAndSet(null, session)) {
            throw new IllegalStateException("Another streaming recognition session is active");
        }
        try {
            session.connect(endpoint, apiKey);
            session.awaitStarted();
            StreamingAudioResult audio = audioCapture.stream(
                    captureSession,
                    settings.boundedMaxRecordingSeconds(),
                    captureListener,
                    session::sendAudio);
            listener.onFinishing();
            String text = session.finishAndAwait();
            if (text.isBlank()) {
                throw new IllegalStateException("Streaming recognition returned no text");
            }
            return new Result(
                    text,
                    audio.durationMs(),
                    audio.reachedLimit(),
                    audio.autoStopped());
        } finally {
            active.compareAndSet(session, null);
            session.close();
        }
    }

    @Override
    public void cancelActiveSession() {
        Session session = active.getAndSet(null);
        if (session != null) session.cancel();
    }

    @Override
    public void shutdown() {
        cancelActiveSession();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    final class Session extends WebSocketListener {
        private final String taskId;
        private final AppSettings settings;
        private final StreamingRecognitionEngine.Listener listener;
        private final ParaformerTranscriptAssembler assembler =
                new ParaformerTranscriptAssembler();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();

        private volatile WebSocket webSocket;
        private final AtomicReference<String> failure = new AtomicReference<>("");

        Session(
                String taskId,
                AppSettings settings,
                StreamingRecognitionEngine.Listener listener) {
            this.taskId = taskId;
            this.settings = settings;
            this.listener = listener;
        }

        void connect(String endpoint, String apiKey) {
            Request request = new Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", "OpenTypeless-Android/0.3")
                    .build();
            webSocket = client.newWebSocket(request, this);
        }

        void awaitStarted() {
            await(started, START_TIMEOUT_SECONDS, "Streaming service did not start in time");
        }

        void sendAudio(byte[] bytes, int offset, int length) {
            throwIfFailedOrCancelled();
            WebSocket socket = webSocket;
            if (socket == null || started.getCount() != 0L) {
                throw new IllegalStateException("Streaming service is not ready for audio");
            }
            if (socket.queueSize() > MAX_OUTGOING_QUEUE_BYTES) {
                fail("Network is too slow for bounded realtime audio");
                throwIfFailedOrCancelled();
            }
            if (!socket.send(ByteString.of(bytes, offset, length))) {
                fail("Streaming audio transport closed unexpectedly");
                throwIfFailedOrCancelled();
            }
        }

        String finishAndAwait() {
            throwIfFailedOrCancelled();
            WebSocket socket = webSocket;
            if (socket == null || !socket.send(ParaformerProtocol.finishTask(taskId))) {
                fail("Unable to finish streaming recognition");
            }
            await(finished, FINISH_TIMEOUT_SECONDS, "Streaming results timed out");
            throwIfFailedOrCancelled();
            return assembler.finalText().trim();
        }

        void cancel() {
            cancelled.set(true);
            WebSocket socket = webSocket;
            if (socket != null) socket.cancel();
            started.countDown();
            finished.countDown();
        }

        void close() {
            WebSocket socket = webSocket;
            if (socket == null) return;
            if (terminal.get() && failure.get().isEmpty() && !cancelled.get()) {
                socket.close(1000, "complete");
            } else {
                socket.cancel();
            }
        }

        @Override
        public void onOpen(WebSocket socket, Response response) {
            if (cancelled.get()) {
                socket.cancel();
                return;
            }
            String command = ParaformerProtocol.runTask(
                    taskId,
                    settings.streamingModel(),
                    settings.language(),
                    settings.streamingVocabularyId());
            if (!socket.send(command)) fail("Unable to start streaming recognition");
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            if (cancelled.get() || terminal.get()) return;
            ParaformerProtocol.Event event;
            try {
                event = ParaformerProtocol.parse(text, taskId);
            } catch (RuntimeException error) {
                fail("Streaming service returned an invalid event");
                return;
            }
            switch (event.type()) {
                case TASK_STARTED -> started.countDown();
                case RESULT -> {
                    try {
                        ParaformerTranscriptAssembler.Snapshot transcript = assembler.accept(event);
                        listener.onTranscript(transcript.stableText(), transcript.unstableText());
                    } catch (IllegalArgumentException error) {
                        fail("Streaming transcript exceeded the safety limit");
                    } catch (RuntimeException error) {
                        fail("Unable to deliver the streaming transcript");
                    }
                }
                case TASK_FINISHED -> {
                    terminal.set(true);
                    finished.countDown();
                }
                case TASK_FAILED -> fail(providerFailure(event));
                case IGNORED -> {
                    // Heartbeats and future optional events intentionally do not affect text.
                }
            }
        }

        @Override
        public void onFailure(WebSocket socket, Throwable error, Response response) {
            int status = response == null ? 0 : response.code();
            if (response != null) response.close();
            if (cancelled.get()) return;
            if (status == 401 || status == 403) {
                fail("DashScope authentication failed (" + status + ")");
            } else if (status > 0) {
                fail("Streaming WebSocket handshake failed (" + status + ")");
            } else {
                fail("Streaming connection failed");
            }
        }

        @Override
        public void onClosed(WebSocket socket, int code, String reason) {
            if (!terminal.get() && !cancelled.get()) {
                fail("Streaming connection closed before final results");
            }
        }

        private void await(CountDownLatch latch, long timeoutSeconds, String timeoutMessage) {
            boolean completed;
            try {
                completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                cancel();
                throw new CancellationException("Streaming recognition interrupted");
            }
            if (!completed) fail(timeoutMessage);
            throwIfFailedOrCancelled();
        }

        private void fail(String message) {
            failure.compareAndSet("", safeMessage(message));
            terminal.set(true);
            started.countDown();
            finished.countDown();
            WebSocket socket = webSocket;
            if (socket != null) socket.cancel();
        }

        private void throwIfFailedOrCancelled() {
            if (cancelled.get()) throw new CancellationException("Streaming recognition cancelled");
            String message = failure.get();
            if (!message.isEmpty()) throw new IllegalStateException(message);
        }

        private String providerFailure(ParaformerProtocol.Event event) {
            String code = event.errorCode().isBlank() ? "unknown" : event.errorCode();
            // Provider-controlled detail may echo request text. Keep the bounded error code for
            // diagnosis, but never surface the free-form response message in the IME.
            return "Streaming recognition failed (" + code + ")";
        }
    }

    private static String safeMessage(String value) {
        String clean = value == null
                ? ""
                : value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").trim();
        int count = clean.codePointCount(0, clean.length());
        if (count > 300) clean = clean.substring(0, clean.offsetByCodePoints(0, 300));
        return clean.isEmpty() ? "Streaming recognition failed" : clean;
    }

    /** Revalidates migrated or damaged stored credentials before constructing an HTTP header. */
    static String requireApiKey(String value) {
        String apiKey = value == null ? "" : value.trim();
        if (apiKey.isEmpty()) {
            throw new IllegalArgumentException("DashScope API key is required");
        }
        if (apiKey.codePointCount(0, apiKey.length()) > 4_096) {
            throw new IllegalArgumentException("DashScope API key is too long");
        }
        if (apiKey.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "DashScope API key contains unsupported control characters");
        }
        return apiKey;
    }
}
