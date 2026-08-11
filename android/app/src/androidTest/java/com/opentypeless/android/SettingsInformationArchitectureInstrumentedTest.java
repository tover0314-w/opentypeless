package com.opentypeless.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
public final class SettingsInformationArchitectureInstrumentedTest {
    @Test
    public void activeRouteLeadsAndAdvancedRecognitionIsProgressivelyDisclosed() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                TextView active = field(activity, "activeConfigurationSummary", TextView.class);
                Button advanced = field(activity, "recognitionAdvancedToggle", Button.class);

                assertTrue(active.isShown());
                assertEquals(3, active.getText().toString().split("\\n", -1).length);
                assertFalse(field(
                        activity,
                        "recognitionAdvancedFields",
                        LinearLayout.class).isShown());
                assertTrue(advanced.isShown());

                advanced.performClick();
                assertTrue(field(activity, "language", EditText.class).isShown());
            });

            scenario.recreate();

            scenario.onActivity(activity -> assertTrue(
                    field(activity, "language", EditText.class).isShown()));
        }
    }

    @Test
    public void saveActionRemainsVisibleWithoutScrollingTheForm() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Button save = findButton(
                        activity.getWindow().getDecorView(),
                        activity.getString(R.string.save_configuration));
                assertTrue(save.isShown());
            });
        }
    }

    @Test
    public void firstRunChecklistIncludesSavedRouteAndSuccessfulVoiceTest() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                TextView status = field(activity, "permissionStatus", TextView.class);
                String value = status.getText().toString();
                assertTrue(value.equals(activity.getString(R.string.setup_complete))
                        || value.split("\\n", -1).length == 5);
            });
        }
    }

    private static Button findButton(android.view.View view, String label) {
        if (view instanceof Button button && label.contentEquals(button.getText())) return button;
        if (view instanceof android.view.ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                try {
                    return findButton(group.getChildAt(index), label);
                } catch (AssertionError ignored) {
                    // Continue through the current branch.
                }
            }
        }
        throw new AssertionError("Missing button: " + label);
    }

    private static <T> T field(Object owner, String name, Class<T> type) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(owner));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Missing test field: " + name, error);
        }
    }
}
