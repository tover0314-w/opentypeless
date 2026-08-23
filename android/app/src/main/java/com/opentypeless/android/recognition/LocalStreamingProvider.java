package com.opentypeless.android.recognition;

import android.content.Context;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.offline.LocalOfflineRecognizer;
import com.opentypeless.android.offline.LocalRealtimeRecognitionClient;
import com.opentypeless.android.offline.OfflineStreamingModelSpec;
import com.opentypeless.android.offline.OfflineStreamingModelStore;
import com.opentypeless.android.recognition.RecognitionFailureMapper.LocalAvailability;
import com.opentypeless.android.speech.core.SessionId;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** STR-005 adapter for the revision-pinned on-device Streaming Paraformer candidate. */
final class LocalStreamingProvider
        implements RecognitionProvider<LocalStreamingProvider.StartRequest> {
    static final int MAX_PCM_FRAME_BYTES = 64 * 1024;
    static final int MAX_QUEUED_PCM_BYTES = 256 * 1024;
    static final int MAX_TOTAL_PCM_BYTES = 17_280_000;
    static final long READY_TIMEOUT_MS = 30_000L;
    static final long FINISH_TIMEOUT_MS = 35_000L;

    private static final String PROVIDER_ID = "builtin.local-streaming-paraformer";

    private final Object lifecycleLock = new Object();
    private final ProviderDescriptor descriptor = new ProviderDescriptor(
            PROVIDER_ID,
            OfflineStreamingModelSpec.REALTIME.displayName(),
            ProviderCapabilities.localStreamingParaformer());
    private final Backend backend;
    private final Worker worker;
    private final Timer timer;

    private SessionState active;
    private boolean closed;

    static LocalStreamingProvider create(Context context) {
        Context application = Objects.requireNonNull(context, "context").getApplicationContext();
        return new LocalStreamingProvider(
                new ClientBackend(application, new LocalRealtimeRecognitionClient(application)),
                new SingleWorker(),
                new ScheduledTimer());
    }

    LocalStreamingProvider(Backend backend, Worker worker, Timer timer) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ProviderRegistry.ProbeObservation probe() {
        synchronized (lifecycleLock) {
            if (closed) {
                return new ProviderRegistry.ObservedUnavailable(
                        RecognitionRoute.FailureClass.UNAVAILABLE);
            }
            RecognitionRoute.FailureClass failure = availabilityFailureLocked();
            return failure == null
                    ? new ProviderRegistry.ObservedAvailable(descriptor.capabilities())
                    : new ProviderRegistry.ObservedUnavailable(failure);
        }
    }

    @Override
    public PreparationResult prepare(StartRequest request) {
        StartRequest safeRequest = Objects.requireNonNull(request, "request");
        synchronized (lifecycleLock) {
            if (closed) return new NotPrepared(RecognitionRoute.FailureClass.UNAVAILABLE);
            if (!safeRequest.available()) {
                return new NotPrepared(RecognitionRoute.FailureClass.INTERNAL_ERROR);
            }
            RecognitionRoute.FailureClass failure = availabilityFailureLocked();
            return failure == null ? new Prepared(descriptor) : new NotPrepared(failure);
        }
    }

    @Override
    public StreamingSession start(StartRequest request, EventSink sink) {
        StartRequest safeRequest = Objects.requireNonNull(request, "request");
        EventSink safeSink = Objects.requireNonNull(sink, "sink");
        SessionState session;
        synchronized (lifecycleLock) {
            if (closed) {
                return detachedFailure(
                        safeRequest.sessionId(),
                        safeSink,
                        RecognitionRoute.FailureClass.UNAVAILABLE);
            }
            if (active != null) {
                return detachedFailure(
                        safeRequest.sessionId(),
                        safeSink,
                        RecognitionRoute.FailureClass.RECOGNIZER_BUSY);
            }
            RecognitionRoute.FailureClass availability = availabilityFailureLocked();
            if (availability != null) {
                return detachedFailure(safeRequest.sessionId(), safeSink, availability);
            }
            SessionId claimed = safeRequest.claim();
            if (claimed == null) {
                return detachedFailure(
                        safeRequest.sessionId(),
                        safeSink,
                        RecognitionRoute.FailureClass.INTERNAL_ERROR);
            }
            session = new SessionState(claimed, safeSink);
            active = session;
            if (!emitNonTerminalLocked(
                    session,
                    new RecognitionEvent.Preparing(
                            claimed, nextSequenceLocked(session)))) {
                return session;
            }
        }

        scheduleReadyTimeout(session);
        try {
            worker.execute(() -> openOnWorker(session));
        } catch (RuntimeException ignored) {
            finishFailure(session, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        }
        return session;
    }

    private void openOnWorker(SessionState session) {
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
        }
        Connection connection;
        try {
            connection = Objects.requireNonNull(
                    backend.open(new BackendListener() {
                        @Override
                        public void onReady() {
                            backendReady(session);
                        }

                        @Override
                        public void onPartial(String text) {
                            backendPartial(session, text);
                        }
                    }),
                    "connection");
        } catch (Exception error) {
            finishRuntime(session, error);
            return;
        }
        boolean retained;
        synchronized (lifecycleLock) {
            retained = isCurrentLocked(session) && session.connection == null;
            if (retained) session.connection = connection;
        }
        if (!retained) {
            cancelQuietly(connection);
        }
    }

    @Override
    public void close() {
        SessionState current;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            current = active;
        }
        if (current != null) cancel(current);
        try {
            backend.close();
        } catch (RuntimeException ignored) {
            // Provider authority is already revoked and backend details stay private.
        }
        try {
            worker.close();
        } catch (RuntimeException ignored) {
            // Continue releasing the independent timer even if worker teardown fails.
        }
        try {
            timer.close();
        } catch (RuntimeException ignored) {
            // Provider authority and all session references are already revoked.
        }
    }

    private void backendReady(SessionState session) {
        boolean finishNow = false;
        Connection cancel = null;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session) || session.ready) return;
            session.ready = true;
            cancelTicket(session.readyTimeout);
            session.readyTimeout = null;
            Connection currentConnection = session.connection;
            if (!emitNonTerminalLocked(
                    session,
                    new RecognitionEvent.Ready(
                            session.sessionId, nextSequenceLocked(session)))) {
                cancel = currentConnection;
            } else {
                finishNow = session.stopping
                        && session.queuedPcmBytes == 0
                        && !session.finishStarted;
                if (finishNow) session.finishStarted = true;
            }
        }
        cancelQuietly(cancel);
        if (finishNow) dispatchFinish(session);
    }

    private void backendPartial(SessionState session, String text) {
        Connection cancel = null;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            if (!session.ready || session.stopping && session.connection == null) {
                cancel = finishFailureLocked(
                        session, RecognitionRoute.FailureClass.PROTOCOL_ERROR);
            } else {
                String safe;
                try {
                    safe = boundedText(text, true);
                } catch (RuntimeException ignored) {
                    cancel = finishFailureLocked(
                            session, RecognitionRoute.FailureClass.PROTOCOL_ERROR);
                    safe = null;
                }
                if (safe != null && !safe.equals(session.lastPartialText)) {
                    long sequence = nextSequenceLocked(session);
                    Long revisionOf = session.lastPartialText.isEmpty()
                            || safe.startsWith(session.lastPartialText)
                            ? null
                            : session.lastPartialSequence;
                    RecognitionEvent.Partial event = new RecognitionEvent.Partial(
                            session.sessionId,
                            sequence,
                            safe,
                            null,
                            revisionOf);
                    session.lastPartialText = safe;
                    session.lastPartialSequence = sequence;
                    Connection currentConnection = session.connection;
                    if (!emitNonTerminalLocked(session, event)) cancel = currentConnection;
                }
            }
        }
        cancelQuietly(cancel);
    }

    private boolean acceptPcm(SessionState session, byte[] pcm, int length) {
        Objects.requireNonNull(pcm, "pcm");
        if (length <= 0
                || length > MAX_PCM_FRAME_BYTES
                || length > pcm.length
                || (length & 1) != 0) {
            throw new IllegalArgumentException("PCM frame is outside its bound");
        }
        byte[] copied;
        boolean overflow = false;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session) || session.stopping) {
                return false;
            }
            if (session.acceptedPcmBytes > MAX_TOTAL_PCM_BYTES - length
                    || session.queuedPcmBytes > MAX_QUEUED_PCM_BYTES - length) {
                overflow = true;
            } else {
                session.acceptedPcmBytes += length;
                session.queuedPcmBytes += length;
            }
        }
        if (overflow) {
            finishFailure(session, RecognitionRoute.FailureClass.AUDIO_ERROR);
            return false;
        }
        copied = Arrays.copyOf(pcm, length);
        try {
            worker.execute(() -> deliverPcmOnWorker(session, copied));
            return true;
        } catch (RuntimeException ignored) {
            Arrays.fill(copied, (byte) 0);
            finishFailure(session, RecognitionRoute.FailureClass.INTERNAL_ERROR);
            releaseQueuedPcm(session, copied.length);
            return false;
        }
    }

    private void deliverPcmOnWorker(SessionState session, byte[] copied) {
        Connection connection;
        synchronized (lifecycleLock) {
            connection = isCurrentLocked(session) ? session.connection : null;
        }
        try {
            if (connection == null) {
                if (isCurrent(session)) {
                    finishFailure(session, RecognitionRoute.FailureClass.INTERNAL_ERROR);
                }
                return;
            }
            connection.acceptPcm(copied, copied.length);
        } catch (RuntimeException error) {
            finishRuntime(session, error);
        } finally {
            releaseQueuedPcm(session, copied.length);
            Arrays.fill(copied, (byte) 0);
        }
    }

    private void releaseQueuedPcm(SessionState session, int length) {
        boolean finishNow = false;
        synchronized (lifecycleLock) {
            session.queuedPcmBytes = Math.max(0, session.queuedPcmBytes - length);
            if (isCurrentLocked(session)
                    && session.stopping
                    && session.ready
                    && session.queuedPcmBytes == 0
                    && !session.finishStarted) {
                session.finishStarted = true;
                finishNow = true;
            }
        }
        if (finishNow) dispatchFinish(session);
    }

    private void stop(SessionState session) {
        Connection cancel = null;
        boolean finishNow = false;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session) || session.stopping) return;
            session.stopping = true;
            if (session.acceptedPcmBytes == 0) {
                cancel = finishFailureLocked(session, RecognitionRoute.FailureClass.NO_MATCH);
            } else if (session.ready
                    && session.queuedPcmBytes == 0
                    && !session.finishStarted) {
                session.finishStarted = true;
                finishNow = true;
            }
        }
        cancelQuietly(cancel);
        if (finishNow) dispatchFinish(session);
    }

    private void dispatchFinish(SessionState session) {
        scheduleFinishTimeout(session);
        try {
            worker.execute(() -> finishOnWorker(session));
        } catch (RuntimeException ignored) {
            finishFailure(session, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        }
    }

    private void finishOnWorker(SessionState session) {
        Connection connection;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session) || !session.stopping) return;
            connection = session.connection;
        }
        if (connection == null) {
            finishFailure(session, RecognitionRoute.FailureClass.INTERNAL_ERROR);
            return;
        }
        String finalText;
        try {
            finalText = connection.finish();
        } catch (Exception error) {
            finishRuntime(session, error);
            return;
        }
        Connection cancel = null;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            String safe;
            try {
                safe = boundedText(finalText, false);
            } catch (RuntimeException ignored) {
                cancel = finishFailureLocked(
                        session, RecognitionRoute.FailureClass.PROTOCOL_ERROR);
                safe = null;
            }
            if (safe != null) {
                RecognitionEvent.Final event = new RecognitionEvent.Final(
                        session.sessionId,
                        nextSequenceLocked(session),
                        safe,
                        new RecognitionMetadata(
                                null, null, durationMs(session.acceptedPcmBytes)));
                markTerminalLocked(session);
                emitTerminalLocked(session, event);
            }
        }
        cancelQuietly(cancel);
    }

    private void cancel(SessionState session) {
        Connection connection;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            RecognitionEvent.Cancelled event = new RecognitionEvent.Cancelled(
                    session.sessionId, nextSequenceLocked(session));
            connection = session.connection;
            markTerminalLocked(session);
            emitTerminalLocked(session, event);
        }
        cancelQuietly(connection);
    }

    private void finishFailure(
            SessionState session,
            RecognitionRoute.FailureClass failureClass) {
        Connection connection;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            connection = finishFailureLocked(session, failureClass);
        }
        cancelQuietly(connection);
    }

    private void finishRuntime(SessionState session, Throwable error) {
        RecognitionRoute.FailureClass failureClass = runtimeFailureClass(error);
        if (failureClass == RecognitionRoute.FailureClass.CANCELLED) {
            cancel(session);
        } else {
            finishFailure(session, failureClass);
        }
    }

    private Connection finishFailureLocked(
            SessionState session,
            RecognitionRoute.FailureClass failureClass) {
        Connection connection = session.connection;
        RecognitionEvent.Failure event = new RecognitionEvent.Failure(
                session.sessionId,
                nextSequenceLocked(session),
                Objects.requireNonNull(failureClass, "failureClass"));
        markTerminalLocked(session);
        emitTerminalLocked(session, event);
        return connection;
    }

    private boolean emitNonTerminalLocked(SessionState session, RecognitionEvent event) {
        if (!isCurrentLocked(session) || session.sink == null) return false;
        try {
            session.sink.onEvent(event);
            return isCurrentLocked(session);
        } catch (RuntimeException ignored) {
            markTerminalLocked(session);
            session.releaseReferences();
            return false;
        }
    }

    private void emitTerminalLocked(SessionState session, RecognitionEvent event) {
        try {
            if (session.sink != null) session.sink.onEvent(event);
        } catch (RuntimeException ignored) {
            // Terminal state is already committed and content remains private.
        } finally {
            session.releaseReferences();
        }
    }

    private StreamingSession detachedFailure(
            SessionId sessionId,
            EventSink sink,
            RecognitionRoute.FailureClass failureClass) {
        SessionState detached = new SessionState(sessionId, sink);
        detached.terminal = true;
        emitTerminalLocked(
                detached,
                new RecognitionEvent.Failure(sessionId, 1L, failureClass));
        return detached;
    }

    private void scheduleReadyTimeout(SessionState session) {
        Ticket ticket;
        try {
            ticket = timer.schedule(
                    () -> finishFailure(session, RecognitionRoute.FailureClass.UNAVAILABLE),
                    READY_TIMEOUT_MS);
        } catch (RuntimeException ignored) {
            finishFailure(session, RecognitionRoute.FailureClass.INTERNAL_ERROR);
            return;
        }
        synchronized (lifecycleLock) {
            if (isCurrentLocked(session) && !session.ready) {
                session.readyTimeout = ticket;
                return;
            }
        }
        ticket.cancel();
    }

    private void scheduleFinishTimeout(SessionState session) {
        Ticket ticket;
        try {
            ticket = timer.schedule(
                    () -> finishFailure(session, RecognitionRoute.FailureClass.UNAVAILABLE),
                    FINISH_TIMEOUT_MS);
        } catch (RuntimeException ignored) {
            finishFailure(session, RecognitionRoute.FailureClass.INTERNAL_ERROR);
            return;
        }
        synchronized (lifecycleLock) {
            if (isCurrentLocked(session) && session.stopping) {
                session.finishTimeout = ticket;
                return;
            }
        }
        ticket.cancel();
    }

    private RecognitionRoute.FailureClass availabilityFailureLocked() {
        LocalAvailability availability;
        try {
            availability = Objects.requireNonNull(backend.availability(), "availability");
        } catch (RuntimeException ignored) {
            return RecognitionRoute.FailureClass.INTERNAL_ERROR;
        }
        return availability == LocalAvailability.READY
                ? null
                : RecognitionFailureMapper.fromLocalAvailability(availability);
    }

    private RecognitionRoute.FailureClass runtimeFailureClass(Throwable error) {
        LocalAvailability availability;
        try {
            availability = Objects.requireNonNull(backend.availability(), "availability");
        } catch (RuntimeException ignored) {
            return RecognitionRoute.FailureClass.INTERNAL_ERROR;
        }
        return RecognitionFailureMapper.fromLocalRuntime(availability, error);
    }

    private boolean isCurrentLocked(SessionState session) {
        return active == session && !session.terminal;
    }

    private boolean isCurrent(SessionState session) {
        synchronized (lifecycleLock) {
            return isCurrentLocked(session);
        }
    }

    private void markTerminalLocked(SessionState session) {
        session.terminal = true;
        cancelTicket(session.readyTimeout);
        cancelTicket(session.finishTimeout);
        session.readyTimeout = null;
        session.finishTimeout = null;
        if (active == session) active = null;
    }

    private static long nextSequenceLocked(SessionState session) {
        if (session.sequence == Long.MAX_VALUE) {
            throw new IllegalStateException("recognition event sequence exhausted");
        }
        return ++session.sequence;
    }

    private static long durationMs(int pcmBytes) {
        return Math.max(1L, (pcmBytes * 1_000L + 31_999L) / 32_000L);
    }

    private static String boundedText(String value, boolean emptyAllowed) {
        String text = Objects.requireNonNull(value, "text");
        if ((!emptyAllowed && text.isBlank())
                || text.codePointCount(0, text.length()) > RecognitionEvent.MAX_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException("streaming text is outside its bound");
        }
        for (int index = 0; index < text.length(); ) {
            char unit = text.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= text.length()
                        || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    throw new IllegalArgumentException("streaming text must be well-formed UTF-16");
                }
                index += 2;
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("streaming text must be well-formed UTF-16");
            } else {
                index++;
            }
        }
        return text;
    }

    private static void cancelTicket(Ticket ticket) {
        if (ticket == null) return;
        try {
            ticket.cancel();
        } catch (RuntimeException ignored) {
            // Authority no longer depends on advisory timer cleanup.
        }
    }

    private static void cancelQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.cancel();
        } catch (RuntimeException ignored) {
            // Session authority is already revoked.
        }
    }

    @Override
    public String toString() {
        synchronized (lifecycleLock) {
            return "LocalStreamingProvider{active=" + (active != null)
                    + ", closed=" + closed + ", content=<redacted>}";
        }
    }

    interface StreamingSession extends RecognitionProvider.Session {
        boolean acceptPcm(byte[] pcm, int length);

        int acceptedPcmBytes();
    }

    static final class StartRequest implements AutoCloseable {
        private final SessionId sessionId;
        private boolean claimed;

        StartRequest(SessionId sessionId) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        }

        SessionId sessionId() {
            return sessionId;
        }

        synchronized boolean available() {
            return !claimed;
        }

        private synchronized SessionId claim() {
            if (claimed) return null;
            claimed = true;
            return sessionId;
        }

        @Override
        public synchronized void close() {
            claimed = true;
        }

        @Override
        public synchronized String toString() {
            return "LocalStreamingStartRequest{session=<redacted>, available="
                    + available() + "}";
        }
    }

    interface Backend {
        LocalAvailability availability();

        Connection open(BackendListener listener);

        void close();
    }

    interface Connection {
        void acceptPcm(byte[] pcm, int length);

        String finish();

        void cancel();
    }

    interface BackendListener {
        void onReady();

        void onPartial(String text);
    }

    interface Worker {
        void execute(Runnable action);

        void close();
    }

    interface Timer {
        Ticket schedule(Runnable action, long delayMillis);

        void close();
    }

    interface Ticket {
        void cancel();
    }

    static final class ClientBackend implements Backend {
        private final Context context;
        private final LocalRealtimeRecognitionClient client;

        ClientBackend(Context context, LocalRealtimeRecognitionClient client) {
            this.context = Objects.requireNonNull(context, "context").getApplicationContext();
            this.client = Objects.requireNonNull(client, "client");
        }

        @Override
        public LocalAvailability availability() {
            LocalOfflineRecognizer.DeviceSupport support =
                    LocalOfflineRecognizer.deviceSupport(context);
            if (support != LocalOfflineRecognizer.DeviceSupport.SUPPORTED) {
                return switch (support) {
                    case LOW_MEMORY -> LocalAvailability.LOW_MEMORY;
                    case UNSUPPORTED_ABI -> LocalAvailability.UNSUPPORTED_ABI;
                    case SYSTEM_UNAVAILABLE -> LocalAvailability.SYSTEM_UNAVAILABLE;
                    case SUPPORTED -> throw new AssertionError("unreachable support state");
                };
            }
            return switch (OfflineStreamingModelStore.status(context)) {
                case INSTALLED -> LocalAvailability.READY;
                case MISSING -> LocalAvailability.MODEL_MISSING;
                case CORRUPT -> LocalAvailability.MODEL_CORRUPT;
            };
        }

        @Override
        public Connection open(BackendListener listener) {
            BackendListener safeListener = Objects.requireNonNull(listener, "listener");
            LocalRealtimeRecognitionClient.Session session = client.start(
                    new LocalRealtimeRecognitionClient.Listener() {
                        @Override
                        public void onReady() {
                            safeListener.onReady();
                        }

                        @Override
                        public void onPartial(String text) {
                            safeListener.onPartial(text);
                        }
                    });
            return new ClientConnection(session);
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private static final class ClientConnection implements Connection {
        private final LocalRealtimeRecognitionClient.Session session;

        private ClientConnection(LocalRealtimeRecognitionClient.Session session) {
            this.session = Objects.requireNonNull(session, "session");
        }

        @Override
        public void acceptPcm(byte[] pcm, int length) {
            session.accept(pcm, length);
        }

        @Override
        public String finish() {
            return session.finish();
        }

        @Override
        public void cancel() {
            session.cancel();
        }
    }

    private static final class SingleWorker implements Worker {
        private final ExecutorService executor = Executors.newSingleThreadExecutor(action ->
                new Thread(action, "OpenTypeless-LocalStreamingProvider"));

        @Override
        public void execute(Runnable action) {
            executor.execute(Objects.requireNonNull(action, "action"));
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static final class ScheduledTimer implements Timer {
        private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1,
                action -> new Thread(action, "OpenTypeless-LocalStreamingTimer"));

        private ScheduledTimer() {
            executor.setRemoveOnCancelPolicy(true);
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }

        @Override
        public Ticket schedule(Runnable action, long delayMillis) {
            ScheduledFuture<?> future = executor.schedule(
                    Objects.requireNonNull(action, "action"),
                    delayMillis,
                    TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private final class SessionState implements StreamingSession {
        private final SessionId sessionId;
        private EventSink sink;
        private Connection connection;
        private Ticket readyTimeout;
        private Ticket finishTimeout;
        private String lastPartialText = "";
        private long sequence;
        private long lastPartialSequence;
        private int acceptedPcmBytes;
        private int queuedPcmBytes;
        private boolean ready;
        private boolean stopping;
        private boolean finishStarted;
        private boolean terminal;

        private SessionState(SessionId sessionId, EventSink sink) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.sink = Objects.requireNonNull(sink, "sink");
        }

        @Override
        public SessionId sessionId() {
            return sessionId;
        }

        @Override
        public boolean acceptPcm(byte[] pcm, int length) {
            return LocalStreamingProvider.this.acceptPcm(this, pcm, length);
        }

        @Override
        public int acceptedPcmBytes() {
            synchronized (lifecycleLock) {
                return acceptedPcmBytes;
            }
        }

        @Override
        public void stop() {
            LocalStreamingProvider.this.stop(this);
        }

        @Override
        public void cancel() {
            LocalStreamingProvider.this.cancel(this);
        }

        @Override
        public void close() {
            cancel();
        }

        private void releaseReferences() {
            sink = null;
            connection = null;
            lastPartialText = "";
        }

        @Override
        public String toString() {
            synchronized (lifecycleLock) {
                return "LocalStreamingSession{sequence=" + sequence
                        + ", acceptedPcmBytes=" + acceptedPcmBytes
                        + ", ready=" + ready
                        + ", stopping=" + stopping
                        + ", terminal=" + terminal
                        + ", session=<redacted>, content=<redacted>}";
            }
        }
    }
}
