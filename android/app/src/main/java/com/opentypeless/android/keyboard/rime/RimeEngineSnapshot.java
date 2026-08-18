package com.opentypeless.android.keyboard.rime;

import com.opentypeless.android.keyboard.candidate.CandidatePage;
import java.util.Objects;
import java.util.Optional;

/** Immutable, bounded and content-redacted state emitted by a {@link RimeInputEngine}. */
public final class RimeEngineSnapshot {
    public static final int MAXIMUM_TEXT_CODE_POINTS = 256;
    public static final int MAXIMUM_TEXT_UTF16_UNITS = 512;

    public enum Phase {
        INACTIVE,
        ACTIVE
    }

    private static final String PRODUCER_ID = "rime";

    private final Phase phase;
    private final long editorGeneration;
    private final long coordinationGeneration;
    private final long revision;
    private final String preedit;
    private final CandidatePage candidatePage;

    private RimeEngineSnapshot(
            Phase phase,
            long editorGeneration,
            long coordinationGeneration,
            long revision,
            String preedit,
            CandidatePage candidatePage) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.preedit = requireBoundedText(preedit, true, "preedit");
        if (phase == Phase.INACTIVE) {
            if (editorGeneration != 0L
                    || coordinationGeneration != 0L
                    || revision != 0L
                    || !preedit.isEmpty()
                    || candidatePage != null) {
                throw new IllegalArgumentException("inactive snapshot must be empty");
            }
        } else {
            requirePositive(editorGeneration, "editorGeneration");
            requirePositive(coordinationGeneration, "coordinationGeneration");
            requirePositive(revision, "revision");
            if (candidatePage != null) {
                if (!PRODUCER_ID.equals(candidatePage.producerId())
                        || candidatePage.generation() != coordinationGeneration
                        || candidatePage.pageRevision() != revision) {
                    throw new IllegalArgumentException(
                            "candidate page must match the active Rime generation and revision");
                }
            }
        }
        this.editorGeneration = editorGeneration;
        this.coordinationGeneration = coordinationGeneration;
        this.revision = revision;
        this.candidatePage = candidatePage;
    }

    public static RimeEngineSnapshot inactive() {
        return new RimeEngineSnapshot(Phase.INACTIVE, 0L, 0L, 0L, "", null);
    }

    public static RimeEngineSnapshot active(
            long editorGeneration,
            long coordinationGeneration,
            long revision,
            String preedit,
            CandidatePage candidatePage) {
        return new RimeEngineSnapshot(
                Phase.ACTIVE,
                editorGeneration,
                coordinationGeneration,
                revision,
                preedit,
                candidatePage);
    }

    public Phase phase() {
        return phase;
    }

    public long editorGeneration() {
        return editorGeneration;
    }

    public long coordinationGeneration() {
        return coordinationGeneration;
    }

    public long revision() {
        return revision;
    }

    public String preedit() {
        return preedit;
    }

    public Optional<CandidatePage> candidatePage() {
        return Optional.ofNullable(candidatePage);
    }

    public boolean hasComposition() {
        return !preedit.isEmpty() || candidatePage != null;
    }

    static String requireBoundedText(String value, boolean allowEmpty, String name) {
        Objects.requireNonNull(value, name);
        if ((!allowEmpty && value.isEmpty())
                || value.length() > MAXIMUM_TEXT_UTF16_UNITS
                || value.codePointCount(0, value.length()) > MAXIMUM_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException(name + " is outside the bounded text contract");
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint) || isBidiControl(codePoint)) {
                throw new IllegalArgumentException(name + " contains a control character");
            }
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    static void requirePositive(long value, String name) {
        if (value <= 0L) throw new IllegalArgumentException(name + " must be positive");
    }

    static boolean isBidiControl(int codePoint) {
        return codePoint == 0x061c
                || (codePoint >= 0x200e && codePoint <= 0x200f)
                || (codePoint >= 0x202a && codePoint <= 0x202e)
                || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    @Override
    public String toString() {
        return "RimeEngineSnapshot{phase=" + phase
                + ", editorGeneration=" + editorGeneration
                + ", coordinationGeneration=" + coordinationGeneration
                + ", revision=" + revision
                + ", preedit=<redacted>, candidateCount="
                + (candidatePage == null ? 0 : candidatePage.items().size()) + '}';
    }
}
