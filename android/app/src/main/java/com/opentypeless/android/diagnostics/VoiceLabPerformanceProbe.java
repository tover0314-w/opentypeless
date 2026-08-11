package com.opentypeless.android.diagnostics;

import android.app.ActivityManager;
import android.content.Context;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;
import android.os.Process;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Best-effort process-boundary resource probe used only while Voice Lab is visible. */
public final class VoiceLabPerformanceProbe implements AutoCloseable {
    public record Snapshot(
            long startPssKb,
            long peakPssKb,
            long endPssKb,
            long localAsrStartPssKb,
            long localAsrPeakPssKb,
            long localAsrEndPssKb,
            long cpuDeltaMs,
            long appRxDeltaBytes,
            long appTxDeltaBytes,
            int startThermalStatus,
            int endThermalStatus) {}

    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
    private final PowerManager powerManager;
    private final ActivityManager activityManager;
    private final String localAsrProcessName;
    private ScheduledFuture<?> samplingTask;
    private boolean running;
    private long startPssKb;
    private long peakPssKb;
    private long startCpuMs;
    private long localAsrStartPssKb;
    private long localAsrPeakPssKb;
    private long startRxBytes;
    private long startTxBytes;
    private int startThermalStatus;

    public VoiceLabPerformanceProbe(Context context) {
        Context applicationContext = context.getApplicationContext();
        powerManager = (PowerManager) applicationContext.getSystemService(
                Context.POWER_SERVICE);
        activityManager = (ActivityManager) applicationContext.getSystemService(
                Context.ACTIVITY_SERVICE);
        localAsrProcessName = applicationContext.getPackageName() + ":local_asr";
    }

    public synchronized void start() {
        cancelTask();
        running = true;
        startPssKb = currentPssKb();
        peakPssKb = startPssKb;
        localAsrStartPssKb = currentLocalAsrPssKb();
        localAsrPeakPssKb = localAsrStartPssKb;
        startCpuMs = Process.getElapsedCpuTime();
        startRxBytes = TrafficStats.getUidRxBytes(Process.myUid());
        startTxBytes = TrafficStats.getUidTxBytes(Process.myUid());
        startThermalStatus = currentThermalStatus();
        samplingTask = sampler.scheduleAtFixedRate(
                this::sampleSafely,
                250L,
                250L,
                TimeUnit.MILLISECONDS);
    }

    public synchronized Snapshot finish() {
        if (!running) return null;
        running = false;
        cancelTask();
        long endPssKb = currentPssKb();
        peakPssKb = Math.max(peakPssKb, endPssKb);
        long localAsrEndPssKb = currentLocalAsrPssKb();
        localAsrPeakPssKb = maxPss(localAsrPeakPssKb, localAsrEndPssKb);
        return new Snapshot(
                startPssKb,
                peakPssKb,
                endPssKb,
                localAsrStartPssKb,
                localAsrPeakPssKb,
                localAsrEndPssKb,
                Math.max(0L, Process.getElapsedCpuTime() - startCpuMs),
                delta(startRxBytes, TrafficStats.getUidRxBytes(Process.myUid())),
                delta(startTxBytes, TrafficStats.getUidTxBytes(Process.myUid())),
                startThermalStatus,
                currentThermalStatus());
    }

    private void sampleSafely() {
        try {
            long pssKb = currentPssKb();
            long localAsrPssKb = currentLocalAsrPssKb();
            synchronized (this) {
                if (running) {
                    peakPssKb = Math.max(peakPssKb, pssKb);
                    localAsrPeakPssKb = maxPss(localAsrPeakPssKb, localAsrPssKb);
                }
            }
        } catch (RuntimeException ignored) {
            // A measurement failure must never affect recognition.
        }
    }

    private static long currentPssKb() {
        return Math.max(0L, Debug.getPss());
    }

    private long currentLocalAsrPssKb() {
        if (activityManager == null) return -1L;
        try {
            java.util.List<ActivityManager.RunningAppProcessInfo> processes =
                    activityManager.getRunningAppProcesses();
            if (processes == null) return -1L;
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (!localAsrProcessName.equals(process.processName) || process.pid <= 0) continue;
                Debug.MemoryInfo[] memory = activityManager.getProcessMemoryInfo(
                        new int[]{process.pid});
                if (memory.length == 1 && memory[0] != null) {
                    return Math.max(0L, memory[0].getTotalPss());
                }
            }
        } catch (RuntimeException ignored) {
            // Process visibility and meminfo access differ across Android releases/OEMs.
        }
        return -1L;
    }

    private static long maxPss(long first, long second) {
        if (first < 0L) return second;
        if (second < 0L) return first;
        return Math.max(first, second);
    }

    private int currentThermalStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || powerManager == null) return -1;
        return powerManager.getCurrentThermalStatus();
    }

    private static long delta(long start, long end) {
        if (start == TrafficStats.UNSUPPORTED || end == TrafficStats.UNSUPPORTED || end < start) {
            return -1L;
        }
        return end - start;
    }

    private void cancelTask() {
        if (samplingTask != null) samplingTask.cancel(false);
        samplingTask = null;
    }

    @Override
    public synchronized void close() {
        running = false;
        cancelTask();
        sampler.shutdownNow();
    }
}
