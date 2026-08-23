package com.opentypeless.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.diagnostics.RecognitionDiagnostics;
import com.opentypeless.android.diagnostics.RecognitionRoute;
import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

public final class SetupChecklistTest {
    @Test
    public void acceptsSuccessfulCurrentRouteIncludingAnExplicitFallback() {
        RecognitionDiagnostics.Snapshot snapshot = snapshot(
                RecognitionBackend.SYSTEM_DEFAULT,
                RecognitionBackend.LOCAL_OFFLINE,
                "zh-CN",
                RecognitionDiagnostics.Status.SUCCEEDED,
                3);

        assertTrue(SetupChecklist.successfulTestMatches(
                RecognitionBackend.SYSTEM_DEFAULT,
                "zh_CN",
                snapshot));
    }

    @Test
    public void rejectsSuccessfulDiagnosticFromAnOlderBackendOrLanguage() {
        RecognitionDiagnostics.Snapshot snapshot = snapshot(
                RecognitionBackend.SYSTEM_DEFAULT,
                RecognitionBackend.SYSTEM_DEFAULT,
                "zh-CN",
                RecognitionDiagnostics.Status.SUCCEEDED,
                3);

        assertFalse(SetupChecklist.successfulTestMatches(
                RecognitionBackend.LOCAL_OFFLINE,
                "zh-CN",
                snapshot));
        assertFalse(SetupChecklist.successfulTestMatches(
                RecognitionBackend.SYSTEM_DEFAULT,
                "en-US",
                snapshot));
    }

    @Test
    public void rejectsFailureAndEmptyFinalButSupportsAutoLanguage() {
        assertFalse(SetupChecklist.successfulTestMatches(
                RecognitionBackend.SYSTEM_DEFAULT,
                "",
                snapshot(
                        RecognitionBackend.SYSTEM_DEFAULT,
                        RecognitionBackend.SYSTEM_DEFAULT,
                        "und",
                        RecognitionDiagnostics.Status.FAILED,
                        -1)));
        assertFalse(SetupChecklist.successfulTestMatches(
                RecognitionBackend.SYSTEM_DEFAULT,
                "",
                snapshot(
                        RecognitionBackend.SYSTEM_DEFAULT,
                        RecognitionBackend.SYSTEM_DEFAULT,
                        "und",
                        RecognitionDiagnostics.Status.SUCCEEDED,
                        0)));
        assertTrue(SetupChecklist.successfulTestMatches(
                RecognitionBackend.SYSTEM_DEFAULT,
                "",
                snapshot(
                        RecognitionBackend.SYSTEM_DEFAULT,
                        RecognitionBackend.SYSTEM_DEFAULT,
                        "und",
                        RecognitionDiagnostics.Status.SUCCEEDED,
                        1)));
    }

    private static RecognitionDiagnostics.Snapshot snapshot(
            RecognitionBackend selected,
            RecognitionBackend actual,
            String language,
            RecognitionDiagnostics.Status status,
            int finalCodePoints) {
        RecognitionRoute.FallbackReason fallback = selected == actual
                ? RecognitionRoute.FallbackReason.NONE
                : RecognitionRoute.FallbackReason.ANDROID_MICROPHONE_BLOCKED;
        return new RecognitionDiagnostics.Snapshot(
                1L,
                1L,
                new RecognitionRoute(selected, actual, fallback),
                language,
                status,
                10L,
                20L,
                30L,
                500L,
                finalCodePoints,
                false);
    }
}
