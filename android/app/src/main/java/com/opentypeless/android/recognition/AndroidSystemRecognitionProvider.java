package com.opentypeless.android.recognition;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.speech.core.SessionId;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Android framework speech adapter with one main-thread-owned active session. */
final class AndroidSystemRecognitionProvider
        implements RecognitionProvider<AndroidSystemRecognitionProvider.StartRequest> {
    static final int MAX_BIASING_TERMS = 50;
    static final int MAX_BIASING_TERM_CODE_POINTS = 80;

    private final RecognitionBackend recognitionBackend;
    private final ProviderDescriptor descriptor;
    private final Backend backend;
    private final MainThread mainThread;

    private SessionState active;
    private boolean closed;

    static AndroidSystemRecognitionProvider create(
            Context context,
            RecognitionBackend recognitionBackend) {
        Context application = Objects.requireNonNull(context, "context").getApplicationContext();
        return new AndroidSystemRecognitionProvider(
                recognitionBackend,
                new SystemBackend(application),
                new HandlerMainThread());
    }

    AndroidSystemRecognitionProvider(
            RecognitionBackend recognitionBackend,
            Backend backend,
            MainThread mainThread) {
        this.recognitionBackend = requireSystemBackend(recognitionBackend);
        this.descriptor = ProviderDescriptor.declaredForBackend(recognitionBackend);
        this.backend = Objects.requireNonNull(backend, "backend");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ProviderRegistry.ProbeObservation probe() {
        try {
            return backend.available(recognitionBackend)
                    ? new ProviderRegistry.ObservedAvailable(descriptor.capabilities())
                    : new ProviderRegistry.ObservedUnavailable(
                            RecognitionRoute.FailureClass.UNAVAILABLE);
        } catch (RuntimeException ignored) {
            return new ProviderRegistry.ObservedUnavailable(
                    RecognitionRoute.FailureClass.INTERNAL_ERROR);
        }
    }

    @Override
    public PreparationResult prepare(StartRequest request) {
        Objects.requireNonNull(request, "request");
        ProviderRegistry.ProbeObservation observation = probe();
        if (observation instanceof ProviderRegistry.ObservedAvailable) {
            return new Prepared(descriptor);
        }
        return new NotPrepared(
                ((ProviderRegistry.ObservedUnavailable) observation).failureClass());
    }

    @Override
    public Session start(StartRequest request, EventSink sink) {
        SessionState session = new SessionState(
                Objects.requireNonNull(request, "request"),
                Objects.requireNonNull(sink, "sink"));
        mainThread.execute(() -> activate(session));
        return session;
    }

    @Override
    public void close() {
        mainThread.execute(this::closeOnMain);
    }

    private void activate(SessionState session) {
        requireMainThread();
        if (closed) {
            finishDetachedFailure(session, RecognitionRoute.FailureClass.INTERNAL_ERROR);
            return;
        }
        if (active != null) {
            finishDetachedFailure(session, RecognitionRoute.FailureClass.RECOGNIZER_BUSY);
            return;
        }
        ProviderRegistry.ProbeObservation observation = probe();
        if (observation instanceof ProviderRegistry.ObservedUnavailable unavailable) {
            finishDetachedFailure(session, unavailable.failureClass());
            return;
        }

        active = session;
        if (!emitPreparing(session) || !isCurrent(session)) return;
        session.started = true;
        try {
            StartRequest request = session.request;
            if (request == null) return;
            backend.start(
                    recognitionBackend,
                    request,
                    backendCallback(session));
        } catch (RuntimeException ignored) {
            finishFailure(session, RecognitionRoute.FailureClass.INTERNAL_ERROR, true);
        }
    }

    private Backend.Callback backendCallback(SessionState session) {
        return new Backend.Callback() {
            @Override
            public void onReady() {
                mainThread.execute(() -> ready(session));
            }

            @Override
            public void onBeginningOfSpeech() {
                mainThread.execute(() -> beginningOfSpeech(session));
            }

            @Override
            public void onPartial(String text) {
                mainThread.execute(() -> partial(session, text));
            }

            @Override
            public void onEndOfSpeech() {
                mainThread.execute(() -> endpoint(session));
            }

            @Override
            public void onFinal(String text) {
                mainThread.execute(() -> finishFinal(session, text));
            }

            @Override
            public void onError(int errorCode, String message) {
                mainThread.execute(() -> finishFailure(
                        session,
                        failureClass(errorCode, message),
                        false));
            }
        };
    }

    private void ready(SessionState session) {
        requireMainThread();
        if (!acceptsNonTerminal(session) || session.readyDelivered || session.stopRequested) return;
        session.readyDelivered = true;
        emit(session, new RecognitionEvent.Ready(session.sessionId, nextSequence(session)));
    }

    private void beginningOfSpeech(SessionState session) {
        requireMainThread();
        if (!acceptsNonTerminal(session) || session.stopRequested) return;
        if (!session.readyDelivered) ready(session);
        if (!acceptsNonTerminal(session) || session.speechStartedDelivered) return;
        session.speechStartedDelivered = true;
        emit(
                session,
                new RecognitionEvent.SpeechStarted(
                        session.sessionId, nextSequence(session)));
    }

    private void partial(SessionState session, String text) {
        requireMainThread();
        if (!acceptsNonTerminal(session)
                || session.stopRequested
                || session.request == null
                || !session.request.partialResults()
                || text == null
                || text.isBlank()) {
            return;
        }
        try {
            long sequence = nextSequence(session);
            Long revisionOf = session.lastPartialSequence == 0L
                    ? null
                    : session.lastPartialSequence;
            RecognitionEvent.Partial event = new RecognitionEvent.Partial(
                    session.sessionId, sequence, text, null, revisionOf);
            session.lastPartialSequence = sequence;
            emit(session, event);
        } catch (IllegalArgumentException error) {
            finishFailure(session, RecognitionRoute.FailureClass.PROTOCOL_ERROR, true);
        }
    }

    private void endpoint(SessionState session) {
        requireMainThread();
        if (!acceptsNonTerminal(session) || session.endpointDelivered) return;
        session.endpointDelivered = true;
        emit(
                session,
                new RecognitionEvent.Endpoint(
                        session.sessionId, nextSequence(session)));
    }

    private void finishFinal(SessionState session, String text) {
        requireMainThread();
        if (!isCurrent(session) || session.terminal) return;
        if (text == null || text.isBlank()) {
            finishFailure(session, RecognitionRoute.FailureClass.NO_MATCH, false);
            return;
        }
        try {
            if (!session.endpointDelivered) endpoint(session);
            if (!isCurrent(session) || session.terminal) return;
            RecognitionEvent.Final event = new RecognitionEvent.Final(
                    session.sessionId,
                    nextSequence(session),
                    text,
                    RecognitionMetadata.empty());
            markTerminal(session);
            emitTerminal(session, event);
        } catch (IllegalArgumentException error) {
            finishFailure(session, RecognitionRoute.FailureClass.PROTOCOL_ERROR, true);
        }
    }

    private boolean emitPreparing(SessionState session) {
        return emit(
                session,
                new RecognitionEvent.Preparing(
                        session.sessionId, nextSequence(session)));
    }

    private boolean emit(SessionState session, RecognitionEvent event) {
        if (session.terminal || (active != session && !event.terminal())) return false;
        try {
            EventSink sink = session.sink;
            if (sink == null) return false;
            sink.onEvent(event);
            return true;
        } catch (RuntimeException ignored) {
            if (!event.terminal()) abortAfterSinkFailure(session);
            return false;
        }
    }

    private void emitTerminal(SessionState session, RecognitionEvent event) {
        try {
            EventSink sink = session.sink;
            if (sink != null) sink.onEvent(event);
        } catch (RuntimeException ignored) {
            // The session is already detached and terminal; user content is never logged here.
        } finally {
            session.releaseReferences();
        }
    }

    private void abortAfterSinkFailure(SessionState session) {
        if (session.terminal) return;
        markTerminal(session);
        if (session.started) safeCancelBackend();
        session.releaseReferences();
    }

    private void finishFailure(
            SessionState session,
            RecognitionRoute.FailureClass failureClass,
            boolean cancelBackend) {
        requireMainThread();
        if (!isCurrent(session) || session.terminal) return;
        RecognitionEvent.Failure event = new RecognitionEvent.Failure(
                session.sessionId,
                nextSequence(session),
                Objects.requireNonNull(failureClass, "failureClass"));
        markTerminal(session);
        if (cancelBackend && session.started) safeCancelBackend();
        emitTerminal(session, event);
    }

    private void finishDetachedFailure(
            SessionState session,
            RecognitionRoute.FailureClass failureClass) {
        requireMainThread();
        if (session.terminal) return;
        RecognitionEvent.Failure event = new RecognitionEvent.Failure(
                session.sessionId, nextSequence(session), failureClass);
        session.terminal = true;
        emitTerminal(session, event);
    }

    private void stop(SessionState session) {
        mainThread.execute(() -> {
            requireMainThread();
            if (!isCurrent(session)
                    || session.terminal
                    || session.stopRequested
                    || !session.started) {
                return;
            }
            session.stopRequested = true;
            try {
                backend.stop();
            } catch (RuntimeException ignored) {
                finishFailure(session, RecognitionRoute.FailureClass.INTERNAL_ERROR, true);
            }
        });
    }

    private void cancel(SessionState session) {
        mainThread.execute(() -> cancelOnMain(session));
    }

    private void cancelOnMain(SessionState session) {
        requireMainThread();
        if (!isCurrent(session) || session.terminal) return;
        RecognitionEvent.Cancelled event = new RecognitionEvent.Cancelled(
                session.sessionId, nextSequence(session));
        markTerminal(session);
        if (session.started) safeCancelBackend();
        emitTerminal(session, event);
    }

    private void closeOnMain() {
        requireMainThread();
        if (closed) return;
        closed = true;
        SessionState doomed = active;
        if (doomed != null) cancelOnMain(doomed);
        try {
            backend.destroy();
        } catch (RuntimeException ignored) {
            // Provider ownership is already revoked even if an OEM destroy call throws.
        }
    }

    private void safeCancelBackend() {
        try {
            backend.cancel();
        } catch (RuntimeException ignored) {
            // The adapter terminal gate already revoked the session.
        }
    }

    private void markTerminal(SessionState session) {
        session.terminal = true;
        if (active == session) active = null;
    }

    private boolean acceptsNonTerminal(SessionState session) {
        return isCurrent(session) && !session.terminal;
    }

    private boolean isCurrent(SessionState session) {
        return active == session;
    }

    private long nextSequence(SessionState session) {
        if (session.sequence == Long.MAX_VALUE) {
            throw new IllegalStateException("recognition event sequence exhausted");
        }
        return ++session.sequence;
    }

    private void requireMainThread() {
        if (!mainThread.isMainThread()) {
            throw new IllegalStateException("Android recognition lifecycle must run on main thread");
        }
    }

    static RecognitionRoute.FailureClass failureClass(int errorCode, String message) {
        return RecognitionFailureMapper.fromAndroidSystem(errorCode, message);
    }

    record StartRequest(
            SessionId sessionId,
            String language,
            int maxResults,
            boolean partialResults,
            List<String> biasingTerms,
            long timeoutMillis) {
        StartRequest {
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            language = normalizedLanguage(language);
            if (maxResults < 1 || maxResults > 5) {
                throw new IllegalArgumentException("recognition result count is outside its bound");
            }
            biasingTerms = validatedBiasingTerms(biasingTerms);
            if (timeoutMillis <= 0L
                    || timeoutMillis > ProviderCapabilities.APP_CAPTURE_LIMIT_MS) {
                throw new IllegalArgumentException("recognition timeout is outside the app bound");
            }
        }

        static StartRequest fromSnapshot(
                SessionId sessionId,
                RecognitionRequest request,
                PersonalizationSnapshot personalization,
                long timeoutMillis) {
            RecognitionRequest safeRequest = Objects.requireNonNull(request, "request");
            return new StartRequest(
                    sessionId,
                    safeRequest.language(),
                    safeRequest.maxResults(),
                    safeRequest.partialResults(),
                    SystemRecognitionIntentFactory.biasingStrings(personalization),
                    timeoutMillis);
        }

        @Override
        public String toString() {
            return "AndroidSystemStartRequest{session=<redacted>, language=<redacted>, "
                    + "biasingTerms=<redacted>, maxResults=" + maxResults
                    + ", partialResults=" + partialResults
                    + ", timeoutMillis=" + timeoutMillis + "}";
        }
    }

    interface Backend {
        boolean available(RecognitionBackend recognitionBackend);

        void start(
                RecognitionBackend recognitionBackend,
                StartRequest request,
                Callback callback);

        void stop();

        void cancel();

        void destroy();

        interface Callback {
            void onReady();

            void onBeginningOfSpeech();

            void onPartial(String text);

            void onEndOfSpeech();

            void onFinal(String text);

            void onError(int errorCode, String message);
        }
    }

    interface MainThread {
        void execute(Runnable action);

        boolean isMainThread();
    }

    private final class SessionState implements Session {
        private final SessionId sessionId;
        private StartRequest request;
        private EventSink sink;
        private long sequence;
        private long lastPartialSequence;
        private boolean started;
        private boolean stopRequested;
        private boolean readyDelivered;
        private boolean speechStartedDelivered;
        private boolean endpointDelivered;
        private boolean terminal;

        private SessionState(StartRequest request, EventSink sink) {
            this.sessionId = request.sessionId();
            this.request = request;
            this.sink = sink;
        }

        @Override
        public SessionId sessionId() {
            return sessionId;
        }

        @Override
        public void stop() {
            AndroidSystemRecognitionProvider.this.stop(this);
        }

        @Override
        public void cancel() {
            AndroidSystemRecognitionProvider.this.cancel(this);
        }

        @Override
        public void close() {
            cancel();
        }

        @Override
        public String toString() {
            return "AndroidSystemRecognitionSession{session=<redacted>, sequence=" + sequence
                    + ", terminal=" + terminal + "}";
        }

        private void releaseReferences() {
            request = null;
            sink = null;
        }
    }

    private static final class HandlerMainThread implements MainThread {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(Runnable action) {
            Runnable safeAction = Objects.requireNonNull(action, "action");
            if (isMainThread()) {
                safeAction.run();
            } else if (!handler.post(safeAction)) {
                throw new IllegalStateException("Android main looper rejected recognition work");
            }
        }

        @Override
        public boolean isMainThread() {
            return Looper.myLooper() == handler.getLooper();
        }
    }

    private static final class SystemBackend implements Backend {
        private final Context context;
        private final SystemSpeechRecognizer recognizer;

        private SystemBackend(Context context) {
            this.context = context;
            this.recognizer = new SystemSpeechRecognizer(context);
        }

        @Override
        public boolean available(RecognitionBackend recognitionBackend) {
            return recognitionBackend == RecognitionBackend.SYSTEM_ON_DEVICE
                    ? SystemSpeechRecognizer.onDeviceAvailable(context)
                    : SystemSpeechRecognizer.systemAvailable(context);
        }

        @Override
        public void start(
                RecognitionBackend recognitionBackend,
                StartRequest request,
                Callback callback) {
            recognizer.start(
                    recognitionBackend,
                    request.language(),
                    request.maxResults(),
                    request.partialResults(),
                    request.biasingTerms(),
                    new SystemSpeechRecognizer.Callback() {
                        @Override
                        public void onReady() {
                            callback.onReady();
                        }

                        @Override
                        public void onBeginningOfSpeech() {
                            callback.onBeginningOfSpeech();
                        }

                        @Override
                        public void onPartial(String text) {
                            callback.onPartial(text);
                        }

                        @Override
                        public void onEndOfSpeech() {
                            callback.onEndOfSpeech();
                        }

                        @Override
                        public void onFinal(String text) {
                            callback.onFinal(text);
                        }

                        @Override
                        public void onError(int errorCode, String message) {
                            callback.onError(errorCode, message);
                        }
                    },
                    request.timeoutMillis());
        }

        @Override
        public void stop() {
            recognizer.stop();
        }

        @Override
        public void cancel() {
            recognizer.cancel();
        }

        @Override
        public void destroy() {
            recognizer.destroy();
        }
    }

    private static RecognitionBackend requireSystemBackend(RecognitionBackend value) {
        RecognitionBackend backend = Objects.requireNonNull(value, "recognitionBackend");
        if (backend != RecognitionBackend.SYSTEM_DEFAULT
                && backend != RecognitionBackend.SYSTEM_ON_DEVICE) {
            throw new IllegalArgumentException("Android System provider requires a system backend");
        }
        return backend;
    }

    private static String normalizedLanguage(String value) {
        String language = value == null ? "" : value;
        if (language.length() > 160
                || !language.equals(language.strip())
                || language.codePointCount(0, language.length()) > 80
                || !wellFormedUtf16(language)
                || language.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("recognition language is outside its bound");
        }
        return language;
    }

    private static List<String> validatedBiasingTerms(List<String> input) {
        List<String> source = input == null ? List.of() : input;
        if (source.size() > MAX_BIASING_TERMS) {
            throw new IllegalArgumentException("recognition biasing term count is outside its bound");
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : source) {
            String term = Objects.requireNonNull(value, "biasing term");
            if (term.length() > MAX_BIASING_TERM_CODE_POINTS * 2
                    || term.isEmpty()
                    || !term.equals(term.strip())
                    || term.codePointCount(0, term.length()) > MAX_BIASING_TERM_CODE_POINTS
                    || !wellFormedUtf16(term)
                    || term.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("recognition biasing term is outside its bound");
            }
            result.add(term);
        }
        return List.copyOf(result);
    }

    private static boolean wellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); ) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index += 2;
            } else if (Character.isLowSurrogate(unit)) {
                return false;
            } else {
                index++;
            }
        }
        return true;
    }
}
