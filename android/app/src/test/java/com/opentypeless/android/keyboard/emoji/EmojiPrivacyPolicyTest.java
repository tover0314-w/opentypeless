package com.opentypeless.android.keyboard.emoji;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.security.PrivacyPolicyEngine;
import org.junit.Test;

public final class EmojiPrivacyPolicyTest {
    @Test
    public void ordinaryFieldShowsPanelAndRecents() {
        EmojiPrivacyPolicy.State state = EmojiPrivacyPolicy.resolve(
                true, PrivacyPolicyEngine.hardSafety(false, true));

        assertTrue(state.panelVisible());
        assertTrue(state.recentsVisible());
        assertTrue(state.recentsWritable());
    }

    @Test
    public void sensitiveAndNoLearningFieldsKeepStaticEmojiButSuppressRecents() {
        EmojiPrivacyPolicy.State sensitive = EmojiPrivacyPolicy.resolve(
                true, PrivacyPolicyEngine.hardSafety(true, true));
        EmojiPrivacyPolicy.State noLearning = EmojiPrivacyPolicy.resolve(
                true, PrivacyPolicyEngine.hardSafety(false, false));

        assertTrue(sensitive.panelVisible());
        assertFalse(sensitive.recentsVisible());
        assertFalse(sensitive.recentsWritable());
        assertTrue(noLearning.panelVisible());
        assertFalse(noLearning.recentsVisible());
        assertFalse(noLearning.recentsWritable());
    }

    @Test
    public void missingEditorAndInvalidSafetyFailClosed() {
        EmojiPrivacyPolicy.State hidden = EmojiPrivacyPolicy.resolve(
                false, PrivacyPolicyEngine.hardSafety(false, true));

        assertFalse(hidden.panelVisible());
        assertFalse(hidden.recentsVisible());
        assertFalse(hidden.recentsWritable());
        assertThrows(NullPointerException.class, () -> EmojiPrivacyPolicy.resolve(true, null));
    }
}
