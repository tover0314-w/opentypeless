package com.opentypeless.android.recognition;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.SpeechRecognizer;

import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.RecognitionBackend;

import java.util.concurrent.Executor;

/** Isolates API 33 speech classes so loading the wrapper remains safe on API 26-32. */
@SuppressLint("NewApi")
final class SystemRecognitionSupportApi33 {
    private SystemRecognitionSupportApi33() {}

    static void check(
            SystemRecognitionSupport.OneShotOperation operation,
            Context context,
            AppSettings settings,
            PersonalizationSnapshot snapshot,
            SystemRecognitionSupport.Callback callback) {
        SpeechRecognizer recognizer;
        try {
            recognizer = createRecognizer(context, settings.recognitionBackend());
        } catch (RuntimeException error) {
            SystemRecognitionSupport.complete(
                    operation,
                    callback,
                    error(SystemRecognitionSupport.message(
                            error,
                            "Unable to create speech recognizer")));
            return;
        }
        operation.attach(recognizer);
        if (!operation.isActive()) return;
        Intent intent = SystemRecognitionIntentFactory.create(settings, snapshot);
        Executor mainExecutor = command -> operation.main.post(command);
        operation.armTimeout(() -> SystemRecognitionSupport.complete(
                operation,
                callback,
                new SystemRecognitionSupport.Result(
                        SystemRecognitionSupport.Status.ERROR,
                        settings.language(),
                        "Android speech service did not return language support in time",
                        false,
                        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)));
        try {
            recognizer.checkRecognitionSupport(
                    intent,
                    mainExecutor,
                    new RecognitionSupportCallback() {
                        @Override
                        public void onSupportResult(RecognitionSupport support) {
                            SystemRecognitionSupport.complete(
                                    operation,
                                    callback,
                                    evaluate(settings, support));
                        }

                        @Override
                        public void onError(int error) {
                            SystemRecognitionSupport.complete(
                                    operation,
                                    callback,
                                    new SystemRecognitionSupport.Result(
                                            SystemRecognitionSupport.Status.ERROR,
                                            settings.language(),
                                            "Android speech service could not check language support ("
                                                    + error + ")",
                                            false,
                                            error));
                        }
                    });
        } catch (RuntimeException error) {
            SystemRecognitionSupport.complete(
                    operation,
                    callback,
                    error(SystemRecognitionSupport.message(
                            error,
                            "Unable to check language support")));
        }
    }

