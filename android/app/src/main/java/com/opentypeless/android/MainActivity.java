package com.opentypeless.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.opentypeless.android.net.EndpointNormalizer;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.SettingsRepository;

public final class MainActivity extends Activity {
    private static final int MICROPHONE_PERMISSION_REQUEST = 100;
    private SettingsRepository repository;
    private EditText sttBaseUrl;
    private EditText sttApiKey;
    private EditText sttModel;
    private EditText language;
    private CheckBox polishEnabled;
    private EditText llmBaseUrl;
    private EditText llmApiKey;
    private EditText llmModel;
    private TextView permissionStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        repository = new SettingsRepository(this);
        setContentView(buildContent(repository.load()));
        refreshPermissionStatus();
    }

    private View buildContent(AppSettings settings) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);

        TextView title = text("OpenTypeless Android", 26, true);
        root.addView(title);
        TextView intro = text(
                "A BYOK-only system voice keyboard. Audio and text are sent directly to the endpoints you configure; there is no OpenTypeless account or paid cloud service.",
                15,
                false);
        intro.setTextColor(Color.DKGRAY);
        intro.setPadding(0, dp(8), 0, dp(16));
        root.addView(intro);

        root.addView(section("Speech to text"));
        sttBaseUrl = field(root, "STT base URL", settings.sttBaseUrl(), false);
        sttApiKey = field(root, "STT API key (Android Keystore)", settings.sttApiKey(), true);
        sttModel = field(root, "STT model", settings.sttModel(), false);
        language = field(root, "Language code (optional, e.g. zh or en)", settings.language(), false);

        root.addView(section("AI polish (optional)"));
        polishEnabled = new CheckBox(this);
        polishEnabled.setText(R.string.polish_enabled);
        polishEnabled.setChecked(settings.polishEnabled());
        root.addView(polishEnabled);
        llmBaseUrl = field(root, "LLM base URL", settings.llmBaseUrl(), false);
        llmApiKey = field(root, "LLM API key (Android Keystore)", settings.llmApiKey(), true);
        llmModel = field(root, "LLM model", settings.llmModel(), false);

        TextView localHttp = text(
                "Security note: HTTPS is strongly recommended. HTTP is allowed for explicit localhost/LAN self-hosted endpoints and can expose audio or text on an untrusted network.",
                13,
                false);
        localHttp.setTextColor(Color.rgb(145, 88, 0));
        localHttp.setPadding(0, dp(8), 0, dp(12));
        root.addView(localHttp);

        Button save = button("Save configuration", ignored -> saveSettings());
        root.addView(save);

        root.addView(section("Enable the keyboard"));
        permissionStatus = text("", 14, false);
        root.addView(permissionStatus);
        root.addView(button("1. Grant microphone permission", ignored -> requestMicrophone()));
        root.addView(button("2. Enable OpenTypeless keyboard", ignored ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        root.addView(button("3. Choose OpenTypeless keyboard", ignored -> {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            manager.showInputMethodPicker();
        }));
        return scroll;
    }

    private void saveSettings() {
        try {
            EndpointNormalizer.endpoint(sttBaseUrl.getText().toString(), "audio/transcriptions");
            if (sttModel.getText().toString().trim().isEmpty()) {
                throw new IllegalArgumentException("STT model is required");
            }
            if (polishEnabled.isChecked()) {
                EndpointNormalizer.endpoint(llmBaseUrl.getText().toString(), "chat/completions");
                if (llmModel.getText().toString().trim().isEmpty()) {
                    throw new IllegalArgumentException("LLM model is required when polish is enabled");
                }
            }
            repository.save(new AppSettings(
                    sttBaseUrl.getText().toString(),
                    sttApiKey.getText().toString(),
                    sttModel.getText().toString(),
                    language.getText().toString(),
                    polishEnabled.isChecked(),
                    llmBaseUrl.getText().toString(),
                    llmApiKey.getText().toString(),
                    llmModel.getText().toString()));
            Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is already granted", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MICROPHONE_PERMISSION_REQUEST) refreshPermissionStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (permissionStatus != null) refreshPermissionStatus();
    }

    private void refreshPermissionStatus() {
        boolean granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(granted
                ? "✓ Microphone permission granted"
                : "Microphone permission is required before recording from the keyboard");
        permissionStatus.setTextColor(granted ? Color.rgb(0, 110, 82) : Color.rgb(170, 40, 40));
    }

    private EditText field(LinearLayout root, String hint, String value, boolean secret) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value == null ? "" : value);
        field.setSingleLine(true);
        field.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(field);
        return field;
    }

    private TextView section(String value) {
        TextView view = text(value, 19, true);
        view.setPadding(0, dp(18), 0, dp(4));
        return view;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
