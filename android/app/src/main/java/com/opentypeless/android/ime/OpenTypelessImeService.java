package com.opentypeless.android.ime;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.opentypeless.android.HistoryActivity;
import com.opentypeless.android.MainActivity;
import com.opentypeless.android.AppProfileActivity;
import com.opentypeless.android.DictionaryActivity;
import com.opentypeless.android.R;
import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.context.InputContextClassifier;
import com.opentypeless.android.data.HistoryEntry;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.offline.LocalOfflineRecognizer;
import com.opentypeless.android.data.PersonalizationStore;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.AppProfile;
import com.opentypeless.android.settings.AppProfileRepository;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.SettingsRepository;
import com.opentypeless.android.security.SecurePreferences;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A deliberately small voice keyboard. It binds every asynchronous recognition run to an exact
 * editor epoch and cursor fingerprint so a late result can never spill into a different field.
 */
public final class OpenTypelessImeService extends InputMethodService {
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
    private static final int MENU_PUNCTUATION_BASE = 300;
    private static final long DISCARD_CONFIRM_WINDOW_MILLIS = 10_000L;
    // Two bounded network stages may run after capture: ASR and optional AI processing. Each may
    // legally consume a 20s connect + 120s read timeout, so teardown must cover the whole chain.
    private static final long DESTROY_FINALIZATION_TIMEOUT_MILLIS = 330_000L;
    private static final long DETACHED_STATE_REFRESH_MILLIS = 500L;
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

    static final class DetachedFinalizationGate {
        enum State { INACTIVE, WAITING, TERMINAL_ARRIVED, CLOSED }

        private final AtomicReference<State> state = new AtomicReference<>(State.INACTIVE);

        void begin() {
            state.compareAndSet(State.INACTIVE, State.WAITING);
        }

        boolean terminalArrived() {
            State current = state.get();
            return current == State.TERMINAL_ARRIVED
                    || state.compareAndSet(State.WAITING, State.TERMINAL_ARRIVED);
        }

        boolean claimTerminalHandler() {
            while (true) {
                State current = state.get();
                if (current == State.CLOSED || current == State.INACTIVE) return false;
                if (state.compareAndSet(current, State.CLOSED)) return true;
            }
        }

        boolean claimTimeout() {
            return state.compareAndSet(State.WAITING, State.CLOSED);
        }

        void close() {
            state.set(State.CLOSED);
        }
    }

