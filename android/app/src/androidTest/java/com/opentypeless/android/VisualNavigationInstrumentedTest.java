package com.opentypeless.android;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class VisualNavigationInstrumentedTest {
    @Test
    public void homeLeadsWithLiveRouteAndKeepsFourStableDestinations() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                TextView route = activity.findViewById(R.id.home_route_value);
                assertNotNull(route);
                assertTrue(route.isShown());
                assertTrue(route.getText().length() > 0);

                View root = activity.getWindow().getDecorView();
                assertTrue(countText(root, activity.getString(R.string.nav_home)) >= 1);
                assertTrue(countText(root, activity.getString(R.string.nav_history)) >= 1);
                assertTrue(countText(root, activity.getString(R.string.nav_dictionary)) >= 1);
                assertTrue(countText(root, activity.getString(R.string.nav_settings)) >= 1);
            });
        }
    }

    @Test
    public void settingsUsesStatusFirstProgressiveDisclosure() {
        try (ActivityScenario<SettingsHomeActivity> scenario =
                     ActivityScenario.launch(SettingsHomeActivity.class)) {
            scenario.onActivity(activity -> {
                View routeCard = activity.findViewById(R.id.settings_home_route_card);
                View voiceModels = activity.findViewById(R.id.settings_home_voice_row);

                assertNotNull(routeCard);
                assertNotNull(voiceModels);
                assertTrue(routeCard.isShown());
                assertTrue(voiceModels.isShown());
                assertTrue(voiceModels.isClickable());
            });
        }
    }

    @Test
    public void voiceLabExposesSpeechCoreV2RuntimeAndRevisionTrace() {
        try (ActivityScenario<VoiceLabActivity> scenario =
                     ActivityScenario.launch(VoiceLabActivity.class)) {
            scenario.onActivity(activity -> {
                TextView shadow = activity.findViewById(R.id.voice_lab_v2_shadow);
                assertNotNull(shadow);
                assertTrue(shadow.isShown());
                assertTrue(shadow.getText().toString().contains("v2"));
            });
        }
    }

    private static int countText(View view, String expected) {
        int matches = view instanceof TextView text && expected.contentEquals(text.getText()) ? 1 : 0;
        if (view instanceof android.view.ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                matches += countText(group.getChildAt(index), expected);
            }
        }
        return matches;
    }
}
