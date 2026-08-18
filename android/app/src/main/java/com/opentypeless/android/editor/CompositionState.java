package com.opentypeless.android.editor;

/**
 * Immutable and closed composition-domain state.
 *
 * <p>Every state fixes its own {@link CompositionOwner}; callers cannot supply a separate owner
 * that could drift from the phase. CMP-002 owns transitions between these values.
 */
public sealed interface CompositionState permits
        CompositionState.Idle,
        CompositionState.LatinComposing,
        CompositionState.RimeComposing,
        CompositionState.VoicePreparing,
        CompositionState.VoiceListening,
        CompositionState.VoicePartial,
        CompositionState.VoiceFinalizing,
        CompositionState.ActionRunning,
        CompositionState.ActionPreview {

    /**
     * Fixed writable-composition owner. {@link CompositionOwner#NONE} applies to both idle and an
     * action that is still running without an editor preview.
     */
    CompositionOwner owner();

    /** Zero only for {@link Idle}; every active state belongs to one positive generation. */
    long coordinationGeneration();

    record Idle() implements CompositionState {
        @Override
        public CompositionOwner owner() {
            return CompositionOwner.NONE;
        }

        @Override
        public long coordinationGeneration() {
            return 0L;
        }
    }

    record LatinComposing(long coordinationGeneration, long revision)
            implements CompositionState {
        public LatinComposing {
            requirePositive(coordinationGeneration, "coordinationGeneration");
            requirePositive(revision, "revision");
        }

        @Override
        public CompositionOwner owner() {
            return CompositionOwner.LATIN;
        }
    }

    record RimeComposing(long coordinationGeneration, long revision)
            implements CompositionState {
        public RimeComposing {
            requirePositive(coordinationGeneration, "coordinationGeneration");
            requirePositive(revision, "revision");
        }

        @Override
        public CompositionOwner owner() {
            return CompositionOwner.RIME;
        }
    }

    record VoicePreparing(long coordinationGeneration) implements CompositionState {
        public VoicePreparing {
            requirePositive(coordinationGeneration, "coordinationGeneration");
        }

        @Override
        public CompositionOwner owner() {
            return CompositionOwner.VOICE;
        }
    }

    record VoiceListening(long coordinationGeneration) implements CompositionState {
        public VoiceListening {
            requirePositive(coordinationGeneration, "coordinationGeneration");
        }

        @Override
        public CompositionOwner owner() {
            return CompositionOwner.VOICE;
        }
    }

    record VoicePartial(long coordinationGeneration, long revision)
            implements CompositionState {
        public VoicePartial {
            requirePositive(coordinationGeneration, "coordinationGeneration");
            requirePositive(revision, "revision");
        }

        @Override
        public CompositionOwner owner() {
            return CompositionOwner.VOICE;
        }
    }

    record VoiceFinalizing(long coordinationGeneration, long latestRevision)
            implements CompositionState {
        public VoiceFinalizing {
            requirePositive(coordinationGeneration, "coordinationGeneration");
            if (latestRevision < 0L) {
                throw new IllegalArgumentException("latestRevision must not be negative");
            }
        }

        @Override
        public CompositionOwner owner() {
            return CompositionOwner.VOICE;
        }
    }

    record ActionRunning(long coordinationGeneration) implements CompositionState {
        public ActionRunning {
            requirePositive(coordinationGeneration, "coordinationGeneration");
        }

        @Override
        public CompositionOwner owner() {
            return CompositionOwner.NONE;
        }
    }

    record ActionPreview(long coordinationGeneration) implements CompositionState {
        public ActionPreview {
            requirePositive(coordinationGeneration, "coordinationGeneration");
        }

        @Override
        public CompositionOwner owner() {
            return CompositionOwner.ACTION_PREVIEW;
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) throw new IllegalArgumentException(name + " must be positive");
    }
}
