package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public final class AndroidRecognitionContractsInstrumentedTest {
    private static final String PREFERENCES = "opentypeless_standard_recognition";

    @After
    public void clearSettings() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void activityAndServiceUseTheirDistinctAndroidResultContracts() {
        RecognitionResult result = new RecognitionResult(List.of("best", "other"), new float[]{0.8f});

        Intent activity = AndroidRecognitionContracts.resultIntent(result);
        Bundle service = AndroidRecognitionContracts.results(result);

        assertEquals(
                List.of("best", "other"),
                activity.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS));
        assertTrue(activity.hasExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES));
        assertFalse(activity.hasExtra(SpeechRecognizer.RESULTS_RECOGNITION));
        assertEquals(
                List.of("best", "other"),
                service.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
        assertTrue(service.containsKey(SpeechRecognizer.CONFIDENCE_SCORES));
        assertFalse(service.containsKey(RecognizerIntent.EXTRA_RESULTS));
    }

    @Test
    public void externalStandardSpeechIsDisabledUntilUserExplicitlyEnablesAllowlist() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit();
        StandardRecognitionSettings settings = new StandardRecognitionSettings(context);

        assertFalse(settings.load().enabled());
        settings.save(settings.validate(true, "com.example.keyboard"));

        StandardRecognitionSettings.Snapshot saved = settings.load();
        assertTrue(saved.enabled());
        assertEquals(Set.of("com.example.keyboard"), saved.allowedPackages());
    }

    @Test
    public void systemStartSupportCheckAndDownloadShareTheFullRecognizerIntentContract() {
        AppSettings settings = systemSettings("zh-CN");

        Intent intent = SystemRecognitionIntentFactory.create(
                settings,
                PersonalizationSnapshot.empty());

        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.getAction());
        assertEquals(
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL));
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false));
        assertEquals(3, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0));
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false));
        assertEquals("zh-CN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
        assertTrue(intent.hasExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING));
    }

    @Test
    public void unobservedDownloadResultKeepsOperationAliveForPlatformDispatch() throws Exception {
        SystemRecognitionSupport.OneShotOperation operation =
                new SystemRecognitionSupport.OneShotOperation(
                        new Handler(Looper.getMainLooper()));
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicInteger results = new AtomicInteger();
        SystemRecognitionSupport.DownloadCallback callback = result -> {
            results.incrementAndGet();
            delivered.countDown();
        };

        SystemRecognitionSupport.reportDownloadDispatched(
                operation,
                callback,
                new SystemRecognitionSupport.DownloadResult(
                        SystemRecognitionSupport.DownloadStatus.REQUESTED,
                        "handed off",
                        0));
        SystemRecognitionSupport.completeDownload(
                operation,
                callback,
                new SystemRecognitionSupport.DownloadResult(
                        SystemRecognitionSupport.DownloadStatus.FAILED,
                        "late duplicate",
                        SpeechRecognizer.ERROR_CLIENT));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertEquals(1, results.get());
        assertTrue("Recognizer must survive long enough to drain its queued trigger",
                operation.isActive());
        Thread.sleep(1_250L);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse(operation.isActive());
    }

    private static AppSettings systemSettings(String language) {
        return new AppSettings(
                RecognitionBackend.SYSTEM_ON_DEVICE,
                "",
                "",
                "",
                language,
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
}
