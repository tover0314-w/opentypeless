package com.opentypeless.android.recognition;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;

import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.RecognitionBackend;

import java.util.concurrent.atomic.AtomicBoolean;

/** Safe one-shot wrappers for Android language support and on-device model download APIs. */
public final class SystemRecognitionSupport {
    private static final long SUPPORT_TIMEOUT_MILLIS = 15_000L;
    private static final long DOWNLOAD_DISPATCH_GRACE_MILLIS = 1_000L;

    public enum Status {
        INSTALLED,
        DOWNLOAD_PENDING,
        DOWNLOAD_AVAILABLE,
        ONLINE_ONLY,
        UNSUPPORTED,
        LANGUAGE_UNSPECIFIED,
        LEGACY_NOT_VERIFIABLE,
        SERVICE_UNAVAILABLE,
        ERROR
    }

    public record Result(
            Status status,
            String language,
            String message,
            boolean canDownload,
            int errorCode) {}

    public interface Callback {
        void onResult(Result result);
    }

    public enum DownloadStatus {
        REQUESTED,
        SCHEDULED,
        COMPLETED,
        API_UNAVAILABLE,
        FAILED
    }

    public record DownloadResult(DownloadStatus status, String message, int errorCode) {}

    public interface DownloadCallback {
        default void onProgress(int percent) {}
        void onResult(DownloadResult result);
    }

    public interface Operation {
        void cancel();
    }

    private SystemRecognitionSupport() {}

    static Operation check(
            Context context,
            AppSettings settings,
            PersonalizationSnapshot snapshot,
            Callback callback) {
        if (context == null || callback == null) {
            throw new IllegalArgumentException("Context and support callback are required");
        }
        Handler main = new Handler(Looper.getMainLooper());
        OneShotOperation operation = new OneShotOperation(main);
        Context application = context.getApplicationContext();
        main.post(() -> beginCheck(operation, application, settings, snapshot, callback));
        return operation;
    }

    static Operation triggerDownload(
            Context context,
            AppSettings settings,
            PersonalizationSnapshot snapshot,
            DownloadCallback callback) {
        if (context == null || callback == null) {
            throw new IllegalArgumentException("Context and download callback are required");
        }
        Handler main = new Handler(Looper.getMainLooper());
        OneShotOperation operation = new OneShotOperation(main);
        Context application = context.getApplicationContext();
        main.post(() -> beginDownload(operation, application, settings, snapshot, callback));
        return operation;
    }

    private static void beginCheck(
            OneShotOperation operation,
            Context context,
            AppSettings settings,
            PersonalizationSnapshot snapshot,
            Callback callback) {
        if (!operation.isActive()) return;
        if (settings == null) {
            complete(operation, callback, error("Speech recognition settings are unavailable"));
            return;
        }
        RecognitionSupportPlatformPolicy.Decision platform =
                RecognitionSupportPlatformPolicy.decide(
                        Build.VERSION.SDK_INT,
                        serviceAvailable(context, settings.recognitionBackend()));
        if (platform == RecognitionSupportPlatformPolicy.Decision.SERVICE_UNAVAILABLE) {
            complete(operation, callback, new Result(
                    Status.SERVICE_UNAVAILABLE,
                    settings.language(),
                    "No compatible Android speech recognition service is available",
                    false,
                    SpeechRecognizer.ERROR_CLIENT));
            return;
        }
        if (platform == RecognitionSupportPlatformPolicy.Decision.LEGACY_NOT_VERIFIABLE) {
            complete(operation, callback, new Result(
                    Status.LEGACY_NOT_VERIFIABLE,
                    settings.language(),
                    "A speech service exists, but Android 12L or earlier cannot preflight "
                            + "the selected language model; offline availability is unverified",
                    false,
                    0));
            return;
        }
        SystemRecognitionSupportApi33.check(operation, context, settings, snapshot, callback);
    }

