package com.opentypeless.android.keyboard.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class KeyboardInputModeLayoutInstrumentedTest {
    @Test
    public void voiceStartsVisibleAndTabsSelectExactlyOnePage() {
        onMain(() -> {
            Harness harness = new Harness();

            assertEquals(KeyboardInputModeLayout.Mode.VOICE, harness.layout.mode());
            assertEquals(View.VISIBLE, harness.voicePage.getVisibility());
            assertEquals(View.GONE, harness.qwertyPage.getVisibility());
            assertTrue(harness.voiceTab.isSelected());

            assertTrue(harness.qwertyTab.performClick());
            assertEquals(KeyboardInputModeLayout.Mode.QWERTY, harness.layout.mode());
            assertEquals(View.GONE, harness.voicePage.getVisibility());
            assertEquals(View.VISIBLE, harness.qwertyPage.getVisibility());
            assertTrue(harness.qwertyTab.isSelected());
        });
    }

    @Test
    public void sensitivePolicyHidesVoiceAndForcesQwertyFailClosed() {
        onMain(() -> {
            Harness harness = new Harness();

            harness.layout.setVoiceAvailable(false);

            assertFalse(harness.layout.voiceAvailable());
            assertEquals(View.GONE, harness.voiceTab.getVisibility());
            assertEquals(KeyboardInputModeLayout.Mode.QWERTY, harness.layout.mode());
            assertEquals(View.VISIBLE, harness.qwertyPage.getVisibility());
            harness.layout.select(KeyboardInputModeLayout.Mode.VOICE);
            assertEquals(KeyboardInputModeLayout.Mode.QWERTY, harness.layout.mode());
        });
    }

    @Test
    public void activeVoiceSessionCanLockBothTabsWithoutChangingTheVisiblePage() {
        onMain(() -> {
            Harness harness = new Harness();

            harness.layout.setSwitchingEnabled(false);

            assertEquals(KeyboardInputModeLayout.Mode.VOICE, harness.layout.mode());
            assertFalse(harness.voiceTab.isEnabled());
            assertFalse(harness.qwertyTab.isEnabled());

            harness.layout.setSwitchingEnabled(true);
            assertTrue(harness.qwertyTab.isEnabled());
        });
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private static final class Harness {
        final Button voiceTab;
        final Button qwertyTab;
        final TextView voicePage;
        final TextView qwertyPage;
        final KeyboardInputModeLayout layout;

        Harness() {
            Context context = ApplicationProvider.getApplicationContext();
            voiceTab = button(context, "Voice");
            qwertyTab = button(context, "Keyboard");
            voicePage = new TextView(context);
            qwertyPage = new TextView(context);
            layout = new KeyboardInputModeLayout(
                    context,
                    voiceTab,
                    qwertyTab,
                    voicePage,
                    qwertyPage,
                    KeyboardInputModeLayout.Mode.VOICE);
        }

        private static Button button(Context context, String label) {
            Button button = new Button(context);
            button.setText(label);
            button.setContentDescription(label);
            return button;
        }
    }
}
