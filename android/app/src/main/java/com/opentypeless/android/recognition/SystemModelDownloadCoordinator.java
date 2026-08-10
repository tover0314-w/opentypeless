package com.opentypeless.android.recognition;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.AppSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Rebinds Android's potentially interactive model download across Activity recreation. */
public final class SystemModelDownloadCoordinator {
    public record State(
            long generation,
            boolean running,
            int progress,
            String language,
            SystemRecognitionSupport.DownloadResult result) {}

    public interface Listener {
        void onState(State state);
    }

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Listener> LISTENERS = Collections.newSetFromMap(new WeakHashMap<>());

    private static long generation;
    private static State state = new State(0, false, 0, "", null);
    private static SystemRecognitionSupport.Operation operation;

    private SystemModelDownloadCoordinator() {}

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

    public static boolean start(
            Context context,
            AppSettings settings,
            PersonalizationSnapshot snapshot) {
        if (context == null || settings == null) {
            throw new IllegalArgumentException("System model settings are required");
        }
        final long request;
        synchronized (LOCK) {
            if (operation != null || state.running()) return false;
            request = ++generation;
            state = new State(request, true, 0, settings.language(), null);
        }
        publish(snapshot());
        try {
            SystemRecognitionSupport.Operation started =
                    SystemSpeechRecognizer.triggerModelDownload(
                            context.getApplicationContext(),
                            settings,
                            snapshot == null ? PersonalizationSnapshot.empty() : snapshot,
                            new SystemRecognitionSupport.DownloadCallback() {
                                @Override
                                public void onProgress(int percent) {
                                    updateProgress(request, percent);
                                }

                                @Override
                                public void onResult(
                                        SystemRecognitionSupport.DownloadResult result) {
                                    finish(request, result);
                                }
                            });
            synchronized (LOCK) {
                if (generation == request && state.running()) operation = started;
                else started.cancel();
            }
            return true;
        } catch (RuntimeException error) {
            finish(request, new SystemRecognitionSupport.DownloadResult(
                    SystemRecognitionSupport.DownloadStatus.FAILED,
                    safeMessage(error),
                    android.speech.SpeechRecognizer.ERROR_CLIENT));
            return false;
        }
    }

    public static void cancel() {
        SystemRecognitionSupport.Operation cancelled;
        State next;
        synchronized (LOCK) {
            generation++;
            cancelled = operation;
            operation = null;
            next = new State(generation, false, 0, "", null);
            state = next;
        }
        if (cancelled != null) cancelled.cancel();
        publish(next);
    }

    private static void updateProgress(long request, int percent) {
        State next;
        synchronized (LOCK) {
            if (generation != request || !state.running()) return;
            next = new State(
                    request,
                    true,
                    Math.max(0, Math.min(percent, 100)),
                    state.language(),
                    null);
            state = next;
        }
        publish(next);
    }

    private static void finish(
            long request,
            SystemRecognitionSupport.DownloadResult result) {
        State next;
        synchronized (LOCK) {
            if (generation != request || !state.running()) return;
            operation = null;
            next = new State(request, false, state.progress(), state.language(), result);
            state = next;
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

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? "Android could not start the model download"
                : message;
    }
}
