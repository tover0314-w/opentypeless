package com.opentypeless.android.keyboard.toolbar;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import com.opentypeless.android.security.PrivacyPolicyEngine;
import org.junit.Test;

public final class KeyboardToolbarPrivacyPolicyTest {
    @Test
    public void sensitiveFieldHidesEveryPlaintextToolbarCapability() {
        KeyboardToolbarPrivacyPolicy.State state = KeyboardToolbarPrivacyPolicy.resolve(
                PrivacyPolicyEngine.hardSafety(true, true));

        assertFalse(state.voiceVisible());
        assertFalse(state.actionVisible());
        assertFalse(state.clipboardVisible());
        assertFalse(state.teachVisible());
    }

    @Test
    public void noLearningHidesTeachWithoutRemovingOrdinaryInputActions() {
        KeyboardToolbarPrivacyPolicy.State state = KeyboardToolbarPrivacyPolicy.resolve(
                PrivacyPolicyEngine.hardSafety(false, false));

        assertTrue(state.voiceVisible());
        assertTrue(state.actionVisible());
        assertTrue(state.clipboardVisible());
        assertFalse(state.teachVisible());
    }

    @Test
    public void ordinaryFieldRestoresAllToolbarPlacements() {
        KeyboardToolbarPrivacyPolicy.State state = KeyboardToolbarPrivacyPolicy.resolve(
                PrivacyPolicyEngine.hardSafety(false, true));

        assertTrue(state.voiceVisible());
        assertTrue(state.actionVisible());
        assertTrue(state.clipboardVisible());
        assertTrue(state.teachVisible());
    }

    @Test
    public void invalidInputFailsClosedAndDiagnosticsContainNoFieldIdentity() {
        assertThrows(NullPointerException.class,
                () -> KeyboardToolbarPrivacyPolicy.resolve(null));
        String diagnostic = KeyboardToolbarPrivacyPolicy.resolve(
                PrivacyPolicyEngine.hardSafety(true, false)).toString();
        assertTrue(diagnostic.contains("visible=0"));
        assertFalse(diagnostic.contains("package"));
        assertFalse(diagnostic.contains("field"));
    }
}
