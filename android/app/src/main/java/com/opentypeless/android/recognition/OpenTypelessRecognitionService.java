package com.opentypeless.android.recognition;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

import java.util.concurrent.atomic.AtomicReference;

/** Android standard SpeechRecognizer entry backed by the OpenTypeless BYOK pipeline. */
public final class OpenTypelessRecognitionService extends RecognitionService {
    private interface RemoteDelivery {
        void run() throws RemoteException;
    }

    private RecognitionSessionController controller;
    private VoicePipelineRecognitionEngine engine;
    private StandardRecognitionSettings standardSettings;
    private Handler mainHandler;
    private final AtomicReference<Callback> activeCallback = new AtomicReference<>();

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        engine = new VoicePipelineRecognitionEngine(this);
        controller = new RecognitionSessionController(engine);
        standardSettings = new StandardRecognitionSettings(this);
    }

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
                || checkPermission(
                        Manifest.permission.RECORD_AUDIO,
                        -1,
                        listener.getCallingUid()) != PackageManager.PERMISSION_GRANTED) {
            deliver(listener, () -> listener.error(
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS));
            return;
        }
        String callerPackage = callingPackage(listener);
        int callerUid = listener.getCallingUid();
        RecognitionAccessController.Decision access = StandardRecognitionAccess.forService(
                standardSettings.load(),
                callerPackage,
                getPackageManager().getPackagesForUid(callerUid));
        if (access != RecognitionAccessController.Decision.ALLOWED) {
            int error = access == RecognitionAccessController.Decision.RATE_LIMITED
                    ? RecognitionErrors.rateLimitedCode()
                    : SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
            deliver(listener, () -> listener.error(error));
            return;
        }
        if (!activeCallback.compareAndSet(null, listener)) {
            deliver(listener, () -> listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY));
            return;
        }

        try {
            engine.setRecordingContext(recordingContext(listener));
        } catch (RuntimeException error) {
            activeCallback.compareAndSet(listener, null);
            deliver(listener, () -> listener.error(
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS));
            return;
        }
        RecognitionRequest request = AndroidRecognitionContracts.request(
                recognizerIntent,
                callerPackage);
        controller.start(request, observer(listener));
    }

    @Override
    protected void onStopListening(Callback listener) {
        if (activeCallback.get() == listener) controller.stop();
    }

    @Override
    protected void onCancel(Callback listener) {
        if (activeCallback.get() != listener) return;
        controller.cancel();
        activeCallback.compareAndSet(listener, null);
    }

    @Override
    public void onDestroy() {
        if (controller != null) controller.shutdown();
        activeCallback.set(null);
        super.onDestroy();
    }

    private RecognitionSessionController.Observer observer(Callback listener) {
        return new RecognitionSessionController.Observer() {
            @Override
            public void onReady() {
                postActive(listener, () -> listener.readyForSpeech(new Bundle()));
            }

            @Override
            public void onBeginningOfSpeech() {
                postActive(listener, listener::beginningOfSpeech);
            }

            @Override
            public void onEndOfSpeech() {
                postActive(listener, listener::endOfSpeech);
            }

            @Override
            public void onPartial(RecognitionResult result) {
                postActive(listener, () -> listener.partialResults(
                        AndroidRecognitionContracts.results(result)));
            }

            @Override
            public void onFinal(RecognitionResult result) {
                postTerminal(listener, () -> listener.results(
                        AndroidRecognitionContracts.results(result)));
            }

            @Override
            public void onError(RecognitionFailure failure) {
                postTerminal(listener, () -> listener.error(failure.errorCode()));
            }

            @Override
            public void onCancelled() {
                activeCallback.compareAndSet(listener, null);
            }
        };
    }

    private void postActive(Callback listener, RemoteDelivery delivery) {
        mainHandler.post(() -> {
            if (activeCallback.get() != listener) return;
            deliverOrCancel(listener, delivery);
        });
    }

    private void postTerminal(Callback listener, RemoteDelivery delivery) {
        mainHandler.post(() -> {
            if (!activeCallback.compareAndSet(listener, null)) return;
            deliver(listener, delivery);
        });
    }

    private void deliverOrCancel(Callback listener, RemoteDelivery delivery) {
        if (!deliver(listener, delivery) && activeCallback.get() == listener) {
            controller.cancel();
        }
    }

    private static boolean deliver(Callback listener, RemoteDelivery delivery) {
        try {
            delivery.run();
            return true;
        } catch (RemoteException ignored) {
            return false;
        }
    }

    private String callingPackage(Callback listener) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                String packageName = listener.getCallingAttributionSource().getPackageName();
                return packageName == null ? "" : packageName;
            }
            String packageName = getPackageManager().getNameForUid(listener.getCallingUid());
            return packageName == null ? "" : packageName;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private Context recordingContext(Callback listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this;
        return attributedContext(listener);
    }

    @SuppressLint("NewApi") // recordingContext() guards this helper with SDK_INT >= 31.
    private Context attributedContext(Callback listener) {
        return createContext(new ContextParams.Builder()
                .setNextAttributionSource(listener.getCallingAttributionSource())
                .build());
    }
}
