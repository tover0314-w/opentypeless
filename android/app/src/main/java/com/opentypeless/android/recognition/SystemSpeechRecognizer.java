package com.opentypeless.android.recognition;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;

import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.RecognitionBackend;

import java.util.List;

public final class SystemSpeechRecognizer {
    public static final String MICROPHONE_ACCESS_BLOCKED =
            "Android speech service was denied microphone access";
    private static final long STOP_GRACE_MILLIS = 8_000L;
    private static final long NATURAL_END_GRACE_MILLIS = 15_000L;

    public interface Callback {
        void onReady();
        default void onBeginningOfSpeech() {}
        void onPartial(String text);
        void onFinal(String text);
        void onError(int errorCode, String message);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SessionGenerationToken generation = new SessionGenerationToken();
    private final GenerationSafeWatchdog recordingWatchdog = new GenerationSafeWatchdog(
            new GenerationSafeWatchdog.Scheduler() {
                @Override
                public void postDelayed(Runnable action, long delayMillis) {
                    mainHandler.postDelayed(action, delayMillis);
                }

                @Override
                public void removeCallbacks(Runnable action) {
                    mainHandler.removeCallbacks(action);
                }
            });
    private SpeechRecognizer recognizer;
    private Callback activeCallback;
    private long activeRun = Long.MIN_VALUE;
    private long awaitingTerminalRun = Long.MIN_VALUE;

    public SystemSpeechRecognizer(Context context) {
        this.context = context.getApplicationContext();
    }

    public static boolean onDeviceAvailable(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
    }

    public static boolean systemAvailable(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    public static SystemRecognitionSupport.Operation checkRecognitionSupport(
            Context context,
            AppSettings settings,
            PersonalizationSnapshot snapshot,
            SystemRecognitionSupport.Callback callback) {
        return SystemRecognitionSupport.check(context, settings, snapshot, callback);
    }

    public static SystemRecognitionSupport.Operation triggerModelDownload(
            Context context,
            AppSettings settings,
            PersonalizationSnapshot snapshot,
            SystemRecognitionSupport.DownloadCallback callback) {
        return SystemRecognitionSupport.triggerDownload(context, settings, snapshot, callback);
    }

    public void start(
            AppSettings settings,
            PersonalizationSnapshot snapshot,
            Callback callback) {
        long run = generation.next();
        mainHandler.post(() -> startOnMain(run, settings, snapshot, callback));
    }

    private void startOnMain(
            long run,
            AppSettings settings,
            PersonalizationSnapshot snapshot,
            Callback callback) {
        if (!generation.isCurrent(run)) return;
        destroyRecognizer();
        if (!generation.isCurrent(run)) return;
        try {
            if (settings.recognitionBackend() == RecognitionBackend.SYSTEM_ON_DEVICE) {
                if (!onDeviceAvailable(context)) {
                    failStart(
                            run,
                            callback,
                            SpeechRecognizer.ERROR_CLIENT,
                            "On-device speech recognition is not available on this device");
                    return;
                }
                recognizer = createOnDeviceRecognizer();
            } else {
                if (!systemAvailable(context)) {
                    failStart(
                            run,
                            callback,
                            SpeechRecognizer.ERROR_CLIENT,
                            "No Android speech recognition service is installed");
                    return;
                }
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            }
            recognizer.setRecognitionListener(listener(run, callback));
            activeRun = run;
            activeCallback = callback;
            android.content.Intent intent = SystemRecognitionIntentFactory.create(settings, snapshot);
            recordingWatchdog.arm(
                    run,
                    RecognitionTimeoutPolicy.milliseconds(settings.maxRecordingSeconds()),
                    () -> stopForTimeout(run, callback));
            recognizer.startListening(intent);
        } catch (RuntimeException error) {
            failStart(run, callback, SpeechRecognizer.ERROR_CLIENT, error.getMessage() == null
                    ? "Unable to start Android speech recognition"
                    : error.getMessage());
        }
    }

    @SuppressLint("NewApi") // Caller is guarded by onDeviceAvailable(), which requires API 31.
    private SpeechRecognizer createOnDeviceRecognizer() {
        return SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
    }

    public void stop() {
        mainHandler.post(this::stopOnMain);
    }

    public void cancel() {
        long cancellation = generation.invalidateAndGet();
        mainHandler.post(() -> {
            if (!generation.isCurrent(cancellation)) return;
            try {
                if (recognizer != null) recognizer.cancel();
            } catch (RuntimeException ignored) {
                // Some vendor services throw while already stopping; destroy still owns cleanup.
            }
            destroyRecognizer();
        });
    }

    public void destroy() {
        generation.invalidate();
        mainHandler.post(this::destroyRecognizer);
    }

    private RecognitionListener listener(long run, Callback callback) {
        return new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                if (current(run)) callback.onReady();
            }

            @Override
            public void onBeginningOfSpeech() {
                if (current(run)) callback.onBeginningOfSpeech();
            }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override
            public void onEndOfSpeech() {
                if (!current(run) || awaitingTerminalRun == run) return;
                awaitingTerminalRun = run;
                recordingWatchdog.arm(
                        run,
                        NATURAL_END_GRACE_MILLIS,
                        () -> abortAfterStopGrace(
                                run,
                                callback,
                                "Android speech recognition timed out waiting for results"));
            }

            @Override
            public void onError(int error) {
                if (finishCurrent(run)) callback.onError(error, errorMessage(error));
            }

            @Override
            public void onResults(Bundle results) {
                if (!current(run)) return;
                String text;
                try {
                    text = firstResult(results);
                } catch (IllegalArgumentException error) {
                    if (finishCurrent(run)) {
                        callback.onError(SpeechRecognizer.ERROR_CLIENT, error.getMessage());
                    }
                    return;
                }
                if (!finishCurrent(run)) return;
                if (text.isBlank()) {
                    callback.onError(
                            SpeechRecognizer.ERROR_NO_MATCH,
                            "Android speech recognition returned no text");
                }
                else callback.onFinal(text);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (current(run)) {
                    String text;
                    try {
                        text = firstResult(partialResults);
                    } catch (IllegalArgumentException error) {
                        if (finishCurrent(run)) {
                            callback.onError(SpeechRecognizer.ERROR_CLIENT, error.getMessage());
                        }
                        return;
                    }
                    if (!text.isBlank()) callback.onPartial(text);
                }
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        };
    }

