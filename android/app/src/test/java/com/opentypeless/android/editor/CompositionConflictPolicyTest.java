package com.opentypeless.android.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

public final class CompositionConflictPolicyTest {
    @Test
    public void defaultsPreferVisibleTextAndPreserveDisplacedActionResult() {
        CompositionConflictPolicy policy = CompositionConflictPolicy.defaults();

        assertEquals(
                CompositionConflictPolicy.RimeToVoice.COMMIT_RIME,
                policy.rimeToVoice());
        assertEquals(
                CompositionConflictPolicy.VoicePartialToKeyboard.COMMIT_VISIBLE_PARTIAL,
                policy.voicePartialToKeyboard());
        assertEquals(
                CompositionConflictPolicy.ActionToVoice.PRESERVE_RESULT_PANEL,
                policy.actionToVoice());
        assertEquals(
                CompositionConflictPolicy.Decision.COMMIT_CURRENT,
                policy.rimeToVoiceDecision());
        assertEquals(
                CompositionConflictPolicy.Decision.COMMIT_CURRENT,
                policy.latinToVoiceDecision());
    }

    @Test
    public void everyConfigurationChoiceMapsToOneClosedReleaseIntent() {
        for (CompositionConflictPolicy.RimeToVoice rime
                : CompositionConflictPolicy.RimeToVoice.values()) {
            for (CompositionConflictPolicy.VoicePartialToKeyboard voice
                    : CompositionConflictPolicy.VoicePartialToKeyboard.values()) {
                for (CompositionConflictPolicy.ActionToVoice action
                        : CompositionConflictPolicy.ActionToVoice.values()) {
                    CompositionConflictPolicy policy =
                            new CompositionConflictPolicy(rime, voice, action);
                    assertEquals(
                            rime == CompositionConflictPolicy.RimeToVoice.COMMIT_RIME
                                    ? CompositionConflictPolicy.Decision.COMMIT_CURRENT
                                    : CompositionConflictPolicy.Decision.CANCEL_CURRENT,
                            policy.rimeToVoiceDecision());
                    assertEquals(
                            voice
                                            == CompositionConflictPolicy.VoicePartialToKeyboard
                                                    .COMMIT_VISIBLE_PARTIAL
                                    ? CompositionConflictPolicy.Decision.COMMIT_CURRENT
                                    : CompositionConflictPolicy.Decision.CANCEL_CURRENT,
                            policy.voiceToKeyboardDecision(
                                    new CompositionState.VoicePartial(3L, 7L)));
                    assertEquals(
                            action
                                            == CompositionConflictPolicy.ActionToVoice
                                                    .PRESERVE_RESULT_PANEL
                                    ? CompositionConflictPolicy.Decision
                                            .CANCEL_CURRENT_AND_ROUTE_RESULT
                                    : CompositionConflictPolicy.Decision.CANCEL_CURRENT,
                            policy.actionToVoiceDecision(
                                    new CompositionState.ActionPreview(3L)));
                }
            }
        }
    }

    @Test
    public void voiceKeyboardPolicyHandlesNoPartialPartialAndFinalizingExplicitly() {
        CompositionConflictPolicy commit = CompositionConflictPolicy.defaults();
        CompositionConflictPolicy cancel = new CompositionConflictPolicy(
                CompositionConflictPolicy.RimeToVoice.CANCEL_RIME,
                CompositionConflictPolicy.VoicePartialToKeyboard.CANCEL_VOICE,
                CompositionConflictPolicy.ActionToVoice.DISCARD_RESULT);

        for (CompositionState noPartial : List.of(
                new CompositionState.VoicePreparing(1L),
                new CompositionState.VoiceListening(1L))) {
            assertEquals(
                    CompositionConflictPolicy.Decision.CANCEL_CURRENT,
                    commit.voiceToKeyboardDecision(noPartial));
            assertEquals(
                    CompositionConflictPolicy.Decision.CANCEL_CURRENT,
                    cancel.voiceToKeyboardDecision(noPartial));
        }
        assertEquals(
                CompositionConflictPolicy.Decision.COMMIT_CURRENT,
                commit.voiceToKeyboardDecision(new CompositionState.VoicePartial(1L, 9L)));
        assertEquals(
                CompositionConflictPolicy.Decision.CANCEL_CURRENT,
                cancel.voiceToKeyboardDecision(new CompositionState.VoicePartial(1L, 9L)));
        assertEquals(
                CompositionConflictPolicy.Decision.CANCEL_CURRENT_AND_ROUTE_RESULT,
                commit.voiceToKeyboardDecision(new CompositionState.VoiceFinalizing(1L, 0L)));
        assertEquals(
                CompositionConflictPolicy.Decision.COMMIT_CURRENT_AND_ROUTE_RESULT,
                commit.voiceToKeyboardDecision(new CompositionState.VoiceFinalizing(1L, 9L)));
        assertEquals(
                CompositionConflictPolicy.Decision.CANCEL_CURRENT_AND_ROUTE_RESULT,
                cancel.voiceToKeyboardDecision(new CompositionState.VoiceFinalizing(1L, 9L)));
    }

