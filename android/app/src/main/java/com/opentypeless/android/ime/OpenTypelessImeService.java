package com.opentypeless.android.ime;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.opentypeless.android.HistoryActivity;
import com.opentypeless.android.MainActivity;
import com.opentypeless.android.AppProfileActivity;
import com.opentypeless.android.R;
import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.context.InputContextClassifier;
import com.opentypeless.android.data.HistoryEntry;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.data.PersonalizationStore;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.AppProfile;
import com.opentypeless.android.settings.AppProfileRepository;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.SettingsRepository;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A deliberately small voice keyboard. It binds every asynchronous recognition run to an exact
 * editor epoch and cursor fingerprint so a late result can never spill into a different field.
 */
public final class OpenTypelessImeService extends InputMethodService {
    private static final int CONTEXT_CHAR_LIMIT = 800;
    private static final int FINGERPRINT_CODE_POINTS = 64;
    private static final int MAX_SELECTION_CODE_POINTS = 4_000;

    enum EditorMutationResult {
        APPLIED,
        DELETE_REJECTED,
        COMMIT_REJECTED,
        ROLLED_BACK,
        ROLLBACK_FAILED,
        CONNECTION_ERROR
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
    private ExecutorService localIo;
    private TextView status;
    private Button microphone;
    private Button learnButton;
    private Button rawButton;
    private final Map<ProcessingMode, Button> modeButtons = new EnumMap<>(ProcessingMode.class);

    private long editorEpoch;
    private EditorInfo currentEditor;
    private FieldKind currentFieldKind = FieldKind.GENERAL;
    private boolean sensitiveField;
    private int currentSelectionStart = -1;
    private int currentSelectionEnd = -1;
    private ProcessingMode selectedMode = ProcessingMode.AUTO;
    private CommitTarget activeTarget;
    private LastVoiceCommit lastCommit;

    @Override
    public void onCreate() {
        super.onCreate();
        pipeline = new VoicePipeline(this);
        settingsRepository = new SettingsRepository(this);
        appProfileRepository = new AppProfileRepository(this);
        personalizationStore = new PersonalizationStore(this);
        localIo = Executors.newSingleThreadExecutor();
        selectedMode = settingsRepository.loadDefaultMode();
        localIo.execute(() -> {
            settingsRepository.load();
            personalizationStore.getReadableDatabase();
        });
    }

    @Override
    public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(6), dp(8), dp(8));
        root.setBackgroundColor(Color.rgb(245, 247, 246));

