package com.opentypeless.android.keyboard.emoji;

import com.opentypeless.android.security.PrivacyPolicyEngine;
import java.util.Objects;

/** Allows local static Emoji while suppressing MRU reads/writes in sensitive/no-learning fields. */
public final class EmojiPrivacyPolicy {
    private EmojiPrivacyPolicy() {}

    public record State(boolean panelVisible, boolean recentsVisible, boolean recentsWritable) {
        @Override
        public String toString() {
            return "EmojiPrivacyState{panel=" + panelVisible
                    + ", recents=" + recentsVisible
                    + ", learning=" + recentsWritable + '}';
        }
    }

    public static State resolve(
            boolean editorActive,
            PrivacyPolicyEngine.HardSafety hardSafety) {
        PrivacyPolicyEngine.HardSafety safe = Objects.requireNonNull(
                hardSafety, "hardSafety");
        boolean recentsAllowed = editorActive
                && !safe.denies(PrivacyPolicyEngine.Capability.TEACH);
        return new State(editorActive, recentsAllowed, recentsAllowed);
    }

    public static State hidden() {
        return new State(false, false, false);
    }
}
