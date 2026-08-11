package com.opentypeless.android.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

public final class RecognitionDiagnosticsTest {
    @Test
    public void reportsPrivacyBoundaryFromActualRoute() {
        assertEquals(
                RecognitionRoute.PrivacyBoundary.ON_DEVICE,
                RecognitionRoute.direct(RecognitionBackend.SYSTEM_ON_DEVICE).privacyBoundary());
        assertEquals(
                RecognitionRoute.PrivacyBoundary.PROVIDER_DEPENDENT,
                RecognitionRoute.direct(RecognitionBackend.SYSTEM_DEFAULT).privacyBoundary());
        assertEquals(
                RecognitionRoute.PrivacyBoundary.NETWORK,
                RecognitionRoute.direct(RecognitionBackend.DASHSCOPE_STREAMING).privacyBoundary());
    }

    @Test
    public void capturesFirstOnlyMonotonicMilestonesAndTerminalResult() {
        RecognitionDiagnostics trace = RecognitionDiagnostics.start(
                RecognitionBackend.SYSTEM_DEFAULT,
                "zh_CN",
                10_000L,
                1_000L);

        assertTrue(trace.markReady(1_080L));
        assertFalse(trace.markReady(1_500L));
        assertTrue(trace.markFirstPartial(1_310L));
        assertFalse(trace.markFirstPartial(1_800L));
        assertTrue(trace.succeed(1_620L, 540L, "没问题。", false));
        assertFalse(trace.fail(1_700L));

        RecognitionDiagnostics.Snapshot snapshot = trace.snapshot();
        assertEquals("zh-CN", snapshot.languageTag());
        assertEquals(80L, snapshot.readyLatencyMs());
        assertEquals(310L, snapshot.firstPartialLatencyMs());
        assertEquals(620L, snapshot.terminalLatencyMs());
        assertEquals(540L, snapshot.audioDurationMs());
        assertEquals(4, snapshot.finalCodePointCount());
        assertEquals(RecognitionDiagnostics.Status.SUCCEEDED, snapshot.status());
    }

    @Test
    public void recordsExplicitFallbackWithoutProviderSecrets() {
        RecognitionDiagnostics trace = RecognitionDiagnostics.start(
                RecognitionBackend.SYSTEM_DEFAULT,
                "en-US",
                20_000L,
                2_000L);
        RecognitionRoute fallback = new RecognitionRoute(
                RecognitionBackend.SYSTEM_DEFAULT,
                RecognitionBackend.LOCAL_OFFLINE,
                RecognitionRoute.FallbackReason.ANDROID_MICROPHONE_BLOCKED);

        assertTrue(trace.updateRoute(fallback));
        assertTrue(trace.snapshot().route().fellBack());
        assertEquals(
                RecognitionRoute.PrivacyBoundary.ON_DEVICE,
                trace.snapshot().route().privacyBoundary());
    }

    @Test
    public void sanitizesUntrustedLanguageAndClampsNegativeElapsedTime() {
        RecognitionDiagnostics trace = RecognitionDiagnostics.start(
                RecognitionBackend.LOCAL_OFFLINE,
                "../../secret",
                30_000L,
                3_000L);
        assertTrue(trace.cancel(2_000L));

        assertEquals("und", trace.snapshot().languageTag());
        assertEquals(0L, trace.snapshot().terminalLatencyMs());
    }
}