    static void download(
            SystemRecognitionSupport.OneShotOperation operation,
            Context context,
            AppSettings settings,
            PersonalizationSnapshot snapshot,
            SystemRecognitionSupport.DownloadCallback callback) {
        SpeechRecognizer recognizer;
        try {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
        } catch (RuntimeException error) {
            SystemRecognitionSupport.completeDownload(
                    operation,
                    callback,
                    new SystemRecognitionSupport.DownloadResult(
                            SystemRecognitionSupport.DownloadStatus.FAILED,
                            SystemRecognitionSupport.message(
                                    error,
                                    "Unable to create on-device speech recognizer"),
                            SpeechRecognizer.ERROR_CLIENT));
            return;
        }
        operation.attach(recognizer);
        if (!operation.isActive()) return;
        Intent intent = SystemRecognitionIntentFactory.create(settings, snapshot);
        Executor mainExecutor = command -> operation.main.post(command);
        operation.armTimeout(() -> SystemRecognitionSupport.completeDownload(
                operation,
                callback,
                new SystemRecognitionSupport.DownloadResult(
                        SystemRecognitionSupport.DownloadStatus.FAILED,
                        "Android speech service did not accept the model download in time",
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT)));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                operation.disarmTimeout();
                SystemRecognitionSupportApi34.download(
                        operation,
                        recognizer,
                        intent,
                        callback);
            } else {
                // Android 13 queues triggerModelDownload until its asynchronous recognition
                // session connects. Prime that same recognizer with a support query first so the
                // trigger is dispatched instead of being cleared by an early destroy().
                recognizer.checkRecognitionSupport(
                        intent,
                        mainExecutor,
                        new RecognitionSupportCallback() {
                            @Override
                            public void onSupportResult(RecognitionSupport ignored) {
                                dispatchUnobservedDownload(
                                        operation,
                                        recognizer,
                                        intent,
                                        callback);
                            }

                            @Override
                            public void onError(int ignored) {
                                // ERROR_CANNOT_CHECK_SUPPORT still proves the recognizer session
                                // connected; the API 33 download call itself has no callback.
                                dispatchUnobservedDownload(
                                        operation,
                                        recognizer,
                                        intent,
                                        callback);
                            }
                        });
            }
        } catch (RuntimeException error) {
            SystemRecognitionSupport.completeDownload(
                    operation,
                    callback,
                    new SystemRecognitionSupport.DownloadResult(
                            SystemRecognitionSupport.DownloadStatus.FAILED,
                            SystemRecognitionSupport.message(
                                    error,
                                    "Unable to request speech model download"),
                            SpeechRecognizer.ERROR_CLIENT));
        }
    }

    static void dispatchUnobservedDownload(
            SystemRecognitionSupport.OneShotOperation operation,
            SpeechRecognizer recognizer,
            Intent intent,
            SystemRecognitionSupport.DownloadCallback callback) {
        if (!operation.isActive()) return;
        try {
            recognizer.triggerModelDownload(intent);
            SystemRecognitionSupport.reportDownloadDispatched(
                    operation,
                    callback,
                    new SystemRecognitionSupport.DownloadResult(
                            SystemRecognitionSupport.DownloadStatus.REQUESTED,
                            "The model download request was handed to Android; this speech "
                                    + "service does not report completion here",
                            0));
        } catch (RuntimeException error) {
            SystemRecognitionSupport.completeDownload(
                    operation,
                    callback,
                    new SystemRecognitionSupport.DownloadResult(
                            SystemRecognitionSupport.DownloadStatus.FAILED,
                            SystemRecognitionSupport.message(
                                    error,
                                    "Unable to request speech model download"),
                            SpeechRecognizer.ERROR_CLIENT));
        }
    }

    private static SystemRecognitionSupport.Result evaluate(
            AppSettings settings,
            RecognitionSupport support) {
        RecognitionLanguageSupportEvaluator.Evaluation evaluation =
                RecognitionLanguageSupportEvaluator.evaluate(
                        settings.language(),
                        support.getInstalledOnDeviceLanguages(),
                        support.getPendingOnDeviceLanguages(),
                        support.getSupportedOnDeviceLanguages(),
                        support.getOnlineLanguages());
        boolean onDevice = settings.recognitionBackend() == RecognitionBackend.SYSTEM_ON_DEVICE;
        return switch (evaluation.outcome()) {
            case INSTALLED -> new SystemRecognitionSupport.Result(
                    SystemRecognitionSupport.Status.INSTALLED,
                    evaluation.language(),
                    onDevice
                            ? "The selected language model is installed for on-device recognition"
                            : "An on-device model is installed, but the current system-default "
                                    + "route does not guarantee offline recognition",
                    false,
                    0);
            case DOWNLOAD_PENDING -> new SystemRecognitionSupport.Result(
                    SystemRecognitionSupport.Status.DOWNLOAD_PENDING,
                    evaluation.language(),
                    "The selected on-device language model download is pending",
                    false,
                    0);
            case DOWNLOAD_AVAILABLE -> new SystemRecognitionSupport.Result(
                    SystemRecognitionSupport.Status.DOWNLOAD_AVAILABLE,
                    evaluation.language(),
                    "The selected language supports on-device recognition but its model is not installed",
                    onDevice,
                    0);
            case ONLINE_ONLY -> new SystemRecognitionSupport.Result(
                    SystemRecognitionSupport.Status.ONLINE_ONLY,
                    evaluation.language(),
                    "The selected language is reported only for online recognition; offline use is unavailable",
                    false,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE);
            case UNSUPPORTED -> new SystemRecognitionSupport.Result(
                    SystemRecognitionSupport.Status.UNSUPPORTED,
                    evaluation.language(),
                    "The selected language is not supported by this Android speech service",
                    false,
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED);
            case LANGUAGE_UNSPECIFIED -> new SystemRecognitionSupport.Result(
                    SystemRecognitionSupport.Status.LANGUAGE_UNSPECIFIED,
                    "",
                    "No language is selected, so Android may auto-detect or use the device default; "
                            + "offline availability cannot be verified for a specific language",
                    false,
                    0);
        };
    }

    private static SpeechRecognizer createRecognizer(
            Context context,
            RecognitionBackend backend) {
        return backend == RecognitionBackend.SYSTEM_ON_DEVICE
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                : SpeechRecognizer.createSpeechRecognizer(context);
    }

    private static SystemRecognitionSupport.Result error(String message) {
        return new SystemRecognitionSupport.Result(
                SystemRecognitionSupport.Status.ERROR,
                "",
                message,
                false,
                SpeechRecognizer.ERROR_CLIENT);
    }
}
