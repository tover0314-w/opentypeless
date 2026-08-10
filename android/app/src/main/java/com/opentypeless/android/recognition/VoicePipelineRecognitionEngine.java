package com.opentypeless.android.recognition;

import android.content.Context;
import android.speech.SpeechRecognizer;

import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.data.PersonalizationStore;
import com.opentypeless.android.ime.DictationRequest;
import com.opentypeless.android.ime.DictationResult;
import com.opentypeless.android.ime.TranscriptUpdate;
import com.opentypeless.android.ime.VoicePipeline;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.settings.SettingsRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges Android's standard recognition surfaces to the guarded BYOK dictation pipeline.
 * Settings decryption and SQLite personalization reads run on a private preparation thread.
 */
public final class VoicePipelineRecognitionEngine implements RecognitionSessionController.Engine {
    private record Prepared(DictationRequest request) {}

    private final SettingsRepository settingsRepository;
    private final PersonalizationStore personalizationStore;
    private final VoicePipeline pipeline;
    private final Context baseContext;
    private final Object lifecycleLock = new Object();
    private final RecognitionPreparationState preparationState = new RecognitionPreparationState();
    private final ExecutorService preparationExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "opentypeless-recognition-prepare");
        thread.setDaemon(true);
        return thread;
    });

    private Future<?> preparation;
    private Callback activeCallback;

    public VoicePipelineRecognitionEngine(Context context) {
        Context application = context.getApplicationContext();
        baseContext = application;
        settingsRepository = new SettingsRepository(application);
        personalizationStore = new PersonalizationStore(application);
        pipeline = new VoicePipeline(context);
    }

    void setRecordingContext(Context context) {
        synchronized (lifecycleLock) {
            pipeline.setRecordingContext(context == null ? baseContext : context);
        }
    }

    @Override
    public boolean start(RecognitionRequest request, Callback callback) throws Exception {
        synchronized (lifecycleLock) {
            long token = preparationState.begin();
            if (token == 0L) return false;
            activeCallback = callback;
            FutureTask<Void> task = new FutureTask<>(() -> {
                prepareAndStart(token, request, callback);
                return null;
            });
            preparation = task;
            try {
                preparationExecutor.execute(task);
            } catch (RejectedExecutionException error) {
                preparation = null;
                activeCallback = null;
                preparationState.finish(token);
                throw new RecognitionStartException(new RecognitionFailure(
                        SpeechRecognizer.ERROR_CLIENT,
                        "Speech recognition engine is closed"));
            }
            return true;
        }
    }

    private void prepareAndStart(
            long token,
            RecognitionRequest request,
            Callback callback) {
        Prepared prepared;
        try {
            prepared = prepare(request);
        } catch (RecognitionStartException error) {
            failPreparation(token, callback, error.failure());
            return;
        } catch (Exception error) {
            failPreparation(
                    token,
                    callback,
                    RecognitionErrors.fromPipelineMessage(error.getMessage()));
            return;
        }

        synchronized (lifecycleLock) {
            if (!preparationState.beginPipeline(token)) return;
            preparation = null;
            AtomicBoolean ended = new AtomicBoolean();
            boolean accepted;
            try {
                accepted = pipeline.start(
                        prepared.request(),
                        pipelineListener(token, callback, ended));
            } catch (RuntimeException error) {
                finishWithError(
                        token,
                        callback,
                        RecognitionErrors.fromPipelineMessage(error.getMessage()));
                return;
            }
            if (!accepted) finishWithError(token, callback, RecognitionErrors.busy());
        }
    }

    private Prepared prepare(RecognitionRequest request) throws RecognitionStartException {
        AppSettings stored = settingsRepository.load();
        if (stored.recognitionBackend() != RecognitionBackend.OPENAI_COMPATIBLE
                && stored.recognitionBackend() != RecognitionBackend.DASHSCOPE_STREAMING) {
            throw new RecognitionStartException(
                    RecognitionErrors.unsupportedBackend(stored.recognitionBackend()));
        }
        if (!stored.isReady()) {
            throw new RecognitionStartException(RecognitionErrors.endpointNotConfigured());
        }

        AppSettings settings = withLanguage(stored, request.language());
        String appPackage = request.callingPackage();
        PersonalizationSnapshot snapshot = settings.personalizationEnabled()
                ? personalizationStore.snapshot(appPackage)
                : PersonalizationSnapshot.empty();
        InputContext inputContext = new InputContext(
                appPackage,
                FieldKind.GENERAL,
                "",
                "",
                false);
        return new Prepared(new DictationRequest(
                settings,
                ProcessingMode.VERBATIM,
                inputContext,
                snapshot));
    }

    private VoicePipeline.Listener pipelineListener(
            long token,
            Callback callback,
            AtomicBoolean ended) {
        return new VoicePipeline.Listener() {
            @Override
            public void onState(VoicePipeline.State state, String message) {
                if ((state == VoicePipeline.State.TRANSCRIBING
                        || state == VoicePipeline.State.POLISHING)
                        && ended.compareAndSet(false, true)) {
                    deliverActive(token, callback::onEndOfSpeech);
                }
            }

            @Override
            public void onReadyForSpeech() {
                deliverActive(token, callback::onReady);
            }

            @Override
            public void onBeginningOfSpeech() {
                deliverActive(token, callback::onBeginningOfSpeech);
            }

            @Override
            public void onTranscript(TranscriptUpdate update) {
                deliverActive(token, () -> callback.onPartial(update.text()));
            }

            @Override
            public void onResult(DictationResult result) {
                synchronized (lifecycleLock) {
                    if (!preparationState.finish(token)) return;
                    preparation = null;
                    activeCallback = null;
                    pipeline.setRecordingContext(baseContext);
                    if (ended.compareAndSet(false, true)) callback.onEndOfSpeech();
                    callback.onFinal(result.finalText());
                }
            }

            @Override
            public void onError(String message) {
                finishWithError(
                        token,
                        callback,
                        RecognitionErrors.fromPipelineMessage(message));
            }
        };
    }

    private void deliverActive(long token, Runnable delivery) {
        synchronized (lifecycleLock) {
            if (preparationState.isCurrent(token)) delivery.run();
        }
    }

    private void failPreparation(
            long token,
            Callback callback,
            RecognitionFailure failure) {
        synchronized (lifecycleLock) {
            if (!preparationState.finish(token)) return;
            preparation = null;
            activeCallback = null;
            pipeline.setRecordingContext(baseContext);
            callback.onError(failure);
        }
    }

    private void finishWithError(
            long token,
            Callback callback,
            RecognitionFailure failure) {
        synchronized (lifecycleLock) {
            if (!preparationState.finish(token)) return;
            preparation = null;
            activeCallback = null;
            pipeline.cancel();
            pipeline.setRecordingContext(baseContext);
            callback.onError(failure);
        }
    }

    @Override
    public void stop() {
        Future<?> toCancel = null;
        Callback stoppedBeforeStart = null;
        synchronized (lifecycleLock) {
            RecognitionPreparationState.StopAction action = preparationState.stop();
            if (action == RecognitionPreparationState.StopAction.FAIL_PREPARATION) {
                toCancel = preparation;
                preparation = null;
                stoppedBeforeStart = activeCallback;
                activeCallback = null;
                pipeline.setRecordingContext(baseContext);
            } else if (action == RecognitionPreparationState.StopAction.STOP_PIPELINE) {
                pipeline.stopRecording();
            }
        }
        if (toCancel != null) toCancel.cancel(true);
        if (stoppedBeforeStart != null) {
            stoppedBeforeStart.onError(new RecognitionFailure(
                    SpeechRecognizer.ERROR_NO_MATCH,
                    "Speech recognition stopped before recording began"));
        }
    }

    @Override
    public void cancel() {
        Future<?> toCancel;
        synchronized (lifecycleLock) {
            preparationState.cancel();
            toCancel = preparation;
            preparation = null;
            activeCallback = null;
            pipeline.cancel();
            pipeline.setRecordingContext(baseContext);
        }
        if (toCancel != null) toCancel.cancel(true);
    }

    @Override
    public void shutdown() {
        Future<?> toCancel;
        synchronized (lifecycleLock) {
            if (!preparationState.shutdown()) return;
            toCancel = preparation;
            preparation = null;
            activeCallback = null;
            pipeline.cancel();
            pipeline.setRecordingContext(baseContext);
        }
        if (toCancel != null) toCancel.cancel(true);
        try {
            preparationExecutor.execute(personalizationStore::close);
        } catch (RejectedExecutionException ignored) {
            // The executor can reject only after a repeated or externally forced shutdown.
        }
        preparationExecutor.shutdown();
        pipeline.shutdown();
    }

    private static AppSettings withLanguage(AppSettings settings, String requestedLanguage) {
        String language = requestedLanguage == null || requestedLanguage.isBlank()
                ? settings.language()
                : requestedLanguage.trim();
        return new AppSettings(
                settings.recognitionBackend(),
                settings.sttBaseUrl(),
                settings.sttApiKey(),
                settings.sttModel(),
                settings.streamingBaseUrl(),
                settings.streamingApiKey(),
                settings.streamingModel(),
                settings.streamingVocabularyId(),
                language,
                settings.defaultMode(),
                settings.polishEnabled(),
                settings.llmBaseUrl(),
                settings.llmApiKey(),
                settings.llmModel(),
                settings.targetLanguage(),
                settings.customInstructions(),
                settings.personalizationEnabled(),
                settings.historyEnabled(),
                settings.sendContext(),
                settings.maxRecordingSeconds());
    }
}
