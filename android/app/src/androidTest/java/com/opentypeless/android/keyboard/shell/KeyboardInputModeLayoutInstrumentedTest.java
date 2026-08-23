package com.opentypeless.android.keyboard.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.opentypeless.android.R;
import com.opentypeless.android.keyboard.ui.CenteredIconButton;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class KeyboardInputModeLayoutInstrumentedTest {
    @Test
    public void voiceStartsVisibleAndCompactToggleSelectsExactlyOnePage() {
        onMain(() -> {
            Harness harness = new Harness();

            assertEquals(KeyboardInputModeLayout.Mode.VOICE, harness.layout.mode());
            assertEquals(1, harness.layout.root().getChildCount());
            assertEquals(View.VISIBLE, harness.voicePage.getVisibility());
            assertEquals(View.GONE, harness.qwertyPage.getVisibility());
            assertEquals(Gravity.CENTER, harness.toggle.getGravity());
            measure(harness.toggle, 96, 48);
            Rect voiceBounds = harness.toggle.centeredIconBounds();
            assertTrue("voice icon bounds=" + voiceBounds,
                    Math.abs(harness.toggle.getWidth() / 2 - voiceBounds.centerX()) <= 1);
            assertTrue("voice icon bounds=" + voiceBounds,
                    Math.abs(harness.toggle.getHeight() / 2 - voiceBounds.centerY()) <= 1);

            assertTrue(harness.toggle.performClick());
            assertEquals(KeyboardInputModeLayout.Mode.QWERTY, harness.layout.mode());
            assertEquals(View.GONE, harness.voicePage.getVisibility());
            assertEquals(View.VISIBLE, harness.qwertyPage.getVisibility());
            Rect qwertyBounds = harness.toggle.centeredIconBounds();
            assertTrue("qwerty icon bounds=" + qwertyBounds,
                    Math.abs(harness.toggle.getWidth() / 2 - qwertyBounds.centerX()) <= 1);
            assertTrue("qwerty icon bounds=" + qwertyBounds,
                    Math.abs(harness.toggle.getHeight() / 2 - qwertyBounds.centerY()) <= 1);
        });
    }

    @Test
    public void sensitivePolicyHidesVoiceAndForcesQwertyFailClosed() {
        onMain(() -> {
            Harness harness = new Harness();

            harness.layout.setVoiceAvailable(false);

            assertFalse(harness.layout.voiceAvailable());
            assertEquals(View.GONE, harness.toggle.getVisibility());
            assertEquals(KeyboardInputModeLayout.Mode.QWERTY, harness.layout.mode());
            assertEquals(View.VISIBLE, harness.qwertyPage.getVisibility());
            harness.layout.select(KeyboardInputModeLayout.Mode.VOICE);
            assertEquals(KeyboardInputModeLayout.Mode.QWERTY, harness.layout.mode());
        });
    }

    @Test
    public void activeVoiceSessionCanLockToggleWithoutChangingTheVisiblePage() {
        onMain(() -> {
            Harness harness = new Harness();

            harness.layout.setSwitchingEnabled(false);

            assertEquals(KeyboardInputModeLayout.Mode.VOICE, harness.layout.mode());
            assertFalse(harness.toggle.isEnabled());

            harness.layout.setSwitchingEnabled(true);
            assertTrue(harness.toggle.isEnabled());
        });
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static void measure(View view, int width, int height) {
        int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
        view.measure(widthSpec, heightSpec);
        view.layout(0, 0, width, height);
    }

    private static final class Harness {
        final CenteredIconButton toggle;
        final TextView voicePage;
        final TextView qwertyPage;
        final KeyboardInputModeLayout layout;

        Harness() {
            Context context = ApplicationProvider.getApplicationContext();
            toggle = button(context, "Mode");
            voicePage = new TextView(context);
            qwertyPage = new TextView(context);
            layout = new KeyboardInputModeLayout(
                    context,
                    toggle,
                    voicePage,
                    qwertyPage,
                    KeyboardInputModeLayout.Mode.VOICE);
        }

        private static CenteredIconButton button(Context context, String label) {
            CenteredIconButton button = new CenteredIconButton(context);
            button.setText(label);
            button.setContentDescription(label);
            button.setBackgroundResource(R.drawable.ime_key_background);
            return button;
        }
    }
}
