package com.opentypeless.android.speech.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Device-backed verification for EDT-017's session-frozen editor-writer switch. */
@RunWith(AndroidJUnit4.class)
public final class VoiceEditorTransactionConfigInstrumentedTest {
    private static final String STORE = "voice_editor_transaction_runtime";
    private static final String VOICE_ENGINE_V2 = "voice_engine_v2";
    private static final String LEGACY_ENABLED = "enabled";

    @Test
    public void canonicalFlagDefaultsOnMigratesLegacyAndPersistsBothChoicesSynchronously() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        boolean canonicalExisted = preferences.contains(VOICE_ENGINE_V2);
        boolean canonicalOriginal = preferences.getBoolean(VOICE_ENGINE_V2, true);
        boolean legacyExisted = preferences.contains(LEGACY_ENABLED);
        boolean legacyOriginal = preferences.getBoolean(LEGACY_ENABLED, true);
        try {
            assertTrue(preferences.edit()
                    .remove(VOICE_ENGINE_V2)
                    .remove(LEGACY_ENABLED)
                    .commit());
            assertTrue(VoiceEditorTransactionConfig.enabled(context));

            VoiceEditorTransactionConfig.setEnabled(context, false);
            assertFalse(VoiceEditorTransactionConfig.enabled(context));
            assertFalse(preferences.getBoolean(VOICE_ENGINE_V2, true));
            assertFalse(preferences.contains(LEGACY_ENABLED));

            VoiceEditorTransactionConfig.setEnabled(context, true);
            assertTrue(VoiceEditorTransactionConfig.enabled(context));
            assertTrue(preferences.getBoolean(VOICE_ENGINE_V2, false));

            assertTrue(preferences.edit()
                    .remove(VOICE_ENGINE_V2)
                    .putBoolean(LEGACY_ENABLED, false)
                    .commit());
            assertFalse(VoiceEditorTransactionConfig.enabled(context));
            assertFalse(preferences.getBoolean(VOICE_ENGINE_V2, true));
            assertFalse(preferences.contains(LEGACY_ENABLED));

            assertTrue(preferences.edit()
                    .putBoolean(VOICE_ENGINE_V2, true)
                    .putBoolean(LEGACY_ENABLED, false)
                    .commit());
            assertTrue(VoiceEditorTransactionConfig.enabled(context));
            assertTrue(preferences.getBoolean(VOICE_ENGINE_V2, false));
            assertFalse(preferences.contains(LEGACY_ENABLED));
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            if (canonicalExisted) {
                restore.putBoolean(VOICE_ENGINE_V2, canonicalOriginal);
            } else {
                restore.remove(VOICE_ENGINE_V2);
            }
            if (legacyExisted) {
                restore.putBoolean(LEGACY_ENABLED, legacyOriginal);
            } else {
                restore.remove(LEGACY_ENABLED);
            }
            assertTrue(restore.commit());
        }
    }
}
