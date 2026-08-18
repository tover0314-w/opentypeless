package com.opentypeless.android.recognition;

import android.content.Context;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.offline.LocalOfflineRecognitionClient;
import com.opentypeless.android.offline.LocalOfflineRecognitionService;
import com.opentypeless.android.offline.LocalOfflineRecognizer;
import com.opentypeless.android.offline.OfflineModelStore;
import com.opentypeless.android.recognition.RecognitionFailureMapper.LocalAvailability;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.speech.core.SessionId;

import java.util.Arrays;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Final-only adapter for the private-process, pinned SenseVoice quality model. */
final class SenseVoiceFinalProvider
        implements RecognitionProvider<SenseVoiceFinalProvider.StartRequest> {
    private final Object lifecycleLock = new Object();
    private final ProviderDescriptor descriptor =
            ProviderDescriptor.declaredForBackend(RecognitionBackend.LOCAL_OFFLINE);
    private final Backend backend;
    private final Worker worker;

    private SessionState active;
    private boolean closed;

    static SenseVoiceFinalProvider create(Context context) {
        Context application = Objects.requireNonNull(context, "context").getApplicationContext();
        return new SenseVoiceFinalProvider(
                new ClientBackend(application, new LocalOfflineRecognitionClient(application)),
                new SingleWorker());
    }

    SenseVoiceFinalProvider(Backend backend, Worker worker) {
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
            if (closed) {
                return new NotPrepared(RecognitionRoute.FailureClass.UNAVAILABLE);
            }
            if (!safeRequest.available()) {
                return new NotPrepared(RecognitionRoute.FailureClass.INTERNAL_ERROR);
            }
            RecognitionRoute.FailureClass failure = availabilityFailureLocked();
            return failure == null ? new Prepared(descriptor) : new NotPrepared(failure);
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
            RecognitionRoute.FailureClass availabilityFailure = availabilityFailureLocked();
            if (availabilityFailure != null) {
                return detachedFailure(
                        safeRequest.sessionId(), safeSink, availabilityFailure);
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
                            session.sessionId, nextSequenceLocked(session)))) {
                return session;
            }
        }
        try {
            worker.execute(() -> runRecognition(session));
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
            // Provider authority is already revoked; backend details remain private.
        } finally {
            worker.close();
        }
    }

    private void runRecognition(SessionState session) {
        if (!emitReady(session)) return;
        String transcript;
        try {
            transcript = backend.transcribe(
                    session.audio,
                    session.language,
                    session.useInverseTextNormalization,
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
        finishFinal(session, transcript);
    }

    private boolean emitReady(SessionState session) {
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return false;
            return emitNonTerminalLocked(
                    session,
                    new RecognitionEvent.Ready(
                            session.sessionId, nextSequenceLocked(session)));
        }
    }

    private boolean emitNonTerminalLocked(SessionState session, RecognitionEvent event) {
        if (!isCurrentLocked(session) || session.sink == null) return false;
        try {
            session.sink.onEvent(event);
            return isCurrentLocked(session);
        } catch (RuntimeException ignored) {
            markTerminalLocked(session);
            try {
                backend.cancel();
            } catch (RuntimeException ignoredCancel) {
                // Sink failure already revoked the session.
            }
            session.releaseReferences();
            return false;
        }
    }

    private void finishFinal(SessionState session, String transcript) {
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            RecognitionEvent.Final event;
            try {
                event = new RecognitionEvent.Final(
                        session.sessionId,
                        nextSequenceLocked(session),
                        transcript,
                        new RecognitionMetadata(null, null, session.durationMs));
            } catch (IllegalArgumentException ignored) {
                finishFailureLocked(session, RecognitionRoute.FailureClass.PROTOCOL_ERROR);
                return;
            }
            markTerminalLocked(session);
            emitTerminalLocked(session, event);
        }
    }

    private void finishFailure(
            SessionState session,
            RecognitionRoute.FailureClass failureClass) {
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            finishFailureLocked(session, failureClass);
        }
    }

    private void finishFailureLocked(
            SessionState session,
            RecognitionRoute.FailureClass failureClass) {
        RecognitionEvent.Failure event = new RecognitionEvent.Failure(
                session.sessionId,
                nextSequenceLocked(session),
                Objects.requireNonNull(failureClass, "failureClass"));
        markTerminalLocked(session);
        emitTerminalLocked(session, event);
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
        emitTerminalLocked(
                detached,
                new RecognitionEvent.Failure(
                        sessionId, nextSequenceLocked(detached), failureClass));
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
                session.sessionId, nextSequenceLocked(session));
        markTerminalLocked(session);
        try {
            backend.cancel();
        } catch (RuntimeException ignored) {
            // The terminal gate remains authoritative even if worker teardown throws.
        }
        emitTerminalLocked(session, event);
    }

    private void emitTerminalLocked(SessionState session, RecognitionEvent event) {
        try {
            if (session.sink != null) session.sink.onEvent(event);
        } catch (RuntimeException ignored) {
            // Terminal is already committed and provider content is never logged here.
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

    private static long nextSequenceLocked(SessionState session) {
        if (session.sequence == Long.MAX_VALUE) {
            throw new IllegalStateException("recognition event sequence exhausted");
        }
        return ++session.sequence;
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

    private RecognitionRoute.FailureClass failureClass(Exception error) {
        LocalAvailability availability;
        try {
            availability = Objects.requireNonNull(backend.availability(), "availability");
        } catch (RuntimeException ignored) {
            return RecognitionRoute.FailureClass.INTERNAL_ERROR;
        }
        return RecognitionFailureMapper.fromLocalRuntime(availability, error);
    }

    @Override
    public String toString() {
        synchronized (lifecycleLock) {
            return "SenseVoiceFinalProvider{active=" + (active != null)
                    + ", closed=" + closed + ", content=<redacted>}";
        }
    }

    /** One-use request whose copied WAV is transferred to exactly one provider session. */
    static final class StartRequest implements AutoCloseable {
        private final SessionId sessionId;
        private byte[] wav;
        private final String language;
        private final boolean useInverseTextNormalization;
        private final long durationMs;
        private boolean claimed;

        StartRequest(
                SessionId sessionId,
                byte[] wav,
                String language,
                boolean useInverseTextNormalization,
                long durationMs) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            byte[] source = Objects.requireNonNull(wav, "wav");
            if (source.length < 44 || source.length > LocalOfflineRecognitionService.MAX_WAV_BYTES) {
                throw new IllegalArgumentException("offline audio is outside its byte bound");
            }
            this.wav = Arrays.copyOf(source, source.length);
            this.language = normalizedLanguage(language);
            this.useInverseTextNormalization = useInverseTextNormalization;
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
            return new AudioClaim(
                    sessionId,
                    transferred,
                    language,
                    useInverseTextNormalization,
                    durationMs);
        }

        @Override
        public synchronized void close() {
            claimed = true;
            if (wav != null) Arrays.fill(wav, (byte) 0);
            wav = null;
        }

        @Override
        public synchronized String toString() {
            return "SenseVoiceStartRequest{session=<redacted>, audio=<redacted>, "
                    + "language=<redacted>, durationMs=" + durationMs
                    + ", available=" + available() + "}";
        }
    }

    interface Backend {
        LocalAvailability availability();

        String transcribe(
                byte[] wav,
                String language,
                boolean useInverseTextNormalization,
                BooleanSupplier cancelled);

        void cancel();

        void close();
    }

    interface Worker {
        void execute(Runnable action);

        void close();
    }

    static final class ClientBackend implements Backend {
        private final Context context;
        private final LocalOfflineRecognitionClient client;

        ClientBackend(Context context, LocalOfflineRecognitionClient client) {
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
            return switch (OfflineModelStore.status(context)) {
                case INSTALLED -> LocalAvailability.READY;
                case MISSING -> LocalAvailability.MODEL_MISSING;
                case CORRUPT -> LocalAvailability.MODEL_CORRUPT;
            };
        }

        @Override
        public String transcribe(
                byte[] wav,
                String language,
                boolean useInverseTextNormalization,
                BooleanSupplier cancelled) {
            throwIfCancelled(cancelled);
            LocalOfflineRecognitionClient.Result result = client.recognize(
                    wav, language, useInverseTextNormalization);
            throwIfCancelled(cancelled);
            return useInverseTextNormalization ? result.punctuatedText() : result.exactText();
        }

        @Override
        public void cancel() {
            client.cancelActive();
        }

        @Override
        public void close() {
            client.close();
        }

        private static void throwIfCancelled(BooleanSupplier cancelled) {
            if (Thread.currentThread().isInterrupted()
                    || (cancelled != null && cancelled.getAsBoolean())) {
                throw new CancellationException("offline recognition cancelled");
            }
        }
    }

    private static final class SingleWorker implements Worker {
        private final ExecutorService executor = Executors.newSingleThreadExecutor(action ->
                new Thread(action, "OpenTypeless-SenseVoiceFinal"));

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
        private final boolean useInverseTextNormalization;
        private final long durationMs;

        private AudioClaim(
                SessionId sessionId,
                byte[] wav,
                String language,
                boolean useInverseTextNormalization,
                long durationMs) {
            this.sessionId = sessionId;
            this.wav = wav;
            this.language = language;
            this.useInverseTextNormalization = useInverseTextNormalization;
            this.durationMs = durationMs;
        }
    }

    private final class SessionState implements Session {
        private final SessionId sessionId;
        private byte[] audio;
        private String language;
        private final boolean useInverseTextNormalization;
        private final long durationMs;
        private EventSink sink;
        private long sequence;
        private boolean terminal;

        private SessionState(AudioClaim claim, EventSink sink) {
            sessionId = claim.sessionId;
            audio = claim.wav;
            language = claim.language;
            useInverseTextNormalization = claim.useInverseTextNormalization;
            durationMs = claim.durationMs;
            this.sink = sink;
        }

        private SessionState(SessionId sessionId, EventSink sink) {
            this.sessionId = sessionId;
            useInverseTextNormalization = false;
            durationMs = 0L;
            this.sink = sink;
        }

        @Override
        public SessionId sessionId() {
            return sessionId;
        }

        @Override
        public void stop() {
            SenseVoiceFinalProvider.this.cancel(this);
        }

        @Override
        public void cancel() {
            SenseVoiceFinalProvider.this.cancel(this);
        }

        @Override
        public void close() {
            cancel();
        }

        private void releaseReferences() {
            if (audio != null) Arrays.fill(audio, (byte) 0);
            audio = null;
            language = null;
            sink = null;
        }

        @Override
        public String toString() {
            synchronized (lifecycleLock) {
                return "SenseVoiceFinalSession{session=<redacted>, sequence=" + sequence
                        + ", terminal=" + terminal + ", content=<redacted>}";
            }
        }
    }

    private static String normalizedLanguage(String value) {
        String language = boundedText(
                value,
                "language",
                RecognitionMetadata.MAX_LANGUAGE_TAG_CODE_POINTS);
        if (language.isEmpty()) return language;
        try {
            return new Locale.Builder().setLanguageTag(language).build().toLanguageTag();
        } catch (IllformedLocaleException error) {
            throw new IllegalArgumentException("language tag is invalid");
        }
    }

    private static String boundedText(String value, String label, int maximumCodePoints) {
        String safe = Objects.requireNonNull(value, label);
        if (!safe.equals(safe.strip()) || safe.length() > maximumCodePoints * 2) {
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
