package com.opentypeless.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.WindowManager;
import android.widget.Button;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@RunWith(AndroidJUnit4.class)
public final class VoiceLabAccessibilityInstrumentedTest {
    @Test
    public void holdGestureAlsoExposesAStatefulTalkBackClickAction() {
        try (ActivityScenario<VoiceLabActivity> scenario =
                     ActivityScenario.launch(VoiceLabActivity.class)) {
            scenario.onActivity(activity -> {
                Button hold = field(activity, "holdButton", Button.class);

                assertTrue(hold.hasOnClickListeners());
                assertTrue(hold.isClickable());
                assertTrue(hold.getMinimumHeight() >= dp(activity, 48));
                assertEquals(
                        activity.getString(R.string.voice_lab_cd_start_recording),
                        hold.getContentDescription().toString());
                assertTrue((activity.getWindow().getAttributes().flags
                        & WindowManager.LayoutParams.FLAG_SECURE) != 0);

                setBoolean(activity, "recognitionActive", true);
                setBoolean(activity, "holding", true);
                invoke(activity, "renderControls");
                assertEquals(
                        activity.getString(R.string.voice_lab_cd_finish_recording),
                        hold.getContentDescription().toString());
            });
        }
    }

    private static int dp(VoiceLabActivity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
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

    private static void setBoolean(Object owner, String name, boolean value) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(owner, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Missing test field: " + name, error);
        }
    }

    private static void invoke(Object owner, String name) {
        try {
            Method method = owner.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(owner);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Missing test method: " + name, error);
        }
    }
}