    @Test
    public void fixedPairsCommitComposingTextAndActionChoiceCoversBothActionPhases() {
        CompositionConflictPolicy preserve = CompositionConflictPolicy.defaults();
        CompositionConflictPolicy discard = new CompositionConflictPolicy(
                CompositionConflictPolicy.RimeToVoice.CANCEL_RIME,
                CompositionConflictPolicy.VoicePartialToKeyboard.CANCEL_VOICE,
                CompositionConflictPolicy.ActionToVoice.DISCARD_RESULT);

        for (CompositionState composing : List.of(
                new CompositionState.LatinComposing(2L, 1L),
                new CompositionState.RimeComposing(2L, 1L))) {
            assertEquals(
                    CompositionConflictPolicy.Decision.COMMIT_CURRENT,
                    preserve.composingToActionDecision(composing));
        }
        for (CompositionState action : List.of(
                new CompositionState.ActionRunning(2L),
                new CompositionState.ActionPreview(2L))) {
            assertEquals(
                    CompositionConflictPolicy.Decision.CANCEL_CURRENT_AND_ROUTE_RESULT,
                    preserve.actionToVoiceDecision(action));
            assertEquals(
                    CompositionConflictPolicy.Decision.CANCEL_CURRENT,
                    discard.actionToVoiceDecision(action));
        }
    }

    @Test
    public void wrongStateAndNullConfigurationFailBeforeProducingAnIntent() {
        assertNullRejected(() -> new CompositionConflictPolicy(
                null,
                CompositionConflictPolicy.VoicePartialToKeyboard.COMMIT_VISIBLE_PARTIAL,
                CompositionConflictPolicy.ActionToVoice.PRESERVE_RESULT_PANEL));
        assertNullRejected(() -> new CompositionConflictPolicy(
                CompositionConflictPolicy.RimeToVoice.COMMIT_RIME,
                null,
                CompositionConflictPolicy.ActionToVoice.PRESERVE_RESULT_PANEL));
        assertNullRejected(() -> new CompositionConflictPolicy(
                CompositionConflictPolicy.RimeToVoice.COMMIT_RIME,
                CompositionConflictPolicy.VoicePartialToKeyboard.COMMIT_VISIBLE_PARTIAL,
                null));

        CompositionConflictPolicy policy = CompositionConflictPolicy.defaults();
        assertNullRejected(() -> policy.voiceToKeyboardDecision(null));
        assertNullRejected(() -> policy.actionToVoiceDecision(null));
        assertNullRejected(() -> policy.composingToActionDecision(null));
        assertIllegal(() -> policy.voiceToKeyboardDecision(new CompositionState.Idle()));
        assertIllegal(() -> policy.actionToVoiceDecision(
                new CompositionState.VoiceListening(1L)));
        assertIllegal(() -> policy.composingToActionDecision(
                new CompositionState.ActionRunning(1L)));
    }

    @Test
    public void modelShapeIsClosedTextFreeAndNotSerializable() {
        assertTrue(CompositionConflictPolicy.class.isRecord());
        RecordComponent[] components = CompositionConflictPolicy.class.getRecordComponents();
        assertEquals(
                List.of("rimeToVoice", "voicePartialToKeyboard", "actionToVoice"),
                Arrays.stream(components).map(RecordComponent::getName).toList());
        assertEquals(
                List.of(
                        CompositionConflictPolicy.RimeToVoice.class,
                        CompositionConflictPolicy.VoicePartialToKeyboard.class,
                        CompositionConflictPolicy.ActionToVoice.class),
                Arrays.stream(components).map(RecordComponent::getType).toList());

        assertEquals(
                Set.of("COMMIT_RIME", "CANCEL_RIME"),
                enumNames(CompositionConflictPolicy.RimeToVoice.values()));
        assertEquals(
                Set.of("COMMIT_VISIBLE_PARTIAL", "CANCEL_VOICE"),
                enumNames(CompositionConflictPolicy.VoicePartialToKeyboard.values()));
        assertEquals(
                Set.of("PRESERVE_RESULT_PANEL", "DISCARD_RESULT"),
                enumNames(CompositionConflictPolicy.ActionToVoice.values()));
        assertEquals(
                Set.of(
                        "COMMIT_CURRENT",
                        "CANCEL_CURRENT",
                        "COMMIT_CURRENT_AND_ROUTE_RESULT",
                        "CANCEL_CURRENT_AND_ROUTE_RESULT"),
                enumNames(CompositionConflictPolicy.Decision.values()));

        assertFalse(java.io.Serializable.class.isAssignableFrom(
                CompositionConflictPolicy.class));
        for (Class<?> type : List.of(
                CompositionConflictPolicy.class,
                CompositionConflictPolicy.RimeToVoice.class,
                CompositionConflictPolicy.VoicePartialToKeyboard.class,
                CompositionConflictPolicy.ActionToVoice.class,
                CompositionConflictPolicy.Decision.class)) {
            assertFalse(Arrays.stream(type.getInterfaces())
                    .map(Class::getName)
                    .anyMatch(name -> name.equals("android.os.Parcelable")));
            assertFalse(Arrays.stream(type.getDeclaredFields())
                    .map(field -> field.getType().getName())
                    .anyMatch(name -> name.equals("java.lang.String")
                            || name.equals("java.lang.CharSequence")
                            || name.equals("java.lang.Throwable")
                            || name.contains("InputConnection")
                            || name.contains("EditorOperation")));
        }

        for (CompositionConflictPolicy.Decision decision
                : CompositionConflictPolicy.Decision.values()) {
            assertTrue(decision.releaseDirective()
                    == CompositionCoordinator.ReleaseDirective.COMMIT_CURRENT
                    || decision.releaseDirective()
                            == CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT);
        }
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }

    private static void assertNullRejected(Runnable action) {
        try {
            action.run();
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // Expected.
        }
    }

    private static void assertIllegal(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
