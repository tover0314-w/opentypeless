package com.opentypeless.android;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.opentypeless.android.data.PersonalizationStore;
import com.opentypeless.android.diagnostics.RecognitionDiagnostics;
import com.opentypeless.android.diagnostics.RecognitionDiagnosticsStore;
import com.opentypeless.android.diagnostics.RecognitionRoute;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.SettingsRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Progressive-disclosure settings hub; detailed provider fields remain in MainActivity. */
public final class SettingsHomeActivity extends android.app.Activity {
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private int loadGeneration;
    private TextView routeValue;
    private TextView privacyValue;
    private TextView routeStatus;
    private LinearLayout settingsRows;

    private record Snapshot(
            AppSettings settings,
            RecognitionDiagnostics.Snapshot diagnostics,
            int terms,
            int corrections) {}

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

        root.addView(AppVisualSystem.title(this, getString(R.string.settings_title_short)));
        TextView intro = AppVisualSystem.body(this, getString(R.string.settings_home_intro));
        intro.setPadding(0, dp(8), 0, dp(18));
        root.addView(intro);

        LinearLayout hero = AppVisualSystem.heroCard(this);
        hero.setId(R.id.settings_home_route_card);
        hero.addView(AppVisualSystem.eyebrow(this, getString(R.string.settings_current_route)));
        routeValue = AppVisualSystem.heroValue(this, getString(R.string.settings_route_loading));
        hero.addView(routeValue);
        privacyValue = AppVisualSystem.success(this, getString(R.string.settings_route_loading));
        hero.addView(privacyValue);
        routeStatus = AppVisualSystem.note(this, getString(R.string.settings_route_loading));
        routeStatus.setMinHeight(dp(40));
        hero.addView(routeStatus);
        hero.addView(AppVisualSystem.accentButton(
                this,
                R.string.open_voice_lab,
                ignored -> startActivity(new Intent(this, VoiceLabActivity.class))),
                matchWrapWithTop(10));
        root.addView(hero, AppVisualSystem.cardParams(this));

        settingsRows = AppVisualSystem.card(this);
        settingsRows.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(settingsRows, AppVisualSystem.cardParams(this));
        renderLoadingRows();

