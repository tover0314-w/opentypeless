package com.opentypeless.android;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import com.opentypeless.android.keyboard.feedback.AndroidKeyboardFeedback;
import com.opentypeless.android.keyboard.feedback.KeyboardFeedbackPreferences;

/** Immediate, text-free settings for KBD-005 keyboard feedback. */
public final class KeyboardFeedbackActivity extends android.app.Activity {
    private KeyboardFeedbackPreferences preferences;
    private AndroidKeyboardFeedback feedback;
    private RadioGroup hapticMode;
    private Spinner hapticStrength;
    private Switch soundEnabled;
    private SeekBar soundVolume;
    private TextView soundVolumeValue;
    private Button preview;
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new KeyboardFeedbackPreferences(this);
        feedback = new AndroidKeyboardFeedback(this);
        setContentView(buildContent());
        bind(preferences.load());
    }

    private View buildContent() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        AppVisualSystem.stylePage(this, page);
        SystemBarInsets.apply(page);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(24));
        AppVisualSystem.stylePage(this, root);
        scroll.addView(root);
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(AppVisualSystem.backHeader(this, getString(R.string.keyboard_feedback_title)));
        TextView intro = AppVisualSystem.body(this, getString(R.string.keyboard_feedback_intro));
        intro.setPadding(0, dp(8), 0, dp(14));
        root.addView(intro);

        LinearLayout hapticCard = AppVisualSystem.card(this);
        hapticCard.addView(AppVisualSystem.eyebrow(this, getString(R.string.keyboard_haptic_title)));
        hapticCard.addView(AppVisualSystem.note(this, getString(R.string.keyboard_haptic_summary)));
        hapticMode = new RadioGroup(this);
        hapticMode.setOrientation(RadioGroup.VERTICAL);
        addHapticChoice(R.id.keyboard_haptic_system, R.string.keyboard_haptic_system);
        addHapticChoice(R.id.keyboard_haptic_on, R.string.keyboard_haptic_on);
        addHapticChoice(R.id.keyboard_haptic_off, R.string.keyboard_haptic_off);
        hapticMode.setOnCheckedChangeListener((group, checkedId) -> persist());
        hapticCard.addView(hapticMode, topMargin(8));
        hapticCard.addView(AppVisualSystem.note(
                this, getString(R.string.keyboard_haptic_strength_title)), topMargin(8));

        hapticStrength = new Spinner(this);
        hapticStrength.setId(R.id.keyboard_haptic_strength);
        hapticStrength.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[] {
                    getString(R.string.keyboard_haptic_light),
                    getString(R.string.keyboard_haptic_medium),
                    getString(R.string.keyboard_haptic_strong)
                }));
        hapticStrength.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                persist();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        hapticCard.addView(hapticStrength, topMargin(4));
        root.addView(hapticCard, AppVisualSystem.cardParams(this));

        LinearLayout soundCard = AppVisualSystem.card(this);
        soundCard.addView(AppVisualSystem.eyebrow(this, getString(R.string.keyboard_sound_title)));
        soundEnabled = new Switch(this);
        soundEnabled.setId(R.id.keyboard_sound_enabled);
        soundEnabled.setText(R.string.keyboard_sound_enabled);
        soundEnabled.setMinHeight(dp(48));
        soundEnabled.setOnCheckedChangeListener((button, checked) -> {
            soundVolume.setEnabled(checked);
            persist();
        });
        soundCard.addView(soundEnabled, topMargin(6));
        soundVolumeValue = AppVisualSystem.note(this, "");
        soundCard.addView(soundVolumeValue, topMargin(4));
        soundVolume = new SeekBar(this);
        soundVolume.setId(R.id.keyboard_sound_volume);
        soundVolume.setMax(100);
        soundVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                soundVolumeValue.setText(getString(R.string.keyboard_sound_volume_value, progress));
                persist();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        soundCard.addView(soundVolume, topMargin(4));
        preview = AppVisualSystem.accentButton(
                this, R.string.keyboard_feedback_preview, ignored -> feedback.onPress(preview));
        preview.setId(R.id.keyboard_feedback_preview);
        soundCard.addView(preview, topMargin(10));
        root.addView(soundCard, AppVisualSystem.cardParams(this));

        page.addView(
                AppVisualSystem.bottomNavigation(this, AppVisualSystem.Destination.SETTINGS),
                AppVisualSystem.matchWrap());
        return page;
    }

    private void addHapticChoice(int id, int label) {
        RadioButton button = new RadioButton(this);
        button.setId(id);
        button.setText(label);
        button.setMinHeight(dp(48));
        hapticMode.addView(button);
    }

    private void bind(KeyboardFeedbackPreferences.Config config) {
        binding = true;
        hapticMode.check(switch (config.hapticMode()) {
            case FOLLOW_SYSTEM -> R.id.keyboard_haptic_system;
            case ENABLED -> R.id.keyboard_haptic_on;
            case DISABLED -> R.id.keyboard_haptic_off;
        });
        hapticStrength.setSelection(config.hapticStrength().ordinal());
        soundEnabled.setChecked(config.soundEnabled());
        soundVolume.setProgress(config.soundVolumePercent());
        soundVolume.setEnabled(config.soundEnabled());
        soundVolumeValue.setText(getString(
                R.string.keyboard_sound_volume_value, config.soundVolumePercent()));
        binding = false;
    }

    private void persist() {
        if (binding || hapticMode == null || hapticStrength == null
                || soundEnabled == null || soundVolume == null) return;
        int checkedId = hapticMode.getCheckedRadioButtonId();
        KeyboardFeedbackPreferences.HapticMode mode;
        if (checkedId == R.id.keyboard_haptic_on) {
            mode = KeyboardFeedbackPreferences.HapticMode.ENABLED;
        } else if (checkedId == R.id.keyboard_haptic_off) {
            mode = KeyboardFeedbackPreferences.HapticMode.DISABLED;
        } else {
            mode = KeyboardFeedbackPreferences.HapticMode.FOLLOW_SYSTEM;
        }
        int strengthIndex = Math.max(0, hapticStrength.getSelectedItemPosition());
        KeyboardFeedbackPreferences.HapticStrength strength =
                KeyboardFeedbackPreferences.HapticStrength.values()[strengthIndex];
        preferences.save(new KeyboardFeedbackPreferences.Config(
                KeyboardFeedbackPreferences.Config.CURRENT_VERSION,
                mode,
                strength,
                soundEnabled.isChecked(),
                soundVolume.getProgress()));
    }

    private LinearLayout.LayoutParams topMargin(int marginDp) {
        LinearLayout.LayoutParams params = AppVisualSystem.matchWrap();
        params.topMargin = dp(marginDp);
        return params;
    }

    private int dp(int value) {
        return AppVisualSystem.dp(this, value);
    }
}
