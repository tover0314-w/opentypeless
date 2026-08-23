package com.opentypeless.android.recognition;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;

import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.RecognitionBackend;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
            boolean canDownload,
            RecognitionRoute.FailureClass failureClass) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            language = boundedLanguage(language);
            boolean failureStatus = status == Status.ERROR
                    || status == Status.SERVICE_UNAVAILABLE
                    || status == Status.ONLINE_ONLY
                    || status == Status.UNSUPPORTED;
            if (failureStatus != (failureClass != null)) {
                throw new IllegalArgumentException("Failure classification does not match status");
            }
            if (canDownload && status != Status.DOWNLOAD_AVAILABLE) {
                throw new IllegalArgumentException("Only a downloadable model can be downloaded");
            }
        }

        @Override
        public String toString() {
            return "Result{status=" + status
                    + ", language=<redacted>, canDownload=" + canDownload
                    + ", failureClass=" + failureClass + "}";
        }
    }

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

    public record DownloadResult(
            DownloadStatus status,
            RecognitionRoute.FailureClass failureClass) {
        public DownloadResult {
            status = Objects.requireNonNull(status, "status");
            if ((status == DownloadStatus.FAILED) != (failureClass != null)) {
                throw new IllegalArgumentException("Failure classification does not match status");
            }
        }

        @Override
        public String toString() {
            return "DownloadResult{status=" + status
                    + ", failureClass=" + failureClass + "}";
        }
    }

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
            complete(operation, callback, error(RecognitionRoute.FailureClass.INTERNAL_ERROR));
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
                    false,
                    RecognitionRoute.FailureClass.UNAVAILABLE));
            return;
        }
        if (platform == RecognitionSupportPlatformPolicy.Decision.LEGACY_NOT_VERIFIABLE) {
            complete(operation, callback, new Result(
                    Status.LEGACY_NOT_VERIFIABLE,
                    settings.language(),
                    false,
                    null));
            return;
        }
        SystemRecognitionSupportApi33.check(operation, context, settings, callback);
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
                    RecognitionRoute.FailureClass.UNAVAILABLE));
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            completeDownload(operation, callback, new DownloadResult(
                    DownloadStatus.API_UNAVAILABLE,
                    null));
            return;
        }
        if (!serviceAvailable(context, RecognitionBackend.SYSTEM_ON_DEVICE)) {
            completeDownload(operation, callback, new DownloadResult(
                    DownloadStatus.FAILED,
                    RecognitionRoute.FailureClass.UNAVAILABLE));
            return;
        }
        SystemRecognitionSupportApi33.download(
                operation,
                context,
                settings,
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

    private static Result error(RecognitionRoute.FailureClass failureClass) {
        return new Result(Status.ERROR, "", false, failureClass);
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

    static void reportDownloadProgress(
            OneShotOperation operation,
            DownloadCallback callback,
            int percent) {
        operation.reportProgress(callback, percent);
    }

    interface Scheduler {
        void post(Runnable action);
        void postDelayed(Runnable action, long delayMillis);
        void removeCallbacks(Runnable action);
    }

    static final class OneShotOperation implements Operation {
        private final Scheduler main;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean resultDelivered = new AtomicBoolean();
        private final AtomicInteger lastProgress = new AtomicInteger(-1);
        private SpeechRecognizer recognizer;
        private Runnable timeout;

        OneShotOperation(Handler main) {
            this(new Scheduler() {
                @Override
                public void post(Runnable action) {
                    main.post(action);
                }

                @Override
                public void postDelayed(Runnable action, long delayMillis) {
                    main.postDelayed(action, delayMillis);
                }

                @Override
                public void removeCallbacks(Runnable action) {
                    main.removeCallbacks(action);
                }
            });
        }

        OneShotOperation(Scheduler main) {
            this.main = Objects.requireNonNull(main, "main");
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
            if (!isActive()) return;
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

        void post(Runnable action) {
            main.post(action);
        }

        void reportProgress(DownloadCallback callback, int percent) {
            int bounded = Math.max(0, Math.min(percent, 100));
            while (isActive() && !resultDelivered.get()) {
                int previous = lastProgress.get();
                if (bounded <= previous) return;
                if (!lastProgress.compareAndSet(previous, bounded)) continue;
                if (isActive() && !resultDelivered.get()) callback.onProgress(bounded);
                return;
            }
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

    static String boundedLanguage(String value) {
        if (value == null || value.length() > 128 || !wellFormedUtf16(value)) return "";
        String clean = value.trim();
        if (clean.length() > 128
                || clean.codePointCount(0, clean.length()) > 64
                || !wellFormedUtf16(clean)) {
            return "";
        }
        return clean;
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