    private static void beginDownload(
            OneShotOperation operation,
            Context context,
            AppSettings settings,
            PersonalizationSnapshot snapshot,
            DownloadCallback callback) {
        if (!operation.isActive()) return;
        if (settings == null
                || settings.recognitionBackend() != RecognitionBackend.SYSTEM_ON_DEVICE) {
            completeDownload(operation, callback, new DownloadResult(
                    DownloadStatus.FAILED,
                    "Model download requires the Android on-device recognition backend",
                    SpeechRecognizer.ERROR_CLIENT));
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            completeDownload(operation, callback, new DownloadResult(
                    DownloadStatus.API_UNAVAILABLE,
                    "Android 12L or earlier cannot request a speech model download from this app",
                    SpeechRecognizer.ERROR_CLIENT));
            return;
        }
        if (!serviceAvailable(context, RecognitionBackend.SYSTEM_ON_DEVICE)) {
            completeDownload(operation, callback, new DownloadResult(
                    DownloadStatus.FAILED,
                    "No Android on-device speech recognition service is available",
                    SpeechRecognizer.ERROR_CLIENT));
            return;
        }
        SystemRecognitionSupportApi33.download(
                operation,
                context,
                settings,
                snapshot,
                callback);
    }

    private static boolean serviceAvailable(Context context, RecognitionBackend backend) {
        try {
            return backend == RecognitionBackend.SYSTEM_ON_DEVICE
                    ? Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                    : SpeechRecognizer.isRecognitionAvailable(context);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Result error(String message) {
        return new Result(Status.ERROR, "", message, false, SpeechRecognizer.ERROR_CLIENT);
    }

    static String message(RuntimeException error, String fallback) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    static void complete(
            OneShotOperation operation,
            Callback callback,
            Result result) {
        if (operation.finish()) callback.onResult(result);
    }

    static void completeDownload(
            OneShotOperation operation,
            DownloadCallback callback,
            DownloadResult result) {
        if (operation.finish()) callback.onResult(result);
    }

    static void reportDownloadDispatched(
            OneShotOperation operation,
            DownloadCallback callback,
            DownloadResult result) {
        operation.disarmTimeout();
        if (!operation.reportWhileActive()) return;
        operation.finishSilentlyAfter(DOWNLOAD_DISPATCH_GRACE_MILLIS);
        callback.onResult(result);
    }

    static final class OneShotOperation implements Operation {
        final Handler main;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean resultDelivered = new AtomicBoolean();
        private SpeechRecognizer recognizer;
        private Runnable timeout;

        OneShotOperation(Handler main) {
            this.main = main;
        }

        boolean isActive() {
            return active.get();
        }

        void attach(SpeechRecognizer recognizer) {
            if (!isActive()) {
                safeDestroy(recognizer);
                return;
            }
            this.recognizer = recognizer;
        }

        void armTimeout(Runnable action) {
            disarmTimeout();
            timeout = action;
            main.postDelayed(action, SUPPORT_TIMEOUT_MILLIS);
        }

        void disarmTimeout() {
            if (timeout == null) return;
            main.removeCallbacks(timeout);
            timeout = null;
        }

        boolean finish() {
            if (!resultDelivered.compareAndSet(false, true)) return false;
            if (!active.compareAndSet(true, false)) return false;
            cleanup();
            return true;
        }

        boolean reportWhileActive() {
            return active.get()
                    && resultDelivered.compareAndSet(false, true)
                    && active.get();
        }

        void finishSilentlyAfter(long delayMillis) {
            if (!isActive()) return;
            Runnable close = this::finishSilently;
            timeout = close;
            main.postDelayed(close, Math.max(0L, delayMillis));
        }

        private void finishSilently() {
            if (!active.compareAndSet(true, false)) return;
            cleanup();
        }

        @Override
        public void cancel() {
            if (!active.compareAndSet(true, false)) return;
            main.post(this::cleanup);
        }

        private void cleanup() {
            disarmTimeout();
            SpeechRecognizer doomed = recognizer;
            recognizer = null;
            safeDestroy(doomed);
        }
    }

    private static void safeDestroy(SpeechRecognizer recognizer) {
        if (recognizer == null) return;
        try {
            recognizer.destroy();
        } catch (RuntimeException ignored) {
            // The operation is already terminal and no reference to the vendor object is retained.
        }
    }
}
