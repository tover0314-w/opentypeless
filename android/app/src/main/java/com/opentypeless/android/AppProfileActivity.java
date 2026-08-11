package com.opentypeless.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.opentypeless.android.settings.AppProfile;
import com.opentypeless.android.settings.AppProfileDraft;
import com.opentypeless.android.settings.AppProfileRepository;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.SettingsRepository;

import java.util.ArrayList;
import java.util.List;

/** Explicit per-app tone and routing preferences; nothing is learned from foreground app content. */
public final class AppProfileActivity extends Activity {
    public static final String EXTRA_PACKAGE = "app_package";
    private static final String STATE_PREFIX = "profile_draft_";
    private static final String STATE_HAS_DRAFT = STATE_PREFIX + "present";

    private AppProfileRepository repository;
    private SettingsRepository settingsRepository;
    private EditText packageName;
    private Spinner mode;
    private EditText targetLanguage;
    private EditText instructions;
    private CheckBox sendContext;
    private LinearLayout profiles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        repository = new AppProfileRepository(this);
        settingsRepository = new SettingsRepository(this);
        setTitle(R.string.app_profiles_title);
        setContentView(buildView());

        if (savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_HAS_DRAFT, false)) {
            applyDraft(readDraft(savedInstanceState));
        } else {
            String requestedPackage = getIntent().getStringExtra(EXTRA_PACKAGE);
            if (requestedPackage != null && !requestedPackage.isBlank()) {
                AppProfile existing = repository.get(requestedPackage);
                if (existing != null) populate(existing);
                else populateNew(requestedPackage);
            } else {
                populateNew("");
            }
        }
        refreshProfiles();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        AppProfileDraft draft = captureDraft();
        outState.putBoolean(STATE_HAS_DRAFT, true);
        outState.putString(key("package"), draft.packageName());
        outState.putInt(key("mode"), draft.modeIndex());
        outState.putString(key("target"), draft.targetLanguage());
        outState.putString(key("instructions"), draft.customInstructions());
        outState.putBoolean(key("context"), draft.sendContext());
        super.onSaveInstanceState(outState);
    }

    private View buildView() {
        ScrollView scroll = new ScrollView(this);
        SystemBarInsets.apply(scroll);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(32));
        AppVisualSystem.stylePage(this, root);
        scroll.addView(root);

        root.addView(heading(getString(R.string.app_profile_heading), 24));
        root.addView(body(getString(R.string.app_profile_intro)));

        LinearLayout editorCard = AppVisualSystem.card(this);
        packageName = field(getString(R.string.app_profile_package_hint), false);
        editorCard.addView(packageName);

        TextView modeLabel = body(getString(R.string.app_profile_mode_label));
        modeLabel.setLabelFor(View.generateViewId());
        editorCard.addView(modeLabel);
        mode = new Spinner(this);
        mode.setId(modeLabel.getLabelFor());
        List<String> modeLabels = new ArrayList<>();
        for (ProcessingMode value : ProcessingMode.values()) modeLabels.add(modeLabel(value));
        mode.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, modeLabels));
        mode.setMinimumHeight(dp(48));
        mode.setContentDescription(getString(R.string.app_profile_mode_label));
        editorCard.addView(mode, matchWrap());

        targetLanguage = field(getString(R.string.app_profile_target_hint), false);
        editorCard.addView(targetLanguage);
        instructions = field(getString(R.string.app_profile_instructions_hint), true);
        instructions.setMinLines(3);
        editorCard.addView(instructions);
        sendContext = new CheckBox(this);
        sendContext.setText(R.string.app_profile_context);
        sendContext.setMinHeight(dp(48));
        sendContext.setContentDescription(
                getString(R.string.app_profile_context_description));
        editorCard.addView(sendContext, matchWrap());

        LinearLayout actions = row();
        actions.addView(button(getString(R.string.save_app_profile), 1f, ignored -> save()));
        actions.addView(button(getString(R.string.delete_app_profile), 1f, ignored -> confirmDelete()));
        editorCard.addView(actions, matchWrap());
        root.addView(editorCard, AppVisualSystem.cardParams(this));

        root.addView(heading(getString(R.string.saved_app_profiles), 20));
        profiles = new LinearLayout(this);
        profiles.setOrientation(LinearLayout.VERTICAL);
        root.addView(profiles, matchWrap());
        return scroll;
    }

    private void save() {
        try {
            ProcessingMode selected = ProcessingMode.values()[mode.getSelectedItemPosition()];
            repository.save(new AppProfile(
                    packageName.getText().toString(),
                    selected,
                    targetLanguage.getText().toString(),
                    instructions.getText().toString(),
                    sendContext.isChecked()));
            Toast.makeText(this, R.string.app_profile_saved, Toast.LENGTH_SHORT).show();
            refreshProfiles();
        } catch (RuntimeException error) {
            Toast.makeText(this, safeMessage(error), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete() {
        String current = packageName.getText().toString().trim();
        if (current.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_app_profile_title)
                .setMessage(getString(R.string.delete_app_profile_message, current))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    repository.delete(current);
                    populateNew(current);
                    refreshProfiles();
                    Toast.makeText(this, R.string.app_profile_deleted, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void refreshProfiles() {
        if (profiles == null) return;
        profiles.removeAllViews();
        List<AppProfile> values = repository.list();
        if (values.isEmpty()) {
            profiles.addView(body(getString(R.string.no_app_profiles)));
            return;
        }
        for (AppProfile profile : values) {
            LinearLayout card = AppVisualSystem.card(this);
            TextView title = heading(profile.packageName(), 16);
            card.addView(title);
            String summary = modeLabel(profile.mode())
                    + (profile.targetLanguage().isBlank()
                    ? "" : getString(R.string.app_profile_summary_target, profile.targetLanguage()))
                    + getString(profile.sendContext()
                    ? R.string.app_profile_summary_context_allowed
                    : R.string.app_profile_summary_no_context);
            card.addView(body(summary));
            Button edit = button(getString(R.string.edit_app_profile), 1f, ignored -> populate(profile));
            edit.setContentDescription(getString(
                    R.string.edit_app_profile_description, profile.packageName()));
            card.addView(edit, matchWrap());
            profiles.addView(card, AppVisualSystem.cardParams(this));
        }
    }

    private void populate(AppProfile profile) {
        packageName.setText(profile.packageName());
        mode.setSelection(profile.mode().ordinal());
        targetLanguage.setText(profile.targetLanguage());
        instructions.setText(profile.customInstructions());
        sendContext.setChecked(profile.sendContext());
        packageName.requestFocus();
    }

    private void populateNew(String appPackage) {
        packageName.setText(appPackage);
        mode.setSelection(settingsRepository.loadDefaultMode().ordinal());
        targetLanguage.setText(settingsRepository.loadTargetLanguage());
        instructions.setText("");
        sendContext.setChecked(false);
    }

    private AppProfileDraft captureDraft() {
        return new AppProfileDraft(
                packageName.getText().toString(),
                mode.getSelectedItemPosition(),
                targetLanguage.getText().toString(),
                instructions.getText().toString(),
                sendContext.isChecked());
    }

    private void applyDraft(AppProfileDraft draft) {
        packageName.setText(draft.packageName());
        int lastMode = Math.max(0, ProcessingMode.values().length - 1);
        mode.setSelection(Math.max(0, Math.min(draft.modeIndex(), lastMode)));
        targetLanguage.setText(draft.targetLanguage());
        instructions.setText(draft.customInstructions());
        sendContext.setChecked(draft.sendContext());
    }

    private static AppProfileDraft readDraft(Bundle state) {
        return new AppProfileDraft(
                state.getString(key("package"), ""),
                state.getInt(key("mode"), 0),
                state.getString(key("target"), ""),
                state.getString(key("instructions"), ""),
                state.getBoolean(key("context"), false));
    }

    private EditText field(String hint, boolean multiline) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setContentDescription(hint);
        field.setTextSize(16);
        field.setMinHeight(dp(48));
        field.setSingleLine(!multiline);
        field.setInputType(multiline
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : InputType.TYPE_CLASS_TEXT);
        field.setPadding(dp(4), dp(8), dp(4), dp(8));
        return field;
    }

    private TextView heading(String text, int size) {
        TextView view = size >= 24
                ? AppVisualSystem.title(this, text)
                : AppVisualSystem.section(this, text);
        view.setPadding(0, dp(10), 0, dp(6));
        return view;
    }

    private TextView body(String text) {
        return AppVisualSystem.body(this, text);
    }

    private LinearLayout row() {
        return AppVisualSystem.actionGroup(this);
    }

    private Button button(String text, float weight, View.OnClickListener listener) {
        Button button = AppVisualSystem.secondaryButton(this, text, listener);
        button.setLayoutParams(AppVisualSystem.actionParams(this));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? getString(R.string.app_profile_save_failed)
                : message;
    }

    private String modeLabel(ProcessingMode value) {
        return getString(switch (value) {
            case AUTO -> R.string.mode_auto;
            case VERBATIM -> R.string.mode_verbatim;
            case SMART -> R.string.mode_smart;
            case TRANSLATE -> R.string.mode_translate;
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String key(String suffix) {
        return STATE_PREFIX + suffix;
    }
}
