package com.opentypeless.android.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.opentypeless.android.audio.Pcm16WaveDecoder;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** STR-004 device benchmark for the exact revision-pinned streaming candidate. */
@RunWith(AndroidJUnit4.class)
public final class StreamingCandidateBenchmarkInstrumentedTest {
    private static final String AUDIO_FILE = "str004-official-0.wav";
    private static final long AUDIO_BYTES = 321_744L;
    private static final String AUDIO_SHA256 =
            "7d93384ca14702cc584a7a33fe2fed92e89e708549161cb12ea38c916882103b";
    private static final int FRAME_SAMPLES = 16_000 * 40 / 1_000;
    private static final int WARM_RUNS = 5;

    @Test
    public void exactPinnedCandidateReportsFreshProcessAndWarmLatencyWithPeakPss()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Assume.assumeTrue(OfflineStreamingRecognizer.isInstalled(context));
        OfflineStreamingModelStore.InstalledModel model =
                OfflineStreamingModelStore.requireVerified(context);
        File audio = new File(model.directory(), AUDIO_FILE);
        Assume.assumeTrue(audio.isFile());
        assertEquals(AUDIO_BYTES, audio.length());
        assertEquals(AUDIO_SHA256, sha256(audio));

        byte[] wav = readAll(audio);
        Pcm16WaveDecoder.Waveform waveform = Pcm16WaveDecoder.decode(wav);
        assertEquals(16_000, waveform.sampleRate());
        byte[] pcm = Arrays.copyOfRange(wav, 44, wav.length);
        long audioDurationMs = Math.round(waveform.samples().length * 1_000.0 / 16_000.0);

        RunResult fresh;
        RunResult[] warm = new RunResult[WARM_RUNS];
        try (LocalRealtimeRecognitionClient client =
                     new LocalRealtimeRecognitionClient(context)) {
            fresh = runOnce(context, client, pcm);
            for (int index = 0; index < warm.length; index++) {
                warm[index] = runOnce(context, client, pcm);
            }
        }

        assertValid(fresh);
        for (RunResult result : warm) assertValid(result);

