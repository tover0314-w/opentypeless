package com.opentypeless.android.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.EffectiveProfile;
import com.opentypeless.android.config.EffectiveProfileResolver;
import com.opentypeless.android.config.EffectiveProfileResolver.ProviderDefaults;
import com.opentypeless.android.config.EffectiveProfileResolver.Request;
import com.opentypeless.android.config.GlobalConfig;
import com.opentypeless.android.config.OverrideValue;
import com.opentypeless.android.config.ProcessingMode;
import com.opentypeless.android.config.RuleOverrides;
import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.security.PrivacyPolicyEngine.Capability;
import com.opentypeless.android.security.PrivacyPolicyEngine.CapabilitySet;
import com.opentypeless.android.security.PrivacyPolicyEngine.Decision;
import com.opentypeless.android.security.PrivacyPolicyEngine.DecisionReason;
import com.opentypeless.android.security.PrivacyPolicyEngine.Policy;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.Test;

public final class PrivacyPolicyEngineTest {
    @Test
    public void closedVocabularyAndPureAuthorityShapeAreStable() {
        assertArrayEquals(new Capability[] {
            Capability.VOICE,
            Capability.SEND_CONTEXT,
            Capability.HISTORY,
            Capability.ACTION,
            Capability.CLIPBOARD,
            Capability.LEARNING,
            Capability.TEACH
        }, Capability.values());
        assertArrayEquals(new DecisionReason[] {
            DecisionReason.ALLOWED,
            DecisionReason.SENSITIVE_FIELD,
            DecisionReason.NO_PERSONALIZED_LEARNING,
            DecisionReason.GLOBAL_INCOGNITO,
            DecisionReason.APP_RULE,
            DecisionReason.PROFILE,
            DecisionReason.USER_CHOICE
        }, DecisionReason.values());
        assertTrue(Modifier.isFinal(PrivacyPolicyEngine.class.getModifiers()));
        assertTrue(Modifier.isPrivate(
                PrivacyPolicyEngine.class.getDeclaredConstructors()[0].getModifiers()));
        assertEquals(Capability.values().length, CapabilitySet.all().size());
        assertEquals(0, CapabilitySet.none().size());
    }

    @Test
    public void ordinaryFullyEnabledTargetAllowsEveryCapability() {
        Policy policy = evaluate(profile(true, true, true, true), false, true, false,
                CapabilitySet.all(), CapabilitySet.all());
        for (Capability capability : Capability.values()) {
            assertDecision(policy, capability, true, DecisionReason.ALLOWED);
        }
    }

    @Test
    public void sensitiveSignalOverridesProfileAppAndUiEnables() {
        Policy policy = evaluate(profile(true, true, true, true), true, true, false,
                CapabilitySet.all(), CapabilitySet.all());
        for (Capability capability : Capability.values()) {
            assertDecision(policy, capability, false, DecisionReason.SENSITIVE_FIELD);
        }
    }

    @Test
    public void hardSafetyProfileCannotBeRelabeledAsOrdinaryByCaller() {
        Policy policy = evaluate(sensitiveProfile(), false, true, false,
                CapabilitySet.all(), CapabilitySet.all());
        for (Capability capability : Capability.values()) {
            assertDecision(policy, capability, false, DecisionReason.SENSITIVE_FIELD);
        }
    }

    @Test
    public void noLearningBlocksOnlyPersistentPersonalizationCapabilities() {
        Policy policy = evaluate(profile(true, true, true, true), false, false, false,
                CapabilitySet.all(), CapabilitySet.all());
        for (Capability capability : List.of(
                Capability.VOICE, Capability.SEND_CONTEXT, Capability.ACTION, Capability.CLIPBOARD)) {
            assertDecision(policy, capability, true, DecisionReason.ALLOWED);
        }
        for (Capability capability : List.of(
                Capability.HISTORY, Capability.LEARNING, Capability.TEACH)) {
            assertDecision(
                    policy, capability, false, DecisionReason.NO_PERSONALIZED_LEARNING);
        }
    }

    @Test
    public void incognitoBlocksContextHistoryLearningAndTeachWithoutDisablingInput() {
        Policy policy = evaluate(profile(true, true, true, true), false, true, true,
                CapabilitySet.all(), CapabilitySet.all());
        for (Capability capability : List.of(
                Capability.VOICE, Capability.ACTION, Capability.CLIPBOARD)) {
            assertDecision(policy, capability, true, DecisionReason.ALLOWED);
        }
        for (Capability capability : List.of(
                Capability.SEND_CONTEXT,
                Capability.HISTORY,
                Capability.LEARNING,
                Capability.TEACH)) {
            assertDecision(policy, capability, false, DecisionReason.GLOBAL_INCOGNITO);
        }
    }

