package com.opentypeless.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodInfo;
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
import com.opentypeless.android.diagnostics.RecognitionDiagnostics;
import com.opentypeless.android.diagnostics.RecognitionDiagnosticsStore;
import com.opentypeless.android.diagnostics.RecognitionRoute;
import com.opentypeless.android.offline.LocalOfflineRecognizer;
import com.opentypeless.android.offline.OfflineModelOperationCoordinator;
import com.opentypeless.android.offline.OfflineModelSpec;
import com.opentypeless.android.offline.OfflineModelStore;
import com.opentypeless.android.offline.OfflinePunctuationModelSpec;
import com.opentypeless.android.offline.OfflinePunctuationModelStore;
import com.opentypeless.android.offline.OfflineStreamingModelSpec;
import com.opentypeless.android.offline.OfflineStreamingModelStore;
import com.opentypeless.android.ime.OpenTypelessImeService;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int MICROPHONE_PERMISSION_REQUEST = 100;
    private static final String STATE_PREFIX = "settings_draft_";
    private static final String STATE_HAS_DRAFT = STATE_PREFIX + "present";
    private static final String STATE_RECOGNITION_ADVANCED =
            STATE_PREFIX + "recognition_advanced";
    private static final String STATE_PROCESSING_ADVANCED =
            STATE_PREFIX + "processing_advanced";

    private SettingsRepository repository;
    private StandardRecognitionSettings standardRecognitionSettings;
    private RecognitionDiagnosticsStore recognitionDiagnosticsStore;
    private AppSettings savedSettings;
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
    private Button grantMicrophoneButton;
    private Button enableKeyboardButton;
    private Button chooseKeyboardButton;
    private TextView lastRecognitionDiagnostics;
    private TextView activeConfigurationSummary;
    private LinearLayout recognitionAdvancedFields;
    private Button recognitionAdvancedToggle;
    private Button processingAdvancedToggle;
    private boolean recognitionAdvancedExpanded;
    private boolean processingAdvancedExpanded;
    private SettingsFormDraft formDraft;
    private SystemRecognitionSupport.Operation supportOperation;
    private RecognitionBackend supportBackend;
    private SystemModelDownloadCoordinator.Subscription systemModelSubscription;
    private boolean languageDownloadAvailable;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService diagnosticsExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "opentypeless-settings-diagnostics");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> systemDiagnosticsTask;
    private SystemRecognitionDiagnostics.Snapshot systemDiagnosticsSnapshot;
    private long systemDiagnosticsUpdatedAt;
    private long systemDiagnosticsGeneration;
    private boolean activityDestroyed;
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
        recognitionDiagnosticsStore = new RecognitionDiagnosticsStore(this);
        AppSettings persisted = repository.load();
        savedSettings = persisted;
        formDraft = draftFromSettings(persisted, standardRecognitionSettings.load());
        recognitionAdvancedExpanded = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_RECOGNITION_ADVANCED, false);
        processingAdvancedExpanded = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_PROCESSING_ADVANCED, false);
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
        outState.putBoolean(STATE_RECOGNITION_ADVANCED, recognitionAdvancedExpanded);
        outState.putBoolean(STATE_PROCESSING_ADVANCED, processingAdvancedExpanded);
        super.onSaveInstanceState(outState);
    }

    private View buildContent(AppSettings settings) {
        LinearLayout page = verticalLayout();
        AppVisualSystem.stylePage(this, page);
        SystemBarInsets.apply(page);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = verticalLayout();
        int padding = dp(20);
        root.setPadding(padding, dp(16), padding, dp(20));
        AppVisualSystem.stylePage(this, root);
        scroll.addView(root);
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        root.addView(AppVisualSystem.backHeader(this, getString(R.string.settings_title)));
        TextView intro = text(getString(R.string.settings_intro), 15, false);
        intro.setTextColor(getColor(R.color.ime_on_surface_variant));
        intro.setPadding(0, dp(8), 0, dp(16));
        root.addView(intro);

        LinearLayout activeCard = card();
        activeCard.addView(section(R.string.section_active_configuration));
        activeConfigurationSummary = note(
                R.string.active_configuration_loading,
                getColor(R.color.ime_on_surface));
        activeConfigurationSummary.setMinHeight(dp(48));
        activeConfigurationSummary.setTextIsSelectable(true);
        activeCard.addView(activeConfigurationSummary);
        root.addView(activeCard, cardParams());

        LinearLayout setupCard = card();
        setupCard.addView(section(R.string.section_enable_keyboard));
        permissionStatus = text("", 14, false);
        permissionStatus.setMinHeight(dp(48));
        permissionStatus.setPadding(0, dp(6), 0, dp(8));
        setupCard.addView(permissionStatus);
        grantMicrophoneButton = button(R.string.grant_microphone, ignored -> requestMicrophone());
        setupCard.addView(grantMicrophoneButton);
        enableKeyboardButton = button(R.string.enable_keyboard, ignored ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        setupCard.addView(enableKeyboardButton);
        chooseKeyboardButton = button(R.string.choose_keyboard, ignored -> {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            manager.showInputMethodPicker();
        });
        setupCard.addView(chooseKeyboardButton);
        root.addView(setupCard, cardParams());

        LinearLayout voiceLabCard = card();
        voiceLabCard.addView(section(R.string.section_voice_lab));
        voiceLabCard.addView(note(
                R.string.settings_voice_lab_intro,
                getColor(R.color.ime_on_surface_variant)));
        lastRecognitionDiagnostics = note(
                R.string.last_recognition_none,
                getColor(R.color.ime_on_surface));
        lastRecognitionDiagnostics.setMinHeight(dp(48));
        lastRecognitionDiagnostics.setTextIsSelectable(true);
        voiceLabCard.addView(lastRecognitionDiagnostics);
        voiceLabCard.addView(button(R.string.open_voice_lab, ignored ->
                startActivity(new Intent(this, VoiceLabActivity.class))));
        root.addView(voiceLabCard, cardParams());

        LinearLayout recognitionCard = card();
        recognitionCard.addView(section(R.string.section_recognition));
        recognitionCard.addView(note(
                R.string.recognition_mode_explanation,
                getColor(R.color.ime_on_surface_variant)));
        recognitionBackend = enumSpinner(
                recognitionCard,
                R.string.backend_label,
                RecognitionBackend.values(),
                settings.recognitionBackend().ordinal());

        systemBackendNote = note(
                R.string.system_backend_note,
                getColor(R.color.ime_on_surface_variant));
        recognitionCard.addView(systemBackendNote);
        systemRouteDiagnostics = note(
                R.string.system_route_inspecting,
                getColor(R.color.ime_on_surface_variant));
        systemRouteDiagnostics.setTextIsSelectable(true);
        recognitionCard.addView(systemRouteDiagnostics);

        recognitionAdvancedToggle = button(
                R.string.show_recognition_advanced,
                ignored -> {
                    recognitionAdvancedExpanded = !recognitionAdvancedExpanded;
                    if (recognitionAdvancedExpanded) ensureRecognitionAdvancedFields();
                    updateAdvancedVisibility();
                });
        recognitionCard.addView(recognitionAdvancedToggle);
        recognitionAdvancedFields = verticalLayout();
        recognitionCard.addView(recognitionAdvancedFields);
        root.addView(recognitionCard, cardParams());

        LinearLayout processingCard = card();
        processingCard.addView(section(R.string.section_processing));
        processingCard.addView(note(
                R.string.processing_mode_explanation,
                getColor(R.color.ime_on_surface_variant)));
        defaultMode = enumSpinner(
                processingCard,
                R.string.default_mode_label,
                ProcessingMode.values(),
                settings.defaultMode().ordinal());
        polishEnabled = checkbox(R.string.polish_enabled, settings.polishEnabled());
        processingCard.addView(polishEnabled);
        processingAdvancedToggle = button(
                R.string.show_processing_advanced,
                ignored -> {
                    processingAdvancedExpanded = !processingAdvancedExpanded;
                    if (processingAdvancedExpanded) ensureProcessingAdvancedFields();
                    updateAdvancedVisibility();
                });
        processingCard.addView(processingAdvancedToggle);
        llmFields = verticalLayout();
        processingCard.addView(llmFields);
        root.addView(processingCard, cardParams());

        LinearLayout privacyCard = card();
        privacyCard.addView(section(R.string.section_privacy));
        personalizationEnabled = checkbox(
                R.string.personalization_enabled,
                settings.personalizationEnabled());
        historyEnabled = checkbox(R.string.history_enabled, settings.historyEnabled());
        sendContext = checkbox(R.string.send_context_enabled, settings.sendContext());
        privacyCard.addView(personalizationEnabled);
        privacyCard.addView(historyEnabled);
        privacyCard.addView(sendContext);
        privacyCard.addView(note(R.string.privacy_note, getColor(R.color.ime_on_surface_variant)));
        privacyCard.addView(note(R.string.local_http_note, getColor(R.color.ime_warning)));
        root.addView(privacyCard, cardParams());

        LinearLayout manageCard = card();
        manageCard.addView(section(R.string.section_manage));
        manageCard.addView(note(
                R.string.manage_data_explanation,
                getColor(R.color.ime_on_surface_variant)));
        manageCard.addView(button(R.string.manage_dictionary, ignored ->
                startActivity(new Intent(this, DictionaryActivity.class))));
        manageCard.addView(button(R.string.manage_history, ignored ->
                startActivity(new Intent(this, HistoryActivity.class))));
        manageCard.addView(button(R.string.manage_app_profiles, ignored ->
                startActivity(new Intent(this, AppProfileActivity.class))));
        manageCard.addView(button(R.string.legal_notices, ignored -> showLegalNotices()));
        root.addView(manageCard, cardParams());

        LinearLayout saveBar = verticalLayout();
        saveBar.setPadding(padding, dp(8), padding, dp(10));
        saveBar.setBackgroundColor(getColor(R.color.ime_surface_container));
        Button save = button(R.string.save_configuration, ignored -> saveSettings());
        save.setBackgroundResource(R.drawable.ime_primary_key_background);
        save.setTextColor(getColorStateList(R.color.ime_primary_key_text));
        saveBar.addView(save);
        page.addView(saveBar, matchWrap());

        recognitionBackend.setOnItemSelectedListener(new SimpleSelectionListener(this::updateVisibility));
        polishEnabled.setOnCheckedChangeListener((ignored, checked) -> updateVisibility());
        if (recognitionAdvancedExpanded) ensureRecognitionAdvancedFields();
        if (processingAdvancedExpanded) ensureProcessingAdvancedFields();
        updateVisibility();
        refreshRecognitionDiagnostics();
        renderActiveConfiguration();
        return page;
    }

    /** Builds the large provider form only when the user asks for it. */
    private void ensureRecognitionAdvancedFields() {
        if (language != null) return;
        SettingsFormDraft draft = formDraft;
        language = field(
                recognitionAdvancedFields,
                R.string.language_label,
                draft.language(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                false);
        maxRecordingSeconds = field(
                recognitionAdvancedFields,
                R.string.max_recording_label,
                draft.maxRecordingSeconds(),
                InputType.TYPE_CLASS_NUMBER,
                false);

        networkSttFields = verticalLayout();
        batchSttFields = verticalLayout();
        sttBaseUrl = field(
                batchSttFields,
                R.string.stt_base_url_label,
                draft.sttBaseUrl(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                false);
        sttApiKey = field(
                batchSttFields,
                R.string.stt_api_key_label,
                draft.sttApiKey(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                false);
        protectSecretField(sttApiKey);
        sttModel = field(
                batchSttFields,
                R.string.stt_model_label,
                draft.sttModel(),
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
                draft.streamingBaseUrl(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                false);
        streamingApiKey = field(
                streamingSttFields,
                R.string.streaming_api_key_label,
                draft.streamingApiKey(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                false);
        protectSecretField(streamingApiKey);
        streamingModel = field(
                streamingSttFields,
                R.string.streaming_model_label,
                draft.streamingModel(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                false);
        streamingVocabularyId = field(
                streamingSttFields,
                R.string.streaming_vocabulary_id_label,
                draft.streamingVocabularyId(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                false);
        networkSttFields.addView(streamingSttFields);
        recognitionAdvancedFields.addView(networkSttFields);

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
        recognitionAdvancedFields.addView(localOfflineFields);

        languageSupportStatus = note(
                R.string.language_support_not_checked,
                getColor(R.color.ime_on_surface_variant));
        languageSupportStatus.setMinHeight(dp(48));
        recognitionAdvancedFields.addView(languageSupportStatus);
        checkLanguageSupport = button(
                R.string.check_language_support,
                ignored -> checkLanguageSupport());
        recognitionAdvancedFields.addView(checkLanguageSupport);
        downloadLanguageModel = button(
                R.string.download_language_model,
                ignored -> downloadLanguageModel());
        downloadLanguageModel.setVisibility(View.GONE);
        recognitionAdvancedFields.addView(downloadLanguageModel);

        recognitionAdvancedFields.addView(section(R.string.section_standard_speech));
        standardSpeechEnabled = checkbox(
                R.string.standard_speech_enabled,
                draft.standardSpeechEnabled());
        recognitionAdvancedFields.addView(standardSpeechEnabled);
        standardSpeechCallers = field(
                recognitionAdvancedFields,
                R.string.standard_speech_callers_label,
                draft.standardSpeechCallers(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                true);
        standardSpeechCallers.setHint(R.string.standard_speech_callers_hint);
        recognitionAdvancedFields.addView(note(
                R.string.standard_speech_security_note,
                getColor(R.color.ime_warning)));
        language.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) {
                if (!applyingDraft && supportBackend != null) resetLanguageSupportState();
            }
        });
    }

    /** Builds endpoint and instruction controls only after progressive disclosure is expanded. */
    private void ensureProcessingAdvancedFields() {
        if (llmBaseUrl != null) return;
        SettingsFormDraft draft = formDraft;
        llmBaseUrl = field(
                llmFields,
                R.string.llm_base_url_label,
                draft.llmBaseUrl(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                false);
        llmApiKey = field(
                llmFields,
                R.string.llm_api_key_label,
                draft.llmApiKey(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                false);
        protectSecretField(llmApiKey);
        llmModel = field(
                llmFields,
                R.string.llm_model_label,
                draft.llmModel(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                false);
        translationFields = verticalLayout();
        targetLanguage = field(
                translationFields,
                R.string.target_language_label,
                draft.targetLanguage(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                false);
        llmFields.addView(translationFields);
        customInstructions = field(
                llmFields,
                R.string.custom_instructions_label,
                draft.customInstructions(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                true);
        customInstructions.setHint(R.string.custom_instructions_hint);
    }

    private static SettingsFormDraft draftFromSettings(
            AppSettings settings,
            StandardRecognitionSettings.Snapshot standardSpeech) {
        return new SettingsFormDraft(
                settings.recognitionBackend().ordinal(),
                settings.defaultMode().ordinal(),
                settings.language(),
                Integer.toString(settings.boundedMaxRecordingSeconds()),
                settings.sttBaseUrl(),
                settings.sttApiKey(),
                settings.sttModel(),
                settings.streamingBaseUrl(),
                settings.streamingApiKey(),
                settings.streamingModel(),
                settings.streamingVocabularyId(),
                standardSpeech.enabled(),
                standardSpeech.packagesAsText(),
                settings.polishEnabled(),
                settings.llmBaseUrl(),
                settings.llmApiKey(),
                settings.llmModel(),
                settings.targetLanguage(),
                settings.customInstructions(),
                settings.personalizationEnabled(),
                settings.historyEnabled(),
                settings.sendContext());
    }

    private void showLegalNotices() {
        String notices;
        try {
            notices = readRawText(R.raw.legal_notices)
                    + "\n\n"
                    + readRawText(R.raw.offline_asr_runtime_licenses)
                    + "\n\n"
                    + readRawText(R.raw.native_engine_licenses);
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
        OfflineStreamingModelStore.Status streamingStatus =
                OfflineStreamingModelStore.status(this);
        OfflinePunctuationModelStore.Status punctuationStatus =
                OfflinePunctuationModelStore.status(this);
        boolean supported = LocalOfflineRecognizer.isSupportedDevice(this);
        boolean qualityInstalled = status == OfflineModelStore.Status.INSTALLED;
        boolean streamingInstalled = streamingStatus
                == OfflineStreamingModelStore.Status.INSTALLED;
        boolean punctuationInstalled = punctuationStatus
                == OfflinePunctuationModelStore.Status.INSTALLED;
        int message;
        if (!supported) {
            message = R.string.offline_model_unsupported_low_memory;
        } else if (status == OfflineModelStore.Status.MISSING) {
            message = R.string.offline_model_missing;
        } else if (status == OfflineModelStore.Status.CORRUPT) {
            message = R.string.offline_model_corrupt;
        } else if (streamingStatus == OfflineStreamingModelStore.Status.CORRUPT) {
            message = R.string.offline_preview_model_corrupt;
        } else if (punctuationStatus == OfflinePunctuationModelStore.Status.CORRUPT) {
            message = R.string.offline_punctuation_model_corrupt;
        } else if (!streamingInstalled) {
            message = R.string.offline_model_quality_only;
        } else if (!punctuationInstalled) {
            message = R.string.offline_model_punctuation_missing;
        } else {
            message = R.string.offline_model_installed;
        }
        localModelStatus.setText(message);
        localModelStatus.setTextColor(
                supported && qualityInstalled && streamingInstalled && punctuationInstalled
                ? getColor(R.color.ime_primary)
                : supported && qualityInstalled
                ? getColor(R.color.ime_warning)
                : getColor(R.color.ime_error));
        localModelStatus.setContentDescription(localModelStatus.getText());
        downloadOfflineModel.setText(
                qualityInstalled && streamingInstalled && !punctuationInstalled
                        ? R.string.download_offline_punctuation
                        : qualityInstalled && !streamingInstalled
                                ? R.string.download_offline_live_preview
                                : R.string.download_offline_model);
        downloadOfflineModel.setEnabled(supported
                && (!qualityInstalled || !streamingInstalled || !punctuationInstalled));
        deleteOfflineModel.setVisibility(status == OfflineModelStore.Status.MISSING
                && streamingStatus == OfflineStreamingModelStore.Status.MISSING
                && punctuationStatus == OfflinePunctuationModelStore.Status.MISSING
                ? View.GONE
                : View.VISIBLE);
    }

    private void confirmOfflineModelDownload() {
        if (OfflineModelOperationCoordinator.snapshot().running()) return;
        OfflineModelSpec quality = OfflineModelSpec.QUALITY;
        OfflineStreamingModelSpec streaming = OfflineStreamingModelSpec.REALTIME;
        OfflinePunctuationModelSpec punctuation = OfflinePunctuationModelSpec.ZH_EN;
        boolean needsQuality = OfflineModelStore.status(this)
                != OfflineModelStore.Status.INSTALLED;
        boolean needsStreaming = OfflineStreamingModelStore.status(this)
                != OfflineStreamingModelStore.Status.INSTALLED;
        boolean needsPunctuation = OfflinePunctuationModelStore.status(this)
                != OfflinePunctuationModelStore.Status.INSTALLED;
        long missingBytes = (needsQuality ? quality.downloadBytes() : 0L)
                + (needsStreaming ? streaming.downloadBytes() : 0L)
                + (needsPunctuation ? punctuation.downloadBytes() : 0L);
        java.util.ArrayList<String> modelNames = new java.util.ArrayList<>();
        java.util.ArrayList<String> revisionsList = new java.util.ArrayList<>();
        if (needsQuality) {
            modelNames.add(quality.displayName());
            revisionsList.add(quality.revision());
        }
        if (needsStreaming) {
            modelNames.add(streaming.displayName());
            revisionsList.add(streaming.revision());
        }
        if (needsPunctuation) {
            modelNames.add(punctuation.displayName());
            revisionsList.add(punctuation.revision());
        }
        String models = String.join(" + ", modelNames);
        String revisions = String.join(" / ", revisionsList);
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_offline_model_title)
                .setMessage(getString(
                        R.string.download_offline_model_confirmation,
                        models,
                        missingBytes / (1024L * 1024L),
                        revisions))
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
        if (networkSttFields != null) {
            networkSttFields.setVisibility(network ? View.VISIBLE : View.GONE);
            batchSttFields.setVisibility(batch ? View.VISIBLE : View.GONE);
            streamingSttFields.setVisibility(streaming ? View.VISIBLE : View.GONE);
            localOfflineFields.setVisibility(local ? View.VISIBLE : View.GONE);
        }
        systemBackendNote.setVisibility(system ? View.VISIBLE : View.GONE);
        systemRouteDiagnostics.setVisibility(system ? View.VISIBLE : View.GONE);
        if (languageSupportStatus != null) {
            languageSupportStatus.setVisibility(system ? View.VISIBLE : View.GONE);
            checkLanguageSupport.setVisibility(system ? View.VISIBLE : View.GONE);
            downloadLanguageModel.setVisibility(system && languageDownloadAvailable
                    ? View.VISIBLE
                    : View.GONE);
        }
        if (local && localModelStatus != null) refreshOfflineModelStatus();
        if (system) {
            refreshSystemRouteDiagnostics(backend);
        }
        updateAdvancedVisibility();
        if (translationFields != null) translationFields.setVisibility(View.VISIBLE);
    }

    private void updateAdvancedVisibility() {
        if (recognitionAdvancedExpanded) ensureRecognitionAdvancedFields();
        if (processingAdvancedExpanded) ensureProcessingAdvancedFields();
        if (recognitionAdvancedFields != null) {
            recognitionAdvancedFields.setVisibility(
                    recognitionAdvancedExpanded ? View.VISIBLE : View.GONE);
        }
        if (recognitionAdvancedToggle != null) {
            recognitionAdvancedToggle.setText(recognitionAdvancedExpanded
                    ? R.string.hide_recognition_advanced
                    : R.string.show_recognition_advanced);
            recognitionAdvancedToggle.setContentDescription(
                    recognitionAdvancedToggle.getText());
        }
        boolean processingEnabled = polishEnabled != null && polishEnabled.isChecked();
        if (processingAdvancedToggle != null) {
            processingAdvancedToggle.setVisibility(processingEnabled ? View.VISIBLE : View.GONE);
            processingAdvancedToggle.setText(processingAdvancedExpanded
                    ? R.string.hide_processing_advanced
                    : R.string.show_processing_advanced);
            processingAdvancedToggle.setContentDescription(
                    processingAdvancedToggle.getText());
        }
        if (llmFields != null) {
            llmFields.setVisibility(processingEnabled && processingAdvancedExpanded
                    ? View.VISIBLE
                    : View.GONE);
        }
    }

    private void refreshSystemRouteDiagnostics(RecognitionBackend backend) {
        SystemRecognitionDiagnostics.Snapshot cached = systemDiagnosticsSnapshot;
        if (cached != null) renderSystemRouteDiagnostics(backend, cached);
        else {
            systemBackendNote.setText(R.string.system_route_inspecting);
            systemBackendNote.setTextColor(getColor(R.color.ime_on_surface_variant));
            systemRouteDiagnostics.setText(R.string.system_route_inspecting);
            systemRouteDiagnostics.setTextColor(getColor(R.color.ime_on_surface_variant));
        }

        long now = SystemClock.elapsedRealtime();
        if (systemDiagnosticsTask != null && !systemDiagnosticsTask.isDone()) return;
        if (cached != null && now - systemDiagnosticsUpdatedAt < 2_000L) return;
        long request = ++systemDiagnosticsGeneration;
        systemDiagnosticsTask = diagnosticsExecutor.submit(() -> {
            SystemRecognitionDiagnostics.Snapshot diagnostics =
                    SystemRecognitionDiagnostics.inspect(getApplicationContext());
            mainHandler.post(() -> {
                if (activityDestroyed || request != systemDiagnosticsGeneration) return;
                systemDiagnosticsTask = null;
                systemDiagnosticsSnapshot = diagnostics;
                systemDiagnosticsUpdatedAt = SystemClock.elapsedRealtime();
                if (recognitionBackend == null) return;
                RecognitionBackend selected = selectedBackend();
                if (selected == RecognitionBackend.SYSTEM_ON_DEVICE
                        || selected == RecognitionBackend.SYSTEM_DEFAULT) {
                    renderSystemRouteDiagnostics(selected, diagnostics);
                }
            });
        });
    }

    private void renderSystemRouteDiagnostics(
            RecognitionBackend backend,
            SystemRecognitionDiagnostics.Snapshot diagnostics) {
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
        boolean available = backendAvailable(diagnostics, backend);
        systemRouteDiagnostics.setTextColor(getColor(available
                ? R.color.ime_on_surface_variant
                : R.color.ime_error));
        int statusResource = backend == RecognitionBackend.SYSTEM_ON_DEVICE
                ? (available ? R.string.on_device_available : R.string.on_device_unavailable)
                : (available ? R.string.system_speech_available : R.string.system_speech_unavailable);
        systemBackendNote.setText(getString(
                R.string.system_backend_status,
                getString(statusResource),
                getString(R.string.system_backend_note)));
        systemBackendNote.setTextColor(getColor(available
                ? R.color.ime_primary
                : R.color.ime_error));
        systemBackendNote.setContentDescription(systemBackendNote.getText());
    }

    private void checkLanguageSupport() {
        if (selectedBackend() != RecognitionBackend.SYSTEM_ON_DEVICE
                && selectedBackend() != RecognitionBackend.SYSTEM_DEFAULT) return;
        cancelLanguageOperations();
        languageDownloadAvailable = false;
        checkLanguageSupport.setEnabled(false);
        downloadLanguageModel.setVisibility(View.GONE);
        languageSupportStatus.setText(R.string.language_support_checking);
        languageSupportStatus.setTextColor(getColor(R.color.ime_on_surface_variant));
        SystemRecognitionSupport.Operation[] request = new SystemRecognitionSupport.Operation[1];
        request[0] = SystemSpeechRecognizer.checkRecognitionSupport(
                this,
                languageSupportSettings(),
                PersonalizationSnapshot.empty(),
                result -> {
                    if (supportOperation != request[0] || isFinishing() || isDestroyed()) return;
                    supportOperation = null;
                    showLanguageSupport(result);
                });
        supportOperation = request[0];
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
            case ERROR -> getString(R.string.language_support_check_failed);
        };
    }

    private String downloadResultText(SystemRecognitionSupport.DownloadResult result) {
        return switch (result.status()) {
            case REQUESTED -> getString(R.string.language_model_download_requested);
            case SCHEDULED -> getString(R.string.language_model_download_scheduled);
            case COMPLETED -> getString(R.string.language_model_download_completed);
            case API_UNAVAILABLE -> getString(R.string.language_model_download_api_unavailable);
            case FAILED -> getString(R.string.language_model_download_failed);
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
        if (supportOperation != null) supportOperation.cancel();
        supportOperation = null;
        SystemModelDownloadCoordinator.cancel();
    }

    private void saveSettings() {
        try {
            SettingsFormDraft draft = captureDraft();
            StandardRecognitionSettings.Snapshot standardSpeech =
                    standardRecognitionSettings.validate(
                            draft.standardSpeechEnabled(),
                            draft.standardSpeechCallers().trim());
            RecognitionBackend backend = selectedBackend();
            if (backend == RecognitionBackend.OPENAI_COMPATIBLE) {
                String endpoint = EndpointNormalizer.endpoint(
                        draft.sttBaseUrl(),
                        "audio/transcriptions");
                EndpointNormalizer.requireCredentialSafeTransport(
                        endpoint,
                        draft.sttApiKey().trim());
                if (draft.sttModel().trim().isEmpty()) {
                    throw new IllegalArgumentException(getString(R.string.stt_model_required));
                }
            } else if (backend == RecognitionBackend.LOCAL_OFFLINE
                    && (!LocalOfflineRecognizer.isSupportedDevice(this)
                    || !LocalOfflineRecognizer.isInstalled(this))) {
                throw new IllegalArgumentException(getString(R.string.offline_model_required));
            } else if (backend == RecognitionBackend.DASHSCOPE_STREAMING) {
                EndpointNormalizer.dashScopeWebSocket(draft.streamingBaseUrl().trim());
                if (draft.streamingApiKey().trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            getString(R.string.streaming_api_key_required));
                }
                if (draft.streamingModel().trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            getString(R.string.streaming_model_required));
                }
            } else if (backend == RecognitionBackend.SYSTEM_ON_DEVICE
                    && !requireKnownBackendAvailable(backend)) {
                throw new IllegalArgumentException(getString(R.string.on_device_unavailable));
            } else if (backend == RecognitionBackend.SYSTEM_DEFAULT
                    && !requireKnownBackendAvailable(backend)) {
                throw new IllegalArgumentException(getString(R.string.system_speech_unavailable));
            }
            if (draft.polishEnabled()) {
                String endpoint = EndpointNormalizer.endpoint(
                        draft.llmBaseUrl(),
                        "chat/completions");
                EndpointNormalizer.requireCredentialSafeTransport(
                        endpoint,
                        draft.llmApiKey().trim());
                if (draft.llmModel().trim().isEmpty()) {
                    throw new IllegalArgumentException(getString(R.string.llm_model_required));
                }
            }
            int maximumSeconds;
            try {
                maximumSeconds = Integer.parseInt(draft.maxRecordingSeconds().trim());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(getString(R.string.invalid_recording_length));
            }
            if (maximumSeconds < 5 || maximumSeconds > 540) {
                throw new IllegalArgumentException(getString(R.string.invalid_recording_length));
            }

            AppSettings proposed = new AppSettings(
                    backend,
                    draft.sttBaseUrl().trim(),
                    draft.sttApiKey().trim(),
                    draft.sttModel().trim(),
                    draft.streamingBaseUrl().trim(),
                    draft.streamingApiKey().trim(),
                    draft.streamingModel().trim(),
                    draft.streamingVocabularyId().trim(),
                    draft.language().trim(),
                    selectedMode(),
                    draft.polishEnabled(),
                    draft.llmBaseUrl().trim(),
                    draft.llmApiKey().trim(),
                    draft.llmModel().trim(),
                    draft.targetLanguage().trim(),
                    draft.customInstructions().trim(),
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
            savedSettings = proposed;
            formDraft = draftFromSettings(proposed, standardSpeech);
            renderActiveConfiguration();
            refreshPermissionStatus();
            Toast.makeText(this, R.string.configuration_saved, Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException error) {
            Toast.makeText(this, safeMessage(error), Toast.LENGTH_LONG).show();
        } catch (RuntimeException error) {
            // Unexpected platform failures are reported without their possibly sensitive message.
            Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_LONG).show();
        }
    }

    private SettingsFormDraft captureDraft() {
        SettingsFormDraft previous = formDraft;
        SettingsFormDraft captured = new SettingsFormDraft(
                recognitionBackend.getSelectedItemPosition(),
                defaultMode.getSelectedItemPosition(),
                language == null ? previous.language() : raw(language),
                maxRecordingSeconds == null
                        ? previous.maxRecordingSeconds()
                        : raw(maxRecordingSeconds),
                sttBaseUrl == null ? previous.sttBaseUrl() : raw(sttBaseUrl),
                sttApiKey == null ? previous.sttApiKey() : raw(sttApiKey),
                sttModel == null ? previous.sttModel() : raw(sttModel),
                streamingBaseUrl == null ? previous.streamingBaseUrl() : raw(streamingBaseUrl),
                streamingApiKey == null ? previous.streamingApiKey() : raw(streamingApiKey),
                streamingModel == null ? previous.streamingModel() : raw(streamingModel),
                streamingVocabularyId == null
                        ? previous.streamingVocabularyId()
                        : raw(streamingVocabularyId),
                standardSpeechEnabled == null
                        ? previous.standardSpeechEnabled()
                        : standardSpeechEnabled.isChecked(),
                standardSpeechCallers == null
                        ? previous.standardSpeechCallers()
                        : raw(standardSpeechCallers),
                polishEnabled.isChecked(),
                llmBaseUrl == null ? previous.llmBaseUrl() : raw(llmBaseUrl),
                llmApiKey == null ? previous.llmApiKey() : raw(llmApiKey),
                llmModel == null ? previous.llmModel() : raw(llmModel),
                targetLanguage == null ? previous.targetLanguage() : raw(targetLanguage),
                customInstructions == null
                        ? previous.customInstructions()
                        : raw(customInstructions),
                personalizationEnabled.isChecked(),
                historyEnabled.isChecked(),
                sendContext.isChecked());
        formDraft = captured;
        return captured;
    }

    private void applyDraft(SettingsFormDraft draft) {
        formDraft = draft;
        recognitionBackend.setSelection(clamp(
                draft.recognitionBackendIndex(), RecognitionBackend.values().length));
        defaultMode.setSelection(clamp(draft.defaultModeIndex(), ProcessingMode.values().length));
        polishEnabled.setChecked(draft.polishEnabled());
        if (language != null) {
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
        }
        if (llmBaseUrl != null) {
            llmBaseUrl.setText(draft.llmBaseUrl());
            llmApiKey.setText(draft.llmApiKey());
            llmModel.setText(draft.llmModel());
            targetLanguage.setText(draft.targetLanguage());
            customInstructions.setText(draft.customInstructions());
        }
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
        renderActiveConfiguration();
        refreshRecognitionDiagnostics();
    }

    @Override
    protected void onStart() {
        super.onStart();
        OfflineModelOperationCoordinator.addListener(offlineModelListener);
        if (systemModelSubscription != null) systemModelSubscription.close();
        systemModelSubscription = SystemModelDownloadCoordinator.subscribe(systemModelListener);
    }

    @Override
    protected void onStop() {
        OfflineModelOperationCoordinator.removeListener(offlineModelListener);
        if (systemModelSubscription != null) systemModelSubscription.close();
        systemModelSubscription = null;
        // A platform language-model download may temporarily launch system approval UI. Keep it
        // alive while this Activity is merely stopped. A support check has no user interaction and
        // can be safely abandoned. OpenTypeless model transfers are application-scoped separately.
        if (supportOperation != null) {
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
        activityDestroyed = true;
        systemDiagnosticsGeneration++;
        if (systemDiagnosticsTask != null) systemDiagnosticsTask.cancel(true);
        systemDiagnosticsTask = null;
        diagnosticsExecutor.shutdownNow();
        if (supportOperation != null) supportOperation.cancel();
        supportOperation = null;
        super.onDestroy();
    }

    private void refreshPermissionStatus() {
        boolean granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
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
        String selectedValue = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        android.content.ComponentName selected = selectedValue == null
                ? null
                : android.content.ComponentName.unflattenFromString(selectedValue);
        boolean selectedHere = selected != null
                && selected.getPackageName().equals(getPackageName())
                && selected.getClassName().equals(OpenTypelessImeService.class.getName());
        boolean backendReady = savedSettings != null && savedSettings.isReady();
        RecognitionDiagnostics.Snapshot latest = recognitionDiagnosticsStore == null
                ? null
                : recognitionDiagnosticsStore.load();
        boolean testPassed = savedSettings != null && SetupChecklist.successfulTestMatches(
                savedSettings.recognitionBackend(),
                savedSettings.language(),
                latest);
        boolean complete = granted && enabled && selectedHere && backendReady && testPassed;
        permissionStatus.setText(complete
                ? getString(R.string.setup_complete)
                : getString(
                        R.string.setup_status_summary,
                        getString(granted ? R.string.setup_done : R.string.setup_pending),
                        getString(enabled ? R.string.setup_done : R.string.setup_pending),
                        getString(selectedHere ? R.string.setup_done : R.string.setup_pending),
                        getString(backendReady ? R.string.setup_done : R.string.setup_pending),
                        getString(testPassed ? R.string.setup_done : R.string.setup_pending)));
        permissionStatus.setTextColor(getColor(
                complete ? R.color.ime_primary : R.color.ime_warning));
        permissionStatus.setContentDescription(permissionStatus.getText());
        grantMicrophoneButton.setVisibility(granted ? View.GONE : View.VISIBLE);
        enableKeyboardButton.setVisibility(enabled ? View.GONE : View.VISIBLE);
        chooseKeyboardButton.setVisibility(selectedHere ? View.GONE : View.VISIBLE);
    }

    private void refreshRecognitionDiagnostics() {
        if (lastRecognitionDiagnostics == null || recognitionDiagnosticsStore == null) return;
        RecognitionDiagnostics.Snapshot snapshot = recognitionDiagnosticsStore.load();
        if (snapshot == null) {
            lastRecognitionDiagnostics.setText(R.string.last_recognition_none);
            return;
        }
        RecognitionRoute route = snapshot.route();
        String fallback = route.fellBack()
                ? getString(
                        R.string.voice_lab_route_fallback,
                        fallbackLabel(route.fallbackReason()))
                : getString(R.string.voice_lab_route_no_fallback);
        lastRecognitionDiagnostics.setText(getString(
                R.string.last_recognition_summary,
                enumLabel(route.selectedBackend()),
                enumLabel(route.actualBackend()),
                privacyLabel(route.privacyBoundary()),
                fallback,
                diagnosticsStatus(snapshot.status()),
                metric(snapshot.readyLatencyMs()),
                metric(snapshot.firstPartialLatencyMs()),
                metric(snapshot.terminalLatencyMs())));
    }

    private void renderActiveConfiguration() {
        if (activeConfigurationSummary == null || savedSettings == null) return;
        RecognitionRoute route = RecognitionRoute.direct(savedSettings.recognitionBackend());
        activeConfigurationSummary.setText(getString(
                R.string.active_configuration_summary,
                enumLabel(savedSettings.recognitionBackend()),
                enumLabel(savedSettings.defaultMode()),
                privacyLabel(route.privacyBoundary())));
        activeConfigurationSummary.setContentDescription(activeConfigurationSummary.getText());
    }

    private String privacyLabel(RecognitionRoute.PrivacyBoundary boundary) {
        return getString(switch (boundary) {
            case ON_DEVICE -> R.string.voice_lab_privacy_on_device;
            case PROVIDER_DEPENDENT -> R.string.voice_lab_privacy_provider_dependent;
            case NETWORK -> R.string.voice_lab_privacy_network;
        });
    }

    private String fallbackLabel(RecognitionRoute.FallbackReason reason) {
        return getString(switch (reason) {
            case NONE -> R.string.voice_lab_fallback_none;
            case ANDROID_MICROPHONE_BLOCKED -> R.string.voice_lab_fallback_android_microphone;
        });
    }

    private String diagnosticsStatus(RecognitionDiagnostics.Status status) {
        return getString(switch (status) {
            case ACTIVE -> R.string.voice_lab_status_active;
            case SUCCEEDED -> R.string.voice_lab_status_succeeded;
            case FAILED -> R.string.voice_lab_status_failed;
            case CANCELLED -> R.string.voice_lab_status_cancelled;
        });
    }

    private static String metric(long value) {
        return value < 0L ? "—" : value + " ms";
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
        Button button = AppVisualSystem.secondaryButton(this, labelResource, listener);
        LinearLayout.LayoutParams parameters = matchWrap();
        parameters.topMargin = dp(3);
        parameters.bottomMargin = dp(3);
        button.setLayoutParams(parameters);
        return button;
    }

    private TextView section(int stringResource) {
        return AppVisualSystem.section(this, getString(stringResource));
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

    private LinearLayout card() {
        return AppVisualSystem.card(this);
    }

    private LinearLayout.LayoutParams cardParams() {
        return AppVisualSystem.cardParams(this);
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

    private boolean requireKnownBackendAvailable(RecognitionBackend backend) {
        SystemRecognitionDiagnostics.Snapshot snapshot = systemDiagnosticsSnapshot;
        if (snapshot == null) {
            refreshSystemRouteDiagnostics(backend);
            throw new IllegalArgumentException(getString(R.string.system_route_inspecting));
        }
        return backendAvailable(snapshot, backend);
    }

    private static boolean backendAvailable(
            SystemRecognitionDiagnostics.Snapshot snapshot,
            RecognitionBackend backend) {
        return backend == RecognitionBackend.SYSTEM_ON_DEVICE
                ? snapshot.onDeviceAvailable()
                : snapshot.systemAvailable();
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