        status = new TextView(this);
        status.setText(R.string.status_ready);
        status.setTextColor(Color.rgb(35, 70, 63));
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(4), dp(4), dp(4), dp(6));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status, matchWrap());

        ViewGroup modes = row();
        addModeButton(modes, ProcessingMode.AUTO);
        addModeButton(modes, ProcessingMode.VERBATIM);
        addModeButton(modes, ProcessingMode.SMART);
        addModeButton(modes, ProcessingMode.TRANSLATE);
        root.addView(modes, matchWrap());
        refreshModeButtons();

        ViewGroup actions = row();
        microphone = key(getString(R.string.action_speak),
                getString(R.string.ime_cd_start_voice_input), 2f,
                ignored -> toggleRecording());
        actions.addView(microphone);
        actions.addView(key(R.string.ime_key_delete, R.string.ime_cd_delete, 1f,
                ignored -> backspace()));
        actions.addView(key(R.string.ime_key_space, R.string.ime_cd_insert_space, 1.3f,
                ignored -> commitText(" ")));
        actions.addView(key(R.string.ime_key_enter, R.string.ime_cd_enter, 1f,
                ignored -> sendEnter()));
        actions.addView(key(R.string.ime_key_switch_keyboard,
                R.string.ime_cd_switch_keyboard, 1f, ignored -> switchKeyboard()));
        root.addView(actions, matchWrap());

        ViewGroup secondary = row();
        secondary.addView(key(R.string.ime_key_undo, R.string.ime_cd_undo, 1f,
                ignored -> undoLastVoiceCommit()));
        rawButton = key(R.string.ime_key_raw, R.string.ime_cd_restore_raw, 1f,
                ignored -> restoreRawTranscript());
        secondary.addView(rawButton);
        learnButton = key(R.string.ime_key_teach, R.string.ime_cd_teach, 1f,
                ignored -> teachCorrection());
        secondary.addView(learnButton);
        secondary.addView(key(R.string.ime_key_cancel, R.string.ime_cd_cancel, 1f,
                ignored -> cancelPipeline(getString(R.string.ime_status_cancelled), true)));
        root.addView(secondary, matchWrap());

        ViewGroup management = row();
        management.addView(key(R.string.ime_key_app_profile,
                R.string.ime_cd_app_profile, 1f,
                ignored -> openAppProfile()));
        management.addView(key(R.string.ime_key_settings, R.string.ime_cd_settings, 1f,
                ignored -> openSettings()));
        root.addView(management, matchWrap());

        refreshPostCommitActions();
        return root;
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        return true;
    }

    private void toggleRecording() {
        if (sensitiveField) {
            setStatus(R.string.ime_status_sensitive_disabled, true);
            return;
        }
        if (pipeline.state() == VoicePipeline.State.RECORDING) {
            pipeline.stopRecording();
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
        lastCommit = null;
        refreshPostCommitActions();
        ProcessingMode requestedMode = selectedMode;
        setStatus(R.string.ime_status_preparing_local_data, false);
        localIo.execute(() -> prepareAndStart(target, requestedMode));
    }

    private void prepareAndStart(CommitTarget target, ProcessingMode requestedMode) {
        try {
            AppSettings settings = settingsRepository.load();
            settings = appProfileRepository.apply(
                    settings,
                    appProfileRepository.get(target.packageName));
            if (!settings.isReady()) {
                postUi(() -> {
                    if (activeTarget != target) return;
                    activeTarget = null;
                    setStatus(R.string.ime_status_configure_backend, true);
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
                    snapshot);
            postUi(() -> {
                if (activeTarget != target) return;
                if (!targetStillValid(target)) {
                    activeTarget = null;
                    setStatus(R.string.ime_status_target_changed_cancelled, true);
                    return;
                }
                if (!pipeline.start(request, listenerFor(target))) {
                    activeTarget = null;
                    setStatus(R.string.ime_status_session_active, true);
                }
            });
        } catch (RuntimeException error) {
            postUi(() -> {
                if (activeTarget != target) return;
                activeTarget = null;
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
                    setStatus(message, false);
                    updateMicrophone(state);
                });
            }

            @Override
            public void onPartial(String text) {
                postUi(() -> {
                    if (activeTarget == target && !text.isBlank()) {
                        setPartialStatus(getString(
                                R.string.ime_status_live, compact(text, 100)));
                    }
                });
            }

            @Override
            public void onResult(DictationResult result) {
                postUi(() -> commitResult(target, result));
            }

            @Override
            public void onError(String message) {
                postUi(() -> {
                    if (activeTarget != target) return;
                    activeTarget = null;
                    updateMicrophone(VoicePipeline.State.IDLE);
                    setStatus(safeMessage(message), true);
                });
            }
        };
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
        String selectedText = selected == null ? "" : selected.toString();
        if (codePointCount(selectedText) > MAX_SELECTION_CODE_POINTS) {
            setStatus(R.string.ime_status_selection_too_long, true);
            return null;
        }
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
                currentSelectionStart,
                currentSelectionEnd);
    }

    private void commitResult(CommitTarget target, DictationResult result) {
        if (activeTarget != target) return;
        activeTarget = null;
        updateMicrophone(VoicePipeline.State.IDLE);
        if (!targetStillValid(target)) {
            setStatus(R.string.ime_status_target_changed_discarded, true);
            return;
        }

        String finalText = result.finalText();
        if (finalText == null || finalText.isBlank()) {
            setStatus(R.string.ime_status_no_text, true);
            return;
        }
        EditorMutationResult mutation = guardedReplace(
                target.connection,
                0,
                finalText,
                "");
        if (mutation != EditorMutationResult.APPLIED) {
            setStatus(mutation == EditorMutationResult.CONNECTION_ERROR
                    ? getString(R.string.ime_status_result_connection_failed)
                    : getString(R.string.ime_status_result_rejected), true);
            return;
        }

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
        setStatus(result.message(), result.message().startsWith("AI edit blocked"));
        localIo.execute(() -> persistSuccessfulResult(target, result));
    }

    private long persistSuccessfulResult(CommitTarget target, DictationResult result) {
        if (!target.learningAllowed || sensitiveField) return -1L;
        try {
            personalizationStore.markTermsUsed(result.matchedTermIds());
            personalizationStore.markCorrectionsUsed(result.matchedCorrectionIds());
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
                    result.durationMs()));
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

    private void addModeButton(ViewGroup row, ProcessingMode mode) {
        String label = localizedModeLabel(mode);
        Button button = key(label, getString(R.string.mode_accessibility, label, ""), 1f,
                ignored -> {
                    if (pipeline.state() != VoicePipeline.State.IDLE) {
                        setStatus(R.string.ime_status_finish_before_mode_change, true);
                        return;
                    }
                    selectedMode = mode;
                    refreshModeButtons();
                    setStatus(getString(
                            R.string.ime_status_mode_selected, localizedModeLabel(mode)), false);
                });
        modeButtons.put(mode, button);
        row.addView(button);
    }

    private void refreshModeButtons() {
        for (Map.Entry<ProcessingMode, Button> entry : modeButtons.entrySet()) {
            boolean selected = entry.getKey() == selectedMode;
            entry.getValue().setSelected(selected);
            String label = localizedModeLabel(entry.getKey());
            entry.getValue().setText(selected
                    ? getString(R.string.mode_selected_visual, label)
                    : label);
            entry.getValue().setContentDescription(getString(
                    R.string.mode_accessibility,
                    label,
                    selected ? getString(R.string.mode_accessibility_selected) : ""));
        }
    }

    private void refreshPostCommitActions() {
        if (rawButton != null) rawButton.setEnabled(lastCommit != null
                && lastCommit.originalSelection.isEmpty()
                && !lastCommit.rawText.isBlank()
                && !lastCommit.rawText.equals(lastCommit.insertedText));
        if (learnButton != null) learnButton.setEnabled(lastCommit != null
                && lastCommit.learningAllowed
                && !lastCommit.rawText.isBlank());
    }

    private void updateMicrophone(VoicePipeline.State state) {
        if (microphone == null) return;
        boolean recording = state == VoicePipeline.State.RECORDING;
        microphone.setText(recording ? R.string.action_stop : R.string.action_speak);
        microphone.setContentDescription(recording
                ? getString(R.string.ime_cd_stop_voice_recording)
                : getString(R.string.ime_cd_start_voice_input));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            microphone.setStateDescription(localizedPipelineState(state));
        }
    }

    private void cancelPipeline(String message, boolean announce) {
        pipeline.cancel();
        activeTarget = null;
        updateMicrophone(VoicePipeline.State.IDLE);
        if (announce) setStatus(message, false);
    }

    private void invalidateLastCommit() {
        lastCommit = null;
        refreshPostCommitActions();
    }

    private void setStatus(String message, boolean error) {
        if (status == null) return;
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText(safeMessage(message));
        status.setTextColor(error ? Color.rgb(170, 40, 40) : Color.rgb(35, 70, 63));
    }

    private void setStatus(int messageResource, boolean error) {
        setStatus(getString(messageResource), error);
    }

    private void setPartialStatus(String message) {
        if (status == null) return;
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        status.setText(safeMessage(message));
        status.setTextColor(Color.rgb(35, 70, 63));
    }

    private void postUi(Runnable runnable) {
        if (status != null) status.post(runnable);
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        cancelPipeline("", false);
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
        refreshModeButtons();
        currentSelectionStart = attribute == null ? -1 : attribute.initialSelStart;
        currentSelectionEnd = attribute == null ? -1 : attribute.initialSelEnd;
        invalidateLastCommit();
        if (sensitiveField) {
            setStatus(R.string.ime_status_sensitive_field, false);
        } else {
            setStatus(getString(
                    R.string.ime_status_ready_mode, localizedModeLabel(selectedMode)), false);
        }
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
        if (target != null
                && target.selectionStart >= 0
                && target.selectionEnd >= 0
                && (target.selectionStart != newSelStart || target.selectionEnd != newSelEnd)) {
            cancelPipeline(getString(R.string.ime_status_cursor_moved_cancelled), true);
        }
    }

    @Override
    public void onFinishInput() {
        cancelPipeline("", false);
        editorEpoch++;
        currentEditor = null;
        sensitiveField = false;
        invalidateLastCommit();
        super.onFinishInput();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        cancelPipeline("", false);
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onWindowHidden() {
        cancelPipeline("", false);
        super.onWindowHidden();
    }

    @Override
    public void onDestroy() {
        pipeline.shutdown();
        activeTarget = null;
        localIo.execute(personalizationStore::close);
        localIo.shutdown();
        super.onDestroy();
    }

    private ViewGroup row() {
        return new WrappingRow(this, dp(4), dp(2));
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
        button.setTextSize(13);
        button.setMinWidth(dp(weight >= 2f ? 96 : 48));
        button.setMinHeight(dp(48));
        button.setOnClickListener(listener);
        ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(2));
        params.setMarginEnd(dp(2));
        button.setLayoutParams(params);
        return button;
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

    /** Wraps keys instead of compressing them below the 48dp touch target on narrow IME windows. */
    private static final class WrappingRow extends ViewGroup {
        private final int horizontalGap;
        private final int verticalGap;

        WrappingRow(android.content.Context context, int horizontalGap, int verticalGap) {
            super(context);
            this.horizontalGap = horizontalGap;
            this.verticalGap = verticalGap;
        }

        @Override
        protected LayoutParams generateDefaultLayoutParams() {
            return new MarginLayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT);
        }

        @Override
        public LayoutParams generateLayoutParams(android.util.AttributeSet attributes) {
            return new MarginLayoutParams(getContext(), attributes);
        }

        @Override
        protected LayoutParams generateLayoutParams(LayoutParams source) {
            return source instanceof MarginLayoutParams margins
                    ? new MarginLayoutParams(margins)
                    : new MarginLayoutParams(source);
        }

        @Override
        protected boolean checkLayoutParams(LayoutParams parameters) {
            return parameters instanceof MarginLayoutParams;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int availableWidth = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? Integer.MAX_VALUE
                    : Math.max(0, MeasureSpec.getSize(widthMeasureSpec)
                            - getPaddingStart()
                            - getPaddingEnd());
            int rowWidth = 0;
            int rowHeight = 0;
            int contentWidth = 0;
            int contentHeight = 0;
            for (int index = 0; index < getChildCount(); index++) {
                View child = getChildAt(index);
                if (child.getVisibility() == GONE) continue;
                MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
                int childWidth = child.getMeasuredWidth()
                        + params.getMarginStart()
                        + params.getMarginEnd();
                int childHeight = child.getMeasuredHeight()
                        + params.topMargin
                        + params.bottomMargin;
                int gap = rowWidth == 0 ? 0 : horizontalGap;
                if (rowWidth > 0 && rowWidth + gap + childWidth > availableWidth) {
                    contentWidth = Math.max(contentWidth, rowWidth);
                    contentHeight += rowHeight + (contentHeight == 0 ? 0 : verticalGap);
                    rowWidth = 0;
                    rowHeight = 0;
                    gap = 0;
                }
                rowWidth += gap + childWidth;
                rowHeight = Math.max(rowHeight, childHeight);
            }
            if (rowWidth > 0) {
                contentWidth = Math.max(contentWidth, rowWidth);
                contentHeight += rowHeight + (contentHeight == 0 ? 0 : verticalGap);
            }
            int desiredWidth = contentWidth + getPaddingStart() + getPaddingEnd();
            int desiredHeight = contentHeight + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(
                    resolveSize(desiredWidth, widthMeasureSpec),
                    resolveSize(desiredHeight, heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int availableWidth = Math.max(0, right - left - getPaddingStart() - getPaddingEnd());
            int rowWidth = 0;
            int rowHeight = 0;
            int y = getPaddingTop();
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            for (int index = 0; index < getChildCount(); index++) {
                View child = getChildAt(index);
                if (child.getVisibility() == GONE) continue;
                MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
                int childWidth = child.getMeasuredWidth()
                        + params.getMarginStart()
                        + params.getMarginEnd();
                int childHeight = child.getMeasuredHeight()
                        + params.topMargin
                        + params.bottomMargin;
                int gap = rowWidth == 0 ? 0 : horizontalGap;
                if (rowWidth > 0 && rowWidth + gap + childWidth > availableWidth) {
                    y += rowHeight + verticalGap;
                    rowWidth = 0;
                    rowHeight = 0;
                    gap = 0;
                }
                int logicalStart = rowWidth + gap + params.getMarginStart();
                int childLeft = rtl
                        ? getWidth() - getPaddingEnd() - logicalStart - child.getMeasuredWidth()
                        : getPaddingStart() + logicalStart;
                int childTop = y + params.topMargin;
                child.layout(
                        childLeft,
                        childTop,
                        childLeft + child.getMeasuredWidth(),
                        childTop + child.getMeasuredHeight());
                rowWidth += gap + childWidth;
                rowHeight = Math.max(rowHeight, childHeight);
            }
        }
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

    private String localizedPipelineState(VoicePipeline.State state) {
        return getString(switch (state) {
            case IDLE -> R.string.ime_state_idle;
            case RECORDING -> R.string.ime_state_recording;
            case TRANSCRIBING -> R.string.ime_state_transcribing;
            case POLISHING -> R.string.ime_state_polishing;
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
