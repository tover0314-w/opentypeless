package com.opentypeless.android.diagnostics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

public final class RecognitionDiagnosticsJsonTest {
    @Test
    public void exportsOnlyRedactedRouteAndNumericMeasurements() {
        RecognitionDiagnostics trace = RecognitionDiagnostics.start(
                RecognitionBackend.SYSTEM_DEFAULT,
                "zh-CN",
                10_000L,
                1_000L);
        trace.markReady(1_100L);
        trace.markStopRequested(1_300L);
        trace.markRawFinal(1_500L);
        trace.succeed(1_600L, 450L, "不能进入导出的秘密转写", false);

        String json = RecognitionDiagnosticsJson.encode(trace.snapshot());

        assertTrue(json.contains("\"selected_backend\": \"SYSTEM_DEFAULT\""));
        assertTrue(json.contains("\"ready_latency_ms\": 100"));
        assertTrue(json.contains("\"release_to_raw_final_latency_ms\": 200"));
        assertTrue(json.contains("\"text_processing_latency_ms\": 100"));
        assertTrue(json.contains("\"release_to_terminal_latency_ms\": 300"));
        assertTrue(json.contains("\"final_code_point_count\": 11"));
        assertFalse(json.contains("秘密转写"));
        assertFalse(json.contains("transcript"));
        assertFalse(json.contains("api_key"));
        assertFalse(json.contains("package_name"));
        assertFalse(json.contains("endpoint"));
    }
}
