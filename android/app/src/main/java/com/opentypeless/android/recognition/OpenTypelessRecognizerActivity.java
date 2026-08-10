package com.opentypeless.android.recognition;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.opentypeless.android.R;
import com.opentypeless.android.SystemBarInsets;

/** Activity implementation for {@link RecognizerIntent#ACTION_RECOGNIZE_SPEECH}. */
public final class OpenTypelessRecognizerActivity extends Activity {
    private static final int MICROPHONE_PERMISSION_REQUEST = 4201;
    private static final String STATE_SESSION_CANCELLED =
            "com.opentypeless.android.recognition.SESSION_CANCELLED";

    private RecognitionSessionController controller;
    private TextView status;
    private TextView partial;
    private Button stop;
    private boolean started;
    private boolean cancelledForSavedState;
    private volatile boolean completed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setResult(RESULT_CANCELED);
        if (savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_SESSION_CANCELLED, false)) {
            completed = true;
            finish();
            return;
        }
        buildContentView();
        Intent source = getIntent();
        if (source == null
                || !RecognizerIntent.ACTION_RECOGNIZE_SPEECH.equals(source.getAction())) {
            finishError(new RecognitionFailure(
                    android.speech.SpeechRecognizer.ERROR_CLIENT,
                    getString(R.string.recognition_error_unsupported_action)));
            return;
        }
        String callingPackage = getCallingPackage();
        RecognitionAccessController.Decision access = StandardRecognitionAccess.forActivity(
                new StandardRecognitionSettings(this).load(),
                callingPackage);
        if (access != RecognitionAccessController.Decision.ALLOWED) {
            finishError(new RecognitionFailure(
                    access == RecognitionAccessController.Decision.RATE_LIMITED
                            ? RecognitionErrors.rateLimitedCode()
                            : android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    access == RecognitionAccessController.Decision.RATE_LIMITED
                            ? getString(R.string.recognition_error_rate_limited)
                            : getString(R.string.recognition_error_caller_not_allowed)));
            return;
        }
        controller = new RecognitionSessionController(new VoicePipelineRecognitionEngine(this));
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startRecognition();
        } else {
            status.setText(R.string.recognition_status_microphone_required);
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    MICROPHONE_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MICROPHONE_PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecognition();
        } else {
            finishError(new RecognitionFailure(
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    getString(R.string.recognition_error_microphone_permission)));
        }
    }

    @Override
    public void onBackPressed() {
        cancelAndFinish();
    }

    @Override
    protected void onStop() {
        if (!completed) {
            if (cancelledForSavedState) finishCancelled();
            else cancelAndFinish();
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_SESSION_CANCELLED, true);
        if (!completed) {
            cancelledForSavedState = true;
            if (controller != null) controller.cancel();
            setResult(RESULT_CANCELED);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (controller != null) controller.shutdown();
        super.onDestroy();
    }

    private void startRecognition() {
        if (started || completed) return;
        started = true;
        RecognitionRequest request = AndroidRecognitionContracts.request(
                getIntent(),
                getCallingPackage());
        if (!request.prompt().isEmpty()) status.setText(request.prompt());
        controller.start(request, new RecognitionSessionController.Observer() {
            @Override
            public void onReady() {
                postUi(() -> {
                    status.setText(R.string.recognition_status_listening);
                    stop.setEnabled(true);
                });
            }

            @Override public void onBeginningOfSpeech() {}

            @Override
            public void onEndOfSpeech() {
                postUi(() -> {
                    status.setText(R.string.recognition_status_transcribing);
                    stop.setEnabled(false);
                });
            }

            @Override
            public void onPartial(RecognitionResult result) {
                postUi(() -> partial.setText(result.bestText()));
            }

            @Override
            public void onFinal(RecognitionResult result) {
                postUi(() -> finishSuccess(result));
            }

            @Override
            public void onError(RecognitionFailure failure) {
                postUi(() -> finishError(failure));
            }

            @Override
            public void onCancelled() {
                postUi(OpenTypelessRecognizerActivity.this::finishCancelled);
            }
        });
    }

    private void finishSuccess(RecognitionResult result) {
        if (cancelledForSavedState) {
            finishCancelled();
            return;
        }
        if (completed) return;
        completed = true;
        Intent data = AndroidRecognitionContracts.resultIntent(result);
        addPendingIntentExtras(data);
        sendPendingResult(RESULT_OK, data);
        setResult(RESULT_OK, data);
        finish();
    }

    private void finishError(RecognitionFailure failure) {
        if (cancelledForSavedState) {
            finishCancelled();
            return;
        }
        if (completed) return;
        completed = true;
        Intent data = AndroidRecognitionContracts.errorIntent(failure);
        addPendingIntentExtras(data);
        sendPendingResult(RESULT_CANCELED, data);
        setResult(RESULT_CANCELED, data);
        Toast.makeText(this, failure.message(), Toast.LENGTH_LONG).show();
        finish();
    }

    private void cancelAndFinish() {
        if (completed) return;
        if (controller != null) controller.cancel();
        finishCancelled();
    }

    private void finishCancelled() {
        if (completed) return;
        completed = true;
        setResult(RESULT_CANCELED);
        finish();
    }

    private void stopRecognition() {
        if (controller == null || completed) return;
        controller.stop();
        status.setText(R.string.recognition_status_transcribing);
        stop.setEnabled(false);
    }

    private void buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(20));
        root.setBackgroundColor(getColor(R.color.ime_surface));

        status = new TextView(this);
        status.setText(R.string.recognition_status_preparing);
        status.setTextSize(18);
        status.setTextColor(getColor(R.color.ime_on_surface_variant));
        status.setGravity(Gravity.CENTER);
        root.addView(status, matchWidthWrap());

        partial = new TextView(this);
        partial.setTextSize(16);
        partial.setTextColor(getColor(R.color.ime_on_surface));
        partial.setGravity(Gravity.CENTER);
        partial.setPadding(0, dp(20), 0, dp(20));
        root.addView(partial, matchWidthWrap());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        stop = button(R.string.recognition_action_stop, ignored -> stopRecognition());
        stop.setEnabled(false);
        buttons.addView(stop, buttonLayoutParams());
        buttons.addView(
                button(R.string.cancel, ignored -> cancelAndFinish()),
                buttonLayoutParams());
        root.addView(buttons, matchWidthWrap());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        SystemBarInsets.apply(scroll);
        scroll.addView(root);
        setContentView(scroll);
    }

    private Button button(int textResource, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(textResource);
        button.setAllCaps(false);
        button.setMinimumHeight(dp(48));
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams buttonLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(8));
        params.setMarginEnd(dp(8));
        return params;
    }

    private LinearLayout.LayoutParams matchWidthWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void postUi(Runnable action) {
        if (isFinishing() || completed) return;
        runOnUiThread(action);
    }

    private void addPendingIntentExtras(Intent result) {
        Intent source = getIntent();
        if (source == null) return;
        Bundle extras = source.getBundleExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE);
        if (extras == null) return;
        Bundle authoritativeResult = result.getExtras() == null
                ? new Bundle()
                : new Bundle(result.getExtras());
        result.replaceExtras(new Bundle(extras));
        result.putExtras(authoritativeResult);
    }

    private void sendPendingResult(int resultCode, Intent result) {
        PendingIntent pending = pendingResult();
        if (pending == null) return;
        try {
            pending.send(this, resultCode, result);
        } catch (PendingIntent.CanceledException ignored) {
            // The normal Activity result is still returned to the caller.
        }
    }

    @SuppressWarnings("deprecation")
    private PendingIntent pendingResult() {
        Intent source = getIntent();
        if (source == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return source.getParcelableExtra(
                    RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT,
                    PendingIntent.class);
        }
        return source.getParcelableExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