    @Test
    public void appMaximumCannotBeBypassedByFullyEnabledUiChoices() {
        CapabilitySet appMaximum = CapabilitySet.of(
                Capability.VOICE, Capability.ACTION, Capability.LEARNING);
        Policy policy = evaluate(profile(true, true, true, true), false, true, false,
                appMaximum, CapabilitySet.all());
        assertDecision(policy, Capability.VOICE, true, DecisionReason.ALLOWED);
        assertDecision(policy, Capability.ACTION, true, DecisionReason.ALLOWED);
        assertDecision(policy, Capability.LEARNING, true, DecisionReason.ALLOWED);
        for (Capability capability : List.of(
                Capability.SEND_CONTEXT,
                Capability.HISTORY,
                Capability.CLIPBOARD,
                Capability.TEACH)) {
            assertDecision(policy, capability, false, DecisionReason.APP_RULE);
        }
    }

    @Test
    public void resolvedProfileDisablesAreHonoredBeforeUiChoices() {
        Policy policy = evaluate(profile(false, false, false, false), false, true, false,
                CapabilitySet.all(), CapabilitySet.all());
        for (Capability capability : List.of(
                Capability.VOICE,
                Capability.SEND_CONTEXT,
                Capability.HISTORY,
                Capability.ACTION)) {
            assertDecision(policy, capability, false, DecisionReason.PROFILE);
        }
        assertDecision(policy, Capability.CLIPBOARD, true, DecisionReason.ALLOWED);
        assertDecision(policy, Capability.LEARNING, true, DecisionReason.ALLOWED);
        assertDecision(policy, Capability.TEACH, true, DecisionReason.ALLOWED);
    }

    @Test
    public void userChoicesCanOnlyTightenTheAlreadyAuthorizedPolicy() {
        CapabilitySet choices = CapabilitySet.of(Capability.VOICE, Capability.CLIPBOARD);
        Policy policy = evaluate(profile(true, true, true, true), false, true, false,
                CapabilitySet.all(), choices);
        assertDecision(policy, Capability.VOICE, true, DecisionReason.ALLOWED);
        assertDecision(policy, Capability.CLIPBOARD, true, DecisionReason.ALLOWED);
        for (Capability capability : List.of(
                Capability.SEND_CONTEXT,
                Capability.HISTORY,
                Capability.ACTION,
                Capability.LEARNING,
                Capability.TEACH)) {
            assertDecision(policy, capability, false, DecisionReason.USER_CHOICE);
        }
    }

    @Test
    public void restrictiveReasonPrecedenceIsStableAndTeachRequiresLearning() {
        Policy sensitive = evaluate(sensitiveProfile(), true, false, true,
                CapabilitySet.none(), CapabilitySet.none());
        assertDecision(sensitive, Capability.TEACH, false, DecisionReason.SENSITIVE_FIELD);

        Policy noLearning = evaluate(profile(true, true, true, true), false, false, true,
                CapabilitySet.none(), CapabilitySet.none());
        assertDecision(
                noLearning,
                Capability.TEACH,
                false,
                DecisionReason.NO_PERSONALIZED_LEARNING);

        Policy noLearningChoice = evaluate(profile(true, true, true, true), false, true, false,
                CapabilitySet.all(), CapabilitySet.of(Capability.TEACH));
        assertDecision(
                noLearningChoice,
                Capability.LEARNING,
                false,
                DecisionReason.USER_CHOICE);
        assertDecision(
                noLearningChoice,
                Capability.TEACH,
                false,
                DecisionReason.USER_CHOICE);
        assertThrows(
                IllegalArgumentException.class,
                () -> new Policy(
                        denied(), denied(), denied(), denied(), denied(), denied(), allowed()));
    }

