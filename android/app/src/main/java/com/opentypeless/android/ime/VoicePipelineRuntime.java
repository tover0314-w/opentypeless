package com.opentypeless.android.ime;

import com.opentypeless.android.ime.VoicePipeline.AiCandidateDisposition;
import com.opentypeless.android.ime.VoicePipeline.Listener;
import com.opentypeless.android.ime.VoicePipeline.State;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.speech.SpeechRecognizer;

import com.opentypeless.android.audio.AndroidAudioCapture;
import com.opentypeless.android.audio.AudioCapture;
import com.opentypeless.android.audio.RecordedAudio;
import com.opentypeless.android.audio.WavEncoder;
import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.context.InputPolicy;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.diagnostics.RecognitionDiagnostics;
import com.opentypeless.android.diagnostics.RecognitionDiagnosticsStore;
import com.opentypeless.android.diagnostics.RecognitionRoute;
import com.opentypeless.android.diagnostics.VoiceDiagnosticsLog;
import com.opentypeless.android.net.OpenAiCompatibleClient;
import com.opentypeless.android.offline.LocalOfflineRecognitionClient;
import com.opentypeless.android.offline.LocalOfflineRecognizer;
import com.opentypeless.android.offline.LocalOfflineRecognitionService;
import com.opentypeless.android.offline.LocalPunctuationRecognitionClient;
import com.opentypeless.android.offline.LocalPunctuationRecognizer;
import com.opentypeless.android.offline.LocalRealtimeRecognitionClient;
import com.opentypeless.android.offline.OfflineStreamingRecognizer;
import com.opentypeless.android.offline.SafePunctuationRestorer;
import com.opentypeless.android.net.streaming.ParaformerStreamingRecognizer;
import com.opentypeless.android.net.streaming.StreamingRecognitionEngine;
import com.opentypeless.android.personalization.ProcessingResult;
import com.opentypeless.android.personalization.PromptComposer;
import com.opentypeless.android.personalization.VoiceCommandProcessor;
import com.opentypeless.android.recognition.SystemSpeechRecognizer;
import com.opentypeless.android.recognition.ProviderCapabilities;
import com.opentypeless.android.security.VoiceRecoveryJournal;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.speech.journal.JournalAudioChunk;
import com.opentypeless.android.speech.journal.JournalRecovery;
import com.opentypeless.android.speech.journal.JournalSegmentRecovery;
import com.opentypeless.android.speech.journal.JournalToken;
import com.opentypeless.android.speech.journal.JournalWriteResult;
import com.opentypeless.android.speech.journal.VoiceDraftJournal;
import com.opentypeless.android.speech.runtime.SpeechCoreV2Config;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.transform.IntegrityResult;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

final class VoicePipelineRuntime {
    private static final int MAX_TRANSCRIPT_CODE_POINTS = 20_000;

    private static final class ActiveRun {
        final long id;
        final DictationRequest request;
        final ProcessingMode mode;
        volatile AudioCapture.Session captureSession;
        volatile RecognitionBackend actualBackend;
        volatile boolean systemFallbackAttempted;
        volatile boolean stopRequested;
        volatile String latestTranscript = "";
        volatile String completedSystemTranscript = "";
        final AtomicBoolean visiblePartialClaimed = new AtomicBoolean();
        final RecognitionDiagnostics diagnostics;
        final Listener listener;
        final long startedAt;
        final AtomicLong transcriptSequence = new AtomicLong();
        final String recoveryId;
        volatile boolean cancelled;
        volatile boolean discardRecoveryRequested;
        volatile boolean recoveryJournalWritten;
        volatile String v2RecoveryId = "";
        volatile LocalSpeechCoreV2Session v2Session;
        volatile Future<?> task;

        ActiveRun(
                long id,
                DictationRequest request,
                ProcessingMode mode,
                AudioCapture.Session captureSession,
                Listener listener,
                String recoveryId) {
            this.id = id;
            this.request = request;
            this.mode = mode;
            this.captureSession = captureSession;
            this.actualBackend = request.settings().recognitionBackend();
            this.diagnostics = RecognitionDiagnostics.start(
                    this.actualBackend,
                    request.settings().language(),
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime());
            this.listener = listener;
            this.startedAt = System.currentTimeMillis();
            this.recoveryId = recoveryId == null || recoveryId.isBlank()
                    ? UUID.randomUUID().toString().replace("-", "")
                    : recoveryId;
        }