    record SelectionEvidence(
            boolean known,
            boolean hasSelection,
            boolean selectedTextAvailable,
            int start,
            int end,
            String text) {}

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
        final Object recoveryToken = new Object();

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
                int selectionEnd) {
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
        }

        boolean replacedSelection() {
            return !selectedText.isEmpty();
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

        LastVoiceCommit(
                long editorEpoch,
                InputConnection connection,
                String insertedText,
                String originalSelection,
                String rawText,
                long historyId,
                String packageName,
                boolean learningAllowed) {
            this.editorEpoch = editorEpoch;
            this.connection = connection;
            this.insertedText = insertedText;
            this.originalSelection = originalSelection;
            this.rawText = rawText;
            this.historyId = historyId;
            this.packageName = packageName;
            this.learningAllowed = learningAllowed;
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
                    learningAllowed);
        }
    }

    private VoicePipeline pipeline;
    private SettingsRepository settingsRepository;
    private AppProfileRepository appProfileRepository;
    private PersonalizationStore personalizationStore;
    private SecurePreferences draftPreferences;
    private Handler mainHandler;
    private ExecutorService localIo;
    private TextView status;
    private TextView transcript;
    private Button microphone;
    private Button modeButton;
    private Button undoButton;
    private Button holdToTalkButton;
    private Button switchKeyboardButton;
    private Button punctuationButton;
    private Button deleteButton;
    private Button enterButton;
    private boolean holdToTalkActive;
    private boolean preparingVoiceInput;
    private boolean finishingVoiceInput;
    private boolean compactLayout;
    private boolean recoverableDraftLoading = true;
    private boolean recoveringSavedAudio;
    private long discardConfirmationDeadline;
    private long recoverableInsertConfirmationDeadline;
    private String latestPreviewText = "";
    private final RecoverableDraftSlot recoverableDraft = PROCESS_RECOVERABLE_DRAFT;
    private DictationRequest.CaptureMode activeCaptureMode;
    private volatile CommitTarget detachedTargetAwaitingResult;
    private volatile boolean serviceDestroyed;
    private boolean resourcesClosed;
    private final Runnable destroyFinalizationTimeout = this::forceCloseDestroyedService;
    private final Runnable pendingDetachedRefresh = () -> {
        if (!serviceDestroyed) renderInputViewState();
    };
    private final DetachedFinalizationGate destroyFinalizationGate =
            new DetachedFinalizationGate();

    private long editorEpoch;
    private EditorInfo currentEditor;
    private FieldKind currentFieldKind = FieldKind.GENERAL;
    private boolean sensitiveField;
    private int currentSelectionStart = -1;
    private int currentSelectionEnd = -1;
    private ProcessingMode selectedMode = ProcessingMode.AUTO;
    private CommitTarget activeTarget;
    private VoiceCompositionSession activeComposition;
    private LastVoiceCommit lastCommit;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        pipeline = new VoicePipeline(this);
        settingsRepository = new SettingsRepository(this);
        appProfileRepository = new AppProfileRepository(this);
        personalizationStore = new PersonalizationStore(this);
        draftPreferences = new SecurePreferences(this);
        localIo = Executors.newSingleThreadExecutor();
        selectedMode = settingsRepository.loadDefaultMode();
        localIo.execute(() -> {
            settingsRepository.load();
            personalizationStore.getReadableDatabase();
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(10));
        root.setBackgroundResource(R.drawable.ime_panel_background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int navigationBottom = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            view.setPadding(dp(8), dp(8), dp(8), dp(10) + navigationBottom);
            return insets;
        });

        status = new TextView(this);
        status.setText(R.string.status_ready);
        status.setTextColor(getColor(R.color.ime_on_surface_variant));
        status.setTextSize(12);
        status.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        status.setPadding(dp(6), dp(2), dp(6), dp(6));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status, matchWrap());

        transcript = new TextView(this);
        transcript.setText(R.string.ime_transcript_hint);
        transcript.setTextColor(getColor(R.color.ime_on_surface));
        transcript.setTextSize(17);
        transcript.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        transcript.setPadding(dp(12), dp(8), dp(12), dp(8));
        transcript.setMinHeight(dp(52));
        transcript.setMaxLines(2);
        transcript.setTextIsSelectable(true);
        transcript.setBackgroundResource(R.drawable.ime_transcript_background);
        transcript.setVisibility(View.GONE);
        transcript.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        root.addView(transcript, matchWrap());

        LinearLayout toolbar = horizontalRow();
        modeButton = key("", getString(R.string.ime_cd_choose_mode), 1f,
                ignored -> showModeMenu());
        addWeighted(toolbar, modeButton, 1.15f);
        microphone = key(getString(voiceLabel(
                        R.string.ime_key_long_dictation,
                        R.string.ime_key_long_dictation_compact)),
                getString(R.string.ime_cd_start_long_dictation), 2f,
                ignored -> toggleRecording(DictationRequest.CaptureMode.CONTINUOUS));
        addWeighted(toolbar, microphone, 1.35f);
        undoButton = key(R.string.ime_key_undo, R.string.ime_cd_undo, 1f,
                ignored -> undoLastVoiceCommit());
        undoButton.setVisibility(View.GONE);
        addWeighted(toolbar, undoButton, 1f);
        addFixed(toolbar, key(
                R.string.ime_key_more,
                R.string.ime_cd_more,
                1f,
                this::showMoreMenu), 48);
        root.addView(toolbar, matchWrap());
        refreshModeButton();

        LinearLayout typing = horizontalRow();
        switchKeyboardButton = key(
                R.string.ime_key_switch_keyboard,
                R.string.ime_cd_switch_keyboard,
                1f,
                ignored -> switchKeyboard());
        addFixed(typing, switchKeyboardButton, 48);
        punctuationButton = key(
                R.string.ime_key_punctuation,
                R.string.ime_cd_punctuation,
                1f,
                this::showPunctuationMenu);
        addFixed(typing, punctuationButton, 48);
        holdToTalkButton = key(
                voiceLabel(
                        R.string.ime_key_hold_to_talk,
                        R.string.ime_key_hold_to_talk_compact),
                R.string.ime_cd_space_hold_to_talk,
                2f,
                ignored -> commitText(" "));
        configureHoldToTalk(holdToTalkButton);
        addWeighted(typing, holdToTalkButton, 3f);
        deleteButton = key(R.string.ime_key_delete, R.string.ime_cd_delete, 1f,
                ignored -> backspace());
        addFixed(typing, deleteButton, 48);
        enterButton = key(R.string.ime_key_enter, R.string.ime_cd_enter, 1f,
                ignored -> sendEnter());
        addFixed(typing, enterButton, 48);
        root.addView(typing, matchWrap());

        refreshEnterKey();
        refreshPostCommitActions();
        renderInputViewState();
        return root;
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        return true;
    }

    private void toggleRecording(DictationRequest.CaptureMode captureMode) {
        if (sensitiveField) {
            setStatus(R.string.ime_status_sensitive_disabled, true);
            return;
        }
        if (recoverableDraftLoading) {
            setStatus(R.string.ime_status_recoverable_draft_loading, false);
            return;
        }
        if (pipeline.hasRecoverableAudio()) {
            setStatus(R.string.ime_status_recoverable_audio_resolve_first, true);
            return;
        }
        if (pendingDetachedTarget() != null) {
            setStatus(R.string.ime_status_previous_voice_finalizing, false);
            return;
        }
        if (recoverableDraft.hasDraft()) {
            setStatus(R.string.ime_status_recoverable_draft_resolve_first, true);
            return;
        }
        if (pipeline.state() == VoicePipeline.State.RECORDING) {
            pipeline.stopRecording();
            finishingVoiceInput = true;
            updateMicrophone(VoicePipeline.State.TRANSCRIBING);
            setStatus(R.string.ime_status_finishing_recording, false);
            return;
        }
        if (pipeline.state() != VoicePipeline.State.IDLE) {
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

        CommitTarget target = captureTarget();
        if (target == null) return;
        activeTarget = target;
        preparingVoiceInput = true;
        finishingVoiceInput = false;
        activeCaptureMode = captureMode;
        discardConfirmationDeadline = 0L;
        latestPreviewText = "";
        activeComposition = new VoiceCompositionSession(
                target.connection,
                target.selectionStart,
                target.selectionEnd);
        setEditingKeysEnabled(false, false);
        showPreparingState();
        lastCommit = null;
        refreshPostCommitActions();
        if (target.replacedSelection() || !activeComposition.enabled()) {
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
        VoicePipeline.State currentState = pipeline.state();
        switch (holdReleaseAction(
                currentState, activeTarget != null, preparingVoiceInput)) {
            case STOP_AND_COMMIT -> {
                pipeline.stopRecording();
                finishingVoiceInput = true;
                updateMicrophone(VoicePipeline.State.TRANSCRIBING);
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
            VoicePipeline.State state,
            boolean hasActiveTarget,
            boolean preparing) {
        // VoicePipeline enters RECORDING before the recognizer has actually opened the
        // microphone. Releasing while that asynchronous preparation is still visible must
        // cancel it instead of asking an unready recorder to finalize an empty utterance.
        if (hasActiveTarget && preparing) {
            return HoldReleaseAction.CANCEL_PREPARATION;
        }
        if (state == VoicePipeline.State.RECORDING) return HoldReleaseAction.STOP_AND_COMMIT;
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
                    updateMicrophone(VoicePipeline.State.IDLE);
                    clearTranscript();
                    setStatus(R.string.ime_status_configure_backend, true);
                    openSettings();
                });
                return;
            }
            if (settings.recognitionBackend()
                    == com.opentypeless.android.settings.RecognitionBackend.LOCAL_OFFLINE
                    && (!LocalOfflineRecognizer.isSupportedDevice(this)
                    || !LocalOfflineRecognizer.isInstalled(this))) {
                postUi(() -> {
                    if (activeTarget != target) return;
                    preparingVoiceInput = false;
                    finishingVoiceInput = false;
                    activeCaptureMode = null;
                    activeTarget = null;
                    discardActiveComposition();
                    updateMicrophone(VoicePipeline.State.IDLE);
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
                    updateMicrophone(VoicePipeline.State.IDLE);
                    clearTranscript();
                    setStatus(R.string.ime_status_target_changed_cancelled, true);
                    return;
                }
                boolean started = pipeline.start(request, listenerFor(target));
                if (!started) {
                    preparingVoiceInput = false;
                    finishingVoiceInput = false;
                    activeCaptureMode = null;
                    activeTarget = null;
                    discardActiveComposition();
                    updateMicrophone(VoicePipeline.State.IDLE);
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
                updateMicrophone(VoicePipeline.State.IDLE);
                clearTranscript();
                setStatus(safeMessage(error.getMessage()), true);
            });
        }
    }

    private VoicePipeline.Listener listenerFor(CommitTarget target) {
        return new VoicePipeline.Listener() {
            @Override
            public void onState(VoicePipeline.State state, String message) {
                postUi(() -> {
                    if (activeTarget != target) return;
                    if (state == VoicePipeline.State.RECORDING && preparingVoiceInput) {
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
                postUi(() -> {
                    if (!shouldHandleSpeechReady(
                            activeTarget, target, finishingVoiceInput)) return;
                    preparingVoiceInput = false;
                    setStatus(R.string.ime_status_listening, false);
                    updateMicrophone(VoicePipeline.State.RECORDING);
                    if (latestPreviewText.isBlank()
                            && (target.replacedSelection()
                            || activeComposition == null
                            || !activeComposition.enabled())) {
                        showTranscript(getString(R.string.ime_transcript_listening));
                    }
                    View readyControl = activeCaptureMode
                            == DictationRequest.CaptureMode.HOLD_TO_TALK
                            ? holdToTalkButton
                            : microphone;
                    if (readyControl != null) {
                        readyControl.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                });
            }

            @Override
            public void onTranscript(TranscriptUpdate update) {
                postUi(() -> applyTranscriptUpdate(target, update));
            }

            @Override
            public void onResult(DictationResult result) {
                markDetachedTerminalArrived(target);
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
                markDetachedTerminalArrived(target);
                postUi(() -> {
                    if (activeTarget == target) {
                        preparingVoiceInput = false;
                        finishingVoiceInput = false;
                        activeCaptureMode = null;
                        boolean preserved = preserveActiveDraft();
                        activeTarget = null;
                        updateMicrophone(VoicePipeline.State.IDLE);
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
        if (serviceDestroyed && !destroyFinalizationGate.claimTerminalHandler()) return;
        PendingDetachedSession<CommitTarget> pending = detachedSessionFor(target);
        detachedTargetAwaitingResult = null;
        if (pending != null && pending.discarded()) {
            clearDetachedSession(pending);
            if (serviceDestroyed) finishDestroyedService();
            return;
        }
        String finalText = result.finalText() == null ? "" : result.finalText();
        boolean selectionPreserved = target.replacedSelection();
        boolean saved = !selectionPreserved
                && !finalText.isBlank()
                && saveRecoverableDraftFromResult(target, finalText, result);
        completeDetachedSession(pending, target);
        if (serviceDestroyed) {
            finishDestroyedService();
        } else if (activeTarget == null) {
            holdToTalkActive = false;
            preparingVoiceInput = false;
            finishingVoiceInput = false;
            activeCaptureMode = null;
            if (sensitiveField) {
                renderInputViewState();
                return;
            }
            updateMicrophone(VoicePipeline.State.IDLE);
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
        if (serviceDestroyed && !destroyFinalizationGate.claimTerminalHandler()) return;
        CommitTarget target = detachedTargetAwaitingResult;
        PendingDetachedSession<CommitTarget> pending = detachedSessionFor(target);
        detachedTargetAwaitingResult = null;
        if (pending != null && pending.discarded()) {
            clearDetachedSession(pending);
            if (serviceDestroyed) finishDestroyedService();
            return;
        }
        completeDetachedSession(pending, target);
        if (serviceDestroyed) {
            finishDestroyedService();
            return;
        }
        if (activeTarget != null) return;
        holdToTalkActive = false;
        preparingVoiceInput = false;
        finishingVoiceInput = false;
        activeCaptureMode = null;
        if (sensitiveField) {
            renderInputViewState();
            return;
        }
        updateMicrophone(VoicePipeline.State.IDLE);
        showRecoverableDraftOrClear();
        setStatus(recoverableDraft.hasDraft()
                ? getString(R.string.ime_status_detached_partial_recoverable)
                : safeMessage(message), true);
    }

    private void applyTranscriptUpdate(CommitTarget target, TranscriptUpdate update) {
        if (activeTarget != target || update == null || update.finalResult()) return;
        String text = update.text().trim();
        if (text.isEmpty()) return;
        latestPreviewText = text;
        VoiceCompositionSession composition = activeComposition;
        if (target.replacedSelection() || composition == null || !composition.enabled()) {
            showTranscript(compact(text, 180));
            return;
        }
        if (!targetStillValid(target)) {
            stopPipelinePreservingDraft(
                    getString(R.string.ime_status_target_changed_preserved), true);
            return;
        }
        VoiceCompositionSession.ApplyResult result = composition.apply(update);
        if (result == VoiceCompositionSession.ApplyResult.REJECTED
                || result == VoiceCompositionSession.ApplyResult.CONNECTION_ERROR) {
            composition.disableLiveUpdates();
            showTranscript(compact(text, 180));
            setStatus(R.string.ime_status_live_composition_fallback, true);
        } else if (result == VoiceCompositionSession.ApplyResult.APPLIED
                || result == VoiceCompositionSession.ApplyResult.UNCHANGED) {
            clearTranscript();
        }
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
        CharSequence selected = connection.getSelectedText(0);
        ExtractedText extracted = null;
        if (currentSelectionStart < 0 || currentSelectionEnd < 0) {
            extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
        }
        SelectionEvidence selection = resolveSelectionEvidence(
                currentSelectionStart,
                currentSelectionEnd,
                selected,
                extracted == null ? -1 : extracted.selectionStart,
                extracted == null ? -1 : extracted.selectionEnd,
                extracted != null);
        if (!selection.known()) {
            setStatus(R.string.ime_status_selection_unknown, true);
            return null;
        }
        if (selection.hasSelection() && !selection.selectedTextAvailable()) {
            setStatus(R.string.ime_status_selection_unavailable, true);
            return null;
        }
        String selectedText = selection.text();
        if (codePointCount(selectedText) > MAX_SELECTION_CODE_POINTS) {
            setStatus(R.string.ime_status_selection_too_long, true);
            return null;
        }
        currentSelectionStart = selection.start();
        currentSelectionEnd = selection.end();
        boolean learningAllowed = (editor.imeOptions
                & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
        CharSequence before = connection.getTextBeforeCursor(CONTEXT_CHAR_LIMIT, 0);
        return new CommitTarget(
                editorEpoch,
                connection,
                safe(editor.packageName),
                editor.fieldId,
                currentFieldKind,
                selectedText,
                tailCodePoints(before, FINGERPRINT_CODE_POINTS),
                headCodePoints(connection.getTextAfterCursor(CONTEXT_CHAR_LIMIT, 0),
                        FINGERPRINT_CODE_POINTS),
                before == null ? "" : before.toString(),
                learningAllowed,
                selection.start(),
                selection.end());
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

    /** Prevents a queued callback from an old editor session from mutating the new session's UI. */
    static boolean runIfCurrent(Object current, Object expected, Runnable callback) {
        if (current != expected) return false;
        callback.run();
        return true;
    }

    private void commitResult(CommitTarget target, DictationResult result) {
        if (activeTarget != target) return;
        if (!targetStillValid(target)) {
            preserveActiveDraft();
            if (!target.replacedSelection()
                    && result.finalText() != null
                    && !result.finalText().isBlank()) {
                saveRecoverableDraftFromResult(target, result.finalText(), result);
            }
            finishActiveUiSession(target);
            showRecoverableDraftOrClear();
            setStatus(R.string.ime_status_target_changed_preserved, true);
            return;
        }

        String finalText = result.finalText();
        if (finalText == null || finalText.isBlank()) {
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

        pipeline.acknowledgeRecovery(result.recoveryId());

        lastCommit = new LastVoiceCommit(
                target.editorEpoch,
                target.connection,
                finalText,
                target.selectedText,
                result.rawText(),
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
        updateMicrophone(VoicePipeline.State.IDLE);
    }

    private long persistSuccessfulResult(CommitTarget target, DictationResult result) {
        if (!target.learningAllowed || sensitiveField) return -1L;
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
                            && commit.connection == target.connection
                            && commit.insertedText.equals(result.finalText())
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
                    result.rawText(),
                    result.finalText(),
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
        if (currentEditor == null) return false;
        InputConnection currentConnection = getCurrentInputConnection();
        if (currentConnection == null) return false;
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

    private void restoreRawTranscript() {
        LastVoiceCommit commit = lastCommit;
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

    private void teachCorrection() {
        LastVoiceCommit commit = lastCommit;
        if (commit == null || commit.rawText.isBlank()) {
            setStatus(R.string.ime_status_teach_requires_insertion, false);
            return;
        }
        if (!commit.learningAllowed) {
            setStatus(R.string.ime_status_learning_disallowed, false);
            return;
        }
        Intent intent = new Intent(this, HistoryActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("history_id", commit.historyId);
        intent.putExtra("raw_text", commit.rawText);
        intent.putExtra("final_text", commit.insertedText);
        intent.putExtra("app_scope", commit.packageName);
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

    private void backspace() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        try {
            CharSequence selected = connection.getSelectedText(0);
            boolean applied = selected != null && selected.length() > 0
                    ? connection.commitText("", 1)
                    : connection.deleteSurroundingTextInCodePoints(1, 0);
            if (!applied) setStatus(R.string.ime_status_delete_rejected, true);
        } catch (RuntimeException ignored) {
            setStatus(R.string.ime_status_delete_connection_failed, true);
        }
        invalidateLastCommit();
    }

    private void commitText(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            try {
                if (!connection.commitText(text, 1)) {
                    setStatus(R.string.ime_status_key_rejected, true);
                }
            } catch (RuntimeException ignored) {
                setStatus(R.string.ime_status_typing_connection_failed, true);
            }
        }
        invalidateLastCommit();
    }

    private void sendEnter() {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        int action = currentEditor == null
                ? EditorInfo.IME_ACTION_NONE
                : currentEditor.imeOptions & EditorInfo.IME_MASK_ACTION;
        try {
            switch (action) {
                case EditorInfo.IME_ACTION_DONE,
                        EditorInfo.IME_ACTION_GO,
                        EditorInfo.IME_ACTION_NEXT,
                        EditorInfo.IME_ACTION_PREVIOUS,
                        EditorInfo.IME_ACTION_SEARCH,
                        EditorInfo.IME_ACTION_SEND -> connection.performEditorAction(action);
                default -> {
                    connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                }
            }
        } catch (RuntimeException ignored) {
            setStatus(R.string.ime_status_editor_action_failed, true);
        }
        invalidateLastCommit();
    }

    private void switchKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && shouldOfferSwitchingToNextInputMethod()) {
            switchToNextInputMethod(false);
        } else {
            Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }

    private void openSettings() {
        Intent intent = new Intent(this, MainActivity.class);
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
        if (pipeline.state() != VoicePipeline.State.IDLE || activeTarget != null) {
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
        String displayLabel = compactLayout ? localizedCompactModeLabel(selectedMode) : label;
        modeButton.setText(getString(R.string.ime_mode_button, displayLabel));
        modeButton.setContentDescription(getString(R.string.ime_cd_choose_mode_current, label));
    }

    private void showMoreMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        boolean pendingDetached = pendingDetachedTarget() != null;
        boolean pendingAudio = pipeline.hasRecoverableAudio();
        boolean sessionActive = activeTarget != null
                || pipeline.state() != VoicePipeline.State.IDLE;
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
            if (commit != null && commit.learningAllowed && !commit.rawText.isBlank()) {
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
        }
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_DISCARD -> requestExplicitDiscard();
                case MENU_RESTORE_RAW -> restoreRawTranscript();
                case MENU_TEACH -> teachCorrection();
                case MENU_DICTIONARY -> openDictionary();
                case MENU_APP_PROFILE -> openAppProfile();
                case MENU_SETTINGS -> openSettings();
                case MENU_INSERT_RECOVERABLE_DRAFT -> insertRecoverableDraft();
                case MENU_DISCARD_RECOVERABLE_DRAFT -> discardRecoverableDraft();
                case MENU_RECOVER_AUDIO -> recoverSavedAudio();
                case MENU_DISCARD_AUDIO -> discardSavedAudio();
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
            commitText(punctuation[index]);
            return true;
        });
        popup.show();
    }

    private void refreshPostCommitActions() {
        if (undoButton != null) {
            undoButton.setVisibility(lastCommit == null ? View.GONE : View.VISIBLE);
        }
    }

    private void renderInputViewState() {
        if (microphone == null) return;
        VoicePipeline.State state = pipeline.state();
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
        } else if (recoverableDraft.hasDraft()) {
            showRecoverableDraftOrClear();
            setStatus(R.string.ime_status_recoverable_draft_available, false);
        } else if (activeTarget != null || state != VoicePipeline.State.IDLE) {
            if (!latestPreviewText.isBlank()) showTranscript(compact(latestPreviewText, 180));
            setStatus(preparingVoiceInput
                    ? getString(R.string.ime_status_preparing_local_data)
                    : finishingVoiceInput && state == VoicePipeline.State.IDLE
                    ? getString(R.string.ime_status_finishing_recording)
                    : localizedPipelineStatus(state), false);
        } else if (pipeline.hasRecoverableAudio()) {
            clearTranscript();
            setStatus(R.string.ime_status_recoverable_audio_available, false);
        } else {
            clearTranscript();
            setStatus(getString(
                    R.string.ime_status_ready_mode, localizedModeLabel(selectedMode)), false);
        }
    }

    private void updateMicrophone(VoicePipeline.State state) {
        if (microphone == null) return;
        boolean recording = state == VoicePipeline.State.RECORDING && !finishingVoiceInput;
        boolean processing = finishingVoiceInput
                || state == VoicePipeline.State.TRANSCRIBING
                || state == VoicePipeline.State.POLISHING;
        boolean idle = state == VoicePipeline.State.IDLE && !preparingVoiceInput;
        boolean longSession = activeCaptureMode == DictationRequest.CaptureMode.CONTINUOUS;
        boolean holdSession = activeCaptureMode == DictationRequest.CaptureMode.HOLD_TO_TALK;
        boolean longRecording = recording && longSession;
        boolean holdRecording = recording && holdSession;
        boolean startAllowed = voiceStartAllowed();
        boolean editorKeysAllowed = activeTarget == null;
        boolean spaceIsVoiceUnavailable = editorKeysAllowed && !startAllowed;
        boolean externalFinalizing = editorKeysAllowed && pendingDetachedTarget() != null;
        boolean voiceUnavailable = editorKeysAllowed && !startAllowed && !externalFinalizing;

        setEditingKeysEnabled(editorKeysAllowed, startAllowed);
        setPrimaryKeyStyle(microphone, longRecording);
        microphone.setSelected(longRecording);
        int longRegularLabel = longRecording
                ? R.string.ime_key_finish_dictation
                : processing && longSession
                ? R.string.ime_key_processing
                : preparingVoiceInput && longSession
                ? R.string.ime_key_preparing
                : R.string.ime_key_long_dictation;
        int longCompactLabel = longRecording
                ? R.string.ime_key_finish_dictation_compact
                : processing && longSession
                ? R.string.ime_key_processing_compact
                : preparingVoiceInput && longSession
                ? R.string.ime_key_preparing_compact
                : R.string.ime_key_long_dictation_compact;
        microphone.setText(voiceLabel(longRegularLabel, longCompactLabel));
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
                    ? R.string.ime_key_listening
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
        updateMicrophone(VoicePipeline.State.IDLE);
    }

    private void setEditingKeysEnabled(boolean editorEnabled, boolean modeEnabled) {
        if (modeButton != null) modeButton.setEnabled(modeEnabled);
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
                && !sensitiveField
                && !recoverableDraftLoading
                && !pipeline.hasRecoverableAudio()
                && !recoverableDraft.hasDraft()
                && pendingDetachedTarget() == null
                && activeTarget == null
                && pipeline.state() == VoicePipeline.State.IDLE;
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

    static boolean shouldDeferServiceShutdown(Object detachedTarget) {
        return detachedTarget != null;
    }

    private void markDetachedTerminalArrived(CommitTarget target) {
        if (serviceDestroyed && detachedTargetAwaitingResult == target) {
            destroyFinalizationGate.terminalArrived();
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
        updateMicrophone(VoicePipeline.State.IDLE);
        showRecoverableDraftOrClear();
        if (announce) {
            setStatus(cleaned
                    ? message
                    : getString(R.string.ime_status_composition_cleanup_failed), !cleaned);
        }
    }

    private boolean cancelActiveComposition() {
        VoiceCompositionSession composition = activeComposition;
        activeComposition = null;
        return composition == null || composition.cancel();
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
            if (pipeline.state() != VoicePipeline.State.IDLE) pipeline.cancel();
            holdToTalkActive = false;
            preparingVoiceInput = false;
            finishingVoiceInput = false;
            activeCaptureMode = null;
            updateMicrophone(VoicePipeline.State.IDLE);
            showRecoverableDraftOrClear();
            return;
        }

        VoicePipeline.State previousState = pipeline.state();
        boolean wasPreparing = preparingVoiceInput;
        VoiceCompositionSession composition = activeComposition;
        boolean awaitFinalResult = !wasPreparing;
        if (awaitFinalResult) {
            detachedTargetAwaitingResult = target;
            installPendingDetached(target);
            if (previousState == VoicePipeline.State.RECORDING) pipeline.stopRecording();
        } else {
            // No recorder exists yet, so there is no speech or final result to preserve.
            pipeline.cancel();
        }

        holdToTalkActive = false;
        preparingVoiceInput = false;
        finishingVoiceInput = awaitFinalResult;
        if (!awaitFinalResult) activeCaptureMode = null;
        discardConfirmationDeadline = 0L;
        boolean preserved = preserveActiveDraft();
        activeTarget = null;
        updateMicrophone(awaitFinalResult
                ? VoicePipeline.State.TRANSCRIBING
                : VoicePipeline.State.IDLE);
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

    private void insertRecoverableDraft() {
        RecoverableDraftSlot.Draft draft = recoverableDraft.get();
        if (draft == null || sensitiveField) return;
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) {
            setStatus(R.string.ime_status_no_active_field, true);
            return;
        }
        long now = System.currentTimeMillis();
        if (now > recoverableInsertConfirmationDeadline) {
            recoverableInsertConfirmationDeadline = now + DISCARD_CONFIRM_WINDOW_MILLIS;
            setStatus(R.string.ime_status_confirm_recoverable_insert, true);
            return;
        }
        // Recovery is deliberately insertion-only. InputConnection objects can be reused across
        // fields, so suffix-based deletion could erase unrelated text after a cursor move.
        EditorMutationResult result = guardedReplace(connection, 0, draft.text(), "");
        if (result == EditorMutationResult.APPLIED) {
            recoverableInsertConfirmationDeadline = 0L;
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
        recoverableInsertConfirmationDeadline = 0L;
        clearTranscript();
        setStatus(R.string.ime_status_recoverable_draft_discarded, false);
    }

    private void showRecoverableDraftOrClear() {
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
        if (recoveringSavedAudio || pipeline.state() != VoicePipeline.State.IDLE) return;
        recoveringSavedAudio = true;
        updateMicrophone(VoicePipeline.State.TRANSCRIBING);
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
                    updateMicrophone(state);
                });
            }

            @Override
            public void onResult(DictationResult result) {
                postUiIfAlive(() -> {
                    recoveringSavedAudio = false;
                    boolean saved = saveRecoverableDraftFromResult(
                            null, result.finalText(), result);
                    updateMicrophone(VoicePipeline.State.IDLE);
                    showRecoverableDraftOrClear();
                    setStatus(saved
                            ? R.string.ime_status_recoverable_audio_ready
                            : R.string.ime_status_recoverable_draft_conflict, !saved);
                });
            }

            @Override
            public void onError(String message) {
                postUiIfAlive(() -> {
                    recoveringSavedAudio = false;
                    updateMicrophone(VoicePipeline.State.IDLE);
                    setStatus(getString(
                            R.string.ime_status_recoverable_audio_failed,
                            safeMessage(message)), true);
                });
            }
        };
    }

    private void discardSavedAudio() {
        if (!pipeline.hasRecoverableAudio()) return;
        long now = System.currentTimeMillis();
        if (now > discardConfirmationDeadline) {
            discardConfirmationDeadline = now + DISCARD_CONFIRM_WINDOW_MILLIS;
            setStatus(R.string.ime_status_confirm_discard_audio, true);
            return;
        }
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
            destroyFinalizationGate.close();
            closeServiceResources();
            return;
        }
        updateMicrophone(VoicePipeline.State.IDLE);
        showRecoverableDraftOrClear();
        setStatus(R.string.ime_status_cancelled, false);
    }

    private boolean hasVisibleVoiceDraft() {
        if (recoverableDraft.hasDraft() || pipeline.hasRecoverableAudio()) return true;
        VoiceCompositionSession composition = activeComposition;
        if (composition != null && composition.ownsComposition()) return true;
        if (transcript == null || transcript.getVisibility() != View.VISIBLE) return false;
        String visible = transcript.getText() == null ? "" : transcript.getText().toString().trim();
        return !visible.isEmpty()
                && !visible.equals(getString(R.string.ime_transcript_listening))
                && !visible.equals(getString(R.string.ime_transcript_hint));
    }

    private void discardActiveComposition() {
        VoiceCompositionSession composition = activeComposition;
        activeComposition = null;
        if (composition != null) composition.discardState();
    }

    private void invalidateLastCommit() {
        lastCommit = null;
        refreshPostCommitActions();
    }

    private void setStatus(String message, boolean error) {
        if (status == null) return;
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText(safeMessage(message));
        status.setTextColor(getColor(error ? R.color.ime_error : R.color.ime_on_surface_variant));
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

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        stopPipelinePreservingDraft("", false);
        super.onStartInput(attribute, restarting);
        editorEpoch++;
        currentEditor = attribute;
        currentFieldKind = InputContextClassifier.classify(attribute);
        sensitiveField = currentFieldKind == FieldKind.SENSITIVE;
        AppProfile profile = attribute == null
                ? null
                : appProfileRepository.get(safe(attribute.packageName));
        selectedMode = profile == null
                ? settingsRepository.loadDefaultMode()
                : profile.mode();
        refreshModeButton();
        currentSelectionStart = attribute == null ? -1 : attribute.initialSelStart;
        currentSelectionEnd = attribute == null ? -1 : attribute.initialSelEnd;
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
        VoiceCompositionSession composition = activeComposition;
        boolean ownedCompositionMove = target != null
                && composition != null
                && composition.ownsComposition()
                && composition.acceptsSelection(
                        newSelStart,
                        newSelEnd,
                        candidatesStart,
                        candidatesEnd);
        if (target != null
                && composition != null
                && composition.ownsComposition()
                && !ownedCompositionMove) {
            stopPipelinePreservingDraft(
                    getString(R.string.ime_status_cursor_moved_preserved), true);
            return;
        }
        if (!ownedCompositionMove
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
        stopPipelinePreservingDraft("", false);
        editorEpoch++;
        currentEditor = null;
        sensitiveField = false;
        invalidateLastCommit();
        super.onFinishInput();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        stopPipelinePreservingDraft("", false);
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onWindowHidden() {
        stopPipelinePreservingDraft("", false);
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
        stopPipelinePreservingDraft("", false);
        boolean deferShutdown = shouldDeferServiceShutdown(detachedTargetAwaitingResult);
        if (deferShutdown) destroyFinalizationGate.begin();
        // Volatile publication happens after the target and gate are ready. A worker observing
        // true can therefore safely arbitrate its terminal callback against the timeout.
        serviceDestroyed = true;
        activeTarget = null;
        if (deferShutdown) {
            // Keep the headless recognition pipeline alive long enough to deliver the already
            // requested final. The callback persists it without touching a destroyed IME view.
            mainHandler.postDelayed(
                    destroyFinalizationTimeout, DESTROY_FINALIZATION_TIMEOUT_MILLIS);
        } else {
            destroyFinalizationGate.close();
            closeServiceResources();
        }
        super.onDestroy();
    }

    private void finishDestroyedService() {
        if (!serviceDestroyed || detachedTargetAwaitingResult != null) return;
        closeServiceResources();
    }

    private void forceCloseDestroyedService() {
        if (!serviceDestroyed || resourcesClosed) return;
        // Once a terminal callback has entered the process, its already queued headless handler
        // owns teardown. This prevents a timeout from closing localIo ahead of the final write.
        if (!destroyFinalizationGate.claimTimeout()) return;
        CommitTarget target = detachedTargetAwaitingResult;
        PendingDetachedSession<CommitTarget> pending = detachedSessionFor(target);
        detachedTargetAwaitingResult = null;
        if (pending != null) {
            pending.complete();
            RecoverableDraftSlot.Draft draft = recoverableDraft.get();
            if (draft == null || draft.source() != pending.recoveryToken) {
                clearDetachedSession(pending);
            }
        }
        pipeline.cancel();
        closeServiceResources();
    }

    private void closeServiceResources() {
        if (resourcesClosed) return;
        resourcesClosed = true;
        if (mainHandler != null) mainHandler.removeCallbacks(destroyFinalizationTimeout);
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

    private Button key(
            String label,
            String contentDescription,
            float weight,
            View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setContentDescription(contentDescription);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(13);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            button.setAutoSizeTextTypeUniformWithConfiguration(
                    9, 13, 1, TypedValue.COMPLEX_UNIT_SP);
        }
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

    private Button key(
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

    private String localizedPipelineState(VoicePipeline.State state) {
        return getString(localizedPipelineStateResource(state));
    }

    private int localizedPipelineStateResource(VoicePipeline.State state) {
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

    private String localizedPipelineStatus(VoicePipeline.State state) {
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
