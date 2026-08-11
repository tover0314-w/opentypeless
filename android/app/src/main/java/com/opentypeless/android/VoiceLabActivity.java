package com.opentypeless.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.diagnostics.RecognitionDiagnostics;
import com.opentypeless.android.diagnostics.RecognitionDiagnosticsJson;
import com.opentypeless.android.diagnostics.RecognitionDiagnosticsStore;
import com.opentypeless.android.diagnostics.RecognitionRoute;
import com.opentypeless.android.diagnostics.SpeechCoreShadowEvaluator;
import com.opentypeless.android.diagnostics.SpeechCoreShadowSnapshot;
import com.opentypeless.android.diagnostics.VoiceLabScorer;
import com.opentypeless.android.diagnostics.VoiceLabPerformanceProbe;
import com.opentypeless.android.ime.DictationRequest;
import com.opentypeless.android.ime.DictationResult;
import com.opentypeless.android.ime.TranscriptUpdate;
import com.opentypeless.android.ime.VoicePipeline;
import com.opentypeless.android.offline.OfflineStreamingRecognizer;
import com.opentypeless.android.recognition.SystemRecognitionDiagnostics;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.settings.SettingsRepository;
import com.opentypeless.android.speech.engine.ProcessingLocation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Locale;

/** A local, transcript-free quick check that exercises the same pipeline as the IME. */
public final class VoiceLabActivity extends Activity {
    private static final int MICROPHONE_PERMISSION_REQUEST = 201;
    private static final String STATE_PROMPT_INDEX = "voice_lab_prompt_index";
    private static final String STATE_ATTEMPTS = "voice_lab_attempts";
    private static final String STATE_SUCCESSES = "voice_lab_successes";
    private static final String STATE_EXACT_MATCHES = "voice_lab_exact_matches";
    private static final String[] CHINESE_PROMPTS = {"没问题", "知道了", "稍等一下"};
    private static final String[] ENGLISH_PROMPTS = {"Yes", "Sounds good", "No problem"};

