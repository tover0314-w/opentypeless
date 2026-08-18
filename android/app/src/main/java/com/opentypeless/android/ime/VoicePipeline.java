package com.opentypeless.android.ime;

import android.content.Context;

import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.diagnostics.RecognitionRoute;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.speech.journal.JournalToken;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Compatibility facade for the voice runtime.
 *
 * <p>Session execution, capture, recognition, processing, recovery and diagnostics live in the
 * package-confined {@link VoicePipelineRuntime}. This class deliberately retains the historical
 * caller surface while limiting itself to exact delegation and compatibility-only pure helpers.
 */
public final class VoicePipeline {
    public enum State { IDLE, RECORDING, TRANSCRIBING, POLISHING }

    enum AiCandidateDisposition { ACCEPT, PRESERVE_SELECTION, INSERT_EXACT_TRANSCRIPT }

    public interface Listener {
        void onState(State state, String message);

        default void onRoute(RecognitionRoute route) {}

        default void onReadyForSpeech() {}

        default void onBeginningOfSpeech() {}

        default void onTranscript(TranscriptUpdate update) {
            onPartial(update.text());
        }

        /** @deprecated Implement {@link #onTranscript(TranscriptUpdate)} for revision metadata. */
        @Deprecated
        default void onPartial(String text) {}

        void onResult(DictationResult result);

        void onError(String message);
    }

    private final VoicePipelineRuntime runtime;

    public VoicePipeline(Context context) {
        runtime = new VoicePipelineRuntime(Objects.requireNonNull(context, "context"));
    }

    /** Must be called while idle, before starting an externally attributed recording. */
    public void setRecordingContext(Context context) {
        runtime.setRecordingContext(context);
    }

    public boolean start(DictationRequest request, Listener listener) {
        return runtime.start(request, listener);
    }

    /** Warms only the streaming first pass; SenseVoice remains cold and isolated until needed. */
    public void prewarmLocalOffline() {
        runtime.prewarmLocalOffline();
    }

    public void stopRecording() {
        runtime.stopRecording();
    }

    public void cancel() {
        runtime.cancel();
    }

    /** Explicit user discard. Unlike lifecycle cancellation this removes the durable checkpoint. */
    public void discard() {
        runtime.discard();
    }

    public boolean hasRecoverableAudio() {
        return runtime.hasRecoverableAudio();
    }

    /** Removes a completed checkpoint only after its result has been safely accepted elsewhere. */
    public boolean acknowledgeRecovery(String recoveryId) {
        return runtime.acknowledgeRecovery(recoveryId);
    }

    /**
     * Replays a protected checkpoint without opening the microphone. Results remain detached from
     * any editor captured before process death.
     */
    public boolean recover(DictationRequest request, Listener listener) {
        return runtime.recover(request, listener);
    }

    public State state() {
        return runtime.state();
    }

    public void shutdown() {
        runtime.shutdown();
    }

    static boolean shouldUseSpeechCoreV2(
            RecognitionBackend backend, boolean speechCoreV2Enabled) {
        return VoicePipelineRuntime.shouldUseSpeechCoreV2(backend, speechCoreV2Enabled);
    }

    static boolean shouldFallbackToLocal(
            int errorCode,
            boolean permissionGranted,
            boolean supported,
            boolean installed,
            boolean alreadyAttempted,
            boolean stopRequested) {
        return VoicePipelineRuntime.shouldFallbackToLocal(
                errorCode,
                permissionGranted,
                supported,
                installed,
                alreadyAttempted,
                stopRequested);
    }

    static boolean shouldRecoverVisiblePartial(boolean hasSelection, String latestTranscript) {
        return VoicePipelineRuntime.shouldRecoverVisiblePartial(hasSelection, latestTranscript);
    }

    static String joinTranscriptSegments(String completed, String next) {
        return VoicePipelineRuntime.joinTranscriptSegments(completed, next);
    }

    static String reconcileSystemFinal(
            String completed,
            String finalSegment,
            String latestVisible,
            FieldKind fieldKind) {
        return VoicePipelineRuntime.reconcileSystemFinal(
                completed, finalSegment, latestVisible, fieldKind);
    }

    static String limitCodePoints(String value, int maximum) {
        return VoicePipelineRuntime.limitCodePoints(value, maximum);
    }

    static JournalToken parseSpeechCoreRecoveryId(String recoveryId) {
        return VoicePipelineRuntime.parseSpeechCoreRecoveryId(recoveryId);
    }

    static <T> boolean clearCancelledRun(
            Object lock,
            AtomicReference<T> activeRun,
            AtomicReference<State> pipelineState,
            T cancelledRun) {
        return VoicePipelineRuntime.clearCancelledRun(
                lock, activeRun, pipelineState, cancelledRun);
    }

    static AiCandidateDisposition aiCandidateDisposition(
            boolean candidateSafe, boolean hasSelection) {
        return VoicePipelineRuntime.aiCandidateDisposition(candidateSafe, hasSelection);
    }
}
