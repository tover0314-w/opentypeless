package com.opentypeless.android.recognition;

import android.content.Context;

import com.opentypeless.android.audio.WavEncoder;
import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.transform.TranscriptIntegrityGuard;

import java.util.Arrays;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * STR-006 on-device composite: Streaming Paraformer owns preview events and SenseVoice owns the
 * single terminal transcript.
 *
 * <p>The provider is deliberately package-confined and is not a production route registration.
 * It captures no microphone itself, owns no editor capability, and retains PCM only for the
 * lifetime of one bounded session.
 */
final class TwoStageStreamingProvider
        implements RecognitionProvider<TwoStageStreamingProvider.StartRequest> {
    static final int SAMPLE_RATE_HZ = 16_000;
    static final int MAX_PCM_FRAME_BYTES = LocalStreamingProvider.MAX_PCM_FRAME_BYTES;
    static final int MAX_TOTAL_PCM_BYTES = LocalStreamingProvider.MAX_TOTAL_PCM_BYTES;

    private static final String PROVIDER_ID = "builtin.local-two-stage";
    private static final String DISPLAY_NAME = "Streaming Paraformer + SenseVoice";

    private final Object lifecycleLock = new Object();
    private final ProviderDescriptor descriptor = new ProviderDescriptor(
            PROVIDER_ID, DISPLAY_NAME, ProviderCapabilities.localTwoStage());
    private final RecognitionProvider<LocalStreamingProvider.StartRequest> streaming;
    private final RecognitionProvider<SenseVoiceFinalProvider.StartRequest> finalizer;
    private final Worker worker;

    private SessionState active;
    private boolean closed;

    static TwoStageStreamingProvider create(Context context) {
        Context application = Objects.requireNonNull(context, "context").getApplicationContext();
        return new TwoStageStreamingProvider(
                LocalStreamingProvider.create(application),
                SenseVoiceFinalProvider.create(application),
                new SingleWorker());
    }

    TwoStageStreamingProvider(
            RecognitionProvider<LocalStreamingProvider.StartRequest> streaming,
            RecognitionProvider<SenseVoiceFinalProvider.StartRequest> finalizer,
            Worker worker) {
        this.streaming = Objects.requireNonNull(streaming, "streaming");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ProviderRegistry.ProbeObservation probe() {
        synchronized (lifecycleLock) {
            if (closed) return unavailable(RecognitionRoute.FailureClass.UNAVAILABLE);
        }
        RecognitionRoute.FailureClass failure = childAvailability(streaming);
        if (failure == null) failure = childAvailability(finalizer);
        return failure == null
                ? new ProviderRegistry.ObservedAvailable(descriptor.capabilities())
                : unavailable(failure);
    }

    @Override
    public PreparationResult prepare(StartRequest request) {
        StartRequest safeRequest = Objects.requireNonNull(request, "request");
        synchronized (lifecycleLock) {
            if (closed) return new NotPrepared(RecognitionRoute.FailureClass.UNAVAILABLE);
            if (!safeRequest.available()) {
                return new NotPrepared(RecognitionRoute.FailureClass.INTERNAL_ERROR);
            }
        }
        RecognitionRoute.FailureClass failure = childAvailability(streaming);
        if (failure == null) failure = childAvailability(finalizer);
        return failure == null ? new Prepared(descriptor) : new NotPrepared(failure);
    }

    @Override
    public StreamingSession start(StartRequest request, EventSink sink) {
        StartRequest safeRequest = Objects.requireNonNull(request, "request");
        EventSink safeSink = Objects.requireNonNull(sink, "sink");
        SessionState session;
        synchronized (lifecycleLock) {
            if (closed) {
                return detachedFailure(
                        safeRequest.sessionId(), safeSink,
                        RecognitionRoute.FailureClass.UNAVAILABLE);
            }
            if (active != null) {
                return detachedFailure(
                        safeRequest.sessionId(), safeSink,
                        RecognitionRoute.FailureClass.RECOGNIZER_BUSY);
            }
            RecognitionRoute.FailureClass availability = childAvailability(streaming);
            if (availability == null) availability = childAvailability(finalizer);
            if (availability != null) {
                return detachedFailure(safeRequest.sessionId(), safeSink, availability);
            }
            RequestClaim claim = safeRequest.claim();
            if (claim == null) {
                return detachedFailure(
                        safeRequest.sessionId(), safeSink,
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
        startStreaming(session);
        return session;
    }

    private void startStreaming(SessionState session) {
        LocalStreamingProvider.StartRequest request =
                new LocalStreamingProvider.StartRequest(session.sessionId);
        RecognitionProvider.Session child = null;
        try {
            child = streaming.start(request, event -> onStreamingEvent(session, event));
        } catch (RuntimeException ignored) {
            degradeStreaming(session);
        } finally {
            request.close();
        }
        if (!(child instanceof LocalStreamingProvider.StreamingSession stream)) {
            cancelQuietly(child);
            degradeStreaming(session);
            return;
        }
        boolean retained;
        synchronized (lifecycleLock) {
            retained = isCurrentLocked(session) && session.streamingActive;
            if (retained) session.streamingSession = stream;
        }
        if (!retained) cancelQuietly(stream);
    }

    private void onStreamingEvent(SessionState session, RecognitionEvent event) {
        Objects.requireNonNull(event, "event");
        RecognitionProvider.Session[] cancel = null;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session) || !session.streamingActive) return;
            if (!session.sessionId.equals(event.sessionId())
                    || event.sequence() <= session.lastStreamingSequence) {
                session.streamingActive = false;
                cancel = detachChildrenLocked(session);
                emitReadyIfNeededLocked(session);
            } else {
                session.lastStreamingSequence = event.sequence();
                if (event instanceof RecognitionEvent.Ready) {
                    if (!emitReadyIfNeededLocked(session)) {
                        cancel = detachChildrenLocked(session);
                    }
                } else if (event instanceof RecognitionEvent.Partial partial) {
                    if (!session.ready && !emitReadyIfNeededLocked(session)) {
                        cancel = detachChildrenLocked(session);
                    } else if (!emitPartialLocked(session, partial.text())) {
                        cancel = detachChildrenLocked(session);
                    }
                } else if (event instanceof RecognitionEvent.SpeechStarted) {
                    if (!emitMappedLocked(
                            session,
                            new RecognitionEvent.SpeechStarted(
                                    session.sessionId, nextSequenceLocked(session)))) {
                        cancel = detachChildrenLocked(session);
                    }
                } else if (event instanceof RecognitionEvent.Endpoint) {
                    if (!emitMappedLocked(
                            session,
                            new RecognitionEvent.Endpoint(
                                    session.sessionId, nextSequenceLocked(session)))) {
                        cancel = detachChildrenLocked(session);
                    }
                } else if (event instanceof RecognitionEvent.Failure
                        || event instanceof RecognitionEvent.Cancelled
                        || event instanceof RecognitionEvent.Final) {
                    session.streamingActive = false;
                    cancel = detachChildrenLocked(session);
                    emitReadyIfNeededLocked(session);
                }
            }
        }
        cancelQuietly(cancel);
    }

    private boolean emitPartialLocked(SessionState session, String text) {
        if (!isCurrentLocked(session) || session.stopping) return false;
        long sequence = nextSequenceLocked(session);
        Long revisionOf = session.lastPartialText == null
                        || text.startsWith(session.lastPartialText)
                ? null
                : session.lastPartialSequence;
        RecognitionEvent.Partial event = new RecognitionEvent.Partial(
                session.sessionId, sequence, text, null, revisionOf);
        session.lastPartialText = text;
        session.lastPartialSequence = sequence;
        return emitNonTerminalLocked(session, event);
    }

    private boolean emitReadyIfNeededLocked(SessionState session) {
        if (!isCurrentLocked(session) || session.stopping) return false;
        if (session.ready) return true;
        session.ready = true;
        return emitNonTerminalLocked(
                session,
                new RecognitionEvent.Ready(
                        session.sessionId, nextSequenceLocked(session)));
    }

    private boolean emitMappedLocked(SessionState session, RecognitionEvent event) {
        return emitNonTerminalLocked(session, event);
    }

    private void degradeStreaming(SessionState session) {
        RecognitionProvider.Session cancel;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session) || !session.streamingActive) return;
            session.streamingActive = false;
            cancel = session.streamingSession;
            session.streamingSession = null;
            emitReadyIfNeededLocked(session);
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
        LocalStreamingProvider.StreamingSession stream;
        RecognitionProvider.Session[] cancel = null;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session) || session.stopping) return false;
            if (!session.audio.append(pcm, length)) {
                cancel = finishFailureLocked(
                        session, RecognitionRoute.FailureClass.AUDIO_ERROR);
                stream = null;
            } else {
                session.acceptedPcmBytes += length;
                stream = session.streamingActive ? session.streamingSession : null;
            }
        }
        cancelQuietly(cancel);
        if (cancel != null) return false;
        if (stream != null) {
            try {
                if (!stream.acceptPcm(pcm, length)) degradeStreaming(session);
            } catch (RuntimeException ignored) {
                degradeStreaming(session);
            }
        }
        return true;
    }

    private void stop(SessionState session) {
        RecognitionProvider.Session cancelStream = null;
        RecognitionProvider.Session[] cancelChildren = null;
        AudioClaim claim = null;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session) || session.stopping) return;
            session.stopping = true;
            session.streamingActive = false;
            cancelStream = session.streamingSession;
            session.streamingSession = null;
            if (session.acceptedPcmBytes == 0) {
                cancelChildren = finishFailureLocked(
                        session, RecognitionRoute.FailureClass.NO_MATCH);
            } else {
                claim = session.audio.claim();
                session.audioClaim = claim;
            }
        }
        cancelQuietly(cancelStream);
        cancelQuietly(cancelChildren);
        if (claim == null) return;
        AudioClaim finalClaim = claim;
        try {
            worker.execute(() -> finalizeOnWorker(session, finalClaim));
        } catch (RuntimeException ignored) {
            finalClaim.close();
            finishFailure(session, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        }
    }

    private void finalizeOnWorker(SessionState session, AudioClaim claim) {
        byte[] pcm = claim.take();
        if (pcm == null) return;
        byte[] wav = null;
        SenseVoiceFinalProvider.StartRequest request = null;
        try {
            synchronized (lifecycleLock) {
                if (!isCurrentLocked(session) || !session.stopping) return;
            }
            wav = WavEncoder.pcm16Mono(pcm, SAMPLE_RATE_HZ);
            request = new SenseVoiceFinalProvider.StartRequest(
                    session.sessionId,
                    wav,
                    session.language,
                    session.useInverseTextNormalization,
                    durationMs(session.acceptedPcmBytes));
        } catch (RuntimeException ignored) {
            finishFailure(session, RecognitionRoute.FailureClass.INTERNAL_ERROR);
            return;
        } finally {
            Arrays.fill(pcm, (byte) 0);
            if (wav != null) Arrays.fill(wav, (byte) 0);
        }

        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session) || !session.stopping) {
                request.close();
                return;
            }
        }

        RecognitionProvider.Session child = null;
        try {
            child = finalizer.start(request, event -> onFinalizerEvent(session, event));
        } catch (RuntimeException ignored) {
            finishFailure(session, RecognitionRoute.FailureClass.INTERNAL_ERROR);
        } finally {
            request.close();
        }
        boolean retained;
        synchronized (lifecycleLock) {
            retained = isCurrentLocked(session) && session.stopping;
            if (retained) session.finalizerSession = child;
        }
        if (!retained) cancelQuietly(child);
    }

    private void onFinalizerEvent(SessionState session, RecognitionEvent event) {
        Objects.requireNonNull(event, "event");
        RecognitionProvider.Session[] cancel = null;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session) || !session.stopping) return;
            if (!session.sessionId.equals(event.sessionId())
                    || event.sequence() <= session.lastFinalizerSequence) {
                cancel = finishFailureLocked(
                        session, RecognitionRoute.FailureClass.PROTOCOL_ERROR);
            } else {
                session.lastFinalizerSequence = event.sequence();
                if (event instanceof RecognitionEvent.Final terminal) {
                    cancel = finishFinalLocked(session, terminal);
                } else if (event instanceof RecognitionEvent.Failure failure) {
                    cancel = finishFailureLocked(session, failure.failureClass());
                } else if (event instanceof RecognitionEvent.Cancelled) {
                    cancel = finishCancelledLocked(session);
                }
            }
        }
        cancelQuietly(cancel);
    }

    private RecognitionProvider.Session[] finishFinalLocked(
            SessionState session, RecognitionEvent.Final terminal) {
        String text = terminal.text();
        String preview = session.lastPartialText;
        if (preview != null && !preview.isBlank()) {
            try {
                if (!TranscriptIntegrityGuard.validate(
                                preview,
                                text,
                                ProcessingMode.SMART,
                                PersonalizationSnapshot.empty())
                        .safe()) {
                    text = preview;
                }
            } catch (RuntimeException ignored) {
                text = preview;
            }
        }
        RecognitionEvent.Final event;
        try {
            event = new RecognitionEvent.Final(
                    session.sessionId,
                    nextSequenceLocked(session),
                    text,
                    terminal.metadata());
        } catch (RuntimeException ignored) {
            return finishFailureLocked(
                    session, RecognitionRoute.FailureClass.PROTOCOL_ERROR);
        }
        RecognitionProvider.Session[] cancel = detachChildrenLocked(session);
        markTerminalLocked(session);
        emitTerminalLocked(session, event);
        return cancel;
    }

    private void cancel(SessionState session) {
        RecognitionProvider.Session[] cancel;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            cancel = finishCancelledLocked(session);
        }
        cancelQuietly(cancel);
    }

    private RecognitionProvider.Session[] finishCancelledLocked(SessionState session) {
        RecognitionEvent.Cancelled event = new RecognitionEvent.Cancelled(
                session.sessionId, nextSequenceLocked(session));
        RecognitionProvider.Session[] cancel = detachChildrenLocked(session);
        markTerminalLocked(session);
        emitTerminalLocked(session, event);
        return cancel;
    }

    private void finishFailure(
            SessionState session,
            RecognitionRoute.FailureClass failureClass) {
        RecognitionProvider.Session[] cancel;
        synchronized (lifecycleLock) {
            if (!isCurrentLocked(session)) return;
            cancel = finishFailureLocked(session, failureClass);
        }
        cancelQuietly(cancel);
    }

    private RecognitionProvider.Session[] finishFailureLocked(
            SessionState session,
            RecognitionRoute.FailureClass failureClass) {
        RecognitionEvent.Failure event = new RecognitionEvent.Failure(
                session.sessionId,
                nextSequenceLocked(session),
                Objects.requireNonNull(failureClass, "failureClass"));
        RecognitionProvider.Session[] cancel = detachChildrenLocked(session);
        markTerminalLocked(session);
        emitTerminalLocked(session, event);
        return cancel;
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
            // Terminal authority is already committed and transcript content is never logged.
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

    private RecognitionProvider.Session[] detachChildrenLocked(SessionState session) {
        RecognitionProvider.Session[] children = {
            session.streamingSession, session.finalizerSession
        };
        session.streamingSession = null;
        session.finalizerSession = null;
        return children;
    }

    private boolean isCurrentLocked(SessionState session) {
        return active == session && !session.terminal;
    }

    private void markTerminalLocked(SessionState session) {
        session.terminal = true;
        if (active == session) active = null;
    }

    @Override
    public void close() {
        RecognitionProvider.Session[] cancel = null;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            if (active != null) {
                cancel = finishCancelledLocked(active);
            }
        }
        cancelQuietly(cancel);
        closeQuietly(streaming);
        closeQuietly(finalizer);
        try {
            worker.close();
        } catch (RuntimeException ignored) {
            // Provider authority and audio ownership have already been revoked.
        }
    }

    @Override
    public String toString() {
        synchronized (lifecycleLock) {
            return "TwoStageStreamingProvider{active=" + (active != null)
                    + ", closed=" + closed + ", content=<redacted>}";
        }
    }

    interface StreamingSession extends RecognitionProvider.Session {
        boolean acceptPcm(byte[] pcm, int length);

        int acceptedPcmBytes();
    }

    static final class StartRequest implements AutoCloseable {
        private final SessionId sessionId;
        private final String language;
        private final boolean useInverseTextNormalization;
        private boolean claimed;

        StartRequest(
                SessionId sessionId,
                String language,
                boolean useInverseTextNormalization) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.language = normalizedLanguage(language);
            this.useInverseTextNormalization = useInverseTextNormalization;
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
            return new RequestClaim(sessionId, language, useInverseTextNormalization);
        }

        @Override
        public synchronized void close() {
            claimed = true;
        }

        @Override
        public synchronized String toString() {
            return "TwoStageStartRequest{session=<redacted>, language=<redacted>, available="
                    + available() + "}";
        }
    }

    interface Worker {
        void execute(Runnable action);

        void close();
    }

    private static final class SingleWorker implements Worker {
        private final ExecutorService executor = Executors.newSingleThreadExecutor(action ->
                new Thread(action, "OpenTypeless-TwoStageFinalizer"));

        @Override
        public void execute(Runnable action) {
            executor.execute(Objects.requireNonNull(action, "action"));
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static final class RequestClaim {
        private final SessionId sessionId;
        private final String language;
        private final boolean useInverseTextNormalization;

        private RequestClaim(
                SessionId sessionId,
                String language,
                boolean useInverseTextNormalization) {
            this.sessionId = sessionId;
            this.language = language;
            this.useInverseTextNormalization = useInverseTextNormalization;
        }

        private SessionId sessionId() {
            return sessionId;
        }

        private String language() {
            return language;
        }

        private boolean useInverseTextNormalization() {
            return useInverseTextNormalization;
        }

        @Override
        public String toString() {
            return "TwoStageRequestClaim{session=<redacted>, language=<redacted>}";
        }
    }

    private final class SessionState implements StreamingSession {
        private final SessionId sessionId;
        private String language;
        private final boolean useInverseTextNormalization;
        private final PcmBuffer audio = new PcmBuffer();
        private EventSink sink;
        private LocalStreamingProvider.StreamingSession streamingSession;
        private RecognitionProvider.Session finalizerSession;
        private AudioClaim audioClaim;
        private String lastPartialText;
        private long sequence;
        private long lastPartialSequence;
        private long lastStreamingSequence;
        private long lastFinalizerSequence;
        private int acceptedPcmBytes;
        private boolean streamingActive = true;
        private boolean ready;
        private boolean stopping;
        private boolean terminal;

        private SessionState(RequestClaim claim, EventSink sink) {
            sessionId = claim.sessionId();
            language = claim.language();
            useInverseTextNormalization = claim.useInverseTextNormalization();
            this.sink = sink;
        }

        private SessionState(SessionId sessionId, EventSink sink) {
            this.sessionId = sessionId;
            language = "";
            useInverseTextNormalization = false;
            this.sink = sink;
        }

        @Override
        public SessionId sessionId() {
            return sessionId;
        }

        @Override
        public boolean acceptPcm(byte[] pcm, int length) {
            return TwoStageStreamingProvider.this.acceptPcm(this, pcm, length);
        }

        @Override
        public int acceptedPcmBytes() {
            synchronized (lifecycleLock) {
                return acceptedPcmBytes;
            }
        }

        @Override
        public void stop() {
            TwoStageStreamingProvider.this.stop(this);
        }

        @Override
        public void cancel() {
            TwoStageStreamingProvider.this.cancel(this);
        }

        @Override
        public void close() {
            cancel();
        }

        private void releaseReferences() {
            audio.close();
            if (audioClaim != null) audioClaim.close();
            audioClaim = null;
            language = null;
            lastPartialText = null;
            sink = null;
        }

        @Override
        public String toString() {
            synchronized (lifecycleLock) {
                return "TwoStageSession{session=<redacted>, sequence=" + sequence
                        + ", terminal=" + terminal + ", content=<redacted>}";
            }
        }
    }

    private static final class PcmBuffer implements AutoCloseable {
        private byte[] bytes = new byte[0];
        private int length;
        private boolean claimed;

        synchronized boolean append(byte[] source, int count) {
            if (claimed || count > MAX_TOTAL_PCM_BYTES - length) return false;
            int required = length + count;
            if (bytes.length < required) {
                int capacity = Math.max(4_096, bytes.length);
                while (capacity < required) {
                    int grown = capacity + Math.max(4_096, capacity >>> 1);
                    capacity = Math.min(MAX_TOTAL_PCM_BYTES, grown);
                    if (capacity < required && capacity == MAX_TOTAL_PCM_BYTES) return false;
                }
                byte[] replacement = Arrays.copyOf(bytes, capacity);
                Arrays.fill(bytes, (byte) 0);
                bytes = replacement;
            }
            System.arraycopy(source, 0, bytes, length, count);
            length = required;
            return true;
        }

        synchronized AudioClaim claim() {
            if (claimed || length == 0) return null;
            claimed = true;
            byte[] exact = Arrays.copyOf(bytes, length);
            Arrays.fill(bytes, (byte) 0);
            bytes = new byte[0];
            length = 0;
            return new AudioClaim(exact);
        }

        @Override
        public synchronized void close() {
            claimed = true;
            Arrays.fill(bytes, (byte) 0);
            bytes = new byte[0];
            length = 0;
        }
    }

    private static final class AudioClaim implements AutoCloseable {
        private byte[] pcm;

        private AudioClaim(byte[] pcm) {
            this.pcm = pcm;
        }

        synchronized byte[] take() {
            byte[] taken = pcm;
            pcm = null;
            return taken;
        }

        @Override
        public synchronized void close() {
            if (pcm != null) Arrays.fill(pcm, (byte) 0);
            pcm = null;
        }
    }

    private static RecognitionRoute.FailureClass childAvailability(
            RecognitionProvider<?> child) {
        try {
            ProviderRegistry.ProbeObservation observation = child.probe();
            if (observation instanceof ProviderRegistry.ObservedAvailable) return null;
            return ((ProviderRegistry.ObservedUnavailable) observation).failureClass();
        } catch (RuntimeException ignored) {
            return RecognitionRoute.FailureClass.INTERNAL_ERROR;
        }
    }

    private static ProviderRegistry.ObservedUnavailable unavailable(
            RecognitionRoute.FailureClass failureClass) {
        return new ProviderRegistry.ObservedUnavailable(failureClass);
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

    private static String normalizedLanguage(String value) {
        String language = Objects.requireNonNull(value, "language");
        if (language.codePointCount(0, language.length())
                        > RecognitionMetadata.MAX_LANGUAGE_TAG_CODE_POINTS
                || !language.equals(language.strip())) {
            throw new IllegalArgumentException("language is outside its bound");
        }
        for (int index = 0; index < language.length(); ) {
            int codePoint = language.codePointAt(index);
            if (Character.isSurrogate(language.charAt(index))
                    && (codePoint == language.charAt(index))) {
                throw new IllegalArgumentException("language must be well-formed UTF-16");
            }
            index += Character.charCount(codePoint);
        }
        if (language.isEmpty()) return language;
        try {
            return new Locale.Builder().setLanguageTag(language).build().toLanguageTag();
        } catch (IllformedLocaleException error) {
            throw new IllegalArgumentException("language tag is invalid");
        }
    }

    private static void cancelQuietly(RecognitionProvider.Session session) {
        if (session == null) return;
        try {
            session.cancel();
        } catch (RuntimeException ignored) {
            // Composite authority is independent of child teardown details.
        }
    }

    private static void cancelQuietly(RecognitionProvider.Session[] sessions) {
        if (sessions == null) return;
        for (RecognitionProvider.Session session : sessions) cancelQuietly(session);
        Arrays.fill(sessions, null);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Composite authority and audio references have already been revoked.
        }
    }
}
