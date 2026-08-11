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
        if (!enabled() || update.finalResult()) return ApplyResult.DISABLED;
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

        int expectedEnd = originalSelectionStart + replacement.length();
        rememberExpectedSelection(expectedEnd);
        try {
            if (!connection.setComposingText(replacement, 1)) {
                expectedSelectionEnds.removeLastOccurrence(expectedEnd);
                return ApplyResult.REJECTED;
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
            removed = connection.setComposingText("", 1);
            if (!removed) removed = connection.commitText("", 1);
            if (removed) connection.finishComposingText();
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
    }

    private void rememberExpectedSelection(int selectionEnd) {
        expectedSelectionEnds.addLast(selectionEnd);
        while (expectedSelectionEnds.size() > MAX_PENDING_SELECTIONS) {
            expectedSelectionEnds.removeFirst();
        }
    }
}
