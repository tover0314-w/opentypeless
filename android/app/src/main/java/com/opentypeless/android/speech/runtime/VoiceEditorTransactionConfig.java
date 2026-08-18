package com.opentypeless.android.speech.runtime;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Rollback switch for EDT-017/VOC-011 editor delivery.
 *
 * <p>The value is read once when a voice session captures its target. That frozen choice selects
 * either the transaction writer or the complete legacy writer for the lifetime of the session;
 * failures never fall through to the other writer. The canonical {@code voice_engine_v2} value
 * migrates the former {@code enabled} key without changing an explicit rollback choice.
 */
public final class VoiceEditorTransactionConfig {
    private static final String STORE = "voice_editor_transaction_runtime";
    private static final String VOICE_ENGINE_V2 = "voice_engine_v2";
    private static final String LEGACY_ENABLED = "enabled";

    private VoiceEditorTransactionConfig() {}

    public static synchronized boolean enabled(Context context) {
        SharedPreferences preferences = preferences(context);
        boolean canonicalPresent = preferences.contains(VOICE_ENGINE_V2);
        boolean legacyPresent = preferences.contains(LEGACY_ENABLED);
        boolean enabled = canonicalPresent
                ? preferences.getBoolean(VOICE_ENGINE_V2, true)
                : legacyPresent ? preferences.getBoolean(LEGACY_ENABLED, true) : true;
        if (legacyPresent) {
            SharedPreferences.Editor migration = preferences.edit().remove(LEGACY_ENABLED);
            if (!canonicalPresent) migration.putBoolean(VOICE_ENGINE_V2, enabled);
            if (!migration.commit()) return enabled;
        }
        return enabled;
    }

    public static synchronized void setEnabled(Context context, boolean enabled) {
        if (!preferences(context)
                .edit()
                .putBoolean(VOICE_ENGINE_V2, enabled)
                .remove(LEGACY_ENABLED)
                .commit()) {
            throw new IllegalStateException("Unable to update Voice editor transaction selection");
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                STORE, Context.MODE_PRIVATE);
    }
}
