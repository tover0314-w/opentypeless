package com.opentypeless.android.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.context.FieldKind;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Explicit large-download/native gate; ordinary connected tests skip it. */
@RunWith(AndroidJUnit4.class)
public final class OfflineModelEndToEndInstrumentedTest {
    private static final String TAG = "OpenTypelessOfflineE2E";

    @Test
    public void downloadsVerifiesAndTranscribesPreparedRealSpeech() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("true".equals(arguments.getString("offlineModelE2E")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File wav = new File(context.getNoBackupFilesDir(), "offline-smoke.wav");
        assertTrue("Prepare offline-smoke.wav in the target no_backup directory", wav.isFile());

        if ("true".equals(arguments.getString("offlineModelFresh"))) {
            LocalOfflineRecognizer.deleteModel(context);
        }
        if (OfflineModelStore.status(context) != OfflineModelStore.Status.INSTALLED) {
            CountDownLatch complete = new CountDownLatch(1);
            AtomicReference<String> error = new AtomicReference<>();
            AtomicInteger lastProgress = new AtomicInteger(-1);
            OfflineModelDownloader.Operation operation = OfflineModelDownloader.download(
                    context,
                    new OfflineModelDownloader.Callback() {
                    @Override
                    public void onProgress(int percent, long downloadedBytes, long totalBytes) {
                        int previous = lastProgress.getAndSet(percent);
                        if (percent < previous || downloadedBytes < 0 || downloadedBytes > totalBytes) {
                            error.compareAndSet(null, "Download progress was not monotonic");
                        }
                    }

                    @Override
                    public void onComplete() {
                        complete.countDown();
                    }

                    @Override
                    public void onError(String message) {
                        error.set(message);
                        complete.countDown();
                    }
                    });
            try {
                assertTrue("Offline model operation timed out", complete.await(8, TimeUnit.MINUTES));
            } finally {
                operation.cancel();
            }
            assertNull(error.get(), error.get());
        }
        assertEquals(OfflineModelStore.Status.INSTALLED, OfflineModelStore.status(context));

        byte[] wavBytes = Files.readAllBytes(wav.toPath());
        assertTrue("Prepared smoke WAV must have a PCM data chunk", wavBytes.length > 44
                && wavBytes[36] == 'd' && wavBytes[37] == 'a'
                && wavBytes[38] == 't' && wavBytes[39] == 'a');
        long started = android.os.SystemClock.elapsedRealtime();
        String transcript;
        String punctuated;
        AtomicReference<String> partial = new AtomicReference<>();
        try (LocalOfflineRecognizer.Session session =
                     LocalOfflineRecognizer.openSession(context, "zh-CN")) {
            CountDownLatch previewReady = new CountDownLatch(1);
            try (LocalRealtimePreview preview = new LocalRealtimePreview(session, text -> {
                partial.set(text);
                previewReady.countDown();
            })) {
                byte[] pcm = Arrays.copyOfRange(wavBytes, 44, wavBytes.length);
                preview.accept(pcm, pcm.length);
                assertTrue("Offline prefix preview timed out",
                        previewReady.await(30, TimeUnit.SECONDS));
            }
            punctuated = session.transcribeWithPunctuation(wavBytes);
            transcript = session.transcribe(wavBytes);
        }
        long elapsed = android.os.SystemClock.elapsedRealtime() - started;
        assertEquals("那其他方面除了运动方面还有什么爱好", transcript);
        assertTrue("Prefix preview was empty", partial.get() != null && !partial.get().isBlank());
        String safePunctuated = SafePunctuationRestorer.choose(
                transcript, punctuated, FieldKind.LONG_TEXT);
        assertEquals(
                SafePunctuationRestorer.contentKey(transcript),
                SafePunctuationRestorer.contentKey(safePunctuated));
        assertTrue("Cold model load and decode exceeded 90 seconds", elapsed < 90_000);
        Log.i(TAG, "cold_transcribe_ms=" + elapsed
                + " partial=" + partial.get()
                + " punctuated=" + punctuated
                + " safe_punctuated=" + safePunctuated
                + " vm_rss_kib=" + procStatusValue("VmRSS")
                + " vm_hwm_kib=" + procStatusValue("VmHWM")
                + " java_heap_bytes=" + Runtime.getRuntime().totalMemory());
    }

    private static long procStatusValue(String key) throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(key + ":")) continue;
                String[] parts = line.substring(line.indexOf(':') + 1).trim().split("\\s+");
                return Long.parseLong(parts[0]);
            }
        }
        return -1;
    }
}
