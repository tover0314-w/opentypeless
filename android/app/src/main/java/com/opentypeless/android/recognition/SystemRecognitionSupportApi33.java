package com.opentypeless.android.recognition;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.SpeechRecognizer;

import com.opentypeless.android.config.RecognitionRoute;
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
            SystemRecognitionSupport.Callback callback) {
        SpeechRecognizer recognizer;
        try {
            recognizer = createRecognizer(context, settings.recognitionBackend());
        } catch (RuntimeException error) {
            SystemRecognitionSupport.complete(
                    operation,
                    callback,
                    error(RecognitionRoute.FailureClass.INTERNAL_ERROR));
            return;
        }
        operation.attach(recognizer);
        if (!operation.isActive()) return;
        Intent intent = SystemRecognitionIntentFactory.createCapabilityRequest(settings);
        Executor mainExecutor = operation::post;
        operation.armTimeout(() -> SystemRecognitionSupport.complete(
                operation,
                callback,
                new SystemRecognitionSupport.Result(
                        SystemRecognitionSupport.Status.ERROR,
                        settings.language(),
                        false,
                        RecognitionRoute.FailureClass.NETWORK_TIMEOUT)));
        try {
            recognizer.checkRecognitionSupport(
                    intent,
                    mainExecutor,
                    new RecognitionSupportCallback() {
                        @Override
                        public void onSupportResult(RecognitionSupport support) {
                            SystemRecognitionSupport.Result result;
                            try {
                                result = evaluate(settings, support);
                            } catch (RuntimeException ignored) {
                                result = error(RecognitionRoute.FailureClass.INTERNAL_ERROR);
                            }
                            SystemRecognitionSupport.complete(
                                    operation,
                                    callback,
                                    result);
                        }

                        @Override
                        public void onError(int error) {
                            SystemRecognitionSupport.complete(
                                    operation,
                                    callback,
                                    new SystemRecognitionSupport.Result(
                                            SystemRecognitionSupport.Status.ERROR,
                                            settings.language(),
                                            false,
                                            RecognitionFailureMapper.fromAndroidSystem(error, "")));
                        }
                    });
        } catch (RuntimeException error) {
            SystemRecognitionSupport.complete(
                    operation,
                    callback,
                    error(RecognitionRoute.FailureClass.INTERNAL_ERROR));
        }
    }

    static void download(
            SystemRecognitionSupport.OneShotOperation operation,
            Context context,
            AppSettings settings,
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
                            RecognitionRoute.FailureClass.INTERNAL_ERROR));
            return;
        }
        operation.attach(recognizer);
        if (!operation.isActive()) return;
        Intent intent = SystemRecognitionIntentFactory.createCapabilityRequest(settings);
        Executor mainExecutor = operation::post;
        operation.armTimeout(() -> SystemRecognitionSupport.completeDownload(
                operation,
                callback,
                new SystemRecognitionSupport.DownloadResult(
                        SystemRecognitionSupport.DownloadStatus.FAILED,
                        RecognitionRoute.FailureClass.NETWORK_TIMEOUT)));
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
                            RecognitionRoute.FailureClass.INTERNAL_ERROR));
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
                            null));
        } catch (RuntimeException error) {
            SystemRecognitionSupport.completeDownload(
                    operation,
                    callback,
                    new SystemRecognitionSupport.DownloadResult(
                            SystemRecognitionSupport.DownloadStatus.FAILED,
                            RecognitionRoute.FailureClass.INTERNAL_ERROR));
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
                    false,
                    null);
            case DOWNLOAD_PENDING -> new SystemRecognitionSupport.Result(
                    SystemRecognitionSupport.Status.DOWNLOAD_PENDING,
                    evaluation.language(),
                    false,
                    null);
            case DOWNLOAD_AVAILABLE -> new SystemRecognitionSupport.Result(
                    SystemRecognitionSupport.Status.DOWNLOAD_AVAILABLE,
                    evaluation.language(),
                    onDevice,
                    null);
            case ONLINE_ONLY -> new SystemRecognitionSupport.Result(
                    SystemRecognitionSupport.Status.ONLINE_ONLY,
                    evaluation.language(),
                    false,
                    RecognitionRoute.FailureClass.MODEL_MISSING);
            case UNSUPPORTED -> new SystemRecognitionSupport.Result(
                    SystemRecognitionSupport.Status.UNSUPPORTED,
                    evaluation.language(),
                    false,
                    RecognitionRoute.FailureClass.UNSUPPORTED_LANGUAGE);
            case LANGUAGE_UNSPECIFIED -> new SystemRecognitionSupport.Result(
                    SystemRecognitionSupport.Status.LANGUAGE_UNSPECIFIED,
                    "",
                    false,
                    null);
            case INVALID_RESPONSE -> error(RecognitionRoute.FailureClass.PROTOCOL_ERROR);
        };
    }

    private static SpeechRecognizer createRecognizer(
            Context context,
            RecognitionBackend backend) {
        return backend == RecognitionBackend.SYSTEM_ON_DEVICE
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                : SpeechRecognizer.createSpeechRecognizer(context);
    }

    private static SystemRecognitionSupport.Result error(
            RecognitionRoute.FailureClass failureClass) {
        return new SystemRecognitionSupport.Result(
                SystemRecognitionSupport.Status.ERROR,
                "",
                false,
                failureClass);
    }
}
