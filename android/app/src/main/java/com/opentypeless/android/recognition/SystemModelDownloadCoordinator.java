package com.opentypeless.android.recognition;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.AppSettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Rebinds Android's potentially interactive model download across Activity recreation. */
public final class SystemModelDownloadCoordinator {
    private static final int MAX_SUBSCRIPTIONS = 16;

    public record State(
            long generation,
            boolean running,
            int progress,
            String language,
            SystemRecognitionSupport.DownloadResult result) {
        public State {
            if (generation < 0L || progress < 0 || progress > 100) {
                throw new IllegalArgumentException("Invalid model download state");
            }
            language = SystemRecognitionSupport.boundedLanguage(language);
            if (running && result != null) {
                throw new IllegalArgumentException("A running download cannot be terminal");
            }
        }

        @Override
        public String toString() {
            return "State{generation=" + generation
                    + ", running=" + running
                    + ", progress=" + progress
                    + ", language=<redacted>, result=" + result + "}";
        }
    }

    public interface Listener {
        void onState(State state);
    }

    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    interface Poster {
        void post(Runnable action);
    }

    interface Starter {
        SystemRecognitionSupport.Operation start(SystemRecognitionSupport.DownloadCallback callback);
    }

    private SystemModelDownloadCoordinator() {}

    public static State snapshot() {
        return ProductionHolder.CORE.snapshot();
    }

    public static Subscription subscribe(Listener listener) {
        return ProductionHolder.CORE.subscribe(listener);
    }

    public static boolean start(
            Context context,
            AppSettings settings,
            PersonalizationSnapshot snapshot) {
        if (context == null || settings == null) {
            throw new IllegalArgumentException("System model settings are required");
        }
        Context application = context.getApplicationContext();
        PersonalizationSnapshot safeSnapshot = snapshot == null
                ? PersonalizationSnapshot.empty()
                : snapshot;
        return ProductionHolder.CORE.start(settings.language(), callback ->
                SystemSpeechRecognizer.triggerModelDownload(
                        application,
                        settings,
                        safeSnapshot,
                        callback));
    }

    public static void cancel() {
        ProductionHolder.CORE.cancel();
    }

    private static final class ProductionHolder {
        private static final Handler MAIN = new Handler(Looper.getMainLooper());
        private static final Core CORE = new Core(action -> MAIN.post(action));
    }

    static final class Core {
        private final Object lock = new Object();
        private final Poster poster;
        private final Set<CoreSubscription> subscriptions = new LinkedHashSet<>();
        private long generation;
        private State state = idleState(0L);
        private Request activeRequest;
        private SystemRecognitionSupport.Operation operation;

        Core(Poster poster) {
            this.poster = Objects.requireNonNull(poster, "poster");
        }

        State snapshot() {
            synchronized (lock) {
                return state;
            }
        }

        Subscription subscribe(Listener listener) {
            Objects.requireNonNull(listener, "listener");
            CoreSubscription subscription = new CoreSubscription(this, listener);
            State current;
            synchronized (lock) {
                if (subscriptions.size() >= MAX_SUBSCRIPTIONS) {
                    throw new IllegalStateException("Too many model download observers");
                }
                subscriptions.add(subscription);
                current = state;
            }
            post(subscription, current);
            return subscription;
        }

