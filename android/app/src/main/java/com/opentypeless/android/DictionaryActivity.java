package com.opentypeless.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.opentypeless.android.data.CorrectionRule;
import com.opentypeless.android.data.PersonalTerm;
import com.opentypeless.android.data.PersonalizationStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DictionaryActivity extends Activity {
    private static final int OPEN_BACKUP_REQUEST = 201;
    private static final int CREATE_BACKUP_REQUEST = 202;
    private static final int MAX_IMPORT_BYTES = 1_048_576;
    private static final int PAGE_SIZE = 50;
    private static final String STATE_TERM_CANONICAL = "term_canonical";
    private static final String STATE_TERM_PRONUNCIATION = "term_pronunciation";
    private static final String STATE_TERM_ALIASES = "term_aliases";
    private static final String STATE_TERM_SCOPE = "term_scope";
    private static final String STATE_CORRECTION_PATTERN = "correction_pattern";
    private static final String STATE_CORRECTION_REPLACEMENT = "correction_replacement";
    private static final String STATE_CORRECTION_SCOPE = "correction_scope";

    private PersonalizationStore store;
    private EditText termCanonical;
    private EditText termPronunciation;
    private EditText termAliases;
    private EditText termScope;
    private EditText correctionPattern;
    private EditText correctionReplacement;
    private EditText correctionScope;
    private LinearLayout termList;
    private LinearLayout correctionList;
    private int termOffset;
    private int correctionOffset;
    private int listGeneration;
    private boolean termPageLoading;
    private boolean correctionPageLoading;
    private ExecutorService io;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new PersonalizationStore(this);
        io = Executors.newSingleThreadExecutor();
        setTitle(R.string.dictionary_title);
        setContentView(buildContent());
        restoreForm(savedInstanceState);
        refreshLists();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        SystemBarInsets.apply(scroll);
        LinearLayout root = verticalLayout();
        int padding = dp(20);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);

        root.addView(title(R.string.dictionary_title));
        root.addView(note(R.string.dictionary_intro));

        root.addView(section(R.string.section_add_term));
        termCanonical = field(root, R.string.term_canonical_label, false);
        termPronunciation = field(root, R.string.term_pronunciation_label, false);
        termAliases = field(root, R.string.term_aliases_label, true);
        termScope = field(root, R.string.app_scope_label, false);
        root.addView(button(R.string.add_term, ignored -> addTerm()));

        root.addView(section(R.string.section_add_correction));
        correctionPattern = field(root, R.string.wrong_phrase_label, true);
        correctionReplacement = field(root, R.string.correct_phrase_label, true);
        correctionScope = field(root, R.string.app_scope_label, false);
        root.addView(button(R.string.add_correction, ignored -> addCorrection()));

        root.addView(section(R.string.section_backup));
        LinearLayout backupActions = horizontalLayout();
        backupActions.addView(
                button(R.string.import_personalization, ignored -> chooseImport()),
                weighted());
        backupActions.addView(
                button(R.string.export_personalization, ignored -> chooseExport()),
                weighted());
        root.addView(backupActions, matchWrap());
        root.addView(note(R.string.backup_privacy_note));

        root.addView(section(R.string.section_terms));
        termList = verticalLayout();
        root.addView(termList, matchWrap());

        root.addView(section(R.string.section_corrections));
        correctionList = verticalLayout();
        root.addView(correctionList, matchWrap());
        return scroll;
    }

    private void addTerm() {
        String canonical = value(termCanonical);
        String pronunciation = value(termPronunciation);
        String aliases = value(termAliases);
        String scope = value(termScope);
        io.execute(() -> {
            try {
                store.addTerm(canonical, pronunciation, aliases, scope);
                postUi(() -> {
                    clearIfUnchanged(termCanonical, canonical);
                    clearIfUnchanged(termPronunciation, pronunciation);
                    clearIfUnchanged(termAliases, aliases);
                    Toast.makeText(this, R.string.term_added, Toast.LENGTH_SHORT).show();
                    refreshLists();
                });
            } catch (IllegalArgumentException error) {
                postUi(() -> showError(error));
            }
        });
    }

    private void addCorrection() {
        String pattern = value(correctionPattern);
        String replacement = value(correctionReplacement);
        String scope = value(correctionScope);
        io.execute(() -> {
            try {
                store.addCorrection(pattern, replacement, scope);
                postUi(() -> {
                    clearIfUnchanged(correctionPattern, pattern);
                    clearIfUnchanged(correctionReplacement, replacement);
                    Toast.makeText(this, R.string.correction_added, Toast.LENGTH_SHORT).show();
                    refreshLists();
                });
            } catch (IllegalArgumentException error) {
                postUi(() -> showError(error));
            }
        });
    }

    private void refreshLists() {
        listGeneration++;
        termOffset = 0;
        correctionOffset = 0;
        termPageLoading = false;
        correctionPageLoading = false;
        termList.removeAllViews();
        correctionList.removeAllViews();
        termList.addView(empty(R.string.loading));
        correctionList.addView(empty(R.string.loading));
        appendTermPage(listGeneration, true);
        appendCorrectionPage(listGeneration, true);
    }

    private void appendTermPage(int generation, boolean firstPage) {
        if (termPageLoading) return;
        termPageLoading = true;
        int requestedOffset = termOffset;
        io.execute(() -> {
            try {
                List<PersonalTerm> terms = store.listTerms(PAGE_SIZE, requestedOffset);
                postUi(() -> renderTermPage(generation, firstPage, requestedOffset, terms));
            } catch (RuntimeException error) {
                postUi(() -> {
                    if (generation != listGeneration) return;
                    termPageLoading = false;
                    showError(error);
                });
            }
        });
    }

    private void renderTermPage(
            int generation,
            boolean firstPage,
            int requestedOffset,
            List<PersonalTerm> terms) {
        if (generation != listGeneration) return;
        termPageLoading = false;
        if (firstPage) termList.removeAllViews();
        if (terms.isEmpty()) {
            if (requestedOffset == 0) termList.addView(empty(R.string.no_terms));
        } else {
            for (PersonalTerm term : terms) termList.addView(termCard(term));
            termOffset = requestedOffset + terms.size();
            if (terms.size() == PAGE_SIZE) {
                Button more = button(R.string.load_more, ignored -> {
                    termList.removeView(ignored);
                    appendTermPage(generation, false);
                });
                termList.addView(more);
            }
        }
    }

    private void appendCorrectionPage(int generation, boolean firstPage) {
        if (correctionPageLoading) return;
        correctionPageLoading = true;
        int requestedOffset = correctionOffset;
        io.execute(() -> {
            try {
                List<CorrectionRule> corrections =
                        store.listCorrections(PAGE_SIZE, requestedOffset);
                postUi(() -> renderCorrectionPage(
                        generation,
                        firstPage,
                        requestedOffset,
                        corrections));
            } catch (RuntimeException error) {
                postUi(() -> {
                    if (generation != listGeneration) return;
                    correctionPageLoading = false;
                    showError(error);
                });
            }
        });
    }

    private void renderCorrectionPage(
            int generation,
            boolean firstPage,
            int requestedOffset,
            List<CorrectionRule> corrections) {
        if (generation != listGeneration) return;
        correctionPageLoading = false;
        if (firstPage) correctionList.removeAllViews();
        if (corrections.isEmpty()) {
            if (requestedOffset == 0) correctionList.addView(empty(R.string.no_corrections));
        } else {
            for (CorrectionRule rule : corrections) correctionList.addView(correctionCard(rule));
            correctionOffset = requestedOffset + corrections.size();
            if (corrections.size() == PAGE_SIZE) {
                Button more = button(R.string.load_more, ignored -> {
                    correctionList.removeView(ignored);
                    appendCorrectionPage(generation, false);
                });
                correctionList.addView(more);
            }
        }
    }

    private View termCard(PersonalTerm term) {
        LinearLayout card = card();
        TextView canonical = text(term.canonical(), 17, true);
        card.addView(canonical);
        StringBuilder detail = new StringBuilder();
        if (!term.pronunciation().isBlank()) {
            detail.append(getString(R.string.pronunciation_summary, term.pronunciation())).append('\n');
        }
        if (!term.aliases().isBlank()) {
            detail.append(getString(R.string.aliases_summary, term.aliases())).append('\n');
        }
        detail.append(getString(
                R.string.scope_summary,
                term.appScope().isBlank() ? getString(R.string.global_scope) : term.appScope()));
        detail.append(" · ").append(getResources().getQuantityString(
                R.plurals.use_count_summary,
                term.useCount(),
                term.useCount()));
        card.addView(detail(detail.toString()));

        LinearLayout actions = horizontalLayout();
        CheckBox enabled = toggle(term.enabled());
        enabled.setContentDescription(getString(R.string.enabled) + ": " + term.canonical());
        enabled.setOnCheckedChangeListener((ignored, checked) -> io.execute(() -> {
            try {
                store.setTermEnabled(term.id(), checked);
            } catch (RuntimeException error) {
                postUi(() -> showError(error));
            }
        }));
        actions.addView(enabled, weighted());
        Button delete = button(R.string.delete, ignored -> confirmDeleteTerm(term));
        delete.setContentDescription(getString(R.string.delete) + ": " + term.canonical());
        actions.addView(delete, weighted());
        card.addView(actions, matchWrap());
        return card;
    }

    private View correctionCard(CorrectionRule rule) {
        LinearLayout card = card();
        card.addView(text(rule.pattern() + " → " + rule.replacement(), 17, true));
        String scope = rule.appScope().isBlank() ? getString(R.string.global_scope) : rule.appScope();
        card.addView(detail(
                getString(R.string.scope_summary, scope)
                        + " · "
                        + getResources().getQuantityString(
                                R.plurals.use_count_summary,
                                rule.useCount(),
                                rule.useCount())));

        LinearLayout actions = horizontalLayout();
        CheckBox enabled = toggle(rule.enabled());
        enabled.setContentDescription(
                getString(R.string.enabled) + ": " + rule.pattern() + " → " + rule.replacement());
        enabled.setOnCheckedChangeListener((ignored, checked) -> io.execute(() -> {
            try {
                store.setCorrectionEnabled(rule.id(), checked);
            } catch (RuntimeException error) {
                postUi(() -> showError(error));
            }
        }));
        actions.addView(enabled, weighted());
        Button delete = button(R.string.delete, ignored -> confirmDeleteCorrection(rule));
        delete.setContentDescription(
                getString(R.string.delete) + ": " + rule.pattern() + " → " + rule.replacement());
        actions.addView(delete, weighted());
        card.addView(actions, matchWrap());
        return card;
    }

    private void confirmDeleteTerm(PersonalTerm term) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_term_title)
                .setMessage(getString(R.string.delete_term_message, term.canonical()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (ignored, which) -> {
                    io.execute(() -> {
                        store.deleteTerm(term.id());
                        postUi(this::refreshLists);
                    });
                })
                .show();
    }

    private void confirmDeleteCorrection(CorrectionRule rule) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_correction_title)
                .setMessage(getString(
                        R.string.delete_correction_message,
                        rule.pattern(),
                        rule.replacement()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (ignored, which) -> {
                    io.execute(() -> {
                        store.deleteCorrection(rule.id());
                        postUi(this::refreshLists);
                    });
                })
                .show();
    }

    private void chooseImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, OPEN_BACKUP_REQUEST);
    }

    private void chooseExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "opentypeless-personalization.json");
        startActivityForResult(intent, CREATE_BACKUP_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == OPEN_BACKUP_REQUEST) previewImport(uri);
        else if (requestCode == CREATE_BACKUP_REQUEST) exportTo(uri);
    }

    private void previewImport(Uri uri) {
        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                String json = readLimited(uri);
                PersonalizationStore.ImportPreview preview = store.previewPersonalization(json);
                postUi(() -> showImportPreview(preview));
            } catch (Exception error) {
                postUi(() -> Toast.makeText(
                        this,
                        importError(error),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showImportPreview(PersonalizationStore.ImportPreview preview) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.import_preview_title)
                .setMessage(previewMessage(preview))
                .setNegativeButton(R.string.cancel, null);
        if (preview.fitsCapacity()) {
            dialog.setPositiveButton(
                    R.string.confirm_import,
                    (ignored, which) -> importConfirmed(preview));
        } else {
            dialog.setMessage(previewMessage(preview)
                    + "\n\n"
                    + getString(R.string.import_capacity_error));
        }
        dialog.show();
    }

    private String previewMessage(PersonalizationStore.ImportPreview preview) {
        StringBuilder message = new StringBuilder(getString(
                R.string.import_preview_message,
                Integer.toString(preview.acceptedTerms()),
                Integer.toString(preview.acceptedCorrections()),
                Integer.toString(preview.duplicateTerms()),
                Integer.toString(preview.duplicateCorrections()),
                Integer.toString(preview.invalidRows()),
                getString(preview.fitsCapacity()
                        ? R.string.import_capacity_fits
                        : R.string.import_capacity_does_not_fit)));
        appendImportSamples(message, preview);
        message.append("\n\n").append(getString(R.string.import_preview_confirmation));
        return message.toString();
    }

    private void appendImportSamples(
            StringBuilder message,
            PersonalizationStore.ImportPreview preview) {
        if (!preview.termSamples().isEmpty()) {
            message.append("\n\n").append(getString(
                    R.string.import_term_samples,
                    preview.termSamples().size(),
                    preview.acceptedTerms()));
            for (PersonalizationStore.ImportTerm term : preview.termSamples()) {
                message.append("\n• “").append(previewValue(term.canonical())).append('”');
                if (!term.pronunciation().isBlank()) {
                    message.append(" · ").append(getString(
                            R.string.pronunciation_summary,
                            previewValue(term.pronunciation())));
                }
                if (!term.aliases().isBlank()) {
                    message.append(" · ").append(getString(
                            R.string.aliases_summary,
                            previewValue(term.aliases())));
                }
                message.append(" · ").append(getString(
                        R.string.scope_summary,
                        term.appScope().isBlank()
                                ? getString(R.string.global_scope)
                                : previewValue(term.appScope())));
            }
        }
        if (!preview.correctionSamples().isEmpty()) {
            message.append("\n\n").append(getString(
                    R.string.import_correction_samples,
                    preview.correctionSamples().size(),
                    preview.acceptedCorrections()));
            for (PersonalizationStore.ImportCorrection correction : preview.correctionSamples()) {
                message.append("\n• “")
                        .append(previewValue(correction.pattern()))
                        .append("” → “")
                        .append(previewValue(correction.replacement()))
                        .append("” · ")
                        .append(getString(
                                R.string.scope_summary,
                                correction.appScope().isBlank()
                                        ? getString(R.string.global_scope)
                                        : previewValue(correction.appScope())));
            }
        }
    }

    static String previewValue(String value) {
        String safe = value == null ? "" : value;
        StringBuilder visible = new StringBuilder(safe.length());
        for (int offset = 0; offset < safe.length(); ) {
            int codePoint = safe.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint) || type == Character.FORMAT) {
                visible.append(String.format(
                        java.util.Locale.ROOT,
                        "⟦U+%04X⟧",
                        codePoint));
            } else {
                visible.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return visible.toString();
    }

    private void importConfirmed(PersonalizationStore.ImportPreview preview) {
        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                PersonalizationStore.ImportReport report = store.commitPersonalization(preview);
                postUi(() -> {
                    int imported = report.importedTotal();
                    Toast.makeText(this, getResources().getQuantityString(
                            R.plurals.import_complete,
                            imported,
                            imported), Toast.LENGTH_LONG).show();
                    refreshLists();
                });
            } catch (IllegalArgumentException error) {
                postUi(() -> showError(error));
            }
        });
    }

    private void exportTo(Uri uri) {
        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException(getString(R.string.operation_failed));
                output.write(store.exportPersonalization().getBytes(StandardCharsets.UTF_8));
                output.flush();
                postUi(() -> Toast.makeText(
                        this,
                        R.string.export_complete,
                        Toast.LENGTH_SHORT).show());
            } catch (Exception error) {
                postUi(() -> showError(error));
            }
        });
    }

    private String readLimited(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException(getString(R.string.operation_failed));
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_IMPORT_BYTES) {
                    throw new IllegalArgumentException(getString(R.string.backup_too_large));
                }
                bytes.write(buffer, 0, read);
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String importError(Exception error) {
        if (error instanceof IllegalArgumentException && error.getMessage() != null) {
            return error.getMessage();
        }
        return getString(R.string.invalid_backup);
    }

    private EditText field(LinearLayout root, int labelResource, boolean multiline) {
        String label = getString(labelResource);
        TextView labelView = text(label, 14, true);
        labelView.setPadding(0, dp(8), 0, 0);
        root.addView(labelView);
        EditText field = new EditText(this);
        field.setContentDescription(label);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        field.setMinHeight(dp(multiline ? 72 : 48));
        field.setSingleLine(!multiline);
        if (multiline) {
            field.setMinLines(2);
            field.setGravity(Gravity.TOP | Gravity.START);
        }
        root.addView(field, matchWrap());
        return field;
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

    private CheckBox toggle(boolean checked) {
        CheckBox toggle = new CheckBox(this);
        toggle.setText(R.string.enabled);
        toggle.setChecked(checked);
        toggle.setMinHeight(dp(48));
        return toggle;
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

    private TextView section(int resource) {
        TextView section = text(getString(resource), 19, true);
        section.setPadding(0, dp(18), 0, dp(4));
        heading(section);
        return section;
    }

    private TextView note(int resource) {
        TextView note = text(getString(resource), 14, false);
        note.setTextColor(getColor(R.color.ime_on_surface_variant));
        note.setPadding(0, dp(8), 0, dp(12));
        return note;
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

    private void showError(Exception error) {
        String message = error.getMessage();
        Toast.makeText(
                this,
                message == null || message.trim().isEmpty()
                        ? getString(R.string.operation_failed)
                        : message,
                Toast.LENGTH_LONG).show();
    }

    private void postUi(Runnable action) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) action.run();
        });
    }

    private static void clearIfUnchanged(EditText field, String expectedValue) {
        if (value(field).equals(expectedValue)) field.setText("");
    }

    private void restoreForm(Bundle state) {
        if (state == null) return;
        termCanonical.setText(state.getString(STATE_TERM_CANONICAL, ""));
        termPronunciation.setText(state.getString(STATE_TERM_PRONUNCIATION, ""));
        termAliases.setText(state.getString(STATE_TERM_ALIASES, ""));
        termScope.setText(state.getString(STATE_TERM_SCOPE, ""));
        correctionPattern.setText(state.getString(STATE_CORRECTION_PATTERN, ""));
        correctionReplacement.setText(state.getString(STATE_CORRECTION_REPLACEMENT, ""));
        correctionScope.setText(state.getString(STATE_CORRECTION_SCOPE, ""));
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString(STATE_TERM_CANONICAL, termCanonical.getText().toString());
        state.putString(STATE_TERM_PRONUNCIATION, termPronunciation.getText().toString());
        state.putString(STATE_TERM_ALIASES, termAliases.getText().toString());
        state.putString(STATE_TERM_SCOPE, termScope.getText().toString());
        state.putString(STATE_CORRECTION_PATTERN, correctionPattern.getText().toString());
        state.putString(STATE_CORRECTION_REPLACEMENT, correctionReplacement.getText().toString());
        state.putString(STATE_CORRECTION_SCOPE, correctionScope.getText().toString());
        super.onSaveInstanceState(state);
    }

    private static String value(EditText field) {
        return field.getText().toString().trim();
    }

    private static void clear(EditText... fields) {
        for (EditText field : fields) field.setText("");
    }

    @Override
    protected void onDestroy() {
        listGeneration++;
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

}
