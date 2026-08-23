package com.opentypeless.android.keyboard.emoji;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class KeyboardEmojiPanelInstrumentedTest {
    @Test
    public void recentCategorySelectsExactMultiCodePointEmojiAndMeetsTouchTargets() {
        onMain(() -> {
            Harness harness = new Harness();
            EmojiRecents recents = EmojiRecents.empty().record("😀").record("🐻‍❄️");
            harness.panel.render(recents, true);

            assertEquals(EmojiCatalog.Category.RECENT, harness.panel.selectedCategory());
            assertEquals(View.VISIBLE,
                    harness.panel.categoryButton(EmojiCatalog.Category.RECENT).getVisibility());
            assertEquals(2, harness.panel.grid().getChildCount());
            Button first = (Button) harness.panel.grid().getChildAt(0);
            assertEquals("🐻‍❄️", first.getText().toString());
            assertTrue(first.performClick());
            assertEquals("🐻‍❄️", harness.selected.get());

            int minimum = harness.dp(KeyboardEmojiPanel.MINIMUM_TOUCH_TARGET_DP);
            measure(first, minimum, minimum);
            measure(harness.panel.closeButton(), harness.dp(64), minimum);
            assertTrue(first.getMeasuredHeight() >= minimum);
            assertTrue(harness.panel.closeButton().getMeasuredHeight() >= minimum);
        });
    }

    @Test
    public void sensitiveProjectionHidesRecentsButKeepsStaticCategoriesAndClose() {
        onMain(() -> {
            Harness harness = new Harness();
            harness.panel.render(EmojiRecents.empty().record("😀"), false);

            assertEquals(View.GONE,
                    harness.panel.categoryButton(EmojiCatalog.Category.RECENT).getVisibility());
            assertEquals(EmojiCatalog.Category.SMILEYS, harness.panel.selectedCategory());
            assertEquals(21, harness.panel.grid().getChildCount());
            Button first = (Button) harness.panel.grid().getChildAt(0);
            assertEquals("😀", first.getText().toString());
            assertTrue(first.performClick());
            assertEquals("😀", harness.selected.get());

            assertTrue(harness.panel.closeButton().performClick());
            assertEquals(1, harness.closes.get());
            harness.panel.clear();
            assertEquals(0, harness.panel.grid().getChildCount());
        });
    }

    @Test
    public void categorySelectionRendersOnlyTheSelectedBoundedPage() {
        onMain(() -> {
            Harness harness = new Harness();
            harness.panel.render(EmojiRecents.empty(), true);

            assertTrue(harness.panel.categoryButton(
                    EmojiCatalog.Category.SYMBOLS).performClick());
            assertEquals(EmojiCatalog.Category.SYMBOLS, harness.panel.selectedCategory());
            assertEquals(EmojiCatalog.emoji(
                    EmojiCatalog.Category.SYMBOLS).size(), harness.panel.grid().getChildCount());
            assertEquals("❤️", ((Button) harness.panel.grid().getChildAt(0)).getText().toString());
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
        final AtomicReference<String> selected = new AtomicReference<>();
        final AtomicInteger closes = new AtomicInteger();
        final KeyboardEmojiPanel panel = new KeyboardEmojiPanel(
                context,
                new KeyboardEmojiPanel.Listener() {
                    @Override
                    public void onEmojiSelected(String emoji) {
                        selected.set(emoji);
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
