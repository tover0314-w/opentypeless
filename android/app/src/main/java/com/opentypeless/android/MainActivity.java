package com.opentypeless.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.opentypeless.android.net.EndpointNormalizer;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.offline.LocalOfflineRecognizer;
import com.opentypeless.android.offline.OfflineModelOperationCoordinator;
import com.opentypeless.android.offline.OfflineModelSpec;
import com.opentypeless.android.offline.OfflineModelStore;
import com.opentypeless.android.recognition.SystemSpeechRecognizer;
import com.opentypeless.android.recognition.SystemRecognitionDiagnostics;
import com.opentypeless.android.recognition.SystemModelDownloadCoordinator;
import com.opentypeless.android.recognition.SystemRecognitionSupport;
import com.opentypeless.android.recognition.StandardRecognitionSettings;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.settings.SettingsFormDraft;
import com.opentypeless.android.settings.SettingsRepository;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private static final int MICROPHONE_PERMISSION_REQUEST = 100;
    private static final String STATE_PREFIX = "settings_draft_";
    private static final String STATE_HAS_DRAFT = STATE_PREFIX + "present";

    private SettingsRepository repository;
    private StandardRecognitionSettings standardRecognitionSettings;
    private Spinner recognitionBackend;
    private Spinner defaultMode;
    private LinearLayout networkSttFields;
    private LinearLayout batchSttFields;
    private LinearLayout streamingSttFields;
    private LinearLayout localOfflineFields;
    private TextView systemBackendNote;
    private TextView systemRouteDiagnostics;
    private TextView localModelStatus;
    private Button downloadOfflineModel;
    private Button deleteOfflineModel;
    private TextView languageSupportStatus;
    private Button checkLanguageSupport;
    private Button downloadLanguageModel;
    private CheckBox standardSpeechEnabled;
    private EditText standardSpeechCallers;
    private EditText sttBaseUrl;
    private EditText sttApiKey;
    private EditText sttModel;
    private EditText streamingBaseUrl;
    private EditText streamingApiKey;
    private EditText streamingModel;
    private EditText streamingVocabularyId;
    private EditText language;
    private EditText maxRecordingSeconds;
    private CheckBox polishEnabled;
    private LinearLayout llmFields;
    private LinearLayout translationFields;
    private EditText llmBaseUrl;
    private EditText llmApiKey;
    private EditText llmModel;
    private EditText targetLanguage;
    private EditText customInstructions;
    private CheckBox personalizationEnabled;
    private CheckBox historyEnabled;
    private CheckBox sendContext;
    private TextView permissionStatus;
    private SystemRecognitionSupport.Operation supportOperation;
    private RecognitionBackend supportBackend;
    private long supportGeneration;
    private boolean languageDownloadAvailable;
    private final OfflineModelOperationCoordinator.Listener offlineModelListener =
            this::renderOfflineModelOperation;
    private final SystemModelDownloadCoordinator.Listener systemModelListener =
            this::renderSystemModelDownload;
    private boolean applyingDraft;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        repository = new SettingsRepository(this);
        standardRecognitionSettings = new StandardRecognitionSettings(this);
        AppSettings persisted = repository.load();
        setContentView(buildContent(persisted));
        Object retained = getLastNonConfigurationInstance();
        applyingDraft = true;
        try {
            if (retained instanceof SettingsFormDraft draft) {
                applyDraft(draft);
            } else if (savedInstanceState != null
                    && savedInstanceState.getBoolean(STATE_HAS_DRAFT, false)) {
                applyDraft(readPersistentDraft(savedInstanceState).withSecrets(
                        persisted.sttApiKey(),
                        persisted.streamingApiKey(),
                        persisted.llmApiKey()));
            }
        } finally {
            applyingDraft = false;
        }
        refreshPermissionStatus();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        // Keep unsaved API keys in memory across rotation/fold changes, never in saved-state Bundles.
        return recognitionBackend == null ? null : captureDraft();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (recognitionBackend != null) writePersistentDraft(outState, captureDraft());
        super.onSaveInstanceState(outState);
    }

    private View buildContent(AppSettings settings) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        SystemBarInsets.apply(scroll);
        LinearLayout root = verticalLayout();
        int padding = dp(20);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(getColor(R.color.ime_surface));
        scroll.addView(root);

        root.addView(text(getString(R.string.settings_title), 26, true));
        TextView intro = text(getString(R.string.settings_intro), 15, false);
        intro.setTextColor(getColor(R.color.ime_on_surface_variant));
        intro.setPadding(0, dp(8), 0, dp(12));
        root.addView(intro);

        root.addView(section(R.string.section_recognition));
        recognitionBackend = enumSpinner(
                root,
                R.string.backend_label,
                RecognitionBackend.values(),
                settings.recognitionBackend().ordinal());
        defaultMode = enumSpinner(
                root,
                R.string.default_mode_label,
                ProcessingMode.values(),
                settings.defaultMode().ordinal());
        language = field(
                root,
                R.string.language_label,
                settings.language(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                false);
        maxRecordingSeconds = field(
                root,
                R.string.max_recording_label,
                Integer.toString(settings.boundedMaxRecordingSeconds()),
                InputType.TYPE_CLASS_NUMBER,
                false);

        networkSttFields = verticalLayout();
        batchSttFields = verticalLayout();
        sttBaseUrl = field(
                batchSttFields,
                R.string.stt_base_url_label,
                settings.sttBaseUrl(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                false);
        sttApiKey = field(
                batchSttFields,
                R.string.stt_api_key_label,
                settings.sttApiKey(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                false);
        protectSecretField(sttApiKey);
        sttModel = field(
                batchSttFields,
                R.string.stt_model_label,
                settings.sttModel(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                false);
        networkSttFields.addView(batchSttFields);

        streamingSttFields = verticalLayout();
        streamingSttFields.addView(note(
                R.string.streaming_provider_note,
                getColor(R.color.ime_on_surface_variant)));
        streamingBaseUrl = field(
                streamingSttFields,
                R.string.streaming_base_url_label,
                settings.streamingBaseUrl(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                false);
        streamingApiKey = field(
                streamingSttFields,
                R.string.streaming_api_key_label,
                settings.streamingApiKey(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                false);
        protectSecretField(streamingApiKey);
        streamingModel = field(
                streamingSttFields,
                R.string.streaming_model_label,
                settings.streamingModel(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                false);
        streamingVocabularyId = field(
                streamingSttFields,
                R.string.streaming_vocabulary_id_label,
                settings.streamingVocabularyId(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                false);
        networkSttFields.addView(streamingSttFields);
        root.addView(networkSttFields);

        localOfflineFields = verticalLayout();
        localOfflineFields.addView(note(
                R.string.offline_model_note,
                getColor(R.color.ime_on_surface_variant)));
        localModelStatus = note(R.string.offline_model_missing, getColor(R.color.ime_warning));
        localModelStatus.setMinHeight(dp(48));
        localOfflineFields.addView(localModelStatus);
        downloadOfflineModel = button(
                R.string.download_offline_model,
                ignored -> confirmOfflineModelDownload());
        localOfflineFields.addView(downloadOfflineModel);
        deleteOfflineModel = button(
                R.string.delete_offline_model,
                ignored -> confirmOfflineModelDelete());
        localOfflineFields.addView(deleteOfflineModel);
        root.addView(localOfflineFields);

        systemBackendNote = note(
                R.string.system_backend_note,
                getColor(R.color.ime_on_surface_variant));
        root.addView(systemBackendNote);
        systemRouteDiagnostics = note(
                R.string.system_route_inspecting,
                getColor(R.color.ime_on_surface_variant));
        systemRouteDiagnostics.setTextIsSelectable(true);
        root.addView(systemRouteDiagnostics);
        languageSupportStatus = note(
                R.string.language_support_not_checked,
                getColor(R.color.ime_on_surface_variant));
        languageSupportStatus.setMinHeight(dp(48));
        root.addView(languageSupportStatus);
        checkLanguageSupport = button(
                R.string.check_language_support,
                ignored -> checkLanguageSupport());
        root.addView(checkLanguageSupport);
        downloadLanguageModel = button(
                R.string.download_language_model,
                ignored -> downloadLanguageModel());
        downloadLanguageModel.setVisibility(View.GONE);
        root.addView(downloadLanguageModel);

        StandardRecognitionSettings.Snapshot standardSpeech = standardRecognitionSettings.load();
        root.addView(section(R.string.section_standard_speech));
        standardSpeechEnabled = checkbox(
                R.string.standard_speech_enabled,
                standardSpeech.enabled());
        root.addView(standardSpeechEnabled);
        standardSpeechCallers = field(
                root,
                R.string.standard_speech_callers_label,
                standardSpeech.packagesAsText(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                true);
        standardSpeechCallers.setHint(R.string.standard_speech_callers_hint);
        root.addView(note(
                R.string.standard_speech_security_note,
                getColor(R.color.ime_warning)));

        root.addView(section(R.string.section_processing));
        polishEnabled = checkbox(R.string.polish_enabled, settings.polishEnabled());
        root.addView(polishEnabled);
        llmFields = verticalLayout();
        llmBaseUrl = field(
                llmFields,
                R.string.llm_base_url_label,
                settings.llmBaseUrl(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                false);
        llmApiKey = field(
                llmFields,
                R.string.llm_api_key_label,
                settings.llmApiKey(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                false);
        protectSecretField(llmApiKey);
        llmModel = field(
                llmFields,
                R.string.llm_model_label,
                settings.llmModel(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                false);
        translationFields = verticalLayout();
        targetLanguage = field(
                translationFields,
                R.string.target_language_label,
                settings.targetLanguage(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                false);
        llmFields.addView(translationFields);
        customInstructions = field(
                llmFields,
                R.string.custom_instructions_label,
                settings.customInstructions(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                true);
        customInstructions.setHint(R.string.custom_instructions_hint);
        root.addView(llmFields);

        root.addView(section(R.string.section_privacy));
        personalizationEnabled = checkbox(
                R.string.personalization_enabled,
                settings.personalizationEnabled());
        historyEnabled = checkbox(R.string.history_enabled, settings.historyEnabled());
        sendContext = checkbox(R.string.send_context_enabled, settings.sendContext());
        root.addView(personalizationEnabled);
        root.addView(historyEnabled);
        root.addView(sendContext);
        root.addView(note(R.string.privacy_note, getColor(R.color.ime_on_surface_variant)));
        root.addView(note(R.string.local_http_note, getColor(R.color.ime_warning)));

        Button save = button(R.string.save_configuration, ignored -> saveSettings());
        save.setBackgroundResource(R.drawable.ime_primary_key_background);
        save.setTextColor(getColorStateList(R.color.ime_primary_key_text));
        root.addView(save);

        root.addView(section(R.string.section_manage));
        root.addView(button(R.string.manage_dictionary, ignored ->
                startActivity(new Intent(this, DictionaryActivity.class))));
        root.addView(button(R.string.manage_history, ignored ->
                startActivity(new Intent(this, HistoryActivity.class))));
        root.addView(button(R.string.manage_app_profiles, ignored ->
                startActivity(new Intent(this, AppProfileActivity.class))));
        root.addView(button(R.string.legal_notices, ignored -> showLegalNotices()));

        root.addView(section(R.string.section_enable_keyboard));
        permissionStatus = text("", 14, false);
        permissionStatus.setMinHeight(dp(48));
        root.addView(permissionStatus);
        root.addView(button(R.string.grant_microphone, ignored -> requestMicrophone()));
        root.addView(button(R.string.enable_keyboard, ignored ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        root.addView(button(R.string.choose_keyboard, ignored -> {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            manager.showInputMethodPicker();
        }));

        recognitionBackend.setOnItemSelectedListener(new SimpleSelectionListener(this::updateVisibility));
        language.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) {
                if (!applyingDraft && supportBackend != null) resetLanguageSupportState();
            }
        });
        polishEnabled.setOnCheckedChangeListener((ignored, checked) -> updateVisibility());
        updateVisibility();
        return scroll;
    }

    private void showLegalNotices() {
        String notices;
        try {
            notices = readRawText(R.raw.legal_notices)
                    + "\n\n"
                    + readRawText(R.raw.offline_asr_runtime_licenses);
        } catch (Exception error) {
            notices = getString(R.string.operation_failed);
        }
        TextView content = text(notices, 13, false);
        int padding = dp(20);
        content.setPadding(padding, padding, padding, padding);
        content.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle(R.string.legal_notices)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private String readRawText(int resource) throws Exception {
        try (InputStream input = getResources().openRawResource(resource)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void refreshOfflineModelStatus() {
        OfflineModelOperationCoordinator.State operation =
                OfflineModelOperationCoordinator.snapshot();
        if (operation.running()
                || operation.phase() == OfflineModelOperationCoordinator.Phase.FAILED) {
            renderOfflineModelOperation(operation);
            return;
        }
        OfflineModelStore.Status status = OfflineModelStore.status(this);
        boolean supported = LocalOfflineRecognizer.isSupportedDevice(this);
        int message = switch (status) {
            case MISSING -> supported
                    ? R.string.offline_model_missing
                    : R.string.offline_model_unsupported_low_memory;
            case INSTALLED -> R.string.offline_model_installed;
            case CORRUPT -> R.string.offline_model_corrupt;
        };
        localModelStatus.setText(message);
        localModelStatus.setTextColor(status == OfflineModelStore.Status.INSTALLED
                ? getColor(R.color.ime_primary)
                : getColor(R.color.ime_error));
        localModelStatus.setContentDescription(localModelStatus.getText());
        downloadOfflineModel.setEnabled(supported
                && status != OfflineModelStore.Status.INSTALLED);
        deleteOfflineModel.setVisibility(status == OfflineModelStore.Status.MISSING
                ? View.GONE
                : View.VISIBLE);
    }

    private void confirmOfflineModelDownload() {
        if (OfflineModelOperationCoordinator.snapshot().running()) return;
        OfflineModelSpec spec = OfflineModelSpec.QUALITY;
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_offline_model_title)
                .setMessage(getString(
                        R.string.download_offline_model_confirmation,
                        spec.displayName(),
                        spec.downloadBytes() / (1024L * 1024L),
                        spec.revision()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.download_offline_model, (ignored, which) ->
                        startOfflineModelDownload())
                .show();
    }

    private void startOfflineModelDownload() {
        OfflineModelOperationCoordinator.startDownload(this);
    }

    private void confirmOfflineModelDelete() {
        if (OfflineModelOperationCoordinator.snapshot().running()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_offline_model_title)
                .setMessage(R.string.delete_offline_model_confirmation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (ignored, which) -> startOfflineModelDelete())
                .show();
    }

    private void startOfflineModelDelete() {
        OfflineModelOperationCoordinator.startDelete(this);
    }

    private void renderOfflineModelOperation(OfflineModelOperationCoordinator.State operation) {
        if (localModelStatus == null || isFinishing() || isDestroyed()) return;
        boolean running = operation.running();
        downloadOfflineModel.setEnabled(!running);
        deleteOfflineModel.setEnabled(!running);
        if (running && operation.kind() == OfflineModelOperationCoordinator.Kind.DOWNLOAD) {
            deleteOfflineModel.setVisibility(View.GONE);
            if (operation.totalBytes() > 0) {
                localModelStatus.setText(getString(
                        R.string.offline_model_download_progress,
                        operation.percent(),
                        operation.completedBytes() / (1024L * 1024L),
                        operation.totalBytes() / (1024L * 1024L)));
            } else {
                localModelStatus.setText(R.string.offline_model_download_starting);
            }
            localModelStatus.setTextColor(getColor(R.color.ime_on_surface_variant));
            return;
        }
        if (running) {
            localModelStatus.setText(R.string.offline_model_deleting);
            localModelStatus.setTextColor(getColor(R.color.ime_on_surface_variant));
            return;
        }
        if (operation.phase() == OfflineModelOperationCoordinator.Phase.FAILED) {
            int message = operation.kind() == OfflineModelOperationCoordinator.Kind.DOWNLOAD
                    ? R.string.offline_model_download_failed
                    : R.string.offline_model_delete_failed;
            localModelStatus.setText(getString(message, operation.errorMessage()));
            localModelStatus.setTextColor(getColor(R.color.ime_error));
            return;
        }
        refreshOfflineModelStatus();
    }

    private void updateVisibility() {
        RecognitionBackend backend = selectedBackend();
        boolean batch = backend == RecognitionBackend.OPENAI_COMPATIBLE;
        boolean streaming = backend == RecognitionBackend.DASHSCOPE_STREAMING;
        boolean network = batch || streaming;
        boolean local = backend == RecognitionBackend.LOCAL_OFFLINE;
        boolean system = backend == RecognitionBackend.SYSTEM_ON_DEVICE
                || backend == RecognitionBackend.SYSTEM_DEFAULT;
        if (supportBackend != backend) {
            boolean changedByUser = supportBackend != null && !applyingDraft;
            supportBackend = backend;
            if (changedByUser) resetLanguageSupportState();
        }
        networkSttFields.setVisibility(network ? View.VISIBLE : View.GONE);
        batchSttFields.setVisibility(batch ? View.VISIBLE : View.GONE);
        streamingSttFields.setVisibility(streaming ? View.VISIBLE : View.GONE);
        localOfflineFields.setVisibility(local ? View.VISIBLE : View.GONE);
        systemBackendNote.setVisibility(system ? View.VISIBLE : View.GONE);
        systemRouteDiagnostics.setVisibility(system ? View.VISIBLE : View.GONE);
        languageSupportStatus.setVisibility(system ? View.VISIBLE : View.GONE);
        checkLanguageSupport.setVisibility(system ? View.VISIBLE : View.GONE);
        downloadLanguageModel.setVisibility(system && languageDownloadAvailable
                ? View.VISIBLE
                : View.GONE);
        if (local) refreshOfflineModelStatus();
        if (system) {
            refreshSystemRouteDiagnostics(backend);
            boolean available;
            int statusResource;
            if (selectedBackend() == RecognitionBackend.SYSTEM_ON_DEVICE) {
                available = backendAvailable(RecognitionBackend.SYSTEM_ON_DEVICE);
                statusResource = available
                        ? R.string.on_device_available
                        : R.string.on_device_unavailable;
            } else {
                available = backendAvailable(RecognitionBackend.SYSTEM_DEFAULT);
                statusResource = available
                        ? R.string.system_speech_available
                        : R.string.system_speech_unavailable;
            }
            systemBackendNote.setText(getString(
                    R.string.system_backend_status,
                    getString(statusResource),
                    getString(R.string.system_backend_note)));
            systemBackendNote.setTextColor(getColor(available
                    ? R.color.ime_primary
                    : R.color.ime_error));
            systemBackendNote.setContentDescription(systemBackendNote.getText());
        }
        llmFields.setVisibility(polishEnabled.isChecked() ? View.VISIBLE : View.GONE);
        translationFields.setVisibility(View.VISIBLE);
    }

    private void refreshSystemRouteDiagnostics(RecognitionBackend backend) {
        SystemRecognitionDiagnostics.Snapshot diagnostics =
                SystemRecognitionDiagnostics.inspect(this);
        String service = diagnostics.serviceIdentified()
                ? getString(
                        R.string.system_route_service,
                        diagnostics.serviceLabel().isBlank()
                                ? diagnostics.packageName()
                                : diagnostics.serviceLabel(),
                        diagnostics.packageName(),
                        diagnostics.versionName().isBlank()
                                ? getString(R.string.system_route_version_unknown)
                                : diagnostics.versionName())
                : getString(R.string.system_route_service_unknown);
        String capability;
        if (backend == RecognitionBackend.SYSTEM_ON_DEVICE) {
            capability = diagnostics.onDeviceAvailable()
                    ? getString(R.string.system_route_on_device_available)
                    : getString(R.string.system_route_on_device_unavailable);
        } else {
            capability = diagnostics.systemAvailable()
                    ? getString(R.string.system_route_default_available)
                    : getString(R.string.system_route_default_unavailable);
        }
        systemRouteDiagnostics.setText(getString(
                R.string.system_route_diagnostics_summary,
                service,
                capability));
        systemRouteDiagnostics.setTextColor(getColor(
                backendAvailable(backend)
                        ? R.color.ime_on_surface_variant
                        : R.color.ime_error));
    }

    private void checkLanguageSupport() {
        if (selectedBackend() != RecognitionBackend.SYSTEM_ON_DEVICE
                && selectedBackend() != RecognitionBackend.SYSTEM_DEFAULT) return;
        cancelLanguageOperations();
        long request = supportGeneration;
        languageDownloadAvailable = false;
        checkLanguageSupport.setEnabled(false);
        downloadLanguageModel.setVisibility(View.GONE);
        languageSupportStatus.setText(R.string.language_support_checking);
        languageSupportStatus.setTextColor(getColor(R.color.ime_on_surface_variant));
        supportOperation = SystemSpeechRecognizer.checkRecognitionSupport(
                this,
                languageSupportSettings(),
                PersonalizationSnapshot.empty(),
                result -> {
                    if (request != supportGeneration || isFinishing() || isDestroyed()) return;
                    supportOperation = null;
                    showLanguageSupport(result);
                });
    }

    private void downloadLanguageModel() {
        if (selectedBackend() != RecognitionBackend.SYSTEM_ON_DEVICE) return;
        cancelLanguageOperations();
        languageDownloadAvailable = false;
        checkLanguageSupport.setEnabled(false);
        downloadLanguageModel.setVisibility(View.GONE);
        languageSupportStatus.setText(R.string.language_model_download_starting);
        languageSupportStatus.setTextColor(getColor(R.color.ime_on_surface_variant));
        SystemModelDownloadCoordinator.start(
                this,
                languageSupportSettings(),
                PersonalizationSnapshot.empty());
    }

    private void renderSystemModelDownload(SystemModelDownloadCoordinator.State operation) {
        if (languageSupportStatus == null || isFinishing() || isDestroyed()) return;
        if (operation.running()) {
            languageDownloadAvailable = false;
            checkLanguageSupport.setEnabled(false);
            downloadLanguageModel.setVisibility(View.GONE);
            languageSupportStatus.setText(operation.progress() > 0
                    ? getString(R.string.language_model_download_progress, operation.progress())
                    : getString(R.string.language_model_download_starting));
            languageSupportStatus.setTextColor(getColor(R.color.ime_on_surface_variant));
            return;
        }
        SystemRecognitionSupport.DownloadResult result = operation.result();
        if (result == null) return;
        checkLanguageSupport.setEnabled(true);
        languageSupportStatus.setText(downloadResultText(result));
        languageSupportStatus.setTextColor(getColor(
                result.status() == SystemRecognitionSupport.DownloadStatus.COMPLETED
                        ? R.color.ime_primary
                        : result.status() == SystemRecognitionSupport.DownloadStatus.FAILED
                                ? R.color.ime_error
                                : R.color.ime_warning));
    }

    private void showLanguageSupport(SystemRecognitionSupport.Result result) {
        checkLanguageSupport.setEnabled(true);
        languageDownloadAvailable = result.canDownload()
                && selectedBackend() == RecognitionBackend.SYSTEM_ON_DEVICE;
        downloadLanguageModel.setVisibility(languageDownloadAvailable ? View.VISIBLE : View.GONE);
        String text = supportResultText(result);
        if (selectedBackend() == RecognitionBackend.SYSTEM_DEFAULT) {
            text += "\n\n" + getString(R.string.system_default_offline_not_guaranteed);
        }
        languageSupportStatus.setText(text);
        boolean success = result.status() == SystemRecognitionSupport.Status.INSTALLED
                && selectedBackend() == RecognitionBackend.SYSTEM_ON_DEVICE;
        boolean failure = result.status() == SystemRecognitionSupport.Status.UNSUPPORTED
                || result.status() == SystemRecognitionSupport.Status.SERVICE_UNAVAILABLE
                || result.status() == SystemRecognitionSupport.Status.ERROR;
        languageSupportStatus.setTextColor(getColor(success
                ? R.color.ime_primary
                : failure ? R.color.ime_error : R.color.ime_warning));
    }

    private String supportResultText(SystemRecognitionSupport.Result result) {
        String selectedLanguage = result.language().isBlank()
                ? value(language)
                : result.language();
        return switch (result.status()) {
            case INSTALLED -> getString(R.string.language_support_installed, selectedLanguage);
            case DOWNLOAD_PENDING -> getString(
                    R.string.language_support_download_pending,
                    selectedLanguage);
            case DOWNLOAD_AVAILABLE -> getString(
                    R.string.language_support_download_available,
                    selectedLanguage);
            case ONLINE_ONLY -> getString(R.string.language_support_online_only, selectedLanguage);
            case UNSUPPORTED -> getString(R.string.language_support_unsupported, selectedLanguage);
            case LANGUAGE_UNSPECIFIED -> getString(R.string.language_support_unspecified);
            case LEGACY_NOT_VERIFIABLE -> getString(R.string.language_support_legacy_unverified);
            case SERVICE_UNAVAILABLE -> getString(R.string.language_support_service_unavailable);
            case ERROR -> getString(R.string.language_support_check_failed, result.errorCode());
        };
    }

    private String downloadResultText(SystemRecognitionSupport.DownloadResult result) {
        return switch (result.status()) {
            case REQUESTED -> getString(R.string.language_model_download_requested);
            case SCHEDULED -> getString(R.string.language_model_download_scheduled);
            case COMPLETED -> getString(R.string.language_model_download_completed);
            case API_UNAVAILABLE -> getString(R.string.language_model_download_api_unavailable);
            case FAILED -> getString(R.string.language_model_download_failed, result.errorCode());
        };
    }

    private AppSettings languageSupportSettings() {
        return new AppSettings(
                selectedBackend(),
                "",
                "",
                "",
                "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
                "",
                "paraformer-realtime-v2",
                "",
                value(language),
                ProcessingMode.VERBATIM,
                false,
                "",
                "",
                "",
                "",
                "",
                false,
                false,
                false,
                180);
    }

    private void resetLanguageSupportState() {
        cancelLanguageOperations();
        languageDownloadAvailable = false;
        if (languageSupportStatus != null) {
            languageSupportStatus.setText(R.string.language_support_not_checked);
            languageSupportStatus.setTextColor(getColor(R.color.ime_on_surface_variant));
        }
        if (checkLanguageSupport != null) checkLanguageSupport.setEnabled(true);
        if (downloadLanguageModel != null) downloadLanguageModel.setVisibility(View.GONE);
    }

    private void cancelLanguageOperations() {
        supportGeneration++;
        if (supportOperation != null) supportOperation.cancel();
        supportOperation = null;
        SystemModelDownloadCoordinator.cancel();
    }

    private void saveSettings() {
        try {
            StandardRecognitionSettings.Snapshot standardSpeech =
                    standardRecognitionSettings.validate(
                            standardSpeechEnabled.isChecked(),
                            value(standardSpeechCallers));
            RecognitionBackend backend = selectedBackend();
            if (backend == RecognitionBackend.OPENAI_COMPATIBLE) {
                String endpoint = EndpointNormalizer.endpoint(
                        sttBaseUrl.getText().toString(),
                        "audio/transcriptions");
                EndpointNormalizer.requireCredentialSafeTransport(endpoint, value(sttApiKey));
                if (sttModel.getText().toString().trim().isEmpty()) {
                    throw new IllegalArgumentException(getString(R.string.stt_model_required));
                }
            } else if (backend == RecognitionBackend.LOCAL_OFFLINE
                    && (!LocalOfflineRecognizer.isSupportedDevice(this)
                    || !LocalOfflineRecognizer.isInstalled(this))) {
                throw new IllegalArgumentException(getString(R.string.offline_model_required));
            } else if (backend == RecognitionBackend.DASHSCOPE_STREAMING) {
                EndpointNormalizer.dashScopeWebSocket(value(streamingBaseUrl));
                if (value(streamingApiKey).isEmpty()) {
                    throw new IllegalArgumentException(
                            getString(R.string.streaming_api_key_required));
                }
                if (value(streamingModel).isEmpty()) {
                    throw new IllegalArgumentException(
                            getString(R.string.streaming_model_required));
                }
            } else if (backend == RecognitionBackend.SYSTEM_ON_DEVICE
                    && !backendAvailable(backend)) {
                throw new IllegalArgumentException(getString(R.string.on_device_unavailable));
            } else if (backend == RecognitionBackend.SYSTEM_DEFAULT
                    && !backendAvailable(backend)) {
                throw new IllegalArgumentException(getString(R.string.system_speech_unavailable));
            }
            if (polishEnabled.isChecked()) {
                String endpoint = EndpointNormalizer.endpoint(
                        llmBaseUrl.getText().toString(),
                        "chat/completions");
                EndpointNormalizer.requireCredentialSafeTransport(endpoint, value(llmApiKey));
                if (llmModel.getText().toString().trim().isEmpty()) {
                    throw new IllegalArgumentException(getString(R.string.llm_model_required));
                }
            }
            int maximumSeconds;
            try {
                maximumSeconds = Integer.parseInt(maxRecordingSeconds.getText().toString().trim());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(getString(R.string.invalid_recording_length));
            }
            if (maximumSeconds < 5 || maximumSeconds > 540) {
                throw new IllegalArgumentException(getString(R.string.invalid_recording_length));
            }

            AppSettings proposed = new AppSettings(
                    backend,
                    value(sttBaseUrl),
                    value(sttApiKey),
                    value(sttModel),
                    value(streamingBaseUrl),
                    value(streamingApiKey),
                    value(streamingModel),
                    value(streamingVocabularyId),
                    value(language),
                    selectedMode(),
                    polishEnabled.isChecked(),
                    value(llmBaseUrl),
                    value(llmApiKey),
                    value(llmModel),
                    value(targetLanguage),
                    value(customInstructions),
                    personalizationEnabled.isChecked(),
                    historyEnabled.isChecked(),
                    sendContext.isChecked(),
                    maximumSeconds);
            if (!StandardRecognitionSettings.isSupportedRoute(standardSpeech, proposed)) {
                throw new IllegalArgumentException(
                        getString(R.string.standard_speech_requires_byok));
            }
            try {
                repository.save(proposed);
                standardRecognitionSettings.save(standardSpeech);
            } catch (RuntimeException storageFailure) {
                // Never show storage/Keystore exception text: nested causes may contain endpoint or
                // credential material supplied by a provider implementation.
                Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, R.string.configuration_saved, Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, safeMessage(error), Toast.LENGTH_LONG).show();
        } catch (RuntimeException error) {
            // Unexpected platform failures are reported without their possibly sensitive message.
            Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_LONG).show();
        }
    }

    private SettingsFormDraft captureDraft() {
        return new SettingsFormDraft(
                recognitionBackend.getSelectedItemPosition(),
                defaultMode.getSelectedItemPosition(),
                raw(language),
                raw(maxRecordingSeconds),
                raw(sttBaseUrl),
                raw(sttApiKey),
                raw(sttModel),
                raw(streamingBaseUrl),
                raw(streamingApiKey),
                raw(streamingModel),
                raw(streamingVocabularyId),
                standardSpeechEnabled.isChecked(),
                raw(standardSpeechCallers),
                polishEnabled.isChecked(),
                raw(llmBaseUrl),
                raw(llmApiKey),
                raw(llmModel),
                raw(targetLanguage),
                raw(customInstructions),
                personalizationEnabled.isChecked(),
                historyEnabled.isChecked(),
                sendContext.isChecked());
    }

    private void applyDraft(SettingsFormDraft draft) {
        recognitionBackend.setSelection(clamp(
                draft.recognitionBackendIndex(), RecognitionBackend.values().length));
        defaultMode.setSelection(clamp(draft.defaultModeIndex(), ProcessingMode.values().length));
        language.setText(draft.language());
        maxRecordingSeconds.setText(draft.maxRecordingSeconds());
        sttBaseUrl.setText(draft.sttBaseUrl());
        sttApiKey.setText(draft.sttApiKey());
        sttModel.setText(draft.sttModel());
        streamingBaseUrl.setText(draft.streamingBaseUrl());
        streamingApiKey.setText(draft.streamingApiKey());
        streamingModel.setText(draft.streamingModel());
        streamingVocabularyId.setText(draft.streamingVocabularyId());
        standardSpeechEnabled.setChecked(draft.standardSpeechEnabled());
        standardSpeechCallers.setText(draft.standardSpeechCallers());
        polishEnabled.setChecked(draft.polishEnabled());
        llmBaseUrl.setText(draft.llmBaseUrl());
        llmApiKey.setText(draft.llmApiKey());
        llmModel.setText(draft.llmModel());
        targetLanguage.setText(draft.targetLanguage());
        customInstructions.setText(draft.customInstructions());
        personalizationEnabled.setChecked(draft.personalizationEnabled());
        historyEnabled.setChecked(draft.historyEnabled());
        sendContext.setChecked(draft.sendContext());
        updateVisibility();
    }

    private static void writePersistentDraft(Bundle state, SettingsFormDraft draft) {
        state.putBoolean(STATE_HAS_DRAFT, true);
        state.putInt(key("backend"), draft.recognitionBackendIndex());
        state.putInt(key("mode"), draft.defaultModeIndex());
        state.putString(key("language"), draft.language());
        state.putString(key("maximum"), draft.maxRecordingSeconds());
        state.putString(key("stt_url"), draft.sttBaseUrl());
        // API keys deliberately stay out of Bundle because Android may persist it to disk.
        state.putString(key("stt_model"), draft.sttModel());
        state.putString(key("streaming_url"), draft.streamingBaseUrl());
        state.putString(key("streaming_model"), draft.streamingModel());
        state.putString(key("streaming_vocabulary"), draft.streamingVocabularyId());
        state.putBoolean(key("standard_enabled"), draft.standardSpeechEnabled());
        state.putString(key("standard_callers"), draft.standardSpeechCallers());
        state.putBoolean(key("polish"), draft.polishEnabled());
        state.putString(key("llm_url"), draft.llmBaseUrl());
        state.putString(key("llm_model"), draft.llmModel());
        state.putString(key("target"), draft.targetLanguage());
        state.putString(key("instructions"), draft.customInstructions());
        state.putBoolean(key("personalization"), draft.personalizationEnabled());
        state.putBoolean(key("history"), draft.historyEnabled());
        state.putBoolean(key("context"), draft.sendContext());
    }

    private static SettingsFormDraft readPersistentDraft(Bundle state) {
        return new SettingsFormDraft(
                state.getInt(key("backend"), 0),
                state.getInt(key("mode"), 0),
                state.getString(key("language"), ""),
                state.getString(key("maximum"), ""),
                state.getString(key("stt_url"), ""),
                "",
                state.getString(key("stt_model"), ""),
                state.getString(
                        key("streaming_url"),
                        "wss://dashscope.aliyuncs.com/api-ws/v1/inference"),
                "",
                state.getString(key("streaming_model"), "paraformer-realtime-v2"),
                state.getString(key("streaming_vocabulary"), ""),
                state.getBoolean(key("standard_enabled"), false),
                state.getString(key("standard_callers"), ""),
                state.getBoolean(key("polish"), false),
                state.getString(key("llm_url"), ""),
                "",
                state.getString(key("llm_model"), ""),
                state.getString(key("target"), ""),
                state.getString(key("instructions"), ""),
                state.getBoolean(key("personalization"), false),
                state.getBoolean(key("history"), false),
                state.getBoolean(key("context"), false));
    }

    private void requestMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.microphone_already_granted, Toast.LENGTH_SHORT).show();
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
        if (recognitionBackend != null) updateVisibility();
    }

    @Override
    protected void onStart() {
        super.onStart();
        OfflineModelOperationCoordinator.addListener(offlineModelListener);
        SystemModelDownloadCoordinator.addListener(systemModelListener);
    }

    @Override
    protected void onStop() {
        OfflineModelOperationCoordinator.removeListener(offlineModelListener);
        SystemModelDownloadCoordinator.removeListener(systemModelListener);
        // A platform language-model download may temporarily launch system approval UI. Keep it
        // alive while this Activity is merely stopped. A support check has no user interaction and
        // can be safely abandoned. OpenTypeless model transfers are application-scoped separately.
        if (supportOperation != null) {
            supportGeneration++;
            supportOperation.cancel();
            supportOperation = null;
            languageDownloadAvailable = false;
            languageSupportStatus.setText(R.string.language_support_not_checked);
            languageSupportStatus.setTextColor(getColor(R.color.ime_on_surface_variant));
            checkLanguageSupport.setEnabled(true);
            downloadLanguageModel.setVisibility(View.GONE);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        supportGeneration++;
        if (supportOperation != null) supportOperation.cancel();
        supportOperation = null;
        super.onDestroy();
    }

    private void refreshPermissionStatus() {
        boolean granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(granted
                ? R.string.microphone_granted
                : R.string.microphone_required);
        permissionStatus.setTextColor(getColor(
                granted ? R.color.ime_primary : R.color.ime_error));
        permissionStatus.setContentDescription(permissionStatus.getText());
    }

    private Spinner enumSpinner(
            LinearLayout root,
            int labelResource,
            Object[] values,
            int selectedIndex) {
        String label = getString(labelResource);
        TextView labelView = text(label, 14, true);
        labelView.setPadding(0, dp(8), 0, 0);
        root.addView(labelView);
        String[] labels = new String[values.length];
        for (int index = 0; index < values.length; index++) {
            labels[index] = enumLabel(values[index]);
        }
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(selectedIndex, labels.length - 1)));
        spinner.setMinimumHeight(dp(48));
        spinner.setContentDescription(label);
        root.addView(spinner, matchWrap());
        return spinner;
    }

    private EditText field(
            LinearLayout root,
            int labelResource,
            String value,
            int inputType,
            boolean multiline) {
        String label = getString(labelResource);
        TextView labelView = text(label, 14, true);
        labelView.setPadding(0, dp(8), 0, 0);
        root.addView(labelView);
        EditText field = new EditText(this);
        field.setText(value == null ? "" : value);
        field.setInputType(inputType);
        field.setContentDescription(label);
        field.setMinHeight(dp(multiline ? 96 : 48));
        if (multiline) {
            field.setSingleLine(false);
            field.setMinLines(3);
            field.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        } else {
            field.setSingleLine(true);
        }
        root.addView(field, matchWrap());
        return field;
    }

    private CheckBox checkbox(int labelResource, boolean checked) {
        CheckBox checkbox = new CheckBox(this);
        checkbox.setText(labelResource);
        checkbox.setChecked(checked);
        checkbox.setMinHeight(dp(48));
        checkbox.setContentDescription(getString(labelResource));
        return checkbox;
    }

    private Button button(int labelResource, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(labelResource);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setBackgroundResource(R.drawable.ime_key_background);
        button.setTextColor(getColorStateList(R.color.ime_key_text));
        button.setElevation(0f);
        button.setStateListAnimator(null);
        LinearLayout.LayoutParams parameters = matchWrap();
        parameters.topMargin = dp(3);
        parameters.bottomMargin = dp(3);
        button.setLayoutParams(parameters);
        button.setContentDescription(getString(labelResource));
        button.setOnClickListener(listener);
        return button;
    }

    private TextView section(int stringResource) {
        TextView view = text(getString(stringResource), 19, true);
        view.setTextColor(getColor(R.color.ime_primary));
        view.setPadding(0, dp(18), 0, dp(4));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) view.setAccessibilityHeading(true);
        return view;
    }

    private TextView note(int stringResource, int color) {
        TextView view = text(getString(stringResource), 13, false);
        view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(12));
        return view;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout verticalLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private RecognitionBackend selectedBackend() {
        return RecognitionBackend.values()[recognitionBackend.getSelectedItemPosition()];
    }

    private ProcessingMode selectedMode() {
        return ProcessingMode.values()[defaultMode.getSelectedItemPosition()];
    }

    private boolean backendAvailable(RecognitionBackend backend) {
        try {
            return backend == RecognitionBackend.SYSTEM_ON_DEVICE
                    ? SystemSpeechRecognizer.onDeviceAvailable(this)
                    : SystemSpeechRecognizer.systemAvailable(this);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String enumLabel(Object value) {
        if (value instanceof RecognitionBackend backend) {
            return getString(switch (backend) {
                case OPENAI_COMPATIBLE -> R.string.backend_openai;
                case LOCAL_OFFLINE -> R.string.backend_local_offline;
                case DASHSCOPE_STREAMING -> R.string.backend_dashscope_streaming;
                case SYSTEM_ON_DEVICE -> R.string.backend_on_device;
                case SYSTEM_DEFAULT -> R.string.backend_system_default;
            });
        }
        if (value instanceof ProcessingMode mode) {
            return getString(switch (mode) {
                case AUTO -> R.string.mode_auto;
                case VERBATIM -> R.string.mode_verbatim;
                case SMART -> R.string.mode_smart;
                case TRANSLATE -> R.string.mode_translate;
            });
        }
        return value.toString();
    }

    private static String value(EditText field) {
        return field.getText().toString().trim();
    }

    private static String raw(EditText field) {
        return field.getText().toString();
    }

    private static void protectSecretField(EditText field) {
        // API keys use the in-memory retained draft; never let View state or Autofill persist them.
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(value, Math.max(0, size - 1)));
    }

    private static String key(String suffix) {
        return STATE_PREFIX + suffix;
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? getString(R.string.operation_failed)
                : message;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class SimpleSelectionListener implements AdapterView.OnItemSelectedListener {
        private final Runnable callback;

        SimpleSelectionListener(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            callback.run();
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            callback.run();
        }
    }
}