        page.addView(
                AppVisualSystem.bottomNavigation(this, AppVisualSystem.Destination.SETTINGS),
                AppVisualSystem.matchWrap());
        return page;
    }

    private void refresh() {
        int generation = ++loadGeneration;
        loader.execute(() -> {
            Snapshot snapshot = null;
            PersonalizationStore store = null;
            try {
                AppSettings settings = new SettingsRepository(getApplicationContext()).load();
                RecognitionDiagnostics.Snapshot diagnostics =
                        new RecognitionDiagnosticsStore(getApplicationContext()).load();
                store = new PersonalizationStore(getApplicationContext());
                int terms = store.countTerms();
                int corrections = store.countCorrections();
                snapshot = new Snapshot(settings, diagnostics, terms, corrections);
            } catch (RuntimeException ignored) {
                // The hub reveals neither storage nor credential exception details.
            } finally {
                if (store != null) store.close();
            }
            Snapshot result = snapshot;
            runOnUiThread(() -> {
                if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
                render(result);
            });
        });
    }

    private void render(Snapshot snapshot) {
        settingsRows.removeAllViews();
        if (snapshot == null) {
            routeValue.setText(R.string.settings_route_unavailable);
            privacyValue.setText(R.string.settings_route_unavailable_detail);
            privacyValue.setTextColor(getColor(R.color.ime_error));
            routeStatus.setText(R.string.settings_route_open_details);
            addSettingsRow(
                    R.string.settings_voice_models,
                    R.string.settings_route_open_details,
                    MainActivity.class,
                    R.id.settings_home_voice_row);
            return;
        }

        AppSettings settings = snapshot.settings();
        RecognitionRoute route = AppVisualSystem.routeForSummary(
                settings.recognitionBackend(), snapshot.diagnostics());
        routeValue.setText(AppVisualSystem.backendLabel(this, route.actualBackend()));
        privacyValue.setText(AppVisualSystem.privacyLabel(this, route.privacyBoundary()));
        privacyValue.setTextColor(getColor(route.privacyBoundary()
                == RecognitionRoute.PrivacyBoundary.ON_DEVICE
                ? R.color.ime_success
                : R.color.ime_warning));
        boolean checked = SetupChecklist.successfulTestMatches(
                settings.recognitionBackend(), settings.language(), snapshot.diagnostics());
        routeStatus.setText(checked
                ? R.string.settings_voice_check_passed
                : R.string.settings_voice_check_needed);

        String routeSummary = getString(
                R.string.settings_voice_models_summary,
                AppVisualSystem.backendLabel(this, settings.recognitionBackend()),
                AppVisualSystem.modeLabel(this, settings.defaultMode()));
        addSettingsRow(
                getString(R.string.settings_voice_models),
                routeSummary,
                MainActivity.class,
                R.id.settings_home_voice_row);
        addDivider();
        addSettingsRow(
                getString(R.string.settings_input_experience),
                getString(
                        R.string.settings_input_experience_summary,
                        getResources().getQuantityString(
                                R.plurals.settings_recording_limit_seconds,
                                settings.boundedMaxRecordingSeconds(),
                                settings.boundedMaxRecordingSeconds())),
                MainActivity.class,
                View.NO_ID);
        addDivider();
        addSettingsRow(
                getString(R.string.keyboard_feedback_title),
                getString(R.string.keyboard_feedback_settings_summary),
                KeyboardFeedbackActivity.class,
                R.id.settings_home_feedback_row);
        addDivider();
        addSettingsRow(
                getString(R.string.settings_personalization_data),
                getString(
                        R.string.settings_personalization_summary,
                        getResources().getQuantityString(
                                R.plurals.settings_terms_count,
                                snapshot.terms(),
                                snapshot.terms()),
                        getResources().getQuantityString(
                                R.plurals.settings_corrections_count,
                                snapshot.corrections(),
                                snapshot.corrections()),
                        getString(settings.historyEnabled()
                                ? R.string.settings_enabled
                                : R.string.settings_disabled)),
                DictionaryActivity.class,
                View.NO_ID);
        addDivider();
        addSettingsRow(
                getString(R.string.rime_resources_title),
                getString(R.string.rime_resources_settings_summary),
                RimeResourceActivity.class,
                R.id.settings_home_rime_row);
        addDivider();
        LinearLayout privacy = addSettingsRow(
                getString(R.string.settings_privacy_security),
                getString(
                        R.string.settings_privacy_summary,
                        AppVisualSystem.privacyLabel(this, route.privacyBoundary()),
                        getString(settings.sendContext()
                                ? R.string.settings_context_on
                                : R.string.settings_context_off)),
                MainActivity.class,
                R.id.settings_home_privacy_row);
        privacy.setId(R.id.settings_home_privacy_row);
        addDivider();
        addSettingsRow(
                getString(R.string.settings_advanced_connections),
                getString(R.string.settings_advanced_connections_summary),
                MainActivity.class,
                View.NO_ID);
    }

    private void renderLoadingRows() {
        settingsRows.removeAllViews();
        addSettingsRow(
                getString(R.string.settings_voice_models),
                getString(R.string.settings_route_loading),
                MainActivity.class,
                R.id.settings_home_voice_row);
    }

    private LinearLayout addSettingsRow(
            int labelResource,
            int summaryResource,
            Class<? extends android.app.Activity> target,
            int id) {
        return addSettingsRow(
                getString(labelResource),
                getString(summaryResource),
                target,
                id);
    }

    private LinearLayout addSettingsRow(
            CharSequence label,
            CharSequence summary,
            Class<? extends android.app.Activity> target,
            int id) {
        LinearLayout row = AppVisualSystem.navigationRow(
                this,
                label,
                summary,
                ignored -> startActivity(new Intent(this, target)));
        if (id != View.NO_ID) row.setId(id);
        settingsRows.addView(row);
        return row;
    }

    private void addDivider() {
        settingsRows.addView(AppVisualSystem.divider(this));
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
