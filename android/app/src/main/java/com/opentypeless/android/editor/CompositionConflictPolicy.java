package com.opentypeless.android.editor;

import java.util.Objects;

/**
 * Immutable, text-free choices for resolving composition conflicts.
 *
 * <p>This value only selects an intent for CMP-002's two-phase preemption handshake. A
 * {@link Decision} is neither proof that the current composition was released nor authority to
 * mutate an editor. CMP-004 must still execute the release through the unique transaction bridge
 * and map its typed result to {@link CompositionCoordinator.ReleaseResolution}.
 */
public record CompositionConflictPolicy(
        RimeToVoice rimeToVoice,
        VoicePartialToKeyboard voicePartialToKeyboard,
        ActionToVoice actionToVoice) {

    /** User choice when voice starts while Rime has visible composition. */
    public enum RimeToVoice {
        COMMIT_RIME,
        CANCEL_RIME
    }

    /** User choice when a keyboard event interrupts a visible voice partial. */
    public enum VoicePartialToKeyboard {
        COMMIT_VISIBLE_PARTIAL,
        CANCEL_VOICE
    }

    /** User choice for a displaced Action result when voice starts. */
    public enum ActionToVoice {
        PRESERVE_RESULT_PANEL,
        DISCARD_RESULT
    }

    /**
     * Closed preemption intent. Routing a displaced result is UI metadata, not editor authority.
     */
    public enum Decision {
        COMMIT_CURRENT(CompositionCoordinator.ReleaseDirective.COMMIT_CURRENT, false),
        CANCEL_CURRENT(CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT, false),
        COMMIT_CURRENT_AND_ROUTE_RESULT(
                CompositionCoordinator.ReleaseDirective.COMMIT_CURRENT, true),
        CANCEL_CURRENT_AND_ROUTE_RESULT(
                CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT, true);

        private final CompositionCoordinator.ReleaseDirective releaseDirective;
        private final boolean routeDisplacedResultToPanel;

        Decision(
                CompositionCoordinator.ReleaseDirective releaseDirective,
                boolean routeDisplacedResultToPanel) {
            this.releaseDirective = releaseDirective;
            this.routeDisplacedResultToPanel = routeDisplacedResultToPanel;
        }

        public CompositionCoordinator.ReleaseDirective releaseDirective() {
            return releaseDirective;
        }

        public boolean routeDisplacedResultToPanel() {
            return routeDisplacedResultToPanel;
        }
    }

    public CompositionConflictPolicy {
        Objects.requireNonNull(rimeToVoice, "rimeToVoice");
        Objects.requireNonNull(voicePartialToKeyboard, "voicePartialToKeyboard");
        Objects.requireNonNull(actionToVoice, "actionToVoice");
    }

    /**
     * Loss-avoiding product defaults: commit visible text and preserve displaced Action output.
     */
    public static CompositionConflictPolicy defaults() {
        return new CompositionConflictPolicy(
                RimeToVoice.COMMIT_RIME,
                VoicePartialToKeyboard.COMMIT_VISIBLE_PARTIAL,
                ActionToVoice.PRESERVE_RESULT_PANEL);
    }

    /** Resolves the configurable Rime-to-voice conflict. */
    public Decision rimeToVoiceDecision() {
        return switch (rimeToVoice) {
            case COMMIT_RIME -> Decision.COMMIT_CURRENT;
            case CANCEL_RIME -> Decision.CANCEL_CURRENT;
        };
    }

    /** Latin composition is always committed before starting voice. */
    public Decision latinToVoiceDecision() {
        return Decision.COMMIT_CURRENT;
    }

    /**
     * Resolves a keyboard event against an exact voice phase.
     *
     * <p>Preparing/listening has no visible partial to preserve. A keyboard event during Finalizing
     * keeps or cancels the already visible partial according to the configured choice, then treats
     * the key as an explicit cancellation of the displaced late Final.
     */
    public Decision voiceToKeyboardDecision(CompositionState voiceState) {
        Objects.requireNonNull(voiceState, "voiceState");
        if (voiceState instanceof CompositionState.VoicePreparing
                || voiceState instanceof CompositionState.VoiceListening) {
            return Decision.CANCEL_CURRENT;
        }
        if (voiceState instanceof CompositionState.VoicePartial) {
            return voicePartialToKeyboard == VoicePartialToKeyboard.COMMIT_VISIBLE_PARTIAL
                    ? Decision.COMMIT_CURRENT
                    : Decision.CANCEL_CURRENT;
        }
        if (voiceState instanceof CompositionState.VoiceFinalizing finalizing) {
            boolean commitVisible = finalizing.latestRevision() > 0L
                    && voicePartialToKeyboard
                            == VoicePartialToKeyboard.COMMIT_VISIBLE_PARTIAL;
            return commitVisible
                    ? Decision.COMMIT_CURRENT
                    : Decision.CANCEL_CURRENT;
        }
        throw wrongState();
    }

    /** Resolves Action-running/preview displacement when voice starts. */
    public Decision actionToVoiceDecision(CompositionState actionState) {
        Objects.requireNonNull(actionState, "actionState");
        if (!(actionState instanceof CompositionState.ActionRunning)
                && !(actionState instanceof CompositionState.ActionPreview)) {
            throw wrongState();
        }
        return actionToVoice == ActionToVoice.PRESERVE_RESULT_PANEL
                ? Decision.CANCEL_CURRENT_AND_ROUTE_RESULT
                : Decision.CANCEL_CURRENT;
    }

    /** Latin or Rime composition is committed before an Action captures a fresh target. */
    public Decision composingToActionDecision(CompositionState composingState) {
        Objects.requireNonNull(composingState, "composingState");
        if (composingState instanceof CompositionState.LatinComposing
                || composingState instanceof CompositionState.RimeComposing) {
            return Decision.COMMIT_CURRENT;
        }
        throw wrongState();
    }

    private static IllegalArgumentException wrongState() {
        return new IllegalArgumentException("state is not valid for this conflict");
    }
}
