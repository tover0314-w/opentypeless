package com.opentypeless.android.speech.runtime;

import android.content.Context;
import android.content.SharedPreferences;

/** Explicit local rollback switch. V2 is the product default; V1 is emergency-only. */
public final class SpeechCoreV2Config {
    private static final String STORE = "speech_core_v2_runtime";
    private static final String ENABLED = "enabled";

    private SpeechCoreV2Config() {}

    public static boolean enabled(Context context) {
        return preferences(context).getBoolean(ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (!preferences(context).edit().putBoolean(ENABLED, enabled).commit()) {
            throw new IllegalStateException("Unable to update Speech Core runtime selection");
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                STORE, Context.MODE_PRIVATE);
    }
}
