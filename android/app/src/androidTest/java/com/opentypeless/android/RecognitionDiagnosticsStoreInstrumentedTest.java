package com.opentypeless.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.diagnostics.RecognitionDiagnostics;
import com.opentypeless.android.diagnostics.RecognitionDiagnosticsStore;
import com.opentypeless.android.diagnostics.RecognitionRoute;
import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class RecognitionDiagnosticsStoreInstrumentedTest {
    @Test
    public void persistsOnlyRedactedRouteAndTimingSnapshot() {
        Context context = ApplicationProvider.getApplicationContext();
        RecognitionDiagnosticsStore store = new RecognitionDiagnosticsStore(context);
        store.clear();
        assertNull(store.load());

        RecognitionDiagnostics trace = RecognitionDiagnostics.start(
                RecognitionBackend.SYSTEM_DEFAULT,
                "zh-CN",
                System.currentTimeMillis(),
                1_000L);
        trace.updateRoute(new RecognitionRoute(
                RecognitionBackend.SYSTEM_DEFAULT,
                RecognitionBackend.LOCAL_OFFLINE,
                RecognitionRoute.FallbackReason.ANDROID_MICROPHONE_BLOCKED));
        trace.markReady(1_080L);
        trace.markFirstPartial(1_250L);
        trace.succeed(1_500L, 420L, "这段文字绝不能落盘", false);
        store.save(trace.snapshot());

        RecognitionDiagnostics.Snapshot restored = store.load();
        assertNotNull(restored);
        assertEquals(RecognitionBackend.SYSTEM_DEFAULT, restored.route().selectedBackend());
        assertEquals(RecognitionBackend.LOCAL_OFFLINE, restored.route().actualBackend());
        assertEquals(80L, restored.readyLatencyMs());
        assertEquals(250L, restored.firstPartialLatencyMs());
        assertEquals(9, restored.finalCodePointCount());
        assertEquals(RecognitionDiagnostics.Status.SUCCEEDED, restored.status());
    }
}
