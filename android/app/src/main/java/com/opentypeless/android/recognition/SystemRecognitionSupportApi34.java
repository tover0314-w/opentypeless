package com.opentypeless.android.recognition;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.speech.ModelDownloadListener;
import android.speech.SpeechRecognizer;

/** Isolates API 34 model-download listener classes from older Android releases. */
@SuppressLint("NewApi")
final class SystemRecognitionSupportApi34 {
    private SystemRecognitionSupportApi34() {}

    static void download(
            SystemRecognitionSupport.OneShotOperation operation,
            SpeechRecognizer recognizer,
            Intent intent,
            SystemRecognitionSupport.DownloadCallback callback) {
        recognizer.triggerModelDownload(
                intent,
                command -> operation.main.post(command),
                new ModelDownloadListener() {
                    @Override
                    public void onProgress(int percent) {
                        if (operation.isActive()) {
                            callback.onProgress(Math.max(0, Math.min(percent, 100)));
                        }
                    }

                    @Override
                    public void onSuccess() {
                        SystemRecognitionSupport.completeDownload(
                                operation,
                                callback,
                                new SystemRecognitionSupport.DownloadResult(
                                        SystemRecognitionSupport.DownloadStatus.COMPLETED,
                                        "The selected on-device language model is installed",
                                        0));
                    }

                    @Override
                    public void onScheduled() {
                        SystemRecognitionSupport.completeDownload(
                                operation,
                                callback,
                                new SystemRecognitionSupport.DownloadResult(
                                        SystemRecognitionSupport.DownloadStatus.SCHEDULED,
                                        "Android scheduled the language model download",
                                        0));
                    }

                    @Override
                    public void onError(int error) {
                        if (RecognitionModelDownloadPolicy.shouldFallbackWithoutEvents(error)) {
                            SystemRecognitionSupportApi33.dispatchUnobservedDownload(
                                    operation,
                                    recognizer,
                                    intent,
                                    callback);
                            return;
                        }
                        SystemRecognitionSupport.completeDownload(
                                operation,
                                callback,
                                new SystemRecognitionSupport.DownloadResult(
                                        SystemRecognitionSupport.DownloadStatus.FAILED,
                                        "Android could not download the speech model (" + error + ")",
                                        error));
                    }
                });
    }
}
