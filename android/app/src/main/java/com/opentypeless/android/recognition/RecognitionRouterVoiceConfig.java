package com.opentypeless.android.recognition;

import android.content.Context;
import android.content.SharedPreferences;

import com.opentypeless.android.ime.VoiceController;
import com.opentypeless.android.ime.VoicePipelineAdapter;

import java.util.Objects;

/** Whole-controller rollback flag for the STR-010 RecognitionRouter migration. */
public final class RecognitionRouterVoiceConfig {
    private static final String STORE = "recognition_router_voice_runtime";
    private static final String ENABLED = "recognition_router_v1";

    private RecognitionRouterVoiceConfig() {}

    public static boolean enabled(Context context) {
        return preferences(context).getBoolean(ENABLED, true);
    }

    /** Selects exactly one controller path for the lifetime of the caller-owned delegate. */
    public static VoiceController select(
            Context context,
            VoicePipelineAdapter compatibilityDelegate) {
        Context application = Objects.requireNonNull(context, "context").getApplicationContext();
        VoicePipelineAdapter delegate = Objects.requireNonNull(
                compatibilityDelegate,
                "compatibilityDelegate");
        return enabled(application)
                ? new RecognitionRouterVoiceController(application, delegate)
                : delegate;
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (!preferences(context).edit().putBoolean(ENABLED, enabled).commit()) {
            throw new IllegalStateException("Unable to update recognition route selection");
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }
}
