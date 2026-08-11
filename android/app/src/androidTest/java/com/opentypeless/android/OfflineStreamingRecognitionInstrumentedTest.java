package com.opentypeless.android;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.offline.LocalRealtimeRecognitionClient;
import com.opentypeless.android.offline.LocalOfflineRecognitionClient;
import com.opentypeless.android.offline.LocalOfflineRecognizer;
import com.opentypeless.android.offline.OfflineStreamingModelStore;
import com.opentypeless.android.offline.OfflineStreamingRecognizer;
import com.opentypeless.android.audio.Pcm16WaveDecoder;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Optional native/Binder smoke gate; CI skips it until the 226 MiB model is provisioned. */
@RunWith(AndroidJUnit4.class)
public final class OfflineStreamingRecognitionInstrumentedTest {
    @Test
    public void privateProcessAcceptsRealtimePcmAndFlushes() {
        Context context = ApplicationProvider.getApplicationContext();
        Assume.assumeTrue(OfflineStreamingRecognizer.isInstalled(context));

        try (LocalRealtimeRecognitionClient client =
                     new LocalRealtimeRecognitionClient(context);
             LocalRealtimeRecognitionClient.Session session = client.start(ignored -> {})) {
            byte[] frame = new byte[16_000 * 2 * 40 / 1_000];
            // Deterministic low-amplitude tone: the assertion is lifecycle/ABI safety, not ASR
            // accuracy. Corpus accuracy is measured by the offline_asr benchmark separately.
            for (int chunk = 0; chunk < 25; chunk++) {
                for (int sample = 0; sample < frame.length / 2; sample++) {
                    double phase = 2.0 * Math.PI * 220.0
                            * (chunk * frame.length / 2.0 + sample) / 16_000.0;
                    short value = (short) Math.round(Math.sin(phase) * 2_000.0);
                    frame[sample * 2] = (byte) (value & 0xff);
                    frame[sample * 2 + 1] = (byte) ((value >>> 8) & 0xff);
                }
                session.accept(frame, frame.length);
            }
            assertNotNull(session.finish());
        }
    }

    @Test
    public void optionalOfficialWaveProducesLiveAndFinalText() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Assume.assumeTrue(OfflineStreamingRecognizer.isInstalled(context));
        File smoke = new File(
                OfflineStreamingModelStore.requireVerified(context).directory(),
                "smoke.wav");
        Assume.assumeTrue(smoke.isFile());
        byte[] wav = readAll(smoke);
        Pcm16WaveDecoder.Waveform waveform = Pcm16WaveDecoder.decode(wav);
        AtomicInteger partials = new AtomicInteger();
        AtomicLong firstPartialMs = new AtomicLong(-1L);
        long started = SystemClock.elapsedRealtime();
        AtomicLong streamClientStartMs = new AtomicLong(-1L);
        long modelPssKb;

        String finalText;
        try (LocalRealtimeRecognitionClient client =
                     new LocalRealtimeRecognitionClient(context);
             LocalRealtimeRecognitionClient.Session session = client.start(text -> {
                 partials.incrementAndGet();
                 firstPartialMs.compareAndSet(
                         -1L, SystemClock.elapsedRealtime() - started);
            })) {
            streamClientStartMs.set(SystemClock.elapsedRealtime() - started);
            modelPssKb = processPssKb(context, ":local_stream");
            int frameSamples = 16_000 * 40 / 1_000;
            float[] samples = waveform.samples();
            for (int offset = 0; offset < samples.length; offset += frameSamples) {
                int count = Math.min(frameSamples, samples.length - offset);
                byte[] pcm = new byte[count * 2];
                for (int index = 0; index < count; index++) {
                    int value = Math.max(-32_768, Math.min(
                            32_767, Math.round(samples[offset + index] * 32_768.0f)));
                    pcm[index * 2] = (byte) (value & 0xff);
                    pcm[index * 2 + 1] = (byte) ((value >>> 8) & 0xff);
                }
                session.accept(pcm, pcm.length);
                Thread.sleep(40L);
            }
            finalText = session.finish();
        }

        long totalMs = SystemClock.elapsedRealtime() - started;
        Log.i("OpenTypelessOfflineSmoke", "partials=" + partials.get()
                + " stream_client_start_ms=" + streamClientStartMs.get()
                + " first_partial_ms=" + firstPartialMs.get()
                + " streaming_pss_kb=" + modelPssKb
                + " total_ms=" + totalMs
                + " first_pass_code_points=" + finalText.codePointCount(0, finalText.length()));
        assertTrue("Expected at least one live hypothesis", partials.get() > 0);
        assertTrue("Expected a non-empty first-pass final", !finalText.isBlank());
        assertTrue("Expected measurable private ASR process PSS", modelPssKb > 0L);
    }

    @Test
    public void optionalOfficialWaveProducesQualityFinal() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Assume.assumeTrue(OfflineStreamingRecognizer.isInstalled(context));
        Assume.assumeTrue(LocalOfflineRecognizer.isInstalled(context));
        File smoke = new File(
                OfflineStreamingModelStore.requireVerified(context).directory(),
                "smoke.wav");
        Assume.assumeTrue(smoke.isFile());
        byte[] wav = readAll(smoke);
        long qualityStarted = SystemClock.elapsedRealtime();
        String qualityFinal;
        long qualityPssKb;
        try (LocalOfflineRecognitionClient quality =
                     new LocalOfflineRecognitionClient(context)) {
            qualityFinal = quality.recognize(wav, "zh-CN", true).punctuatedText();
            qualityPssKb = processPssKb(context, ":local_quality");
        }
        long qualityMs = SystemClock.elapsedRealtime() - qualityStarted;
        Log.i("OpenTypelessOfflineSmoke", "quality_ms=" + qualityMs
                + " quality_pss_kb=" + qualityPssKb
                + " quality_code_points="
                + qualityFinal.codePointCount(0, qualityFinal.length()));
        assertTrue("Expected a non-empty quality final", !qualityFinal.isBlank());
        assertTrue("Expected measurable quality process PSS", qualityPssKb > 0L);
    }

    private static byte[] readAll(File file) throws IOException {
        if (file.length() <= 0 || file.length() > 2_000_000L) {
            throw new IOException("Smoke WAV size is invalid");
        }
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) throw new IOException("Smoke WAV ended early");
                offset += read;
            }
        }
        return data;
    }

    private static long processPssKb(Context context, String suffix) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null || manager.getRunningAppProcesses() == null) return -1L;
        String expected = context.getPackageName() + suffix;
        for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
            if (!expected.equals(process.processName)) continue;
            Debug.MemoryInfo[] memory = manager.getProcessMemoryInfo(new int[]{process.pid});
            if (memory.length != 1) return -1L;
            return memory[0].getTotalPss();
        }
        return -1L;
    }
}
