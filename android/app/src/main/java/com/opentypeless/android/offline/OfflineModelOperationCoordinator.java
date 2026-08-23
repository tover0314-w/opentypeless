package com.opentypeless.android.offline;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Keeps the long model transfer alive while settings activities are recreated. */
public final class OfflineModelOperationCoordinator {
    public enum Kind { NONE, DOWNLOAD, DELETE }
    public enum Phase { IDLE, RUNNING, SUCCEEDED, FAILED }

    public record State(
            long generation,
            Kind kind,
            Phase phase,
            int percent,
            long completedBytes,
            long totalBytes,
            String errorMessage) {
        public State {
            if (kind == null || phase == null) {
                throw new IllegalArgumentException("Operation state is required");
            }
            errorMessage = errorMessage == null ? "" : errorMessage;
        }

        public boolean running() {
            return phase == Phase.RUNNING;
        }
    }

    public interface Listener {
        void onState(State state);
    }

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Listener> LISTENERS = Collections.newSetFromMap(new WeakHashMap<>());

    private static long generation;
    private static State state = idleState();
    private static OfflineModelDownloader.Operation operation;

    private OfflineModelOperationCoordinator() {}

    public static State snapshot() {
        synchronized (LOCK) {
            return state;
        }
    }

    public static void addListener(Listener listener) {
        if (listener == null) return;
        State current;
        synchronized (LOCK) {
            LISTENERS.add(listener);
            current = state;
        }
        MAIN.post(() -> listener.onState(current));
    }

    public static void removeListener(Listener listener) {
        if (listener == null) return;
        synchronized (LOCK) {
            LISTENERS.remove(listener);
        }
    }

    public static boolean startDownload(Context context) {
        return start(context, Kind.DOWNLOAD);
    }

    public static boolean startDelete(Context context) {
        return start(context, Kind.DELETE);
    }

    private static boolean start(Context context, Kind kind) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        final long request;
        synchronized (LOCK) {
            if (operation != null || state.running()) return false;
            request = ++generation;
            state = new State(request, kind, Phase.RUNNING, 0, 0, 0, "");
        }
        publish(snapshot());
        try {
            OfflineModelDownloader.Callback callback = new OfflineModelDownloader.Callback() {
                @Override
                public void onProgress(int percent, long completedBytes, long totalBytes) {
                    transition(request, new State(
                            request,
                            kind,
                            Phase.RUNNING,
                            percent,
                            completedBytes,
                            totalBytes,
                            ""), false);
                }

                @Override
                public void onComplete() {
                    transition(request, new State(
                            request,
                            kind,
                            Phase.SUCCEEDED,
                            100,
                            0,
                            0,
                            ""), true);
                }

                @Override
                public void onError(String message) {
                    transition(request, new State(
                            request,
                            kind,
                            Phase.FAILED,
                            0,
                            0,
                            0,
                            message), true);
                }
            };
            OfflineModelDownloader.Operation started = kind == Kind.DOWNLOAD
                    ? OfflineModelDownloader.download(context.getApplicationContext(), callback)
                    : OfflineModelDownloader.delete(context.getApplicationContext(), callback);
            synchronized (LOCK) {
                if (generation == request && state.running()) operation = started;
                else started.cancel();
            }
            return true;
        } catch (RuntimeException error) {
            transition(request, new State(
                    request,
                    kind,
                    Phase.FAILED,
                    0,
                    0,
                    0,
                    safeMessage(error)), true);
            return false;
        }
    }

    private static void transition(long request, State next, boolean terminal) {
        synchronized (LOCK) {
            if (generation != request || state.generation() != request) return;
            state = next;
            if (terminal) operation = null;
        }
        publish(next);
    }

    private static void publish(State value) {
        ArrayList<Listener> listeners;
        synchronized (LOCK) {
            listeners = new ArrayList<>(LISTENERS);
        }
        MAIN.post(() -> {
            for (Listener listener : listeners) listener.onState(value);
        });
    }

    private static State idleState() {
        return new State(0, Kind.NONE, Phase.IDLE, 0, 0, 0, "");
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? "Offline model operation failed"
                : message;
    }
}
