package com.opentypeless.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.diagnostics.RecognitionDiagnostics;
import com.opentypeless.android.diagnostics.RecognitionRoute;
import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

public final class AppVisualSystemTest {
    @Test
    public void compactAtNarrowWidth() {
        assertTrue(AppVisualSystem.compactFor(320, 1.0f));
        assertFalse(AppVisualSystem.compactFor(360, 1.0f));
    }

    @Test
    public void compactAtLargeFont() {
        assertTrue(AppVisualSystem.compactFor(411, 1.3f));
        assertFalse(AppVisualSystem.compactFor(411, 1.29f));
    }

    @Test
    public void statusSummaryUsesActualBackendOnlyAfterAUsableSuccessfulSession() {
        RecognitionDiagnostics.Snapshot success = new RecognitionDiagnostics.Snapshot(
                1,
                0,
                new RecognitionRoute(
                        RecognitionBackend.SYSTEM_DEFAULT,
                        RecognitionBackend.LOCAL_OFFLINE,
                        RecognitionRoute.FallbackReason.ANDROID_MICROPHONE_BLOCKED),
                "zh-CN",
                RecognitionDiagnostics.Status.SUCCEEDED,
                20,
                100,
                400,
                300,
                3,
                false);

        assertEquals(
                RecognitionBackend.LOCAL_OFFLINE,
                AppVisualSystem.routeForSummary(
                        RecognitionBackend.SYSTEM_DEFAULT,
                        success).actualBackend());

        RecognitionDiagnostics.Snapshot failed = new RecognitionDiagnostics.Snapshot(
                2,
                0,
                success.route(),
                "zh-CN",
                RecognitionDiagnostics.Status.FAILED,
                -1,
                -1,
                400,
                -1,
                -1,
                false);
        assertEquals(
                RecognitionBackend.SYSTEM_DEFAULT,
                AppVisualSystem.routeForSummary(
                        RecognitionBackend.SYSTEM_DEFAULT,
                        failed).actualBackend());
    }
}
