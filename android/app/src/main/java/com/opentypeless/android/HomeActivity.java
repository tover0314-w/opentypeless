package com.opentypeless.android;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.opentypeless.android.diagnostics.RecognitionDiagnostics;
import com.opentypeless.android.diagnostics.RecognitionDiagnosticsStore;
import com.opentypeless.android.diagnostics.RecognitionRoute;
import com.opentypeless.android.ime.OpenTypelessImeService;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.SettingsRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Calm, status-first launcher for the keyboard and its companion tools. */
public final class HomeActivity extends android.app.Activity {
    private static final int MICROPHONE_PERMISSION_REQUEST = 41;

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private int loadGeneration;
    private TextView routeValue;
    private TextView privacyValue;
    private TextView readinessValue;
    private LinearLayout setupGroup;
    private Button primaryAction;
    private AppSettings settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        loadGeneration++;
        loader.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MICROPHONE_PERMISSION_REQUEST) refresh();
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
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        root.addView(AppVisualSystem.title(this, getString(R.string.home_title)));
        TextView intro = AppVisualSystem.body(this, getString(R.string.home_intro));
        intro.setPadding(0, dp(8), 0, dp(18));
        root.addView(intro);

        LinearLayout hero = AppVisualSystem.heroCard(this);
        hero.addView(AppVisualSystem.eyebrow(this, getString(R.string.settings_current_route)));
        routeValue = AppVisualSystem.heroValue(this, getString(R.string.settings_route_loading));
        routeValue.setId(R.id.home_route_value);
        hero.addView(routeValue);
        privacyValue = AppVisualSystem.success(this, getString(R.string.settings_route_loading));
        hero.addView(privacyValue);
        readinessValue = AppVisualSystem.note(this, getString(R.string.settings_route_loading));
        readinessValue.setMinHeight(dp(40));
        hero.addView(readinessValue);
        primaryAction = AppVisualSystem.accentButton(
                this,
                R.string.open_voice_lab,
                ignored -> startActivity(new Intent(this, VoiceLabActivity.class)));
        hero.addView(primaryAction, matchWrapWithTop(10));
        root.addView(hero, AppVisualSystem.cardParams(this));

        TextView setupHeading = AppVisualSystem.eyebrow(this, getString(R.string.home_setup_heading));
        setupHeading.setPadding(dp(4), dp(10), 0, dp(8));
        root.addView(setupHeading);
        setupGroup = AppVisualSystem.card(this);
        setupGroup.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(setupGroup, AppVisualSystem.cardParams(this));

        TextView toolsHeading = AppVisualSystem.eyebrow(this, getString(R.string.home_tools_heading));
        toolsHeading.setPadding(dp(4), dp(10), 0, dp(8));
        root.addView(toolsHeading);
        LinearLayout tools = AppVisualSystem.card(this);
        tools.setPadding(dp(4), dp(4), dp(4), dp(4));
        tools.addView(AppVisualSystem.navigationRow(
                this,
                getString(R.string.settings_title_short),
                getString(R.string.home_settings_summary),
                ignored -> startActivity(new Intent(this, SettingsHomeActivity.class))));
        tools.addView(AppVisualSystem.divider(this));
        tools.addView(AppVisualSystem.navigationRow(
                this,
                getString(R.string.section_voice_lab),
                getString(R.string.home_voice_lab_summary),
                ignored -> startActivity(new Intent(this, VoiceLabActivity.class))));
        root.addView(tools, AppVisualSystem.cardParams(this));

        page.addView(
                AppVisualSystem.bottomNavigation(this, AppVisualSystem.Destination.HOME),
                AppVisualSystem.matchWrap());
        return page;
    }

    private void refresh() {
        int generation = ++loadGeneration;
        loader.execute(() -> {
            AppSettings loaded;
            RecognitionDiagnostics.Snapshot latest;
            try {
                loaded = new SettingsRepository(getApplicationContext()).load();
                latest = new RecognitionDiagnosticsStore(getApplicationContext()).load();
            } catch (RuntimeException error) {
                loaded = null;
                latest = null;
            }
            AppSettings result = loaded;
            RecognitionDiagnostics.Snapshot diagnostics = latest;
            runOnUiThread(() -> {
                if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
                render(result, diagnostics);
            });
        });
    }

    private void render(
            AppSettings loaded,
            RecognitionDiagnostics.Snapshot diagnostics) {
        settings = loaded;
        if (loaded == null) {
            routeValue.setText(R.string.settings_route_unavailable);
            privacyValue.setText(R.string.settings_route_unavailable_detail);
            privacyValue.setTextColor(getColor(R.color.ime_error));
            readinessValue.setText(R.string.settings_route_open_details);
        } else {
            RecognitionRoute route = AppVisualSystem.routeForSummary(
                    loaded.recognitionBackend(), diagnostics);
            routeValue.setText(AppVisualSystem.backendLabel(this, route.actualBackend()));
            privacyValue.setText(AppVisualSystem.privacyLabel(this, route.privacyBoundary()));
            privacyValue.setTextColor(getColor(route.privacyBoundary()
                    == RecognitionRoute.PrivacyBoundary.ON_DEVICE
                    ? R.color.ime_success
                    : R.color.ime_warning));
            readinessValue.setText(SetupChecklist.successfulTestMatches(
                    loaded.recognitionBackend(), loaded.language(), diagnostics)
                    ? R.string.home_voice_check_passed
                    : R.string.home_voice_check_needed);
        }
        renderSetup(diagnostics);
    }

    private void renderSetup(RecognitionDiagnostics.Snapshot diagnostics) {
        setupGroup.removeAllViews();
        boolean microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        boolean enabled = false;
        for (InputMethodInfo info : manager.getEnabledInputMethodList()) {
            if (info.getServiceInfo().packageName.equals(getPackageName())
                    && info.getServiceInfo().name.equals(OpenTypelessImeService.class.getName())) {
                enabled = true;
                break;
            }
        }
        final boolean keyboardEnabled = enabled;
        String selectedValue = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName selected = selectedValue == null
                ? null
                : ComponentName.unflattenFromString(selectedValue);
        boolean selectedHere = selected != null
                && selected.getPackageName().equals(getPackageName())
                && selected.getClassName().equals(OpenTypelessImeService.class.getName());
        boolean routeReady = settings != null && settings.isReady();
        boolean voiceChecked = settings != null && SetupChecklist.successfulTestMatches(
                settings.recognitionBackend(), settings.language(), diagnostics);

        setupGroup.addView(AppVisualSystem.navigationRow(
                this,
                getString(R.string.home_microphone),
                getString(microphone
                        ? R.string.home_status_ready
                        : R.string.home_status_action_required),
                microphone ? null : ignored -> {
                    if (!microphone) requestPermissions(
                            new String[]{Manifest.permission.RECORD_AUDIO},
                            MICROPHONE_PERMISSION_REQUEST);
                }));
        setupGroup.addView(AppVisualSystem.divider(this));
        setupGroup.addView(AppVisualSystem.navigationRow(
                this,
                getString(R.string.home_keyboard),
                getString(selectedHere
                        ? R.string.home_status_selected
                        : keyboardEnabled
                        ? R.string.home_status_choose_keyboard
                        : R.string.home_status_enable_keyboard),
                selectedHere ? null : ignored -> {
                    if (!keyboardEnabled) {
                        startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
                    } else if (!selectedHere) {
                        manager.showInputMethodPicker();
                    }
                }));
        setupGroup.addView(AppVisualSystem.divider(this));
        setupGroup.addView(AppVisualSystem.navigationRow(
                this,
                getString(R.string.home_recognition_route),
                getString(routeReady
                        ? R.string.home_status_ready
                        : R.string.home_status_action_required),
                ignored -> startActivity(new Intent(this, MainActivity.class))));
        setupGroup.addView(AppVisualSystem.divider(this));
        setupGroup.addView(AppVisualSystem.navigationRow(
                this,
                getString(R.string.home_voice_check),
                getString(voiceChecked
                        ? R.string.home_status_passed
                        : R.string.home_status_not_checked),
                ignored -> startActivity(new Intent(this, VoiceLabActivity.class))));

        if (!microphone) {
            primaryAction.setText(R.string.home_action_grant_microphone);
            primaryAction.setOnClickListener(ignored -> requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    MICROPHONE_PERMISSION_REQUEST));
        } else if (!keyboardEnabled) {
            primaryAction.setText(R.string.home_action_enable_keyboard);
            primaryAction.setOnClickListener(ignored ->
                    startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        } else if (!selectedHere) {
            primaryAction.setText(R.string.home_action_choose_keyboard);
            primaryAction.setOnClickListener(ignored -> manager.showInputMethodPicker());
        } else if (!routeReady) {
            primaryAction.setText(R.string.settings_open_voice_models);
            primaryAction.setOnClickListener(ignored ->
                    startActivity(new Intent(this, MainActivity.class)));
        } else {
            primaryAction.setText(R.string.open_voice_lab);
            primaryAction.setOnClickListener(ignored ->
                    startActivity(new Intent(this, VoiceLabActivity.class)));
        }
        primaryAction.setContentDescription(primaryAction.getText());
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int top) {
        LinearLayout.LayoutParams parameters = AppVisualSystem.matchWrap();
        parameters.topMargin = dp(top);
        return parameters;
    }

    private int dp(int value) {
        return AppVisualSystem.dp(this, value);
    }
}
