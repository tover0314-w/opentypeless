package com.opentypeless.android.keyboard.toolbar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class KeyboardToolbarLayoutInstrumentedTest {
    @Test
    public void statusPrimaryAndOverflowUseStableOrderedSlots() {
        onMain(() -> {
            Harness harness = new Harness();
            View indicator = new View(harness.context);
            TextView status = new TextView(harness.context);
            Button mode = action(harness.context, "Mode");
            Button voice = action(harness.context, "Voice");
            Button more = action(harness.context, "More");

            harness.layout.attachStatusIndicator(indicator, 30);
            harness.layout.attachStatusText(status);
            harness.layout.attachPrimaryAction("voice.mode", mode, 64);
            harness.layout.attachPrimaryAction("voice.long_dictation", voice, 64);
            harness.layout.attachOverflowAnchor("more", more);

            assertEquals(3, harness.root.getChildCount());
            assertEquals(2, harness.layout.statusSlot().getChildCount());
            assertEquals(2, harness.layout.primarySlot().getChildCount());
            assertEquals(KeyboardToolbarLayout.STATUS_SLOT_TAG,
                    harness.layout.statusSlot().getTag());
            assertEquals(KeyboardToolbarLayout.PRIMARY_SLOT_TAG,
                    harness.layout.primarySlot().getTag());
            assertEquals(KeyboardToolbarLayout.OVERFLOW_ANCHOR_TAG, more.getTag());
            assertEquals(KeyboardToolbarLayout.Placement.PRIMARY,
                    harness.layout.placementOf("voice.mode"));
            assertEquals(KeyboardToolbarLayout.Placement.OVERFLOW,
                    harness.layout.placementOf("more"));
        });
    }

    @Test
    public void everyInteractiveSlotKeepsAtLeastFortyEightDp() {
        onMain(() -> {
            Harness harness = new Harness();
            Button mode = action(harness.context, "Mode");
            Button voice = action(harness.context, "Voice");
            Button more = action(harness.context, "More");
            harness.layout.attachPrimaryAction("voice.mode", mode, 48);
            harness.layout.attachPrimaryAction("voice.long_dictation", voice, 48);
            harness.layout.attachOverflowAnchor("more", more);
            int minimum = harness.dp(KeyboardToolbarLayout.MINIMUM_TOUCH_TARGET_DP);

            for (Button action : new Button[] {mode, voice, more}) {
                assertTrue(action.getMinimumWidth() >= minimum);
                assertTrue(action.getMinimumHeight() >= minimum);
                LinearLayout.LayoutParams params =
                        (LinearLayout.LayoutParams) action.getLayoutParams();
                assertTrue(params.width >= minimum);
                assertTrue(params.height >= minimum);
            }
        });
    }

    @Test
    public void narrowLandscapeMeasureKeepsEveryFixedActionInsideTheToolbar() {
        onMain(() -> {
            Harness harness = new Harness();
            harness.layout.attachStatusIndicator(new View(harness.context), 30);
            TextView status = new TextView(harness.context);
            status.setSingleLine(true);
            status.setText("A deliberately long status that must yield to fixed actions");
            harness.layout.attachStatusText(status);
            harness.layout.attachPrimaryAction("voice.mode", action(harness.context, "Mode"), 64);
            harness.layout.attachPrimaryAction(
                    "voice.long_dictation", action(harness.context, "Voice"), 64);
            harness.layout.attachOverflowAnchor("more", action(harness.context, "More"));

            int width = harness.dp(320);
            int height = harness.dp(48);
            harness.root.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            harness.root.layout(0, 0, width, height);

            assertEquals(width, harness.root.getMeasuredWidth());
            assertEquals(height, harness.root.getMeasuredHeight());
            for (int index = 0; index < harness.root.getChildCount(); index++) {
                View child = harness.root.getChildAt(index);
                assertTrue("child starts outside toolbar", child.getLeft() >= 0);
                assertTrue("child is clipped at toolbar end", child.getRight() <= width);
            }
            assertTrue("status text did not yield horizontal space",
                    status.getMeasuredWidth() < width);
        });
    }

    @Test
    public void thirdPrimaryActionFailsClosedIntoOverflow() {
        onMain(() -> {
            Harness harness = new Harness();
            harness.layout.attachPrimaryAction("one", action(harness.context, "One"), 48);
            harness.layout.attachPrimaryAction("two", action(harness.context, "Two"), 48);
            assertThrows(IllegalStateException.class, () -> harness.layout.attachPrimaryAction(
                    "three", action(harness.context, "Three"), 48));
        });
    }

    @Test
    public void duplicateInvalidAndUnlabeledPlacementsFailClosed() {
        onMain(() -> {
            Harness harness = new Harness();
            harness.layout.attachPrimaryAction("voice.mode", action(harness.context, "Mode"), 48);
            assertThrows(IllegalArgumentException.class, () -> harness.layout.attachOverflowAnchor(
                    "voice.mode", action(harness.context, "More")));
            assertThrows(IllegalArgumentException.class, () -> harness.layout.attachOverflowAnchor(
                    "../more", action(harness.context, "More")));
            Button unlabeled = new Button(harness.context);
            unlabeled.setOnClickListener(ignored -> {});
            assertThrows(IllegalArgumentException.class, () -> harness.layout.attachOverflowAnchor(
                    "more", unlabeled));
        });
    }

    @Test
    public void privacyVisibilityHidesAndRestoresOnlyTheRequestedActions() {
        onMain(() -> {
            Harness harness = new Harness();
            Button mode = action(harness.context, "Mode");
            Button voice = action(harness.context, "Voice");
            Button more = action(harness.context, "More");
            harness.layout.attachPrimaryAction("voice.mode", mode, 64);
            harness.layout.attachPrimaryAction("voice.long_dictation", voice, 64);
            harness.layout.attachOverflowAnchor("more", more);

            harness.layout.setActionVisible("voice.mode", false);
            harness.layout.setActionVisible("voice.long_dictation", false);
            assertFalse(harness.layout.isActionVisible("voice.mode"));
            assertFalse(harness.layout.isActionVisible("voice.long_dictation"));
            assertTrue(harness.layout.isActionVisible("more"));

            harness.layout.setActionVisible("voice.mode", true);
            harness.layout.setActionVisible("voice.long_dictation", true);
            assertTrue(harness.layout.isActionVisible("voice.mode"));
            assertTrue(harness.layout.isActionVisible("voice.long_dictation"));
            assertThrows(IllegalArgumentException.class,
                    () -> harness.layout.setActionVisible("unknown", true));
        });
    }

    private static Button action(Context context, String description) {
        Button button = new Button(context);
        button.setText(description);
        button.setContentDescription(description);
        button.setOnClickListener(ignored -> {});
        return button;
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static final class Harness {
        final Context context = ApplicationProvider.getApplicationContext();
        final LinearLayout root = new LinearLayout(context);
        final KeyboardToolbarLayout layout = new KeyboardToolbarLayout(context, root);

        int dp(int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }
}
