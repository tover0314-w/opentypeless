package com.opentypeless.android.ime;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.opentypeless.android.MainActivity;
import com.opentypeless.android.R;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.SettingsRepository;

public final class OpenTypelessImeService extends InputMethodService {
    private VoicePipeline pipeline;
    private SettingsRepository settingsRepository;
    private TextView status;
    private Button microphone;
    private String lastCommitted = "";
    private boolean sensitiveField;

    @Override
    public void onCreate() {
        super.onCreate();
        pipeline = new VoicePipeline();
        settingsRepository = new SettingsRepository(this);
    }

    @Override
    public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackgroundColor(Color.rgb(245, 247, 246));

        status = new TextView(this);
        status.setText(R.string.status_ready);
        status.setTextColor(Color.rgb(35, 50, 47));
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(4), dp(3), dp(4), dp(8));
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        microphone = key(getString(R.string.action_speak), 2f, ignored -> toggleRecording());
        actions.addView(microphone);
        actions.addView(key("⌫", 1f, ignored -> backspace()));
        actions.addView(key("Space", 1.4f, ignored -> commit(" ")));
        actions.addView(key("↵", 1f, ignored -> sendEnter()));
        actions.addView(key("⌨", 1f, ignored -> switchKeyboard()));
        root.addView(actions);

        LinearLayout secondary = new LinearLayout(this);
        secondary.setOrientation(LinearLayout.HORIZONTAL);
        secondary.addView(key("Undo voice", 1f, ignored -> undoLastVoiceCommit()));
        secondary.addView(key("Settings", 1f, ignored -> openSettings()));
        secondary.addView(key("Cancel", 1f, ignored -> cancelPipeline()));
        root.addView(secondary);
        return root;
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        // Keep the voice controls visible on devices with an attached hardware keyboard too.
        super.onEvaluateInputViewShown();
        return true;
    }

    private void toggleRecording() {
        if (sensitiveField) {
            setStatus("Voice input is disabled for password fields", true);
            return;
        }
        if (pipeline.state() == VoicePipeline.State.RECORDING) {
            pipeline.stopRecording();
            setStatus("Finishing recording…", false);
            return;
        }
        if (pipeline.state() != VoicePipeline.State.IDLE) {
            setStatus("Please wait, or tap Cancel", true);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Grant microphone permission in OpenTypeless settings", true);
            openSettings();
            return;
        }
        AppSettings settings = settingsRepository.load();
        if (!settings.isReady()) {
            setStatus("Configure a speech endpoint first", true);
            openSettings();
            return;
        }
        pipeline.start(settings, new VoicePipeline.Listener() {
            @Override
            public void onState(VoicePipeline.State state, String message) {
                postUi(() -> {
                    setStatus(message, false);
                    microphone.setText(state == VoicePipeline.State.RECORDING
                            ? R.string.action_stop
                            : R.string.action_speak);
                });
            }

            @Override
            public void onResult(String text, String message) {
                postUi(() -> {
                    InputConnection connection = getCurrentInputConnection();
                    if (connection == null) {
                        setStatus("No active text field", true);
                        return;
                    }
                    lastCommitted = text;
                    connection.commitText(text, 1);
                    microphone.setText(R.string.action_speak);
                    setStatus(message, !"Inserted".equals(message));
                });
            }

            @Override
            public void onError(String message) {
                postUi(() -> {
                    microphone.setText(R.string.action_speak);
                    setStatus(message, true);
                });
            }
        });
    }

    private void backspace() {
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) connection.deleteSurroundingText(1, 0);
    }

    private void commit(String text) {
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) connection.commitText(text, 1);
    }

    private void sendEnter() {
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
    }

    private void undoLastVoiceCommit() {
        if (lastCommitted.isEmpty()) {
            setStatus("Nothing to undo", false);
            return;
        }
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) {
            CharSequence before = connection.getTextBeforeCursor(lastCommitted.length(), 0);
            if (lastCommitted.contentEquals(before)) {
                connection.deleteSurroundingText(lastCommitted.length(), 0);
                lastCommitted = "";
                setStatus("Voice insertion removed", false);
            } else {
                setStatus("Text changed; undo was not applied", true);
            }
        }
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

    private void cancelPipeline() {
        pipeline.cancel();
        if (microphone != null) microphone.setText(R.string.action_speak);
        setStatus("Cancelled", false);
    }

    private Button key(String label, float weight, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinWidth(0);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, dp(52), weight));
        return button;
    }

    private void setStatus(String message, boolean error) {
        if (status == null) return;
        status.setText(message);
        status.setTextColor(error ? Color.rgb(170, 40, 40) : Color.rgb(35, 70, 63));
    }

    private void postUi(Runnable runnable) {
        if (status != null) status.post(runnable);
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        int inputClass = attribute == null ? InputType.TYPE_NULL
                : attribute.inputType & InputType.TYPE_MASK_CLASS;
        int variation = attribute == null ? 0
                : attribute.inputType & InputType.TYPE_MASK_VARIATION;
        sensitiveField = (inputClass == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || (inputClass == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        lastCommitted = "";
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        cancelPipeline();
    }

    @Override
    public void onDestroy() {
        pipeline.shutdown();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