        long[] warmFirstPartialMs = values(warm, Value.FIRST_PARTIAL);
        long[] warmStopToFinalMs = values(warm, Value.STOP_TO_FINAL);
        long[] warmTotalMs = values(warm, Value.TOTAL);
        long[] warmPeakPssKib = values(warm, Value.PEAK_PSS);
        Bundle status = new Bundle();
        status.putString("str004_candidate_id", OfflineStreamingModelSpec.REALTIME.id());
        status.putLong("str004_model_bytes", OfflineStreamingModelSpec.REALTIME.downloadBytes());
        status.putString("str004_audio_sha256", AUDIO_SHA256);
        status.putLong("str004_audio_duration_ms", audioDurationMs);
        status.putLong("str004_fresh_first_partial_ms", fresh.firstPartialMs());
        status.putLong("str004_fresh_stop_to_final_ms", fresh.stopToFinalMs());
        status.putLong("str004_fresh_total_ms", fresh.totalMs());
        status.putLong("str004_fresh_peak_pss_kib", fresh.peakPssKib());
        status.putLong("str004_warm_first_partial_ms_p50", percentile(warmFirstPartialMs, 0.50));
        status.putLong("str004_warm_first_partial_ms_p95", percentile(warmFirstPartialMs, 0.95));
        status.putLong("str004_warm_stop_to_final_ms_p50", percentile(warmStopToFinalMs, 0.50));
        status.putLong("str004_warm_stop_to_final_ms_p95", percentile(warmStopToFinalMs, 0.95));
        status.putLong("str004_warm_total_ms_p50", percentile(warmTotalMs, 0.50));
        status.putLong("str004_warm_total_ms_p95", percentile(warmTotalMs, 0.95));
        status.putLong("str004_warm_peak_pss_kib_max", max(warmPeakPssKib));
        status.putLong("str004_fresh_partial_count", fresh.partialCount());
        status.putLong("str004_warm_partial_count_min", min(values(warm, Value.PARTIAL_COUNT)));
        status.putLong("str004_final_code_points_min", Math.min(
                fresh.finalCodePoints(), min(values(warm, Value.FINAL_CODE_POINTS))));
        status.putString("str004_contains_audio", "false");
        status.putString("str004_contains_transcript", "false");
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.sendStatus(2, status);
    }

    private static RunResult runOnce(
            Context context,
            LocalRealtimeRecognitionClient client,
            byte[] pcm) throws Exception {
        AtomicInteger partialCount = new AtomicInteger();
        AtomicLong firstPartialMs = new AtomicLong(-1L);
        long started = SystemClock.elapsedRealtime();
        try (MemorySampler sampler = new MemorySampler(context, ":local_stream")) {
            sampler.start();
            String finalText;
            try (LocalRealtimeRecognitionClient.Session session = client.start(text -> {
                partialCount.incrementAndGet();
                firstPartialMs.compareAndSet(
                        -1L, SystemClock.elapsedRealtime() - started);
            })) {
                for (int offset = 0; offset < pcm.length; offset += FRAME_SAMPLES * 2) {
                    int end = Math.min(pcm.length, offset + FRAME_SAMPLES * 2);
                    byte[] frame = Arrays.copyOfRange(pcm, offset, end);
                    session.accept(frame, frame.length);
                    long frameMs = Math.max(1L, Math.round(
                            (frame.length / 2.0) * 1_000.0 / 16_000.0));
                    SystemClock.sleep(frameMs);
                }
                long stopStarted = SystemClock.elapsedRealtime();
                finalText = session.finish();
                long completed = SystemClock.elapsedRealtime();
                sampler.sampleNow();
                return new RunResult(
                        firstPartialMs.get(),
                        completed - stopStarted,
                        completed - started,
                        sampler.peakPssKib(),
                        partialCount.get(),
                        finalText.codePointCount(0, finalText.length()));
            }
        }
    }

    private static void assertValid(RunResult result) {
        assertTrue("Expected a live partial", result.firstPartialMs() >= 0L);
        assertTrue("Expected a bounded final", result.finalCodePoints() > 0L);
        assertTrue("Expected at least one partial", result.partialCount() > 0L);
        assertTrue("Expected measurable isolated-process PSS", result.peakPssKib() > 0L);
        assertTrue("Expected a measured stop-to-final interval", result.stopToFinalMs() >= 0L);
    }

    private static byte[] readAll(File file) throws IOException {
        if (file.length() != AUDIO_BYTES) throw new IOException("Benchmark WAV size is invalid");
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) throw new IOException("Benchmark WAV ended early");
                offset += read;
            }
        }
        return data;
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private enum Value {
        FIRST_PARTIAL,
        STOP_TO_FINAL,
        TOTAL,
        PEAK_PSS,
        PARTIAL_COUNT,
        FINAL_CODE_POINTS
    }

    private static long[] values(RunResult[] results, Value value) {
        long[] output = new long[results.length];
        for (int index = 0; index < results.length; index++) {
            RunResult result = results[index];
            output[index] = switch (value) {
                case FIRST_PARTIAL -> result.firstPartialMs();
                case STOP_TO_FINAL -> result.stopToFinalMs();
                case TOTAL -> result.totalMs();
                case PEAK_PSS -> result.peakPssKib();
                case PARTIAL_COUNT -> result.partialCount();
                case FINAL_CODE_POINTS -> result.finalCodePoints();
            };
        }
        return output;
    }

    private static long percentile(long[] values, double percentile) {
        long[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        int rank = Math.max(1, (int) Math.ceil(percentile * sorted.length));
        return sorted[rank - 1];
    }

    private static long min(long[] values) {
        long result = Long.MAX_VALUE;
        for (long value : values) result = Math.min(result, value);
        return result;
    }

    private static long max(long[] values) {
        long result = Long.MIN_VALUE;
        for (long value : values) result = Math.max(result, value);
        return result;
    }

    private record RunResult(
            long firstPartialMs,
            long stopToFinalMs,
            long totalMs,
            long peakPssKib,
            long partialCount,
            long finalCodePoints) {}

    private static final class MemorySampler implements AutoCloseable {
        private final Context context;
        private final String processSuffix;
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicLong peakPssKib = new AtomicLong();
        private final CountDownLatch started = new CountDownLatch(1);
        private Thread thread;

        MemorySampler(Context context, String processSuffix) {
            this.context = context;
            this.processSuffix = processSuffix;
        }

        void start() throws InterruptedException {
            if (!running.compareAndSet(false, true)) return;
            thread = new Thread(() -> {
                started.countDown();
                while (running.get()) {
                    sampleNow();
                    SystemClock.sleep(20L);
                }
            }, "str004-pss-sampler");
            thread.start();
            assertTrue("PSS sampler did not start", started.await(2L, TimeUnit.SECONDS));
        }

        void sampleNow() {
            long measured = processPssKib(context, processSuffix);
            if (measured > 0L) peakPssKib.accumulateAndGet(measured, Math::max);
        }

        long peakPssKib() {
            return peakPssKib.get();
        }

        @Override
        public void close() throws InterruptedException {
            running.set(false);
            Thread current = thread;
            if (current != null) current.join(2_000L);
        }
    }

    private static long processPssKib(Context context, String suffix) {
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
