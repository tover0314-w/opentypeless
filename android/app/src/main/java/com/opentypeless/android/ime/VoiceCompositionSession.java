package com.opentypeless.android.ime;

import android.view.inputmethod.InputConnection;

import java.util.ArrayDeque;
import java.util.Deque;

/** Owns only the replaceable editor composition created by one voice session. */
final class VoiceCompositionSession {
    enum ApplyResult {
        APPLIED,
        UNCHANGED,
        STALE,
        DISABLED,
        REJECTED,
        CONNECTION_ERROR
    }

    private static final int MAX_PENDING_SELECTIONS = 8;

    private final InputConnection connection;
    private final int originalSelectionStart;
    private final int originalSelectionEnd;
    private final Deque<Integer> expectedSelectionEnds = new ArrayDeque<>();

    private long latestSequence;
    private String composingText = "";
    private boolean liveUpdatesEnabled = true;
    private boolean committedFallback;

    VoiceCompositionSession(
            InputConnection connection,
            int originalSelectionStart,
            int originalSelectionEnd) {
        this.connection = connection;
        this.originalSelectionStart = originalSelectionStart;
        this.originalSelectionEnd = originalSelectionEnd;
    }

    boolean enabled() {
        return liveUpdatesEnabled
                && connection != null
                && originalSelectionStart >= 0
                && originalSelectionStart == originalSelectionEnd;
    }

    boolean ownsComposition() {
        return !composingText.isEmpty();
    }

    String composingText() {
        return composingText;
    }

    ApplyResult apply(TranscriptUpdate update) {
        if (!enabled()) return ApplyResult.DISABLED;
        if (update.sequence() <= latestSequence) return ApplyResult.STALE;
        String replacement = update.text();
        if (replacement.isBlank()) {
            latestSequence = update.sequence();
            return ApplyResult.UNCHANGED;
        }
        if (replacement.equals(composingText)) {
            latestSequence = update.sequence();
            return ApplyResult.UNCHANGED;
        }

        if (committedFallback) {
            return applyCommittedRevision(update.sequence(), replacement);
        }

        int expectedEnd = originalSelectionStart + replacement.length();
        rememberExpectedSelection(expectedEnd);
        try {
            if (!connection.setComposingText(replacement, 1)) {
                expectedSelectionEnds.removeLastOccurrence(expectedEnd);
                return commitFallbackRevision(update.sequence(), replacement);
            }
        } catch (RuntimeException ignored) {
            expectedSelectionEnds.removeLastOccurrence(expectedEnd);
            return ApplyResult.CONNECTION_ERROR;
        }
        latestSequence = update.sequence();
        composingText = replacement;
        return ApplyResult.APPLIED;
    }

    /**
     * Distinguishes editor selection callbacks caused by our own composing replacement from an
     * actual user cursor move. Some OEM editors omit the composing range and some deliver an older
     * acknowledged revision after a newer one, so a small bounded set of expected ends is retained.
     */
    boolean acceptsSelection(
            int selectionStart,
            int selectionEnd,
            int candidatesStart,
            int candidatesEnd) {
        if (!enabled() || selectionStart != selectionEnd) return false;
        if (!ownsComposition()) {
            return selectionStart == originalSelectionStart;
        }
        if (!expectedSelectionEnds.contains(selectionEnd)) return false;
        boolean rangeOmitted = candidatesStart < 0 && candidatesEnd < 0;
        boolean ownedRange = candidatesStart == originalSelectionStart
                && expectedSelectionEnds.contains(candidatesEnd);
        return rangeOmitted || ownedRange;
    }

    /** Replaces the owned composing range with the accepted final text. */
    boolean commitFinal(String finalText) {
        if (!ownsComposition()) return false;
        if (committedFallback) {
            MutationResult result = replaceCommittedText(finalText == null ? "" : finalText);
            if (result != MutationResult.APPLIED) return false;
            discardState();
            return true;
        }
        try {
            if (!connection.commitText(finalText == null ? "" : finalText, 1)) return false;
        } catch (RuntimeException ignored) {
            return false;
        }
        discardState();
        return true;
    }

    /**
     * Stops treating the current composing range as provisional without deleting it. This is used
     * when an editor/window goes away for reasons other than an explicit user discard.
     */
    boolean preserve() {
        if (!ownsComposition()) {
            discardState();
            return true;
        }
        if (committedFallback) {
            // The compatibility preview is already committed in the editor. Detaching only drops
            // our ownership; it must never append or delete text during a lifecycle transition.
            discardState();
            return true;
        }
        boolean preserved;
        try {
            preserved = connection.finishComposingText();
        } catch (RuntimeException ignored) {
            preserved = false;
        }
        discardState();
        return preserved;
    }

