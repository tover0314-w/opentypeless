package com.opentypeless.android.recognition;

import android.content.Context;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.offline.LocalOfflineRecognizer;
import com.opentypeless.android.offline.LocalRealtimePreview;
import com.opentypeless.android.offline.OfflineModelStore;
import com.opentypeless.android.recognition.RecognitionFailureMapper.LocalAvailability;
import com.opentypeless.android.speech.core.SessionId;

import java.util.Arrays;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Objects;

/** Bounded, fully revisable SenseVoice prefix replay; deliberately not true streaming. */
final class PrefixReplayPreviewProvider
        implements RecognitionProvider<PrefixReplayPreviewProvider.StartRequest> {
    static final int MAX_PCM_BYTES = LocalRealtimePreview.MAX_PCM_BYTES;

    private final Object lifecycleLock = new Object();
    private final ProviderDescriptor descriptor = new ProviderDescriptor(
            "builtin.local-prefix-replay",
            "OpenTypeless prefix preview",
            ProviderCapabilities.prefixReplayPreview());
    private final Backend backend;

    private SessionState active;
    private boolean closed;

    static PrefixReplayPreviewProvider create(Context context) {
        Context safe = Objects.requireNonNull(context, "context");
        Context application = safe.getApplicationContext();
        return new PrefixReplayPreviewProvider(
                new LocalPreviewBackend(application == null ? safe : application));
    }

    PrefixReplayPreviewProvider(Backend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
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
    public PreviewSession start(StartRequest request, EventSink sink) {
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
            RequestClaim claim = safeRequest.claim();
            if (claim == null) {
                return detachedFailure(
                        safeRequest.sessionId(),
                        safeSink,
                        RecognitionRoute.FailureClass.INTERNAL_ERROR);
            }
            session = new SessionState(claim.sessionId, safeSink);
            active = session;
            if (!emitNonTerminalLocked(
                    session,
                    new RecognitionEvent.Preparing(
                            session.sessionId, nextSequenceLocked(session)))) {
                session.releaseReferences();
                return session;
            }
            try {
                session.engine = backend.open(
                        claim.language,
                        text -> onPartial(session, text));
                if (session.engine == null) {
                    throw new IllegalStateException("preview engine was unavailable");
                }
            } catch (RuntimeException ignored) {
                finishFailureLocked(session, failureAfterBackendErrorLocked());
                return session;
            }
            session.ready = true;
            if (!emitNonTerminalLocked(
                    session,
                    new RecognitionEvent.Ready(
                            session.sessionId, nextSequenceLocked(session)))) {
                PreviewEngine engine = session.engine;
                session.releaseReferences();
                cancelQuietly(engine);
            }
        }
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
            // Provider authority is already revoked; backend details remain private.
        }
    }

    private void acceptPcm(SessionState session, byte[] data, int length) {
        PreviewEngine engine;
        byte[] copied;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)
                    || !session.ready
                    || session.engine == null
                    || data == null
                    || length <= 0
                    || session.acceptedPcmBytes >= MAX_PCM_BYTES) {
                return;
            }
            int safeLength = Math.min(length, data.length) & ~1;
            int remaining = MAX_PCM_BYTES - session.acceptedPcmBytes;
            int accepted = Math.min(safeLength, remaining) & ~1;
            if (accepted <= 0) return;
            copied = Arrays.copyOf(data, accepted);
            session.acceptedPcmBytes += accepted;
            engine = session.engine;
        }
        try {
            engine.accept(copied, copied.length);
        } catch (RuntimeException ignored) {
            finishRuntimeFailure(session);
        } finally {
            Arrays.fill(copied, (byte) 0);
        }
    }

    private void onPartial(SessionState session, String value) {
        PreviewEngine cancel = null;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session) || !session.ready) return;
            if (value == null || value.isBlank()) return;
            try {
                long sequence = nextSequenceLocked(session);
                Long revisionOf = session.lastPartialSequence == 0L
                        ? null
                        : session.lastPartialSequence;
                RecognitionEvent.Partial event = new RecognitionEvent.Partial(
                        session.sessionId,
                        sequence,
                        value.strip(),
                        0,
                        revisionOf);
                session.lastPartialSequence = sequence;
                if (!emitNonTerminalLocked(session, event)) {
                    cancel = session.engine;
                    session.releaseReferences();
                }
            } catch (IllegalArgumentException ignored) {
                cancel = session.engine;
                finishFailureLocked(session, RecognitionRoute.FailureClass.PROTOCOL_ERROR);
            } catch (RuntimeException ignored) {
                cancel = session.engine;
                finishFailureLocked(session, RecognitionRoute.FailureClass.INTERNAL_ERROR);
            }
        }
        cancelQuietly(cancel);
    }

    private void finishRuntimeFailure(SessionState session) {
        PreviewEngine cancel;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            cancel = session.engine;
            finishFailureLocked(session, failureAfterBackendErrorLocked());
        }
        cancelQuietly(cancel);
    }

    private void cancel(SessionState session) {
        PreviewEngine engine;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            engine = session.engine;
            RecognitionEvent.Cancelled event = new RecognitionEvent.Cancelled(
                    session.sessionId, nextSequenceLocked(session));
            markTerminalLocked(session);
            emitTerminalLocked(session, event);
        }
        cancelQuietly(engine);
    }

    private PreviewSession detachedFailure(
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

    private boolean emitNonTerminalLocked(SessionState session, RecognitionEvent event) {
        if (!isCurrentLocked(session) || session.sink == null) return false;
        try {
            session.sink.onEvent(event);
            return isCurrentLocked(session);
        } catch (RuntimeException ignored) {
            markTerminalLocked(session);
            return false;
        }
    }

    private void finishFailureLocked(
            SessionState session,
            RecognitionRoute.FailureClass failureClass) {
        if (!isCurrentLocked(session)) return;
        RecognitionEvent.Failure event = new RecognitionEvent.Failure(
                session.sessionId,
                nextSequenceLocked(session),
                Objects.requireNonNull(failureClass, "failureClass"));
        markTerminalLocked(session);
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

    private RecognitionRoute.FailureClass failureAfterBackendErrorLocked() {
        LocalAvailability availability;
        try {
            availability = Objects.requireNonNull(backend.availability(), "availability");
        } catch (RuntimeException ignored) {
            return RecognitionRoute.FailureClass.INTERNAL_ERROR;
        }
        return RecognitionFailureMapper.fromLocalRuntime(availability, null);
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

    private static void cancelQuietly(PreviewEngine engine) {
        if (engine == null) return;
        try {
            engine.cancel();
        } catch (RuntimeException ignored) {
            // Session authority is already revoked; engine details remain private.
        }
    }

    @Override
    public String toString() {
        synchronized (lifecycleLock) {
            return "PrefixReplayPreviewProvider{active=" + (active != null)
                    + ", closed=" + closed + ", content=<redacted>}";
        }
    }

    interface PreviewSession extends RecognitionProvider.Session {
        void acceptPcm(byte[] data, int length);

        int acceptedPcmBytes();
    }

    interface PartialSink {
        void onPartial(String text);
    }

    interface PreviewEngine {
        void accept(byte[] pcm, int length);

        void cancel();
    }

    interface Backend {
        LocalAvailability availability();

        PreviewEngine open(String language, PartialSink sink);

        void close();
    }

    static final class StartRequest implements AutoCloseable {
        private final SessionId sessionId;
        private final String language;
        private boolean claimed;

        StartRequest(SessionId sessionId, String language) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.language = normalizedLanguage(language);
        }

        SessionId sessionId() {
            return sessionId;
        }

        synchronized boolean available() {
            return !claimed;
        }

        private synchronized RequestClaim claim() {
            if (claimed) return null;
            claimed = true;
            return new RequestClaim(sessionId, language);
        }

        @Override
        public synchronized void close() {
            claimed = true;
        }

        @Override
        public synchronized String toString() {
            return "PrefixReplayStartRequest{session=<redacted>, language=<redacted>, available="
                    + available() + "}";
        }
    }

    private final class SessionState implements PreviewSession {
        private final SessionId sessionId;
        private EventSink sink;
        private PreviewEngine engine;
        private long sequence;
        private long lastPartialSequence;
        private int acceptedPcmBytes;
        private boolean ready;
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
        public void acceptPcm(byte[] data, int length) {
            PrefixReplayPreviewProvider.this.acceptPcm(this, data, length);
        }

        @Override
        public int acceptedPcmBytes() {
            synchronized (lifecycleLock) {
                return acceptedPcmBytes;
            }
        }

        @Override
        public void stop() {
            PrefixReplayPreviewProvider.this.cancel(this);
        }

        @Override
        public void cancel() {
            PrefixReplayPreviewProvider.this.cancel(this);
        }

        @Override
        public void close() {
            PrefixReplayPreviewProvider.this.cancel(this);
        }

        private void releaseReferences() {
            sink = null;
            engine = null;
            ready = false;
        }

        @Override
        public String toString() {
            synchronized (lifecycleLock) {
                return "PrefixReplaySession{acceptedBytes=" + acceptedPcmBytes
                        + ", terminal=" + terminal
                        + ", session=<redacted>, content=<redacted>}";
            }
        }
    }

    private static final class RequestClaim {
        private final SessionId sessionId;
        private final String language;

        private RequestClaim(SessionId sessionId, String language) {
            this.sessionId = sessionId;
            this.language = language;
        }

        @Override
        public String toString() {
            return "PrefixReplayRequestClaim{session=<redacted>, language=<redacted>}";
        }
    }

    private static final class LocalPreviewBackend implements Backend {
        private final Context context;

        private LocalPreviewBackend(Context context) {
            this.context = Objects.requireNonNull(context, "context");
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
        public PreviewEngine open(String language, PartialSink sink) {
            LocalRealtimePreview preview = new LocalRealtimePreview(
                    context,
                    language,
                    sink::onPartial);
            return new PreviewEngine() {
                @Override
                public void accept(byte[] pcm, int length) {
                    preview.accept(pcm, length);
                }

                @Override
                public void cancel() {
                    preview.cancel();
                }
            };
        }

        @Override
        public void close() {}
    }

    private static String normalizedLanguage(String value) {
        String text = value == null ? "" : value;
        if (!text.equals(text.strip())) {
            throw new IllegalArgumentException("language is outside its bound");
        }
        if (text.isEmpty()) return "";
        if (text.codePointCount(0, text.length()) > 35) {
            throw new IllegalArgumentException("language is outside its bound");
        }
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
}