        boolean start(String language, Starter starter) {
            Objects.requireNonNull(starter, "starter");
            Request request;
            State startedState;
            State exhaustedState = null;
            synchronized (lock) {
                if (activeRequest != null || operation != null || state.running()) return false;
                if (generation == Long.MAX_VALUE) {
                    exhaustedState = new State(
                            Long.MAX_VALUE,
                            false,
                            0,
                            "",
                            failed(RecognitionRoute.FailureClass.INTERNAL_ERROR));
                    state = exhaustedState;
                    request = null;
                    startedState = null;
                } else {
                    generation++;
                    request = new Request(generation);
                    activeRequest = request;
                    startedState = new State(
                            generation,
                            true,
                            0,
                            language,
                            null);
                    state = startedState;
                }
            }
            if (exhaustedState != null) {
                publish(exhaustedState);
                return false;
            }
            publish(startedState);

            SystemRecognitionSupport.Operation started;
            try {
                started = Objects.requireNonNull(
                        starter.start(new SystemRecognitionSupport.DownloadCallback() {
                            @Override
                            public void onProgress(int percent) {
                                updateProgress(request, percent);
                            }

                            @Override
                            public void onResult(SystemRecognitionSupport.DownloadResult result) {
                                finish(request, result);
                            }
                        }),
                        "operation");
            } catch (RuntimeException ignored) {
                finish(request, failed(RecognitionRoute.FailureClass.INTERNAL_ERROR));
                return false;
            }

            boolean cancelStarted;
            synchronized (lock) {
                if (activeRequest == request && state.running()) {
                    operation = started;
                    cancelStarted = false;
                } else {
                    cancelStarted = true;
                }
            }
            if (cancelStarted) started.cancel();
            return true;
        }

        void cancel() {
            SystemRecognitionSupport.Operation cancelled;
            State next;
            synchronized (lock) {
                if (activeRequest == null
                        && operation == null
                        && !state.running()
                        && state.result() == null
                        && state.progress() == 0
                        && state.language().isEmpty()) {
                    return;
                }
                activeRequest = null;
                cancelled = operation;
                operation = null;
                next = idleState(generation);
                state = next;
            }
            if (cancelled != null) cancelled.cancel();
            publish(next);
        }

        private void updateProgress(Request request, int percent) {
            State next;
            synchronized (lock) {
                if (activeRequest != request || !state.running()) return;
                int bounded = Math.max(0, Math.min(percent, 100));
                if (bounded <= state.progress()) return;
                next = new State(
                        request.generation,
                        true,
                        bounded,
                        state.language(),
                        null);
                state = next;
            }
            publish(next);
        }

        private void finish(
                Request request,
                SystemRecognitionSupport.DownloadResult result) {
            Objects.requireNonNull(result, "result");
            State next;
            synchronized (lock) {
                if (activeRequest != request || !state.running()) return;
                activeRequest = null;
                operation = null;
                next = new State(
                        request.generation,
                        false,
                        state.progress(),
                        state.language(),
                        result);
                state = next;
            }
            publish(next);
        }

        private void publish(State value) {
            ArrayList<CoreSubscription> current;
            synchronized (lock) {
                current = new ArrayList<>(subscriptions);
            }
            for (CoreSubscription subscription : current) post(subscription, value);
        }

        private void post(CoreSubscription subscription, State value) {
            poster.post(() -> subscription.deliver(value));
        }

        private boolean current(State expected) {
            synchronized (lock) {
                return state == expected;
            }
        }

        private void remove(CoreSubscription subscription) {
            synchronized (lock) {
                subscriptions.remove(subscription);
            }
        }

        private static State idleState(long generation) {
            return new State(generation, false, 0, "", null);
        }
    }

    private static final class Request {
        final long generation;

        Request(long generation) {
            this.generation = generation;
        }
    }

    private static final class CoreSubscription implements Subscription {
        private final Core owner;
        private final Listener listener;
        private boolean active = true;

        CoreSubscription(Core owner, Listener listener) {
            this.owner = owner;
            this.listener = listener;
        }

        synchronized void deliver(State value) {
            if (!active || !owner.current(value)) return;
            listener.onState(value);
        }

        @Override
        public void close() {
            synchronized (this) {
                if (!active) return;
                active = false;
            }
            owner.remove(this);
        }

        @Override
        public String toString() {
            return "SystemModelDownloadSubscription{active=<redacted>}";
        }
    }

    private static SystemRecognitionSupport.DownloadResult failed(
            RecognitionRoute.FailureClass failureClass) {
        return new SystemRecognitionSupport.DownloadResult(
                SystemRecognitionSupport.DownloadStatus.FAILED,
                failureClass);
    }
}
