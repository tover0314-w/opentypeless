package com.opentypeless.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.opentypeless.android.data.HistoryEntry;
import com.opentypeless.android.data.PersonalizationStore;
import com.opentypeless.android.personalization.TeachCorrectionResolver;
import com.opentypeless.android.settings.SettingsRepository;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HistoryActivity extends Activity {
    private static final String EXTRA_HISTORY_ID = "history_id";
    private static final String EXTRA_RAW_TEXT = "raw_text";
    private static final String EXTRA_FINAL_TEXT = "final_text";
    private static final String EXTRA_APP_SCOPE = "app_scope";
    private static final int PAGE_SIZE = 25;
    private static final String STATE_TEACH_HANDLED = "teach_intent_handled";
    private static final String STATE_DIALOG_OPEN = "correction_dialog_open";
    private static final String STATE_DIALOG_PATTERN = "correction_dialog_pattern";
    private static final String STATE_DIALOG_REPLACEMENT = "correction_dialog_replacement";
    private static final String STATE_DIALOG_SCOPE = "correction_dialog_scope";

    private PersonalizationStore store;
    private LinearLayout historyList;
    private TextView historyDisabledNote;
    private boolean teachIntentHandled;
    private int historyOffset;
    private int historyGeneration;
    private ExecutorService io;
    private boolean teachLoadPending;
    private AlertDialog correctionDialog;
    private EditText dialogPattern;
    private EditText dialogReplacement;
    private EditText dialogScope;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new PersonalizationStore(this);
        io = Executors.newSingleThreadExecutor();
        setTitle(R.string.history_title);
        setContentView(buildContent());
        if (savedInstanceState != null) {
            teachIntentHandled = savedInstanceState.getBoolean(STATE_TEACH_HANDLED, false);
            if (savedInstanceState.getBoolean(STATE_DIALOG_OPEN, false)) {
                CorrectionDraft draft = new CorrectionDraft(
                        savedInstanceState.getString(STATE_DIALOG_PATTERN, ""),
                        savedInstanceState.getString(STATE_DIALOG_REPLACEMENT, ""));
                String scope = savedInstanceState.getString(STATE_DIALOG_SCOPE, "");
                historyList.post(() -> showCorrectionDialog(draft, scope));
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHistory();
        if (!teachIntentHandled && hasTeachIntent()) {
            teachIntentHandled = true;
            loadTeachIntent();
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        SystemBarInsets.apply(scroll);
        LinearLayout root = verticalLayout();
        int padding = dp(20);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);

        root.addView(title(R.string.history_title));
        root.addView(note(R.string.history_intro));
        historyDisabledNote = warning(R.string.history_disabled_note);
        root.addView(historyDisabledNote);
        root.addView(button(R.string.clear_history, ignored -> confirmClearHistory()));
        historyList = verticalLayout();
        historyList.setPadding(0, dp(12), 0, 0);
        root.addView(historyList, matchWrap());
        return scroll;
    }

    private void refreshHistory() {
        boolean enabled = new SettingsRepository(this).loadHistoryEnabled();
        historyDisabledNote.setVisibility(enabled ? View.GONE : View.VISIBLE);
        historyList.removeAllViews();
        historyOffset = 0;
        historyGeneration++;
        historyList.addView(empty(R.string.loading));
        appendHistoryPage(historyGeneration, true);
    }

    private void appendHistoryPage(int generation, boolean firstPage) {
        int requestedOffset = historyOffset;
        io.execute(() -> {
            List<HistoryEntry> entries = store.listHistory(PAGE_SIZE, requestedOffset);
            runOnUiThread(() -> renderHistoryPage(generation, firstPage, entries));
        });
    }

    private void renderHistoryPage(
            int generation,
            boolean firstPage,
            List<HistoryEntry> entries) {
        if (isFinishing() || isDestroyed() || generation != historyGeneration) return;
        if (firstPage) historyList.removeAllViews();
        if (entries.isEmpty()) {
            if (historyOffset == 0) historyList.addView(empty(R.string.no_history));
            return;
        }
        for (HistoryEntry entry : entries) historyList.addView(historyCard(entry));
        historyOffset += entries.size();
        if (entries.size() == PAGE_SIZE) {
            Button more = button(R.string.load_more, ignored -> {
                historyList.removeView(ignored);
                appendHistoryPage(generation, false);
            });
            historyList.addView(more);
        }
    }

    private boolean hasTeachIntent() {
        return getIntent() != null
                && (getIntent().hasExtra(EXTRA_HISTORY_ID)
                || getIntent().hasExtra(EXTRA_RAW_TEXT)
                || getIntent().hasExtra(EXTRA_FINAL_TEXT));
    }

    private void loadTeachIntent() {
        teachLoadPending = true;
        long historyId = getIntent().getLongExtra(EXTRA_HISTORY_ID, -1L);
        String requestedScope = safeExtra(EXTRA_APP_SCOPE);
        String raw = safeExtra(EXTRA_RAW_TEXT);
        String result = safeExtra(EXTRA_FINAL_TEXT);
        io.execute(() -> {
            HistoryEntry stored = historyId > 0 ? store.history(historyId) : null;
            HistoryEntry entry = TeachCorrectionResolver.resolve(
                    stored,
                    raw,
                    result,
                    requestedScope);
            runOnUiThread(() -> openTeachEntry(entry));
        });
    }

    private void openTeachEntry(HistoryEntry entry) {
        teachLoadPending = false;
        if (isFinishing() || isDestroyed()) return;
        if (entry == null
                || entry.rawText().isBlank()
                || entry.finalText().isBlank()) {
            Toast.makeText(this, R.string.teach_data_missing, Toast.LENGTH_LONG).show();
            return;
        }
        showCorrectionDialog(entry);
    }

    private String safeExtra(String name) {
        String value = getIntent().getStringExtra(name);
        return value == null ? "" : value.trim();
    }

    private View historyCard(HistoryEntry entry) {
        LinearLayout card = card();
        String date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(entry.createdAt()));
        card.addView(text(date, 16, true));
        String app = entry.appPackage().isBlank()
                ? getString(R.string.unknown_app)
                : entry.appPackage();
        card.addView(detail(getString(
                R.string.history_metadata,
                app,
                entry.fieldKind(),
                entry.mode(),
                entry.backend(),
                entry.durationMs())));

        card.addView(transcriptBlock(R.string.raw_transcript_label, limited(entry.rawText(), 800)));
        card.addView(transcriptBlock(R.string.final_transcript_label, limited(entry.finalText(), 800)));
        LinearLayout actions = horizontalLayout();
        Button view = button(R.string.view_full_history_entry, ignored -> showFullEntry(entry));
        view.setContentDescription(
                getString(R.string.view_full_history_entry) + ": " + limited(entry.rawText(), 60));
        actions.addView(view, weighted());
        Button save = button(R.string.save_correction, ignored -> showCorrectionDialog(entry));
        save.setContentDescription(
                getString(R.string.save_correction) + ": " + limited(entry.rawText(), 60));
        actions.addView(save, weighted());
        Button delete = button(
                R.string.delete_history_entry,
                ignored -> confirmDeleteHistory(entry));
        delete.setContentDescription(
                getString(R.string.delete_history_entry) + ": " + limited(entry.rawText(), 60));
        actions.addView(delete, weighted());
        card.addView(actions, matchWrap());
        return card;
    }

    private void showFullEntry(HistoryEntry entry) {
        LinearLayout content = verticalLayout();
        int padding = dp(20);
        content.setPadding(padding, padding, padding, padding);
        content.addView(transcriptBlock(R.string.raw_transcript_label, entry.rawText()));
        content.addView(transcriptBlock(R.string.final_transcript_label, entry.finalText()));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle(R.string.view_full_history_entry)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private View transcriptBlock(int labelResource, String value) {
        LinearLayout block = verticalLayout();
        block.setPadding(0, dp(8), 0, dp(4));
        TextView label = text(getString(labelResource), 13, true);
        TextView transcript = text(value == null ? "" : value, 15, false);
        transcript.setId(View.generateViewId());
        label.setLabelFor(transcript.getId());
        block.addView(label);
        // Do not override the TextView's accessible text with a truncated contentDescription.
        // TalkBack can now read the entire transcript in the full-entry dialog.
        block.addView(transcript);
        return block;
    }

    private void showCorrectionDialog(HistoryEntry entry) {
        showCorrectionDialog(
                differenceDraft(entry.rawText(), entry.finalText()),
                entry.appPackage());
    }

    private void showCorrectionDialog(CorrectionDraft draft, String initialScope) {
        if (correctionDialog != null && correctionDialog.isShowing()) return;
        LinearLayout form = verticalLayout();
        int padding = dp(20);
        form.setPadding(padding, 0, padding, 0);
        TextView explanation = note(R.string.save_correction_note);
        form.addView(explanation);
        EditText pattern = dialogField(
                form,
                R.string.wrong_phrase_label,
                draft.pattern());
        EditText replacement = dialogField(
                form,
                R.string.correct_phrase_label,
                draft.replacement());
        EditText scope = dialogField(
                form,
                R.string.app_scope_label,
                initialScope);

        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.save_correction_title)
                .setView(formScroll)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm_save, null)
                .create();
        correctionDialog = dialog;
        dialogPattern = pattern;
        dialogReplacement = replacement;
        dialogScope = scope;
        dialog.setOnDismissListener(ignored -> {
            if (correctionDialog == dialog) {
                correctionDialog = null;
                dialogPattern = null;
                dialogReplacement = null;
                dialogScope = null;
            }
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    String wrong = value(pattern);
                    String correct = value(replacement);
                    if (wrong.equals(correct)) {
                        pattern.setError(getString(R.string.same_correction_error));
                        return;
                    }
                    String appScope = value(scope);
                    button.setEnabled(false);
                    io.execute(() -> {
                        try {
                            store.addCorrection(wrong, correct, appScope);
                            postUi(() -> {
                                Toast.makeText(
                                        this,
                                        R.string.correction_added,
                                        Toast.LENGTH_SHORT).show();
                                dialog.dismiss();
                            });
                        } catch (RuntimeException error) {
                            postUi(() -> {
                                button.setEnabled(true);
                                replacement.setError(safeMessage(error));
                            });
                        }
                    });
                }));
        dialog.show();
        if (replacement.getText().length() == 0) replacement.requestFocus();
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_history_title)
                .setMessage(R.string.clear_history_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm_clear, (ignored, which) -> {
                    io.execute(() -> {
                        store.clearHistory();
                        postUi(() -> {
                            Toast.makeText(
                                    this,
                                    R.string.history_cleared,
                                    Toast.LENGTH_SHORT).show();
                            refreshHistory();
                        });
                    });
                })
                .show();
    }

    private void confirmDeleteHistory(HistoryEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_history_entry_title)
                .setMessage(R.string.delete_history_entry_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_history_entry, (ignored, which) -> {
                    io.execute(() -> {
                        store.deleteHistory(entry.id());
                        postUi(() -> {
                            Toast.makeText(
                                    this,
                                    R.string.history_entry_deleted,
                                    Toast.LENGTH_SHORT).show();
                            refreshHistory();
                        });
                    });
                })
                .show();
    }

    private EditText dialogField(
            LinearLayout root,
            int labelResource,
            String initialValue) {
        String label = getString(labelResource);
        TextView labelView = text(label, 14, true);
        labelView.setPadding(0, dp(8), 0, 0);
        root.addView(labelView);
        EditText field = new EditText(this);
        field.setText(initialValue == null ? "" : initialValue);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setMinHeight(dp(64));
        field.setMinLines(2);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setContentDescription(label);
        root.addView(field, matchWrap());
        return field;
    }

    /** Package-private instrumentation hook; the dialog owns a separate window from the Activity. */
    List<EditText> correctionDialogFieldsForTest() {
        if (correctionDialog == null
                || !correctionDialog.isShowing()
                || dialogPattern == null
                || dialogReplacement == null
                || dialogScope == null) {
            return List.of();
        }
        return List.of(dialogPattern, dialogReplacement, dialogScope);
    }

    private LinearLayout card() {
        LinearLayout card = verticalLayout();
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackgroundColor(getColor(R.color.ime_surface_container));
        LinearLayout.LayoutParams parameters = matchWrap();
        parameters.setMargins(0, dp(4), 0, dp(8));
        card.setLayoutParams(parameters);
        return card;
    }

    private Button button(int labelResource, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(labelResource);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setContentDescription(getString(labelResource));
        button.setOnClickListener(listener);
        return button;
    }

    private TextView title(int resource) {
        TextView title = text(getString(resource), 26, true);
        heading(title);
        return title;
    }

    private TextView note(int resource) {
        TextView note = text(getString(resource), 14, false);
        note.setTextColor(getColor(R.color.ime_on_surface_variant));
        note.setPadding(0, dp(8), 0, dp(12));
        return note;
    }

    private TextView warning(int resource) {
        TextView warning = text(getString(resource), 14, false);
        warning.setTextColor(getColor(R.color.ime_warning));
        warning.setPadding(0, dp(4), 0, dp(8));
        return warning;
    }

    private TextView empty(int resource) {
        TextView empty = text(getString(resource), 14, false);
        empty.setTextColor(getColor(R.color.ime_on_surface_variant));
        empty.setMinHeight(dp(48));
        empty.setGravity(Gravity.CENTER_VERTICAL);
        return empty;
    }

    private TextView detail(String value) {
        TextView detail = text(value, 13, false);
        detail.setTextColor(getColor(R.color.ime_on_surface_variant));
        detail.setPadding(0, dp(4), 0, dp(4));
        return detail;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private void heading(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) view.setAccessibilityHeading(true);
    }

    private LinearLayout verticalLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontalLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static String value(EditText field) {
        return field.getText().toString().trim();
    }

    private static String limited(String value, int maximumCodePoints) {
        if (value == null) return "";
        String clean = value.trim();
        int count = clean.codePointCount(0, clean.length());
        if (count <= maximumCodePoints) return clean;
        return clean.substring(0, clean.offsetByCodePoints(0, maximumCodePoints));
    }

    private static CorrectionDraft differenceDraft(String rawValue, String finalValue) {
        String raw = rawValue == null ? "" : rawValue.trim();
        String result = finalValue == null ? "" : finalValue.trim();
        if (raw.equals(result)) {
            return new CorrectionDraft(limited(raw, 160), "");
        }
        int[] rawPoints = raw.codePoints().toArray();
        int[] resultPoints = result.codePoints().toArray();
        int prefix = 0;
        while (prefix < rawPoints.length
                && prefix < resultPoints.length
                && rawPoints[prefix] == resultPoints[prefix]) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < rawPoints.length - prefix
                && suffix < resultPoints.length - prefix
                && rawPoints[rawPoints.length - 1 - suffix]
                == resultPoints[resultPoints.length - 1 - suffix]) {
            suffix++;
        }
        String pattern = new String(
                rawPoints,
                prefix,
                rawPoints.length - prefix - suffix).trim();
        String replacement = new String(
                resultPoints,
                prefix,
                resultPoints.length - prefix - suffix).trim();
        if (pattern.isEmpty() || replacement.isEmpty()) {
            pattern = raw;
            replacement = result;
        }
        return new CorrectionDraft(limited(pattern, 160), limited(replacement, 160));
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? getString(R.string.operation_failed)
                : message;
    }

    private void postUi(Runnable action) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) action.run();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(STATE_TEACH_HANDLED, teachIntentHandled && !teachLoadPending);
        AlertDialog dialog = correctionDialog;
        if (dialog != null && dialog.isShowing()
                && dialogPattern != null
                && dialogReplacement != null
                && dialogScope != null) {
            state.putBoolean(STATE_DIALOG_OPEN, true);
            state.putString(STATE_DIALOG_PATTERN, dialogPattern.getText().toString());
            state.putString(STATE_DIALOG_REPLACEMENT, dialogReplacement.getText().toString());
            state.putString(STATE_DIALOG_SCOPE, dialogScope.getText().toString());
        }
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        historyGeneration++;
        AlertDialog dialog = correctionDialog;
        if (dialog != null) {
            // Custom-created dialogs are separate windows and must be dismissed before the
            // Activity token dies. onSaveInstanceState has already captured any open draft.
            dialog.dismiss();
        }
        if (io != null) {
            io.execute(store::close);
            io.shutdown();
        } else if (store != null) {
            store.close();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private record CorrectionDraft(String pattern, String replacement) {}
}
