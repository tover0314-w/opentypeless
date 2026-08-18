package com.opentypeless.android;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.opentypeless.android.rime.importer.RimeImportException;
import com.opentypeless.android.rime.importer.RimeResourceManifest;
import com.opentypeless.android.rime.importer.RimeResourceStore;
import com.opentypeless.android.rime.importer.RimeRuntimePreferences;
import com.opentypeless.android.keyboard.rime.RimeRuntimeConfig;
import com.opentypeless.android.rime.userdata.RimeUserDataException;
import com.opentypeless.android.rime.userdata.RimeUserDataStore;

import java.io.InputStream;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit SAF-only UI for previewing and atomically installing local Rime resource packages. */
public final class RimeResourceActivity extends android.app.Activity {
    private static final int OPEN_PACKAGE_REQUEST = 303;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "opentypeless-rime-import");
        thread.setDaemon(true);
        return thread;
    });
    private int generation;
    private RimeResourceStore store;
    private RimeRuntimePreferences runtimePreferences;
    private RimeUserDataStore userDataStore;
    private RimeResourceStore.StagedImport pending;
    private TextView status;
    private TextView userDataStatus;
    private Button importButton;
    private Button clearButton;
    private Button restoreUserData;
    private Button clearUserData;
    private LinearLayout configurationCard;
    private RadioGroup schemaChoices;
    private CheckBox simplifiedOutput;
    private CheckBox asciiPunctuation;
    private CheckBox fullShape;
    private Button saveConfiguration;
    private List<String> installedSchemas = List.of();
    private boolean resourcesInstalled;
    private boolean userDataAvailable;
    private boolean userDataCheckpoint;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new RimeResourceStore(getApplicationContext());
        runtimePreferences = new RimeRuntimePreferences(getApplicationContext());
        userDataStore = new RimeUserDataStore(getApplicationContext());
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (shouldRefreshOnResume(busy)) refreshStatus();
    }

    @Override
    protected void onDestroy() {
        generation++;
        closePending();
        worker.shutdownNow();
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

        root.addView(AppVisualSystem.backHeader(this, getString(R.string.rime_resources_title)));
        TextView explanation = AppVisualSystem.body(
                this,
                getString(R.string.rime_resources_explanation));
        explanation.setPadding(0, dp(8), 0, dp(14));
        root.addView(explanation);

        LinearLayout statusCard = AppVisualSystem.card(this);
        statusCard.addView(AppVisualSystem.eyebrow(
                this,
                getString(R.string.rime_resources_current)));
        status = AppVisualSystem.note(this, getString(R.string.rime_resources_loading));
        status.setId(R.id.rime_resource_status);
        status.setMinHeight(dp(56));
        statusCard.addView(status);
        root.addView(statusCard, AppVisualSystem.cardParams(this));

        configurationCard = AppVisualSystem.card(this);
        configurationCard.addView(AppVisualSystem.eyebrow(
                this,
                getString(R.string.rime_configuration_title)));
        configurationCard.addView(AppVisualSystem.note(
                this,
                getString(R.string.rime_configuration_explanation)));
        schemaChoices = new RadioGroup(this);
        schemaChoices.setOrientation(RadioGroup.VERTICAL);
        configurationCard.addView(schemaChoices, topMargin(8));
        simplifiedOutput = optionCheckBox(R.string.rime_configuration_simplified);
        asciiPunctuation = optionCheckBox(R.string.rime_configuration_ascii_punctuation);
        fullShape = optionCheckBox(R.string.rime_configuration_full_shape);
        fullShape.setOnCheckedChangeListener((button, checked) -> {
            if (checked) asciiPunctuation.setChecked(false);
        });
        asciiPunctuation.setOnCheckedChangeListener((button, checked) -> {
            if (checked) fullShape.setChecked(false);
        });
        configurationCard.addView(simplifiedOutput, topMargin(8));
        configurationCard.addView(asciiPunctuation, topMargin(4));
        configurationCard.addView(fullShape, topMargin(4));
        saveConfiguration = AppVisualSystem.accentButton(
                this,
                R.string.rime_configuration_save,
                ignored -> saveConfiguration());
        configurationCard.addView(saveConfiguration, topMargin(12));
        root.addView(configurationCard, AppVisualSystem.cardParams(this));
        configurationCard.setVisibility(View.GONE);

        LinearLayout userDataCard = AppVisualSystem.card(this);
        userDataCard.addView(AppVisualSystem.eyebrow(
                this,
                getString(R.string.rime_userdata_title)));
        userDataCard.addView(AppVisualSystem.note(
                this,
                getString(R.string.rime_userdata_explanation)));
        userDataStatus = AppVisualSystem.note(this, getString(R.string.rime_userdata_loading));
        userDataStatus.setMinHeight(dp(48));
        userDataCard.addView(userDataStatus, topMargin(8));
        restoreUserData = AppVisualSystem.secondaryButton(
                this,
                R.string.rime_userdata_restore,
                ignored -> confirmRestoreUserData());
        userDataCard.addView(restoreUserData, topMargin(8));
        clearUserData = AppVisualSystem.secondaryButton(
                this,
                R.string.rime_userdata_clear,
                ignored -> confirmClearUserData());
        userDataCard.addView(clearUserData, topMargin(8));
        root.addView(userDataCard, AppVisualSystem.cardParams(this));

        LinearLayout actionCard = AppVisualSystem.card(this);
        actionCard.addView(AppVisualSystem.eyebrow(
                this,
                getString(R.string.rime_resources_local_import)));
        actionCard.addView(AppVisualSystem.note(
                this,
                getString(R.string.rime_resources_local_only_warning)));
        importButton = AppVisualSystem.accentButton(
                this,
                R.string.rime_resources_choose_file,
                ignored -> choosePackage());
        importButton.setId(R.id.rime_resource_import);
        actionCard.addView(importButton, topMargin(12));
        clearButton = AppVisualSystem.secondaryButton(
                this,
                R.string.rime_resources_clear,
                ignored -> confirmClear());
        clearButton.setId(R.id.rime_resource_clear);
        actionCard.addView(clearButton, topMargin(8));
        root.addView(actionCard, AppVisualSystem.cardParams(this));

        page.addView(
                AppVisualSystem.bottomNavigation(this, AppVisualSystem.Destination.SETTINGS),
                AppVisualSystem.matchWrap());
        return page;
    }

    private void choosePackage() {
        closePending();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/octet-stream"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, OPEN_PACKAGE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != OPEN_PACKAGE_REQUEST || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        stageSelected(data.getData());
    }

    private void stageSelected(Uri uri) {
        int requestGeneration = ++generation;
        setBusy(true);
        status.setText(R.string.rime_resources_validating);
        worker.execute(() -> {
            RimeResourceStore.StagedImport result = null;
            RimeImportException failure = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new RimeImportException(RimeImportException.Code.SOURCE_UNREADABLE);
                }
                result = store.stage(input);
            } catch (RimeImportException error) {
                failure = error;
            } catch (Exception error) {
                failure = new RimeImportException(
                        RimeImportException.Code.SOURCE_UNREADABLE,
                        error);
            }
            RimeResourceStore.StagedImport staged = result;
            RimeImportException error = failure;
            runOnUiThread(() -> {
                if (requestGeneration != generation || isFinishing() || isDestroyed()) {
                    if (staged != null) staged.close();
                    return;
                }
                setBusy(false);
                if (error != null) {
                    showFailure(error);
                    refreshStatus();
                    return;
                }
                pending = staged;
                showPreview(staged.preview());
            });
        });
    }

    private void showPreview(RimeResourceManifest.Preview preview) {
        String source = preview.sourceUrl() == null
                ? getString(R.string.rime_resources_source_unspecified)
                : preview.sourceUrl();
        String schemas = String.join(", ", preview.selectedSchemas());
        String message = getString(
                R.string.rime_resources_preview,
                preview.displayName(),
                preview.packageId(),
                preview.packageVersion(),
                preview.author(),
                preview.rightsholder(),
                preview.licenseExpression(),
                source,
                schemas,
                preview.fileCount(),
                formatBytes(preview.totalBytes()),
                preview.trustState(),
                preview.distributionScope());
        new AlertDialog.Builder(this)
                .setTitle(R.string.rime_resources_confirm_title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, (dialog, which) -> closePending())
                .setOnCancelListener(dialog -> closePending())
                .setPositiveButton(R.string.rime_resources_confirm, (dialog, which) -> commitPending())
                .show();
    }

    private void commitPending() {
        RimeResourceStore.StagedImport staged = pending;
        pending = null;
        if (staged == null) return;
        int requestGeneration = ++generation;
        setBusy(true);
        status.setText(R.string.rime_resources_deploying);
        worker.execute(() -> {
            RimeResourceStore.Installed installed = null;
            RimeImportException failure = null;
            try {
                installed = store.commit(staged);
            } catch (RimeImportException error) {
                failure = error;
            }
            RimeResourceStore.Installed result = installed;
            RimeImportException error = failure;
            runOnUiThread(() -> {
                if (requestGeneration != generation || isFinishing() || isDestroyed()) return;
                setBusy(false);
                if (error != null) {
                    showFailure(error);
                } else {
                    Toast.makeText(
                            this,
                            R.string.rime_resources_import_complete,
                            Toast.LENGTH_LONG).show();
                }
                refreshStatus();
            });
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.rime_resources_clear_title)
                .setMessage(R.string.rime_resources_clear_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.rime_resources_clear, (dialog, which) -> clearResources())
                .show();
    }

    private void clearResources() {
        int requestGeneration = ++generation;
        setBusy(true);
        worker.execute(() -> {
            RimeImportException failure = null;
            try {
                store.clear();
                runtimePreferences.clear();
            } catch (RimeImportException error) {
                failure = error;
            } catch (RuntimeException error) {
                failure = new RimeImportException(
                        RimeImportException.Code.STORAGE_FAILED,
                        error);
            }
            RimeImportException error = failure;
            runOnUiThread(() -> {
                if (requestGeneration != generation || isFinishing() || isDestroyed()) return;
                setBusy(false);
                if (error != null) showFailure(error);
                else Toast.makeText(
                        this,
                        R.string.rime_resources_cleared,
                        Toast.LENGTH_LONG).show();
                refreshStatus();
            });
        });
    }

    private void refreshStatus() {
        int requestGeneration = ++generation;
        worker.execute(() -> {
            RimeResourceStore.Installed installed = null;
            RimeResourceStore.RuntimePackage runtime = null;
            RimeRuntimeConfig configuration = null;
            RimeImportException failure = null;
            RimeUserDataStore.Status learned = null;
            RimeUserDataException learnedFailure = null;
            try {
                installed = store.status();
                runtime = store.runtimePackage();
                if (runtime != null) {
                    configuration = runtimePreferences.load(runtime.selectedSchemas());
                }
            } catch (RimeImportException error) {
                failure = error;
            } catch (RuntimeException error) {
                failure = new RimeImportException(
                        RimeImportException.Code.STORAGE_FAILED,
                        error);
            }
            try {
                learned = userDataStore.status();
            } catch (RimeUserDataException error) {
                learnedFailure = error;
            }
            RimeResourceStore.Installed result = installed;
            RimeResourceStore.RuntimePackage runtimeResult = runtime;
            RimeRuntimeConfig configurationResult = configuration;
            RimeImportException error = failure;
            RimeUserDataStore.Status learnedResult = learned;
            RimeUserDataException learnedError = learnedFailure;
            runOnUiThread(() -> {
                if (requestGeneration != generation || isFinishing() || isDestroyed()) return;
                if (error != null) showFailure(error);
                renderStatus(result, runtimeResult, configurationResult);
                if (learnedError != null) showUserDataFailure(learnedError);
                renderUserDataStatus(learnedResult);
            });
        });
    }

    private void renderStatus(
            RimeResourceStore.Installed installed,
            RimeResourceStore.RuntimePackage runtime,
            RimeRuntimeConfig configuration) {
        if (installed == null) {
            status.setText(R.string.rime_resources_none);
            resourcesInstalled = false;
            clearButton.setEnabled(false);
            configurationCard.setVisibility(View.GONE);
            installedSchemas = List.of();
            return;
        }
        resourcesInstalled = true;
        status.setText(getString(
                R.string.rime_resources_installed,
                installed.displayName(),
                installed.packageVersion(),
                installed.schemaCount(),
                installed.fileCount(),
                formatBytes(installed.totalBytes()),
                installed.trustState(),
                installed.distributionScope()));
        clearButton.setEnabled(true);
        if (runtime == null || configuration == null) {
            configurationCard.setVisibility(View.GONE);
            installedSchemas = List.of();
            return;
        }
        installedSchemas = runtime.selectedSchemas();
        schemaChoices.removeAllViews();
        for (String schema : installedSchemas) {
            RadioButton choice = new RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(schema);
            choice.setTag(schema);
            choice.setMinHeight(dp(48));
            schemaChoices.addView(choice, AppVisualSystem.matchWrap());
            if (schema.equals(configuration.schemaId())) choice.setChecked(true);
        }
        simplifiedOutput.setChecked(configuration.simplifiedOutput());
        fullShape.setChecked(configuration.fullShape());
        asciiPunctuation.setChecked(configuration.asciiPunctuation());
        configurationCard.setVisibility(View.VISIBLE);
    }

    private void renderUserDataStatus(RimeUserDataStore.Status learned) {
        if (learned == null || !learned.hasUserData()) {
            userDataAvailable = false;
            userDataCheckpoint = learned != null && learned.hasCheckpoint();
            userDataStatus.setText(R.string.rime_userdata_none);
        } else {
            userDataAvailable = true;
            userDataCheckpoint = learned.hasCheckpoint();
            userDataStatus.setText(getString(
                    R.string.rime_userdata_status,
                    learned.fileCount(),
                    formatBytes(learned.totalBytes()),
                    getString(learned.hasCheckpoint()
                            ? R.string.rime_userdata_checkpoint_yes
                            : R.string.rime_userdata_checkpoint_no)));
        }
        restoreUserData.setEnabled(userDataCheckpoint);
        clearUserData.setEnabled(userDataAvailable || userDataCheckpoint);
    }

    private void confirmRestoreUserData() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.rime_userdata_restore_title)
                .setMessage(R.string.rime_userdata_restore_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.rime_userdata_restore,
                        (dialog, which) -> runUserDataOperation(true))
                .show();
    }

    private void confirmClearUserData() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.rime_userdata_clear_title)
                .setMessage(R.string.rime_userdata_clear_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.rime_userdata_clear,
                        (dialog, which) -> runUserDataOperation(false))
                .show();
    }

    private void runUserDataOperation(boolean restore) {
        int requestGeneration = ++generation;
        setBusy(true);
        worker.execute(() -> {
            RimeUserDataException failure = null;
            try {
                if (restore) userDataStore.restoreLatestCheckpoint();
                else userDataStore.clear();
            } catch (RimeUserDataException error) {
                failure = error;
            }
            RimeUserDataException error = failure;
            runOnUiThread(() -> {
                if (requestGeneration != generation || isFinishing() || isDestroyed()) return;
                setBusy(false);
                if (error != null) {
                    showUserDataFailure(error);
                } else {
                    Toast.makeText(
                            this,
                            restore
                                    ? R.string.rime_userdata_restored
                                    : R.string.rime_userdata_cleared,
                            Toast.LENGTH_LONG).show();
                }
                refreshStatus();
            });
        });
    }

    private void saveConfiguration() {
        int selectedId = schemaChoices.getCheckedRadioButtonId();
        View selected = selectedId == View.NO_ID ? null : schemaChoices.findViewById(selectedId);
        if (selected == null || !(selected.getTag() instanceof String schema)) {
            Toast.makeText(this, R.string.rime_configuration_select_schema, Toast.LENGTH_LONG)
                    .show();
            return;
        }
        RimeRuntimeConfig configuration;
        try {
            configuration = new RimeRuntimeConfig(
                    schema,
                    simplifiedOutput.isChecked(),
                    asciiPunctuation.isChecked(),
                    fullShape.isChecked());
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(this, R.string.rime_configuration_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        int requestGeneration = ++generation;
        List<String> schemas = List.copyOf(installedSchemas);
        setBusy(true);
        worker.execute(() -> {
            RuntimeException failure = null;
            try {
                runtimePreferences.save(configuration, schemas);
            } catch (RuntimeException error) {
                failure = error;
            }
            RuntimeException error = failure;
            runOnUiThread(() -> {
                if (requestGeneration != generation || isFinishing() || isDestroyed()) return;
                setBusy(false);
                Toast.makeText(
                        this,
                        error == null
                                ? R.string.rime_configuration_saved
                                : R.string.rime_resources_error_storage,
                        Toast.LENGTH_LONG).show();
                refreshStatus();
            });
        });
    }

    private void showFailure(RimeImportException error) {
        int message = switch (error.code()) {
            case BUSY -> R.string.rime_resources_error_busy;
            case SOURCE_UNREADABLE -> R.string.rime_resources_error_source;
            case ARCHIVE_INVALID, ARCHIVE_LIMIT, PATH_INVALID ->
                    R.string.rime_resources_error_archive;
            case MANIFEST_INVALID, FILE_SET_MISMATCH, HASH_MISMATCH,
                    RUNTIME_INCOMPATIBLE -> R.string.rime_resources_error_manifest;
            case RESOURCE_UNSAFE -> R.string.rime_resources_error_unsafe;
            case DEPLOY_FAILED -> R.string.rime_resources_error_deploy;
            case STORAGE_FAILED -> R.string.rime_resources_error_storage;
        };
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void showUserDataFailure(RimeUserDataException error) {
        int message = switch (error.code()) {
            case BUSY -> R.string.rime_userdata_error_busy;
            case NO_CHECKPOINT -> R.string.rime_userdata_error_no_checkpoint;
            case LIMIT_EXCEEDED -> R.string.rime_userdata_error_limit;
            case STORAGE_FAILED -> R.string.rime_userdata_error_storage;
        };
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void setBusy(boolean busy) {
        this.busy = busy;
        importButton.setEnabled(!busy);
        clearButton.setEnabled(!busy && resourcesInstalled);
        saveConfiguration.setEnabled(!busy);
        restoreUserData.setEnabled(!busy && userDataCheckpoint);
        clearUserData.setEnabled(!busy && (userDataAvailable || userDataCheckpoint));
    }

    static boolean shouldRefreshOnResume(boolean busy) {
        return !busy;
    }

    private void closePending() {
        if (pending != null) pending.close();
        pending = null;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024d);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024d * 1024d));
    }

    private LinearLayout.LayoutParams topMargin(int top) {
        LinearLayout.LayoutParams params = AppVisualSystem.matchWrap();
        params.topMargin = dp(top);
        return params;
    }

    private CheckBox optionCheckBox(int label) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setMinHeight(dp(48));
        return checkBox;
    }

    private int dp(int value) {
        return AppVisualSystem.dp(this, value);
    }
}