        ActiveRun(
                long id,
                DictationRequest request,
                ProcessingMode mode,
                AudioCapture.Session captureSession,
                Listener listener) {
            this(id, request, mode, captureSession, listener, "");
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService localQualityExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService localPunctuationExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService modelWarmExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean modelWarmScheduled = new AtomicBoolean();
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<ActiveRun> active = new AtomicReference<>();
    private final AtomicBoolean v2RecoveryPending = new AtomicBoolean();
    private final AtomicBoolean v2RecoveryScanComplete = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private final AudioCapture audioCapture = new AndroidAudioCapture();
    private final OpenAiCompatibleClient client = new OpenAiCompatibleClient();
    private final TextProcessingPipeline textProcessingPipeline;
    private final LocalOfflineRecognitionClient localOfflineClient;
    private final LocalPunctuationRecognitionClient localPunctuationClient;
    private final LocalRealtimeRecognitionClient localRealtimeClient;
    private final ParaformerStreamingRecognizer streamingRecognizer =
            new ParaformerStreamingRecognizer();
    private final SystemSpeechRecognizer systemRecognizer;
    private final Context applicationContext;
    private final RecognitionDiagnosticsStore diagnosticsStore;
    private final VoiceRecoveryJournal recoveryJournal;
    private final VoiceDraftJournal speechCoreJournal;

    VoicePipelineRuntime(Context context) {
        applicationContext = context.getApplicationContext();
        textProcessingPipeline = new StagedTextProcessingPipeline(
                new DeterministicPersonalizationStage(),
                text -> Optional.ofNullable(VoiceCommandProcessor.exactReplacement(text)),
                new OpenAiOptionalLlmStage(client),
                new TranscriptIntegrityGuardStage());
        audioCapture.setAttributionContext(context);
        systemRecognizer = new SystemSpeechRecognizer(context);
        localOfflineClient = new LocalOfflineRecognitionClient(context);
        localPunctuationClient = new LocalPunctuationRecognitionClient(context);
        localRealtimeClient = new LocalRealtimeRecognitionClient(context);
        diagnosticsStore = new RecognitionDiagnosticsStore(context);
        recoveryJournal = new VoiceRecoveryJournal(context);
        speechCoreJournal = new VoiceDraftJournal(context);
        localQualityExecutor.submit(() -> {
            try {
                v2RecoveryPending.set(!speechCoreJournal.listRecoverable().isEmpty());
            } catch (RuntimeException ignored) {
                // Fail closed: an unavailable recovery store must not be overwritten by a session.
                v2RecoveryPending.set(true);
            } finally {
                v2RecoveryScanComplete.set(true);
            }
        });
    }

    /** Must be called while idle, before starting an externally attributed recording. */
    void setRecordingContext(Context context) {
        if (active.get() != null) {
            throw new IllegalStateException("Cannot change microphone attribution during recognition");
        }
        audioCapture.setAttributionContext(context);
    }

    boolean start(DictationRequest request, Listener listener) {
        ProcessingMode mode = InputPolicy.resolve(request.requestedMode(), request.inputContext());
        RecognitionBackend backend = request.settings().recognitionBackend();
        AudioCapture.Session captureSession = backend == RecognitionBackend.OPENAI_COMPATIBLE
                || backend == RecognitionBackend.LOCAL_OFFLINE
                || backend == RecognitionBackend.DASHSCOPE_STREAMING
                ? audioCapture.createSession(request.captureMode().userControlledEndpointing())
                : null;
        ActiveRun run;
        boolean dispatched;
        synchronized (lifecycleLock) {
            if (active.get() != null) return false;
            if ((backend == RecognitionBackend.OPENAI_COMPATIBLE
                    || backend == RecognitionBackend.LOCAL_OFFLINE)
                    && (recoveryJournal.hasPending()
                            || !v2RecoveryScanComplete.get()
                            || v2RecoveryPending.get())) {
                return false;
            }
            run = new ActiveRun(
                    generation.incrementAndGet(), request, mode, captureSession, listener);
            active.set(run);
            state.set(State.RECORDING);
            try {
                if (backend == RecognitionBackend.OPENAI_COMPATIBLE
                        || backend == RecognitionBackend.LOCAL_OFFLINE) {
                    // V2 is the product route, not an opportunistic preview. A missing streaming
                    // model must fail visibly so an upgraded install cannot silently fall back to
                    // the old sequential implementation. V1 remains reachable only through the
                    // explicit emergency rollback preference.
                    boolean useSpeechCoreV2 = shouldUseSpeechCoreV2(
                            backend, SpeechCoreV2Config.enabled(applicationContext));
                    run.task = executor.submit(useSpeechCoreV2
                            ? () -> executeLocalSpeechCoreV2(run)
                            : () -> executeCaptured(run));
                } else if (backend == RecognitionBackend.DASHSCOPE_STREAMING) {
                    run.task = executor.submit(() -> executeStreaming(run));
                } else {
                    startSystem(run);
                }
                dispatched = true;
            } catch (RejectedExecutionException error) {
                active.compareAndSet(run, null);
                state.set(State.IDLE);
                dispatched = false;
            }
        }
        if (!dispatched) return false;
        persistDiagnostics(run);
        listener.onRoute(run.diagnostics.snapshot().route());
        listener.onState(State.RECORDING, routeLabel(run) + " · listening…");
        return true;
    }

    static boolean shouldUseSpeechCoreV2(
            RecognitionBackend backend, boolean speechCoreV2Enabled) {
        return backend == RecognitionBackend.LOCAL_OFFLINE && speechCoreV2Enabled;
    }

    /** Warms only the streaming first pass; SenseVoice remains cold and isolated until needed. */
    void prewarmLocalOffline() {
        if (!SpeechCoreV2Config.enabled(applicationContext)
                || !OfflineStreamingRecognizer.isInstalled(applicationContext)
                || !modelWarmScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            modelWarmExecutor.submit(() -> {
                try {
                    if (active.get() == null) {
                        localRealtimeClient.prewarm();
                    }
                } catch (RuntimeException ignored) {
                    // Dictation can still load on demand; prewarm is an optimization, not a gate.
                } finally {
                    modelWarmScheduled.set(false);
                }
            });
        } catch (RejectedExecutionException ignored) {
            modelWarmScheduled.set(false);
        }
    }

    private void executeLocalSpeechCoreV2(ActiveRun run) {
        LocalSpeechCoreV2Session session = new LocalSpeechCoreV2Session(
                applicationContext,
                run.request,
                run.id,
                audioCapture,
                localRealtimeClient,
                localOfflineClient,
                localPunctuationClient,
                localQualityExecutor,
                localPunctuationExecutor,
                speechCoreJournal,
                new LocalSpeechCoreV2Session.Observer() {
                    @Override
                    public boolean isCurrent() {
                        return VoicePipelineRuntime.this.isCurrent(run);
                    }

                    @Override
                    public void onReadyForSpeech() {
                        notifyReadyForSpeech(run);
                    }

                    @Override
                    public void onBeginningOfSpeech() {
                        if (isCurrent()) run.listener.onBeginningOfSpeech();
                    }

                    @Override
                    public void onDocument(
                            com.opentypeless.android.speech.delivery.ProjectionDocument document,
                            String renderedText,
                            boolean terminalPreview) {
                        if (!isCurrent() || renderedText == null || renderedText.isBlank()) return;
                        TranscriptUpdate update = new TranscriptUpdate(
                                run.transcriptSequence.incrementAndGet(),
                                document.sealedPrefix(),
                                document.composingTail(),
                                false,
                                TranscriptUpdate.Source.SPEECH_CORE_V2);
                        publishTranscript(run, update);
                        if (terminalPreview
                                && run.diagnostics.markRawFinal(SystemClock.elapsedRealtime())) {
                            persistDiagnostics(run);
                        }
                    }

                    @Override
                    public void onStatus(String message) {
                        if (isCurrent()) run.listener.onState(state.get(), message);
                    }

                    @Override
                    public void onJournalCreated(JournalToken token) {
                        if (!isCurrent()) return;
                        run.v2RecoveryId = LocalSpeechCoreV2Session.recoveryId(token);
                        v2RecoveryPending.set(true);
                    }
                });
        run.v2Session = session;
        session.setCaptureSession(run.captureSession);
        try {
            LocalSpeechCoreV2Session.Result result = session.execute();
            if (!isCurrent(run)) return;
            run.v2RecoveryId = result.recoveryId();
            if (!transitionStateIfCurrent(
                    run,
                    State.TRANSCRIBING,
                    "Speech Core v2 · finalizing the last segment…")) {
                return;
            }
            finishTranscription(
                    run,
                    result.rawText(),
                    result.renderedText(),
                    result.durationMs(),
                    result.reachedLimit(),
                    result.autoStopped(),
                    false);
        } catch (CancellationException ignored) {
            finishCancelled(run);
        } catch (Exception error) {
            if (!recoverVisiblePartial(run, true)) finishError(run, error);
        } finally {
            run.v2Session = null;
            session.close();
        }
    }

    private void executeCaptured(ActiveRun run) {
        AtomicReference<LocalRealtimeRecognitionClient.Session> previewRef =
                new AtomicReference<>();
        try {
            RecognitionBackend backend = run.actualBackend;
            if (backend == RecognitionBackend.LOCAL_OFFLINE
                    && OfflineStreamingRecognizer.isInstalled(applicationContext)) {
                try {
                    previewRef.set(localRealtimeClient.start(text -> publishTranscript(
                            run,
                            TranscriptUpdate.unstable(
                                    run.transcriptSequence.incrementAndGet(),
                                    text,
                                    TranscriptUpdate.Source.LOCAL_OFFLINE))));
                } catch (CancellationException error) {
                    throw error;
                } catch (RuntimeException error) {
                    if (!isCurrent(run)) throw new CancellationException(
                            "Offline live preview preparation was cancelled");
                    run.listener.onState(
                            State.RECORDING,
                            "Live preview unavailable · recording for the quality final…");
                }
            }
            RecordedAudio audio = audioCapture.record(
                    run.captureSession,
                    run.request.settings().boundedMaxRecordingSeconds(),
                    new AudioCapture.CaptureListener() {
                        @Override
                        public void onReady() {
                            notifyReadyForSpeech(run);
                        }

                        @Override
                        public void onBeginningOfSpeech() {
                            if (isCurrent(run)) run.listener.onBeginningOfSpeech();
                        }

                        @Override
                        public void onAudio(byte[] pcm16, int length) {
                            LocalRealtimeRecognitionClient.Session preview = previewRef.get();
                            if (preview == null) return;
                            try {
                                preview.accept(pcm16, length);
                            } catch (RuntimeException error) {
                                if (previewRef.compareAndSet(preview, null)) preview.cancel();
                            }
                        }
                    });
            // The captured waveform is the only complete representation available to batch and
            // local engines. Protect it before any network/model work so process death cannot
            // silently erase an utterance that the user already finished.
            try {
                run.recoveryJournalWritten = recoveryJournal.saveAudioIfAccepted(
                        run.recoveryId,
                        backend.name(),
                        run.request.settings().language(),
                        backend == RecognitionBackend.OPENAI_COMPATIBLE
                                ? run.request.settings().sttBaseUrl().trim()
                                : "",
                        backend == RecognitionBackend.OPENAI_COMPATIBLE
                                ? run.request.settings().sttModel().trim()
                                : "sensevoice-small-int8",
                        run.startedAt,
                        audio.durationMs(),
                        audio.reachedLimit(),
                        audio.autoStopped(),
                        audio.wav(),
                        () -> !run.discardRecoveryRequested);
            } catch (RuntimeException ignored) {
                // Disk-full/Keystore failure must not throw away audio that is still available in
                // this process. Continue the current transcription; only crash recovery is lost.
                if (isCurrent(run)) run.listener.onState(
                        State.TRANSCRIBING,
                        "Protected recovery unavailable · continuing current transcription…");
            }
            if (!transitionStateIfCurrent(
                    run,
                    State.TRANSCRIBING,
                    routeLabel(run) + " · transcribing…")) {
                return;
            }
            LocalRealtimeRecognitionClient.Session preview = previewRef.getAndSet(null);
            if (preview != null) {
                try {
                    String firstPass = preview.finish();
                    if (!firstPass.isBlank()) {
                        publishTranscript(run, new TranscriptUpdate(
                                run.transcriptSequence.incrementAndGet(),
                                firstPass,
                                "",
                                false,
                                TranscriptUpdate.Source.LOCAL_OFFLINE));
                    }
                } catch (CancellationException error) {
                    throw error;
                } catch (RuntimeException ignored) {
                    // The complete waveform is already protected. SenseVoice remains decisive.
                }
            }
            String raw;
            if (backend == RecognitionBackend.LOCAL_OFFLINE) {
                boolean formatted = SafePunctuationRestorer.prefersPunctuation(
                        run.request.inputContext().fieldKind());
                LocalOfflineRecognitionClient.Result local = recognizeLocal(
                        audio.wav(),
                        run.request.settings().language(),
                        formatted);
                raw = SafePunctuationRestorer.choose(
                        local.exactText(),
                        local.punctuatedText(),
                        run.request.inputContext().fieldKind());
            } else {
                ProviderCapabilities capabilities =
                        ProviderCapabilities.declaredForBackend(backend);
                String prompt = capabilities.supportsPrompt()
                        ? PromptComposer.asrPrompt(run.request.personalization())
                        : "";
                raw = client.transcribe(
                        audio.wav(),
                        run.request.settings(),
                        prompt,
                        () -> !isCurrent(run));
            }
            publishTerminalPreview(run, raw, backend == RecognitionBackend.LOCAL_OFFLINE
                    ? TranscriptUpdate.Source.LOCAL_OFFLINE
                    : TranscriptUpdate.Source.OPENAI_COMPATIBLE_BATCH);
            finishTranscription(
                    run,
                    raw,
                    audio.durationMs(),
                    audio.reachedLimit(),
                    audio.autoStopped());
        } catch (CancellationException ignored) {
            finishCancelled(run);
        } catch (Exception error) {
            if (!recoverVisiblePartial(run, true)) finishError(run, error);
        } finally {
            LocalRealtimeRecognitionClient.Session preview = previewRef.getAndSet(null);
            if (preview != null) preview.close();
        }
    }

    private void executeStreaming(ActiveRun run) {
        try {
            StreamingRecognitionEngine.Result streaming = streamingRecognizer.recognize(
                    run.request.settings(),
                    audioCapture,
                    run.captureSession,
                    new AudioCapture.CaptureListener() {
                        @Override
                        public void onReady() {
                            notifyReadyForSpeech(run);
                        }

                        @Override
                        public void onBeginningOfSpeech() {
                            if (isCurrent(run)) run.listener.onBeginningOfSpeech();
                        }
                    },
                    new StreamingRecognitionEngine.Listener() {
                        @Override
                        public void onFinishing() {
                            transitionStateIfCurrent(
                                    run,
                                    State.TRANSCRIBING,
                                    "Paraformer realtime · finalizing punctuation…");
                        }

                        @Override
                        public void onTranscript(String stableText, String unstableText) {
                            publishTranscript(run, new TranscriptUpdate(
                                    run.transcriptSequence.incrementAndGet(),
                                    stableText,
                                    unstableText,
                                    false,
                                    TranscriptUpdate.Source.DASHSCOPE_PARAFORMER));
                        }
                    });
            publishTerminalPreview(
                    run,
                    streaming.text(),
                    TranscriptUpdate.Source.DASHSCOPE_PARAFORMER);
            finishTranscription(
                    run,
                    streaming.text(),
                    streaming.durationMs(),
                    streaming.reachedLimit(),
                    streaming.autoStopped());
        } catch (CancellationException ignored) {
            finishCancelled(run);
        } catch (Exception error) {
            if (!recoverVisiblePartial(run, true)) finishError(run, error);
        }
    }

    private void startSystem(ActiveRun run) {
        synchronized (lifecycleLock) {
            if (!isCurrent(run) || run.stopRequested) return;
            long deadlineMillis = run.startedAt
                    + run.request.settings().boundedMaxRecordingSeconds() * 1_000L;
            long remainingMillis = Math.max(1L, deadlineMillis - System.currentTimeMillis());
            systemRecognizer.start(
                    run.request.settings(),
                    run.request.personalization(),
                    new SystemSpeechRecognizer.Callback() {
                    @Override
                    public void onReady() {
                        if (isCurrent(run)) {
                            run.listener.onState(State.RECORDING,
                                    routeLabel(run) + " · listening…");
                            notifyReadyForSpeech(run);
                        }
                    }

                    @Override
                    public void onBeginningOfSpeech() {
                        if (isCurrent(run)) run.listener.onBeginningOfSpeech();
                    }

                    @Override
                    public void onPartial(String text) {
                        publishTranscript(run, TranscriptUpdate.unstable(
                                run.transcriptSequence.incrementAndGet(),
                                joinTranscriptSegments(run.completedSystemTranscript, text),
                                TranscriptUpdate.Source.ANDROID_SYSTEM));
                    }

                    @Override
                    public void onFinal(String text) {
                        String combined;
                        boolean continueListening;
                        boolean reachedLimit;
                        synchronized (lifecycleLock) {
                            if (!isCurrent(run)) return;
                            String uncapped = reconcileSystemFinal(
                                    run.completedSystemTranscript,
                                    text,
                                    run.latestTranscript,
                                    run.request.inputContext().fieldKind());
                            boolean reachedTextLimit = uncapped.codePointCount(
                                    0, uncapped.length()) > MAX_TRANSCRIPT_CODE_POINTS;
                            combined = limitCodePoints(uncapped, MAX_TRANSCRIPT_CODE_POINTS);
                            long elapsedMs = System.currentTimeMillis() - run.startedAt;
                            reachedLimit = elapsedMs >= run.request.settings()
                                    .boundedMaxRecordingSeconds() * 1_000L
                                    || reachedTextLimit;
                            continueListening = run.request.captureMode()
                                    .userControlledEndpointing()
                                    && !run.stopRequested
                                    && !reachedLimit;
                            if (continueListening) {
                                run.completedSystemTranscript = combined;
                                // Queue the next platform session before releasing the decision
                                // lock, so a concurrent user stop either prevents this restart or
                                // is ordered after it and stops the newly queued session.
                                startSystem(run);
                            }
                        }
                        if (continueListening) {
                            publishTranscript(run, new TranscriptUpdate(
                                    run.transcriptSequence.incrementAndGet(),
                                    combined,
                                    "",
                                    false,
                                    TranscriptUpdate.Source.ANDROID_SYSTEM));
                            return;
                        }
                        publishTerminalPreview(
                                run,
                                combined,
                                TranscriptUpdate.Source.ANDROID_SYSTEM);
                        if (!transitionStateIfCurrent(
                                run, State.TRANSCRIBING, "Applying personal vocabulary…")) {
                            return;
                        }
                        try {
                            boolean finalReachedLimit = reachedLimit;
                            run.task = executor.submit(() -> finishTranscription(
                                    run,
                                    combined,
                                    System.currentTimeMillis() - run.startedAt,
                                    finalReachedLimit,
                                    false));
                        } catch (RejectedExecutionException error) {
                            finishError(run, new IllegalStateException(
                                    "Unable to finalize voice input while shutting down"));
                        }
                    }

                    @Override
                    public void onError(int errorCode, String message) {
                        if (!isCurrent(run)) return;
                        if (recoverVisiblePartial(run, false)) return;
                        if (tryLocalFallback(run, errorCode)) return;
                        finishError(run, new IllegalStateException(message));
                    }
                    },
                    remainingMillis);
        }
    }

    private boolean tryLocalFallback(ActiveRun run, int errorCode) {
        boolean permissionGranted = applicationContext.checkSelfPermission(
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean supported = LocalOfflineRecognizer.isSupportedDevice(applicationContext);
        boolean installed = supported && LocalOfflineRecognizer.isInstalled(applicationContext);
        if (!shouldFallbackToLocal(
                errorCode,
                permissionGranted,
                supported,
                installed,
                run.systemFallbackAttempted,
                run.stopRequested)) {
            return false;
        }
        RejectedExecutionException rejected = null;
        synchronized (lifecycleLock) {
            if (!isCurrent(run) || run.stopRequested) return false;
            run.systemFallbackAttempted = true;
            run.actualBackend = RecognitionBackend.LOCAL_OFFLINE;
            run.captureSession = audioCapture.createSession(
                    run.request.captureMode().userControlledEndpointing());
            state.set(State.RECORDING);
            try {
                run.task = executor.submit(() -> executeCaptured(run));
            } catch (RejectedExecutionException error) {
                rejected = error;
            }
        }
        if (rejected != null) {
            finishError(run, new IllegalStateException(
                    "Unable to start the offline fallback while shutting down"));
            return false;
        }
        RecognitionRoute route = new RecognitionRoute(
                run.request.settings().recognitionBackend(),
                RecognitionBackend.LOCAL_OFFLINE,
                RecognitionRoute.FallbackReason.ANDROID_MICROPHONE_BLOCKED);
        run.diagnostics.updateRoute(route);
        persistDiagnostics(run);
        run.listener.onRoute(route);
        run.listener.onState(
                State.RECORDING,
                "Android speech service blocked microphone access · using OpenTypeless offline");
        return true;
    }

    static boolean shouldFallbackToLocal(
            int errorCode,
            boolean permissionGranted,
            boolean supported,
            boolean installed,
            boolean alreadyAttempted,
            boolean stopRequested) {
        return errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                && permissionGranted
                && supported
                && installed
                && !alreadyAttempted
                && !stopRequested;
    }

    private void publishTranscript(ActiveRun run, TranscriptUpdate update) {
        if (!isCurrent(run) || update == null) return;
        String text = update.text().trim();
        if (!text.isEmpty()) {
            run.latestTranscript = text;
            if (run.diagnostics.markFirstPartial(SystemClock.elapsedRealtime())) {
                persistDiagnostics(run);
            }
        }
        run.listener.onTranscript(update);
    }

    /**
     * Shows the provider's authoritative raw final in the editor before local vocabulary/AI
     * processing completes. It deliberately does not count as a real streaming partial in
     * diagnostics, so Voice Lab can still identify engines that supplied no live hypotheses.
     */
    private void publishTerminalPreview(
            ActiveRun run,
            String text,
            TranscriptUpdate.Source source) {
        if (!isCurrent(run) || text == null || text.isBlank()) return;
        if (run.diagnostics.markRawFinal(SystemClock.elapsedRealtime())) persistDiagnostics(run);
        String bounded = limitCodePoints(text.trim(), MAX_TRANSCRIPT_CODE_POINTS);
        run.latestTranscript = bounded;
        run.listener.onTranscript(TranscriptUpdate.finalText(
                run.transcriptSequence.incrementAndGet(),
                bounded,
                source));
    }

    /**
     * Recognition engines can emit usable partials and then fail before producing a final result.
     * Preserve the last visible hypothesis for ordinary dictation regardless of the terminal
     * failure. An explicit cancel has already detached the run and therefore cannot enter here;
     * selected-text editing remains fail-closed because a partial spoken instruction must never
     * replace the selection.
     */
    private boolean recoverVisiblePartial(ActiveRun run, boolean alreadyOnWorker) {
        String partial;
        synchronized (lifecycleLock) {
            if (!isCurrent(run)) return false;
            partial = run.latestTranscript;
            if (!shouldRecoverVisiblePartial(
                    run.request.inputContext().hasSelection(),
                    partial)
                    || !run.visiblePartialClaimed.compareAndSet(false, true)) {
                return false;
            }
            state.set(State.TRANSCRIBING);
        }
        run.listener.onState(
                State.TRANSCRIBING,
                "Final result unavailable · keeping the last live transcript…");
        if (alreadyOnWorker) {
            // Preserve run.task so explicit cancellation still interrupts the current worker.
            // Semantic post-processing is disabled for recovered text below, so completing on
            // this worker cannot block on an LLM request.
            finishTranscription(
                    run,
                    partial,
                    System.currentTimeMillis() - run.startedAt,
                    false,
                    false,
                    true);
        } else {
            RejectedExecutionException rejected = null;
            synchronized (lifecycleLock) {
                if (!isCurrent(run)) return true;
                try {
                    run.task = executor.submit(() -> finishTranscription(
                            run,
                            partial,
                            System.currentTimeMillis() - run.startedAt,
                            false,
                            false,
                            true));
                } catch (RejectedExecutionException error) {
                    rejected = error;
                }
            }
            if (rejected != null) {
                finishError(run, new IllegalStateException(
                        "Unable to preserve the live transcript while shutting down"));
            }
        }
        return true;
    }

    static String joinTranscriptSegments(String completed, String next) {
        String originalLeft = completed == null ? "" : completed;
        String originalRight = next == null ? "" : next;
        boolean explicitBoundarySpace = (!originalLeft.isEmpty()
                && Character.isWhitespace(originalLeft.codePointBefore(originalLeft.length())))
                || (!originalRight.isEmpty()
                && Character.isWhitespace(originalRight.codePointAt(0)));
        String left = originalLeft.trim();
        String right = originalRight.trim();
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        int leftCodePoint = left.codePointBefore(left.length());
        int rightCodePoint = right.codePointAt(0);
        boolean addSpace = Character.isLetterOrDigit(leftCodePoint)
                && Character.isLetterOrDigit(rightCodePoint)
                && leftCodePoint < 128
                && rightCodePoint < 128;
        return left + (explicitBoundarySpace || addSpace ? " " : "") + right;
    }

    /**
     * Some Android providers expose a well-punctuated live hypothesis and then return the same
     * words with nearly all internal punctuation removed in their terminal result. Prefer that
     * visible punctuation only when the normalized lexical content is identical. A changed word,
     * number, or word boundary keeps the provider's authoritative final and receives at most the
     * conservative terminal punctuation supplied by {@link SafePunctuationRestorer}.
     */
    static String reconcileSystemFinal(
            String completed,
            String finalSegment,
            String latestVisible,
            FieldKind fieldKind) {
        String authoritative = joinTranscriptSegments(completed, finalSegment);
        return SafePunctuationRestorer.choose(authoritative, latestVisible, fieldKind);
    }

    static String limitCodePoints(String value, int maximum) {
        String safe = value == null ? "" : value;
        if (maximum <= 0) return "";
        int count = safe.codePointCount(0, safe.length());
        return count <= maximum
                ? safe
                : safe.substring(0, safe.offsetByCodePoints(0, maximum));
    }

    static boolean shouldRecoverVisiblePartial(boolean hasSelection, String latestTranscript) {
        return !hasSelection
                && latestTranscript != null
                && !latestTranscript.isBlank();
    }

    private void finishTranscription(
            ActiveRun run,
            String rawText,
            long durationMs,
            boolean reachedLimit,
            boolean autoStopped) {
        finishTranscription(
                run,
                rawText,
                durationMs,
                reachedLimit,
                autoStopped,
                false,
                null);
    }

    private void finishTranscription(
            ActiveRun run,
            String rawText,
            long durationMs,
            boolean reachedLimit,
            boolean autoStopped,
            boolean recoveredPartial) {
        finishTranscription(
                run,
                rawText,
                durationMs,
                reachedLimit,
                autoStopped,
                recoveredPartial,
                null);
    }

    /** V2 supplies its already segmented/punctuated document while retaining raw ASR for history. */
    private void finishTranscription(
            ActiveRun run,
            String rawText,
            String speechCoreText,
            long durationMs,
            boolean reachedLimit,
            boolean autoStopped,
            boolean recoveredPartial) {
        finishTranscription(
                run,
                rawText,
                durationMs,
                reachedLimit,
                autoStopped,
                recoveredPartial,
                speechCoreText);
    }

    private void finishTranscription(
            ActiveRun run,
            String rawText,
            long durationMs,
            boolean reachedLimit,
            boolean autoStopped,
            boolean recoveredPartial,
            String speechCoreText) {
        if (!isCurrent(run)) return;
        PersonalizationSnapshot snapshot = run.request.settings().personalizationEnabled()
                ? run.request.personalization()
                : PersonalizationSnapshot.empty();
        ProcessingResult personalized;
        try {
            personalized = textProcessingPipeline.deterministic(
                    speechCoreText == null ? rawText : speechCoreText,
                    snapshot,
                    run.request.inputContext().hasSelection()
                            ? TextProcessingPipeline.DeterministicFailurePolicy.PROPAGATE
                            : TextProcessingPipeline.DeterministicFailurePolicy.PRESERVE_INPUT);
        } catch (IllegalArgumentException error) {
            finishError(run, error);
            return;
        }
        String deterministicText = personalized.text();
        String candidateText = deterministicText;
        String finalText = deterministicText;
        StageProvenance.Disposition commandProvenance =
                StageProvenance.Disposition.SKIPPED;
        StageProvenance.Disposition optionalLlmProvenance =
                StageProvenance.Disposition.SKIPPED;
        StageProvenance.Disposition integrityProvenance =
                StageProvenance.Disposition.SKIPPED;
        StageProvenance.Disposition finalizationProvenance =
                StageProvenance.Disposition.PUBLISHED;
        DictationResult.Outcome outcome = reachedLimit
                ? DictationResult.Outcome.INSERTED_RECORDING_LIMIT
                : autoStopped
                ? DictationResult.Outcome.INSERTED_AFTER_SILENCE
                : DictationResult.Outcome.INSERTED;

        String command = recoveredPartial || run.request.inputContext().hasSelection()
                ? null
                : textProcessingPipeline.command(finalText).orElse(null);
        if (command != null) {
            candidateText = command;
            finalText = command;
            commandProvenance = StageProvenance.Disposition.APPLIED;
            outcome = DictationResult.Outcome.VOICE_COMMAND_INSERTED;
        } else if (!recoveredPartial && requiresLlm(run.mode, run.request.inputContext())) {
            if (!llmReady(run.request.settings())) {
                if (run.request.inputContext().hasSelection()) {
                    finishError(run, new IllegalStateException(
                            "Selected-text editing requires a configured AI polish endpoint"));
                    return;
                }
                outcome = DictationResult.Outcome.EXACT_AI_NOT_CONFIGURED;
                finalizationProvenance = StageProvenance.Disposition.FALLBACK;
            } else {
                if (!transitionStateIfCurrent(
                        run,
                        State.POLISHING,
                        "Checking and polishing without changing facts…")) {
                    return;
                }
                try {
                    String candidate = textProcessingPipeline.optionalLlm(
                            new TextProcessingPipeline.LlmRequest(
                                    run.mode,
                                    run.request.inputContext(),
                                    snapshot,
                                    run.request.settings(),
                                    finalText),
                            () -> !isCurrent(run));
                    ProcessingResult protectedCandidate = textProcessingPipeline.deterministic(
                            candidate,
                            snapshot,
                            TextProcessingPipeline.DeterministicFailurePolicy.PROPAGATE);
                    candidateText = protectedCandidate.text();
                    optionalLlmProvenance = StageProvenance.Disposition.APPLIED;
                    String integritySource = run.request.inputContext().hasSelection()
                            ? run.request.inputContext().selectedText()
                            : finalText;
                    IntegrityResult integrity = textProcessingPipeline.integrity(
                            new TextProcessingPipeline.IntegrityRequest(
                                    integritySource,
                                    candidateText,
                                    run.mode,
                                    snapshot));
                    AiCandidateDisposition disposition = aiCandidateDisposition(
                            integrity.safe(),
                            run.request.inputContext().hasSelection());
                    if (disposition == AiCandidateDisposition.ACCEPT) {
                        finalText = candidateText;
                        integrityProvenance = StageProvenance.Disposition.ACCEPTED;
                        outcome = run.request.inputContext().hasSelection()
                                ? DictationResult.Outcome.SELECTION_UPDATED
                                : run.mode == ProcessingMode.TRANSLATE
                                ? DictationResult.Outcome.TRANSLATED
                                : DictationResult.Outcome.SMART_EDITED;
                    } else if (disposition == AiCandidateDisposition.PRESERVE_SELECTION) {
                        finishError(run, new IllegalStateException(
                                "AI edit blocked to protect facts; original selection was preserved"));
                        return;
                    } else {
                        integrityProvenance = StageProvenance.Disposition.REJECTED;
                        finalizationProvenance = StageProvenance.Disposition.FALLBACK;
                        outcome = DictationResult.Outcome.AI_BLOCKED_EXACT;
                    }
                } catch (Exception error) {
                    if (!isCurrent(run)) return;
                    if (run.request.inputContext().hasSelection()) {
                        finishError(run, new IllegalStateException(
                                "Selected-text edit failed; original selection was preserved"));
                        return;
                    }
                    if (optionalLlmProvenance == StageProvenance.Disposition.APPLIED) {
                        integrityProvenance = StageProvenance.Disposition.FAILED;
                    } else {
                        candidateText = deterministicText;
                        optionalLlmProvenance = StageProvenance.Disposition.FAILED;
                    }
                    finalText = deterministicText;
                    finalizationProvenance = StageProvenance.Disposition.FALLBACK;
                    outcome = DictationResult.Outcome.EXACT_AI_FAILED;
                }
            }
        }

        if (!isCurrent(run)) return;
        String durableRecoveryId = run.v2RecoveryId == null || run.v2RecoveryId.isBlank()
                ? run.recoveryJournalWritten ? run.recoveryId : ""
                : run.v2RecoveryId;
        VoiceResult voiceResult = VoiceResult.processed(
                rawText.trim(),
                deterministicText,
                candidateText,
                finalText,
                commandProvenance,
                optionalLlmProvenance,
                integrityProvenance,
                finalizationProvenance);
        DictationResult result = new DictationResult(
                voiceResult,
                outcome,
                run.mode,
                run.actualBackend,
                durationMs,
                reachedLimit,
                recoveredPartial,
                personalized.matchedTermIds(),
                personalized.matchedCorrectionIds(),
                durableRecoveryId);
        if (run.recoveryJournalWritten) {
            try {
                // Replace audio with the much smaller final text before publishing the result.
                // The IME acknowledges/deletes this only after the editor or encrypted draft has
                // durably accepted the text.
                recoveryJournal.complete(
                        run.recoveryId,
                        run.actualBackend.name(),
                        run.request.settings().language(),
                        run.actualBackend == RecognitionBackend.OPENAI_COMPATIBLE
                                ? run.request.settings().sttBaseUrl().trim()
                                : "",
                        run.actualBackend == RecognitionBackend.OPENAI_COMPATIBLE
                                ? run.request.settings().sttModel().trim()
                                : "sensevoice-small-int8",
                        run.startedAt,
                        durationMs,
                        reachedLimit,
                        autoStopped,
                        voiceResult.finalText());
            } catch (RuntimeException ignored) {
                // Keeping the authenticated audio is a safe fallback; recovery will transcribe it
                // again instead of losing the utterance.
            }
        }
        boolean delivered;
        synchronized (lifecycleLock) {
            delivered = active.compareAndSet(run, null);
            if (delivered) state.set(State.IDLE);
        }
        if (delivered) {
            run.diagnostics.succeed(
                    SystemClock.elapsedRealtime(),
                    durationMs,
                    voiceResult.finalText(),
                    recoveredPartial);
            persistDiagnostics(run);
            run.listener.onResult(result);
        }
    }

    void stopRecording() {
        ActiveRun run;
        synchronized (lifecycleLock) {
            run = active.get();
            if (run == null || state.get() != State.RECORDING) return;
            run.stopRequested = true;
        }
        if (run.diagnostics.markStopRequested(SystemClock.elapsedRealtime())) {
            persistDiagnostics(run);
        }
        if (run.captureSession != null) audioCapture.stop(run.captureSession);
        else systemRecognizer.stop();
    }

    void cancel() {
        cancel(false);
    }

    /** Explicit user discard. Unlike lifecycle cancellation this removes the durable checkpoint. */
    void discard() {
        cancel(true);
    }

    private void cancel(boolean discardRecovery) {
        ActiveRun run;
        synchronized (lifecycleLock) {
            run = active.getAndSet(null);
            generation.incrementAndGet();
            if (run != null) {
                run.cancelled = true;
                if (discardRecovery) run.discardRecoveryRequested = true;
            }
            state.set(State.IDLE);
        }
        if (run != null) {
            run.diagnostics.cancel(SystemClock.elapsedRealtime());
            persistDiagnostics(run);
            if (run.captureSession != null) audioCapture.cancel(run.captureSession);
            systemRecognizer.cancel();
            client.cancelActiveRequest();
            localOfflineClient.cancelActive();
            localRealtimeClient.cancelActive();
            LocalSpeechCoreV2Session v2Session = run.v2Session;
            if (v2Session != null) v2Session.cancel(discardRecovery);
            streamingRecognizer.cancelActiveSession();
            Future<?> task = run.task;
            if (task != null) task.cancel(true);
        }
        if (discardRecovery) {
            if (run != null) {
                recoveryJournal.discard(run.recoveryId);
                String speechCoreRecoveryId = run.v2RecoveryId;
                if (speechCoreRecoveryId != null && !speechCoreRecoveryId.isBlank()) {
                    discardSpeechCoreRecoveryAsync(speechCoreRecoveryId);
                }
            }
            else {
                recoveryJournal.discardAny();
                discardAnySpeechCoreRecovery();
            }
        }
    }

    boolean hasRecoverableAudio() {
        return recoveryJournal.hasPending()
                || !v2RecoveryScanComplete.get()
                || v2RecoveryPending.get();
    }

    /** Removes a completed checkpoint only after its result has been safely accepted elsewhere. */
    boolean acknowledgeRecovery(String recoveryId) {
        if (recoveryId == null || recoveryId.isBlank()) return true;
        if (recoveryId.startsWith("v2:")) return acknowledgeSpeechCoreRecovery(recoveryId);
        return recoveryJournal.discard(recoveryId);
    }

    private boolean acknowledgeSpeechCoreRecovery(String recoveryId) {
        JournalToken token = parseSpeechCoreRecoveryId(recoveryId);
        if (token == null) return false;
        try (VoiceDraftJournal.Session session = speechCoreJournal.resume(token)) {
            if (session == null) {
                refreshSpeechCoreRecoveryFlag();
                return true;
            }
            JournalWriteResult result = session.acknowledge();
            refreshSpeechCoreRecoveryFlag();
            return result == JournalWriteResult.WRITTEN
                    || result == JournalWriteResult.IGNORED_DUPLICATE;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private void discardSpeechCoreRecoveryAsync(String recoveryId) {
        try {
            localQualityExecutor.submit(() -> discardSpeechCoreRecovery(recoveryId));
        } catch (RejectedExecutionException ignored) {
            // Keep the recovery bit set. A later explicit discard can retry safely.
            v2RecoveryPending.set(true);
        }
    }

    private boolean discardSpeechCoreRecovery(String recoveryId) {
        JournalToken token = parseSpeechCoreRecoveryId(recoveryId);
        if (token == null) return false;
        try (VoiceDraftJournal.Session session = speechCoreJournal.resume(token)) {
            if (session == null) {
                refreshSpeechCoreRecoveryFlag();
                return true;
            }
            JournalWriteResult result = session.discard();
            refreshSpeechCoreRecoveryFlag();
            return result == JournalWriteResult.WRITTEN
                    || result == JournalWriteResult.IGNORED_DUPLICATE;
        } catch (RuntimeException error) {
            v2RecoveryPending.set(true);
            return false;
        }
    }

    private void discardAnySpeechCoreRecovery() {
        try {
            for (com.opentypeless.android.speech.journal.JournalRecovery recovery
                    : speechCoreJournal.listRecoverable()) {
                try (VoiceDraftJournal.Session session = speechCoreJournal.resume(recovery.token())) {
                    if (session != null) session.discard();
                }
            }
        } catch (RuntimeException ignored) {
            // Keep the pending bit set so another recording cannot overwrite recoverable work.
        }
        refreshSpeechCoreRecoveryFlag();
    }

    private void refreshSpeechCoreRecoveryFlag() {
        try {
            v2RecoveryPending.set(!speechCoreJournal.listRecoverable().isEmpty());
        } catch (RuntimeException error) {
            v2RecoveryPending.set(true);
        } finally {
            v2RecoveryScanComplete.set(true);
        }
    }

    static JournalToken parseSpeechCoreRecoveryId(String recoveryId) {
        if (recoveryId == null || !recoveryId.startsWith("v2:")) return null;
        int separator = recoveryId.indexOf(':', 3);
        if (separator <= 3 || separator >= recoveryId.length() - 1) return null;
        try {
            long generation = Long.parseLong(recoveryId.substring(3, separator));
            return new JournalToken(
                    SessionId.of(recoveryId.substring(separator + 1)), generation);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    /**
     * Replays a protected batch/local checkpoint without opening the microphone. Callers should
     * route the result to a recoverable draft, never directly to an editor captured before death.
     */
    boolean recover(DictationRequest request, Listener listener) {
        VoiceRecoveryJournal.Entry entry = recoveryJournal.read();
        if (entry == null) return recoverSpeechCoreV2(request, listener);
        RecognitionBackend backend;
        try {
            backend = RecognitionBackend.valueOf(entry.backend());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("The protected recording has an unknown route", error);
        }
        if (backend != RecognitionBackend.OPENAI_COMPATIBLE
                && backend != RecognitionBackend.LOCAL_OFFLINE) {
            throw new IllegalStateException("This saved recording route cannot be recovered");
        }
        if (backend == RecognitionBackend.OPENAI_COMPATIBLE
                && (!entry.endpoint().equals(request.settings().sttBaseUrl().trim())
                || !entry.model().equals(request.settings().sttModel().trim()))) {
            throw new IllegalStateException(
                    "The protected recording belongs to a different provider configuration");
        }
        AppSettings recoverySettings = withRecoveryRoute(request.settings(), backend, entry);
        DictationRequest recoveryRequest = new DictationRequest(
                recoverySettings,
                ProcessingMode.VERBATIM,
                request.inputContext(),
                request.personalization(),
                DictationRequest.CaptureMode.SINGLE_UTTERANCE);
        ActiveRun run;
        synchronized (lifecycleLock) {
            if (active.get() != null) return false;
            if (!entry.id().equals(recoveryJournal.pendingId())) return false;
            run = new ActiveRun(
                    generation.incrementAndGet(),
                    recoveryRequest,
                    ProcessingMode.VERBATIM,
                    null,
                    listener,
                    entry.id());
            run.actualBackend = backend;
            run.recoveryJournalWritten = true;
            active.set(run);
            state.set(State.TRANSCRIBING);
            try {
                run.task = executor.submit(() -> executeRecovery(run, entry));
            } catch (RejectedExecutionException error) {
                active.compareAndSet(run, null);
                state.set(State.IDLE);
                return false;
            }
        }
        persistDiagnostics(run);
        listener.onRoute(run.diagnostics.snapshot().route());
        listener.onState(State.TRANSCRIBING, routeLabel(run) + " · recovering protected audio…");
        return true;
    }

    private boolean recoverSpeechCoreV2(DictationRequest request, Listener listener) {
        List<JournalRecovery> recoveries;
        try {
            recoveries = speechCoreJournal.listRecoverable();
        } catch (RuntimeException error) {
            v2RecoveryPending.set(true);
            return false;
        }
        if (recoveries.isEmpty()) {
            v2RecoveryPending.set(false);
            return false;
        }
        JournalRecovery recovery = recoveries.get(0);
        AppSettings localSettings = withLocalOfflineBackend(request.settings());
        DictationRequest recoveryRequest = new DictationRequest(
                localSettings,
                ProcessingMode.VERBATIM,
                request.inputContext(),
                request.personalization(),
                DictationRequest.CaptureMode.SINGLE_UTTERANCE);
        ActiveRun run;
        synchronized (lifecycleLock) {
            if (active.get() != null) return false;
            run = new ActiveRun(
                    generation.incrementAndGet(),
                    recoveryRequest,
                    ProcessingMode.VERBATIM,
                    null,
                    listener,
                    LocalSpeechCoreV2Session.recoveryId(recovery.token()));
            run.actualBackend = RecognitionBackend.LOCAL_OFFLINE;
            run.v2RecoveryId = run.recoveryId;
            active.set(run);
            state.set(State.TRANSCRIBING);
            try {
                run.task = executor.submit(() -> executeSpeechCoreV2Recovery(run, recovery));
            } catch (RejectedExecutionException error) {
                active.compareAndSet(run, null);
                state.set(State.IDLE);
                return false;
            }
        }
        persistDiagnostics(run);
        listener.onRoute(run.diagnostics.snapshot().route());
        listener.onState(State.TRANSCRIBING, "Speech Core v2 · recovering protected draft…");
        return true;
    }

    private void executeSpeechCoreV2Recovery(ActiveRun run, JournalRecovery recovery) {
        try {
            String text = recovery.renderedText().trim();
            if (text.isBlank()) text = transcribeRecoveredSegments(run, recovery);
            if (text.isBlank()) {
                throw new IllegalStateException("The protected Speech Core draft contains no text");
            }
            String bounded = limitCodePoints(text, MAX_TRANSCRIPT_CODE_POINTS);
            long durationMs = recoveredDurationMs(recovery);
            DictationResult result = new DictationResult(
                    VoiceResult.recovered(bounded),
                    DictationResult.Outcome.INSERTED,
                    ProcessingMode.VERBATIM,
                    RecognitionBackend.LOCAL_OFFLINE,
                    durationMs,
                    recovery.terminalReason()
                            == com.opentypeless.android.speech.core.TerminalReason.DURATION_LIMIT,
                    false,
                    List.of(),
                    List.of(),
                    run.v2RecoveryId);
            boolean delivered;
            synchronized (lifecycleLock) {
                delivered = active.compareAndSet(run, null);
                if (delivered) state.set(State.IDLE);
            }
            if (delivered) run.listener.onResult(result);
        } catch (CancellationException ignored) {
            finishCancelled(run);
        } catch (Exception error) {
            finishError(run, error);
        }
    }

    private String transcribeRecoveredSegments(ActiveRun run, JournalRecovery recovery) {
        StringBuilder rendered = new StringBuilder();
        for (JournalSegmentRecovery segment : recovery.segments()) {
            requireCurrentRecovery(run);
            byte[] pcm = concatenatePcm(segment.audioChunks());
            if (pcm.length == 0) continue;
            byte[] wav = WavEncoder.pcm16Mono(pcm, recovery.metadata().sampleRate());
            Arrays.fill(pcm, (byte) 0);
            try {
                LocalOfflineRecognitionClient.Result recognized = recognizeLocal(
                        wav, recovery.metadata().languageTag(), true);
                String segmentText = recognized.punctuatedText();
                if (segmentText.isBlank()) continue;
                if (rendered.length() > 0) rendered.append(segment.joinBefore().delimiter());
                rendered.append(segmentText);
            } finally {
                Arrays.fill(wav, (byte) 0);
            }
        }
        return rendered.toString().trim();
    }

    private void requireCurrentRecovery(ActiveRun run) {
        if (!isCurrent(run) || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Speech Core recovery was cancelled");
        }
    }

    private static byte[] concatenatePcm(List<JournalAudioChunk> chunks) {
        long total = 0L;
        for (JournalAudioChunk chunk : chunks) total += chunk.byteLength();
        if (total == 0L) return new byte[0];
        if (total > LocalOfflineRecognitionService.MAX_WAV_BYTES - 44L) {
            throw new IllegalStateException("Protected segment audio exceeded the recovery limit");
        }
        byte[] joined = new byte[(int) total];
        int offset = 0;
        for (JournalAudioChunk chunk : chunks) {
            byte[] bytes = chunk.pcm16LittleEndian();
            System.arraycopy(bytes, 0, joined, offset, bytes.length);
            offset += bytes.length;
            Arrays.fill(bytes, (byte) 0);
        }
        return joined;
    }

    private static long recoveredDurationMs(JournalRecovery recovery) {
        long samples = 0L;
        for (JournalSegmentRecovery segment : recovery.segments()) {
            for (JournalAudioChunk chunk : segment.audioChunks()) {
                samples += chunk.byteLength() / 2L;
            }
        }
        return samples * 1_000L / Math.max(1, recovery.metadata().sampleRate());
    }

    private void executeRecovery(ActiveRun run, VoiceRecoveryJournal.Entry entry) {
        try {
            if (entry.kind() == VoiceRecoveryJournal.Kind.COMPLETED_TEXT) {
                deliverCompletedRecovery(run, entry);
                return;
            }
            String raw;
            byte[] wav = entry.wav();
            try {
                if (run.actualBackend == RecognitionBackend.LOCAL_OFFLINE) {
                    boolean formatted = SafePunctuationRestorer.prefersPunctuation(
                            run.request.inputContext().fieldKind());
                    LocalOfflineRecognitionClient.Result local = recognizeLocal(
                            wav,
                            run.request.settings().language(),
                            formatted);
                    raw = SafePunctuationRestorer.choose(
                            local.exactText(),
                            local.punctuatedText(),
                            run.request.inputContext().fieldKind());
                } else {
                    ProviderCapabilities capabilities =
                            ProviderCapabilities.declaredForBackend(run.actualBackend);
                    String prompt = capabilities.supportsPrompt()
                            ? PromptComposer.asrPrompt(run.request.personalization())
                            : "";
                    raw = client.transcribe(
                            wav,
                            run.request.settings(),
                            prompt,
                            () -> !isCurrent(run));
                }
            } finally {
                java.util.Arrays.fill(wav, (byte) 0);
            }
            publishTerminalPreview(
                    run,
                    raw,
                    run.actualBackend == RecognitionBackend.LOCAL_OFFLINE
                            ? TranscriptUpdate.Source.LOCAL_OFFLINE
                            : TranscriptUpdate.Source.OPENAI_COMPATIBLE_BATCH);
            finishTranscription(
                    run,
                    raw,
                    entry.durationMs(),
                    entry.reachedLimit(),
                    entry.autoStopped());
        } catch (CancellationException ignored) {
            finishCancelled(run);
        } catch (Exception error) {
            finishError(run, error);
        }
    }

    private void deliverCompletedRecovery(
            ActiveRun run, VoiceRecoveryJournal.Entry entry) {
        String text = entry.completedText();
        DictationResult result = new DictationResult(
                VoiceResult.recovered(text),
                DictationResult.Outcome.INSERTED,
                ProcessingMode.VERBATIM,
                run.actualBackend,
                entry.durationMs(),
                entry.reachedLimit(),
                false,
                java.util.List.of(),
                java.util.List.of(),
                entry.id());
        boolean delivered;
        synchronized (lifecycleLock) {
            delivered = active.compareAndSet(run, null);
            if (delivered) state.set(State.IDLE);
        }
        if (delivered) run.listener.onResult(result);
    }

    State state() {
        return state.get();
    }

    private LocalOfflineRecognitionClient.Result recognizeLocal(
            byte[] wav, String language, boolean formatted) {
        LocalOfflineRecognitionClient.Result recognized = localOfflineClient.recognize(
                wav, language, formatted);
        if (!formatted || !LocalPunctuationRecognizer.isInstalled(applicationContext)) {
            return recognized;
        }
        try {
            String punctuated = localPunctuationClient.punctuate(recognized.exactText());
            return new LocalOfflineRecognitionClient.Result(recognized.exactText(), punctuated);
        } catch (CancellationException error) {
            throw error;
        } catch (RuntimeException ignored) {
            // The ASR result remains authoritative; the field-safe gate can still add a terminal
            // mark without risking any lexical rewrite.
            return recognized;
        } finally {
            // Legacy/recovery requests have no v2 session lease. Do not retain a 72 MiB model
            // worker after this one text transform.
            localPunctuationClient.releaseSessionWorker();
        }
    }

    void shutdown() {
        cancel();
        systemRecognizer.destroy();
        localOfflineClient.close();
        localPunctuationClient.close();
        localRealtimeClient.close();
        streamingRecognizer.shutdown();
        executor.shutdownNow();
        localQualityExecutor.shutdownNow();
        localPunctuationExecutor.shutdownNow();
        modelWarmExecutor.shutdownNow();
    }

    private static AppSettings withRecoveryRoute(
            AppSettings settings,
            RecognitionBackend backend,
            VoiceRecoveryJournal.Entry entry) {
        return new AppSettings(
                backend,
                backend == RecognitionBackend.OPENAI_COMPATIBLE
                        ? entry.endpoint()
                        : settings.sttBaseUrl(),
                settings.sttApiKey(),
                backend == RecognitionBackend.OPENAI_COMPATIBLE
                        ? entry.model()
                        : settings.sttModel(),
                settings.streamingBaseUrl(),
                settings.streamingApiKey(),
                settings.streamingModel(),
                settings.streamingVocabularyId(),
                entry.language(),
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

    private static AppSettings withLocalOfflineBackend(AppSettings settings) {
        return new AppSettings(
                RecognitionBackend.LOCAL_OFFLINE,
                settings.sttBaseUrl(),
                settings.sttApiKey(),
                settings.sttModel(),
                settings.streamingBaseUrl(),
                settings.streamingApiKey(),
                settings.streamingModel(),
                settings.streamingVocabularyId(),
                settings.language(),
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

    private void finishError(ActiveRun run, Exception error) {
        synchronized (lifecycleLock) {
            if (!active.compareAndSet(run, null)) return;
            state.set(State.IDLE);
        }
        run.diagnostics.fail(SystemClock.elapsedRealtime());
        persistDiagnostics(run);
        String message = error.getMessage();
        run.listener.onError(message == null || message.isBlank() ? "Voice input failed" : message);
    }

    private void finishCancelled(ActiveRun run) {
        if (clearCancelledRun(lifecycleLock, active, state, run)) {
            run.diagnostics.cancel(SystemClock.elapsedRealtime());
            persistDiagnostics(run);
        }
    }

    static <T> boolean clearCancelledRun(
            Object lock,
            AtomicReference<T> activeRun,
            AtomicReference<State> pipelineState,
            T cancelledRun) {
        synchronized (lock) {
            if (!activeRun.compareAndSet(cancelledRun, null)) return false;
            pipelineState.set(State.IDLE);
            return true;
        }
    }

    static AiCandidateDisposition aiCandidateDisposition(boolean candidateSafe, boolean hasSelection) {
        if (candidateSafe) return AiCandidateDisposition.ACCEPT;
        return hasSelection
                ? AiCandidateDisposition.PRESERVE_SELECTION
                : AiCandidateDisposition.INSERT_EXACT_TRANSCRIPT;
    }

    private boolean isCurrent(ActiveRun run) {
        return active.get() == run && !run.cancelled && generation.get() == run.id;
    }

    private void notifyReadyForSpeech(ActiveRun run) {
        if (!isCurrent(run)) return;
        if (run.diagnostics.markReady(SystemClock.elapsedRealtime())) persistDiagnostics(run);
        run.listener.onReadyForSpeech();
    }

    private void persistDiagnostics(ActiveRun run) {
        try {
            RecognitionDiagnostics.Snapshot snapshot = run.diagnostics.snapshot();
            diagnosticsStore.save(snapshot);
            VoiceDiagnosticsLog.emit(snapshot);
        } catch (RuntimeException ignored) {
            // Diagnostics are best-effort and must never break dictation.
        }
    }

    private boolean transitionStateIfCurrent(ActiveRun run, State next, String message) {
        synchronized (lifecycleLock) {
            if (!isCurrent(run)) return false;
            state.set(next);
        }
        run.listener.onState(next, message);
        return true;
    }

    private static boolean requiresLlm(ProcessingMode mode, InputContext context) {
        return context.hasSelection() || mode == ProcessingMode.SMART || mode == ProcessingMode.TRANSLATE;
    }

    private static boolean llmReady(AppSettings settings) {
        return settings.polishEnabled()
                && !settings.llmBaseUrl().trim().isEmpty()
                && !settings.llmModel().trim().isEmpty();
    }

    private static String routeLabel(ActiveRun run) {
        return switch (run.actualBackend) {
            case OPENAI_COMPATIBLE -> "BYOK " + hostLabel(run.request.settings().sttBaseUrl());
            case LOCAL_OFFLINE -> "OpenTypeless offline";
            case DASHSCOPE_STREAMING -> "Paraformer realtime";
            case SYSTEM_ON_DEVICE -> "On-device";
            case SYSTEM_DEFAULT -> "Android speech service";
        };
    }

    private static String hostLabel(String url) {
        try {
            String host = new java.net.URI(url).getHost();
            return host == null ? "provider" : host;
        } catch (Exception ignored) {
            return "provider";
        }
    }
}
