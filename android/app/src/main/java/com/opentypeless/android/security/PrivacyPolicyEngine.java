package com.opentypeless.android.security;

import com.opentypeless.android.config.EffectiveProfile;
import com.opentypeless.android.config.OverrideValue;
import com.opentypeless.android.config.ProcessingMode;
import java.util.Objects;

/**
 * Pure, fail-closed privacy authority for one resolved editor target.
 *
 * <p>The engine only intersects trusted constraints. UI choices can remove a capability, but they
 * can never re-enable a capability denied by field safety, Android's no-learning flag, global
 * incognito mode, an application rule, or the resolved profile.
 */
public final class PrivacyPolicyEngine {
    private static final int ALL_CAPABILITIES_MASK = (1 << Capability.values().length) - 1;

    private PrivacyPolicyEngine() {}

    /** Closed plaintext-bearing capability vocabulary. */
    public enum Capability {
        VOICE,
        SEND_CONTEXT,
        HISTORY,
        ACTION,
        CLIPBOARD,
        LEARNING,
        TEACH
    }

    /** Stable, content-free reason for one capability decision. */
    public enum DecisionReason {
        ALLOWED,
        SENSITIVE_FIELD,
        NO_PERSONALIZED_LEARNING,
        GLOBAL_INCOGNITO,
        APP_RULE,
        PROFILE,
        USER_CHOICE
    }

    /**
     * Deny-only field restrictions that may be projected before a complete EffectiveProfile exists.
     *
     * <p>This value is not capability authorization. It only exposes the two hard field signals
     * that dominate every later App/profile/UI choice.
     */
    public record HardSafety(boolean sensitiveField, boolean learningAllowed) {
        public HardSafety {
            if (sensitiveField) learningAllowed = false;
        }

        public boolean denies(Capability capability) {
            Objects.requireNonNull(capability, "capability");
            return sensitiveField || (!learningAllowed && isLearningBound(capability));
        }

        @Override
        public String toString() {
            return "HardSafety{sensitive=" + sensitiveField
                    + ", learningAllowed=" + learningAllowed + "}";
        }
    }

    public static HardSafety hardSafety(boolean sensitiveField, boolean learningAllowed) {
        return new HardSafety(sensitiveField, learningAllowed);
    }

    /** Immutable bounded set used independently for App maxima and user choices. */
    public static final class CapabilitySet {
        private final int mask;

        private CapabilitySet(int mask) {
            if ((mask & ~ALL_CAPABILITIES_MASK) != 0) {
                throw new IllegalArgumentException("capability mask is invalid");
            }
            this.mask = mask;
        }

        public static CapabilitySet all() {
            return new CapabilitySet(ALL_CAPABILITIES_MASK);
        }

        public static CapabilitySet none() {
            return new CapabilitySet(0);
        }

        public static CapabilitySet of(Capability... capabilities) {
            Objects.requireNonNull(capabilities, "capabilities");
            if (capabilities.length > Capability.values().length) {
                throw new IllegalArgumentException("too many capabilities");
            }
            int result = 0;
            for (Capability capability : capabilities.clone()) {
                result |= 1 << Objects.requireNonNull(capability, "capability").ordinal();
            }
            return new CapabilitySet(result);
        }

        public boolean allows(Capability capability) {
            return (mask & (1 << Objects.requireNonNull(capability, "capability").ordinal())) != 0;
        }

        public int size() {
            return Integer.bitCount(mask);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CapabilitySet set && mask == set.mask;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(mask);
        }

        @Override
        public String toString() {
            return "CapabilitySet{count=" + size() + "}";
        }
    }

    /** Complete policy input; none of these values is execution authority. */
    public record Request(
            EffectiveProfile profile,
            boolean sensitiveField,
            boolean learningAllowed,
            boolean globalIncognito,
            CapabilitySet appMaximum,
            CapabilitySet userChoices) {
        public Request {
            profile = Objects.requireNonNull(profile, "profile");
            appMaximum = Objects.requireNonNull(appMaximum, "appMaximum");
            userChoices = Objects.requireNonNull(userChoices, "userChoices");
        }

        @Override
        public String toString() {
            return "Request{profile=<redacted>, sensitive=" + sensitiveField
                    + ", learningAllowed=" + learningAllowed
                    + ", incognito=" + globalIncognito
                    + ", appMaximum=" + appMaximum
                    + ", userChoices=" + userChoices + "}";
        }
    }

    /** One immutable allow/deny result. */
    public record Decision(boolean allowed, DecisionReason reason) {
        public Decision {
            reason = Objects.requireNonNull(reason, "reason");
            if (allowed != (reason == DecisionReason.ALLOWED)) {
                throw new IllegalArgumentException("decision and reason disagree");
            }
        }

        private static Decision allowedDecision() {
            return new Decision(true, DecisionReason.ALLOWED);
        }

