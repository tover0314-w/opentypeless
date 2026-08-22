package com.opentypeless.android.keyboard.clipboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class KeyboardClipboardPanelInstrumentedTest {
    @Test
    public void textCardPreviewsButPastesTheExactBoundedSnapshot() {
        onMain(() -> {
            Harness harness = new Harness();
            String text = "🙂".repeat(ClipboardPanelSnapshot.DEFAULT_PREVIEW_CODE_POINTS + 4);

            harness.panel.render(ClipboardPanelSnapshot.fromPrimaryText(text));

            assertEquals(View.VISIBLE, harness.panel.contentButton().getVisibility());
            assertTrue(harness.panel.contentButton().getText().toString().endsWith("…"));
            assertTrue(harness.panel.contentButton().performClick());
            assertEquals(text, harness.pasted.get());
        });
    }

    @Test
    public void clearRemovesClipboardBodyAndHeaderActionsRemainReachable() {
        onMain(() -> {
            Harness harness = new Harness();
            harness.panel.render(ClipboardPanelSnapshot.fromPrimaryText("temporary body"));
            harness.panel.clear();

            assertEquals("", harness.panel.contentButton().getText().toString());
            assertEquals(View.GONE, harness.panel.contentButton().getVisibility());
            harness.panel.contentButton().performClick();
            assertNull(harness.pasted.get());

            assertTrue(harness.panel.refreshButton().performClick());
            assertTrue(harness.panel.closeButton().performClick());
            assertEquals(1, harness.refreshes.get());
            assertEquals(1, harness.closes.get());

            int minimum = harness.dp(KeyboardClipboardPanel.MINIMUM_TOUCH_TARGET_DP);
            measure(harness.panel.refreshButton(), harness.dp(64), minimum);
            measure(harness.panel.closeButton(), harness.dp(64), minimum);
            assertTrue(harness.panel.refreshButton().getMeasuredHeight() >= minimum);
            assertTrue(harness.panel.closeButton().getMeasuredHeight() >= minimum);
        });
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static void measure(View view, int width, int height) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static final class Harness {
        final Context context = ApplicationProvider.getApplicationContext();
        final AtomicReference<String> pasted = new AtomicReference<>();
        final AtomicInteger refreshes = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final KeyboardClipboardPanel panel = new KeyboardClipboardPanel(
                context,
                new KeyboardClipboardPanel.Listener() {
                    @Override
                    public void onPaste(String text) {
                        pasted.set(text);
                    }

                    @Override
                    public void onRefresh() {
                        refreshes.incrementAndGet();
                    }

                    @Override
                    public void onClose() {
                        closes.incrementAndGet();
                    }
                });

        int dp(int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }
}
