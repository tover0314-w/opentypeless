package com.opentypeless.android.keyboard.toolbar;

import com.opentypeless.android.security.PrivacyPolicyEngine;
import java.util.Objects;

/** Pure SEC-005 projection from deny-only field safety into bounded toolbar visibility. */
public final class KeyboardToolbarPrivacyPolicy {
    private KeyboardToolbarPrivacyPolicy() {}

    /** Closed visibility state; no View, editor, text or execution capability is retained. */
    public record State(
            boolean voiceVisible,
            boolean actionVisible,
            boolean clipboardVisible,
            boolean teachVisible) {
        @Override
        public String toString() {
            int visible = (voiceVisible ? 1 : 0)
                    + (actionVisible ? 1 : 0)
                    + (clipboardVisible ? 1 : 0)
                    + (teachVisible ? 1 : 0);
            return "ToolbarPrivacyState{visible=" + visible + ", hidden=" + (4 - visible) + "}";
        }
    }

    public static State resolve(PrivacyPolicyEngine.HardSafety hardSafety) {
        PrivacyPolicyEngine.HardSafety safe = Objects.requireNonNull(
                hardSafety, "hardSafety");
        return new State(
                !safe.denies(PrivacyPolicyEngine.Capability.VOICE),
                !safe.denies(PrivacyPolicyEngine.Capability.ACTION),
                !safe.denies(PrivacyPolicyEngine.Capability.CLIPBOARD),
                !safe.denies(PrivacyPolicyEngine.Capability.TEACH));
    }
}