    private boolean current(long run) {
        return activeRun == run && generation.isCurrent(run);
    }

    private boolean finishCurrent(long run) {
        if (activeRun != run || !generation.finish(run)) return false;
        destroyRecognizer();
        return true;
    }

    private void failStart(long run, Callback callback, int errorCode, String message) {
        if (!generation.finish(run)) return;
        destroyRecognizer();
        callback.onError(errorCode, message);
    }

    private void destroyRecognizer() {
        recordingWatchdog.disarm();
        awaitingTerminalRun = Long.MIN_VALUE;
        activeRun = Long.MIN_VALUE;
        activeCallback = null;
        SpeechRecognizer doomed = recognizer;
        recognizer = null;
        if (doomed != null) {
            try {
                doomed.destroy();
            } catch (RuntimeException ignored) {
                // The platform object is detached even if a vendor implementation misbehaves.
            }
        }
    }

    private void stopOnMain() {
        long run = activeRun;
        Callback callback = activeCallback;
        if (!current(run) || recognizer == null || callback == null) {
            recordingWatchdog.disarm();
            return;
        }
        requestStop(run, callback, "Android speech recognition timed out while stopping");
    }

    private void stopForTimeout(long run, Callback callback) {
        if (!current(run) || recognizer == null) return;
        requestStop(run, callback, "Android speech recognition timed out");
    }

    private void requestStop(long run, Callback callback, String terminalError) {
        recordingWatchdog.disarm();
        awaitingTerminalRun = run;
        try {
            recognizer.stopListening();
        } catch (RuntimeException error) {
            if (finishCurrent(run)) {
                callback.onError(
                        SpeechRecognizer.ERROR_CLIENT,
                        "Unable to stop Android speech recognition");
            }
            return;
        }
        if (!current(run) || recognizer == null) return;
        recordingWatchdog.arm(
                run,
                STOP_GRACE_MILLIS,
                () -> abortAfterStopGrace(run, callback, terminalError));
    }

    private void abortAfterStopGrace(long run, Callback callback, String terminalError) {
        if (!current(run) || recognizer == null) return;
        try {
            recognizer.cancel();
        } catch (RuntimeException ignored) {
            // destroyRecognizer() below is the final cleanup path.
        }
        if (finishCurrent(run)) {
            callback.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, terminalError);
        }
    }

    private static String firstResult(Bundle bundle) {
        if (bundle == null) return "";
        List<String> values = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return values == null || values.isEmpty() || values.get(0) == null
                ? ""
                : RecognitionTextLimit.apply(values.get(0).trim());
    }

    private String errorMessage(int error) {
        return switch (error) {
            case SpeechRecognizer.ERROR_AUDIO -> "Android speech recognition audio error";
            case SpeechRecognizer.ERROR_CLIENT -> "Android speech recognition was cancelled";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                            ? MICROPHONE_ACCESS_BLOCKED
                            : "Microphone permission is required";
            case SpeechRecognizer.ERROR_NETWORK -> "Android speech recognition network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Android speech recognition timed out";
            case SpeechRecognizer.ERROR_NO_MATCH -> "No speech could be recognized";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Android speech recognizer is busy";
            case SpeechRecognizer.ERROR_SERVER -> "Android speech recognition service error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected";
            default -> "Android speech recognition failed (" + error + ")";
        };
    }
}
