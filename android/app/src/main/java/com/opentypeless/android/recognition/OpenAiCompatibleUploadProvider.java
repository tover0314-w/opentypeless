package com.opentypeless.android.recognition;

import com.opentypeless.android.config.ProviderConfig;
import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.config.SecretRef;
import com.opentypeless.android.net.OpenAiCompatibleClient;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.speech.core.SessionId;

import java.util.Arrays;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Bounded batch-audio adapter for one configured OpenAI-compatible transcription endpoint. */
final class OpenAiCompatibleUploadProvider
        implements RecognitionProvider<OpenAiCompatibleUploadProvider.StartRequest> {
    static final int MAX_PROMPT_CODE_POINTS = 2_000;

    private final Object lifecycleLock = new Object();
    private final ProviderDescriptor descriptor =
            ProviderDescriptor.declaredForBackend(RecognitionBackend.OPENAI_COMPATIBLE);
    private final ProviderConfig.Asr config;
    private final UploadBackend backend;
    private final Worker worker;

    private SessionState active;
    private boolean closed;

    static OpenAiCompatibleUploadProvider create(
            ProviderConfig.Asr config,
            CredentialAccess credentialAccess) {
        return new OpenAiCompatibleUploadProvider(
                config,
                new ClientUploadBackend(
                        new OpenAiCompatibleClient(),
                        Objects.requireNonNull(credentialAccess, "credentialAccess")),
                new SingleWorker());
    }

    OpenAiCompatibleUploadProvider(
            ProviderConfig.Asr config,
            UploadBackend backend,
            Worker worker) {
        this.config = requireRunnableConfig(config);
        this.backend = Objects.requireNonNull(backend, "backend");
        this.worker = Objects.requireNonNull(worker, "worker");
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
            if (closed) {
                return new NotPrepared(RecognitionRoute.FailureClass.UNAVAILABLE);
            }
            return safeRequest.available()
                    ? new Prepared(descriptor)
                    : new NotPrepared(RecognitionRoute.FailureClass.INTERNAL_ERROR);
        }
    }

    @Override
    public Session start(StartRequest request, EventSink sink) {
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
            AudioClaim claim = safeRequest.claim();
            if (claim == null) {
                return detachedFailure(
                        safeRequest.sessionId(),
                        safeSink,
                        RecognitionRoute.FailureClass.INTERNAL_ERROR);
            }
            session = new SessionState(claim, safeSink);
            active = session;
            if (!emitNonTerminalLocked(
                    session,
                    new RecognitionEvent.Preparing(
                            session.sessionId,
                            nextSequenceLocked(session)))) {
                return session;
            }
        }
        try {
            worker.execute(() -> runUpload(session));
        } catch (RuntimeException ignored) {
            finishFailure(session, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        }
        return session;
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            if (active != null) cancelLocked(active);
        }
        try {
            backend.close();
        } catch (RuntimeException ignored) {
            // Provider authority is already revoked; diagnostics never include backend details.
        } finally {
            worker.close();
        }
    }

    private void runUpload(SessionState session) {
        if (!emitReady(session)) return;
        String transcript;
        try {
            transcript = backend.transcribe(
                    config,
                    session.audio,
                    session.language,
                    session.prompt,
                    session.durationMs,
                    () -> isCancelled(session));
        } catch (CancellationException ignored) {
            finishCancelled(session);
            return;
        } catch (Exception error) {
            finishFailure(session, failureClass(error));
            return;
        }
        if (transcript == null || transcript.isBlank()) {
            finishFailure(session, RecognitionRoute.FailureClass.NO_MATCH);
            return;
        }
        if (!emitEndpoint(session)) return;
        try {
            finishFinal(
                    session,
                    new RecognitionEvent.Final(
                            session.sessionId,
                            nextSequence(session),
                            transcript,
                            new RecognitionMetadata(null, null, session.durationMs)));
        } catch (IllegalArgumentException ignored) {
            finishFailure(session, RecognitionRoute.FailureClass.PROTOCOL_ERROR);
        }
    }

    private boolean emitReady(SessionState session) {
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return false;
            return emitNonTerminalLocked(
                    session,
                    new RecognitionEvent.Ready(
                            session.sessionId,
                            nextSequenceLocked(session)));
        }
    }

    private boolean emitEndpoint(SessionState session) {
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return false;
            return emitNonTerminalLocked(
                    session,
                    new RecognitionEvent.Endpoint(
                            session.sessionId,
                            nextSequenceLocked(session)));
        }
    }

    private boolean emitNonTerminalLocked(
            SessionState session,
            RecognitionEvent event) {
        if (!isCurrentLocked(session) || session.sink == null) return false;
        try {
            session.sink.onEvent(event);
            return isCurrentLocked(session);
        } catch (RuntimeException ignored) {
            markTerminalLocked(session);
            try {
                backend.cancel();
            } catch (RuntimeException ignoredCancel) {
                // Sink failure already revoked this session.
            }
            session.releaseReferences();
            return false;
        }
    }

    private void finishFinal(SessionState session, RecognitionEvent.Final event) {
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            markTerminalLocked(session);
            emitTerminalLocked(session, event);
        }
    }

    private void finishFailure(
            SessionState session,
            RecognitionRoute.FailureClass failureClass) {
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            RecognitionEvent.Failure event = new RecognitionEvent.Failure(
                    session.sessionId,
                    nextSequenceLocked(session),
                    Objects.requireNonNull(failureClass, "failureClass"));
            markTerminalLocked(session);
            emitTerminalLocked(session, event);
        }
    }

    private void finishCancelled(SessionState session) {
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            cancelLocked(session);
        }
    }

    private Session detachedFailure(
            SessionId sessionId,
            EventSink sink,
            RecognitionRoute.FailureClass failureClass) {
        SessionState detached = new SessionState(sessionId, sink);
        detached.terminal = true;
        RecognitionEvent.Failure event = new RecognitionEvent.Failure(
                sessionId,
                nextSequenceLocked(detached),
                failureClass);
        emitTerminalLocked(detached, event);
        return detached;
    }

    private void cancel(SessionState session) {
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            cancelLocked(session);
        }
    }

    private void cancelLocked(SessionState session) {
        RecognitionEvent.Cancelled event = new RecognitionEvent.Cancelled(
                session.sessionId,
                nextSequenceLocked(session));
        markTerminalLocked(session);
        try {
            backend.cancel();
        } catch (RuntimeException ignored) {
            // The terminal gate remains authoritative even if disconnect throws.
        }
        emitTerminalLocked(session, event);
    }

    private void emitTerminalLocked(SessionState session, RecognitionEvent event) {
        try {
            if (session.sink != null) session.sink.onEvent(event);
        } catch (RuntimeException ignored) {
            // Terminal is already committed; provider/user content is never logged here.
        } finally {
            session.releaseReferences();
        }
    }

    private boolean isCancelled(SessionState session) {
        synchronized (lifecycleLock) {
            return Thread.currentThread().isInterrupted() || !isCurrentLocked(session);
        }
    }

    private boolean isCurrentLocked(SessionState session) {
        return active == session && !session.terminal;
    }

    private void markTerminalLocked(SessionState session) {
        session.terminal = true;
        if (active == session) active = null;
    }

    private long nextSequence(SessionState session) {
        synchronized (lifecycleLock) {
            return nextSequenceLocked(session);
        }
    }

    private static long nextSequenceLocked(SessionState session) {
        if (session.sequence == Long.MAX_VALUE) {
            throw new IllegalStateException("recognition event sequence exhausted");
        }
        return ++session.sequence;
    }

    private static RecognitionRoute.FailureClass failureClass(Exception error) {
        return RecognitionFailureMapper.fromUpload(error);
    }

    private static ProviderConfig.Asr requireRunnableConfig(ProviderConfig.Asr value) {
        ProviderConfig.Asr config = Objects.requireNonNull(value, "config");
        if (!config.enabled() || config.endpoint().isEmpty() || config.modelId().isEmpty()) {
            throw new IllegalArgumentException("upload provider configuration is incomplete");
        }
        return config;
    }

    @Override
    public String toString() {
        synchronized (lifecycleLock) {
            return "OpenAiCompatibleUploadProvider{configured=true, active="
                    + (active != null) + ", closed=" + closed + ", content=<redacted>}";
        }
    }

    /** One-use, bounded request. Audio is copied at construction and transferred at start. */
    static final class StartRequest implements AutoCloseable {
        private final SessionId sessionId;
        private byte[] wav;
        private final String language;
        private final String prompt;
        private final long durationMs;
        private boolean claimed;

        StartRequest(
                SessionId sessionId,
                byte[] wav,
                String language,
                String prompt,
                long durationMs) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            byte[] source = Objects.requireNonNull(wav, "wav");
            if (source.length == 0 || source.length > OpenAiCompatibleClient.MAX_AUDIO_BYTES) {
                throw new IllegalArgumentException("audio payload is outside its byte bound");
            }
            this.wav = Arrays.copyOf(source, source.length);
            this.language = normalizedLanguage(language);
            this.prompt = boundedText(prompt, "prompt", MAX_PROMPT_CODE_POINTS, true);
            if (durationMs <= 0L || durationMs > ProviderCapabilities.APP_CAPTURE_LIMIT_MS) {
                throw new IllegalArgumentException("audio duration is outside the app capture bound");
            }
            this.durationMs = durationMs;
        }

        SessionId sessionId() {
            return sessionId;
        }

        synchronized boolean available() {
            return !claimed && wav != null;
        }

        synchronized int audioByteCount() {
            return wav == null ? 0 : wav.length;
        }

        private synchronized AudioClaim claim() {
            if (!available()) return null;
            claimed = true;
            byte[] transferred = wav;
            wav = null;
            return new AudioClaim(sessionId, transferred, language, prompt, durationMs);
        }

        @Override
        public synchronized void close() {
            claimed = true;
            if (wav != null) Arrays.fill(wav, (byte) 0);
            wav = null;
        }

        @Override
        public synchronized String toString() {
            return "OpenAiCompatibleStartRequest{session=<redacted>, audio=<redacted>, "
                    + "language=<redacted>, prompt=<redacted>, durationMs=" + durationMs
                    + ", available=" + available() + "}";
        }
    }

    interface UploadBackend {
        String transcribe(
                ProviderConfig.Asr config,
                byte[] wav,
                String language,
                String prompt,
                long durationMs,
                BooleanSupplier cancelled) throws Exception;

        void cancel();

        void close();
    }

    interface Worker {
        void execute(Runnable action);

        void close();
    }

    @FunctionalInterface
    interface CredentialAccess {
        String use(SecretRef reference, CredentialOperation operation) throws Exception;
    }

    @FunctionalInterface
    interface CredentialOperation {
        String apply(char[] credential) throws Exception;
    }

    static final class CredentialUnavailableException extends Exception {
        CredentialUnavailableException() {
            super("Provider credential is unavailable");
        }

        @Override
        public String toString() {
            return "CredentialUnavailableException{content=<redacted>}";
        }
    }

    static final class ClientUploadBackend implements UploadBackend {
        private final OpenAiCompatibleClient client;
        private final CredentialAccess credentialAccess;

        ClientUploadBackend(
                OpenAiCompatibleClient client,
                CredentialAccess credentialAccess) {
            this.client = Objects.requireNonNull(client, "client");
            this.credentialAccess = Objects.requireNonNull(
                    credentialAccess,
                    "credentialAccess");
        }

        @Override
        public String transcribe(
                ProviderConfig.Asr config,
                byte[] wav,
                String language,
                String prompt,
                long durationMs,
                BooleanSupplier cancelled) throws Exception {
            String endpoint = config.endpoint().orElseThrow().value();
            String model = config.modelId().orElseThrow();
            Optional<SecretRef> reference = config.secretRef();
            if (reference.isEmpty()) {
                return transcribeWithCredential(
                        wav, endpoint, new char[0], model, language, prompt, cancelled);
            }
            return credentialAccess.use(
                    reference.orElseThrow(),
                    credential -> transcribeWithCredential(
                            wav, endpoint, credential, model, language, prompt, cancelled));
        }

        private String transcribeWithCredential(
                byte[] wav,
                String endpoint,
                char[] credential,
                String model,
                String language,
                String prompt,
                BooleanSupplier cancelled) throws Exception {
            return client.transcribe(
                    wav,
                    endpoint,
                    credential,
                    model,
                    language,
                    prompt,
                    cancelled);
        }

        @Override
        public void cancel() {
            client.cancelActiveRequest();
        }

        @Override
        public void close() {
            client.cancelActiveRequest();
        }
    }

    private static final class SingleWorker implements Worker {
        private final ExecutorService executor = Executors.newSingleThreadExecutor(action ->
                new Thread(action, "OpenTypeless-UploadProvider"));

        @Override
        public void execute(Runnable action) {
            executor.execute(Objects.requireNonNull(action, "action"));
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static final class AudioClaim {
        private final SessionId sessionId;
        private final byte[] wav;
        private final String language;
        private final String prompt;
        private final long durationMs;

        private AudioClaim(
                SessionId sessionId,
                byte[] wav,
                String language,
                String prompt,
                long durationMs) {
            this.sessionId = sessionId;
            this.wav = wav;
            this.language = language;
            this.prompt = prompt;
            this.durationMs = durationMs;
        }
    }

    private final class SessionState implements Session {
        private final SessionId sessionId;
        private byte[] audio;
        private String language;
        private String prompt;
        private final long durationMs;
        private EventSink sink;
        private long sequence;
        private boolean terminal;

        private SessionState(AudioClaim claim, EventSink sink) {
            sessionId = claim.sessionId;
            audio = claim.wav;
            language = claim.language;
            prompt = claim.prompt;
            durationMs = claim.durationMs;
            this.sink = sink;
        }

        private SessionState(SessionId sessionId, EventSink sink) {
            this.sessionId = sessionId;
            durationMs = 0L;
            this.sink = sink;
        }

        @Override
        public SessionId sessionId() {
            return sessionId;
        }

        @Override
        public void stop() {
            OpenAiCompatibleUploadProvider.this.cancel(this);
        }

        @Override
        public void cancel() {
            OpenAiCompatibleUploadProvider.this.cancel(this);
        }

        @Override
        public void close() {
            cancel();
        }

        private void releaseReferences() {
            if (audio != null) Arrays.fill(audio, (byte) 0);
            audio = null;
            language = null;
            prompt = null;
            sink = null;
        }

        @Override
        public String toString() {
            synchronized (lifecycleLock) {
                return "OpenAiCompatibleUploadSession{session=<redacted>, sequence="
                        + sequence + ", terminal=" + terminal + ", content=<redacted>}";
            }
        }
    }

    private static String normalizedLanguage(String value) {
        String language = boundedText(
                value,
                "language",
                RecognitionMetadata.MAX_LANGUAGE_TAG_CODE_POINTS,
                true);
        if (language.isEmpty()) return language;
        try {
            return new Locale.Builder().setLanguageTag(language).build().toLanguageTag();
        } catch (IllformedLocaleException error) {
            throw new IllegalArgumentException("language tag is invalid");
        }
    }

    private static String boundedText(
            String value,
            String label,
            int maximumCodePoints,
            boolean emptyAllowed) {
        String safe = Objects.requireNonNull(value, label);
        if ((!emptyAllowed && safe.isEmpty())
                || !safe.equals(safe.strip())
                || safe.length() > maximumCodePoints * 2) {
            throw new IllegalArgumentException(label + " is outside its bound");
        }
        for (int index = 0; index < safe.length(); ) {
            char unit = safe.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= safe.length()
                        || !Character.isLowSurrogate(safe.charAt(index + 1))) {
                    throw new IllegalArgumentException(label + " must be well-formed UTF-16");
                }
                index += 2;
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException(label + " must be well-formed UTF-16");
            } else {
                int codePoint = safe.codePointAt(index);
                if (Character.isISOControl(codePoint)) {
                    throw new IllegalArgumentException(label + " contains a control character");
                }
                index += Character.charCount(codePoint);
            }
        }
        if (safe.codePointCount(0, safe.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(label + " is outside its bound");
        }
        return safe;
    }
}
