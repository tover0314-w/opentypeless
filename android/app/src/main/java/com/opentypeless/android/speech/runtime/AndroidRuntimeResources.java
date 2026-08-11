package com.opentypeless.android.speech.runtime;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;

/** Bounded Android resource probe used once when a local v2 session is prepared. */
public final class AndroidRuntimeResources {
    private static final long EXPECTED_STREAMING_WORKER_MIB = 128L;
    private static final long EXPECTED_QUALITY_WORKER_MIB = 320L;

    private AndroidRuntimeResources() {}

    public static RuntimeResources snapshot(Context context) {
        Context app = context.getApplicationContext();
        ActivityManager manager = app.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(memory);
        long total = Math.max(1L, memory.totalMem / (1_024L * 1_024L));
        long available = Math.max(0L, Math.min(total, memory.availMem / (1_024L * 1_024L)));
        long appPss = Math.max(0L, Debug.getPss() / 1_024L);
        return new RuntimeResources(
                total,
                available,
                appPss,
                EXPECTED_STREAMING_WORKER_MIB,
                EXPECTED_QUALITY_WORKER_MIB,
                thermal(app),
                memory.lowMemory);
    }

    private static ThermalLevel thermal(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalLevel.UNKNOWN;
        PowerManager manager = context.getSystemService(PowerManager.class);
        if (manager == null) return ThermalLevel.UNKNOWN;
        return switch (manager.getCurrentThermalStatus()) {
            case PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NONE;
            case PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT;
            case PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE;
            case PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE;
            case PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL;
            case PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalLevel.EMERGENCY;
            case PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.SHUTDOWN;
            default -> ThermalLevel.UNKNOWN;
        };
    }
}