        private static Decision denied(DecisionReason reason) {
            return new Decision(false, reason);
        }
    }

    /** Complete closed policy for the capabilities that may handle editor plaintext. */
    public record Policy(
            Decision voice,
            Decision sendContext,
            Decision history,
            Decision action,
            Decision clipboard,
            Decision learning,
            Decision teach) {
        public Policy {
            voice = Objects.requireNonNull(voice, "voice");
            sendContext = Objects.requireNonNull(sendContext, "sendContext");
            history = Objects.requireNonNull(history, "history");
            action = Objects.requireNonNull(action, "action");
            clipboard = Objects.requireNonNull(clipboard, "clipboard");
            learning = Objects.requireNonNull(learning, "learning");
            teach = Objects.requireNonNull(teach, "teach");
            if (teach.allowed() && !learning.allowed()) {
                throw new IllegalArgumentException("teach requires learning");
            }
        }

        public Decision decision(Capability capability) {
            return switch (Objects.requireNonNull(capability, "capability")) {
                case VOICE -> voice;
                case SEND_CONTEXT -> sendContext;
                case HISTORY -> history;
                case ACTION -> action;
                case CLIPBOARD -> clipboard;
                case LEARNING -> learning;
                case TEACH -> teach;
            };
        }

        @Override
        public String toString() {
            return "Policy{allowed=" + allowedCount() + ", denied="
                    + (Capability.values().length - allowedCount()) + "}";
        }

        private int allowedCount() {
            int count = 0;
            for (Capability capability : Capability.values()) {
                if (decision(capability).allowed()) count++;
            }
            return count;
        }
    }

    public static Policy evaluate(Request request) {
        Request safe = Objects.requireNonNull(request, "request");
        boolean sensitive = safe.sensitiveField() || profileRequiresFullRestriction(safe.profile());
        Decision learning = decide(Capability.LEARNING, safe, sensitive, true);
        Decision teach = learning.allowed()
                ? decide(Capability.TEACH, safe, sensitive, true)
                : Decision.denied(learning.reason());
        return new Policy(
                decide(Capability.VOICE, safe, sensitive, profileAllowsVoice(safe.profile())),
                decide(
                        Capability.SEND_CONTEXT,
                        safe,
                        sensitive,
                        explicitTrue(safe.profile().sendContext().value())),
                decide(
                        Capability.HISTORY,
                        safe,
                        sensitive,
                        explicitTrue(safe.profile().historyEnabled().value())),
                decide(Capability.ACTION, safe, sensitive, profileAllowsAction(safe.profile())),
                decide(Capability.CLIPBOARD, safe, sensitive, true),
                learning,
                teach);
    }

    private static Decision decide(
            Capability capability,
            Request request,
            boolean sensitive,
            boolean profileAllows) {
        if (sensitive) return Decision.denied(DecisionReason.SENSITIVE_FIELD);
        if (!request.learningAllowed() && isLearningBound(capability)) {
            return Decision.denied(DecisionReason.NO_PERSONALIZED_LEARNING);
        }
        if (request.globalIncognito() && isIncognitoBound(capability)) {
            return Decision.denied(DecisionReason.GLOBAL_INCOGNITO);
        }
        if (!request.appMaximum().allows(capability)) {
            return Decision.denied(DecisionReason.APP_RULE);
        }
        if (!profileAllows) return Decision.denied(DecisionReason.PROFILE);
        if (!request.userChoices().allows(capability)) {
            return Decision.denied(DecisionReason.USER_CHOICE);
        }
        return Decision.allowedDecision();
    }

    private static boolean isLearningBound(Capability capability) {
        return capability == Capability.HISTORY
                || capability == Capability.LEARNING
                || capability == Capability.TEACH;
    }

    private static boolean isIncognitoBound(Capability capability) {
        return capability == Capability.SEND_CONTEXT
                || capability == Capability.HISTORY
                || capability == Capability.LEARNING
                || capability == Capability.TEACH;
    }

    private static boolean profileRequiresFullRestriction(EffectiveProfile profile) {
        return profile.voiceRouteId().isDisabled()
                && OverrideValue.value(ProcessingMode.EXACT).equals(profile.processingMode().value())
                && profile.sendContext().isDisabled()
                && profile.historyEnabled().isDisabled()
                && profile.actionSetId().isDisabled();
    }

    private static boolean profileAllowsVoice(EffectiveProfile profile) {
        return !profile.voiceRouteId().isDisabled();
    }

    private static boolean profileAllowsAction(EffectiveProfile profile) {
        return !profile.actionSetId().isDisabled();
    }

    private static boolean explicitTrue(OverrideValue<Boolean> value) {
        return value instanceof OverrideValue.Value<?> explicit
                && Boolean.TRUE.equals(explicit.value());
    }
}
