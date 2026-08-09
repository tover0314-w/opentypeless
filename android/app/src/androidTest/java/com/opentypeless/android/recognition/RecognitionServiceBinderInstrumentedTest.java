package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.settings.SettingsRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public final class RecognitionServiceBinderInstrumentedTest {
    private Context context;
    private Instrumentation instrumentation;
    private SettingsRepository repository;
    private StandardRecognitionSettings standardSettings;
    private AppSettings originalSettings;
    private StandardRecognitionSettings.Snapshot originalStandardSettings;
    private SpeechRecognizer recognizer;

    @Before
    public void configureActualCallerAndUnreadyByokRoute() {
        context = ApplicationProvider.getApplicationContext();
        instrumentation = InstrumentationRegistry.getInstrumentation();
        repository = new SettingsRepository(context);
        standardSettings = new StandardRecognitionSettings(context);
        originalSettings = repository.load();
        originalStandardSettings = standardSettings.load();

        grantMicrophone(context.getPackageName());
        grantMicrophone(instrumentation.getContext().getPackageName());
        Set<String> callerPackages = new LinkedHashSet<>();
        String[] owned = context.getPackageManager().getPackagesForUid(android.os.Process.myUid());
        if (owned != null) callerPackages.addAll(Arrays.asList(owned));
        callerPackages.add(context.getPackageName());
        callerPackages.add(instrumentation.getContext().getPackageName());
        standardSettings.save(new StandardRecognitionSettings.Snapshot(true, callerPackages));
        repository.save(withSttRoute(originalSettings, "", ""));
    }

    @After
    public void restoreConfigurationAndDestroyRecognizer() {
        if (recognizer != null) {
            instrumentation.runOnMainSync(recognizer::destroy);
            recognizer = null;
        }
        if (originalSettings != null) repository.save(originalSettings);
        if (originalStandardSettings != null) standardSettings.save(originalStandardSettings);
    }

    @Test
    @SuppressLint("MissingPermission") // Permission is granted to the real caller in @Before.
    public void explicitBinderCrossesAttributionAllowlistAndCancelHasNoLateResult()
            throws Exception {
        CountDownLatch firstTerminal = new CountDownLatch(1);
        AtomicInteger firstError = new AtomicInteger(-1);
        AtomicInteger unexpectedResults = new AtomicInteger();
        RecognitionListener first = listener(
                new CountDownLatch(0),
                firstTerminal,
                firstError,
                unexpectedResults);
        instrumentation.runOnMainSync(() -> {
            recognizer = SpeechRecognizer.createSpeechRecognizer(
                    context,
                    new ComponentName(context, OpenTypelessRecognitionService.class));
            recognizer.setRecognitionListener(first);
            recognizer.startListening(request());
        });

        assertTrue("RecognitionService did not return a terminal callback",
                firstTerminal.await(10, TimeUnit.SECONDS));
        assertEquals(
                "Request must pass permission/allowlist and reach the unready BYOK engine",
                SpeechRecognizer.ERROR_CLIENT,
                firstError.get());
        assertEquals(0, unexpectedResults.get());

        repository.save(withSttRoute(originalSettings, "http://127.0.0.1:9/v1", "test-model"));
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch cancelledTerminal = new CountDownLatch(1);
        AtomicInteger cancelledError = new AtomicInteger(-1);
        RecognitionListener second = listener(
                ready,
                cancelledTerminal,
                cancelledError,
                unexpectedResults);
        instrumentation.runOnMainSync(() -> {
            recognizer.setRecognitionListener(second);
            recognizer.startListening(request());
        });
        assertTrue("Ready callback was not delivered through the explicit service binding",
                ready.await(10, TimeUnit.SECONDS));
        instrumentation.runOnMainSync(recognizer::cancel);

        assertTrue("A final result was delivered after cancel",
                !cancelledTerminal.await(750, TimeUnit.MILLISECONDS));
        assertEquals(0, unexpectedResults.get());
        assertEquals(-1, cancelledError.get());
    }

    private RecognitionListener listener(
            CountDownLatch ready,
            CountDownLatch terminal,
            AtomicInteger error,
            AtomicInteger results) {
        return new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { ready.countDown(); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int code) {
                error.compareAndSet(-1, code);
                terminal.countDown();
            }
            @Override public void onResults(Bundle bundle) {
                results.incrementAndGet();
                terminal.countDown();
            }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        };
    }

    private static Intent request() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
    }

    private static AppSettings withSttRoute(
            AppSettings source,
            String baseUrl,
            String model) {
        return new AppSettings(
                RecognitionBackend.OPENAI_COMPATIBLE,
                baseUrl,
                "",
                model,
                source.language(),
                source.defaultMode(),
                false,
                source.llmBaseUrl(),
                source.llmApiKey(),
                source.llmModel(),
                source.targetLanguage(),
                source.customInstructions(),
                false,
                source.historyEnabled(),
                false,
                source.maxRecordingSeconds());
    }

    private void grantMicrophone(String packageName) {
        try {
            instrumentation.getUiAutomation().grantRuntimePermission(
                    packageName,
                    Manifest.permission.RECORD_AUDIO);
        } catch (RuntimeException ignored) {
            // The instrumentation APK does not request RECORD_AUDIO; the target app does.
        }
        if (packageName.equals(context.getPackageName())) {
            assertEquals(
                    PackageManager.PERMISSION_GRANTED,
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
        }
    }
}
