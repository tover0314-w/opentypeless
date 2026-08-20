package com.opentypeless.android.ime;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.opentypeless.android.HistoryActivity;
import com.opentypeless.android.AppProfileActivity;
import com.opentypeless.android.DictionaryActivity;
import com.opentypeless.android.R;
import com.opentypeless.android.SettingsHomeActivity;
import com.opentypeless.android.VoiceLabActivity;
import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.context.InputContextClassifier;
import com.opentypeless.android.editor.CommitRecord;
import com.opentypeless.android.editor.CompositionConflictPolicy;
import com.opentypeless.android.editor.CompositionCoordinator;
import com.opentypeless.android.editor.CompositionState;
import com.opentypeless.android.editor.EditorSessionSnapshot;
import com.opentypeless.android.editor.EditorTransactionResult;
import com.opentypeless.android.editor.SessionValidationResult;
import com.opentypeless.android.editor.SessionValidator;
import com.opentypeless.android.editor.TransactionReceipt;
import com.opentypeless.android.editor.TextRange;
import com.opentypeless.android.editor.host.EditorSessionManager;
import com.opentypeless.android.keyboard.candidate.CandidatePage;
import com.opentypeless.android.keyboard.candidate.KeyboardCandidateBar;
import com.opentypeless.android.keyboard.latin.LatinKeyboardLayout;
import com.opentypeless.android.keyboard.field.KeyboardFieldProfile;
import com.opentypeless.android.keyboard.feedback.AndroidKeyboardFeedback;
import com.opentypeless.android.keyboard.rime.NativeRimeInputEngine;
import com.opentypeless.android.keyboard.rime.RimeEngineSnapshot;
import com.opentypeless.android.keyboard.rime.RimeInputController;
import com.opentypeless.android.keyboard.rime.RimeInputEngine;
import com.opentypeless.android.keyboard.rime.RimeRuntimeConfig;
import com.opentypeless.android.keyboard.shell.KeyboardShellConfig;
import com.opentypeless.android.keyboard.shell.KeyboardShellFrame;
import com.opentypeless.android.keyboard.shell.KeyboardInputModeLayout;
import com.opentypeless.android.keyboard.shell.KeyboardShellRoute;
import com.opentypeless.android.keyboard.shell.KeyboardShellSelector;
import com.opentypeless.android.keyboard.switching.KeyboardEngineSelection;
import com.opentypeless.android.keyboard.switching.KeyboardSystemImeSwitcher;
import com.opentypeless.android.keyboard.toolbar.KeyboardToolbarLayout;
import com.opentypeless.android.keyboard.toolbar.KeyboardToolbarPrivacyPolicy;
import com.opentypeless.android.keyboard.ui.CenteredIconButton;
import com.opentypeless.android.data.HistoryEntry;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.offline.LocalOfflineRecognizer;
import com.opentypeless.android.offline.OfflineStreamingRecognizer;
import com.opentypeless.android.data.PersonalizationStore;
import com.opentypeless.android.diagnostics.RecognitionRoute;
import com.opentypeless.android.personalization.TeachCorrectionResolver;
import com.opentypeless.android.recognition.RecognitionRouterVoiceConfig;
import com.opentypeless.android.rime.importer.RimeImportException;
import com.opentypeless.android.rime.importer.RimeResourceStore;
import com.opentypeless.android.rime.importer.RimeRuntimePreferences;
import com.opentypeless.android.rime.userdata.RimeUserDataStore;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.AppProfile;
import com.opentypeless.android.settings.AppProfileRepository;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.SettingsRepository;
import com.opentypeless.android.security.PrivacyPolicyEngine;
import com.opentypeless.android.security.SecurePreferences;
import com.opentypeless.android.speech.delivery.AndroidInputConnectionAdapter;
import com.opentypeless.android.speech.delivery.EditorProjection;
import com.opentypeless.android.speech.delivery.ProjectionContext;
import com.opentypeless.android.speech.delivery.ProjectionDocument;
import com.opentypeless.android.speech.delivery.ProjectionMode;
import com.opentypeless.android.speech.delivery.ProjectionOutcome;
import com.opentypeless.android.speech.delivery.ProjectionResult;
import com.opentypeless.android.speech.delivery.ProjectionState;
import com.opentypeless.android.speech.runtime.SpeechCoreV2Config;
import com.opentypeless.android.speech.runtime.VoiceEditorTransactionConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A deliberately small voice keyboard. It binds every asynchronous recognition run to an exact
 * editor epoch and cursor fingerprint so a late result can never spill into a different field.
 */