    @Test
    public void capabilitySetsAreDefensiveBoundedAndRejectNulls() {
        Capability[] mutable = {Capability.VOICE, Capability.HISTORY};
        CapabilitySet set = CapabilitySet.of(mutable);
        mutable[0] = Capability.ACTION;
        assertTrue(set.allows(Capability.VOICE));
        assertFalse(set.allows(Capability.ACTION));
        assertEquals(CapabilitySet.of(Capability.VOICE, Capability.HISTORY), set);
        assertEquals(CapabilitySet.of(Capability.VOICE), CapabilitySet.of(
                Capability.VOICE, Capability.VOICE));
        assertThrows(NullPointerException.class, () -> CapabilitySet.of((Capability[]) null));
        assertThrows(NullPointerException.class, () -> CapabilitySet.of(Capability.VOICE, null));
        assertThrows(NullPointerException.class, () -> set.allows(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> CapabilitySet.of(
                        Capability.VOICE,
                        Capability.VOICE,
                        Capability.VOICE,
                        Capability.VOICE,
                        Capability.VOICE,
                        Capability.VOICE,
                        Capability.VOICE,
                        Capability.VOICE));
    }

    @Test
    public void diagnosticsExposeNoProfileIdentifiersAndInputsRejectNulls() {
        EffectiveProfile profile = profile(true, true, true, true);
        PrivacyPolicyEngine.Request request = request(
                profile, false, true, false, CapabilitySet.all(), CapabilitySet.all());
        Policy policy = PrivacyPolicyEngine.evaluate(request);
        for (String diagnostic : List.of(request.toString(), policy.toString())) {
            assertFalse(diagnostic.contains("route.private"));
            assertFalse(diagnostic.contains("actions.private"));
            assertFalse(diagnostic.contains("com.example"));
        }
        assertThrows(NullPointerException.class, () -> PrivacyPolicyEngine.evaluate(null));
        assertThrows(
                NullPointerException.class,
                () -> request(null, false, true, false, CapabilitySet.all(), CapabilitySet.all()));
        assertThrows(
                NullPointerException.class,
                () -> request(profile, false, true, false, null, CapabilitySet.all()));
        assertThrows(
                NullPointerException.class,
                () -> request(profile, false, true, false, CapabilitySet.all(), null));
    }

    private static PrivacyPolicyEngine.Request request(
            EffectiveProfile profile,
            boolean sensitive,
            boolean learningAllowed,
            boolean incognito,
            CapabilitySet appMaximum,
            CapabilitySet choices) {
        return new PrivacyPolicyEngine.Request(
                profile, sensitive, learningAllowed, incognito, appMaximum, choices);
    }

    private static Policy evaluate(
            EffectiveProfile profile,
            boolean sensitive,
            boolean learningAllowed,
            boolean incognito,
            CapabilitySet appMaximum,
            CapabilitySet choices) {
        return PrivacyPolicyEngine.evaluate(request(
                profile, sensitive, learningAllowed, incognito, appMaximum, choices));
    }

    private static void assertDecision(
            Policy policy,
            Capability capability,
            boolean allowed,
            DecisionReason reason) {
        assertEquals(new Decision(allowed, reason), policy.decision(capability));
    }

    private static Decision allowed() {
        return new Decision(true, DecisionReason.ALLOWED);
    }

    private static Decision denied() {
        return new Decision(false, DecisionReason.PROFILE);
    }

    private static EffectiveProfile profile(
            boolean voice,
            boolean context,
            boolean history,
            boolean action) {
        GlobalConfig global = new GlobalConfig(
                GlobalConfig.FORMAT_VERSION,
                new GlobalConfig.KeyboardConfig("latin.base"),
                new GlobalConfig.VoiceConfig(
                        voice ? OverrideValue.value("route.private") : OverrideValue.disabled()),
                new GlobalConfig.ProcessingConfig(OverrideValue.value(ProcessingMode.EXACT)),
                new GlobalConfig.PrivacyConfig(
                        OverrideValue.value(context), OverrideValue.value(history)),
                new GlobalConfig.AutomationConfig(
                        action ? OverrideValue.value("actions.private") : OverrideValue.disabled()));
        return EffectiveProfileResolver.resolve(new Request(
                global,
                defaults(),
                List.of(),
                List.of(),
                inherited(),
                "com.example.editor",
                FieldKind.GENERAL));
    }

    private static EffectiveProfile sensitiveProfile() {
        GlobalConfig global = new GlobalConfig(
                GlobalConfig.FORMAT_VERSION,
                new GlobalConfig.KeyboardConfig("latin.base"),
                new GlobalConfig.VoiceConfig(OverrideValue.value("route.private")),
                new GlobalConfig.ProcessingConfig(OverrideValue.value(ProcessingMode.SMART)),
                new GlobalConfig.PrivacyConfig(
                        OverrideValue.value(true), OverrideValue.value(true)),
                new GlobalConfig.AutomationConfig(OverrideValue.value("actions.private")));
        return EffectiveProfileResolver.resolve(new Request(
                global,
                defaults(),
                List.of(),
                List.of(),
                inherited(),
                "com.example.editor",
                FieldKind.SENSITIVE));
    }

    private static ProviderDefaults defaults() {
        return new ProviderDefaults(
                OverrideValue.value("route.default"),
                OverrideValue.value(ProcessingMode.AUTO),
                OverrideValue.value(false),
                OverrideValue.value(false),
                OverrideValue.disabled());
    }

    private static RuleOverrides inherited() {
        return new RuleOverrides(
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit(),
                OverrideValue.inherit());
    }
}
