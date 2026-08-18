package com.opentypeless.android.editor;

import java.util.Objects;

/**
 * Immutable, bounded and closed editor-operation contract.
 *
 * <p>The model contains no Android capability, arbitrary method name, persistence or serialization
 * contract. Only a future {@code EditorTransactionManager} may interpret these values as writes.
 */
public sealed interface EditorOperation permits
        EditorOperation.SetComposition,
        EditorOperation.CommitComposition,
        EditorOperation.InsertText,
        EditorOperation.ReplaceSelection,
        EditorOperation.ReplaceLastCommit,
        EditorOperation.DeleteBeforeCursor,
        EditorOperation.PerformEditorAction {

    /** Matches the Action protocol v1 string boundary and is counted as Unicode code points. */
    int MAX_TEXT_CODE_POINTS = EditorOperationLimits.MAX_TEXT_CODE_POINTS;
    int MAX_COMMIT_ID_CODE_POINTS = 128;
    int MAX_DELETE_CODE_POINTS = 40_000;

    OperationSource source();

    record SetComposition(
            String text,
            CompositionOwner owner,
            long revision,
            OperationSource source) implements EditorOperation {
        public SetComposition {
            text = requireOperationText(text, "text", true);
            requireCompositionSource(owner, source);
            if (revision <= 0) throw new IllegalArgumentException("revision must be positive");
        }

        @Override
        public String toString() {
            return "SetComposition{textCodePoints=" + codePointCount(text)
                    + ", owner=" + owner
                    + ", revision=" + revision
                    + ", source=" + source + '}';
        }
    }

    /** expectedRevision prevents a late final from committing a newer same-owner composition. */
    record CommitComposition(
            CompositionOwner owner,
            long expectedRevision,
            OperationSource source) implements EditorOperation {
        public CommitComposition {
            requireCompositionSource(owner, source);
            if (expectedRevision <= 0) {
                throw new IllegalArgumentException("expectedRevision must be positive");
            }
        }
    }

    record InsertText(String text, OperationSource source) implements EditorOperation {
        public InsertText {
            text = requireOperationText(text, "text", false);
            source = Objects.requireNonNull(source, "source");
        }

        @Override
        public String toString() {
            return "InsertText{textCodePoints=" + codePointCount(text)
                    + ", source=" + source + '}';
        }
    }

    record ReplaceSelection(
            TextRange expectedSelection,
            TextFingerprint expectedTextHash,
            String text,
            OperationSource source) implements EditorOperation {
        public ReplaceSelection {
            expectedSelection = requireSelection(expectedSelection);
            expectedTextHash = requireFingerprint(
                    expectedTextHash, FingerprintDomain.SELECTED_TEXT, "expectedTextHash");
            text = requireOperationText(text, "text", true);
            source = Objects.requireNonNull(source, "source");
        }

        @Override
        public String toString() {
            return "ReplaceSelection{textCodePoints=" + codePointCount(text)
                    + ", source=" + source + ", target=<redacted>}";
        }
    }

    record ReplaceLastCommit(
            String commitId,
            TextFingerprint expectedTextHash,
            String text,
            OperationSource source) implements EditorOperation {
        public ReplaceLastCommit {
            commitId = requireIdentifier(commitId, "commitId", MAX_COMMIT_ID_CODE_POINTS);
            expectedTextHash = requireFingerprint(
                    expectedTextHash, FingerprintDomain.COMMITTED_TEXT, "expectedTextHash");
            text = requireOperationText(text, "text", true);
            source = Objects.requireNonNull(source, "source");
        }

        @Override
        public String toString() {
            return "ReplaceLastCommit{textCodePoints=" + codePointCount(text)
                    + ", source=" + source + ", target=<redacted>}";
        }
    }

    record DeleteBeforeCursor(int codePoints, OperationSource source) implements EditorOperation {
        public DeleteBeforeCursor {
            if (codePoints <= 0 || codePoints > MAX_DELETE_CODE_POINTS) {
                throw new IllegalArgumentException(
                        "codePoints must be between 1 and " + MAX_DELETE_CODE_POINTS);
            }
            source = Objects.requireNonNull(source, "source");
        }
    }

    record PerformEditorAction(EditorAction action, OperationSource source)
            implements EditorOperation {
        public PerformEditorAction {
            action = Objects.requireNonNull(action, "action");
            source = Objects.requireNonNull(source, "source");
            if (source != OperationSource.LATIN && source != OperationSource.RIME) {
                throw new IllegalArgumentException(
                        "editor actions are allowed only for direct keyboard sources");
            }
        }
    }

    private static TextRange requireSelection(TextRange value) {
        TextRange safe = Objects.requireNonNull(value, "expectedSelection");
        if (!safe.hasSelection()) {
            throw new IllegalArgumentException("expectedSelection must be known and non-collapsed");
        }
        long span = Math.abs((long) safe.end() - safe.start());
        if (span > EditorSessionLimits.MAX_SELECTED_TEXT_CODE_POINTS * 2L) {
            throw new IllegalArgumentException("expectedSelection exceeds the selected-text bound");
        }
        return safe;
    }

    private static TextFingerprint requireFingerprint(
            TextFingerprint value, FingerprintDomain domain, String name) {
        TextFingerprint safe = Objects.requireNonNull(value, name);
        if (safe.domain() != domain) {
            throw new IllegalArgumentException(name + " must use " + domain + " domain");
        }
        return safe;
    }

    private static void requireCompositionSource(
            CompositionOwner owner, OperationSource source) {
        CompositionOwner safeOwner = Objects.requireNonNull(owner, "owner");
        OperationSource safeSource = Objects.requireNonNull(source, "source");
        if (safeOwner == CompositionOwner.NONE) {
            throw new IllegalArgumentException("NONE does not own a composition");
        }
        boolean compatible = switch (safeOwner) {
            case LATIN -> safeSource == OperationSource.LATIN;
            case RIME -> safeSource == OperationSource.RIME;
            case VOICE -> safeSource == OperationSource.VOICE;
            case ACTION_PREVIEW -> safeSource == OperationSource.ACTION;
            case NONE -> false;
        };
        if (!compatible) {
            throw new IllegalArgumentException("composition owner and source must match");
        }
    }

    private static String requireOperationText(String value, String name, boolean emptyAllowed) {
        return EditorOperationLimits.requireText(value, name, emptyAllowed);
    }

    private static String requireIdentifier(String value, String name, int maximumCodePoints) {
        String safe = Objects.requireNonNull(value, name);
        if (safe.length() > maximumCodePoints * 2) {
            throw new IllegalArgumentException(name + " exceeds its bound");
        }
        EditorSessionLimits.requireWellFormedUtf16(safe, name);
        if (codePointCount(safe) > maximumCodePoints) {
            throw new IllegalArgumentException(name + " exceeds its bound");
        }
        if (safe.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        for (int offset = 0; offset < safe.length(); ) {
            int codePoint = safe.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException(name + " contains a control character");
            }
            offset += Character.charCount(codePoint);
        }
        return safe;
    }

    private static int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }
}