    void disableLiveUpdates() {
        liveUpdatesEnabled = false;
    }

    /** Removes only this session's composing range. It never deletes surrounding committed text. */
    boolean cancel() {
        if (!ownsComposition()) {
            discardState();
            return true;
        }
        String ownedText = composingText;
        boolean removed;
        try {
            if (committedFallback) {
                removed = connection.deleteSurroundingTextInCodePoints(
                        ownedText.codePointCount(0, ownedText.length()), 0);
            } else {
                removed = connection.setComposingText("", 1);
                if (!removed) removed = connection.commitText("", 1);
                if (removed) connection.finishComposingText();
            }
            CharSequence remaining = removed
                    ? connection.getTextBeforeCursor(ownedText.length(), 0)
                    : null;
            if (remaining != null && ownedText.contentEquals(remaining)) removed = false;
        } catch (RuntimeException ignored) {
            removed = false;
        }
        discardState();
        return removed;
    }

    void discardState() {
        composingText = "";
        expectedSelectionEnds.clear();
        liveUpdatesEnabled = false;
        committedFallback = false;
    }

    /**
     * Some OEM/editor pairs explicitly reject composing spans. In that case, keep the live text
     * in the host field as a replaceable committed draft. Every later revision first removes only
     * the exact draft owned by this session; target fingerprints are revalidated by the IME before
     * this method is called.
     */
    private ApplyResult commitFallbackRevision(long sequence, String replacement) {
        int expectedEnd = originalSelectionStart + replacement.length();
        rememberExpectedSelection(expectedEnd);
        try {
            // If an earlier composing revision exists, commitText replaces that composition. If
            // this is the first revision, it inserts the new compatibility draft at the cursor.
            if (!connection.commitText(replacement, 1)) {
                expectedSelectionEnds.removeLastOccurrence(expectedEnd);
                return ApplyResult.REJECTED;
            }
        } catch (RuntimeException ignored) {
            expectedSelectionEnds.removeLastOccurrence(expectedEnd);
            return ApplyResult.CONNECTION_ERROR;
        }
        latestSequence = sequence;
        composingText = replacement;
        committedFallback = true;
        return ApplyResult.APPLIED;
    }

    private ApplyResult applyCommittedRevision(long sequence, String replacement) {
        int expectedEnd = originalSelectionStart + replacement.length();
        rememberExpectedSelection(expectedEnd);
        MutationResult result = replaceCommittedText(replacement);
        if (result != MutationResult.APPLIED) {
            expectedSelectionEnds.removeLastOccurrence(expectedEnd);
            return result == MutationResult.REJECTED
                    ? ApplyResult.REJECTED
                    : ApplyResult.CONNECTION_ERROR;
        }
        latestSequence = sequence;
        composingText = replacement;
        return ApplyResult.APPLIED;
    }

    private MutationResult replaceCommittedText(String replacement) {
        String previous = composingText;
        int previousCodePoints = previous.codePointCount(0, previous.length());
        boolean deleted = false;
        boolean batchStarted = false;
        try {
            batchStarted = connection.beginBatchEdit();
            if (!connection.deleteSurroundingTextInCodePoints(previousCodePoints, 0)) {
                return MutationResult.REJECTED;
            }
            deleted = true;
            if (connection.commitText(replacement, 1)) return MutationResult.APPLIED;
            if (connection.commitText(previous, 1)) return MutationResult.REJECTED;
            composingText = "";
            committedFallback = false;
            expectedSelectionEnds.clear();
            return MutationResult.CONNECTION_ERROR;
        } catch (RuntimeException ignored) {
            if (deleted) {
                try {
                    if (!connection.commitText(previous, 1)) {
                        composingText = "";
                        committedFallback = false;
                        expectedSelectionEnds.clear();
                    }
                } catch (RuntimeException rollbackIgnored) {
                    composingText = "";
                    committedFallback = false;
                    expectedSelectionEnds.clear();
                }
            }
            return MutationResult.CONNECTION_ERROR;
        } finally {
            if (batchStarted) {
                try {
                    connection.endBatchEdit();
                } catch (RuntimeException ignored) {
                    // The mutation result above remains authoritative; cleanup is best effort.
                }
            }
        }
    }

    private enum MutationResult { APPLIED, REJECTED, CONNECTION_ERROR }

    private void rememberExpectedSelection(int selectionEnd) {
        expectedSelectionEnds.addLast(selectionEnd);
        while (expectedSelectionEnds.size() > MAX_PENDING_SELECTIONS) {
            expectedSelectionEnds.removeFirst();
        }
    }
}
