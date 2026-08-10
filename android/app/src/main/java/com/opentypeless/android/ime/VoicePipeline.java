package com.opentypeless.android.ime;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.speech.SpeechRecognizer;

import com.opentypeless.android.audio.AudioRecorder;
import com.opentypeless.android.audio.RecordedAudio;
import com.opentypeless.android.audio.RecordingSession;
import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.context.InputPolicy;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.net.OpenAiCompatibleClient;
import com.opentypeless.android.offline.LocalOfflineRecognizer;
import com.opentypeless.android.offline.LocalRealtimePreview;
import com.opentypeless.android.offline.SafePunctuationRestorer;
import com.opentypeless.android.personalization.PersonalizedTextProcessor;
import com.opentypeless.android.personalization.ProcessingResult;
import com.opentypeless.android.personalization.PromptComposer;
import com.opentypeless.android.personalization.VoiceCommandProcessor;
import com.opentypeless.android.recognition.SystemSpeechRecognizer;
import com.opentypeless.android.recognition.ProviderCapabilities;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.transform.IntegrityResult;
import com.opentypeless.android.transform.TranscriptIntegrityGuard;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class VoicePipeline {
    public enum State { IDLE, RECORDING, TRANSCRIBING, POLISHING }
    enum AiCandidateDisposition { ACCEPT, PRESERVE_SELECTION, INSERT_EXACT_TRANSCRIPT }

    public interface Listener {
        void onState(State state, String message);
        default void onReadyForSpeech() {}
        default void onBeginningOfSpeech() {}
        void onPartial(String text);
        void onResult(DictationResult result);
        void onError(String message);
    }

    private static final class ActiveRun {
        final long id;
        final DictationRequest request;
        final ProcessingMode mode;
        volatile RecordingSession recordingSession;
        volatile RecognitionBackend actualBackend;
        volatile boolean systemFallbackAttempted;
        final Listener listener;
        final long startedAt;
        volatile boolean cancelled;
        volatile Future<?> task;

        ActiveRun(
                long id,
                DictationRequest request,
                ProcessingMode mode,
                RecordingSession recordingSession,
                Listener listener) {
            this.id = id;
            this.request = request;
            this.mode = mode;
            this.recordingSession = recordingSession;
            this.actualBackend = request.settings().recognitionBackend();
            this.listener = listener;
            this.startedAt = System.currentTimeMillis();
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<ActiveRun> active = new AtomicReference<>();
    private final AtomicLong generation = new AtomicLong();
    private final AudioRecorder recorder = new AudioRecorder();
    private final OpenAiCompatibleClient client = new OpenAiCompatibleClient();
    private final SystemSpeechRecognizer systemRecognizer;
    private final Context applicationContext;

    public VoicePipeline(Context context) {
        applicationContext = context.getApplicationContext();
        recorder.setAttributionContext(context);
        systemRecognizer = new SystemSpeechRecognizer(context);
    }

    /** Must be called while idle, before starting an externally attributed recording. */
    public void setRecordingContext(Context context) {
        if (active.get() != null) {
            throw new IllegalStateException("Cannot change microphone attribution during recognition");
        }
        recorder.setAttributionContext(context);
    }

    public boolean start(DictationRequest request, Listener listener) {
        ProcessingMode mode = InputPolicy.resolve(request.requestedMode(), request.inputContext());
        RecognitionBackend backend = request.settings().recognitionBackend();
        RecordingSession recordingSession = backend == RecognitionBackend.OPENAI_COMPATIBLE
                || backend == RecognitionBackend.LOCAL_OFFLINE
                ? new RecordingSession()
                : null;
        ActiveRun run = new ActiveRun(
                generation.incrementAndGet(), request, mode, recordingSession, listener);
        if (!active.compareAndSet(null, run)) return false;
        state.set(State.RECORDING);
        listener.onState(State.RECORDING, routeLabel(run) + " · listening…");

        if (recordingSession != null) {
            run.task = executor.submit(() -> executeCaptured(run));
        } else {
            startSystem(run);
        }
        return true;
    }

    private void executeCaptured(ActiveRun run) {
        LocalOfflineRecognizer.Session offlineSession = null;
        LocalRealtimePreview preview = null;
        try {
            RecognitionBackend backend = run.actualBackend;
            if (backend == RecognitionBackend.LOCAL_OFFLINE) {
                offlineSession = LocalOfflineRecognizer.openSession(
                        applicationContext,
                        run.request.settings().language());
                LocalOfflineRecognizer.Session sessionForPreview = offlineSession;
                preview = new LocalRealtimePreview(
                        sessionForPreview,
                        text -> {
                            if (isCurrent(run)) run.listener.onPartial(text);
                        });
            }
            LocalRealtimePreview activePreview = preview;
            RecordedAudio audio = recorder.record(
                    run.recordingSession,
                    run.request.settings().boundedMaxRecordingSeconds(),
                    new AudioRecorder.CaptureListener() {
                        @Override
                        public void onReady() {
                            if (isCurrent(run)) run.listener.onReadyForSpeech();
                        }

                        @Override
                        public void onBeginningOfSpeech() {
                            if (isCurrent(run)) run.listener.onBeginningOfSpeech();
                        }

                        @Override
                        public void onAudio(byte[] pcm16, int length) {
                            if (activePreview != null && isCurrent(run)) {
                                activePreview.accept(pcm16, length);
                            }
                        }
                    });
            if (!isCurrent(run)) return;
            if (preview != null) preview.close();
            state.set(State.TRANSCRIBING);
            run.listener.onState(State.TRANSCRIBING, routeLabel(run) + " · transcribing…");
            String raw;
            if (backend == RecognitionBackend.LOCAL_OFFLINE) {
                String punctuated = offlineSession.transcribeWithPunctuation(audio.wav());
                String conservative = offlineSession.transcribe(audio.wav());
                raw = SafePunctuationRestorer.choose(
                        conservative,
                        punctuated,
                        run.request.inputContext().fieldKind());
            } else {
                ProviderCapabilities capabilities = ProviderCapabilities.forBackend(backend);
                String prompt = capabilities.asrPrompt()
                        ? PromptComposer.asrPrompt(run.request.personalization())
                        : "";
                raw = client.transcribe(audio.wav(), run.request.settings(), prompt);
            }
            finishTranscription(
                    run,
                    raw,
                    audio.durationMs(),
                    audio.reachedLimit(),
                    audio.autoStopped());
        } catch (CancellationException ignored) {
            finishCancelled(run);
        } catch (Exception error) {
            finishError(run, error);
        } finally {
            if (preview != null) preview.close();
            if (offlineSession != null) offlineSession.close();
        }
    }

    private void startSystem(ActiveRun run) {
        systemRecognizer.start(
                run.request.settings(),
                run.request.personalization(),
                new SystemSpeechRecognizer.Callback() {
                    @Override
                    public void onReady() {
                        if (isCurrent(run)) {
                            run.listener.onState(State.RECORDING,
                                    routeLabel(run) + " · listening…");
                            run.listener.onReadyForSpeech();
                        }
                    }

                    @Override
                    public void onBeginningOfSpeech() {
                        if (isCurrent(run)) run.listener.onBeginningOfSpeech();
                    }

                    @Override
                    public void onPartial(String text) {
                        if (isCurrent(run)) run.listener.onPartial(text);
                    }

                    @Override
                    public void onFinal(String text) {
                        if (!isCurrent(run)) return;
                        state.set(State.TRANSCRIBING);
                        run.listener.onState(State.TRANSCRIBING, "Applying personal vocabulary…");
                        run.task = executor.submit(() -> finishTranscription(
                                run,
                                text,
                                System.currentTimeMillis() - run.startedAt,
                                false,
                                false));
                    }

                    @Override
                    public void onError(int errorCode, String message) {
                        if (!isCurrent(run)) return;
                        if (tryLocalFallback(run, errorCode)) return;
                        finishError(run, new IllegalStateException(message));
                    }
                });
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
                run.systemFallbackAttempted)) {
            return false;
        }
        run.systemFallbackAttempted = true;
        run.actualBackend = RecognitionBackend.LOCAL_OFFLINE;
        run.recordingSession = new RecordingSession();
        state.set(State.RECORDING);
        run.listener.onState(
                State.RECORDING,
                "Android speech service blocked microphone access · using OpenTypeless offline");
        run.task = executor.submit(() -> executeCaptured(run));
        return true;
    }

    static boolean shouldFallbackToLocal(
            int errorCode,
            boolean permissionGranted,
            boolean supported,
            boolean installed,
            boolean alreadyAttempted) {
        return errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                && permissionGranted
                && supported
                && installed
                && !alreadyAttempted;
    }

    private void finishTranscription(
            ActiveRun run,
            String rawText,
            long durationMs,
            boolean reachedLimit,
            boolean autoStopped) {
        if (!isCurrent(run)) return;
        PersonalizationSnapshot snapshot = run.request.settings().personalizationEnabled()
                ? run.request.personalization()
                : PersonalizationSnapshot.empty();
        ProcessingResult personalized;
        try {
            personalized = PersonalizedTextProcessor.apply(rawText, snapshot);
        } catch (IllegalArgumentException error) {
            finishError(run, error);
            return;
        }
        String finalText = personalized.text();
        String message = reachedLimit
                ? "Inserted · recording time limit reached"
                : autoStopped ? "Inserted · stopped after silence" : "Inserted";
        boolean aiAccepted = false;

        String command = run.request.inputContext().hasSelection()
                ? null
                : VoiceCommandProcessor.exactReplacement(finalText);
        if (command != null) {
            finalText = command;
            message = "Voice command inserted";
        } else if (requiresLlm(run.mode, run.request.inputContext())) {
            if (!llmReady(run.request.settings())) {
                if (run.request.inputContext().hasSelection()) {
                    finishError(run, new IllegalStateException(
                            "Selected-text editing requires a configured AI polish endpoint"));
                    return;
                }
                message = "Inserted exact transcript · AI polish is not configured";
            } else {
                state.set(State.POLISHING);
                run.listener.onState(State.POLISHING, "Checking and polishing without changing facts…");
                try {
                    String systemPrompt = PromptComposer.systemPrompt(
                            run.mode,
                            run.request.inputContext(),
                            snapshot,
                            run.request.settings().targetLanguage(),
                            run.request.settings().customInstructions());
                    String userPrompt = PromptComposer.userPrompt(
                            finalText,
                            run.request.inputContext(),
                            run.request.settings().sendContext());
                    String candidate = client.complete(systemPrompt, userPrompt, run.request.settings());
                    ProcessingResult protectedCandidate = PersonalizedTextProcessor.apply(candidate, snapshot);
                    String integritySource = run.request.inputContext().hasSelection()
                            ? run.request.inputContext().selectedText()
                            : finalText;
                    IntegrityResult integrity = TranscriptIntegrityGuard.validate(
                            integritySource,
                            protectedCandidate.text(),
                            run.mode,
                            snapshot);
                    AiCandidateDisposition disposition = aiCandidateDisposition(
                            integrity.safe(),
                            run.request.inputContext().hasSelection());
                    if (disposition == AiCandidateDisposition.ACCEPT) {
                        finalText = protectedCandidate.text();
                        aiAccepted = true;
                        message = run.request.inputContext().hasSelection()
                                ? "Selected text updated · Undo available"
                                : run.mode == ProcessingMode.TRANSLATE
                                ? "Translated · Undo available"
                                : "Smart edit inserted · Undo available";
                    } else if (disposition == AiCandidateDisposition.PRESERVE_SELECTION) {
                        finishError(run, new IllegalStateException(
                                "AI edit blocked to protect facts; original selection was preserved"));
                        return;
                    } else {
                        message = "AI edit blocked to protect facts · exact transcript inserted";
                    }
                } catch (Exception error) {
                    if (!isCurrent(run)) return;
                    if (run.request.inputContext().hasSelection()) {
                        finishError(run, new IllegalStateException(
                                "Selected-text edit failed; original selection was preserved"));
                        return;
                    }
                    message = "Inserted exact transcript because AI polish failed";
                }
            }
        }

        if (!isCurrent(run)) return;
        DictationResult result = new DictationResult(
                rawText.trim(),
                personalized.text(),
                finalText,
                message,
                run.mode,
                run.actualBackend,
                durationMs,
                reachedLimit,
                aiAccepted,
                personalized.matchedTermIds(),
                personalized.matchedCorrectionIds());
        if (active.compareAndSet(run, null)) {
            state.set(State.IDLE);
            run.listener.onResult(result);
        }
    }

    public void stopRecording() {
        ActiveRun run = active.get();
        if (run == null || state.get() != State.RECORDING) return;
        if (run.recordingSession != null) recorder.stop(run.recordingSession);
        else systemRecognizer.stop();
    }

    public void cancel() {
        ActiveRun run = active.getAndSet(null);
        generation.incrementAndGet();
        if (run != null) {
            run.cancelled = true;
            if (run.recordingSession != null) recorder.cancel(run.recordingSession);
            systemRecognizer.cancel();
            client.cancelActiveRequest();
            Future<?> task = run.task;
            if (task != null) task.cancel(true);
        }
        state.set(State.IDLE);
    }

    public State state() {
        return state.get();
    }

    public void shutdown() {
        cancel();
        systemRecognizer.destroy();
        executor.shutdownNow();
    }

    private void finishError(ActiveRun run, Exception error) {
        if (!active.compareAndSet(run, null)) return;
        state.set(State.IDLE);
        String message = error.getMessage();
        run.listener.onError(message == null || message.isBlank() ? "Voice input failed" : message);
    }

    private void finishCancelled(ActiveRun run) {
        clearCancelledRun(active, state, run);
    }

    static <T> boolean clearCancelledRun(
            AtomicReference<T> activeRun,
            AtomicReference<State> pipelineState,
            T cancelledRun) {
        if (!activeRun.compareAndSet(cancelledRun, null)) return false;
        pipelineState.set(State.IDLE);
        return true;
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
