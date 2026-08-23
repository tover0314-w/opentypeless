package com.opentypeless.android.recognition;

public final class RecognitionSessionController {
    public enum State { IDLE, STARTING, LISTENING, PROCESSING }

    public interface Engine {
        interface Callback {
            void onReady();
            void onBeginningOfSpeech();
            void onPartial(String text);
            void onEndOfSpeech();
            void onFinal(String text);
            void onError(RecognitionFailure failure);
        }

        boolean start(RecognitionRequest request, Callback callback) throws Exception;
        void stop();
        void cancel();
        void shutdown();
    }

    public interface Observer {
        void onReady();
        void onBeginningOfSpeech();
        void onEndOfSpeech();
        void onPartial(RecognitionResult result);
        void onFinal(RecognitionResult result);
        void onError(RecognitionFailure failure);

        default void onCancelled() {}
    }

    private static final class Session {
        final long id;
        final RecognitionRequest request;
        final Observer observer;
        boolean readyDelivered;
        boolean beginningDelivered;
        boolean endDelivered;

        Session(long id, RecognitionRequest request, Observer observer) {
            this.id = id;
            this.request = request;
            this.observer = observer;
        }
    }

    private final Engine engine;
    private final Object lock = new Object();
    private long generation;
    private Session active;
    private State state = State.IDLE;
    private boolean shutdown;
    private boolean cancelling;

    public RecognitionSessionController(Engine engine) {
        if (engine == null) throw new IllegalArgumentException("Recognition engine is required");
        this.engine = engine;
    }

    public boolean start(RecognitionRequest request, Observer observer) {
        if (request == null) throw new IllegalArgumentException("Recognition request is required");
        if (observer == null) throw new IllegalArgumentException("Recognition observer is required");

        Session session = null;
        RecognitionFailure immediateFailure = null;
        synchronized (lock) {
            if (shutdown) {
                immediateFailure = new RecognitionFailure(
                        android.speech.SpeechRecognizer.ERROR_CLIENT,
                        "Speech recognition controller is closed");
            } else if (active != null || cancelling) {
                immediateFailure = RecognitionErrors.busy();
            } else {
                session = new Session(++generation, request, observer);
                active = session;
                state = State.STARTING;
            }
        }
        if (immediateFailure != null) {
            observer.onError(immediateFailure);
            return false;
        }

        try {
            boolean accepted = engine.start(request, engineCallback(session.id));
            if (!accepted) fail(session.id, RecognitionErrors.busy());
            return accepted;
        } catch (RecognitionStartException error) {
            fail(session.id, error.failure());
            return false;
        } catch (Exception error) {
            fail(session.id, RecognitionErrors.fromPipelineMessage(error.getMessage()));
            return false;
        }
    }

    public void stop() {
        synchronized (lock) {
            if (active == null || state == State.PROCESSING) return;
            state = State.PROCESSING;
        }
        engine.stop();
    }

    public void cancel() {
        Session cancelled;
        synchronized (lock) {
            cancelled = active;
            active = null;
            generation++;
            state = State.IDLE;
            cancelling = cancelled != null;
        }
        if (cancelled == null) return;
        try {
            engine.cancel();
        } catch (RuntimeException ignored) {
            // Cancellation is best-effort, but the session token is already invalidated.
        } finally {
            synchronized (lock) {
                cancelling = false;
            }
        }
        cancelled.observer.onCancelled();
    }

    public void shutdown() {
        synchronized (lock) {
            if (shutdown) return;
            shutdown = true;
        }
        cancel();
        engine.shutdown();
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    private Engine.Callback engineCallback(long sessionId) {
        return new Engine.Callback() {
            @Override public void onReady() { ready(sessionId); }
            @Override public void onBeginningOfSpeech() { beginningOfSpeech(sessionId); }
            @Override public void onPartial(String text) { partial(sessionId, text); }
            @Override public void onEndOfSpeech() { endOfSpeech(sessionId); }
            @Override public void onFinal(String text) { finish(sessionId, text); }
            @Override public void onError(RecognitionFailure failure) { fail(sessionId, failure); }
        };
    }

    private void ready(long sessionId) {
        synchronized (lock) {
            Session session = current(sessionId);
            if (session == null || session.readyDelivered || state == State.PROCESSING) return;
            session.readyDelivered = true;
            state = State.LISTENING;
            session.observer.onReady();
        }
    }

    private void beginningOfSpeech(long sessionId) {
        synchronized (lock) {
            Session session = current(sessionId);
            if (session == null || session.beginningDelivered || state == State.PROCESSING) return;
            if (!session.readyDelivered) {
                session.readyDelivered = true;
                session.observer.onReady();
            }
            session.beginningDelivered = true;
            state = State.LISTENING;
            session.observer.onBeginningOfSpeech();
        }
    }

    private void partial(long sessionId, String text) {
        RecognitionResult result = RecognitionResult.single(text);
        synchronized (lock) {
            Session session = current(sessionId);
            if (session == null
                    || state == State.PROCESSING
                    || !session.request.partialResults()
                    || result.isEmpty()) {
                return;
            }
            session.observer.onPartial(result.limitedTo(1));
        }
    }

    private void endOfSpeech(long sessionId) {
        synchronized (lock) {
            Session session = current(sessionId);
            if (session == null || session.endDelivered) return;
            session.endDelivered = true;
            state = State.PROCESSING;
            session.observer.onEndOfSpeech();
        }
    }

    private void finish(long sessionId, String text) {
        RecognitionResult result = RecognitionResult.single(text);
        if (result.isEmpty()) {
            fail(sessionId, RecognitionErrors.noMatch());
            return;
        }

        synchronized (lock) {
            Session session = current(sessionId);
            if (session == null) return;
            boolean deliverEnd = !session.endDelivered;
            int maxResults = session.request.maxResults();
            active = null;
            state = State.IDLE;
            if (deliverEnd) session.observer.onEndOfSpeech();
            session.observer.onFinal(result.limitedTo(maxResults));
        }
    }

    private void fail(long sessionId, RecognitionFailure failure) {
        synchronized (lock) {
            Session session = current(sessionId);
            if (session == null) return;
            active = null;
            state = State.IDLE;
            session.observer.onError(failure == null
                    ? new RecognitionFailure(
                            android.speech.SpeechRecognizer.ERROR_SERVER,
                            "Speech recognition failed")
                    : failure);
        }
    }

    private Session current(long sessionId) {
        return active != null && active.id == sessionId && generation == sessionId ? active : null;
    }
}
