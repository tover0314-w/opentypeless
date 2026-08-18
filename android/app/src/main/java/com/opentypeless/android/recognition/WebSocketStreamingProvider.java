package com.opentypeless.android.recognition;

import com.opentypeless.android.config.ProviderConfig;
import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.config.SecretRef;
import com.opentypeless.android.net.streaming.StreamingRecognitionWebSocketClient;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.speech.core.SessionId;

import java.util.Arrays;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** STR-001 WebSocket adapter with one active Session and one safe pre-audio reconnect. */
final class WebSocketStreamingProvider
        implements RecognitionProvider<WebSocketStreamingProvider.StartRequest> {
    static final int MAX_PCM_FRAME_BYTES = StreamingRecognitionWebSocketClient.MAX_PCM_FRAME_BYTES;
    static final int MAX_TOTAL_PCM_BYTES = 17_280_000;
    static final int MAX_RECONNECTS = 1;
    static final long READY_TIMEOUT_MS = 10_000L;
    static final long FINISH_TIMEOUT_MS = 15_000L;

    private final Object lifecycleLock = new Object();
    private final ProviderConfig.Asr config;
    private final ProviderDescriptor descriptor;
    private final Backend backend;
    private final Timer timer;

    private SessionState active;
    private boolean closed;

    static WebSocketStreamingProvider create(
            ProviderConfig.Asr config,
            CredentialAccess credentialAccess) {
        ProviderConfig.Asr safeConfig = requireRunnableConfig(config);
        StreamingRecognitionWebSocketClient client =
                new StreamingRecognitionWebSocketClient();
        return new WebSocketStreamingProvider(
                safeConfig,
                new ClientBackend(
                        safeConfig,
                        client,
                        Objects.requireNonNull(credentialAccess, "credentialAccess")),
                new ScheduledTimer());
    }

    static WebSocketStreamingProvider create(
            ProviderConfig.Asr config,
            ProviderDescriptor descriptor,
            Backend backend) {
        return new WebSocketStreamingProvider(
                config,
                descriptor,
                backend,
                new ScheduledTimer());
    }

    WebSocketStreamingProvider(
            ProviderConfig.Asr config,
            Backend backend,
            Timer timer) {
        this(
                config,
                new ProviderDescriptor(
                        config.id(),
                        config.displayName(),
                        ProviderCapabilities.declaredForBackend(
                                RecognitionBackend.DASHSCOPE_STREAMING)),
                backend,
                timer);
    }

    WebSocketStreamingProvider(
            ProviderConfig.Asr config,
            ProviderDescriptor descriptor,
            Backend backend,
            Timer timer) {
        this.config = requireRunnableConfig(config);
        this.descriptor = requireMatchingDescriptor(this.config, descriptor);
        this.backend = Objects.requireNonNull(backend, "backend");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ProviderRegistry.ProbeObservation probe() {
        synchronized (lifecycleLock) {
            return closed
                    ? new ProviderRegistry.ObservedUnavailable(
                            RecognitionRoute.FailureClass.UNAVAILABLE)
                    : new ProviderRegistry.ObservedAvailable(descriptor.capabilities());
        }
    }

    @Override
    public PreparationResult prepare(StartRequest request) {
        StartRequest safeRequest = Objects.requireNonNull(request, "request");
        synchronized (lifecycleLock) {
            if (closed) return new NotPrepared(RecognitionRoute.FailureClass.UNAVAILABLE);
            return safeRequest.available()
                    ? new Prepared(descriptor)
                    : new NotPrepared(RecognitionRoute.FailureClass.INTERNAL_ERROR);
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
            RequestClaim claim = safeRequest.claim();
            if (claim == null) {
                return detachedFailure(
                        safeRequest.sessionId(),
                        safeSink,
                        RecognitionRoute.FailureClass.INTERNAL_ERROR);
            }
            session = new SessionState(claim.sessionId, claim.language, safeSink);
            active = session;
        }
        openAttempt(session);
        return session;
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
            // Provider authority is already revoked; transport details remain private.
        } finally {
            timer.close();
        }
    }

    private void openAttempt(SessionState session) {
        long attempt;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            if (session.attempt == Long.MAX_VALUE) {
                finishFailureLocked(session, RecognitionRoute.FailureClass.INTERNAL_ERROR);
                return;
            }
            attempt = ++session.attempt;
            session.ready = false;
            session.opened = false;
        }

        Connection connection;
        try {
            connection = Objects.requireNonNull(
                    backend.open(
                            session.sessionId,
                            session.language,
                            new AttemptListener() {
                                @Override
                                public void onOpen() {
                                    onAttemptOpened(session, attempt);
                                }

                                @Override
                                public void onEvent(RecognitionEvent event) {
                                    onAttemptEvent(session, attempt, event);
                                }

                                @Override
                                public void onFailure(ClientFailure failure) {
                                    onAttemptFailure(session, attempt, failure);
                                }
                            }),
                    "connection");
        } catch (BackendException error) {
            onAttemptFailure(session, attempt, error.failure());
            return;
        } catch (RuntimeException ignored) {
            onAttemptFailure(session, attempt, ClientFailure.INTERNAL_ERROR);
            return;
        }

        boolean retained;
        boolean finishNow = false;
        synchronized (lifecycleLock) {
            retained = isCurrentAttemptLocked(session, attempt) && session.connection == null;
            if (retained) {
                session.connection = connection;
                if (session.ready && session.stopping && !session.finishSent) {
                    session.finishSent = true;
                    finishNow = true;
                }
            }
        }
        if (!retained) {
            cancelQuietly(connection);
            return;
        }
        scheduleReadyTimeout(session, attempt);
        if (finishNow) sendFinish(session, attempt, connection);
    }

    private void onAttemptOpened(SessionState session, long attempt) {
        synchronized (lifecycleLock) {
            if (!isCurrentAttemptLocked(session, attempt)) return;
            session.opened = true;
        }
    }

    private void onAttemptEvent(
            SessionState session,
            long attempt,
            RecognitionEvent event) {
        RecognitionEvent safeEvent = Objects.requireNonNull(event, "event");
        Connection closeConnection = null;
        Connection finishConnection = null;
        synchronized (lifecycleLock) {
            if (!isCurrentAttemptLocked(session, attempt)) return;
            session.serverEventSeen = true;
            if (!session.sessionId.equals(safeEvent.sessionId())
                    || safeEvent.sequence() <= session.lastSequence
                    || (!safeEvent.terminal() && safeEvent.sequence() == Long.MAX_VALUE)) {
                closeConnection = finishFailureLocked(
                        session, RecognitionRoute.FailureClass.PROTOCOL_ERROR);
            } else {
                session.lastSequence = safeEvent.sequence();
                if (safeEvent instanceof RecognitionEvent.Ready) {
                    session.ready = true;
                    cancelTicket(session.readyTimeout);
                    session.readyTimeout = null;
                }
                if (safeEvent.terminal()) {
                    closeConnection = session.connection;
                    markTerminalLocked(session);
                    emitTerminalLocked(session, safeEvent);
                } else if (!emitNonTerminalLocked(session, safeEvent)) {
                    closeConnection = session.connection;
                } else if (safeEvent instanceof RecognitionEvent.Ready
                        && session.stopping
                        && !session.finishSent
                        && session.connection != null) {
                    session.finishSent = true;
                    finishConnection = session.connection;
                }
            }
        }
        if (finishConnection != null) sendFinish(session, attempt, finishConnection);
        closeQuietly(closeConnection);
    }

    private void onAttemptFailure(
            SessionState session,
            long attempt,
            ClientFailure failure) {
        Connection failedConnection;
        boolean retry;
        synchronized (lifecycleLock) {
            if (!isCurrentAttemptLocked(session, attempt)) return;
            failedConnection = session.connection;
            session.connection = null;
            cancelTicket(session.readyTimeout);
            session.readyTimeout = null;
            retry = !session.serverEventSeen
                    && session.acceptedPcmBytes == 0
                    && !session.stopping
                    && session.reconnects < MAX_RECONNECTS
                    && retryable(failure);
            if (retry) {
                session.reconnects++;
                session.opened = false;
                session.ready = false;
            } else {
                finishFailureLocked(session, failureClass(failure));
            }
        }
        cancelQuietly(failedConnection);
        if (retry) openAttempt(session);
    }

    private boolean acceptPcm(SessionState session, byte[] pcm, int length) {
        Objects.requireNonNull(pcm, "pcm");
        if (length <= 0
                || length > MAX_PCM_FRAME_BYTES
                || length > pcm.length
                || (length & 1) != 0) {
            throw new IllegalArgumentException("PCM frame is outside its bound");
        }
        Connection connection;
        long attempt;
        long queuedBytes;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)
                    || !session.ready
                    || session.stopping
                    || session.connection == null) {
                return false;
            }
            connection = session.connection;
            attempt = session.attempt;
        }
        try {
            queuedBytes = connection.queuedBytes();
        } catch (RuntimeException ignored) {
            onAttemptFailure(session, attempt, ClientFailure.NETWORK_UNAVAILABLE);
            return false;
        }
        if (queuedBytes < 0L
                || queuedBytes
                        > StreamingRecognitionWebSocketClient.MAX_OUTGOING_QUEUE_BYTES - length) {
            onAttemptFailure(session, attempt, ClientFailure.NETWORK_UNAVAILABLE);
            return false;
        }
        synchronized (lifecycleLock) {
            if (!isCurrentAttemptLocked(session, attempt)
                    || session.connection != connection
                    || session.stopping) {
                return false;
            }
            if (session.acceptedPcmBytes > MAX_TOTAL_PCM_BYTES - length) {
                failCurrent(session, RecognitionRoute.FailureClass.AUDIO_ERROR);
                return false;
            }
            session.acceptedPcmBytes += length;
        }
        byte[] copied = Arrays.copyOf(pcm, length);
        boolean sent;
        try {
            sent = connection.sendPcm(copied, copied.length);
        } catch (RuntimeException ignored) {
            sent = false;
        } finally {
            Arrays.fill(copied, (byte) 0);
        }
        if (!sent) onAttemptFailure(session, attempt, ClientFailure.NETWORK_UNAVAILABLE);
        return sent;
    }

    private void stop(SessionState session) {
        Connection connection = null;
        long attempt = 0L;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session) || session.stopping) return;
            session.stopping = true;
            attempt = session.attempt;
            if (session.ready && session.connection != null) {
                session.finishSent = true;
                connection = session.connection;
            }
        }
        scheduleFinishTimeout(session, attempt);
        if (connection != null) sendFinish(session, attempt, connection);
    }

    private void sendFinish(SessionState session, long attempt, Connection connection) {
        boolean sent;
        try {
            sent = connection.finish();
        } catch (RuntimeException ignored) {
            sent = false;
        }
        if (!sent) onAttemptFailure(session, attempt, ClientFailure.NETWORK_UNAVAILABLE);
    }

    private void cancel(SessionState session) {
        Connection connection;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            connection = session.connection;
            RecognitionEvent.Cancelled event = new RecognitionEvent.Cancelled(
                    session.sessionId, nextLocalSequenceLocked(session));
            markTerminalLocked(session);
            emitTerminalLocked(session, event);
        }
        cancelQuietly(connection);
    }

    private void failCurrent(
            SessionState session,
            RecognitionRoute.FailureClass failureClass) {
        Connection connection;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            connection = finishFailureLocked(session, failureClass);
        }
        cancelQuietly(connection);
    }

    private Connection finishFailureLocked(
            SessionState session,
            RecognitionRoute.FailureClass failureClass) {
        if (!isCurrentLocked(session)) return null;
        Connection connection = session.connection;
        RecognitionEvent.Failure event = new RecognitionEvent.Failure(
                session.sessionId,
                nextLocalSequenceLocked(session),
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
            Connection connection = session.connection;
            markTerminalLocked(session);
            session.releaseReferences();
            cancelQuietly(connection);
            return false;
        }
    }

    private void emitTerminalLocked(SessionState session, RecognitionEvent event) {
        try {
            if (session.sink != null) session.sink.onEvent(event);
        } catch (RuntimeException ignored) {
            // Terminal is already committed; event and sink details remain private.
        } finally {
            session.releaseReferences();
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

    private void scheduleReadyTimeout(SessionState session, long attempt) {
        Ticket ticket;
        try {
            ticket = timer.schedule(
                    () -> onAttemptFailure(session, attempt, ClientFailure.NETWORK_TIMEOUT),
                    READY_TIMEOUT_MS);
        } catch (RuntimeException ignored) {
            onAttemptFailure(session, attempt, ClientFailure.INTERNAL_ERROR);
            return;
        }
        synchronized (lifecycleLock) {
            if (isCurrentAttemptLocked(session, attempt) && !session.ready) {
                cancelTicket(session.readyTimeout);
                session.readyTimeout = ticket;
                return;
            }
        }
        ticket.cancel();
    }

    private void scheduleFinishTimeout(SessionState session, long attempt) {
        Ticket ticket;
        try {
            ticket = timer.schedule(
                    () -> onAttemptFailure(session, attempt, ClientFailure.NETWORK_TIMEOUT),
                    FINISH_TIMEOUT_MS);
        } catch (RuntimeException ignored) {
            onAttemptFailure(session, attempt, ClientFailure.INTERNAL_ERROR);
            return;
        }
        synchronized (lifecycleLock) {
            if (isCurrentAttemptLocked(session, attempt) && session.stopping) {
                cancelTicket(session.finishTimeout);
                session.finishTimeout = ticket;
                return;
            }
        }
        ticket.cancel();
    }

    private StreamingSession detachedFailure(
            SessionId sessionId,
            EventSink sink,
            RecognitionRoute.FailureClass failureClass) {
        SessionState detached = new SessionState(sessionId, "", sink);
        detached.terminal = true;
        emitTerminalLocked(
                detached,
                new RecognitionEvent.Failure(sessionId, 1L, failureClass));
        return detached;
    }

    private boolean isCurrentLocked(SessionState session) {
        return active == session && !session.terminal;
    }

    private boolean isCurrentAttemptLocked(SessionState session, long attempt) {
        return isCurrentLocked(session) && session.attempt == attempt;
    }

    private static long nextLocalSequenceLocked(SessionState session) {
        if (session.lastSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("recognition event sequence exhausted");
        }
        return ++session.lastSequence;
    }

    private static void cancelTicket(Ticket ticket) {
        if (ticket == null) return;
        try {
            ticket.cancel();
        } catch (RuntimeException ignored) {
            // Timer is advisory after authority has been revoked.
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

    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (RuntimeException ignored) {
            // Terminal event is already committed.
        }
    }

    private static boolean retryable(ClientFailure failure) {
        return failure == ClientFailure.NETWORK_TIMEOUT
                || failure == ClientFailure.NETWORK_UNAVAILABLE
                || failure == ClientFailure.SERVER_ERROR;
    }

    private static RecognitionRoute.FailureClass failureClass(ClientFailure failure) {
        return switch (Objects.requireNonNull(failure, "failure")) {
            case MODEL_MISSING -> RecognitionRoute.FailureClass.MODEL_MISSING;
            case AUTHENTICATION -> RecognitionRoute.FailureClass.AUTHENTICATION;
            case RATE_LIMITED -> RecognitionRoute.FailureClass.RATE_LIMITED;
            case SERVER_ERROR -> RecognitionRoute.FailureClass.SERVER_ERROR;
            case NETWORK_TIMEOUT -> RecognitionRoute.FailureClass.NETWORK_TIMEOUT;
            case NETWORK_UNAVAILABLE -> RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE;
            case PROTOCOL_ERROR -> RecognitionRoute.FailureClass.PROTOCOL_ERROR;
            case INTERNAL_ERROR -> RecognitionRoute.FailureClass.INTERNAL_ERROR;
        };
    }

    private static ProviderDescriptor requireMatchingDescriptor(
            ProviderConfig.Asr config,
            ProviderDescriptor value) {
        ProviderDescriptor descriptor = Objects.requireNonNull(value, "descriptor");
        if (!config.id().equals(descriptor.id())
                || !config.displayName().equals(descriptor.displayName())
                || !descriptor.capabilities().supportsStreaming()
                || descriptor.capabilities().supportsOnDevice()
                || !descriptor.capabilities().supportedAudioFormats().contains(
                        ProviderCapabilities.AudioFormat.PCM_16_MONO_16000_HZ)) {
            throw new IllegalArgumentException(
                    "streaming descriptor does not match provider configuration");
        }
        return descriptor;
    }

    private static ProviderConfig.Asr requireRunnableConfig(ProviderConfig.Asr value) {
        ProviderConfig.Asr config = Objects.requireNonNull(value, "config");
        if (!config.enabled() || config.endpoint().isEmpty() || config.modelId().isEmpty()) {
            throw new IllegalArgumentException("streaming provider configuration is incomplete");
        }
        return config;
    }

    private static String normalizedLanguage(String value) {
        String text = value == null ? "" : value;
        if (!text.equals(text.strip()) || text.codePointCount(0, text.length()) > 35) {
            throw new IllegalArgumentException("language is outside its bound");
        }
        if (text.isEmpty()) return "";
        for (int index = 0; index < text.length(); ) {
            char unit = text.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= text.length()
                        || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    throw new IllegalArgumentException("language must be well-formed UTF-16");
                }
                index += 2;
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("language must be well-formed UTF-16");
            } else {
                index++;
            }
        }
        try {
            String tag = new Locale.Builder().setLanguageTag(text).build().toLanguageTag();
            if (tag.isEmpty() || tag.equals("und")) {
                throw new IllegalArgumentException("language tag is invalid");
            }
            return tag;
        } catch (IllformedLocaleException error) {
            throw new IllegalArgumentException("language tag is invalid");
        }
    }

    @Override
    public String toString() {
        synchronized (lifecycleLock) {
            return "WebSocketStreamingProvider{configured=true, active=" + (active != null)
                    + ", closed=" + closed + ", content=<redacted>}";
        }
    }

    interface StreamingSession extends RecognitionProvider.Session {
        boolean acceptPcm(byte[] pcm, int length);

        int acceptedPcmBytes();
    }

    static final class StartRequest implements AutoCloseable {
        private final SessionId sessionId;
        private String language;
        private boolean claimed;

        StartRequest(SessionId sessionId, String language) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.language = normalizedLanguage(language);
        }

        SessionId sessionId() {
            return sessionId;
        }

        synchronized boolean available() {
            return !claimed && language != null;
        }

        private synchronized RequestClaim claim() {
            if (!available()) return null;
            claimed = true;
            String transferredLanguage = language;
            language = null;
            return new RequestClaim(sessionId, transferredLanguage);
        }

        @Override
        public synchronized void close() {
            claimed = true;
            language = null;
        }

        @Override
        public synchronized String toString() {
            return "WebSocketStreamingStartRequest{session=<redacted>, language=<redacted>, "
                    + "available=" + available() + "}";
        }
    }

    interface Backend {
        Connection open(SessionId sessionId, String language, AttemptListener listener)
                throws BackendException;

        void close();
    }

    interface Connection {
        boolean sendPcm(byte[] pcm, int length);

        boolean finish();

        long queuedBytes();

        void cancel();

        void close();
    }

    interface AttemptListener {
        void onOpen();

        void onEvent(RecognitionEvent event);

        void onFailure(ClientFailure failure);
    }

    enum ClientFailure {
        MODEL_MISSING,
        AUTHENTICATION,
        RATE_LIMITED,
        SERVER_ERROR,
        NETWORK_TIMEOUT,
        NETWORK_UNAVAILABLE,
        PROTOCOL_ERROR,
        INTERNAL_ERROR
    }

    interface Timer {
        Ticket schedule(Runnable action, long delayMillis);

        void close();
    }

    interface Ticket {
        void cancel();
    }

    @FunctionalInterface
    interface CredentialAccess {
        Connection use(SecretRef reference, CredentialOperation operation) throws Exception;
    }

    @FunctionalInterface
    interface CredentialOperation {
        Connection apply(char[] credential) throws Exception;
    }

    static final class BackendException extends Exception {
        private final ClientFailure failure;

        BackendException(ClientFailure failure) {
            super("streaming backend unavailable");
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        ClientFailure failure() {
            return failure;
        }

        @Override
        public String toString() {
            return "BackendException{failure=" + failure + ", content=<redacted>}";
        }
    }

    private static final class RequestClaim {
        private final SessionId sessionId;
        private final String language;

        private RequestClaim(SessionId sessionId, String language) {
            this.sessionId = sessionId;
            this.language = language;
        }
    }

    private final class SessionState implements StreamingSession {
        private final SessionId sessionId;
        private String language;
        private EventSink sink;
        private Connection connection;
        private Ticket readyTimeout;
        private Ticket finishTimeout;
        private long attempt;
        private long lastSequence;
        private int reconnects;
        private int acceptedPcmBytes;
        private boolean opened;
        private boolean ready;
        private boolean stopping;
        private boolean finishSent;
        private boolean serverEventSeen;
        private boolean terminal;

        private SessionState(SessionId sessionId, String language, EventSink sink) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.language = Objects.requireNonNull(language, "language");
            this.sink = Objects.requireNonNull(sink, "sink");
        }

        @Override
        public SessionId sessionId() {
            return sessionId;
        }

        @Override
        public boolean acceptPcm(byte[] pcm, int length) {
            return WebSocketStreamingProvider.this.acceptPcm(this, pcm, length);
        }

        @Override
        public int acceptedPcmBytes() {
            synchronized (lifecycleLock) {
                return acceptedPcmBytes;
            }
        }

        @Override
        public void stop() {
            WebSocketStreamingProvider.this.stop(this);
        }

        @Override
        public void cancel() {
            WebSocketStreamingProvider.this.cancel(this);
        }

        @Override
        public void close() {
            WebSocketStreamingProvider.this.cancel(this);
        }

        private void releaseReferences() {
            language = null;
            sink = null;
            connection = null;
            ready = false;
        }

        @Override
        public String toString() {
            synchronized (lifecycleLock) {
                return "WebSocketStreamingSession{attempt=" + attempt
                        + ", reconnects=" + reconnects
                        + ", acceptedPcmBytes=" + acceptedPcmBytes
                        + ", terminal=" + terminal
                        + ", session=<redacted>, content=<redacted>}";
            }
        }
    }

    static final class ClientBackend implements Backend {
        private final ProviderConfig.Asr config;
        private final StreamingRecognitionWebSocketClient client;
        private final CredentialAccess credentialAccess;

        ClientBackend(
                ProviderConfig.Asr config,
                StreamingRecognitionWebSocketClient client,
                CredentialAccess credentialAccess) {
            this.config = requireRunnableConfig(config);
            this.client = Objects.requireNonNull(client, "client");
            this.credentialAccess = Objects.requireNonNull(
                    credentialAccess, "credentialAccess");
        }

        @Override
        public Connection open(
                SessionId sessionId,
                String language,
                AttemptListener listener) throws BackendException {
            StreamingRecognitionWebSocketClient.Config clientConfig =
                    new StreamingRecognitionWebSocketClient.Config(
                            config.endpoint().orElseThrow(),
                            sessionId,
                            config.modelId().orElseThrow(),
                            language);
            try {
                Optional<SecretRef> reference = config.secretRef();
                if (reference.isEmpty()) {
                    return openWithCredential(clientConfig, new char[0], listener);
                }
                return credentialAccess.use(
                        reference.orElseThrow(),
                        credential -> openWithCredential(clientConfig, credential, listener));
            } catch (BackendException error) {
                throw error;
            } catch (IllegalArgumentException error) {
                throw new BackendException(ClientFailure.PROTOCOL_ERROR);
            } catch (Exception error) {
                throw new BackendException(ClientFailure.AUTHENTICATION);
            }
        }

        private Connection openWithCredential(
                StreamingRecognitionWebSocketClient.Config clientConfig,
                char[] credential,
                AttemptListener listener) throws BackendException {
            try {
                StreamingRecognitionWebSocketClient.Session session = client.open(
                        clientConfig,
                        credential,
                        new StreamingRecognitionWebSocketClient.Listener() {
                            @Override
                            public void onOpen() {
                                listener.onOpen();
                            }

                            @Override
                            public void onEvent(RecognitionEvent event) {
                                listener.onEvent(event);
                            }

                            @Override
                            public void onFailure(
                                    StreamingRecognitionWebSocketClient.Failure failure) {
                                listener.onFailure(clientFailure(failure));
                            }
                        });
                return new Connection() {
                    @Override
                    public boolean sendPcm(byte[] pcm, int length) {
                        return session.sendPcm(pcm, 0, length);
                    }

                    @Override
                    public boolean finish() {
                        return session.finish();
                    }

                    @Override
                    public long queuedBytes() {
                        return session.queuedBytes();
                    }

                    @Override
                    public void cancel() {
                        session.cancel();
                    }

                    @Override
                    public void close() {
                        session.close();
                    }
                };
            } catch (IllegalArgumentException error) {
                throw new BackendException(ClientFailure.PROTOCOL_ERROR);
            } catch (RuntimeException error) {
                throw new BackendException(ClientFailure.INTERNAL_ERROR);
            }
        }

        @Override
        public void close() {
            client.close();
        }

        private static ClientFailure clientFailure(
                StreamingRecognitionWebSocketClient.Failure failure) {
            return switch (failure) {
                case AUTHENTICATION -> ClientFailure.AUTHENTICATION;
                case RATE_LIMITED -> ClientFailure.RATE_LIMITED;
                case SERVER_ERROR -> ClientFailure.SERVER_ERROR;
                case NETWORK_TIMEOUT -> ClientFailure.NETWORK_TIMEOUT;
                case NETWORK_UNAVAILABLE -> ClientFailure.NETWORK_UNAVAILABLE;
                case PROTOCOL_ERROR -> ClientFailure.PROTOCOL_ERROR;
                case INTERNAL_ERROR -> ClientFailure.INTERNAL_ERROR;
            };
        }
    }

    private static final class ScheduledTimer implements Timer {
        private final ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(1, action ->
                        new Thread(action, "OpenTypeless-WebSocketProvider-Timer"));

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
}