    private final ExecutorService settingsExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;
    private VoicePipeline pipeline;
    private RecognitionDiagnosticsStore diagnosticsStore;
    private VoiceLabPerformanceProbe performanceProbe;
    private AppSettings settings;
    private RecognitionRoute route;
    private SystemRecognitionDiagnostics.Snapshot systemDiagnostics;
    private TextView statusView;
    private TextView routeView;
    private TextView promptView;
    private TextView liveView;
    private TextView finalView;
    private TextView shadowView;
    private TextView metricsView;
    private TextView performanceView;
    private TextView progressView;
    private Button holdButton;
    private Button nextButton;
    private Button exportButton;
    private Button permissionButton;
    private String[] prompts = CHINESE_PROMPTS;
    private int promptIndex;
    private int attempts;
    private int successes;
    private int exactMatches;
    private boolean holding;
    private boolean recognitionActive;
    private boolean microphoneReady;
    private boolean suppressTouchGeneratedClick;
    private long releaseAtMs;
    private long rawFinalAtMs;
    private long completedAtMs;
    private long attemptGeneration;
    private long activeAttempt;
    private SpeechCoreShadowEvaluator shadowEvaluator;
    private boolean productionV2Observed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (savedInstanceState != null) {
            promptIndex = Math.max(0, savedInstanceState.getInt(STATE_PROMPT_INDEX, 0));
            attempts = Math.max(0, savedInstanceState.getInt(STATE_ATTEMPTS, 0));
            successes = Math.max(0, savedInstanceState.getInt(STATE_SUCCESSES, 0));
            exactMatches = Math.max(0, savedInstanceState.getInt(STATE_EXACT_MATCHES, 0));
        }
        pipeline = new VoicePipeline(this);
        diagnosticsStore = new RecognitionDiagnosticsStore(this);
        performanceProbe = new VoiceLabPerformanceProbe(this);
        setContentView(buildContent());
        renderPermission();
        loadSettings();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_PROMPT_INDEX, promptIndex);
        outState.putInt(STATE_ATTEMPTS, attempts);
        outState.putInt(STATE_SUCCESSES, successes);
        outState.putInt(STATE_EXACT_MATCHES, exactMatches);
        super.onSaveInstanceState(outState);
    }

    @SuppressLint("ClickableViewAccessibility") // Touch release calls performClick; TalkBack has a click toggle.
    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        SystemBarInsets.apply(scroll);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        AppVisualSystem.stylePage(this, root);
        scroll.addView(root);

        root.addView(AppVisualSystem.backHeader(this, getString(R.string.voice_lab_title)));
        TextView intro = text(getString(R.string.voice_lab_intro), 15, false);
        intro.setTextColor(getColor(R.color.ime_on_surface_variant));
        intro.setPadding(0, dp(8), 0, dp(14));
        root.addView(intro);

        routeView = cardText(getString(R.string.voice_lab_loading_settings), 14);
        routeView.setTextIsSelectable(true);
        root.addView(routeView);

        statusView = text(getString(R.string.voice_lab_loading_settings), 15, true);
        statusView.setMinHeight(dp(48));
        statusView.setPadding(0, dp(16), 0, dp(8));
        statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(statusView);

        promptView = text("", 25, true);
        promptView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        promptView.setPadding(dp(12), dp(18), dp(12), dp(18));
        promptView.setBackgroundResource(R.drawable.app_card_background);
        root.addView(promptView, matchWrap());

        holdButton = button(R.string.voice_lab_hold_to_record);
        holdButton.setMinHeight(dp(76));
        holdButton.setTextSize(18);
        holdButton.setBackgroundResource(R.drawable.ime_primary_key_background);
        holdButton.setTextColor(getColorStateList(R.color.ime_primary_key_text));
        holdButton.setEnabled(false);
        holdButton.setOnClickListener(ignored -> {
            if (suppressTouchGeneratedClick) return;
            if (holding || recognitionActive) finishQuickCheck();
            else beginQuickCheck();
        });
        holdButton.setOnTouchListener(this::handleHoldGesture);
        root.addView(holdButton, matchWrapWithMargins(0, 14, 0, 6));

        permissionButton = button(R.string.voice_lab_grant_microphone);
        permissionButton.setOnClickListener(ignored -> requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                MICROPHONE_PERMISSION_REQUEST));
        root.addView(permissionButton, matchWrap());

        liveView = cardText(getString(R.string.voice_lab_live_empty), 16);
        liveView.setMinHeight(dp(72));
        root.addView(liveView, matchWrapWithMargins(0, 12, 0, 0));

        finalView = cardText(getString(R.string.voice_lab_final_empty), 16);
        finalView.setMinHeight(dp(72));
        root.addView(finalView, matchWrapWithMargins(0, 8, 0, 0));

        shadowView = cardText(getString(R.string.voice_lab_v2_shadow_empty), 14);
        shadowView.setId(R.id.voice_lab_v2_shadow);
        shadowView.setTextIsSelectable(true);
        root.addView(shadowView, matchWrapWithMargins(0, 8, 0, 0));

        metricsView = cardText(getString(R.string.voice_lab_metrics_empty), 14);
        metricsView.setTextIsSelectable(true);
        root.addView(metricsView, matchWrapWithMargins(0, 8, 0, 0));

        performanceView = cardText(getString(R.string.voice_lab_performance_empty), 14);
        performanceView.setTextIsSelectable(true);
        root.addView(performanceView, matchWrapWithMargins(0, 8, 0, 0));

        progressView = text("", 14, false);
        progressView.setTextColor(getColor(R.color.ime_on_surface_variant));
        progressView.setPadding(0, dp(14), 0, dp(6));
        root.addView(progressView);

        nextButton = button(R.string.voice_lab_next_phrase);
        nextButton.setOnClickListener(ignored -> advancePrompt());
        nextButton.setEnabled(false);
        root.addView(nextButton, matchWrap());

        exportButton = button(R.string.voice_lab_export_diagnostics);
        exportButton.setOnClickListener(ignored -> exportDiagnostics());
        exportButton.setEnabled(diagnosticsStore.load() != null);
        root.addView(exportButton, matchWrap());

        TextView privacy = text(getString(R.string.voice_lab_privacy_note), 13, false);
        privacy.setTextColor(getColor(R.color.ime_on_surface_variant));
        privacy.setPadding(0, dp(16), 0, 0);
        root.addView(privacy);
        return scroll;
    }

    private void loadSettings() {
        settingsExecutor.submit(() -> {
            try {
                AppSettings loaded = new SettingsRepository(this).load();
                if (loaded.recognitionBackend() == RecognitionBackend.LOCAL_OFFLINE) {
                    pipeline.prewarmLocalOffline();
                }
                if (destroyed) return;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    settings = loaded;
                    prompts = usesChinesePrompts(loaded.language())
                            ? CHINESE_PROMPTS
                            : ENGLISH_PROMPTS;
                    promptIndex = Math.min(promptIndex, prompts.length - 1);
                    route = RecognitionRoute.direct(loaded.recognitionBackend());
                    renderIdle();
                });
                if (loaded.recognitionBackend() == RecognitionBackend.SYSTEM_DEFAULT) {
                    SystemRecognitionDiagnostics.Snapshot inspected =
                            SystemRecognitionDiagnostics.inspect(getApplicationContext());
                    if (destroyed) return;
                    runOnUiThread(() -> {
                        if (destroyed || settings != loaded) return;
                        systemDiagnostics = inspected;
                        renderRoute();
                    });
                }
            } catch (RuntimeException error) {
                if (destroyed) return;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    statusView.setText(R.string.voice_lab_settings_failed);
                    holdButton.setEnabled(false);
                });
            }
        });
    }

    private boolean handleHoldGesture(View view, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            beginQuickCheck();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            finishQuickCheck();
            // Emit the normal accessibility click event without re-running the click-to-toggle
            // fallback. TalkBack users can invoke that fallback directly with two double taps.
            suppressTouchGeneratedClick = true;
            try {
                view.performClick();
            } finally {
                suppressTouchGeneratedClick = false;
            }
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // A window/pointer cancellation is a normal endpoint, never an explicit discard.
            finishQuickCheck();
            return true;
        }
        return true;
    }

    private void beginQuickCheck() {
        if (holding || recognitionActive || settings == null || !settings.isReady()) {
            if (settings != null && !settings.isReady()) {
                statusView.setText(R.string.voice_lab_backend_not_ready);
            }
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            statusView.setText(R.string.voice_lab_permission_required);
            return;
        }
        holding = true;
        recognitionActive = true;
        microphoneReady = false;
        releaseAtMs = 0L;
        rawFinalAtMs = 0L;
        completedAtMs = 0L;
        liveView.setText(R.string.voice_lab_live_waiting);
        finalView.setText(R.string.voice_lab_final_empty);
        shadowEvaluator = null;
        productionV2Observed = false;
        shadowView.setText(R.string.voice_lab_v2_shadow_waiting);
        metricsView.setText(R.string.voice_lab_metrics_waiting);
        statusView.setText(R.string.voice_lab_preparing_microphone);
        nextButton.setEnabled(false);
        performanceView.setText(R.string.voice_lab_performance_measuring);
        performanceProbe.start();
        route = RecognitionRoute.direct(settings.recognitionBackend());
        renderRoute();
        long attempt = ++attemptGeneration;
        activeAttempt = attempt;

        DictationRequest request = new DictationRequest(
                settings,
                ProcessingMode.VERBATIM,
                new InputContext(
                        getPackageName(),
                        FieldKind.GENERAL,
                        "",
                        "",
                        false),
                PersonalizationSnapshot.empty(),
                DictationRequest.CaptureMode.HOLD_TO_TALK);
        if (!pipeline.start(request, listener(attempt))) {
            holding = false;
            recognitionActive = false;
            activeAttempt = ++attemptGeneration;
            statusView.setText(R.string.voice_lab_busy);
            renderPerformance(performanceProbe.finish());
            renderControls();
        } else {
            renderControls();
        }
    }

    private void finishQuickCheck() {
        if (!holding) return;
        holding = false;
        if (!recognitionActive) {
            renderControls();
            return;
        }
        if (!microphoneReady) {
            pipeline.discard();
            recognitionActive = false;
            activeAttempt = ++attemptGeneration;
            statusView.setText(R.string.voice_lab_released_before_ready);
            liveView.setText(R.string.voice_lab_live_empty);
            renderPerformance(performanceProbe.finish());
            renderControls();
            return;
        }
        releaseAtMs = SystemClock.elapsedRealtime();
        statusView.setText(R.string.voice_lab_finalizing);
        pipeline.stopRecording();
        renderControls();
    }

    private VoicePipeline.Listener listener(long attempt) {
        return new VoicePipeline.Listener() {
            @Override
            public void onState(VoicePipeline.State state, String message) {
                postUi(() -> {
                    if (!isAttemptCurrent(attempt)) return;
                    if (state == VoicePipeline.State.RECORDING && !microphoneReady) {
                        statusView.setText(R.string.voice_lab_preparing_microphone);
                    } else if (state == VoicePipeline.State.RECORDING) {
                        statusView.setText(R.string.voice_lab_listening_release);
                    } else if (state == VoicePipeline.State.TRANSCRIBING) {
                        statusView.setText(R.string.voice_lab_transcribing);
                    } else if (state == VoicePipeline.State.POLISHING) {
                        statusView.setText(R.string.voice_lab_processing);
                    }
                });
            }

            @Override
            public void onRoute(RecognitionRoute actualRoute) {
                postUi(() -> {
                    if (!isAttemptCurrent(attempt)) return;
                    route = actualRoute;
                    shadowEvaluator = new SpeechCoreShadowEvaluator(
                            attempt,
                            processingLocation(actualRoute.actualBackend()));
                    renderShadow(shadowEvaluator.snapshot());
                    renderRoute();
                });
            }

            @Override
            public void onReadyForSpeech() {
                postUi(() -> {
                    if (!isAttemptCurrent(attempt)) return;
                    if (!holding) return;
                    microphoneReady = true;
                    holdButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    statusView.setText(R.string.voice_lab_listening_release);
                    holdButton.setText(R.string.voice_lab_release_to_finish);
                    renderControls();
                });
            }

            @Override
            public void onTranscript(TranscriptUpdate update) {
                postUi(() -> {
                    if (!isAttemptCurrent(attempt)
                            || update == null
                            || update.text().isBlank()) return;
                    if (update.source() == TranscriptUpdate.Source.SPEECH_CORE_V2) {
                        productionV2Observed = true;
                    }
                    if (update.finalResult() && rawFinalAtMs <= 0L) {
                        rawFinalAtMs = SystemClock.elapsedRealtime();
                    }
                    liveView.setText(getString(R.string.voice_lab_live_value, update.text()));
                    if (shadowEvaluator != null) {
                        renderShadow(shadowEvaluator.accept(update));
                    }
                    renderMetrics(diagnosticsStore.load());
                });
            }

            @Override
            public void onResult(DictationResult result) {
                postUi(() -> finishAttempt(attempt, result, null));
            }

            @Override
            public void onError(String message) {
                postUi(() -> finishAttempt(attempt, null, message));
            }
        };
    }

    private void finishAttempt(long attempt, DictationResult result, String error) {
        if (!isAttemptCurrent(attempt)) return;
        recognitionActive = false;
        holding = false;
        completedAtMs = SystemClock.elapsedRealtime();
        attempts++;
        boolean success = result != null && !result.finalText().isBlank();
        if (success) successes++;
        if (success) {
            if (shadowEvaluator != null) {
                renderShadow(shadowEvaluator.complete(result.rawText()));
            }
            VoiceLabScorer.Score score = VoiceLabScorer.score(
                    prompts[promptIndex],
                    result.finalText());
            if (score.exact()) exactMatches++;
            liveView.setText(getString(R.string.voice_lab_live_value, result.rawText()));
            finalView.setText(getString(
                    R.string.voice_lab_final_scored_value,
                    result.finalText(),
                    score.metric(),
                    String.format(Locale.getDefault(), "%.1f%%", score.errorRate() * 100.0d),
                    getString(score.exact()
                            ? R.string.voice_lab_score_exact
                            : R.string.voice_lab_score_differs)));
            statusView.setText(R.string.voice_lab_attempt_complete);
        } else {
            if (shadowEvaluator != null) renderShadow(shadowEvaluator.fail());
            finalView.setText(getString(
                    R.string.voice_lab_error_value,
                    error == null || error.isBlank()
                            ? getString(R.string.operation_failed)
                            : error));
            statusView.setText(R.string.voice_lab_attempt_failed);
        }
        RecognitionDiagnostics.Snapshot snapshot = diagnosticsStore.load();
        renderMetrics(snapshot);
        renderPerformance(performanceProbe.finish());
        renderProgress();
        nextButton.setEnabled(true);
        exportButton.setEnabled(snapshot != null);
        renderControls();
    }

    private void renderIdle() {
        promptView.setText(getString(R.string.voice_lab_prompt_value, prompts[promptIndex]));
        boolean permission = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        statusView.setText(!permission
                ? R.string.voice_lab_permission_required
                : settings.isReady()
                ? R.string.voice_lab_ready
                : R.string.voice_lab_backend_not_ready);
        liveView.setText(R.string.voice_lab_live_empty);
        finalView.setText(R.string.voice_lab_final_empty);
        shadowEvaluator = null;
        productionV2Observed = false;
        shadowView.setText(R.string.voice_lab_v2_shadow_empty);
        performanceView.setText(R.string.voice_lab_performance_empty);
        RecognitionDiagnostics.Snapshot last = diagnosticsStore.load();
        if (last != null) route = last.route();
        renderMetrics(last);
        exportButton.setEnabled(last != null);
        renderProgress();
        renderRoute();
        renderControls();
    }

    private void advancePrompt() {
        if (recognitionActive) return;
        promptIndex = (promptIndex + 1) % prompts.length;
        promptView.setText(getString(R.string.voice_lab_prompt_value, prompts[promptIndex]));
        statusView.setText(R.string.voice_lab_ready);
        liveView.setText(R.string.voice_lab_live_empty);
        finalView.setText(R.string.voice_lab_final_empty);
        shadowEvaluator = null;
        productionV2Observed = false;
        shadowView.setText(R.string.voice_lab_v2_shadow_empty);
        releaseAtMs = 0L;
        rawFinalAtMs = 0L;
        completedAtMs = 0L;
        nextButton.setEnabled(false);
    }

    private void renderRoute() {
        if (settings == null || route == null) return;
        String fallback = route.fellBack()
                ? getString(R.string.voice_lab_route_fallback, fallbackLabel(route.fallbackReason()))
                : getString(R.string.voice_lab_route_no_fallback);
        routeView.setText(getString(
                R.string.voice_lab_route_summary,
                backendLabel(route.selectedBackend()),
                backendLabel(route.actualBackend()),
                privacyLabel(route.privacyBoundary()),
                fallback,
                engineIdentity(route.actualBackend())));
    }

    private void renderMetrics(RecognitionDiagnostics.Snapshot snapshot) {
        if (snapshot == null) {
            metricsView.setText(R.string.voice_lab_metrics_empty);
            return;
        }
        long asrFinalLatency = releaseAtMs > 0L && rawFinalAtMs > 0L
                ? Math.max(0L, rawFinalAtMs - releaseAtMs)
                : snapshot.releaseToRawFinalLatencyMs();
        long textProcessingLatency = rawFinalAtMs > 0L && completedAtMs > 0L
                ? Math.max(0L, completedAtMs - rawFinalAtMs)
                : snapshot.textProcessingLatencyMs();
        long totalInsertionLatency = releaseAtMs > 0L && completedAtMs > 0L
                ? Math.max(0L, completedAtMs - releaseAtMs)
                : snapshot.releaseToTerminalLatencyMs();
        String firstLiveText = snapshot.firstPartialLatencyMs() >= 0L
                ? metric(snapshot.firstPartialLatencyMs())
                : completedAtMs > 0L
                ? getString(R.string.voice_lab_no_live_partial)
                : metric(-1L);
        String readyToFirstLive = snapshot.readyLatencyMs() >= 0L
                        && snapshot.firstPartialLatencyMs() >= 0L
                ? metric(Math.max(
                        0L,
                        snapshot.firstPartialLatencyMs() - snapshot.readyLatencyMs()))
                : snapshot.terminal()
                ? getString(R.string.voice_lab_no_live_partial)
                : metric(-1L);
        metricsView.setText(getString(
                R.string.voice_lab_metrics_value,
                metric(snapshot.readyLatencyMs()),
                firstLiveText,
                readyToFirstLive,
                metric(asrFinalLatency),
                metric(textProcessingLatency),
                metric(totalInsertionLatency),
                metric(snapshot.audioDurationMs()),
                diagnosticsStatus(snapshot.status())));
    }

    private void renderPerformance(VoiceLabPerformanceProbe.Snapshot snapshot) {
        if (snapshot == null) {
            performanceView.setText(R.string.voice_lab_performance_empty);
            return;
        }
        performanceView.setText(getString(
                R.string.voice_lab_performance_value,
                mib(snapshot.startPssKb()),
                mib(snapshot.peakPssKb()),
                mib(snapshot.endPssKb()),
                pss(snapshot.localAsrStartPssKb()),
                pss(snapshot.localAsrPeakPssKb()),
                pss(snapshot.localAsrEndPssKb()),
                snapshot.cpuDeltaMs() + " ms",
                kib(snapshot.appRxDeltaBytes()),
                kib(snapshot.appTxDeltaBytes()),
                thermalLabel(snapshot.startThermalStatus()),
                thermalLabel(snapshot.endThermalStatus())));
    }

    private void renderProgress() {
        progressView.setText(getString(
                R.string.voice_lab_progress,
                promptIndex + 1,
                prompts.length,
                successes,
                attempts,
                exactMatches));
    }

    private void renderShadow(SpeechCoreShadowSnapshot snapshot) {
        if (snapshot == null) {
            shadowView.setText(R.string.voice_lab_v2_shadow_empty);
            return;
        }
        String text = snapshot.renderedText().isBlank()
                ? getString(R.string.voice_lab_v2_shadow_no_text)
                : snapshot.renderedText();
        shadowView.setText(getString(
                productionV2Observed
                        ? R.string.voice_lab_v2_active_value
                        : R.string.voice_lab_v2_compatibility_value,
                text,
                snapshot.acceptedRevisions(),
                snapshot.earlierTextRevisions(),
                snapshot.provisionalPunctuationObserved()
                        ? getString(R.string.voice_lab_v2_shadow_yes)
                        : getString(R.string.voice_lab_v2_shadow_no),
                snapshot.captureState().name()));
    }

    private void renderControls() {
        boolean permission = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean ready = settings != null && settings.isReady() && permission;
        holdButton.setEnabled(ready && (!recognitionActive || holding));
        if (!microphoneReady || !recognitionActive) {
            holdButton.setText(R.string.voice_lab_hold_to_record);
        }
        holdButton.setContentDescription(getString(
                recognitionActive
                        ? R.string.voice_lab_cd_finish_recording
                        : R.string.voice_lab_cd_start_recording));
        permissionButton.setVisibility(permission ? View.GONE : View.VISIBLE);
    }

    private void renderPermission() {
        boolean granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (!granted) statusView.setText(R.string.voice_lab_permission_required);
        renderControls();
    }

    private void exportDiagnostics() {
        RecognitionDiagnostics.Snapshot snapshot = diagnosticsStore.load();
        if (snapshot == null) {
            statusView.setText(R.string.last_recognition_none);
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("application/json")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.voice_lab_export_subject))
                .putExtra(Intent.EXTRA_TEXT, RecognitionDiagnosticsJson.encode(snapshot));
        startActivity(Intent.createChooser(share, getString(R.string.voice_lab_export_diagnostics)));
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MICROPHONE_PERMISSION_REQUEST) {
            renderPermission();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED && settings != null) {
                renderIdle();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (settings != null && !recognitionActive) renderIdle();
    }

    @Override
    protected void onStop() {
        // Voice Lab takes are disposable diagnostics, not user drafts. Leaving the screen is an
        // explicit end to the test and must not leave a captured checkpoint that blocks the IME or
        // a later quick check. Ordinary IME lifecycle stops continue to preserve user speech.
        if (recognitionActive) pipeline.discard();
        performanceProbe.finish();
        recognitionActive = false;
        holding = false;
        activeAttempt = ++attemptGeneration;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        pipeline.shutdown();
        performanceProbe.close();
        settingsExecutor.shutdownNow();
        super.onDestroy();
    }

    private void postUi(Runnable action) {
        runOnUiThread(() -> {
            if (!destroyed) action.run();
        });
    }

    private boolean isAttemptCurrent(long attempt) {
        return recognitionActive && activeAttempt == attempt;
    }

    private String backendLabel(RecognitionBackend backend) {
        return getString(switch (backend) {
            case OPENAI_COMPATIBLE -> R.string.backend_openai;
            case LOCAL_OFFLINE -> R.string.backend_local_offline;
            case DASHSCOPE_STREAMING -> R.string.backend_dashscope_streaming;
            case SYSTEM_ON_DEVICE -> R.string.backend_on_device;
            case SYSTEM_DEFAULT -> R.string.backend_system_default;
        });
    }

    private String engineIdentity(RecognitionBackend backend) {
        return switch (backend) {
            case OPENAI_COMPATIBLE -> safeIdentity(
                    settings.sttModel(),
                    getString(R.string.voice_lab_engine_byok));
            case DASHSCOPE_STREAMING -> safeIdentity(
                    settings.streamingModel(),
                    getString(R.string.voice_lab_engine_dashscope));
            case LOCAL_OFFLINE -> getString(OfflineStreamingRecognizer.isInstalled(this)
                    ? R.string.voice_lab_engine_offline_hybrid
                    : R.string.voice_lab_engine_sensevoice);
            case SYSTEM_ON_DEVICE -> getString(R.string.voice_lab_engine_system_on_device);
            case SYSTEM_DEFAULT -> {
                SystemRecognitionDiagnostics.Snapshot system = systemDiagnostics;
                if (system == null) {
                    yield getString(R.string.voice_lab_engine_system_checking);
                }
                String identified = (system.serviceLabel() + " " + system.versionName()).trim();
                yield safeIdentity(
                        identified,
                        getString(R.string.voice_lab_engine_system_unknown));
            }
        };
    }

    private static String safeIdentity(String value, String fallback) {
        String clean = value == null
                ? ""
                : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.isEmpty()) return fallback;
        int count = clean.codePointCount(0, clean.length());
        return count <= 80
                ? clean
                : clean.substring(0, clean.offsetByCodePoints(0, 80));
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

    private static ProcessingLocation processingLocation(RecognitionBackend backend) {
        return switch (backend) {
            case LOCAL_OFFLINE -> ProcessingLocation.ON_DEVICE;
            case SYSTEM_ON_DEVICE, SYSTEM_DEFAULT -> ProcessingLocation.ANDROID_SYSTEM_SERVICE;
            case OPENAI_COMPATIBLE, DASHSCOPE_STREAMING -> ProcessingLocation.NETWORK;
        };
    }

    private String thermalLabel(int status) {
        return getString(switch (status) {
            case 0 -> R.string.voice_lab_thermal_none;
            case 1 -> R.string.voice_lab_thermal_light;
            case 2 -> R.string.voice_lab_thermal_moderate;
            case 3 -> R.string.voice_lab_thermal_severe;
            case 4 -> R.string.voice_lab_thermal_critical;
            case 5 -> R.string.voice_lab_thermal_emergency;
            case 6 -> R.string.voice_lab_thermal_shutdown;
            default -> R.string.voice_lab_thermal_unknown;
        });
    }

    private static boolean usesChinesePrompts(String language) {
        String normalized = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Locale.getDefault().getLanguage().equalsIgnoreCase("zh");
        }
        return normalized.startsWith("zh") || normalized.startsWith("cmn");
    }

    private static String metric(long value) {
        return value < 0L ? "—" : value + " ms";
    }

    private static String mib(long kibibytes) {
        return String.format(Locale.getDefault(), "%.1f MiB", kibibytes / 1024.0d);
    }

    private static String pss(long kibibytes) {
        return kibibytes < 0L ? "—" : mib(kibibytes);
    }

    private static String kib(long bytes) {
        return bytes < 0L
                ? "—"
                : String.format(Locale.getDefault(), "%.1f KiB", bytes / 1024.0d);
    }

    private TextView cardText(String value, int sp) {
        TextView view = text(value, sp, false);
        view.setTextColor(getColor(R.color.ime_on_surface));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackgroundResource(R.drawable.app_card_background);
        return view;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    private Button button(int labelResource) {
        return AppVisualSystem.secondaryButton(this, labelResource, null);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithMargins(
            int left,
            int top,
            int right,
            int bottom) {
        LinearLayout.LayoutParams parameters = matchWrap();
        parameters.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return parameters;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