public final class OpenTypelessImeService extends InputMethodService
        implements EditorSessionManager.KeyboardHost {
    private static final int CONTEXT_CHAR_LIMIT = 800;
    private static final int FINGERPRINT_CODE_POINTS = 64;
    private static final int MAX_SELECTION_CODE_POINTS = 4_000;
    private static final int MENU_MODE_BASE = 100;
    private static final int MENU_DISCARD = 200;
    private static final int MENU_RESTORE_RAW = 201;
    private static final int MENU_TEACH = 202;
    private static final int MENU_APP_PROFILE = 203;
    private static final int MENU_SETTINGS = 204;
    private static final int MENU_INSERT_RECOVERABLE_DRAFT = 205;
    private static final int MENU_DISCARD_RECOVERABLE_DRAFT = 206;
    private static final int MENU_DICTIONARY = 207;
    private static final int MENU_RECOVER_AUDIO = 208;
    private static final int MENU_DISCARD_AUDIO = 209;
    private static final int MENU_UNDO = 210;
    private static final int MENU_VOICE_DIAGNOSTICS = 211;
    private static final int MENU_PUNCTUATION_BASE = 300;
    private static final long DISCARD_CONFIRM_WINDOW_MILLIS = 10_000L;
    private static final long DETACHED_STATE_REFRESH_MILLIS = 500L;
    private static final long NO_PARTIAL_HINT_DELAY_MILLIS = 2_000L;
    private static final String RECOVERABLE_DRAFT_PREFERENCE = "recoverable_voice_draft";
    private static final String RECOVERABLE_DRAFT_AUDIO_ID_PREFERENCE =
            "recoverable_voice_draft_audio_id";
    /** Shared across service recreation so a late result cannot overwrite a new instance's draft. */
    private static final RecoverableDraftSlot PROCESS_RECOVERABLE_DRAFT =
            new RecoverableDraftSlot();
    private static final AtomicReference<PendingDetachedSession<CommitTarget>>
            PROCESS_PENDING_DETACHED =
            new AtomicReference<>();
    private static final Object DRAFT_STORAGE_LOCK = new Object();
    private static final AtomicLong DRAFT_STORAGE_GENERATION = new AtomicLong();
    private static final AtomicLong VOICE_TRANSACTION_GENERATION = new AtomicLong();
    private static final AtomicReference<String> PROCESS_RECOVERABLE_AUDIO_ID =
            new AtomicReference<>("");
    private static boolean processDraftStorageLoaded;

    enum EditorMutationResult {
        APPLIED,
        DELETE_REJECTED,
        COMMIT_REJECTED,
        ROLLED_BACK,
        ROLLBACK_FAILED,
        CONNECTION_ERROR
    }

    enum HoldReleaseAction { STOP_AND_COMMIT, CANCEL_PREPARATION, WAIT_FOR_RESULT }

    private enum VoiceReleaseProof { RELEASED, UNCHANGED, UNCERTAIN }

    private enum RimeReleaseProof { RELEASED, UNCHANGED, UNCERTAIN }

    record SelectionEvidence(
            boolean known,
            boolean hasSelection,
            boolean selectedTextAvailable,
            int start,
            int end,
            String text) {}

    enum SelectionCaptureDecision {
        ACCEPT,
        UNKNOWN,
        UNAVAILABLE,
        TOO_LONG
    }

    private static final class CommitTarget {
        final long editorEpoch;
        final InputConnection connection;
        final String packageName;
        final int fieldId;
        final FieldKind fieldKind;
        final String selectedText;
        final String beforeFingerprint;
        final String afterFingerprint;
        final String precedingContext;
        final boolean learningAllowed;
        final int selectionStart;
        final int selectionEnd;
        final boolean transactionWriter;
        final long voiceGeneration;
        final EditorSessionManager.CaptureResult editorSessionCapture;
        final Object recoveryToken = new Object();
        final AtomicBoolean voiceTerminal = new AtomicBoolean();

        CommitTarget(
                long editorEpoch,
                InputConnection connection,
                String packageName,
                int fieldId,
                FieldKind fieldKind,
                String selectedText,
                String beforeFingerprint,
                String afterFingerprint,
                String precedingContext,
                boolean learningAllowed,
                int selectionStart,
                int selectionEnd,
                boolean transactionWriter,
                long voiceGeneration,
                EditorSessionManager.CaptureResult editorSessionCapture) {
            this.editorEpoch = editorEpoch;
            this.connection = connection;
            this.packageName = packageName;
            this.fieldId = fieldId;
            this.fieldKind = fieldKind;
            this.selectedText = selectedText;
            this.beforeFingerprint = beforeFingerprint;
            this.afterFingerprint = afterFingerprint;
            this.precedingContext = precedingContext;
            this.learningAllowed = learningAllowed;
            this.selectionStart = selectionStart;
            this.selectionEnd = selectionEnd;
            this.transactionWriter = transactionWriter;
            this.voiceGeneration = voiceGeneration;
            this.editorSessionCapture = editorSessionCapture;
        }

        boolean replacedSelection() {
            return !selectedText.isEmpty();
        }

        boolean markVoiceTerminal() {
            return voiceTerminal.compareAndSet(false, true);
        }

        boolean voiceTerminal() {
            return voiceTerminal.get();
        }
    }

    /**
     * Session-local generation, transcript ordering and ETM composition state.
     *
     * <p>This object contains no Android editor capability. It is created only for the frozen
     * transaction-writer branch and becomes terminal before the final callback performs a write,
     * so a queued partial with any larger provider sequence is still rejected.
     */
    static final class VoiceTransactionSession {
        private static final int MAX_PENDING_SELECTIONS = 8;

        /** Opaque, session-owned handle for one Voice-to-keyboard preemption. */
        static final class KeyboardPreemption {
            private final VoiceTransactionSession owner;
            private final CompositionCoordinator.PreemptTicket ticket;
            private final CompositionConflictPolicy.Decision decision;
            private boolean keyboardAcquired;
            private boolean closed;

            private KeyboardPreemption(
                    VoiceTransactionSession owner,
                    CompositionCoordinator.PreemptTicket ticket,
                    CompositionConflictPolicy.Decision decision) {
                this.owner = owner;
                this.ticket = ticket;
                this.decision = decision;
            }

            CompositionCoordinator.ReleaseDirective directive() {
                return decision.releaseDirective();
            }

            boolean routeLateResult() {
                return decision.routeDisplacedResultToPanel();
            }

            @Override
            public String toString() {
                return "KeyboardPreemption{directive="
                        + directive()
                        + ", routeLateResult="
                        + routeLateResult()
                        + ", ticket=<redacted>}";
            }
        }

        final long generation;
        final TextRange originalSelection;
        final int originalCursor;
        final Deque<Integer> expectedSelectionEnds = new ArrayDeque<>();
        private final CompositionCoordinator coordinator;
        private CompositionCoordinator.Observation compositionObservation;
        EditorSessionSnapshot snapshot;
        long latestSequence;
        long revision;
        String compositionText = "";
        boolean compositionActive;
        boolean terminal;
        private boolean coordinatorReleased;
        private boolean finalCallbackClaimed;
        private KeyboardPreemption keyboardPreemption;

        private VoiceTransactionSession(
                long generation,
                EditorSessionSnapshot snapshot,
                CompositionCoordinator coordinator,
                CompositionCoordinator.Observation compositionObservation) {
            if (generation <= 0) throw new IllegalArgumentException("generation must be positive");
            this.generation = generation;
            this.snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
            this.coordinator = java.util.Objects.requireNonNull(coordinator, "coordinator");
            this.compositionObservation = java.util.Objects.requireNonNull(
                    compositionObservation, "compositionObservation");
            originalSelection = snapshot.selection();
            originalCursor = originalSelection.isKnown()
                    ? Math.min(originalSelection.start(), originalSelection.end())
                    : -1;
        }

        static VoiceTransactionSession acquire(
                long generation,
                EditorSessionSnapshot snapshot,
                CompositionCoordinator coordinator) {
            if (generation <= 0) throw new IllegalArgumentException("generation must be positive");
            java.util.Objects.requireNonNull(snapshot, "snapshot");
            java.util.Objects.requireNonNull(coordinator, "coordinator");
            CompositionCoordinator.Transition acquired = coordinator.acquire(
                    coordinator.observe(), new CompositionCoordinator.Acquisition.Voice());
            if (acquired.disposition() != CompositionCoordinator.Disposition.APPLIED) return null;
            return new VoiceTransactionSession(
                    generation, snapshot, coordinator, acquired.after());
        }

        synchronized boolean markReady(long expectedGeneration) {
            if (expectedGeneration != generation || terminal || coordinatorReleased) return false;
            CompositionCoordinator.Transition transition =
                    coordinator.voiceReady(compositionObservation);
            if (!accepted(transition, CompositionCoordinator.Disposition.IGNORED_DUPLICATE)) {
                return false;
            }
            compositionObservation = transition.after();
            return true;
        }

        synchronized boolean acceptsPartial(long expectedGeneration, long sequence) {
            return expectedGeneration == generation
                    && !terminal
                    && !coordinatorReleased
                    && sequence > latestSequence;
        }

        synchronized long prepareComposition(long sequence, String text) {
            if (terminal && !compositionActive) {
                throw new IllegalStateException("voice transaction is terminal");
            }
            if (coordinatorReleased) {
                throw new IllegalStateException("voice coordinator already released");
            }
            String committedText = java.util.Objects.requireNonNull(text, "text");
            long nextRevision = Math.addExact(revision, 1L);
            if (nextRevision <= 0) {
                throw new IllegalStateException("voice revision exhausted");
            }
            int expectedEnd = Math.addExact(originalCursor, committedText.length());
            CompositionCoordinator.Transition transition = coordinator.voicePartial(
                    compositionObservation, nextRevision);
            if (transition.disposition() != CompositionCoordinator.Disposition.APPLIED) {
                throw new IllegalStateException("voice partial transition rejected");
            }
            compositionObservation = transition.after();
            revision = nextRevision;
            latestSequence = Math.max(latestSequence, sequence);
            compositionText = committedText;
            compositionActive = true;
            rememberExpectedSelection(expectedEnd);
            return revision;
        }

        synchronized void completeComposition(EditorSessionSnapshot captured) {
            snapshot = java.util.Objects.requireNonNull(captured, "captured");
        }

        synchronized void recordIgnoredSequence(long sequence) {
            latestSequence = Math.max(latestSequence, sequence);
        }

        synchronized void prepareFinalSelection(String text) {
            rememberExpectedSelection(Math.addExact(originalCursor, text.length()));
        }

        synchronized boolean beginTerminal(long expectedGeneration) {
            if (expectedGeneration != generation
                    || coordinatorReleased
                    || finalCallbackClaimed
                    || keyboardPreemption != null) {
                return false;
            }
            finalCallbackClaimed = true;
            terminal = true;
            return true;
        }

        synchronized boolean beginFinalizing() {
            if (!terminal || coordinatorReleased) return false;
            CompositionCoordinator.Transition transition =
                    coordinator.beginVoiceFinalizing(compositionObservation);
            if (!accepted(transition, CompositionCoordinator.Disposition.IGNORED_DUPLICATE)) {
                return false;
            }
            compositionObservation = transition.after();
            return true;
        }

        synchronized boolean beginPreserving() {
            if (coordinatorReleased) return false;
            terminal = true;
            if (!compositionActive) return true;
            return beginFinalizing();
        }

        synchronized boolean completeCoordinatorAfterCommit() {
            if (coordinatorReleased) return true;
            CompositionCoordinator.Transition transition =
                    coordinator.complete(compositionObservation);
            if (transition.disposition() != CompositionCoordinator.Disposition.APPLIED) {
                return false;
            }
            compositionObservation = transition.after();
            coordinatorReleased = true;
            return true;
        }

        synchronized boolean cancelCoordinatorAfterCleanup() {
            if (coordinatorReleased) return true;
            terminal = true;
            CompositionCoordinator.Transition transition =
                    coordinator.cancel(compositionObservation);
            if (!accepted(transition, CompositionCoordinator.Disposition.IGNORED_DUPLICATE)) {
                return false;
            }
            compositionObservation = transition.after();
            coordinatorReleased = true;
            return true;
        }

        synchronized boolean releaseAfterEditorLifecycle() {
            if (keyboardPreemption != null) {
                KeyboardPreemption preemption = keyboardPreemption;
                if (!preemption.keyboardAcquired) {
                    CompositionCoordinator.Transition released = coordinator.finishPreempt(
                            preemption.ticket,
                            CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED);
                    if (released.disposition() != CompositionCoordinator.Disposition.APPLIED) {
                        return false;
                    }
                    compositionObservation = released.after();
                    preemption.keyboardAcquired = true;
                }
                CompositionCoordinator.Transition cancelled =
                        coordinator.cancel(compositionObservation);
                if (cancelled.disposition() != CompositionCoordinator.Disposition.APPLIED) {
                    return false;
                }
                compositionObservation = cancelled.after();
                preemption.closed = true;
                keyboardPreemption = null;
                coordinatorReleased = true;
                terminal = true;
                return true;
            }
            return cancelCoordinatorAfterCleanup();
        }

        synchronized KeyboardPreemption beginKeyboardPreemption(
                CompositionConflictPolicy policy, boolean finalPending) {
            java.util.Objects.requireNonNull(policy, "policy");
            if (coordinatorReleased || keyboardPreemption != null) return null;
            CompositionConflictPolicy.Decision decision;
            try {
                CompositionState policyState = compositionObservation.state();
                if (finalPending) {
                    policyState = new CompositionState.VoiceFinalizing(
                            policyState.coordinationGeneration(),
                            compositionActive ? revision : 0L);
                }
                decision = policy.voiceToKeyboardDecision(policyState);
            } catch (RuntimeException wrongState) {
                return null;
            }
            CompositionCoordinator.PreemptStart started = coordinator.beginPreempt(
                    compositionObservation,
                    decision.releaseDirective(),
                    new CompositionCoordinator.Acquisition.Latin(1L));
            if (!(started instanceof CompositionCoordinator.PreemptPrepared prepared)) {
                return null;
            }
            terminal = true;
            compositionObservation = prepared.observation();
            keyboardPreemption = new KeyboardPreemption(this, prepared.ticket(), decision);
            return keyboardPreemption;
        }

        synchronized long prepareKeyboardCancellation(KeyboardPreemption preemption) {
            requireActiveKeyboardPreemption(preemption, false);
            if (preemption.directive()
                    != CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT) {
                throw new IllegalStateException("keyboard preemption is not cancellation");
            }
            if (!compositionActive) {
                throw new IllegalStateException("voice composition is not active");
            }
            long nextRevision = Math.addExact(revision, 1L);
            if (nextRevision <= 0L) {
                throw new IllegalStateException("voice revision exhausted");
            }
            rememberExpectedSelection(originalCursor);
            return nextRevision;
        }

        synchronized void completeKeyboardCancellation(
                KeyboardPreemption preemption, long appliedRevision) {
            requireActiveKeyboardPreemption(preemption, false);
            if (appliedRevision != Math.addExact(revision, 1L)) {
                throw new IllegalStateException("keyboard cancellation revision drifted");
            }
            revision = appliedRevision;
            compositionText = "";
        }

        synchronized boolean finishKeyboardRelease(
                KeyboardPreemption preemption,
                CompositionCoordinator.ReleaseResolution resolution) {
            java.util.Objects.requireNonNull(resolution, "resolution");
            requireActiveKeyboardPreemption(preemption, false);
            CompositionCoordinator.Transition transition =
                    coordinator.finishPreempt(preemption.ticket, resolution);
            if (resolution == CompositionCoordinator.ReleaseResolution.UNCERTAIN) {
                return false;
            }
            compositionObservation = transition.after();
            if (resolution == CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED
                    && transition.disposition() == CompositionCoordinator.Disposition.APPLIED) {
                preemption.keyboardAcquired = true;
                return true;
            }
            if (resolution == CompositionCoordinator.ReleaseResolution.PROVEN_UNCHANGED
                    && transition.disposition()
                            == CompositionCoordinator.Disposition.RELEASE_PROVEN_UNCHANGED) {
                preemption.closed = true;
                keyboardPreemption = null;
            }
            return false;
        }

        synchronized boolean finishKeyboardEvent(
                KeyboardPreemption preemption, boolean applied) {
            requireActiveKeyboardPreemption(preemption, true);
            CompositionCoordinator.Transition transition = applied
                    ? coordinator.commit(compositionObservation, 1L)
                    : coordinator.cancel(compositionObservation);
            if (transition.disposition() != CompositionCoordinator.Disposition.APPLIED) {
                return false;
            }
            compositionObservation = transition.after();
            preemption.closed = true;
            keyboardPreemption = null;
            coordinatorReleased = true;
            terminal = true;
            return true;
        }

        synchronized boolean keyboardPreemptionActive() {
            return keyboardPreemption != null
                    && keyboardPreemption.keyboardAcquired
                    && !keyboardPreemption.closed;
        }

        synchronized boolean coordinatorReleased() {
            return coordinatorReleased;
        }

        synchronized boolean acceptsSelection(
                int start, int end, int candidatesStart, int candidatesEnd) {
            if (originalCursor < 0) return false;
            if (!compositionActive
                    && start == originalSelection.start()
                    && end == originalSelection.end()) {
                return true;
            }
            if (start != end) return false;
            if (!expectedSelectionEnds.contains(end)) return false;
            boolean rangeOmitted = candidatesStart < 0 && candidatesEnd < 0;
            boolean ownedRange = candidatesStart == originalCursor
                    && expectedSelectionEnds.contains(candidatesEnd);
            return rangeOmitted || ownedRange;
        }

        synchronized void close() {
            terminal = true;
            compositionActive = false;
            compositionText = "";
            expectedSelectionEnds.clear();
        }

        private void requireActiveKeyboardPreemption(
                KeyboardPreemption preemption, boolean requireKeyboardAcquired) {
            if (preemption == null
                    || preemption.owner != this
                    || preemption != keyboardPreemption
                    || preemption.closed
                    || (requireKeyboardAcquired && !preemption.keyboardAcquired)) {
                throw new IllegalStateException("keyboard preemption is not active");
            }
        }

        private static boolean accepted(
                CompositionCoordinator.Transition transition,
                CompositionCoordinator.Disposition idempotent) {
            return transition.disposition() == CompositionCoordinator.Disposition.APPLIED
                    || transition.disposition() == idempotent;
        }

        private void rememberExpectedSelection(int end) {
            if (end < 0) return;
            expectedSelectionEnds.remove(end);
            expectedSelectionEnds.addLast(end);
            while (expectedSelectionEnds.size() > MAX_PENDING_SELECTIONS) {
                expectedSelectionEnds.removeFirst();
            }
        }

        @Override
        public String toString() {
            return "VoiceTransactionSession{generation=" + generation + ", <redacted>}";
        }
    }

    /**
     * Opaque Rime-to-Voice handoff. Policy selects an intent, the editor-host proves its physical
     * release, and only this owner-bound handle can publish or cancel the reserved Voice owner.
     */
    static final class RimeVoicePreemption {
        enum Finish {
            VOICE_ACQUIRED,
            RIME_UNCHANGED,
            UNCERTAIN
        }

        private final CompositionCoordinator coordinator;
        private final CompositionCoordinator.PreemptTicket ticket;
        private final CompositionConflictPolicy.Decision decision;
        private CompositionCoordinator.Observation observation;
        private Finish finish;
        private boolean claimed;

        private RimeVoicePreemption(
                CompositionCoordinator coordinator,
                CompositionCoordinator.PreemptTicket ticket,
                CompositionConflictPolicy.Decision decision,
                CompositionCoordinator.Observation observation) {
            this.coordinator = coordinator;
            this.ticket = ticket;
            this.decision = decision;
            this.observation = observation;
        }

        static RimeVoicePreemption begin(
                CompositionCoordinator coordinator,
                CompositionCoordinator.Observation expectedRime,
                CompositionConflictPolicy policy) {
            java.util.Objects.requireNonNull(coordinator, "coordinator");
            java.util.Objects.requireNonNull(expectedRime, "expectedRime");
            java.util.Objects.requireNonNull(policy, "policy");
            if (!(expectedRime.state() instanceof CompositionState.RimeComposing)) return null;
            CompositionConflictPolicy.Decision decision = policy.rimeToVoiceDecision();
            CompositionCoordinator.PreemptStart started = coordinator.beginPreempt(
                    expectedRime,
                    decision.releaseDirective(),
                    new CompositionCoordinator.Acquisition.Voice());
            if (!(started instanceof CompositionCoordinator.PreemptPrepared prepared)) return null;
            return new RimeVoicePreemption(
                    coordinator, prepared.ticket(), decision, prepared.observation());
        }

        synchronized CompositionCoordinator.ReleaseDirective directive() {
            return decision.releaseDirective();
        }

        synchronized Finish finish(CompositionCoordinator.ReleaseResolution resolution) {
            java.util.Objects.requireNonNull(resolution, "resolution");
            if (finish != null) throw new IllegalStateException("Rime preemption already finished");
            CompositionCoordinator.Transition transition = coordinator.finishPreempt(
                    ticket, resolution);
            observation = transition.after();
            if (resolution == CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED
                    && transition.disposition() == CompositionCoordinator.Disposition.APPLIED
                    && observation.state() instanceof CompositionState.VoicePreparing) {
                finish = Finish.VOICE_ACQUIRED;
            } else if (resolution == CompositionCoordinator.ReleaseResolution.PROVEN_UNCHANGED
                    && transition.disposition()
                            == CompositionCoordinator.Disposition.RELEASE_PROVEN_UNCHANGED
                    && observation.state() instanceof CompositionState.RimeComposing) {
                finish = Finish.RIME_UNCHANGED;
            } else {
                finish = Finish.UNCERTAIN;
            }
            return finish;
        }

        synchronized CompositionCoordinator.Observation restoredRimeObservation() {
            if (finish != Finish.RIME_UNCHANGED || claimed) {
                throw new IllegalStateException("Rime owner was not restored");
            }
            claimed = true;
            return observation;
        }

        synchronized VoiceTransactionSession claimVoiceSession(
                long generation, EditorSessionSnapshot snapshot) {
            if (finish != Finish.VOICE_ACQUIRED || claimed) return null;
            claimed = true;
            return new VoiceTransactionSession(generation, snapshot, coordinator, observation);
        }

        synchronized boolean cancelUnclaimedVoice() {
            if (finish != Finish.VOICE_ACQUIRED || claimed) return false;
            CompositionCoordinator.Transition cancelled = coordinator.cancel(observation);
            if (cancelled.disposition() != CompositionCoordinator.Disposition.APPLIED) return false;
            observation = cancelled.after();
            claimed = true;
            return true;
        }

        @Override
        public String toString() {
            return "RimeVoicePreemption{directive=" + directive() + ", token=<redacted>}";
        }
    }

    static final class PendingDetachedSession<T> {
        final Object recoveryToken;
        private T target;
        private Runnable ownerDiscard;
        private boolean completed;
        private boolean discarded;

        PendingDetachedSession(T target, Object recoveryToken, Runnable ownerDiscard) {
            this.recoveryToken = recoveryToken;
            this.target = target;
            this.ownerDiscard = ownerDiscard;
        }

        synchronized boolean owns(T candidate) {
            return target == candidate;
        }

        synchronized T pendingTarget() {
            return completed || discarded ? null : target;
        }

        synchronized boolean discarded() {
            return discarded;
        }

        synchronized void complete() {
            completed = true;
            target = null;
            ownerDiscard = null;
        }

        synchronized Runnable discard() {
            discarded = true;
            Runnable action = completed ? null : ownerDiscard;
            completed = true;
            target = null;
            ownerDiscard = null;
            return action;
        }
    }

    private static final class LastVoiceCommit {
        final long editorEpoch;
        final InputConnection connection;
        final String insertedText;
        final String originalSelection;
        final String rawText;
        final long historyId;
        final String packageName;
        final boolean learningAllowed;
        final String commitId;
        final long voiceGeneration;
        final CommitRecord teachRecord;

        LastVoiceCommit(
                long editorEpoch,
                InputConnection connection,
                String insertedText,
                String originalSelection,
                String rawText,
                long historyId,
                String packageName,
                boolean learningAllowed) {
            this(
                    editorEpoch,
                    connection,
                    insertedText,
                    originalSelection,
                    rawText,
                    historyId,
                    packageName,
                    learningAllowed,
                    "",
                    0L,
                    null);
        }

        LastVoiceCommit(
                long editorEpoch,
                InputConnection connection,
                String insertedText,
                String originalSelection,
                String rawText,
                long historyId,
                String packageName,
                boolean learningAllowed,
                String commitId,
                long voiceGeneration) {
            this(
                    editorEpoch,
                    connection,
                    insertedText,
                    originalSelection,
                    rawText,
                    historyId,
                    packageName,
                    learningAllowed,
                    commitId,
                    voiceGeneration,
                    null);
        }

        LastVoiceCommit(
                long editorEpoch,
                InputConnection connection,
                String insertedText,
                String originalSelection,
                String rawText,
                long historyId,
                String packageName,
                boolean learningAllowed,
                String commitId,
                long voiceGeneration,
                CommitRecord teachRecord) {
            this.editorEpoch = editorEpoch;
            this.connection = connection;
            this.insertedText = insertedText;
            this.originalSelection = originalSelection;
            this.rawText = rawText;
            this.historyId = historyId;
            this.packageName = packageName;
            this.learningAllowed = learningAllowed;
            this.commitId = commitId == null ? "" : commitId;
            this.voiceGeneration = voiceGeneration;
            this.teachRecord = teachRecord;
        }

        boolean transactionBacked() {
            return !commitId.isEmpty();
        }

        LastVoiceCommit withInsertedText(String replacement) {
            return new LastVoiceCommit(
                    editorEpoch,
                    connection,
                    replacement,
                    originalSelection,
                    rawText,
                    historyId,
                    packageName,
                    learningAllowed,
                    commitId,
                    voiceGeneration,
                    teachRecord);
        }
    }

    private VoicePipeline pipeline;
    private VoiceController voiceController;
    private SettingsRepository settingsRepository;
    private AppProfileRepository appProfileRepository;
    private PersonalizationStore personalizationStore;
    private EditorSessionManager editorSessionManager;
    private KeyboardShellRoute keyboardShellRoute;
    private boolean editorSessionShadowHealthy;
    private SecurePreferences draftPreferences;
    private Handler mainHandler;
    private ExecutorService localIo;
    private RimeResourceStore rimeResourceStore;
    private RimeRuntimePreferences rimeRuntimePreferences;
    private RimeUserDataStore rimeUserDataStore;
    private TextView status;
    private TextView transcript;
    private VoicePulseView voicePulse;
    private CenteredIconButton microphone;
    private Button modeButton;
    private CenteredIconButton moreButton;
    private Button holdToTalkButton;
    private Button switchKeyboardButton;
    private Button punctuationButton;
    private Button deleteButton;
    private Button enterButton;
    private LatinKeyboardLayout latinKeyboardLayout;
    private KeyboardInputModeLayout keyboardInputModeLayout;
    private AndroidKeyboardFeedback keyboardFeedback;
    private KeyboardCandidateBar keyboardCandidateBar;
    private KeyboardToolbarLayout keyboardToolbarLayout;
    private KeyboardToolbarPrivacyPolicy.State keyboardToolbarPrivacy =
            restrictedToolbarPrivacy();
    private boolean compactToolbar;
    private KeyboardFieldProfile currentKeyboardFieldProfile = KeyboardFieldProfile.GENERAL;
    private KeyboardEngineSelection keyboardEngineSelection = KeyboardEngineSelection.latinOnly();
    private RimeResourceStore.RuntimePackage availableRimePackage;
    private RimeRuntimeConfig availableRimeConfig;
    private long rimeAvailabilityRequest;
    private boolean currentLearningAllowed;
    private RimeCompositionLease activeRimeLease;
    private boolean holdToTalkActive;
    private boolean preparingVoiceInput;
    private boolean finishingVoiceInput;
    private boolean compactLayout;
    private boolean recoverableDraftLoading = true;
    private boolean recoveringSavedAudio;
    private long discardConfirmationDeadline;
    private String latestPreviewText = "";
    private final RecoverableDraftSlot recoverableDraft = PROCESS_RECOVERABLE_DRAFT;
    private DictationRequest.CaptureMode activeCaptureMode;
    private RecognitionRoute activeRecognitionRoute;
    private volatile CommitTarget detachedTargetAwaitingResult;
    private volatile boolean serviceDestroyed;
    private boolean resourcesClosed;
    private boolean screenOffReceiverRegistered;
    private boolean voiceRestartBlockedByLifecycle;
    private final BroadcastReceiver screenOffReceiver = createScreenOffReceiver(
            this::cancelVoiceForLifecycle);
    private final Runnable pendingDetachedRefresh = () -> {
        if (!serviceDestroyed) renderInputViewState();
    };

    private long editorEpoch;
    // EditorTransactionManager intentionally retains an owner-specific revision high-watermark
    // after finishComposingText so a delayed callback from an earlier Rime composition cannot be
    // replayed. Every independent native Rime session in this service must therefore continue the
    // same monotonic revision space instead of restarting at one.
    private long rimeRevisionHighWatermark;
    private final CompositionCoordinator compositionCoordinator = new CompositionCoordinator();
    private final CompositionConflictPolicy compositionConflictPolicy =
            CompositionConflictPolicy.defaults();
    private EditorInfo currentEditor;
    private FieldKind currentFieldKind = FieldKind.GENERAL;
    private boolean sensitiveField;
    private int currentSelectionStart = -1;
    private int currentSelectionEnd = -1;
    private ProcessingMode selectedMode = ProcessingMode.AUTO;
    private CommitTarget activeTarget;
    private VoiceCompositionSession activeComposition;
    private EditorProjection activeV2Projection;
    private VoiceTransactionSession activeVoiceTransaction;
    private ProjectionDocument latestV2Document;
    private ProjectionMode activeV2ProjectionMode;
    private LastVoiceCommit lastCommit;

    /** Main-thread lease binding native callbacks to one original editor target and coordinator. */
    private static final class RimeCompositionLease {
        final long editorEpoch;
        EditorSessionSnapshot editorSnapshot;
        final long coordinationGeneration;
        final int baseSelectionStart;
        CompositionCoordinator.Observation observation;
        RimeInputController controller;
        long revision;
        int expectedCaret;
        int pendingKeyCommands;
        String preedit = "";
        CandidatePage candidatePage;
        CandidatePage.Selection pendingSelection;
        CandidatePage.PageRequest pendingPageRequest;

        RimeCompositionLease(
                long editorEpoch,
                EditorSessionSnapshot editorSnapshot,
                long coordinationGeneration,
                CompositionCoordinator.Observation observation,
                long initialRevision) {
            this.editorEpoch = editorEpoch;
            this.editorSnapshot = editorSnapshot;
            this.coordinationGeneration = coordinationGeneration;
            this.observation = observation;
            revision = initialRevision;
            baseSelectionStart = Math.min(
                    editorSnapshot.selection().start(), editorSnapshot.selection().end());
            expectedCaret = baseSelectionStart;
        }

        boolean matches(long callbackEditorEpoch, long callbackCoordinationGeneration) {
            return editorEpoch == callbackEditorEpoch
                    && coordinationGeneration == callbackCoordinationGeneration;
        }

        boolean acceptsSelection(int start, int end, int candidatesStart, int candidatesEnd) {
            if (start != expectedCaret || end != expectedCaret) return false;
            return (candidatesStart == -1 && candidatesEnd == -1)
                    || (candidatesStart == baseSelectionStart && candidatesEnd == expectedCaret);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        editorSessionManager = new EditorSessionManager();
        // Freeze exactly one Shell for this service lifetime. Preference changes take effect only
        // after the IME process restarts, so an editor session cannot cross routes or double-write.
        keyboardShellRoute = KeyboardShellConfig.selectedRoute(this);
        editorSessionShadowHealthy = true;
        mainHandler = new Handler(Looper.getMainLooper());
        registerScreenOffReceiver();
        pipeline = new VoicePipeline(this);
        voiceController = RecognitionRouterVoiceConfig.select(
                this,
                new VoicePipelineAdapter(pipeline));
        settingsRepository = new SettingsRepository(this);
        appProfileRepository = new AppProfileRepository(this);
        rimeResourceStore = new RimeResourceStore(this);
        rimeRuntimePreferences = new RimeRuntimePreferences(this);
        rimeUserDataStore = new RimeUserDataStore(this);
        keyboardFeedback = new AndroidKeyboardFeedback(this);
        personalizationStore = new PersonalizationStore(this);
        draftPreferences = new SecurePreferences(this);
        localIo = Executors.newSingleThreadExecutor();
        selectedMode = settingsRepository.loadDefaultMode();
        localIo.execute(() -> {
            AppSettings initialSettings = settingsRepository.load();
            personalizationStore.getReadableDatabase();
            if (initialSettings.recognitionBackend()
                    == com.opentypeless.android.settings.RecognitionBackend.LOCAL_OFFLINE) {
                pipeline.prewarmLocalOffline();
            }
        });
        localIo.execute(() -> {
            synchronized (DRAFT_STORAGE_LOCK) {
                if (!processDraftStorageLoaded) {
                    boolean restored = recoverableDraft.restore(
                            draftPreferences.get(RECOVERABLE_DRAFT_PREFERENCE));
                    if (restored) {
                        PROCESS_RECOVERABLE_AUDIO_ID.set(draftPreferences.get(
                                RECOVERABLE_DRAFT_AUDIO_ID_PREFERENCE));
                    }
                    processDraftStorageLoaded = true;
                }
            }
            postUiIfAlive(() -> {
                recoverableDraftLoading = false;
                if (currentEditor != null) renderInputViewState();
            });
        });
    }

    @Override
    public View onCreateInputView() {
        compactLayout = getResources().getConfiguration().screenWidthDp < 360
                || getResources().getConfiguration().fontScale >= 1.3f;
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        compactToolbar = compactLayout || landscape;
        KeyboardShellFrame shellFrame = KeyboardShellSelector.select(
                keyboardShellRoute,
                () -> KeyboardShellFrame.routeA(this),
                () -> KeyboardShellFrame.legacyVoice(this));
        boolean routeACandidateBar = shellFrame.route() == KeyboardShellRoute.ROUTE_A;
        LinearLayout root = shellFrame.root();
        root.setMinimumHeight(dp(landscape ? 190 : compactLayout ? 252 : 264));
        root.setPadding(dp(8), dp(8), dp(8), dp(10));
        root.setBackgroundResource(R.drawable.ime_panel_background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int navigationBottom = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            view.setPadding(dp(8), dp(8), dp(8), dp(10) + navigationBottom);
            return insets;
        });

        LinearLayout toolbar = shellFrame.toolbar();
        toolbar.setPadding(dp(2), 0, dp(2), dp(4));
        keyboardToolbarLayout = new KeyboardToolbarLayout(this, toolbar);

        voicePulse = new VoicePulseView(this);
        voicePulse.setPhase(VoicePulseView.Phase.IDLE);
        keyboardToolbarLayout.attachStatusIndicator(voicePulse, 30);

        status = new TextView(this);
        status.setText("");
        status.setTextColor(getColor(R.color.ime_on_surface_variant));
        status.setTextSize(12);
        status.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setAutoSizeTextTypeUniformWithConfiguration(
                9, 12, 1, TypedValue.COMPLEX_UNIT_SP);
        status.setPadding(dp(2), 0, dp(4), 0);
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        keyboardToolbarLayout.attachStatusText(status);

        modeButton = key("", getString(R.string.ime_cd_choose_mode), 1f,
                ignored -> showModeMenu());
        keyboardToolbarLayout.attachPrimaryAction("voice.mode", modeButton, 64);
        microphone = key(getString(R.string.ime_key_long_dictation_compact),
                getString(R.string.ime_cd_start_long_dictation), 2f,
                ignored -> toggleRecording(DictationRequest.CaptureMode.CONTINUOUS));
        moreButton = key("", getString(R.string.ime_cd_more), 1f, this::showMoreMenu);
        setCenteredIcon(moreButton, R.drawable.ime_ic_more_vertical);
        keyboardToolbarLayout.attachOverflowAnchor("more", moreButton);
        applyKeyboardToolbarPrivacy();
        refreshModeButton();

        LinearLayout compositionStage = new LinearLayout(this);
        compositionStage.setOrientation(LinearLayout.VERTICAL);
        if (routeACandidateBar) {
            keyboardCandidateBar = new KeyboardCandidateBar(
                    this,
                    new KeyboardCandidateBar.Listener() {
                        @Override
                        public void onCandidateSelected(CandidatePage.Selection selection) {
                            routeRimeCandidateSelection(selection);
                        }

                        @Override
                        public void onPageRequested(CandidatePage.PageRequest request) {
                            routeRimeCandidatePage(request);
                        }
                    });
            keyboardCandidateBar.setPlaintextVisible(currentEditor != null && !sensitiveField);
            compositionStage.addView(keyboardCandidateBar.root(), matchWrap());
        } else {
            keyboardCandidateBar = null;
        }

        transcript = new TextView(this);
        transcript.setText(R.string.ime_transcript_hint);
        transcript.setTextColor(getColor(R.color.ime_on_surface));
        transcript.setTextSize(17);
        transcript.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        transcript.setPadding(dp(12), dp(8), dp(12), dp(8));
        transcript.setMinHeight(dp(76));
        transcript.setMaxLines(2);
        transcript.setTextIsSelectable(true);
        transcript.setBackgroundResource(R.drawable.ime_transcript_background);
        // Ordinary dictation is rendered directly in the host editor through composing text.
        // This compact panel is reserved for selected-text instructions and recoverable drafts,
        // where writing a provisional transcript into the editor would be unsafe.
        transcript.setVisibility(View.GONE);
        transcript.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        LinearLayout.LayoutParams transcriptParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        transcriptParams.setMarginStart(dp(2));
        transcriptParams.setMarginEnd(dp(2));
        transcriptParams.topMargin = dp(2);
        transcriptParams.bottomMargin = dp(2);
        compositionStage.addView(transcript, transcriptParams);
        shellFrame.attachComposition(compositionStage, matchWrap());
        shellFrame.attachToolbar(matchWrap());

        View typing;
        if (shellFrame.route() == KeyboardShellRoute.ROUTE_A) {
            punctuationButton = null;
            latinKeyboardLayout = new LatinKeyboardLayout(
                    this,
                    (label, description, weight, action) -> key(
                            label,
                            description,
                            weight,
                            ignored -> action.run()),
                    new LatinKeyboardLayout.Listener() {
                        @Override
                        public void insertText(String text) {
                            routeTypingText(text);
                        }

                        @Override
                        public void deleteBackward() {
                            routeDeleteBackward();
                        }

                        @Override
                        public void performEnter() {
                            routeKeyboardEnter();
                        }

                        @Override
                        public void switchKeyboard() {
                            OpenTypelessImeService.this.switchKeyboard();
                        }

                        @Override
                        public void showKeyboardPicker() {
                            OpenTypelessImeService.this.showKeyboardPicker();
                        }

                        @Override
                        public void switchInputEngine() {
                            OpenTypelessImeService.this.switchInputEngine();
                        }
                    },
                    keyboardFeedback);
            latinKeyboardLayout.setFieldProfile(currentKeyboardFieldProfile);
            latinKeyboardLayout.setEngineSelection(keyboardEngineSelection);
            switchKeyboardButton = latinKeyboardLayout.switchKeyboardButton();
            holdToTalkButton = null;
            deleteButton = latinKeyboardLayout.deleteButton();
            enterButton = latinKeyboardLayout.enterButton();
            typing = latinKeyboardLayout.root();
        } else {
            latinKeyboardLayout = null;
            LinearLayout legacyTyping = horizontalRow();
            switchKeyboardButton = key(
                    R.string.ime_key_switch_keyboard,
                    R.string.ime_cd_switch_keyboard,
                    1f,
                    ignored -> switchKeyboard());
            addFixed(legacyTyping, switchKeyboardButton, 48);
            punctuationButton = key(
                    R.string.ime_key_punctuation,
                    R.string.ime_cd_punctuation,
                    1f,
                    this::showPunctuationMenu);
            addFixed(legacyTyping, punctuationButton, 48);
            holdToTalkButton = key(
                    voiceLabel(
                            R.string.ime_key_hold_to_talk,
                            R.string.ime_key_hold_to_talk_compact),
                    R.string.ime_cd_space_hold_to_talk,
                    2f,
                    ignored -> insertKeyboardText(" "));
            configureHoldToTalk(holdToTalkButton);
            addWeighted(legacyTyping, holdToTalkButton, 3f);
            deleteButton = key(R.string.ime_key_delete, R.string.ime_cd_delete, 1f,
                    ignored -> deleteKeyboardBackward());
            addFixed(legacyTyping, deleteButton, 48);
            enterButton = key(R.string.ime_key_enter, R.string.ime_cd_enter, 1f,
                    ignored -> performKeyboardEnter());
            addFixed(legacyTyping, enterButton, 48);
            typing = legacyTyping;
        }

        // Keep the primary typing controls in the visual centre. The bottom reserve is an
        // intentional product slot for future actions (for example notes, commands or clipboard)
        // and must not be consumed by an invisible transcript view.
        LinearLayout keyStage = new LinearLayout(this);
        keyStage.setOrientation(LinearLayout.VERTICAL);
        keyStage.setGravity(Gravity.CENTER);
        if (shellFrame.route() == KeyboardShellRoute.ROUTE_A) {
            LinearLayout voicePage = createVoiceInputPage();
            CenteredIconButton inputModeToggle = key(
                    "",
                    getString(R.string.ime_cd_open_keyboard_tab),
                    1f,
                    ignored -> {});
            keyboardInputModeLayout = new KeyboardInputModeLayout(
                    this,
                    inputModeToggle,
                    voicePage,
                    typing,
                    sensitiveField
                            ? KeyboardInputModeLayout.Mode.QWERTY
                            : KeyboardInputModeLayout.Mode.VOICE,
                    mode -> {
                        refreshStatusVisibilityForInputMode(mode);
                        if (mode == KeyboardInputModeLayout.Mode.VOICE
                                && latinKeyboardLayout != null) {
                            latinKeyboardLayout.cancelTransientGestures();
                        }
                    });
            keyboardToolbarLayout.attachPrimaryAction(
                    "input.mode", keyboardInputModeLayout.toggleButton(), 48);
            keyboardInputModeLayout.setVoiceAvailable(!sensitiveField);
            keyStage.addView(keyboardInputModeLayout.root(), matchWrap());
        } else {
            keyboardInputModeLayout = null;
            keyStage.addView(typing, matchWrap());
        }
        // The key stage must size to its rows. Giving a WRAP_CONTENT IME window a weighted
        // zero-height child makes the platform expand it to the full display, which separates
        // the QWERTY rows by large empty areas and obscures the host editor.
        shellFrame.attachKeys(keyStage, matchWrap());
        View extensionReserve = new View(this);
        extensionReserve.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Keep the stable Route-A slot without charging today's keyboard for hypothetical future
        // controls. Real extensions must opt in with their own bounded layout task.
        shellFrame.attachExtensions(extensionReserve, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0));

        refreshEnterKey();
        refreshPostCommitActions();
        applyKeyboardToolbarPrivacy();
        renderInputViewState();
        return root;
    }

    private LinearLayout createVoiceInputPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setMinimumHeight(dp(compactLayout ? 164 : 184));
        page.setPadding(dp(8), dp(4), dp(8), dp(8));

        TextView hint = new TextView(this);
        hint.setText(R.string.ime_voice_tap_hint);
        hint.setTextColor(getColor(R.color.ime_on_surface_variant));
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, dp(10));
        page.addView(hint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        microphone.setBackgroundResource(R.drawable.ime_voice_button_background);
        setCenteredIcon(microphone, R.drawable.ime_ic_microphone);
        microphone.setBackgroundTintList(null);
        microphone.setTextColor(getColor(R.color.ime_on_voice_primary));
        microphone.setMinWidth(dp(148));
        microphone.setMinimumWidth(dp(148));
        microphone.setMinHeight(dp(56));
        microphone.setMinimumHeight(dp(56));
        microphone.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams microphoneParams = new LinearLayout.LayoutParams(
                dp(148), dp(56));
        microphoneParams.gravity = Gravity.CENTER_HORIZONTAL;
        page.addView(microphone, microphoneParams);
        return page;
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        return true;
    }

    private void toggleRecording(DictationRequest.CaptureMode captureMode) {
        if (!screenOffReceiverRegistered) {
            setStatus(R.string.ime_status_lifecycle_guard_unavailable, true);
            return;
        }
        if (sensitiveField) {
            setStatus(R.string.ime_status_sensitive_disabled, true);
            return;
        }
        if (recoverableDraftLoading) {
            setStatus(R.string.ime_status_recoverable_draft_loading, false);
            return;
        }
        if (pendingDetachedTarget() != null) {
            setStatus(R.string.ime_status_previous_voice_finalizing, false);
            return;
        }
        if (voiceController.state() == VoiceController.State.RECORDING) {
            voiceController.stop();
            finishingVoiceInput = true;
            updateMicrophone(VoiceController.State.TRANSCRIBING);
            setStatus(R.string.ime_status_finishing_recording, false);
            return;
        }
        if (voiceController.state() != VoiceController.State.IDLE) {
            setStatus(R.string.ime_status_processing_cancel_hint, true);
            return;
        }
        if (activeTarget != null) {
            setStatus(R.string.ime_status_preparing_cancel_hint, true);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            setStatus(R.string.ime_status_microphone_permission, true);
            openSettings();
            return;
        }

        RimeVoicePreemption rimePreemption = null;
        if (activeRimeLease != null) {
            rimePreemption = preemptRimeForVoice();
            if (rimePreemption == null) return;
        }
        CommitTarget target = captureTarget();
        if (target == null) {
            if (rimePreemption != null) rimePreemption.cancelUnclaimedVoice();
            return;
        }
        VoiceTransactionSession transactionSession = null;
        if (target.transactionWriter) {
            EditorSessionSnapshot snapshot =
                    ((EditorSessionManager.Captured) target.editorSessionCapture).snapshot();
            transactionSession = rimePreemption == null
                    ? VoiceTransactionSession.acquire(
                            target.voiceGeneration, snapshot, compositionCoordinator)
                    : rimePreemption.claimVoiceSession(target.voiceGeneration, snapshot);
            if (transactionSession == null) {
                if (rimePreemption != null) rimePreemption.cancelUnclaimedVoice();
                setStatus(R.string.ime_status_session_active, true);
                return;
            }
        } else if (rimePreemption != null) {
            rimePreemption.cancelUnclaimedVoice();
            setStatus(R.string.ime_status_session_active, true);
            return;
        }
        activeTarget = target;
        preparingVoiceInput = true;
        finishingVoiceInput = false;
        activeCaptureMode = captureMode;
        activeRecognitionRoute = null;
        discardConfirmationDeadline = 0L;
        latestPreviewText = "";
        if (target.transactionWriter) {
            activeVoiceTransaction = transactionSession;
            activeComposition = null;
        } else {
            activeVoiceTransaction = null;
            activeComposition = new VoiceCompositionSession(
                    target.connection,
                    target.selectionStart,
                    target.selectionEnd);
        }
        activeV2Projection = null;
        latestV2Document = null;
        activeV2ProjectionMode = null;
        showPreparingState();
        lastCommit = null;
        refreshPostCommitActions();
        if (target.replacedSelection()) {
            showTranscript(getString(R.string.ime_transcript_preparing));
        } else {
            clearTranscript();
        }
        ProcessingMode requestedMode = selectedMode;
        setStatus(R.string.ime_status_preparing_local_data, false);
        localIo.execute(() -> prepareAndStart(target, requestedMode, captureMode));
    }

    /** A tap inserts a space; holding starts voice input and releasing ends the utterance. */
    @SuppressLint("ClickableViewAccessibility") // ACTION_UP calls performClick for ordinary taps.
    private void configureHoldToTalk(Button space) {
        final boolean[] pressed = {false};
        final boolean[] holdActivated = {false};
        Runnable beginHold = () -> {
            if (!pressed[0]
                    || !voiceStartAllowed()) {
                return;
            }
            holdActivated[0] = true;
            keyboardFeedback.onLongPress(space);
            holdToTalkActive = true;
            toggleRecording(DictationRequest.CaptureMode.HOLD_TO_TALK);
        };
        space.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    pressed[0] = true;
                    holdActivated[0] = false;
                    view.setPressed(true);
                    view.postDelayed(beginHold, ViewConfiguration.getLongPressTimeout());
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    pressed[0] = false;
                    view.removeCallbacks(beginHold);
                    view.setPressed(false);
                    if (holdActivated[0]) {
                        finishHoldToTalk(false);
                    } else {
                        // Preserve Button accessibility/click semantics for an ordinary space tap.
                        view.performClick();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    pressed[0] = false;
                    view.removeCallbacks(beginHold);
                    view.setPressed(false);
                    // Android can synthesize ACTION_CANCEL for window/pointer ownership changes.
                    // It is not an explicit request to discard already visible speech, so finish
                    // the utterance exactly like ACTION_UP once recording has started.
                    if (holdActivated[0]) finishHoldToTalk(true);
                    return true;
                }
                default -> {
                    return true;
                }
            }
        });
    }

    private void finishHoldToTalk(boolean cancelled) {
        holdToTalkActive = false;
        VoiceController.State currentState = voiceController.state();
        switch (holdReleaseAction(
                currentState, activeTarget != null, preparingVoiceInput)) {
            case STOP_AND_COMMIT -> {
                voiceController.stop();
                finishingVoiceInput = true;
                updateMicrophone(VoiceController.State.TRANSCRIBING);
                setStatus(R.string.ime_status_finishing_recording, false);
            }
            case CANCEL_PREPARATION -> cancelPipeline(
                    getString(cancelled
                                    ? R.string.ime_status_cancelled
                                    : R.string.ime_status_hold_too_short),
                    true);
            case WAIT_FOR_RESULT -> {
                // A terminal result may already be queued on the UI thread. Never reinterpret
                // release as discard while that final callback is pending.
            }
        }
    }

    static HoldReleaseAction holdReleaseAction(
            VoiceController.State state,
            boolean hasActiveTarget,
            boolean preparing) {
        // VoicePipeline enters RECORDING before the recognizer has actually opened the
        // microphone. Releasing while that asynchronous preparation is still visible must
        // cancel it instead of asking an unready recorder to finalize an empty utterance.
        if (hasActiveTarget && preparing) {
            return HoldReleaseAction.CANCEL_PREPARATION;
        }
        if (state == VoiceController.State.RECORDING) return HoldReleaseAction.STOP_AND_COMMIT;
        return HoldReleaseAction.WAIT_FOR_RESULT;
    }

    static boolean shouldHandleSpeechReady(
            Object activeTarget,
            Object callbackTarget,
            boolean finishing) {
        return activeTarget != null && activeTarget == callbackTarget && !finishing;
    }

    private void prepareAndStart(
            CommitTarget target,
            ProcessingMode requestedMode,
            DictationRequest.CaptureMode captureMode) {
        try {
            AppSettings settings = settingsRepository.load();
            settings = appProfileRepository.apply(
                    settings,
                    appProfileRepository.get(target.packageName));
            if (!settings.isReady()) {
                postUi(() -> {
                    if (activeTarget != target) return;
                    preparingVoiceInput = false;
                    finishingVoiceInput = false;
                    activeCaptureMode = null;
                    activeTarget = null;
                    discardActiveComposition();
                    updateMicrophone(VoiceController.State.IDLE);
                    clearTranscript();
                    setStatus(R.string.ime_status_configure_backend, true);
                    openSettings();
                });
                return;
            }
            if (settings.recognitionBackend()
                    == com.opentypeless.android.settings.RecognitionBackend.LOCAL_OFFLINE
                    && (!LocalOfflineRecognizer.isSupportedDevice(this)
                    || !LocalOfflineRecognizer.isInstalled(this)
                    || (SpeechCoreV2Config.enabled(this)
                    && !OfflineStreamingRecognizer.isInstalled(this)))) {
                postUi(() -> {
                    if (activeTarget != target) return;
                    preparingVoiceInput = false;
                    finishingVoiceInput = false;
                    activeCaptureMode = null;
                    activeTarget = null;
                    discardActiveComposition();
                    updateMicrophone(VoiceController.State.IDLE);
                    clearTranscript();
                    setStatus(R.string.ime_status_offline_model_missing, true);
                    openSettings();
                });
                return;
            }
            boolean sendContext = settings.sendContext() && target.learningAllowed;
            InputContext context = new InputContext(
                    target.packageName,
                    target.fieldKind,
                    target.selectedText,
                    sendContext ? target.precedingContext : "",
                    target.learningAllowed);
            PersonalizationSnapshot snapshot = settings.personalizationEnabled()
                    ? personalizationStore.snapshot(target.packageName)
                    : PersonalizationSnapshot.empty();
            DictationRequest request = new DictationRequest(
                    settings,
                    requestedMode,
                    context,
                    snapshot,
                    captureMode);
            postUi(() -> {
                if (activeTarget != target) return;
                if (!targetStillValid(target)) {
                    preparingVoiceInput = false;
                    finishingVoiceInput = false;
                    activeCaptureMode = null;
                    activeTarget = null;
                    discardActiveComposition();
                    updateMicrophone(VoiceController.State.IDLE);
                    clearTranscript();
                    setStatus(R.string.ime_status_target_changed_cancelled, true);
                    return;
                }
                replaceRecoverableVoiceForNewRecording();
                boolean started = voiceController.start(request, listenerFor(target));
                if (!started) {
                    preparingVoiceInput = false;
                    finishingVoiceInput = false;
                    activeCaptureMode = null;
                    activeTarget = null;
                    discardActiveComposition();
                    updateMicrophone(VoiceController.State.IDLE);
                    clearTranscript();
                    setStatus(R.string.ime_status_session_active, true);
                }
            });
        } catch (RuntimeException error) {
            postUi(() -> {
                if (activeTarget != target) return;
                preparingVoiceInput = false;
                finishingVoiceInput = false;
                activeCaptureMode = null;
                activeTarget = null;
                discardActiveComposition();
                updateMicrophone(VoiceController.State.IDLE);
                clearTranscript();
                setStatus(safeMessage(error.getMessage()), true);
            });
        }
    }

    private VoiceController.Events listenerFor(CommitTarget target) {
        return new VoiceController.Events() {
            @Override
            public void onRoute(RecognitionRoute route) {
                if (target.voiceTerminal()) return;
                postUi(() -> {
                    if (shouldDispatchVoiceCallback(
                            activeTarget, target, target.voiceTerminal())) {
                        activeRecognitionRoute = route;
                    }
                });
            }

            @Override
            public void onState(VoiceController.State state, String message) {
                if (target.voiceTerminal()) return;
                postUi(() -> {
                    if (!shouldDispatchVoiceCallback(
                            activeTarget, target, target.voiceTerminal())) return;
                    if (state == VoiceController.State.RECORDING && preparingVoiceInput) {
                        // start() publishes RECORDING when work is dispatched. The user-facing
                        // listening state begins only after onReadyForSpeech confirms that the
                        // microphone is really available.
                        if (!finishingVoiceInput) {
                            setStatus(R.string.ime_status_preparing_microphone, false);
                            showPreparingState();
                        }
                        return;
                    }
                    preparingVoiceInput = false;
                    setStatus(localizedPipelineStatus(state), false);
                    updateMicrophone(state);
                });
            }

            @Override
            public void onReadyForSpeech() {
                if (target.voiceTerminal()) return;
                postUi(() -> {
                    if (!shouldDispatchVoiceCallback(
                            activeTarget, target, target.voiceTerminal())) return;
                    if (!shouldHandleSpeechReady(
                            activeTarget, target, finishingVoiceInput)) return;
                    if (target.transactionWriter) {
                        VoiceTransactionSession session = activeVoiceTransaction;
                        if (session == null || !session.markReady(target.voiceGeneration)) {
                            failVoiceTransaction(target, latestPreviewText);
                            return;
                        }
                    }
                    preparingVoiceInput = false;
                    setStatus(R.string.ime_status_listening, false);
                    updateMicrophone(VoiceController.State.RECORDING);
                    if (target.replacedSelection() && latestPreviewText.isBlank()) {
                        showTranscript(getString(R.string.ime_transcript_listening));
                    }
                    View readyControl = activeCaptureMode
                            == DictationRequest.CaptureMode.HOLD_TO_TALK
                            ? holdToTalkButton
                            : microphone;
                    if (readyControl != null) {
                        readyControl.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                    scheduleNoPartialHint(target);
                });
            }

            @Override
            public void onTranscript(TranscriptUpdate update) {
                if (target.voiceTerminal()) return;
                postUi(() -> applyTranscriptUpdate(target, update));
            }

            @Override
            public void onResult(DictationResult result) {
                if (!target.markVoiceTerminal()) return;
                postUi(() -> {
                    if (runIfCurrent(activeTarget, target, () -> {
                        preparingVoiceInput = false;
                        finishingVoiceInput = false;
                        activeCaptureMode = null;
                        commitResult(target, result);
                    })) return;
                    if (detachedTargetAwaitingResult == target) {
                        handleDetachedResult(target, result);
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (!target.markVoiceTerminal()) return;
                postUi(() -> {
                    if (activeTarget == target) {
                        preparingVoiceInput = false;
                        finishingVoiceInput = false;
                        activeCaptureMode = null;
                        boolean preserved = preserveActiveDraft();
                        activeTarget = null;
                        updateMicrophone(VoiceController.State.IDLE);
                        showRecoverableDraftOrClear();
                        if (!preserved) {
                            setStatus(R.string.ime_status_composition_preserve_uncertain, true);
                        } else if (com.opentypeless.android.recognition.SystemSpeechRecognizer
                                .MICROPHONE_ACCESS_BLOCKED.equals(message)) {
                            setStatus(R.string.ime_status_system_microphone_blocked, true);
                        } else {
                            setStatus(safeMessage(message), true);
                        }
                        return;
                    }
                    if (detachedTargetAwaitingResult == target) {
                        handleDetachedError(message);
                    }
                });
            }
        };
    }

    private void handleDetachedResult(CommitTarget target, DictationResult result) {
        if (detachedTargetAwaitingResult != target) return;
        if (serviceDestroyed) return;
        PendingDetachedSession<CommitTarget> pending = detachedSessionFor(target);
        detachedTargetAwaitingResult = null;
        if (pending != null && pending.discarded()) {
            clearDetachedSession(pending);
            return;
        }
        VoiceResult voiceResult = result.voiceResult();
        String finalText = voiceResult.finalText();
        boolean selectionPreserved = target.replacedSelection();
        boolean saved = !selectionPreserved
                && !finalText.isBlank()
                && saveRecoverableDraftFromResult(target, finalText, result);
        completeDetachedSession(pending, target);
        if (activeTarget == null) {
            holdToTalkActive = false;
            preparingVoiceInput = false;
            finishingVoiceInput = false;
            activeCaptureMode = null;
            if (sensitiveField) {
                renderInputViewState();
                return;
            }
            updateMicrophone(VoiceController.State.IDLE);
            showRecoverableDraftOrClear();
            setStatus(selectionPreserved
                    ? R.string.ime_status_detached_selection_preserved
                    : saved
                    ? R.string.ime_status_detached_final_recoverable
                    : recoverableDraft.hasDraft()
                    ? R.string.ime_status_recoverable_draft_conflict
                    : R.string.ime_status_empty_final_partial_preserved, !saved);
        }
    }

    private void handleDetachedError(String message) {
        if (serviceDestroyed) return;
        CommitTarget target = detachedTargetAwaitingResult;
        PendingDetachedSession<CommitTarget> pending = detachedSessionFor(target);
        detachedTargetAwaitingResult = null;
        if (pending != null && pending.discarded()) {
            clearDetachedSession(pending);
            return;
        }
        completeDetachedSession(pending, target);
        if (activeTarget != null) return;
        holdToTalkActive = false;
        preparingVoiceInput = false;
        finishingVoiceInput = false;
        activeCaptureMode = null;
        if (sensitiveField) {
            renderInputViewState();
            return;
        }
        updateMicrophone(VoiceController.State.IDLE);
        showRecoverableDraftOrClear();
        setStatus(recoverableDraft.hasDraft()
                ? getString(R.string.ime_status_detached_partial_recoverable)
                : safeMessage(message), true);
    }

    private void applyTranscriptUpdate(CommitTarget target, TranscriptUpdate update) {
        if (!shouldDispatchVoiceCallback(activeTarget, target, target.voiceTerminal())
                || update == null) return;
        String text = update.text().trim();
        if (text.isEmpty()) return;
        latestPreviewText = text;
        setStatus(R.string.ime_status_listening, false);
        if (target.transactionWriter) {
            applyVoiceTransactionUpdate(target, update, text);
            return;
        }
        if (update.source() == TranscriptUpdate.Source.SPEECH_CORE_V2
                && !target.replacedSelection()) {
            applySpeechCoreProjection(target, update);
            return;
        }
        VoiceCompositionSession composition = activeComposition;
        if (target.replacedSelection()) {
            // A spoken edit instruction must never overwrite the selected source text before the
            // requested transform has passed the integrity guard.
            showTranscript(compact(text, 180));
            return;
        }
        if (composition == null || !composition.enabled()) {
            clearTranscript();
            setStatus(R.string.ime_status_live_composition_fallback, true);
            return;
        }
        if (!targetStillValid(target)) {
            stopPipelinePreservingDraft(
                    getString(R.string.ime_status_target_changed_preserved), true);
            return;
        }
        VoiceCompositionSession.ApplyResult result = composition.apply(update);
        if (result == VoiceCompositionSession.ApplyResult.APPLIED
                || result == VoiceCompositionSession.ApplyResult.UNCHANGED) {
            clearTranscript();
        }
        if (result == VoiceCompositionSession.ApplyResult.REJECTED
                || result == VoiceCompositionSession.ApplyResult.CONNECTION_ERROR) {
            composition.disableLiveUpdates();
            clearTranscript();
            setStatus(R.string.ime_status_live_composition_fallback, true);
        }
    }

    private void applyVoiceTransactionUpdate(
            CommitTarget target, TranscriptUpdate update, String text) {
        VoiceTransactionSession session = activeVoiceTransaction;
        if (session == null
                || target.voiceTerminal()
                || !session.acceptsPartial(target.voiceGeneration, update.sequence())) {
            return;
        }
        if (target.replacedSelection()) {
            // Selected source text remains untouched until the terminal transform is known.
            session.recordIgnoredSequence(update.sequence());
            showTranscript(compact(text, 180));
            return;
        }
        if (text.equals(session.compositionText)) {
            session.recordIgnoredSequence(update.sequence());
            clearTranscript();
            return;
        }

        long revision;
        try {
            // Register the expected selection before the framework write. OEMs may synchronously
            // call onUpdateSelection from setComposingText.
            revision = session.prepareComposition(update.sequence(), text);
        } catch (RuntimeException exhausted) {
            failVoiceTransaction(target, text);
            return;
        }

        EditorTransactionResult result;
        try {
            result = editorSessionManager.setVoiceComposition(
                    this, session.snapshot, text, revision);
        } catch (RuntimeException unavailable) {
            disableEditorSessionShadow();
            failVoiceTransaction(target, text);
            return;
        }
        if (!(result instanceof EditorTransactionResult.Applied)) {
            failVoiceTransaction(target, text);
            return;
        }

        EditorSessionSnapshot captured = captureCurrentTransactionSnapshot();
        if (captured == null) {
            failVoiceTransaction(target, text);
            return;
        }
        session.completeComposition(captured);
        clearTranscript();
    }

    private void failVoiceTransaction(CommitTarget target, String recoverableText) {
        VoiceTransactionSession session = activeVoiceTransaction;
        if (recoverableText != null && !recoverableText.isBlank()) {
            saveRecoverableDraft(target, recoverableText);
        }
        voiceController.cancel();
        holdToTalkActive = false;
        preparingVoiceInput = false;
        finishingVoiceInput = false;
        activeCaptureMode = null;
        boolean cleaned = session == null || cancelVoiceTransaction(session);
        if (activeTarget == target) activeTarget = null;
        updateMicrophone(VoiceController.State.IDLE);
        showRecoverableDraftOrClear();
        setStatus(cleaned
                ? R.string.ime_status_live_composition_fallback
                : R.string.ime_status_composition_cleanup_failed, true);
    }

    private void applySpeechCoreProjection(CommitTarget target, TranscriptUpdate update) {
        EditorProjection projection = activeV2Projection;
        if (projection == null) {
            try {
                ProjectionMode mode = activeCaptureMode == DictationRequest.CaptureMode.CONTINUOUS
                        ? ProjectionMode.LONG_DICTATION
                        : ProjectionMode.SHORT_DICTATION;
                projection = EditorProjection.capture(
                        new AndroidInputConnectionAdapter(
                                target.connection,
                                () -> currentProjectionContext(target)),
                        mode);
                activeV2Projection = projection;
                activeV2ProjectionMode = mode;
                VoiceCompositionSession legacy = activeComposition;
                activeComposition = null;
                if (legacy != null) legacy.discardState();
            } catch (RuntimeException error) {
                // A provider that withholds a trustworthy cursor snapshot cannot receive v2
                // automatic writes. Keep the complete draft recoverable instead of guessing.
                saveRecoverableDraft(target, update.text());
                latestV2Document = null;
                setStatus(R.string.ime_status_live_composition_fallback, true);
                if (voiceController.state() == VoiceController.State.RECORDING) voiceController.stop();
                return;
            }
        }
        ProjectionDocument document = activeV2ProjectionMode == ProjectionMode.LONG_DICTATION
                ? new ProjectionDocument(update.stableText(), update.unstableText())
                : ProjectionDocument.shortDraft(update.text());
        latestV2Document = document;
        ProjectionResult result = projection.project(document);
        if (result.outcome() == ProjectionOutcome.APPLIED
                || result.outcome() == ProjectionOutcome.UNCHANGED) {
            clearTranscript();
            return;
        }
        String recoverable = result.recoverableText().orElse(document.fullText());
        if (!recoverable.isBlank()) saveRecoverableDraft(target, recoverable);
        showRecoverableDraftOrClear();
        setStatus(result.mutationUncertain()
                ? R.string.ime_status_composition_preserve_uncertain
                : R.string.ime_status_live_composition_fallback, true);
        if (voiceController.state() == VoiceController.State.RECORDING) {
            voiceController.stop();
            finishingVoiceInput = true;
            updateMicrophone(VoiceController.State.TRANSCRIBING);
        }
    }

    private ProjectionContext currentProjectionContext(CommitTarget target) {
        EditorInfo editor = currentEditor;
        int selectionStart = currentSelectionStart;
        int selectionEnd = currentSelectionEnd;
        try {
            ExtractedText extracted = target.connection.getExtractedText(
                    new ExtractedTextRequest(), 0);
            if (extracted != null
                    && extracted.selectionStart >= 0
                    && extracted.selectionEnd >= 0) {
                selectionStart = extracted.selectionStart;
                selectionEnd = extracted.selectionEnd;
            }
        } catch (RuntimeException ignored) {
            // Some editors do not implement extracted text. The latest selection callback remains
            // a valid fallback; unknown/stale coordinates still fail closed in ProjectionTarget.
        }
        return new ProjectionContext(
                editorEpoch,
                editor == null ? target.packageName : safe(editor.packageName),
                editor == null ? target.fieldId : editor.fieldId,
                selectionStart,
                selectionEnd,
                sensitiveField);
    }

    private void scheduleNoPartialHint(CommitTarget target) {
        if (mainHandler == null) return;
        mainHandler.postDelayed(() -> {
            if (serviceDestroyed
                    || activeTarget != target
                    || voiceController.state() != VoiceController.State.RECORDING
                    || !latestPreviewText.isBlank()) {
                return;
            }
            RecognitionRoute route = activeRecognitionRoute;
            if (route == null) {
                setStatus(R.string.ime_status_waiting_for_live_text, false);
                return;
            }
            int message = switch (route.actualBackend()) {
                case DASHSCOPE_STREAMING -> R.string.ime_status_waiting_for_live_text;
                case SYSTEM_DEFAULT, SYSTEM_ON_DEVICE ->
                        R.string.ime_status_system_no_live_text_yet;
                case LOCAL_OFFLINE -> OfflineStreamingRecognizer.isInstalled(this)
                        ? R.string.ime_status_waiting_for_live_text
                        : R.string.ime_status_backend_final_only;
                case OPENAI_COMPATIBLE -> R.string.ime_status_backend_final_only;
            };
            setStatus(message, false);
        }, NO_PARTIAL_HINT_DELAY_MILLIS);
    }

    private CommitTarget captureTarget() {
        try {
            return captureTargetUnchecked();
        } catch (RuntimeException ignored) {
            setStatus(R.string.ime_status_field_unavailable, true);
            return null;
        }
    }

    private CommitTarget captureTargetUnchecked() {
        InputConnection connection = getCurrentInputConnection();
        EditorInfo editor = currentEditor;
        if (connection == null || editor == null) {
            setStatus(R.string.ime_status_no_active_field, true);
            return null;
        }
        if (sensitiveField) {
            // Keep the shadow adapter's hard rule observable in the legacy path too: sensitive
            // sessions must be rejected before any selected/surrounding text read occurs.
            setStatus(R.string.ime_status_sensitive_disabled, true);
            return null;
        }
        EditorEvidenceReader.SelectionResult observed = EditorEvidenceReader.readSelectionOnce(
                connection,
                false,
                currentSelectionStart < 0 || currentSelectionEnd < 0);
        if (!(observed instanceof EditorEvidenceReader.SelectionEvidence evidence)) {
            setStatus(R.string.ime_status_field_unavailable, true);
            return null;
        }
        String selectedText = evidence.selectedText();
        CharSequence selected = evidence.selectedTextAvailable() ? selectedText : null;
        SelectionEvidence selection = resolveSelectionEvidence(
                currentSelectionStart,
                currentSelectionEnd,
                selected,
                evidence.extractedSelectionStart(),
                evidence.extractedSelectionEnd(),
                evidence.extractedTextAvailable());
        switch (selectionCaptureDecision(selection, MAX_SELECTION_CODE_POINTS)) {
            case UNKNOWN -> {
                setStatus(R.string.ime_status_selection_unknown, true);
                return null;
            }
            case UNAVAILABLE -> {
                setStatus(R.string.ime_status_selection_unavailable, true);
                return null;
            }
            case TOO_LONG -> {
                setStatus(R.string.ime_status_selection_too_long, true);
                return null;
            }
            case ACCEPT -> {
                // Only accepted selection evidence may proceed to surrounding-context reads.
            }
        }
        selectedText = selection.text();
        currentSelectionStart = selection.start();
        currentSelectionEnd = selection.end();
        shadowSelectionChanged(selection.start(), selection.end());
        boolean learningAllowed = (editor.imeOptions
                & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
        EditorEvidenceReader.SurroundingResult surroundingResult =
                EditorEvidenceReader.readSurroundingOnce(
                        connection, CONTEXT_CHAR_LIMIT, FINGERPRINT_CODE_POINTS);
        if (!(surroundingResult instanceof EditorEvidenceReader.SurroundingEvidence surrounding)) {
            setStatus(R.string.ime_status_field_unavailable, true);
            return null;
        }
        // All legacy target fields are frozen in their original read/materialization order. Shadow
        // capture consumes only frozen strings, so mutable OEM CharSequences cannot perturb them.
        EditorSessionManager.CaptureResult editorSessionCapture = shadowCaptureEditorSession(
                connection,
                selectedText,
                surrounding.shadowBeforeText(),
                surrounding.shadowAfterText());
        boolean transactionWriter = VoiceEditorTransactionConfig.enabled(this);
        if (transactionWriter
                && !(editorSessionCapture instanceof EditorSessionManager.Captured)) {
            setStatus(R.string.ime_status_field_unavailable, true);
            return null;
        }
        long voiceGeneration = transactionWriter ? nextVoiceTransactionGeneration() : 0L;
        return new CommitTarget(
                editorEpoch,
                transactionWriter ? null : connection,
                safe(editor.packageName),
                editor.fieldId,
                currentFieldKind,
                selectedText,
                surrounding.beforeFingerprint(),
                surrounding.afterFingerprint(),
                surrounding.precedingContext(),
                learningAllowed,
                selection.start(),
                selection.end(),
                transactionWriter,
                voiceGeneration,
                editorSessionCapture);
    }

    private static long nextVoiceTransactionGeneration() {
        while (true) {
            long current = VOICE_TRANSACTION_GENERATION.get();
            if (current == Long.MAX_VALUE) {
                throw new IllegalStateException("Voice transaction generation exhausted");
            }
            long next = current + 1L;
            if (VOICE_TRANSACTION_GENERATION.compareAndSet(current, next)) return next;
        }
    }

    static SelectionEvidence resolveSelectionEvidence(
            int reportedStart,
            int reportedEnd,
            CharSequence selected,
            int extractedStart,
            int extractedEnd,
            boolean extractedAvailable) {
        String text = selected == null ? "" : selected.toString();
        boolean reportedKnown = reportedStart >= 0 && reportedEnd >= 0;
        boolean extractedKnown = extractedAvailable && extractedStart >= 0 && extractedEnd >= 0;
        int start = reportedKnown ? reportedStart : extractedKnown ? extractedStart : -1;
        int end = reportedKnown ? reportedEnd : extractedKnown ? extractedEnd : -1;
        if (start >= 0 && end >= 0) {
            boolean hasSelection = start != end;
            boolean textAvailable = !hasSelection || selected != null && !text.isEmpty();
            return new SelectionEvidence(
                    true, hasSelection, textAvailable, start, end, text);
        }
        if (selected != null && !text.isEmpty()) {
            return new SelectionEvidence(
                    true, true, true, -1, -1, text);
        }
        return new SelectionEvidence(false, false, false, -1, -1, "");
    }

    static SelectionCaptureDecision selectionCaptureDecision(
            SelectionEvidence evidence, int maximumCodePoints) {
        if (evidence == null || !evidence.known()) return SelectionCaptureDecision.UNKNOWN;
        if (evidence.hasSelection() && !evidence.selectedTextAvailable()) {
            return SelectionCaptureDecision.UNAVAILABLE;
        }
        if (maximumCodePoints < 0) throw new IllegalArgumentException("maximum must be >= 0");
        return codePointCount(evidence.text()) > maximumCodePoints
                ? SelectionCaptureDecision.TOO_LONG
                : SelectionCaptureDecision.ACCEPT;
    }

    /** Prevents a queued callback from an old editor session from mutating the new session's UI. */
    static boolean runIfCurrent(Object current, Object expected, Runnable callback) {
        if (current != expected) return false;
        callback.run();
        return true;
    }

    static boolean shouldDispatchVoiceCallback(
            Object current, Object expected, boolean terminal) {
        return !terminal && current != null && current == expected;
    }

    private void commitResult(CommitTarget target, DictationResult result) {
        if (activeTarget != target) return;
        if (target.transactionWriter) {
            commitVoiceTransactionResult(target, result);
            return;
        }
        if (activeV2Projection != null) {
            commitSpeechCoreV2Result(target, result);
            return;
        }
        VoiceResult voiceResult = result.voiceResult();
        if (!targetStillValid(target)) {
            preserveActiveDraft();
            if (!target.replacedSelection()
                    && !voiceResult.finalText().isBlank()) {
                saveRecoverableDraftFromResult(target, voiceResult.finalText(), result);
            }
            finishActiveUiSession(target);
            showRecoverableDraftOrClear();
            setStatus(R.string.ime_status_target_changed_preserved, true);
            return;
        }

        String finalText = voiceResult.finalText();
        if (finalText.isBlank()) {
            boolean preserved = preserveActiveDraft();
            finishActiveUiSession(target);
            showRecoverableDraftOrClear();
            setStatus(preserved
                    ? R.string.ime_status_empty_final_partial_preserved
                    : R.string.ime_status_composition_preserve_uncertain, true);
            return;
        }
        VoiceCompositionSession composition = activeComposition;
        boolean hadEditorDraft = composition != null && composition.ownsComposition();
        EditorMutationResult mutation;
        if (!target.replacedSelection()
                && composition != null
                && composition.ownsComposition()) {
            mutation = composition.commitFinal(finalText)
                    ? EditorMutationResult.APPLIED
                    : EditorMutationResult.COMMIT_REJECTED;
        } else {
            mutation = guardedReplace(target.connection, 0, finalText, "");
        }
        boolean partialPreserved = true;
        if (mutation != EditorMutationResult.APPLIED && hadEditorDraft) {
            partialPreserved = composition.preserve();
        }
        boolean finalRecoverable = mutation != EditorMutationResult.APPLIED
                && !target.replacedSelection()
                && saveRecoverableDraftFromResult(target, finalText, result);
        finishActiveUiSession(target);
        discardActiveComposition();
        if (mutation != EditorMutationResult.APPLIED) {
            showRecoverableDraftOrClear();
            setStatus(finalRecoverable
                    ? R.string.ime_status_result_recoverable
                    : !partialPreserved
                    ? R.string.ime_status_composition_preserve_uncertain
                    : mutation == EditorMutationResult.CONNECTION_ERROR
                    ? R.string.ime_status_result_connection_failed
                    : R.string.ime_status_result_rejected, true);
            return;
        }
        acceptSuccessfulCommit(target, result, finalText);
    }

    private void commitVoiceTransactionResult(CommitTarget target, DictationResult result) {
        VoiceTransactionSession session = activeVoiceTransaction;
        if (session == null) return;
        VoiceResult voiceResult = result.voiceResult();
        String finalText = voiceResult.finalText();
        if (!session.beginTerminal(target.voiceGeneration)) {
            transactionFinalFailed(target, session, finalText);
            return;
        }
        if (finalText.isBlank()) {
            preserveVoiceTransactionDraft(target, session, latestPreviewText);
            finishActiveUiSession(target);
            showRecoverableDraftOrClear();
            setStatus(R.string.ime_status_empty_final_partial_preserved, true);
            return;
        }

        CommitRecord.RawTranscript rawTranscript;
        try {
            String raw = voiceResult.rawText();
            rawTranscript = raw.isBlank()
                    ? new CommitRecord.RawTranscript.Absent()
                    : new CommitRecord.RawTranscript.Present(raw);
        } catch (RuntimeException invalidRaw) {
            transactionFinalFailed(target, session, finalText);
            return;
        }

        TransactionReceipt receipt;
        try {
            if (target.replacedSelection() || !session.compositionActive) {
                session.prepareFinalSelection(finalText);
                if (!session.beginFinalizing()) {
                    transactionFinalFailed(target, session, finalText);
                    return;
                }
                receipt = editorSessionManager.commitVoiceText(
                        this, session.snapshot, finalText, rawTranscript);
            } else {
                if (!finalText.equals(session.compositionText)) {
                    long revision = session.prepareComposition(
                            session.latestSequence, finalText);
                    EditorTransactionResult set = editorSessionManager.setVoiceComposition(
                            this, session.snapshot, finalText, revision);
                    if (!(set instanceof EditorTransactionResult.Applied)) {
                        transactionFinalFailed(target, session, finalText);
                        return;
                    }
                    EditorSessionSnapshot captured = captureCurrentTransactionSnapshot();
                    if (captured == null) {
                        transactionFinalFailed(target, session, finalText);
                        return;
                    }
                    session.completeComposition(captured);
                }
                if (!session.beginFinalizing()) {
                    transactionFinalFailed(target, session, finalText);
                    return;
                }
                receipt = editorSessionManager.commitVoiceComposition(
                        this, session.snapshot, session.revision, rawTranscript);
            }
        } catch (RuntimeException unavailable) {
            disableEditorSessionShadow();
            transactionFinalFailed(target, session, finalText);
            return;
        }

        if (!(receipt instanceof TransactionReceipt.Committed committed)) {
            transactionFinalFailed(target, session, finalText);
            return;
        }
        boolean coordinatorReleased = session.completeCoordinatorAfterCommit();
        finishActiveUiSession(target);
        if (coordinatorReleased) clearVoiceTransactionState();
        acceptSuccessfulTransactionCommit(target, result, committed.record());
        if (!coordinatorReleased) {
            setStatus(R.string.ime_status_composition_cleanup_failed, true);
        }
    }

    private void transactionFinalFailed(
            CommitTarget target, VoiceTransactionSession session, String finalText) {
        preserveVoiceTransactionDraft(target, session, finalText);
        finishActiveUiSession(target);
        showRecoverableDraftOrClear();
        setStatus(R.string.ime_status_result_recoverable, true);
    }

    private void acceptSuccessfulTransactionCommit(
            CommitTarget target, DictationResult result, CommitRecord record) {
        pipeline.acknowledgeRecovery(result.recoveryId());
        String raw = record.rawTranscript() instanceof CommitRecord.RawTranscript.Present present
                ? present.text()
                : "";
        lastCommit = new LastVoiceCommit(
                target.editorEpoch,
                null,
                record.insertedText(),
                record.originalSession().selectedText(),
                raw,
                -1L,
                record.originalSession().packageName(),
                record.originalSession().learningAllowed(),
                record.commitId(),
                target.voiceGeneration,
                record);
        refreshPostCommitActions();
        latestPreviewText = "";
        clearTranscript();
        setStatus(
                result.recoveredPartial()
                        ? getString(R.string.ime_status_inserted_recovered_partial)
                        : localizedResultStatus(result.outcome()),
                result.outcome() == DictationResult.Outcome.AI_BLOCKED_EXACT);
        localIo.execute(() -> persistSuccessfulResult(target, result));
    }

    /** Finalizes the exact document that v2 has already projected into the host editor. */
    private void commitSpeechCoreV2Result(CommitTarget target, DictationResult result) {
        EditorProjection projection = activeV2Projection;
        if (projection == null) return;
        String finalText = result.voiceResult().finalText().trim();
        if (finalText.isEmpty()) {
            boolean preserved = preserveActiveDraft();
            finishActiveUiSession(target);
            showRecoverableDraftOrClear();
            setStatus(preserved
                    ? R.string.ime_status_empty_final_partial_preserved
                    : R.string.ime_status_composition_preserve_uncertain, true);
            return;
        }

        ProjectionDocument finalDocument = finalProjectionDocument(finalText);
        if (finalDocument == null) {
            // Long mode may have safely sealed earlier segments. A later global rewrite must never
            // rewrite those committed bytes speculatively. Freeze what is proven and retain the
            // authoritative final as an explicit recovery draft.
            projection.freeze();
            boolean saved = saveRecoverableDraftFromResult(target, finalText, result);
            clearSpeechCoreProjectionState();
            finishActiveUiSession(target);
            showRecoverableDraftOrClear();
            setStatus(saved
                    ? R.string.ime_status_result_recoverable
                    : R.string.ime_status_composition_preserve_uncertain, true);
            return;
        }

        ProjectionResult projected = projection.finish(finalDocument);
        boolean committed = projected.outcome() == ProjectionOutcome.COMMITTED;
        boolean saved = false;
        if (!committed) {
            String recoverable = projected.recoverableText().orElse(finalText);
            if (recoverable.isBlank()) recoverable = finalText;
            saved = saveRecoverableDraftFromResult(target, recoverable, result);
        }
        clearSpeechCoreProjectionState();
        finishActiveUiSession(target);
        if (!committed) {
            showRecoverableDraftOrClear();
            setStatus(saved
                    ? R.string.ime_status_result_recoverable
                    : projected.mutationUncertain()
                    ? R.string.ime_status_composition_preserve_uncertain
                    : R.string.ime_status_result_rejected, true);
            return;
        }
        acceptSuccessfulCommit(target, result, finalText);
    }

    private ProjectionDocument finalProjectionDocument(String finalText) {
        return finalProjectionDocument(activeV2ProjectionMode, latestV2Document, finalText);
    }

    static ProjectionDocument finalProjectionDocument(
            ProjectionMode mode, ProjectionDocument latest, String finalText) {
        if (mode != ProjectionMode.LONG_DICTATION) {
            return ProjectionDocument.shortDraft(finalText);
        }
        String sealed = latest == null ? "" : latest.sealedPrefix();
        if (!finalText.startsWith(sealed)) return null;
        return new ProjectionDocument(sealed, finalText.substring(sealed.length()));
    }

    private void acceptSuccessfulCommit(
            CommitTarget target, DictationResult result, String finalText) {
        VoiceResult voiceResult = result.voiceResult();
        pipeline.acknowledgeRecovery(result.recoveryId());
        lastCommit = new LastVoiceCommit(
                target.editorEpoch,
                target.connection,
                finalText,
                target.selectedText,
                voiceResult.rawText(),
                -1L,
                target.packageName,
                target.learningAllowed);
        refreshPostCommitActions();
        latestPreviewText = "";
        clearTranscript();
        setStatus(
                result.recoveredPartial()
                        ? getString(R.string.ime_status_inserted_recovered_partial)
                        : localizedResultStatus(result.outcome()),
                result.outcome() == DictationResult.Outcome.AI_BLOCKED_EXACT);
        localIo.execute(() -> persistSuccessfulResult(target, result));
    }

    private void finishActiveUiSession(CommitTarget target) {
        if (activeTarget == target) activeTarget = null;
        holdToTalkActive = false;
        preparingVoiceInput = false;
        finishingVoiceInput = false;
        activeCaptureMode = null;
        updateMicrophone(VoiceController.State.IDLE);
    }

    private long persistSuccessfulResult(CommitTarget target, DictationResult result) {
        if (!target.learningAllowed || sensitiveField) return -1L;
        VoiceResult voiceResult = result.voiceResult();
        try {
            personalizationStore.markTermsUsed(result.matchedTermIds());
            personalizationStore.markCorrectionsUsed(result.matchedCorrectionIds());
            List<String> descriptions = personalizationStore.describeMatches(
                    result.matchedTermIds(), result.matchedCorrectionIds());
            String appliedRules = String.join(" · ", descriptions);
            if (!appliedRules.isBlank()) {
                String visibleEvidence = compact(appliedRules, 160);
                postUiIfAlive(() -> {
                    LastVoiceCommit commit = lastCommit;
                    if (commit != null
                            && (target.transactionWriter
                                    ? commit.voiceGeneration == target.voiceGeneration
                                    : commit.connection == target.connection)
                            && commit.insertedText.equals(voiceResult.finalText())
                            && activeTarget == null) {
                        setStatus(getString(
                                R.string.ime_status_personalization_applied,
                                visibleEvidence), false);
                    }
                });
            }
            if (!settingsRepository.loadHistoryEnabled()) return -1L;
            return personalizationStore.addHistory(new HistoryEntry(
                    0L,
                    System.currentTimeMillis(),
                    target.packageName,
                    target.fieldKind.name(),
                    result.mode().name(),
                    result.backend().name(),
                    voiceResult.rawText(),
                    voiceResult.finalText(),
                    result.durationMs(),
                    appliedRules));
        } catch (RuntimeException ignored) {
            // Insertion is already complete. Local analytics must never make typing fail.
            return -1L;
        }
    }

    private boolean targetStillValid(CommitTarget target) {
        try {
            return targetStillValidUnchecked(target);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean targetStillValidUnchecked(CommitTarget target) {
        if (target.transactionWriter) {
            VoiceTransactionSession session = activeTarget == target
                    ? activeVoiceTransaction
                    : null;
            if (session == null || session.generation != target.voiceGeneration) return false;
            EditorSessionSnapshot current = captureCurrentTransactionSnapshot();
            if (current == null
                    || !(SessionValidator.validate(session.snapshot, current)
                            instanceof SessionValidationResult.Valid)) {
                return false;
            }
            session.snapshot = current;
            return true;
        }
        if (currentEditor == null) return false;
        InputConnection currentConnection = getCurrentInputConnection();
        if (currentConnection == null) return false;
        EditorProjection v2Projection = activeTarget == target ? activeV2Projection : null;
        if (v2Projection != null) return v2Projection.targetStillValid();
        VoiceCompositionSession composition = activeTarget == target ? activeComposition : null;
        if (composition != null && composition.ownsComposition()) {
            return composedTargetStillValid(target, composition, currentConnection);
        }
        if (!selectionCoordinatesStillMatch(
                target.selectionStart,
                target.selectionEnd,
                currentSelectionStart,
                currentSelectionEnd)) {
            return false;
        }
        CharSequence selected = currentConnection.getSelectedText(0);
        String currentSelected = selected == null ? "" : selected.toString();
        EditorTargetGuard.Snapshot captured = new EditorTargetGuard.Snapshot(
                target.editorEpoch,
                target.connection,
                target.packageName,
                target.fieldId,
                target.selectedText,
                target.beforeFingerprint,
                target.afterFingerprint);
        EditorTargetGuard.Snapshot current = new EditorTargetGuard.Snapshot(
                editorEpoch,
                currentConnection,
                safe(currentEditor.packageName),
                currentEditor.fieldId,
                currentSelected,
                tailCodePoints(
                        currentConnection.getTextBeforeCursor(CONTEXT_CHAR_LIMIT, 0),
                        FINGERPRINT_CODE_POINTS),
                headCodePoints(
                        currentConnection.getTextAfterCursor(CONTEXT_CHAR_LIMIT, 0),
                        FINGERPRINT_CODE_POINTS));
        return EditorTargetGuard.matches(captured, current, sensitiveField);
    }

    private boolean composedTargetStillValid(
            CommitTarget target,
            VoiceCompositionSession composition,
            InputConnection currentConnection) {
        if (sensitiveField
                || target.editorEpoch != editorEpoch
                || target.connection != currentConnection
                || !target.packageName.equals(safe(currentEditor.packageName))
                || target.fieldId != currentEditor.fieldId) {
            return false;
        }
        CharSequence selected = currentConnection.getSelectedText(0);
        if (selected != null && selected.length() > 0) return false;
        String composing = composition.composingText();
        int requestedBefore = Math.min(
                50_000,
                CONTEXT_CHAR_LIMIT + Math.max(composing.length(), 0));
        CharSequence beforeSequence = currentConnection.getTextBeforeCursor(requestedBefore, 0);
        String before = beforeSequence == null ? "" : beforeSequence.toString();
        if (!before.endsWith(composing)) return false;
        String originalBefore = before.substring(0, before.length() - composing.length());
        String currentAfter = headCodePoints(
                currentConnection.getTextAfterCursor(CONTEXT_CHAR_LIMIT, 0),
                FINGERPRINT_CODE_POINTS);
        return tailCodePoints(originalBefore, FINGERPRINT_CODE_POINTS)
                        .equals(target.beforeFingerprint)
                && currentAfter.equals(target.afterFingerprint);
    }

    private void undoLastVoiceCommit() {
        LastVoiceCommit commit = lastCommit;
        if (commit != null && commit.transactionBacked()) {
            undoTransactionCommit(commit);
            return;
        }
        if (!lastCommitStillTargetsCurrentEditor(commit)) {
            setStatus(R.string.ime_status_nothing_to_undo, false);
            return;
        }
        if (!endsWithBeforeCursor(commit.connection, commit.insertedText)) {
            setStatus(R.string.ime_status_undo_target_changed, true);
            return;
        }
        EditorMutationResult mutation = guardedReplace(
                commit.connection,
                codePointCount(commit.insertedText),
                commit.originalSelection,
                commit.insertedText);
        if (mutation != EditorMutationResult.APPLIED) {
            handleReplacementFailure(mutation, R.string.ime_operation_undo);
            return;
        }
        lastCommit = null;
        refreshPostCommitActions();
        setStatus(commit.originalSelection.isEmpty()
                ? getString(R.string.ime_status_insertion_removed)
                : getString(R.string.ime_status_selection_restored), false);
    }

    private void undoTransactionCommit(LastVoiceCommit commit) {
        EditorSessionSnapshot snapshot = captureCurrentTransactionSnapshot();
        if (snapshot == null || editorSessionManager == null) {
            invalidateLastCommit();
            setStatus(R.string.ime_status_undo_target_changed, true);
            return;
        }
        EditorTransactionResult result;
        try {
            result = editorSessionManager.undoVoiceCommit(this, snapshot, commit.commitId);
        } catch (RuntimeException unavailable) {
            disableEditorSessionShadow();
            invalidateLastCommit();
            setStatus(R.string.ime_status_operation_connection_failed, true);
            return;
        }
        if (result instanceof EditorTransactionResult.Applied) {
            lastCommit = null;
            refreshPostCommitActions();
            setStatus(commit.originalSelection.isEmpty()
                    ? getString(R.string.ime_status_insertion_removed)
                    : getString(R.string.ime_status_selection_restored), false);
            return;
        }
        handleTransactionCommitFailure(result, R.string.ime_operation_undo);
    }

    private void restoreRawTranscript() {
        LastVoiceCommit commit = lastCommit;
        if (commit != null && commit.transactionBacked()) {
            restoreTransactionRaw(commit);
            return;
        }
        if (!lastCommitStillTargetsCurrentEditor(commit)
                || commit.rawText.isBlank()
                || commit.originalSelection.length() > 0) {
            setStatus(R.string.ime_status_raw_unavailable, false);
            return;
        }
        if (commit.insertedText.equals(commit.rawText)) {
            setStatus(R.string.ime_status_raw_already_matches, false);
            return;
        }
        if (!endsWithBeforeCursor(commit.connection, commit.insertedText)) {
            setStatus(R.string.ime_status_raw_target_changed, true);
            return;
        }
        EditorMutationResult mutation = guardedReplace(
                commit.connection,
                codePointCount(commit.insertedText),
                commit.rawText,
                commit.insertedText);
        if (mutation != EditorMutationResult.APPLIED) {
            handleReplacementFailure(mutation, R.string.ime_operation_raw_restore);
            return;
        }
        lastCommit = commit.withInsertedText(commit.rawText);
        refreshPostCommitActions();
        setStatus(R.string.ime_status_raw_restored, false);
    }

    private void restoreTransactionRaw(LastVoiceCommit commit) {
        if (commit.rawText.isBlank() || commit.insertedText.equals(commit.rawText)) {
            setStatus(R.string.ime_status_raw_unavailable, false);
            return;
        }
        EditorSessionSnapshot snapshot = captureCurrentTransactionSnapshot();
        if (snapshot == null || editorSessionManager == null) {
            invalidateLastCommit();
            setStatus(R.string.ime_status_raw_target_changed, true);
            return;
        }
        EditorTransactionResult result;
        try {
            result = editorSessionManager.restoreRawVoiceCommit(
                    this, snapshot, commit.commitId);
        } catch (RuntimeException unavailable) {
            disableEditorSessionShadow();
            invalidateLastCommit();
            setStatus(R.string.ime_status_operation_connection_failed, true);
            return;
        }
        if (result instanceof EditorTransactionResult.Applied) {
            // Raw consumes the original exact-ID capability. It deliberately does not mint a
            // successor record or leave a stale Undo button.
            lastCommit = null;
            refreshPostCommitActions();
            setStatus(R.string.ime_status_raw_restored, false);
            return;
        }
        handleTransactionCommitFailure(result, R.string.ime_operation_raw_restore);
    }

    private void handleTransactionCommitFailure(EditorTransactionResult result, int operationId) {
        String operation = getString(operationId);
        if (result instanceof EditorTransactionResult.TargetChanged) {
            invalidateLastCommit();
            setStatus(getString(
                    R.string.ime_status_operation_connection_failed, operation), true);
            return;
        }
        if (result instanceof EditorTransactionResult.RollbackFailed) {
            invalidateLastCommit();
            setStatus(getString(
                    R.string.ime_status_operation_connection_failed, operation), true);
            return;
        }
        if (result instanceof EditorTransactionResult.RolledBack) {
            setStatus(getString(
                    R.string.ime_status_operation_rejected_restored, operation), true);
            return;
        }
        setStatus(getString(
                R.string.ime_status_operation_rejected_unchanged, operation), true);
    }

    private void teachCorrection() {
        LastVoiceCommit commit = lastCommit;
        CommitRecord record = commit == null ? null : commit.teachRecord;
        if (record == null || !(record.rawTranscript()
                instanceof CommitRecord.RawTranscript.Present)) {
            setStatus(R.string.ime_status_teach_requires_insertion, false);
            return;
        }
        if (!record.learningAllowed()) {
            setStatus(R.string.ime_status_learning_disallowed, false);
            return;
        }
        Intent intent = HistoryActivity.createTeachIntent(this, record, commit.historyId);
        if (intent == null) {
            setStatus(R.string.ime_status_teach_requires_insertion, false);
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private boolean lastCommitStillTargetsCurrentEditor(LastVoiceCommit commit) {
        return commit != null
                && commit.editorEpoch == editorEpoch
                && commit.connection == getCurrentInputConnection()
                && !sensitiveField;
    }

    private static boolean endsWithBeforeCursor(InputConnection connection, String text) {
        try {
            CharSequence before = connection.getTextBeforeCursor(text.length(), 0);
            return before != null && text.contentEquals(before);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Replaces text immediately before the cursor without trusting a remote editor to accept every
     * step. A rejected delete never advances to commit. If a commit is explicitly rejected after a
     * successful delete, the previous text is restored on a best-effort basis. Runtime failures are
     * treated as an unknown connection state and are never allowed to crash the IME process.
     */
    static EditorMutationResult guardedReplace(
            InputConnection connection,
            int deleteBeforeCodePoints,
            String replacement,
            String rollbackText) {
        if (connection == null || deleteBeforeCodePoints < 0) {
            return EditorMutationResult.CONNECTION_ERROR;
        }
        String safeReplacement = replacement == null ? "" : replacement;
        String safeRollback = rollbackText == null ? "" : rollbackText;
        boolean batchEntered = false;
        boolean deleted = false;
        try {
            connection.beginBatchEdit();
            batchEntered = true;
            if (deleteBeforeCodePoints > 0) {
                if (!connection.deleteSurroundingTextInCodePoints(deleteBeforeCodePoints, 0)) {
                    return EditorMutationResult.DELETE_REJECTED;
                }
                deleted = true;
            }
            if (safeReplacement.isEmpty()) return EditorMutationResult.APPLIED;
            if (connection.commitText(safeReplacement, 1)) return EditorMutationResult.APPLIED;
            if (!deleted) return EditorMutationResult.COMMIT_REJECTED;
            return rollback(connection, safeRollback);
        } catch (RuntimeException ignored) {
            // A remote failure does not tell us whether the last operation reached the editor.
            // Avoid another speculative write here; the caller will invalidate its undo token.
            return EditorMutationResult.CONNECTION_ERROR;
        } finally {
            if (batchEntered) {
                try {
                    connection.endBatchEdit();
                } catch (RuntimeException ignored) {
                    // The mutation result above is still authoritative; cleanup must not crash IME.
                }
            }
        }
    }

    static boolean guardedSetComposingText(InputConnection connection, String text) {
        if (connection == null || text == null || text.isBlank()) return false;
        boolean batchEntered = false;
        try {
            connection.beginBatchEdit();
            batchEntered = true;
            return connection.setComposingText(text, 1);
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (batchEntered) {
                try {
                    connection.endBatchEdit();
                } catch (RuntimeException ignored) {
                    // Composition is provisional; cleanup failure must not crash the IME.
                }
            }
        }
    }

    static EditorMutationResult guardedCommitComposition(
            InputConnection connection,
            String finalText) {
        if (connection == null || finalText == null || finalText.isBlank()) {
            return EditorMutationResult.CONNECTION_ERROR;
        }
        boolean batchEntered = false;
        try {
            connection.beginBatchEdit();
            batchEntered = true;
            return connection.commitText(finalText, 1)
                    ? EditorMutationResult.APPLIED
                    : EditorMutationResult.COMMIT_REJECTED;
        } catch (RuntimeException ignored) {
            return EditorMutationResult.CONNECTION_ERROR;
        } finally {
            if (batchEntered) {
                try {
                    connection.endBatchEdit();
                } catch (RuntimeException ignored) {
                    // The mutation result remains authoritative.
                }
            }
        }
    }

    private static void guardedClearComposition(InputConnection connection) {
        if (connection == null) return;
        boolean batchEntered = false;
        try {
            connection.beginBatchEdit();
            batchEntered = true;
            connection.setComposingText("", 1);
            connection.finishComposingText();
        } catch (RuntimeException ignored) {
            // Clearing provisional text is best-effort when an editor is disappearing.
        } finally {
            if (batchEntered) {
                try {
                    connection.endBatchEdit();
                } catch (RuntimeException ignored) {
                    // Cleanup must not crash the IME.
                }
            }
        }
    }

    private static EditorMutationResult rollback(InputConnection connection, String rollbackText) {
        if (rollbackText.isEmpty()) return EditorMutationResult.ROLLBACK_FAILED;
        try {
            return connection.commitText(rollbackText, 1)
                    ? EditorMutationResult.ROLLED_BACK
                    : EditorMutationResult.ROLLBACK_FAILED;
        } catch (RuntimeException ignored) {
            return EditorMutationResult.ROLLBACK_FAILED;
        }
    }

    private void handleReplacementFailure(EditorMutationResult result, int operationResource) {
        String operation = getString(operationResource);
        switch (result) {
            case DELETE_REJECTED, COMMIT_REJECTED ->
                    setStatus(getString(
                            R.string.ime_status_operation_rejected_unchanged, operation), true);
            case ROLLED_BACK ->
                    setStatus(getString(
                            R.string.ime_status_operation_rejected_restored, operation), true);
            case ROLLBACK_FAILED, CONNECTION_ERROR -> {
                invalidateLastCommit();
                setStatus(getString(
                        R.string.ime_status_operation_connection_failed, operation), true);
            }
            case APPLIED -> {
                // Callers handle successful mutations themselves.
            }
        }
    }

    private void routeTypingText(String text) {
        if (keyboardEngineSelection.active() != KeyboardEngineSelection.Engine.RIME) {
            insertKeyboardText(text);
            return;
        }
        if (" ".equals(text)) {
            routeRimeSpace();
            return;
        }
        if (text == null || text.length() != 1
                || text.charAt(0) < 'a' || text.charAt(0) > 'z') {
            setStatus(R.string.ime_status_key_rejected, true);
            return;
        }
        processRimeKey(RimeInputEngine.Key.printable(text.charAt(0)));
    }

    private void routeRimeSpace() {
        RimeCompositionLease lease = activeRimeLease;
        if (lease == null) {
            insertKeyboardText(" ");
            return;
        }
        CandidatePage page = lease.candidatePage;
        if (page == null || page.items().isEmpty()) {
            setStatus(R.string.ime_status_key_rejected, true);
            return;
        }
        routeRimeCandidateSelection(page.selection(0));
    }

    private void routeDeleteBackward() {
        if (keyboardEngineSelection.active() == KeyboardEngineSelection.Engine.RIME
                && activeRimeLease != null) {
            processRimeKey(RimeInputEngine.Key.backspace());
            return;
        }
        deleteKeyboardBackward();
    }

    private void routeKeyboardEnter() {
        if (keyboardEngineSelection.active() == KeyboardEngineSelection.Engine.RIME
                && activeRimeLease != null) {
            processRimeKey(RimeInputEngine.Key.enter());
            return;
        }
        performKeyboardEnter();
    }

    private void processRimeKey(RimeInputEngine.Key key) {
        if (sensitiveField || !currentLearningAllowed || availableRimePackage == null) {
            setStatus(R.string.ime_status_sensitive_disabled, true);
            closeRimeComposition(false);
            return;
        }
        RimeCompositionLease lease = activeRimeLease;
        if (lease == null) {
            EditorSessionSnapshot snapshot = captureKeyboardSnapshot();
            if (snapshot == null) return;
            long initialRevision = reserveNextRimeRevision();
            if (initialRevision == 0L) {
                setStatus(R.string.ime_status_key_rejected, true);
                return;
            }
            CompositionCoordinator.Observation before = compositionCoordinator.observe();
            CompositionCoordinator.Transition acquired = compositionCoordinator.acquire(
                    before, new CompositionCoordinator.Acquisition.Rime(initialRevision));
            if (acquired.disposition() != CompositionCoordinator.Disposition.APPLIED
                    || !(acquired.after().state() instanceof CompositionState.RimeComposing rime)) {
                setStatus(R.string.ime_status_session_active, true);
                return;
            }
            lease = new RimeCompositionLease(
                    editorEpoch,
                    snapshot,
                    rime.coordinationGeneration(),
                    acquired.after(),
                    initialRevision);
            RimeResourceStore.RuntimePackage runtime = availableRimePackage;
            RimeRuntimeConfig config = availableRimeConfig;
            if (config == null || !runtime.selectedSchemas().contains(config.schemaId())) {
                closeRimeComposition(false);
                setStatus(R.string.ime_status_key_rejected, true);
                return;
            }
            RimeCompositionLease created = lease;
            lease.controller = new RimeInputController(
                    lease.editorEpoch,
                    lease.coordinationGeneration,
                    initialRevision,
                    () -> new NativeRimeInputEngine(
                            runtime.root(), config, rimeUserDataStore,
                            runtime.deploymentId()),
                    this::postUi,
                    (callbackEditor, callbackCoordination, result) ->
                            onRimeResult(created, callbackEditor, callbackCoordination, result));
            activeRimeLease = lease;
        }
        if (lease.pendingSelection != null || lease.pendingPageRequest != null) {
            setStatus(R.string.ime_status_key_rejected, true);
            return;
        }
        lease.pendingKeyCommands++;
        if (keyboardCandidateBar != null) keyboardCandidateBar.setInteractionEnabled(false);
        RimeInputController.EnqueueResult queued = lease.controller.process(key);
        if (queued != RimeInputController.EnqueueResult.QUEUED) {
            lease.pendingKeyCommands--;
            closeRimeComposition(false);
            setStatus(R.string.ime_status_key_rejected, true);
        }
    }

    private void routeRimeCandidateSelection(CandidatePage.Selection selection) {
        RimeCompositionLease lease = activeRimeLease;
        if (lease == null
                || lease.controller == null
                || lease.pendingKeyCommands != 0
                || lease.pendingSelection != null
                || lease.pendingPageRequest != null
                || sensitiveField
                || !currentLearningAllowed
                || keyboardEngineSelection.active() != KeyboardEngineSelection.Engine.RIME) {
            rejectUnboundCandidateEvent();
            return;
        }
        CandidatePage page = lease.candidatePage;
        CandidatePage.Selection expected;
        try {
            expected = page == null ? null : page.selection(selection.candidateIndex());
        } catch (RuntimeException invalid) {
            expected = null;
        }
        if (expected == null || !expected.equals(selection)) {
            rejectUnboundCandidateEvent();
            return;
        }
        lease.pendingSelection = selection;
        if (keyboardCandidateBar != null) keyboardCandidateBar.setInteractionEnabled(false);
        RimeInputController.EnqueueResult queued = lease.controller.selectCandidate(selection);
        if (queued != RimeInputController.EnqueueResult.QUEUED) {
            lease.pendingSelection = null;
            closeRimeComposition(false);
            setStatus(R.string.ime_status_key_rejected, true);
        }
    }

    private void routeRimeCandidatePage(CandidatePage.PageRequest request) {
        RimeCompositionLease lease = activeRimeLease;
        if (lease == null
                || lease.controller == null
                || lease.pendingKeyCommands != 0
                || lease.pendingSelection != null
                || lease.pendingPageRequest != null
                || sensitiveField
                || !currentLearningAllowed
                || keyboardEngineSelection.active() != KeyboardEngineSelection.Engine.RIME) {
            rejectUnboundCandidateEvent();
            return;
        }
        CandidatePage page = lease.candidatePage;
        CandidatePage.PageRequest expected;
        try {
            expected = page == null ? null : page.pageRequest(request.direction());
        } catch (RuntimeException invalid) {
            expected = null;
        }
        if (expected == null || !expected.equals(request)) {
            rejectUnboundCandidateEvent();
            return;
        }
        lease.pendingPageRequest = request;
        if (keyboardCandidateBar != null) keyboardCandidateBar.setInteractionEnabled(false);
        RimeInputController.EnqueueResult queued = lease.controller.requestCandidatePage(request);
        if (queued != RimeInputController.EnqueueResult.QUEUED) {
            lease.pendingPageRequest = null;
            closeRimeComposition(false);
            setStatus(R.string.ime_status_key_rejected, true);
        }
    }

    private void onRimeResult(
            RimeCompositionLease lease,
            long callbackEditorEpoch,
            long callbackCoordinationGeneration,
            RimeInputEngine.ProcessResult result) {
        if (activeRimeLease != lease
                || !lease.matches(callbackEditorEpoch, callbackCoordinationGeneration)
                || editorEpoch != lease.editorEpoch
                || sensitiveField
                || !currentLearningAllowed
                || keyboardEngineSelection.active() != KeyboardEngineSelection.Engine.RIME) {
            if (activeRimeLease == lease) closeRimeComposition(false);
            return;
        }
        if (result instanceof RimeInputEngine.CommitReady committed) {
            applyRimeCommit(lease, committed);
            return;
        }
        if (!(result instanceof RimeInputEngine.StateReady ready)) {
            closeRimeComposition(false);
            setStatus(R.string.ime_status_key_rejected, true);
            return;
        }
        RimeEngineSnapshot snapshot = ready.snapshot();
        if (snapshot.editorGeneration() != lease.editorEpoch
                || snapshot.coordinationGeneration() != lease.coordinationGeneration
                || snapshot.revision() <= lease.revision) {
            closeRimeComposition(false);
            setStatus(R.string.ime_status_key_rejected, true);
            return;
        }
        recordRimeRevision(snapshot.revision());
        if (lease.pendingPageRequest != null) {
            applyRimeCandidatePage(lease, snapshot);
            return;
        }
        if (lease.pendingSelection != null || lease.pendingKeyCommands <= 0) {
            closeRimeComposition(false);
            setStatus(R.string.ime_status_key_rejected, true);
            return;
        }
        lease.pendingKeyCommands--;
        // Register the expected caret before the framework write. Xiaomi and other OEM editors
        // may synchronously call onUpdateSelection from setComposingText; registering afterward
        // would revoke the valid lease in the middle of its own transaction.
        long caret = (long) lease.baseSelectionStart + snapshot.preedit().length();
        if (caret > Integer.MAX_VALUE) {
            closeRimeComposition(false);
            setStatus(R.string.ime_status_key_rejected, true);
            return;
        }
        lease.expectedCaret = (int) caret;
        EditorTransactionResult applied;
        try {
            applied = editorSessionManager.setRimeComposition(
                    this, lease.editorSnapshot, snapshot.preedit(), snapshot.revision());
        } catch (RuntimeException unavailable) {
            disableEditorSessionShadow();
            closeRimeComposition(false);
            setStatus(R.string.ime_status_typing_connection_failed, true);
            return;
        }
        if (!(applied instanceof EditorTransactionResult.Applied)) {
            closeRimeComposition(false);
            handleKeyboardResult(
                    applied,
                    R.string.ime_status_key_rejected,
                    R.string.ime_status_typing_connection_failed);
            return;
        }
        EditorSessionSnapshot recaptured = captureCurrentTransactionSnapshot();
        if (recaptured == null || recaptured.epoch() != lease.editorSnapshot.epoch()) {
            try {
                editorSessionManager.finishRimeComposition(
                        this, lease.editorSnapshot, snapshot.revision());
            } catch (RuntimeException ignored) {
                // Do not recapture or write at a different target.
            }
            closeRimeComposition(false);
            setStatus(R.string.ime_status_composition_cleanup_failed, true);
            return;
        }
        lease.editorSnapshot = recaptured;
        CompositionCoordinator.Transition advanced = compositionCoordinator.update(
                lease.observation, snapshot.revision());
        if (advanced.disposition() != CompositionCoordinator.Disposition.APPLIED) {
            try {
                editorSessionManager.finishRimeComposition(
                        this, lease.editorSnapshot, snapshot.revision());
            } catch (RuntimeException ignored) {
                // The lease is revoked below; no current-cursor fallback is attempted.
            }
            closeRimeComposition(false);
            setStatus(R.string.ime_status_composition_cleanup_failed, true);
            return;
        }
        lease.observation = advanced.after();
        lease.revision = snapshot.revision();
        lease.preedit = snapshot.preedit();
        lease.candidatePage = snapshot.candidatePage().orElse(null);
        renderRimeCandidatePage(lease);
        if (snapshot.preedit().isEmpty()) finishEmptyRimeComposition(lease);
    }

    private void applyRimeCandidatePage(
            RimeCompositionLease lease, RimeEngineSnapshot snapshot) {
        CandidatePage.PageRequest request = lease.pendingPageRequest;
        CandidatePage before = lease.candidatePage;
        CandidatePage after = snapshot.candidatePage().orElse(null);
        int expectedPage = request == null ? -1
                : request.direction() == CandidatePage.Direction.NEXT
                        ? request.pageIndex() + 1 : request.pageIndex() - 1;
        if (request == null
                || lease.pendingSelection != null
                || lease.pendingKeyCommands != 0
                || before == null
                || after == null
                || !snapshot.preedit().equals(lease.preedit)
                || request.pageRevision() != lease.revision
                || request.generation() != lease.coordinationGeneration
                || after.pageIndex() != expectedPage
                || after.pageCount() != before.pageCount()) {
            closeRimeComposition(false);
            setStatus(R.string.ime_status_key_rejected, true);
            return;
        }
        CompositionCoordinator.Transition advanced = compositionCoordinator.update(
                lease.observation, snapshot.revision());
        if (advanced.disposition() != CompositionCoordinator.Disposition.APPLIED) {
            closeRimeComposition(false);
            setStatus(R.string.ime_status_composition_cleanup_failed, true);
            return;
        }
        lease.observation = advanced.after();
        lease.revision = snapshot.revision();
        lease.candidatePage = after;
        lease.pendingPageRequest = null;
        renderRimeCandidatePage(lease);
    }

    private void applyRimeCommit(
            RimeCompositionLease lease, RimeInputEngine.CommitReady ready) {
        RimeInputEngine.Commit commit = ready.commit();
        CandidatePage.Selection selection = lease.pendingSelection;
        RimeEngineSnapshot snapshot = ready.snapshot();
        boolean candidateCommit = selection != null;
        boolean keyCommit = selection == null
                && lease.pendingKeyCommands > 0
                && lease.pendingPageRequest == null;
        if ((!candidateCommit && !keyCommit)
                || lease.pendingPageRequest != null
                || (candidateCommit && lease.pendingKeyCommands != 0)
                || (candidateCommit && lease.candidatePage == null)
                || (candidateCommit
                        && !lease.candidatePage.selection(selection.candidateIndex())
                                .equals(selection))
                || commit.editorGeneration() != lease.editorEpoch
                || commit.coordinationGeneration() != lease.coordinationGeneration
                || commit.revision() <= lease.revision
                || (candidateCommit && !commit.text().equals(selection.expectedText()))
                || snapshot.editorGeneration() != commit.editorGeneration()
                || snapshot.coordinationGeneration() != commit.coordinationGeneration()
                || snapshot.revision() != commit.revision()
                || !snapshot.preedit().isEmpty()
                || snapshot.candidatePage().isPresent()) {
            closeRimeComposition(false);
            setStatus(R.string.ime_status_key_rejected, true);
            return;
        }
        recordRimeRevision(commit.revision());
        long caret = (long) lease.baseSelectionStart + commit.text().length();
        if (caret > Integer.MAX_VALUE) {
            closeRimeComposition(false);
            setStatus(R.string.ime_status_key_rejected, true);
            return;
        }
        lease.expectedCaret = (int) caret;
        EditorTransactionResult applied;
        try {
            applied = editorSessionManager.setRimeComposition(
                    this, lease.editorSnapshot, commit.text(), commit.revision());
        } catch (RuntimeException unavailable) {
            disableEditorSessionShadow();
            closeRimeComposition(false);
            setStatus(R.string.ime_status_typing_connection_failed, true);
            return;
        }
        if (!(applied instanceof EditorTransactionResult.Applied)) {
            closeRimeComposition(false);
            handleKeyboardResult(
                    applied,
                    R.string.ime_status_key_rejected,
                    R.string.ime_status_typing_connection_failed);
            return;
        }
        EditorSessionSnapshot recaptured = captureCurrentTransactionSnapshot();
        if (recaptured == null || recaptured.epoch() != lease.editorSnapshot.epoch()) {
            try {
                editorSessionManager.finishRimeComposition(
                        this, lease.editorSnapshot, commit.revision());
            } catch (RuntimeException ignored) {
                // The original target is the only permitted cleanup target.
            }
            closeRimeComposition(false);
            setStatus(R.string.ime_status_composition_cleanup_failed, true);
            return;
        }
        lease.editorSnapshot = recaptured;
        CompositionCoordinator.Transition advanced = compositionCoordinator.update(
                lease.observation, commit.revision());
        if (advanced.disposition() != CompositionCoordinator.Disposition.APPLIED) {
            try {
                editorSessionManager.finishRimeComposition(
                        this, lease.editorSnapshot, commit.revision());
            } catch (RuntimeException ignored) {
                // The lease is revoked below; no current-cursor fallback is attempted.
            }
            closeRimeComposition(false);
            setStatus(R.string.ime_status_composition_cleanup_failed, true);
            return;
        }
        lease.observation = advanced.after();
        lease.revision = commit.revision();
        if (keyCommit) lease.pendingKeyCommands--;
        lease.pendingSelection = null;
        EditorTransactionResult finished;
        try {
            finished = editorSessionManager.finishRimeComposition(
                    this, lease.editorSnapshot, commit.revision());
        } catch (RuntimeException unavailable) {
            disableEditorSessionShadow();
            closeRimeComposition(false);
            setStatus(R.string.ime_status_composition_cleanup_failed, true);
            return;
        }
        if (!(finished instanceof EditorTransactionResult.Applied)) {
            closeRimeComposition(false);
            handleKeyboardResult(
                    finished,
                    R.string.ime_status_key_rejected,
                    R.string.ime_status_typing_connection_failed);
            return;
        }
        CompositionCoordinator.Transition terminal = compositionCoordinator.commit(
                lease.observation, commit.revision());
        if (terminal.disposition() != CompositionCoordinator.Disposition.APPLIED) {
            closeRimeComposition(false);
            setStatus(R.string.ime_status_composition_cleanup_failed, true);
            return;
        }
        closeRimeControllerOnly(lease);
        activeRimeLease = null;
        if (keyboardCandidateBar != null) keyboardCandidateBar.clear();
    }

    private void renderRimeCandidatePage(RimeCompositionLease lease) {
        KeyboardCandidateBar bar = keyboardCandidateBar;
        if (bar == null) return;
        CandidatePage page = lease.candidatePage;
        if (page == null) {
            bar.clear();
            return;
        }
        bar.setInteractionEnabled(
                lease.pendingKeyCommands == 0
                        && lease.pendingSelection == null
                        && lease.pendingPageRequest == null);
        bar.showPage(page);
    }

    private void finishEmptyRimeComposition(RimeCompositionLease lease) {
        EditorTransactionResult finished;
        try {
            finished = editorSessionManager.finishRimeComposition(
                    this, lease.editorSnapshot, lease.revision);
        } catch (RuntimeException unavailable) {
            disableEditorSessionShadow();
            closeRimeComposition(false);
            return;
        }
        if (finished instanceof EditorTransactionResult.Applied) {
            CompositionCoordinator.Transition committed = compositionCoordinator.commit(
                    lease.observation, lease.revision);
            if (committed.disposition() == CompositionCoordinator.Disposition.APPLIED) {
                closeRimeControllerOnly(lease);
                activeRimeLease = null;
                return;
            }
        }
        closeRimeComposition(false);
        setStatus(R.string.ime_status_composition_cleanup_failed, true);
    }

    private boolean finishRimeCompositionForEngineSwitch() {
        RimeCompositionLease lease = activeRimeLease;
        if (lease == null) return true;
        if (lease.revision <= 1L) {
            closeRimeComposition(false);
            return true;
        }
        EditorTransactionResult result;
        try {
            result = editorSessionManager.finishRimeComposition(
                    this, lease.editorSnapshot, lease.revision);
        } catch (RuntimeException unavailable) {
            disableEditorSessionShadow();
            return false;
        }
        if (!(result instanceof EditorTransactionResult.Applied)) return false;
        CompositionCoordinator.Transition committed = compositionCoordinator.commit(
                lease.observation, lease.revision);
        if (committed.disposition() != CompositionCoordinator.Disposition.APPLIED) return false;
        closeRimeControllerOnly(lease);
        activeRimeLease = null;
        return true;
    }

    private RimeVoicePreemption preemptRimeForVoice() {
        RimeCompositionLease lease = activeRimeLease;
        if (lease == null) return null;
        if (lease.controller == null
                || lease.pendingKeyCommands != 0
                || lease.pendingSelection != null
                || lease.pendingPageRequest != null) {
            setStatus(R.string.ime_status_key_rejected, true);
            return null;
        }
        RimeVoicePreemption preemption = RimeVoicePreemption.begin(
                compositionCoordinator, lease.observation, compositionConflictPolicy);
        if (preemption == null) {
            setStatus(R.string.ime_status_session_active, true);
            return null;
        }
        RimeReleaseProof proof = releaseRimeForVoice(lease, preemption.directive());
        CompositionCoordinator.ReleaseResolution resolution = switch (proof) {
            case RELEASED -> CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED;
            case UNCHANGED -> CompositionCoordinator.ReleaseResolution.PROVEN_UNCHANGED;
            case UNCERTAIN -> CompositionCoordinator.ReleaseResolution.UNCERTAIN;
        };
        RimeVoicePreemption.Finish finish;
        try {
            finish = preemption.finish(resolution);
        } catch (RuntimeException invalidPreemption) {
            finish = RimeVoicePreemption.Finish.UNCERTAIN;
        }
        if (finish == RimeVoicePreemption.Finish.VOICE_ACQUIRED) {
            closeRimeControllerOnly(lease);
            activeRimeLease = null;
            if (keyboardCandidateBar != null) keyboardCandidateBar.clear();
            return preemption;
        }
        if (finish == RimeVoicePreemption.Finish.RIME_UNCHANGED) {
            lease.observation = preemption.restoredRimeObservation();
            setStatus(R.string.ime_status_composition_cleanup_failed, true);
            return null;
        }
        closeRimeControllerOnly(lease);
        activeRimeLease = null;
        if (keyboardCandidateBar != null) keyboardCandidateBar.clear();
        setStatus(R.string.ime_status_composition_cleanup_failed, true);
        return null;
    }

    private RimeReleaseProof releaseRimeForVoice(
            RimeCompositionLease lease,
            CompositionCoordinator.ReleaseDirective directive) {
        if (lease.preedit.isEmpty() && lease.revision <= 1L) {
            return RimeReleaseProof.RELEASED;
        }
        try {
            if (directive == CompositionCoordinator.ReleaseDirective.COMMIT_CURRENT) {
                return classifyUnchangedRelease(editorSessionManager.finishRimeComposition(
                        this, lease.editorSnapshot, lease.revision));
            }
            long cancellationRevision = Math.addExact(lease.revision, 1L);
            lease.expectedCaret = lease.baseSelectionStart;
            EditorTransactionResult cleared = editorSessionManager.setRimeComposition(
                    this, lease.editorSnapshot, "", cancellationRevision);
            RimeReleaseProof clearedProof = classifyUnchangedRelease(cleared);
            if (clearedProof != RimeReleaseProof.RELEASED) return clearedProof;
            EditorSessionSnapshot recaptured = captureCurrentTransactionSnapshot();
            if (recaptured == null || recaptured.epoch() != lease.editorSnapshot.epoch()) {
                return RimeReleaseProof.UNCERTAIN;
            }
            lease.editorSnapshot = recaptured;
            EditorTransactionResult finished = editorSessionManager.finishRimeComposition(
                    this, lease.editorSnapshot, cancellationRevision);
            return finished instanceof EditorTransactionResult.Applied
                    ? RimeReleaseProof.RELEASED
                    : RimeReleaseProof.UNCERTAIN;
        } catch (RuntimeException unavailable) {
            return RimeReleaseProof.UNCERTAIN;
        }
    }

    private static RimeReleaseProof classifyUnchangedRelease(EditorTransactionResult result) {
        if (result instanceof EditorTransactionResult.Applied) return RimeReleaseProof.RELEASED;
        if (result instanceof EditorTransactionResult.Rejected
                || result instanceof EditorTransactionResult.RolledBack) {
            return RimeReleaseProof.UNCHANGED;
        }
        return RimeReleaseProof.UNCERTAIN;
    }

    private void closeRimeComposition(boolean clearAvailability) {
        RimeCompositionLease lease = activeRimeLease;
        activeRimeLease = null;
        if (lease != null) {
            closeRimeControllerOnly(lease);
            compositionCoordinator.cancel(lease.observation);
        }
        if (keyboardCandidateBar != null) keyboardCandidateBar.clear();
        if (clearAvailability) {
            availableRimePackage = null;
            availableRimeConfig = null;
            keyboardEngineSelection = keyboardEngineSelection.withAvailability(
                    EnumSet.of(KeyboardEngineSelection.Engine.LATIN));
            if (latinKeyboardLayout != null) {
                latinKeyboardLayout.setEngineSelection(keyboardEngineSelection);
            }
        }
    }

    private static void closeRimeControllerOnly(RimeCompositionLease lease) {
        RimeInputController controller = lease.controller;
        lease.controller = null;
        if (controller != null) controller.close();
    }

    private void refreshRimeAvailability(
            long expectedEditorEpoch,
            InputContextClassifier.PrivacyClassification privacy) {
        long request = ++rimeAvailabilityRequest;
        if (keyboardShellRoute != KeyboardShellRoute.ROUTE_A
                || privacy.sensitive()
                || !privacy.learningAllowed()
                || currentKeyboardFieldProfile.usesNumericPanel()) {
            applyRimeAvailability(request, expectedEditorEpoch, null, null);
            return;
        }
        try {
            localIo.execute(() -> {
                RimeResourceStore.RuntimePackage runtime = null;
                RimeRuntimeConfig config = null;
                try {
                    runtime = rimeResourceStore.runtimePackage();
                    if (runtime != null) {
                        config = rimeRuntimePreferences.load(runtime.selectedSchemas());
                    }
                } catch (RimeImportException ignored) {
                    // A corrupt/busy local package is unavailable; Latin remains the fallback.
                } catch (RuntimeException ignored) {
                    // A corrupt local preference is repaired or fails closed to Latin.
                }
                RimeResourceStore.RuntimePackage result = runtime;
                RimeRuntimeConfig selected = config;
                postUiIfAlive(() -> applyRimeAvailability(
                        request, expectedEditorEpoch, result, selected));
            });
        } catch (RejectedExecutionException ignored) {
            applyRimeAvailability(request, expectedEditorEpoch, null, null);
        }
    }

    private void applyRimeAvailability(
            long request,
            long expectedEditorEpoch,
            RimeResourceStore.RuntimePackage runtime,
            RimeRuntimeConfig config) {
        if (request != rimeAvailabilityRequest || expectedEditorEpoch != editorEpoch) return;
        availableRimePackage = runtime;
        availableRimeConfig = runtime == null ? null : config;
        EnumSet<KeyboardEngineSelection.Engine> available = runtime == null || config == null
                ? EnumSet.of(KeyboardEngineSelection.Engine.LATIN)
                : EnumSet.of(
                        KeyboardEngineSelection.Engine.LATIN,
                        KeyboardEngineSelection.Engine.RIME);
        keyboardEngineSelection = keyboardEngineSelection.withAvailability(available);
        if ((runtime == null || config == null) && activeRimeLease != null) {
            closeRimeComposition(false);
        }
        if (latinKeyboardLayout != null) {
            latinKeyboardLayout.setEngineSelection(keyboardEngineSelection);
        }
    }

    private long reserveNextRimeRevision() {
        if (rimeRevisionHighWatermark == Long.MAX_VALUE) return 0L;
        rimeRevisionHighWatermark++;
        return rimeRevisionHighWatermark;
    }

    private void recordRimeRevision(long revision) {
        if (revision > rimeRevisionHighWatermark) {
            rimeRevisionHighWatermark = revision;
        }
    }

    private void deleteKeyboardBackward() {
        boolean voiceWasActive = activeVoiceTransaction != null;
        VoiceTransactionSession.KeyboardPreemption preemption =
                preemptVoiceForKeyboard();
        if (voiceWasActive && preemption == null) return;
        EditorSessionSnapshot snapshot = captureKeyboardSnapshot();
        if (snapshot == null) {
            finishVoiceKeyboardPreemption(preemption, false);
            return;
        }
        EditorTransactionResult result;
        try {
            result = editorSessionManager.deleteKeyboardBackward(this, snapshot);
        } catch (RuntimeException unavailable) {
            disableEditorSessionShadow();
            setStatus(R.string.ime_status_delete_connection_failed, true);
            invalidateLastCommit();
            finishVoiceKeyboardPreemption(preemption, false);
            return;
        }
        boolean preemptionClosed = finishVoiceKeyboardPreemption(
                preemption, result instanceof EditorTransactionResult.Applied);
        handleKeyboardResult(
                result,
                R.string.ime_status_delete_rejected,
                R.string.ime_status_delete_connection_failed);
        if (!preemptionClosed) {
            setStatus(R.string.ime_status_composition_cleanup_failed, true);
        }
        invalidateLastCommit();
    }

    @Override
    public EditorInfo currentEditorInfo() {
        return getCurrentInputEditorInfo();
    }

    @Override
    public InputConnection currentInputConnection() {
        return getCurrentInputConnection();
    }

    private void insertKeyboardText(String text) {
        boolean voiceWasActive = activeVoiceTransaction != null;
        VoiceTransactionSession.KeyboardPreemption preemption =
                preemptVoiceForKeyboard();
        if (voiceWasActive && preemption == null) {
            invalidateLastCommit();
            return;
        }
        EditorSessionSnapshot snapshot = captureKeyboardSnapshot();
        if (snapshot == null) {
            finishVoiceKeyboardPreemption(preemption, false);
            invalidateLastCommit();
            return;
        }
        EditorTransactionResult result;
        try {
            result = editorSessionManager.insertKeyboardText(this, snapshot, text);
        } catch (RuntimeException unavailable) {
            disableEditorSessionShadow();
            setStatus(R.string.ime_status_typing_connection_failed, true);
            invalidateLastCommit();
            finishVoiceKeyboardPreemption(preemption, false);
            return;
        }
        boolean preemptionClosed = finishVoiceKeyboardPreemption(
                preemption, result instanceof EditorTransactionResult.Applied);
        handleKeyboardResult(
                result,
                R.string.ime_status_key_rejected,
                R.string.ime_status_typing_connection_failed);
        if (!preemptionClosed) {
            setStatus(R.string.ime_status_composition_cleanup_failed, true);
        }
        invalidateLastCommit();
    }

    private void performKeyboardEnter() {
        boolean voiceWasActive = activeVoiceTransaction != null;
        VoiceTransactionSession.KeyboardPreemption preemption =
                preemptVoiceForKeyboard();
        if (voiceWasActive && preemption == null) return;
        EditorSessionSnapshot snapshot = captureKeyboardSnapshot();
        if (snapshot == null) {
            finishVoiceKeyboardPreemption(preemption, false);
            return;
        }
        EditorTransactionResult result;
        try {
            result = editorSessionManager.performKeyboardEnter(this, snapshot);
        } catch (RuntimeException unavailable) {
            disableEditorSessionShadow();
            setStatus(R.string.ime_status_editor_action_failed, true);
            invalidateLastCommit();
            finishVoiceKeyboardPreemption(preemption, false);
            return;
        }
        boolean preemptionClosed = finishVoiceKeyboardPreemption(
                preemption, result instanceof EditorTransactionResult.Applied);
        handleKeyboardResult(
                result,
                R.string.ime_status_key_rejected,
                R.string.ime_status_editor_action_failed);
        if (!preemptionClosed) {
            setStatus(R.string.ime_status_composition_cleanup_failed, true);
        }
        invalidateLastCommit();
    }

    private VoiceTransactionSession.KeyboardPreemption preemptVoiceForKeyboard() {
        VoiceTransactionSession session = activeVoiceTransaction;
        if (session == null) return null;
        CommitTarget target = activeTarget;
        if (target == null || !target.transactionWriter) {
            setStatus(R.string.ime_status_key_rejected, true);
            return null;
        }

        VoiceTransactionSession.KeyboardPreemption preemption =
                session.beginKeyboardPreemption(
                        compositionConflictPolicy, finishingVoiceInput);
        if (preemption == null) {
            setStatus(R.string.ime_status_key_rejected, true);
            return null;
        }

        VoiceReleaseProof proof = releaseVoiceForKeyboard(session, preemption);
        CompositionCoordinator.ReleaseResolution resolution = switch (proof) {
            case RELEASED -> CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED;
            case UNCHANGED -> CompositionCoordinator.ReleaseResolution.PROVEN_UNCHANGED;
            case UNCERTAIN -> CompositionCoordinator.ReleaseResolution.UNCERTAIN;
        };
        if (!session.finishKeyboardRelease(preemption, resolution)) {
            voiceController.cancel();
            holdToTalkActive = false;
            preparingVoiceInput = false;
            finishingVoiceInput = false;
            activeCaptureMode = null;
            setStatus(R.string.ime_status_composition_cleanup_failed, true);
            return null;
        }

        boolean routeLateResult = preemption.routeLateResult();
        if (routeLateResult) {
            detachedTargetAwaitingResult = target;
            installPendingDetached(target);
            if (detachedSessionFor(target) == null) {
                detachedTargetAwaitingResult = null;
                routeLateResult = false;
            }
        }
        if (!routeLateResult) voiceController.cancel();
        activeTarget = null;
        holdToTalkActive = false;
        preparingVoiceInput = false;
        finishingVoiceInput = routeLateResult;
        if (!routeLateResult) activeCaptureMode = null;
        latestPreviewText = "";
        clearTranscript();
        updateMicrophone(routeLateResult
                ? VoiceController.State.TRANSCRIBING
                : VoiceController.State.IDLE);
        return preemption;
    }

    private VoiceReleaseProof releaseVoiceForKeyboard(
            VoiceTransactionSession session,
            VoiceTransactionSession.KeyboardPreemption preemption) {
        try {
            if (preemption.directive()
                    == CompositionCoordinator.ReleaseDirective.COMMIT_CURRENT) {
                if (!session.compositionActive) return VoiceReleaseProof.UNCERTAIN;
                EditorTransactionResult result = editorSessionManager.finishVoiceComposition(
                        this, session.snapshot, session.revision);
                if (result instanceof EditorTransactionResult.Applied) {
                    return VoiceReleaseProof.RELEASED;
                }
                return result instanceof EditorTransactionResult.Rejected
                        ? VoiceReleaseProof.UNCHANGED
                        : VoiceReleaseProof.UNCERTAIN;
            }
            if (!session.compositionActive) return VoiceReleaseProof.RELEASED;

            long revision = session.prepareKeyboardCancellation(preemption);
            EditorTransactionResult cleared = editorSessionManager.setVoiceComposition(
                    this, session.snapshot, "", revision);
            if (!(cleared instanceof EditorTransactionResult.Applied)) {
                return cleared instanceof EditorTransactionResult.Rejected
                        ? VoiceReleaseProof.UNCHANGED
                        : VoiceReleaseProof.UNCERTAIN;
            }
            session.completeKeyboardCancellation(preemption, revision);
            EditorSessionSnapshot captured = captureCurrentTransactionSnapshot();
            if (captured == null) return VoiceReleaseProof.UNCERTAIN;
            session.completeComposition(captured);
            EditorTransactionResult finished = editorSessionManager.finishVoiceComposition(
                    this, session.snapshot, session.revision);
            return finished instanceof EditorTransactionResult.Applied
                    ? VoiceReleaseProof.RELEASED
                    : VoiceReleaseProof.UNCERTAIN;
        } catch (RuntimeException unavailable) {
            return VoiceReleaseProof.UNCERTAIN;
        }
    }

    private boolean finishVoiceKeyboardPreemption(
            VoiceTransactionSession.KeyboardPreemption preemption,
            boolean keyboardApplied) {
        if (preemption == null) return true;
        VoiceTransactionSession session = activeVoiceTransaction;
        if (session == null) return false;
        boolean finished;
        try {
            finished = session.finishKeyboardEvent(preemption, keyboardApplied);
        } catch (RuntimeException invalidLease) {
            finished = false;
        }
        if (finished) clearVoiceTransactionState();
        return finished;
    }

    private EditorSessionSnapshot captureKeyboardSnapshot() {
        if (!editorSessionShadowHealthy || editorSessionManager == null) {
            setStatus(R.string.ime_status_field_unavailable, true);
            return null;
        }
        boolean keyboardPreemption = activeVoiceTransaction != null
                && activeVoiceTransaction.keyboardPreemptionActive();
        if (activeTarget != null
                || activeComposition != null
                || activeV2Projection != null
                || (activeVoiceTransaction != null && !keyboardPreemption)
                || preparingVoiceInput
                || (finishingVoiceInput && !keyboardPreemption)) {
            setStatus(R.string.ime_status_key_rejected, true);
            return null;
        }

        InputConnection connection = getCurrentInputConnection();
        EditorInfo editor = currentEditor;
        if (connection == null || editor == null) {
            setStatus(R.string.ime_status_no_active_field, true);
            return null;
        }

        EditorSessionManager.CaptureResult capture;
        if (sensitiveField) {
            // Sensitive keyboard transactions preserve the EDT-007 zero-plaintext path. The
            // lifecycle-reported selection is validated by the manager without any text getter.
            capture = shadowCaptureEditorSession(connection, "", "", "");
        } else {
            EditorEvidenceReader.SelectionResult observed =
                    EditorEvidenceReader.readSelectionOnce(connection, false, true);
            if (!(observed instanceof EditorEvidenceReader.SelectionEvidence evidence)) {
                setStatus(R.string.ime_status_field_unavailable, true);
                return null;
            }
            boolean extractedKnown = evidence.extractedTextAvailable()
                    && evidence.extractedSelectionStart() >= 0
                    && evidence.extractedSelectionEnd() >= 0;
            SelectionEvidence selection = resolveSelectionEvidence(
                    extractedKnown ? -1 : currentSelectionStart,
                    extractedKnown ? -1 : currentSelectionEnd,
                    evidence.selectedTextAvailable() ? evidence.selectedText() : null,
                    evidence.extractedSelectionStart(),
                    evidence.extractedSelectionEnd(),
                    evidence.extractedTextAvailable());
            if (selectionCaptureDecision(selection, MAX_SELECTION_CODE_POINTS)
                    != SelectionCaptureDecision.ACCEPT) {
                setStatus(R.string.ime_status_selection_unavailable, true);
                return null;
            }
            currentSelectionStart = selection.start();
            currentSelectionEnd = selection.end();
            shadowSelectionChanged(selection.start(), selection.end());

            EditorEvidenceReader.SurroundingResult surroundingResult =
                    EditorEvidenceReader.readSurroundingOnce(
                            connection, CONTEXT_CHAR_LIMIT, FINGERPRINT_CODE_POINTS);
            if (!(surroundingResult
                    instanceof EditorEvidenceReader.SurroundingEvidence surrounding)) {
                setStatus(R.string.ime_status_field_unavailable, true);
                return null;
            }
            capture = shadowCaptureEditorSession(
                    connection,
                    selection.text(),
                    surrounding.shadowBeforeText(),
                    surrounding.shadowAfterText());
        }

        if (capture instanceof EditorSessionManager.Captured captured) {
            return captured.snapshot();
        }
        setStatus(R.string.ime_status_field_unavailable, true);
        return null;
    }

    /**
     * Recaptures a transaction snapshot from one exact live editor without trusting a delayed
     * onUpdateSelection callback. ExtractedText supplies the absolute range; the manager still
     * performs full authority/evidence validation again before every subsequent write.
     */
    private EditorSessionSnapshot captureCurrentTransactionSnapshot() {
        if (!editorSessionShadowHealthy
                || editorSessionManager == null
                || sensitiveField
                || currentEditor == null) {
            return null;
        }
        try {
            InputConnection connection = getCurrentInputConnection();
            EditorInfo editor = currentEditor;
            if (connection == null) return null;

            EditorEvidenceReader.SelectionResult observed =
                    EditorEvidenceReader.readSelectionOnce(connection, false, true);
            if (!(observed instanceof EditorEvidenceReader.SelectionEvidence evidence)
                    || !evidence.extractedTextAvailable()
                    || evidence.extractedSelectionStart() < 0
                    || evidence.extractedSelectionEnd() < 0) {
                return null;
            }
            SelectionEvidence selection = resolveSelectionEvidence(
                    -1,
                    -1,
                    evidence.selectedTextAvailable() ? evidence.selectedText() : null,
                    evidence.extractedSelectionStart(),
                    evidence.extractedSelectionEnd(),
                    true);
            if (selectionCaptureDecision(selection, MAX_SELECTION_CODE_POINTS)
                    != SelectionCaptureDecision.ACCEPT) {
                return null;
            }
            if (connection != getCurrentInputConnection() || editor != currentEditor) return null;

            currentSelectionStart = selection.start();
            currentSelectionEnd = selection.end();
            shadowSelectionChanged(selection.start(), selection.end());
            EditorEvidenceReader.SurroundingResult surrounding =
                    EditorEvidenceReader.readSurroundingOnce(
                            connection, CONTEXT_CHAR_LIMIT, FINGERPRINT_CODE_POINTS);
            if (!(surrounding instanceof EditorEvidenceReader.SurroundingEvidence evidenceText)) {
                return null;
            }
            if (connection != getCurrentInputConnection() || editor != currentEditor) return null;
            EditorSessionManager.CaptureResult capture = shadowCaptureEditorSession(
                    connection,
                    selection.text(),
                    evidenceText.shadowBeforeText(),
                    evidenceText.shadowAfterText());
            return capture instanceof EditorSessionManager.Captured captured
                    ? captured.snapshot()
                    : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private void handleKeyboardResult(
            EditorTransactionResult result, int rejectedResource, int failedResource) {
        if (result instanceof EditorTransactionResult.Applied) return;
        if (result instanceof EditorTransactionResult.Rejected
                || result instanceof EditorTransactionResult.RolledBack) {
            setStatus(rejectedResource, true);
            return;
        }
        setStatus(failedResource, true);
    }

    private void switchKeyboard() {
        handleSystemImeSwitch(KeyboardSystemImeSwitcher.requestNext(systemImePlatform()));
    }

    private void showKeyboardPicker() {
        handleSystemImeSwitch(KeyboardSystemImeSwitcher.requestPicker(systemImePlatform()));
    }

    private KeyboardSystemImeSwitcher.Platform systemImePlatform() {
        return new KeyboardSystemImeSwitcher.Platform() {
            @Override
            public boolean switchToNextInputMethod() {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
                return OpenTypelessImeService.this.switchToNextInputMethod(false);
            }

            @Override
            public boolean showInputMethodPicker() {
                InputMethodManager manager = getSystemService(InputMethodManager.class);
                if (manager == null) return false;
                manager.showInputMethodPicker();
                return true;
            }
        };
    }

    private void handleSystemImeSwitch(KeyboardSystemImeSwitcher.Outcome outcome) {
        if (outcome == KeyboardSystemImeSwitcher.Outcome.NEXT_INPUT_METHOD_REQUESTED) return;
        setStatus(outcome == KeyboardSystemImeSwitcher.Outcome.FAILED
                ? R.string.ime_status_keyboard_switch_failed
                : R.string.ime_status_keyboard_picker_opened, outcome == KeyboardSystemImeSwitcher.Outcome.FAILED);
    }

    private void switchInputEngine() {
        if (activeTarget != null || voiceController.state() != VoiceController.State.IDLE) {
            setStatus(R.string.ime_status_finish_before_engine_change, true);
            return;
        }
        if (keyboardEngineSelection.active() == KeyboardEngineSelection.Engine.RIME
                && activeRimeLease != null
                && !finishRimeCompositionForEngineSwitch()) {
            setStatus(R.string.ime_status_composition_cleanup_failed, true);
            return;
        }
        KeyboardEngineSelection.CycleResult result = keyboardEngineSelection.cycle();
        if (result instanceof KeyboardEngineSelection.Unavailable) {
            setStatus(R.string.ime_status_second_engine_unavailable, true);
            return;
        }
        keyboardEngineSelection = result.state();
        if (keyboardCandidateBar != null) keyboardCandidateBar.clear();
        if (latinKeyboardLayout != null) {
            latinKeyboardLayout.setEngineSelection(keyboardEngineSelection);
        }
        setStatus(keyboardEngineSelection.active() == KeyboardEngineSelection.Engine.LATIN
                ? R.string.ime_status_engine_latin
                : R.string.ime_status_engine_rime, false);
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsHomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void openVoiceDiagnostics() {
        Intent intent = new Intent(this, VoiceLabActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void openAppProfile() {
        Intent intent = new Intent(this, AppProfileActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(AppProfileActivity.EXTRA_PACKAGE,
                currentEditor == null ? "" : safe(currentEditor.packageName));
        startActivity(intent);
    }

    private void openDictionary() {
        Intent intent = new Intent(this, DictionaryActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void showModeMenu() {
        if (modeButton == null) return;
        if (voiceController.state() != VoiceController.State.IDLE || activeTarget != null) {
            setStatus(R.string.ime_status_finish_before_mode_change, true);
            return;
        }
        PopupMenu popup = new PopupMenu(this, modeButton);
        for (ProcessingMode mode : ProcessingMode.values()) {
            popup.getMenu()
                    .add(Menu.NONE, MENU_MODE_BASE + mode.ordinal(), mode.ordinal(),
                            localizedModeLabel(mode))
                    .setCheckable(true)
                    .setChecked(mode == selectedMode);
        }
        popup.setOnMenuItemClickListener(item -> {
            int ordinal = item.getItemId() - MENU_MODE_BASE;
            ProcessingMode[] modes = ProcessingMode.values();
            if (ordinal < 0 || ordinal >= modes.length) return false;
            selectedMode = modes[ordinal];
            refreshModeButton();
            setStatus(getString(
                    R.string.ime_status_mode_selected,
                    localizedModeLabel(selectedMode)), false);
            return true;
        });
        popup.show();
    }

    private void refreshModeButton() {
        if (modeButton == null) return;
        String label = localizedModeLabel(selectedMode);
        String displayLabel = compactToolbar ? localizedCompactModeLabel(selectedMode) : label;
        modeButton.setText(compactToolbar
                ? displayLabel
                : getString(R.string.ime_mode_button, displayLabel));
        modeButton.setContentDescription(getString(R.string.ime_cd_choose_mode_current, label));
    }

    private void showMoreMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        boolean pendingDetached = pendingDetachedTarget() != null;
        boolean pendingAudio = pipeline.hasRecoverableAudio();
        boolean sessionActive = activeTarget != null
                || voiceController.state() != VoiceController.State.IDLE;
        if (pendingDetached && activeTarget == null) {
            boolean confirmationArmed = hasVisibleVoiceDraft()
                    && System.currentTimeMillis() <= discardConfirmationDeadline;
            popup.getMenu().add(
                    Menu.NONE,
                    MENU_DISCARD,
                    0,
                    confirmationArmed
                            ? R.string.ime_menu_confirm_discard_current
                            : R.string.ime_menu_discard_current);
            popup.getMenu().add(
                    Menu.NONE,
                    MENU_SETTINGS,
                    1,
                    R.string.ime_key_settings);
        } else if (sessionActive) {
            boolean confirmationArmed = hasVisibleVoiceDraft()
                    && System.currentTimeMillis() <= discardConfirmationDeadline;
            popup.getMenu().add(
                    Menu.NONE,
                    MENU_DISCARD,
                    0,
                    confirmationArmed
                            ? R.string.ime_menu_confirm_discard_current
                            : R.string.ime_menu_discard_current);
        } else {
            LastVoiceCommit commit = lastCommit;
            if (commit != null) {
                popup.getMenu().add(
                        Menu.NONE,
                        MENU_UNDO,
                        0,
                        R.string.ime_key_undo);
            }
            if (pendingAudio && !sensitiveField) {
                popup.getMenu().add(
                        Menu.NONE,
                        MENU_RECOVER_AUDIO,
                        0,
                        R.string.ime_menu_recover_saved_audio);
                popup.getMenu().add(
                        Menu.NONE,
                        MENU_DISCARD_AUDIO,
                        1,
                        R.string.ime_menu_discard_saved_audio);
            }
            if (recoverableDraft.hasDraft() && !sensitiveField) {
                popup.getMenu().add(
                        Menu.NONE,
                        MENU_INSERT_RECOVERABLE_DRAFT,
                        0,
                        R.string.ime_menu_insert_recoverable_draft);
                popup.getMenu().add(
                        Menu.NONE,
                        MENU_DISCARD_RECOVERABLE_DRAFT,
                        1,
                        R.string.ime_menu_discard_recoverable_draft);
            }
            if (commit != null
                    && commit.originalSelection.isEmpty()
                    && !commit.rawText.isBlank()
                    && !commit.rawText.equals(commit.insertedText)) {
                popup.getMenu().add(
                        Menu.NONE,
                        MENU_RESTORE_RAW,
                        1,
                        R.string.ime_key_raw);
            }
            if (commit != null
                    && keyboardToolbarPrivacy.teachVisible()
                    && TeachCorrectionResolver.isEligible(commit.teachRecord)) {
                popup.getMenu().add(
                        Menu.NONE,
                        MENU_TEACH,
                        2,
                        R.string.ime_key_teach);
            }
            popup.getMenu().add(
                    Menu.NONE,
                    MENU_DICTIONARY,
                    3,
                    R.string.ime_key_dictionary);
            popup.getMenu().add(
                    Menu.NONE,
                    MENU_APP_PROFILE,
                    4,
                    R.string.ime_key_app_profile);
            popup.getMenu().add(
                    Menu.NONE,
                    MENU_SETTINGS,
                    5,
                    R.string.ime_key_settings);
            popup.getMenu().add(
                    Menu.NONE,
                    MENU_VOICE_DIAGNOSTICS,
                    6,
                    R.string.ime_menu_voice_diagnostics);
        }
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_DISCARD -> requestExplicitDiscard();
                case MENU_RESTORE_RAW -> restoreRawTranscript();
                case MENU_TEACH -> teachCorrection();
                case MENU_DICTIONARY -> openDictionary();
                case MENU_APP_PROFILE -> openAppProfile();
                case MENU_SETTINGS -> openSettings();
                case MENU_VOICE_DIAGNOSTICS -> openVoiceDiagnostics();
                case MENU_INSERT_RECOVERABLE_DRAFT -> insertRecoverableDraft();
                case MENU_DISCARD_RECOVERABLE_DRAFT -> discardRecoverableDraft();
                case MENU_RECOVER_AUDIO -> recoverSavedAudio();
                case MENU_DISCARD_AUDIO -> discardSavedAudio();
                case MENU_UNDO -> undoLastVoiceCommit();
                default -> {
                    return false;
                }
            }
            return true;
        });
        popup.show();
    }

    private void showPunctuationMenu(View anchor) {
        String[] punctuation = {"，", "。", "？", "！", "、", ",", ".", "?", "!"};
        PopupMenu popup = new PopupMenu(this, anchor);
        for (int index = 0; index < punctuation.length; index++) {
            popup.getMenu().add(
                    Menu.NONE,
                    MENU_PUNCTUATION_BASE + index,
                    index,
                    punctuation[index]);
        }
        popup.setOnMenuItemClickListener(item -> {
            int index = item.getItemId() - MENU_PUNCTUATION_BASE;
            if (index < 0 || index >= punctuation.length) return false;
            insertKeyboardText(punctuation[index]);
            return true;
        });
        popup.show();
    }

    private void refreshPostCommitActions() {
        // KBD-006 keeps Undo in the existing overflow menu. This prevents a transient,
        // low-frequency action from shrinking the fixed 48dp toolbar targets.
    }

    private static KeyboardToolbarPrivacyPolicy.State restrictedToolbarPrivacy() {
        return KeyboardToolbarPrivacyPolicy.resolve(
                PrivacyPolicyEngine.hardSafety(true, false));
    }

    private void applyKeyboardToolbarPrivacy() {
        KeyboardToolbarLayout toolbar = keyboardToolbarLayout;
        boolean voiceVisible = keyboardToolbarPrivacy.voiceVisible();
        if (toolbar != null) toolbar.setActionVisible("voice.mode", voiceVisible);
        if (keyboardInputModeLayout != null) {
            keyboardInputModeLayout.setVoiceAvailable(voiceVisible);
        }
        refreshVoicePulseVisibility();
    }

    private void rejectUnboundCandidateEvent() {
        // KBD-007 owns only the common page and View contract. RIM-005/Latin suggestion work must
        // bind a generation-aware engine before a page can be shown; an unbound event never writes.
        if (keyboardCandidateBar != null) keyboardCandidateBar.clear();
        setStatus(R.string.ime_status_candidate_engine_unavailable, true);
    }

    private void renderInputViewState() {
        if (microphone == null) return;
        VoiceController.State state = voiceController.state();
        updateMicrophone(state);
        if (sensitiveField) {
            clearTranscript();
            setStatus(R.string.ime_status_sensitive_field, false);
        } else if (pendingDetachedTarget() != null) {
            showRecoverableDraftOrClear();
            setStatus(R.string.ime_status_previous_voice_finalizing, false);
            mainHandler.removeCallbacks(pendingDetachedRefresh);
            mainHandler.postDelayed(pendingDetachedRefresh, DETACHED_STATE_REFRESH_MILLIS);
        } else if (recoveringSavedAudio) {
            setStatus(R.string.ime_status_recovering_audio, false);
        } else if (recoverableDraftLoading) {
            setStatus(R.string.ime_status_recoverable_draft_loading, false);
        } else if (activeTarget != null || state != VoiceController.State.IDLE) {
            if (activeTarget != null
                    && activeTarget.replacedSelection()
                    && !latestPreviewText.isBlank()) {
                showTranscript(compact(latestPreviewText, 180));
            } else {
                clearTranscript();
            }
            setStatus(preparingVoiceInput
                    ? getString(R.string.ime_status_preparing_local_data)
                    : finishingVoiceInput && state == VoiceController.State.IDLE
                    ? getString(R.string.ime_status_finishing_recording)
                    : localizedPipelineStatus(state), false);
        } else {
            clearTranscript();
            // Idle is the default, not a message. Keep the toolbar quiet until state changes.
            setStatus("", false);
        }
    }

    private void updateMicrophone(VoiceController.State state) {
        if (microphone == null) return;
        boolean recording = state == VoiceController.State.RECORDING && !finishingVoiceInput;
        boolean processing = finishingVoiceInput
                || state == VoiceController.State.TRANSCRIBING
                || state == VoiceController.State.POLISHING;
        boolean idle = state == VoiceController.State.IDLE && !preparingVoiceInput;
        boolean longSession = activeCaptureMode == DictationRequest.CaptureMode.CONTINUOUS;
        boolean holdSession = activeCaptureMode == DictationRequest.CaptureMode.HOLD_TO_TALK;
        boolean longRecording = recording && longSession;
        boolean holdRecording = recording && holdSession;
        boolean startAllowed = voiceStartAllowed();
        boolean editorKeysAllowed = activeTarget == null
                || (activeTarget.transactionWriter && activeVoiceTransaction != null);
        boolean spaceIsVoiceUnavailable = editorKeysAllowed && !startAllowed;
        boolean externalFinalizing = editorKeysAllowed && pendingDetachedTarget() != null;
        boolean voiceUnavailable = editorKeysAllowed && !startAllowed && !externalFinalizing;
        if (keyboardInputModeLayout != null) {
            keyboardInputModeLayout.setSwitchingEnabled(
                    idle
                            && !finishingVoiceInput
                            && activeTarget == null
                            && !externalFinalizing);
        }

        if (voicePulse != null) {
            voicePulse.setPhase(preparingVoiceInput
                    ? VoicePulseView.Phase.PREPARING
                    : recording
                    ? VoicePulseView.Phase.LISTENING
                    : processing || externalFinalizing
                    ? VoicePulseView.Phase.PROCESSING
                    : VoicePulseView.Phase.IDLE);
            refreshVoicePulseVisibility();
        }

        setEditingKeysEnabled(editorKeysAllowed, startAllowed);
        if (switchKeyboardButton != null && activeTarget != null) {
            // Switching IMEs is a lifecycle cancellation, not a content-key preemption.
            // CMP-006 owns that path; CMP-005 only keeps concrete editor keys available.
            switchKeyboardButton.setEnabled(false);
        }
        if (keyboardInputModeLayout == null) {
            setPrimaryKeyStyle(microphone, longRecording);
        } else {
            microphone.setBackgroundResource(R.drawable.ime_voice_button_background);
        }
        microphone.setSelected(longRecording);
        int longCompactLabel = longRecording
                ? R.string.ime_key_finish_dictation_compact
                : processing && longSession
                ? R.string.ime_key_processing_compact
                : preparingVoiceInput && longSession
                ? R.string.ime_key_preparing_compact
                : R.string.ime_key_long_dictation_compact;
        // This control lives in a compact status toolbar; its accessible description carries the
        // full wording while the visual label stays short at every screen width.
        if (keyboardInputModeLayout == null) {
            microphone.setForeground(null);
            microphone.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            microphone.setText(longCompactLabel);
        } else {
            setCenteredIcon(microphone, R.drawable.ime_ic_microphone);
        }
        microphone.setEnabled(startAllowed || longRecording);
        microphone.setContentDescription(getString(longRecording
                ? R.string.ime_cd_finish_long_dictation
                : processing && longSession
                ? R.string.ime_cd_processing
                : preparingVoiceInput && longSession
                ? R.string.ime_cd_preparing
                : externalFinalizing
                ? R.string.ime_cd_processing
                : voiceUnavailable
                ? R.string.ime_cd_long_dictation_unavailable
                : holdSession
                ? R.string.ime_cd_long_dictation_disabled_during_hold
                : R.string.ime_cd_start_long_dictation));
        if (holdToTalkButton != null) {
            setPrimaryKeyStyle(holdToTalkButton,
                    startAllowed || holdRecording);
            holdToTalkButton.setSelected(holdRecording);
            int holdRegularLabel = spaceIsVoiceUnavailable
                    ? R.string.ime_key_space
                    : holdRecording
                    ? R.string.ime_key_release_to_finish
                    : processing && holdSession
                    ? R.string.ime_key_processing
                    : preparingVoiceInput && holdSession
                    ? R.string.ime_key_preparing
                    : longRecording
                    ? R.string.ime_key_listening
                    : R.string.ime_key_hold_to_talk;
            int holdCompactLabel = spaceIsVoiceUnavailable
                    ? R.string.ime_key_space
                    : holdRecording
                    ? R.string.ime_key_release_to_finish
                    : processing && holdSession
                    ? R.string.ime_key_processing_compact
                    : preparingVoiceInput && holdSession
                    ? R.string.ime_key_preparing_compact
                    : longRecording
                    ? R.string.ime_key_listening_compact
                    : R.string.ime_key_hold_to_talk_compact;
            holdToTalkButton.setText(voiceLabel(holdRegularLabel, holdCompactLabel));
            holdToTalkButton.setEnabled(shouldEnableHoldKey(
                    editorKeysAllowed, holdRecording, holdToTalkActive));
            holdToTalkButton.setAlpha(
                    activeTarget != null && (processing || longRecording) ? .55f : 1f);
            holdToTalkButton.setContentDescription(getString(spaceIsVoiceUnavailable
                    ? sensitiveField
                    ? R.string.ime_cd_space_sensitive
                    : R.string.ime_cd_space_voice_unavailable
                    : holdRecording
                    ? R.string.ime_cd_release_to_insert
                    : processing && holdSession
                    ? R.string.ime_cd_processing
                    : preparingVoiceInput && holdSession
                    ? R.string.ime_cd_preparing
                    : longSession
                    ? R.string.ime_cd_hold_disabled_during_long_dictation
                    : R.string.ime_cd_space_hold_to_talk));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            String stateDescription = getString(externalFinalizing
                    ? R.string.ime_state_finishing
                    : voiceUnavailable
                    ? R.string.ime_state_unavailable
                    : preparingVoiceInput
                    ? R.string.ime_state_preparing
                    : finishingVoiceInput
                    ? R.string.ime_state_finishing
                    : localizedPipelineStateResource(state));
            microphone.setStateDescription(stateDescription);
            if (holdToTalkButton != null) {
                holdToTalkButton.setStateDescription(stateDescription);
            }
        }
    }

    private void showPreparingState() {
        updateMicrophone(VoiceController.State.IDLE);
    }

    private void setEditingKeysEnabled(boolean editorEnabled, boolean modeEnabled) {
        if (modeButton != null) modeButton.setEnabled(modeEnabled);
        if (latinKeyboardLayout != null) latinKeyboardLayout.setInputEnabled(editorEnabled);
        if (keyboardCandidateBar != null) {
            keyboardCandidateBar.setInteractionEnabled(editorEnabled);
        }
        if (switchKeyboardButton != null) switchKeyboardButton.setEnabled(editorEnabled);
        if (punctuationButton != null) punctuationButton.setEnabled(editorEnabled);
        if (deleteButton != null) deleteButton.setEnabled(editorEnabled);
        if (enterButton != null) enterButton.setEnabled(editorEnabled);
        if (holdToTalkButton != null) {
            // A hold gesture must keep receiving ACTION_UP after recognition enters RECORDING.
            holdToTalkButton.setEnabled(editorEnabled || holdToTalkActive);
        }
    }

    static boolean shouldEnableHoldKey(
            boolean ordinarySpaceAvailable,
            boolean holdRecording,
            boolean holdGestureActive) {
        return ordinarySpaceAvailable || holdRecording || holdGestureActive;
    }

    private boolean voiceStartAllowed() {
        return !serviceDestroyed
                && screenOffReceiverRegistered
                && !voiceRestartBlockedByLifecycle
                && !sensitiveField
                && !recoverableDraftLoading
                && pendingDetachedTarget() == null
                && activeTarget == null
                && activeVoiceTransaction == null
                && voiceController.state() == VoiceController.State.IDLE;
    }

    private static CommitTarget pendingDetachedTarget() {
        PendingDetachedSession<CommitTarget> pending = PROCESS_PENDING_DETACHED.get();
        return pending == null ? null : pending.pendingTarget();
    }

    private void installPendingDetached(CommitTarget target) {
        PendingDetachedSession<CommitTarget> pending = new PendingDetachedSession<>(
                target,
                target.recoveryToken,
                () -> discardDetachedTarget(target));
        PROCESS_PENDING_DETACHED.compareAndSet(null, pending);
    }

    private static PendingDetachedSession<CommitTarget> detachedSessionFor(
            CommitTarget target) {
        if (target == null) return null;
        PendingDetachedSession<CommitTarget> pending = PROCESS_PENDING_DETACHED.get();
        return pending != null && pending.owns(target) ? pending : null;
    }

    private static void clearDetachedSession(PendingDetachedSession<CommitTarget> pending) {
        if (pending != null) PROCESS_PENDING_DETACHED.compareAndSet(pending, null);
    }

    private void completeDetachedSession(
            PendingDetachedSession<CommitTarget> pending, CommitTarget target) {
        if (pending == null) return;
        pending.complete();
        RecoverableDraftSlot.Draft draft = recoverableDraft.get();
        if (draft == null || draft.source() != target.recoveryToken) {
            clearDetachedSession(pending);
        }
    }

    private void cancelPipeline(String message, boolean announce) {
        CommitTarget cancelledTarget = activeTarget != null
                ? activeTarget
                : detachedTargetAwaitingResult;
        pipeline.discard();
        holdToTalkActive = false;
        preparingVoiceInput = false;
        finishingVoiceInput = false;
        activeCaptureMode = null;
        detachedTargetAwaitingResult = null;
        PendingDetachedSession<CommitTarget> pending = detachedSessionFor(cancelledTarget);
        if (pending != null) {
            pending.discard();
            clearDetachedSession(pending);
        }
        discardConfirmationDeadline = 0L;
        latestPreviewText = "";
        boolean cleaned = cancelActiveComposition();
        activeTarget = null;
        clearRecoverableDraftIfSource(cancelledTarget);
        updateMicrophone(VoiceController.State.IDLE);
        showRecoverableDraftOrClear();
        if (announce) {
            setStatus(cleaned
                    ? message
                    : getString(R.string.ime_status_composition_cleanup_failed), !cleaned);
        }
    }

    /**
     * Cancels every IME-owned voice run at an editor/window/security lifecycle boundary.
     *
     * <p>This is intentionally different from {@link #stopPipelinePreservingDraft}: stop asks the
     * recognizer to finish and therefore permits a later terminal result. Lifecycle cancellation
     * first terminalizes the exact target, then invalidates the pipeline generation through
     * {@link VoiceController#cancel()}; any callback already queued on the main thread sees neither
     * an active nor a detached target. A non-sensitive visible partial may remain as an encrypted
     * recoverable draft, but it is never inserted into a new editor.
     */
    private void cancelVoiceForLifecycle() {
        CommitTarget cancelledTarget = activeTarget != null
                ? activeTarget
                : detachedTargetAwaitingResult;
        if (cancelledTarget != null) cancelledTarget.markVoiceTerminal();

        activeTarget = null;
        detachedTargetAwaitingResult = null;
        if (voiceController != null) cancelControllerForLifecycle(voiceController);

        String recoverable = latestPreviewText;
        VoiceTransactionSession transaction = activeVoiceTransaction;
        if (recoverable.isBlank() && transaction != null) {
            recoverable = transaction.compositionText;
        }
        if (recoverable.isBlank() && activeComposition != null) {
            recoverable = activeComposition.composingText();
        }
        if (recoverable.isBlank() && activeV2Projection != null) {
            recoverable = activeV2Projection.projectedFullText();
        }
        if (cancelledTarget != null
                && !cancelledTarget.replacedSelection()
                && cancelledTarget.fieldKind != FieldKind.SENSITIVE
                && !sensitiveField
                && !recoverable.isBlank()) {
            saveRecoverableDraft(cancelledTarget, recoverable);
        }

        boolean cleanupProven = cancelActiveComposition();
        voiceRestartBlockedByLifecycle = lifecycleRestartBlocked(
                voiceRestartBlockedByLifecycle, cleanupProven, false);
        PendingDetachedSession<CommitTarget> pending = detachedSessionFor(cancelledTarget);
        if (pending != null) completeDetachedSession(pending, cancelledTarget);

        holdToTalkActive = false;
        preparingVoiceInput = false;
        finishingVoiceInput = false;
        activeCaptureMode = null;
        activeRecognitionRoute = null;
        discardConfirmationDeadline = 0L;
        latestPreviewText = "";
        if (!serviceDestroyed && voiceController != null) {
            updateMicrophone(VoiceController.State.IDLE);
            showRecoverableDraftOrClear();
        }
    }

    static BroadcastReceiver createScreenOffReceiver(Runnable cancellation) {
        Runnable action = java.util.Objects.requireNonNull(cancellation, "cancellation");
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    action.run();
                }
            }
        };
    }

    static void cancelControllerForLifecycle(VoiceController controller) {
        java.util.Objects.requireNonNull(controller, "controller").cancel();
    }

    static boolean lifecycleRestartBlocked(
            boolean alreadyBlocked, boolean cleanupProven, boolean editorSessionRotated) {
        return !editorSessionRotated && (alreadyBlocked || !cleanupProven);
    }

    private void registerScreenOffReceiver() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenOffReceiver, filter);
            }
            screenOffReceiverRegistered = true;
        } catch (RuntimeException unavailable) {
            // Voice stays fail-closed when the process cannot observe the lock/screen boundary.
            screenOffReceiverRegistered = false;
        }
    }

    private void unregisterScreenOffReceiver() {
        if (!screenOffReceiverRegistered) return;
        screenOffReceiverRegistered = false;
        try {
            unregisterReceiver(screenOffReceiver);
        } catch (RuntimeException ignored) {
            // Cancellation already happened; teardown must continue without reopening voice.
        }
    }

    private boolean cancelActiveComposition() {
        VoiceTransactionSession transaction = activeVoiceTransaction;
        if (transaction != null) {
            return cancelVoiceTransaction(transaction);
        }
        EditorProjection projection = activeV2Projection;
        clearSpeechCoreProjectionState();
        VoiceCompositionSession composition = activeComposition;
        activeComposition = null;
        boolean projectionSafe = true;
        if (projection != null) {
            ProjectionOutcome outcome = projection.discardConfirmed().outcome();
            projectionSafe = outcome == ProjectionOutcome.DISCARDED
                    || outcome == ProjectionOutcome.DISCARD_EDITOR_RETAINED;
        }
        return projectionSafe && (composition == null || composition.cancel());
    }

    private boolean cancelVoiceTransaction(VoiceTransactionSession session) {
        boolean cancelled = !session.compositionActive;
        boolean coordinatorReleased = false;
        try {
            session.terminal = true;
            if (session.compositionActive) {
                long revision = session.prepareComposition(session.latestSequence, "");
                EditorTransactionResult cleared = editorSessionManager.setVoiceComposition(
                        this, session.snapshot, "", revision);
                if (cleared instanceof EditorTransactionResult.Applied) {
                    EditorSessionSnapshot captured = captureCurrentTransactionSnapshot();
                    if (captured != null) {
                        session.completeComposition(captured);
                        EditorTransactionResult finished =
                                editorSessionManager.finishVoiceComposition(
                                        this, session.snapshot, session.revision);
                        cancelled = finished instanceof EditorTransactionResult.Applied;
                    }
                }
            }
            if (cancelled) {
                coordinatorReleased = session.cancelCoordinatorAfterCleanup();
            }
        } catch (RuntimeException unavailable) {
            cancelled = false;
        } finally {
            if (coordinatorReleased) clearVoiceTransactionState();
        }
        return cancelled && coordinatorReleased;
    }

    /**
     * Detaches from the old editor without discarding work. Recording is stopped normally so batch
     * and streaming backends can still produce a final result, which is routed to the recoverable
     * draft slot instead of a new field.
     */
    private void stopPipelinePreservingDraft(String message, boolean announce) {
        CommitTarget target = activeTarget;
        if (target == null) {
            if (detachedTargetAwaitingResult != null) return;
            if (voiceController.state() != VoiceController.State.IDLE) voiceController.cancel();
            holdToTalkActive = false;
            preparingVoiceInput = false;
            finishingVoiceInput = false;
            activeCaptureMode = null;
            updateMicrophone(VoiceController.State.IDLE);
            showRecoverableDraftOrClear();
            return;
        }

        VoiceController.State previousState = voiceController.state();
        boolean wasPreparing = preparingVoiceInput;
        VoiceCompositionSession composition = activeComposition;
        boolean awaitFinalResult = !wasPreparing;
        if (awaitFinalResult) {
            detachedTargetAwaitingResult = target;
            installPendingDetached(target);
            if (previousState == VoiceController.State.RECORDING) voiceController.stop();
        } else {
            // No recorder exists yet, so there is no speech or final result to preserve.
            voiceController.cancel();
        }

        holdToTalkActive = false;
        preparingVoiceInput = false;
        finishingVoiceInput = awaitFinalResult;
        if (!awaitFinalResult) activeCaptureMode = null;
        discardConfirmationDeadline = 0L;
        boolean preserved = preserveActiveDraft();
        activeTarget = null;
        updateMicrophone(awaitFinalResult
                ? VoiceController.State.TRANSCRIBING
                : VoiceController.State.IDLE);
        showRecoverableDraftOrClear();
        if (announce) {
            setStatus(preserved
                    ? message
                    : getString(R.string.ime_status_composition_preserve_uncertain), !preserved);
        }
    }

    private boolean preserveActiveComposition() {
        VoiceCompositionSession composition = activeComposition;
        activeComposition = null;
        return composition == null || composition.preserve();
    }

    private boolean preserveActiveDraft() {
        CommitTarget target = activeTarget;
        VoiceTransactionSession transaction = activeVoiceTransaction;
        if (transaction != null) {
            String fallback = latestPreviewText;
            latestPreviewText = "";
            return preserveVoiceTransactionDraft(target, transaction, fallback);
        }
        EditorProjection projection = activeV2Projection;
        if (projection != null) {
            String latest = latestV2Document == null
                    ? latestPreviewText
                    : latestV2Document.fullText();
            ProjectionResult frozen = projection.freeze();
            boolean preserved = frozen.outcome() == ProjectionOutcome.FROZEN
                    || frozen.outcome() == ProjectionOutcome.COMMITTED;
            String recoverable = frozen.recoverableText().orElse("");
            if (recoverable.isBlank()
                    && (!preserved || !latest.equals(projection.projectedFullText()))) {
                recoverable = latest;
            }
            boolean saved = recoverable.isBlank()
                    || saveRecoverableDraft(target, recoverable);
            latestPreviewText = "";
            clearSpeechCoreProjectionState();
            activeComposition = null;
            return (preserved || saved) && saved;
        }
        VoiceCompositionSession composition = activeComposition;
        boolean editorDraft = composition != null && composition.ownsComposition();
        String editorText = editorDraft ? composition.composingText() : "";
        boolean preserved = preserveActiveComposition();
        String fallback = latestPreviewText;
        latestPreviewText = "";
        if (target != null && target.replacedSelection()) {
            return preserved;
        }
        if (editorDraft) {
            boolean newerFallback = !fallback.isBlank() && !fallback.equals(editorText);
            if (!preserved || newerFallback) {
                boolean saved = saveRecoverableDraft(
                        target, newerFallback ? fallback : editorText);
                return preserved || saved;
            }
            return true;
        }
        if (fallback.isBlank()) return preserved;
        if (target != null && targetStillValid(target)) {
            EditorMutationResult inserted = guardedReplace(target.connection, 0, fallback, "");
            if (inserted == EditorMutationResult.APPLIED) return true;
        }
        return saveRecoverableDraft(target, fallback);
    }

    private boolean preserveVoiceTransactionDraft(
            CommitTarget target, VoiceTransactionSession session, String fallback) {
        boolean prepared = session.beginPreserving();
        boolean preserved = prepared && !session.compositionActive;
        boolean coordinatorReleased = false;
        if (prepared && session.compositionActive && editorSessionManager != null) {
            try {
                EditorTransactionResult result = editorSessionManager.finishVoiceComposition(
                        this, session.snapshot, session.revision);
                preserved = result instanceof EditorTransactionResult.Applied;
            } catch (RuntimeException unavailable) {
                preserved = false;
            }
        }
        if (preserved) {
            coordinatorReleased = session.compositionActive
                    ? session.completeCoordinatorAfterCommit()
                    : session.cancelCoordinatorAfterCleanup();
            preserved = coordinatorReleased;
        }
        String recovery = fallback == null ? "" : fallback;
        if (recovery.isBlank() && !preserved) recovery = session.compositionText;
        boolean saved = recovery.isBlank() || saveRecoverableDraft(target, recovery);
        if (coordinatorReleased) clearVoiceTransactionState();
        return (preserved || saved) && saved;
    }

    private void clearVoiceTransactionState() {
        VoiceTransactionSession session = activeVoiceTransaction;
        if (session != null && !session.coordinatorReleased()) return;
        activeVoiceTransaction = null;
        if (session != null) session.close();
    }

    private void releaseVoiceCoordinatorAfterEditorLifecycle() {
        VoiceTransactionSession session = activeVoiceTransaction;
        if (session == null) return;
        if (session.releaseAfterEditorLifecycle()) clearVoiceTransactionState();
    }

    private void insertRecoverableDraft() {
        RecoverableDraftSlot.Draft draft = recoverableDraft.get();
        if (draft == null || sensitiveField) return;
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            setStatus(R.string.ime_status_no_active_field, true);
            return;
        }
        // Recovery is deliberately insertion-only. InputConnection objects can be reused across
        // fields, so suffix-based deletion could erase unrelated text after a cursor move.
        EditorMutationResult result = guardedReplace(connection, 0, draft.text(), "");
        if (result == EditorMutationResult.APPLIED) {
            clearRecoverableDraft();
            clearTranscript();
            setStatus(R.string.ime_status_recoverable_draft_inserted, false);
        } else {
            setStatus(R.string.ime_status_recoverable_draft_kept, true);
        }
    }

    private void discardRecoverableDraft() {
        if (!recoverableDraft.hasDraft()) return;
        clearRecoverableDraft();
        clearTranscript();
        setStatus(R.string.ime_status_recoverable_draft_discarded, false);
    }

    /**
     * Starting a new recording is an explicit replacement action. Recovery remains available from
     * the overflow menu until then, but it no longer blocks the keyboard or forces a resolve step
     * into the primary voice flow.
     */
    private void replaceRecoverableVoiceForNewRecording() {
        if (recoverableDraft.hasDraft()) clearRecoverableDraft();
        if (pipeline.hasRecoverableAudio()) pipeline.discard();
    }

    private void showRecoverableDraftOrClear() {
        // Recovery is an overflow action, not the keyboard's primary content/status surface.
        clearTranscript();
    }

    private void showRecoverableDraftPreview() {
        RecoverableDraftSlot.Draft draft = recoverableDraft.get();
        if (draft != null && !sensitiveField) {
            showTranscript(draft.text());
        } else {
            clearTranscript();
        }
    }

    private boolean saveRecoverableDraft(CommitTarget source, String text) {
        return saveRecoverableDraft(source, text, null);
    }

    private boolean saveRecoverableDraft(
            CommitTarget source, String text, Runnable afterPersisted) {
        boolean saved = recoverableDraft.save(
                text, source == null ? null : source.recoveryToken);
        if (saved) persistRecoverableDraft(recoverableDraft.get().text(), afterPersisted);
        return saved;
    }

    private boolean saveRecoverableDraftFromResult(
            CommitTarget source, String text, DictationResult result) {
        String recoveryId = result == null || result.recoveryId() == null
                ? ""
                : result.recoveryId();
        boolean saved = recoverableDraft.save(
                text, source == null ? null : source.recoveryToken);
        if (!saved) return false;
        PROCESS_RECOVERABLE_AUDIO_ID.set(recoveryId);
        persistRecoverableDraft(
                recoverableDraft.get().text(),
                () -> pipeline.acknowledgeRecovery(recoveryId));
        return true;
    }

    private void clearRecoverableDraftIfSource(CommitTarget source) {
        RecoverableDraftSlot.Draft draft = recoverableDraft.get();
        if (source != null && draft != null && draft.source() == source.recoveryToken) {
            clearRecoverableDraft();
        }
    }

    private void clearRecoverableDraft() {
        RecoverableDraftSlot.Draft draft = recoverableDraft.get();
        PendingDetachedSession<CommitTarget> pending = PROCESS_PENDING_DETACHED.get();
        if (draft != null && pending != null && draft.source() == pending.recoveryToken) {
            pending.discard();
            clearDetachedSession(pending);
        }
        String recoveryId = PROCESS_RECOVERABLE_AUDIO_ID.getAndSet("");
        pipeline.acknowledgeRecovery(recoveryId);
        recoverableDraft.clear();
        persistRecoverableDraft("");
    }

    private void persistRecoverableDraft(String text) {
        persistRecoverableDraft(text, null);
    }

    private void persistRecoverableDraft(String text, Runnable afterPersisted) {
        // The UI thread only advances a lock-free version and enqueues work. Keystore and disk I/O
        // never hold a monitor that the IME thread needs.
        final long generation = DRAFT_STORAGE_GENERATION.incrementAndGet();
        try {
            localIo.execute(() -> {
                try {
                    String prepared = draftPreferences.prepare(text);
                    String preparedAudioId = draftPreferences.prepare(
                            PROCESS_RECOVERABLE_AUDIO_ID.get());
                    synchronized (DRAFT_STORAGE_LOCK) {
                        if (generation != DRAFT_STORAGE_GENERATION.get()) return;
                        draftPreferences.commitPrepared(Map.of(
                                RECOVERABLE_DRAFT_PREFERENCE, prepared,
                                RECOVERABLE_DRAFT_AUDIO_ID_PREFERENCE, preparedAudioId));
                    }
                    if (afterPersisted != null) afterPersisted.run();
                } catch (RuntimeException ignored) {
                    postUiIfAlive(() -> setStatus(
                            R.string.ime_status_recoverable_draft_memory_only, true));
                }
            });
        } catch (RejectedExecutionException ignored) {
            postUiIfAlive(() -> setStatus(
                    R.string.ime_status_recoverable_draft_memory_only, true));
        }
    }

    private void requestExplicitDiscard() {
        long now = System.currentTimeMillis();
        if (hasVisibleVoiceDraft() && now > discardConfirmationDeadline) {
            discardConfirmationDeadline = now + DISCARD_CONFIRM_WINDOW_MILLIS;
            setStatus(R.string.ime_status_confirm_discard, true);
            return;
        }
        PendingDetachedSession<CommitTarget> pending = PROCESS_PENDING_DETACHED.get();
        if (activeTarget == null && pending != null) {
            discardConfirmationDeadline = 0L;
            Runnable discardOwner = pending.discard();
            RecoverableDraftSlot.Draft draft = recoverableDraft.get();
            if (draft != null && draft.source() == pending.recoveryToken) {
                recoverableDraft.clear();
                PROCESS_RECOVERABLE_AUDIO_ID.set("");
                persistRecoverableDraft("");
            }
            clearDetachedSession(pending);
            if (discardOwner != null) discardOwner.run();
            setStatus(R.string.ime_status_cancelled, false);
            return;
        }
        cancelPipeline(getString(R.string.ime_status_cancelled), true);
    }

    private void recoverSavedAudio() {
        if (sensitiveField || recoverableDraftLoading || recoverableDraft.hasDraft()) return;
        if (!pipeline.hasRecoverableAudio()) {
            setStatus(R.string.ime_status_recoverable_audio_unavailable, true);
            return;
        }
        if (recoveringSavedAudio || voiceController.state() != VoiceController.State.IDLE) return;
        recoveringSavedAudio = true;
        updateMicrophone(VoiceController.State.TRANSCRIBING);
        setStatus(R.string.ime_status_recovering_audio, false);
        try {
            localIo.execute(() -> {
                try {
                    AppSettings settings = settingsRepository.load();
                    PersonalizationSnapshot snapshot = settings.personalizationEnabled()
                            ? personalizationStore.snapshot("")
                            : PersonalizationSnapshot.empty();
                    DictationRequest request = new DictationRequest(
                            settings,
                            ProcessingMode.VERBATIM,
                            new InputContext("", FieldKind.GENERAL, "", "", false),
                            snapshot,
                            DictationRequest.CaptureMode.SINGLE_UTTERANCE);
                    boolean started = pipeline.recover(request, recoveryListener());
                    if (!started) postUiIfAlive(() -> {
                        recoveringSavedAudio = false;
                        renderInputViewState();
                        setStatus(R.string.ime_status_recoverable_audio_unavailable, true);
                    });
                } catch (RuntimeException error) {
                    postUiIfAlive(() -> {
                        recoveringSavedAudio = false;
                        renderInputViewState();
                        setStatus(safeMessage(error.getMessage()), true);
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
            recoveringSavedAudio = false;
            renderInputViewState();
            setStatus(R.string.ime_status_recoverable_audio_failed_local, true);
        }
    }

    private VoicePipeline.Listener recoveryListener() {
        return new VoicePipeline.Listener() {
            @Override
            public void onState(VoicePipeline.State state, String message) {
                postUiIfAlive(() -> {
                    setStatus(R.string.ime_status_recovering_audio, false);
                    updateMicrophone(VoicePipelineAdapter.controllerState(state));
                });
            }

            @Override
            public void onResult(DictationResult result) {
                postUiIfAlive(() -> {
                    recoveringSavedAudio = false;
                    boolean saved = saveRecoverableDraftFromResult(
                            null, result.voiceResult().finalText(), result);
                    updateMicrophone(VoiceController.State.IDLE);
                    showRecoverableDraftPreview();
                    setStatus(saved
                            ? R.string.ime_status_recoverable_audio_ready
                            : R.string.ime_status_recoverable_draft_conflict, !saved);
                });
            }

            @Override
            public void onError(String message) {
                postUiIfAlive(() -> {
                    recoveringSavedAudio = false;
                    updateMicrophone(VoiceController.State.IDLE);
                    setStatus(getString(
                            R.string.ime_status_recoverable_audio_failed,
                            safeMessage(message)), true);
                });
            }
        };
    }

    private void discardSavedAudio() {
        if (!pipeline.hasRecoverableAudio()) return;
        discardConfirmationDeadline = 0L;
        pipeline.discard();
        recoveringSavedAudio = false;
        renderInputViewState();
        setStatus(R.string.ime_status_recoverable_audio_discarded, false);
    }

    private void discardDetachedTarget(CommitTarget target) {
        if (detachedTargetAwaitingResult != target) return;
        pipeline.discard();
        detachedTargetAwaitingResult = null;
        discardConfirmationDeadline = 0L;
        clearRecoverableDraftIfSource(target);
        holdToTalkActive = false;
        preparingVoiceInput = false;
        finishingVoiceInput = false;
        activeCaptureMode = null;
        if (serviceDestroyed) {
            return;
        }
        updateMicrophone(VoiceController.State.IDLE);
        showRecoverableDraftOrClear();
        setStatus(R.string.ime_status_cancelled, false);
    }

    private boolean hasVisibleVoiceDraft() {
        if (recoverableDraft.hasDraft() || pipeline.hasRecoverableAudio()) return true;
        VoiceTransactionSession transaction = activeVoiceTransaction;
        if (transaction != null && transaction.compositionActive) return true;
        EditorProjection projection = activeV2Projection;
        if (projection != null && !projection.projectedFullText().isBlank()) return true;
        VoiceCompositionSession composition = activeComposition;
        if (composition != null && composition.ownsComposition()) return true;
        if (transcript == null || transcript.getVisibility() != View.VISIBLE) return false;
        String visible = transcript.getText() == null ? "" : transcript.getText().toString().trim();
        return !visible.isEmpty()
                && !visible.equals(getString(R.string.ime_transcript_listening))
                && !visible.equals(getString(R.string.ime_transcript_hint));
    }

    private void discardActiveComposition() {
        VoiceTransactionSession transaction = activeVoiceTransaction;
        if (transaction != null) cancelVoiceTransaction(transaction);
        clearSpeechCoreProjectionState();
        VoiceCompositionSession composition = activeComposition;
        activeComposition = null;
        if (composition != null) composition.discardState();
    }

    private void clearSpeechCoreProjectionState() {
        activeV2Projection = null;
        latestV2Document = null;
        activeV2ProjectionMode = null;
    }

    private void invalidateLastCommit() {
        lastCommit = null;
        refreshPostCommitActions();
    }

    private void setStatus(String message, boolean error) {
        if (status == null) return;
        String safe = safeMessage(message);
        status.setText(safe);
        status.setTextColor(getColor(error ? R.color.ime_error : R.color.ime_on_surface_variant));
        refreshStatusVisibilityForInputMode(keyboardInputModeLayout == null
                ? null
                : keyboardInputModeLayout.mode());
    }

    private void refreshStatusVisibilityForInputMode(KeyboardInputModeLayout.Mode mode) {
        if (status == null) return;
        CharSequence message = status.getText();
        boolean hasMessage = message != null && !message.toString().isBlank();
        // Voice pipeline details belong to the voice surface. Keeping a stale recognition
        // failure beside ordinary typing controls makes the QWERTY page look broken and steals
        // the toolbar's visual hierarchy, while the same message remains available on return.
        boolean visible = hasMessage && mode != KeyboardInputModeLayout.Mode.QWERTY;
        status.setAccessibilityLiveRegion(visible
                ? View.ACCESSIBILITY_LIVE_REGION_POLITE
                : View.ACCESSIBILITY_LIVE_REGION_NONE);
        status.setVisibility(visible ? View.VISIBLE : View.GONE);
        refreshVoicePulseVisibility();
    }

    private void refreshVoicePulseVisibility() {
        if (voicePulse == null) return;
        boolean activeVoice = preparingVoiceInput
                || finishingVoiceInput
                || activeTarget != null
                || (voiceController != null && voiceController.state() != VoiceController.State.IDLE);
        boolean statusVisible = status != null && status.getVisibility() == View.VISIBLE;
        voicePulse.setVisibility(keyboardToolbarPrivacy.voiceVisible()
                && activeVoice
                && statusVisible
                ? View.VISIBLE
                : View.GONE);
    }

    private void setStatus(int messageResource, boolean error) {
        setStatus(getString(messageResource), error);
    }

    private void showTranscript(String value) {
        if (transcript == null) return;
        transcript.setText(safeMessage(value));
        transcript.setVisibility(View.VISIBLE);
    }

    private void clearTranscript() {
        if (transcript == null) return;
        transcript.setText(R.string.ime_transcript_hint);
        transcript.setVisibility(View.GONE);
    }

    private void postUi(Runnable runnable) {
        if (mainHandler != null) mainHandler.post(runnable);
    }

    private void postUiIfAlive(Runnable runnable) {
        postUi(() -> {
            if (!serviceDestroyed) runnable.run();
        });
    }

    private void shadowStartInput(EditorInfo editor) {
        if (!editorSessionShadowHealthy || editorSessionManager == null) return;
        try {
            // Keep framework capability lookup inside the shadow failure boundary. An OEM getter
            // failure must never interrupt the authoritative legacy onStartInput path.
            InputConnection connection = getCurrentInputConnection();
            long shadowEpoch = editorSessionManager.onStartInput(editor, connection);
            if (shadowEpoch != editorEpoch) disableEditorSessionShadow();
        } catch (RuntimeException shadowFailure) {
            disableEditorSessionShadow();
        }
    }

    private void shadowSelectionChanged(int start, int end) {
        if (!editorSessionShadowHealthy || editorSessionManager == null) return;
        try {
            editorSessionManager.onSelectionChanged(start, end);
        } catch (RuntimeException shadowFailure) {
            disableEditorSessionShadow();
        }
    }

    private EditorSessionManager.CaptureResult shadowCaptureEditorSession(
            InputConnection connection,
            CharSequence selected,
            CharSequence before,
            CharSequence after) {
        if (!editorSessionShadowHealthy || editorSessionManager == null) {
            return unavailableEditorSessionCapture();
        }
        try {
            // Ordinary keys consume this snapshot through EDT-016. Legacy voice/Undo/Raw callers
            // still treat it as a sidecar and cannot derive a fallback write from rejection.
            return editorSessionManager.captureFromEvidence(connection, selected, before, after);
        } catch (RuntimeException shadowFailure) {
            disableEditorSessionShadow();
            return unavailableEditorSessionCapture();
        }
    }

    private static EditorSessionManager.CaptureResult unavailableEditorSessionCapture() {
        return new EditorSessionManager.Rejected(
                EditorSessionManager.CaptureFailure.NO_ACTIVE_SESSION);
    }

    private void shadowFinishInput() {
        if (!editorSessionShadowHealthy || editorSessionManager == null) return;
        try {
            long shadowEpoch = editorSessionManager.onFinishInput();
            if (shadowEpoch != editorEpoch) disableEditorSessionShadow();
        } catch (RuntimeException shadowFailure) {
            disableEditorSessionShadow();
        }
    }

    private void disableEditorSessionShadow() {
        editorSessionShadowHealthy = false;
        closeRimeComposition(false);
        EditorSessionManager manager = editorSessionManager;
        if (manager == null) return;
        try {
            // Any adapter failure revokes ordinary-key authority. Legacy voice behavior remains
            // isolated until EDT-017; migrated keys never fall back to it.
            manager.close();
        } catch (RuntimeException ignored) {
            // The manager is now unreachable and migrated keys fail closed.
        }
        releaseVoiceCoordinatorAfterEditorLifecycle();
    }

    private void closeEditorSessionShadow() {
        EditorSessionManager manager = editorSessionManager;
        editorSessionShadowHealthy = false;
        editorSessionManager = null;
        if (manager == null) return;
        try {
            manager.close();
        } catch (RuntimeException ignored) {
            // Service teardown still proceeds; no shadow callback retains this manager instance.
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        closeRimeComposition(false);
        rimeAvailabilityRequest++;
        cancelVoiceForLifecycle();
        super.onStartInput(attribute, restarting);
        editorEpoch++;
        shadowStartInput(attribute);
        releaseVoiceCoordinatorAfterEditorLifecycle();
        voiceRestartBlockedByLifecycle = lifecycleRestartBlocked(
                voiceRestartBlockedByLifecycle, true, true);
        currentEditor = attribute;
        InputContextClassifier.PrivacyClassification privacy =
                InputContextClassifier.classifyPrivacy(attribute);
        currentFieldKind = InputContextClassifier.classify(attribute);
        currentKeyboardFieldProfile = KeyboardFieldProfile.from(attribute, currentFieldKind);
        if (latinKeyboardLayout != null) {
            latinKeyboardLayout.setFieldProfile(currentKeyboardFieldProfile);
        }
        sensitiveField = privacy.sensitive();
        currentLearningAllowed = privacy.learningAllowed();
        if (keyboardCandidateBar != null) {
            keyboardCandidateBar.clear();
            keyboardCandidateBar.setPlaintextVisible(!sensitiveField);
        }
        keyboardToolbarPrivacy = KeyboardToolbarPrivacyPolicy.resolve(
                PrivacyPolicyEngine.hardSafety(
                        privacy.sensitive(), privacy.learningAllowed()));
        applyKeyboardToolbarPrivacy();
        if (keyboardInputModeLayout != null) {
            keyboardInputModeLayout.select(privacy.sensitive()
                    ? KeyboardInputModeLayout.Mode.QWERTY
                    : KeyboardInputModeLayout.Mode.VOICE);
        }
        AppProfile profile = attribute == null
                ? null
                : appProfileRepository.get(safe(attribute.packageName));
        selectedMode = profile == null
                ? settingsRepository.loadDefaultMode()
                : profile.mode();
        refreshModeButton();
        currentSelectionStart = attribute == null ? -1 : attribute.initialSelStart;
        currentSelectionEnd = attribute == null ? -1 : attribute.initialSelEnd;
        refreshRimeAvailability(editorEpoch, privacy);
        refreshEnterKey();
        invalidateLastCommit();
        renderInputViewState();
    }

    @Override
    public void onUpdateSelection(
            int oldSelStart,
            int oldSelEnd,
            int newSelStart,
            int newSelEnd,
            int candidatesStart,
            int candidatesEnd) {
        super.onUpdateSelection(
                oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        CommitTarget target = activeTarget;
        currentSelectionStart = newSelStart;
        currentSelectionEnd = newSelEnd;
        shadowSelectionChanged(newSelStart, newSelEnd);
        RimeCompositionLease rimeLease = activeRimeLease;
        if (rimeLease != null
                && !rimeLease.acceptsSelection(
                        newSelStart, newSelEnd, candidatesStart, candidatesEnd)) {
            closeRimeComposition(false);
        }
        VoiceCompositionSession composition = activeComposition;
        EditorProjection v2Projection = activeV2Projection;
        VoiceTransactionSession transaction = activeVoiceTransaction;
        boolean ownedTransactionMove = target != null
                && transaction != null
                && transaction.generation == target.voiceGeneration
                && transaction.acceptsSelection(
                        newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        boolean ownedV2Move = target != null
                && v2Projection != null
                && v2Projection.acceptsSelection(
                        newSelStart,
                        newSelEnd,
                        candidatesStart,
                        candidatesEnd);
        boolean ownedCompositionMove = target != null
                && composition != null
                && composition.ownsComposition()
                && composition.acceptsSelection(
                        newSelStart,
                        newSelEnd,
                        candidatesStart,
                        candidatesEnd);
        if (target != null
                && v2Projection != null
                && !ownedV2Move) {
            stopPipelinePreservingDraft(
                    getString(R.string.ime_status_cursor_moved_preserved), true);
            return;
        }
        if (target != null
                && composition != null
                && composition.ownsComposition()
                && !ownedCompositionMove) {
            stopPipelinePreservingDraft(
                    getString(R.string.ime_status_cursor_moved_preserved), true);
            return;
        }
        if (target != null
                && transaction != null
                && !ownedTransactionMove) {
            stopPipelinePreservingDraft(
                    getString(R.string.ime_status_cursor_moved_preserved), true);
            return;
        }
        if (!ownedCompositionMove
                && !ownedV2Move
                && !ownedTransactionMove
                && target != null
                && target.selectionStart >= 0
                && target.selectionEnd >= 0
                && (target.selectionStart != newSelStart || target.selectionEnd != newSelEnd)) {
            stopPipelinePreservingDraft(
                    getString(R.string.ime_status_cursor_moved_preserved), true);
        }
    }

    @Override
    public void onFinishInput() {
        if (latinKeyboardLayout != null) latinKeyboardLayout.cancelTransientGestures();
        closeRimeComposition(true);
        rimeAvailabilityRequest++;
        cancelVoiceForLifecycle();
        editorEpoch++;
        shadowFinishInput();
        releaseVoiceCoordinatorAfterEditorLifecycle();
        voiceRestartBlockedByLifecycle = lifecycleRestartBlocked(
                voiceRestartBlockedByLifecycle, true, true);
        currentEditor = null;
        sensitiveField = false;
        currentLearningAllowed = false;
        if (keyboardCandidateBar != null) {
            keyboardCandidateBar.setPlaintextVisible(false);
        }
        keyboardToolbarPrivacy = restrictedToolbarPrivacy();
        applyKeyboardToolbarPrivacy();
        invalidateLastCommit();
        super.onFinishInput();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        if (latinKeyboardLayout != null) latinKeyboardLayout.cancelTransientGestures();
        closeRimeComposition(false);
        cancelVoiceForLifecycle();
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onWindowHidden() {
        if (latinKeyboardLayout != null) latinKeyboardLayout.cancelTransientGestures();
        closeRimeComposition(false);
        cancelVoiceForLifecycle();
        super.onWindowHidden();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    private void refreshEnterKey() {
        if (enterButton == null) return;
        int action = currentEditor == null
                ? EditorInfo.IME_ACTION_NONE
                : currentEditor.imeOptions & EditorInfo.IME_MASK_ACTION;
        switch (action) {
            case EditorInfo.IME_ACTION_SEND -> {
                enterButton.setText(R.string.ime_key_action_send);
                enterButton.setContentDescription(getString(R.string.ime_cd_action_send));
            }
            case EditorInfo.IME_ACTION_SEARCH -> {
                enterButton.setText(R.string.ime_key_action_search);
                enterButton.setContentDescription(getString(R.string.ime_cd_action_search));
            }
            case EditorInfo.IME_ACTION_DONE -> {
                enterButton.setText(R.string.ime_key_action_done);
                enterButton.setContentDescription(getString(R.string.ime_cd_action_done));
            }
            case EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_GO -> {
                enterButton.setText(R.string.ime_key_action_next);
                enterButton.setContentDescription(getString(R.string.ime_cd_action_next));
            }
            default -> {
                enterButton.setText(R.string.ime_key_enter);
                enterButton.setContentDescription(getString(R.string.ime_cd_enter));
            }
        }
    }

    @Override
    public void onDestroy() {
        if (latinKeyboardLayout != null) latinKeyboardLayout.cancelTransientGestures();
        closeRimeComposition(true);
        cancelVoiceForLifecycle();
        unregisterScreenOffReceiver();
        closeEditorSessionShadow();
        releaseVoiceCoordinatorAfterEditorLifecycle();
        serviceDestroyed = true;
        activeTarget = null;
        closeServiceResources();
        super.onDestroy();
    }

    private void closeServiceResources() {
        if (resourcesClosed) return;
        resourcesClosed = true;
        if (mainHandler != null) mainHandler.removeCallbacks(pendingDetachedRefresh);
        pipeline.shutdown();
        try {
            // localIo is single-threaded: any encrypted draft write accepted before this close is
            // guaranteed to run first. shutdown(), unlike shutdownNow(), drains that queue.
            localIo.execute(personalizationStore::close);
        } catch (RejectedExecutionException ignored) {
            personalizationStore.close();
        }
        localIo.shutdown();
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void addWeighted(LinearLayout row, View child, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                weight);
        params.setMarginStart(dp(2));
        params.setMarginEnd(dp(2));
        row.addView(child, params);
    }

    private void addFixed(LinearLayout row, View child, int widthDp) {
        child.setPadding(dp(6), 0, dp(6), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(widthDp),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(2));
        params.setMarginEnd(dp(2));
        row.addView(child, params);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private CenteredIconButton key(
            String label,
            String contentDescription,
            float weight,
            View.OnClickListener listener) {
        CenteredIconButton button = new CenteredIconButton(this);
        button.setText(label);
        button.setContentDescription(contentDescription);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        button.setTextSize(13);
        button.setAutoSizeTextTypeUniformWithConfiguration(
                9, 13, 1, TypedValue.COMPLEX_UNIT_SP);
        button.setMinWidth(dp(weight >= 2f ? 96 : 48));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setBackgroundResource(R.drawable.ime_key_background);
        button.setTextColor(getColorStateList(R.color.ime_key_text));
        int horizontalPadding = compactLayout ? 6 : 12;
        button.setPadding(dp(horizontalPadding), 0, dp(horizontalPadding), 0);
        button.setElevation(0f);
        button.setStateListAnimator(null);
        button.setOnClickListener(listener);
        ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(2));
        params.setMarginEnd(dp(2));
        button.setLayoutParams(params);
        return button;
    }

    private void setCenteredIcon(CenteredIconButton button, int drawableResource) {
        if (button == null) return;
        button.setCenteredIconResource(drawableResource);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
    }

    private void setPrimaryKeyStyle(Button button, boolean primary) {
        if (button == null) return;
        button.setBackgroundResource(primary
                ? R.drawable.ime_primary_key_background
                : R.drawable.ime_key_background);
        button.setTextColor(getColorStateList(primary
                ? R.color.ime_primary_key_text
                : R.color.ime_key_text));
    }

    private int voiceLabel(int regularResource, int compactResource) {
        return compactLayout ? compactResource : regularResource;
    }

    private CenteredIconButton key(
            int labelResource,
            int contentDescriptionResource,
            float weight,
            View.OnClickListener listener) {
        return key(
                getString(labelResource),
                getString(contentDescriptionResource),
                weight,
                listener);
    }

    private static String tailCodePoints(CharSequence value, int maximum) {
        if (value == null) return "";
        String text = value.toString();
        int count = codePointCount(text);
        return count <= maximum
                ? text
                : text.substring(text.offsetByCodePoints(0, count - maximum));
    }

    private static String headCodePoints(CharSequence value, int maximum) {
        if (value == null) return "";
        String text = value.toString();
        int count = codePointCount(text);
        return count <= maximum ? text : text.substring(0, text.offsetByCodePoints(0, maximum));
    }

    private static int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String compact(String value, int maximum) {
        String clean = value.replace('\n', ' ').trim();
        if (codePointCount(clean) <= maximum) return clean;
        return clean.substring(0, clean.offsetByCodePoints(0, maximum)) + "…";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private String localizedModeLabel(ProcessingMode mode) {
        return getString(switch (mode) {
            case AUTO -> R.string.mode_auto;
            case VERBATIM -> R.string.mode_verbatim;
            case SMART -> R.string.mode_smart;
            case TRANSLATE -> R.string.mode_translate;
        });
    }

    private String localizedCompactModeLabel(ProcessingMode mode) {
        return getString(switch (mode) {
            case AUTO -> R.string.ime_mode_auto_compact;
            case VERBATIM -> R.string.ime_mode_verbatim_compact;
            case SMART -> R.string.ime_mode_smart_compact;
            case TRANSLATE -> R.string.ime_mode_translate_compact;
        });
    }

    private String localizedPipelineState(VoiceController.State state) {
        return getString(localizedPipelineStateResource(state));
    }

    private int localizedPipelineStateResource(VoiceController.State state) {
        return switch (state) {
            case IDLE -> R.string.ime_state_idle;
            case RECORDING -> R.string.ime_state_recording;
            case TRANSCRIBING -> R.string.ime_state_transcribing;
            case POLISHING -> R.string.ime_state_polishing;
        };
    }

    static boolean selectionCoordinatesStillMatch(
            int capturedStart,
            int capturedEnd,
            int currentStart,
            int currentEnd) {
        boolean capturedKnown = capturedStart >= 0 && capturedEnd >= 0;
        boolean currentKnown = currentStart >= 0 && currentEnd >= 0;
        return !capturedKnown
                || !currentKnown
                || capturedStart == currentStart && capturedEnd == currentEnd;
    }

    private String localizedPipelineStatus(VoiceController.State state) {
        return switch (state) {
            case IDLE -> getString(
                    R.string.ime_status_ready_mode, localizedModeLabel(selectedMode));
            case RECORDING -> getString(R.string.ime_status_listening);
            case TRANSCRIBING -> getString(R.string.ime_status_transcribing);
            case POLISHING -> getString(R.string.ime_status_polishing);
        };
    }

    private String localizedResultStatus(DictationResult.Outcome outcome) {
        if (outcome == null) return getString(R.string.ime_status_inserted);
        return getString(switch (outcome) {
            case INSERTED -> R.string.ime_status_inserted;
            case INSERTED_RECORDING_LIMIT -> R.string.ime_status_inserted_recording_limit;
            case INSERTED_AFTER_SILENCE -> R.string.ime_status_inserted_after_silence;
            case VOICE_COMMAND_INSERTED -> R.string.ime_status_voice_command_inserted;
            case EXACT_AI_NOT_CONFIGURED -> R.string.ime_status_exact_ai_not_configured;
            case SELECTION_UPDATED -> R.string.ime_status_selection_updated;
            case TRANSLATED -> R.string.ime_status_translated;
            case SMART_EDITED -> R.string.ime_status_smart_edited;
            case AI_BLOCKED_EXACT -> R.string.ime_status_ai_blocked_exact;
            case EXACT_AI_FAILED -> R.string.ime_status_exact_ai_failed;
        });
    }

    private String safeMessage(String value) {
        String clean = safe(value).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").trim();
        return clean.isEmpty()
                ? getString(R.string.ime_status_voice_input_failed)
                : compact(clean, 180);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
