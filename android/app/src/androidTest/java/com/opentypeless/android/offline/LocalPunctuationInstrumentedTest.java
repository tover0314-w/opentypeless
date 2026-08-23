package com.opentypeless.android.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native, opt-in punctuation smoke gate. It runs automatically once exact weights are present. */
@RunWith(AndroidJUnit4.class)
public final class LocalPunctuationInstrumentedTest {
    private static final String TAG = "OpenTypelessPunctuation";

    @Test
    public void exactPinnedModelAddsInternalChinesePunctuationInIsolatedProcess() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Assume.assumeTrue(
                OfflinePunctuationModelStore.status(context)
                        == OfflinePunctuationModelStore.Status.INSTALLED);
        String source = "我们都是木头人不会说话不会动";

        try (LocalPunctuationRecognitionClient client =
                     new LocalPunctuationRecognitionClient(context)) {
            long started = android.os.SystemClock.elapsedRealtime();
            client.prewarm();
            int pid = client.servicePidForDiagnostics();
            String candidate = client.punctuate(source);
            long elapsed = android.os.SystemClock.elapsedRealtime() - started;

            assertNotEquals(android.os.Process.myPid(), pid);
            assertEquals("我们都是木头人，不会说话，不会动。", candidate);
            assertEquals(
                    SafePunctuationRestorer.contentKey(source),
                    SafePunctuationRestorer.contentKey(candidate));
            assertTrue("Punctuation inference exceeded 30 seconds", elapsed < 30_000L);
            long warmStarted = android.os.SystemClock.elapsedRealtime();
            assertEquals(candidate, client.punctuate(source));
            long warmElapsed = android.os.SystemClock.elapsedRealtime() - warmStarted;
            long pssKb = pssKb(context, pid);
            assertTrue("Expected measurable punctuation worker PSS", pssKb > 0L);
            Log.i(TAG, "candidate=" + candidate
                    + " cold_load_and_inference_ms=" + elapsed
                    + " warm_inference_ms=" + warmElapsed
                    + " punctuation_pss_kb=" + pssKb
                    + " worker_pid=" + pid);

            client.releaseSessionWorker();
            assertTrue("Punctuation worker was retained after the session lease",
                    awaitProcessExit(context, pid, 5_000L));
            String reloaded = client.punctuate(source);
            assertEquals(candidate, reloaded);
            assertNotEquals("A released worker must not be reused", pid,
                    client.servicePidForDiagnostics());
        }
    }

    private static long pssKb(Context context, int pid) {
        ActivityManager manager = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE);
        if (manager == null) return -1L;
        Debug.MemoryInfo[] information = manager.getProcessMemoryInfo(new int[] {pid});
        if (information.length != 1 || information[0] == null) return -1L;
        return information[0].getTotalPss();
    }

    private static boolean awaitProcessExit(Context context, int pid, long timeoutMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!processAlive(context, pid)) return true;
            android.os.SystemClock.sleep(50L);
        }
        return !processAlive(context, pid);
    }

    private static boolean processAlive(Context context, int pid) {
        ActivityManager manager = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE);
        if (manager == null || manager.getRunningAppProcesses() == null) return false;
        return manager.getRunningAppProcesses().stream().anyMatch(process -> process.pid == pid);
    }
}
