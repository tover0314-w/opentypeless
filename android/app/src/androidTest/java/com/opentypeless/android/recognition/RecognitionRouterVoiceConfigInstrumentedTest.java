package com.opentypeless.android.recognition;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.opentypeless.android.ime.VoiceController;
import com.opentypeless.android.ime.VoicePipeline;
import com.opentypeless.android.ime.VoicePipelineAdapter;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Device-backed proof that STR-010 freezes exactly one controller implementation per caller. */
@RunWith(AndroidJUnit4.class)
public final class RecognitionRouterVoiceConfigInstrumentedTest {
    private static final String STORE = "recognition_router_voice_runtime";
    private static final String ENABLED = "recognition_router_v1";

    @Test
    public void flagDefaultsOnAndSelectsExactlyOneWholeControllerPath() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        boolean existed = preferences.contains(ENABLED);
        boolean original = preferences.getBoolean(ENABLED, true);
        VoicePipeline pipeline = new VoicePipeline(context);
        try {
            assertTrue(preferences.edit().remove(ENABLED).commit());
            assertTrue(RecognitionRouterVoiceConfig.enabled(context));

            VoicePipelineAdapter routedDelegate = new VoicePipelineAdapter(pipeline);
            VoiceController routed = RecognitionRouterVoiceConfig.select(context, routedDelegate);
            assertTrue(routed instanceof RecognitionRouterVoiceController);
            assertSame(VoiceController.State.IDLE, routed.state());

            RecognitionRouterVoiceConfig.setEnabled(context, false);
            assertFalse(RecognitionRouterVoiceConfig.enabled(context));
            VoicePipelineAdapter compatibility = new VoicePipelineAdapter(pipeline);
            assertSame(
                    compatibility,
                    RecognitionRouterVoiceConfig.select(context, compatibility));

            RecognitionRouterVoiceConfig.setEnabled(context, true);
            assertTrue(RecognitionRouterVoiceConfig.enabled(context));
            assertTrue(RecognitionRouterVoiceConfig.select(
                    context,
                    new VoicePipelineAdapter(pipeline))
                    instanceof RecognitionRouterVoiceController);
        } finally {
            pipeline.shutdown();
            SharedPreferences.Editor restore = preferences.edit();
            if (existed) restore.putBoolean(ENABLED, original);
            else restore.remove(ENABLED);
            assertTrue(restore.commit());
        }
    }
}
