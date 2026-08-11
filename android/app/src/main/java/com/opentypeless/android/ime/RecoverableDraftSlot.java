package com.opentypeless.android.ime;

/**
 * A fail-closed single recoverable draft slot. A different session cannot overwrite an unresolved
 * draft; the user must explicitly insert or discard it first. The same source session may replace
 * a live partial with its later authoritative final result.
 */
final class RecoverableDraftSlot {
    record Draft(String text, Object source) {}

    private Draft draft;

    synchronized boolean save(String text, Object source) {
        String safeText = text == null ? "" : text;
        if (safeText.isBlank()) return false;
        if (draft != null && draft.source() != source) return false;
        draft = new Draft(safeText, source);
        return true;
    }

    synchronized boolean restore(String text) {
        if (draft != null) return false;
        String safeText = text == null ? "" : text;
        if (safeText.isBlank()) return false;
        draft = new Draft(safeText, null);
        return true;
    }

    synchronized Draft get() {
        return draft;
    }

    synchronized boolean hasDraft() {
        return draft != null;
    }

    synchronized void clear() {
        draft = null;
    }
}
