package com.opentypeless.android.ime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EditorTargetGuardTest {
    private final Object connection = new Object();
    private final EditorTargetGuard.Snapshot captured = snapshot(
            7, connection, "com.example", 42, "selected", "before", "after");

    @Test
    public void acceptsOnlyAnUnchangedEditorAndCursorTarget() {
        assertTrue(EditorTargetGuard.matches(captured, snapshot(
                7, connection, "com.example", 42, "selected", "before", "after"), false));
    }

    @Test
    public void rejectsEveryCrossFieldAndCursorMutationVector() {
        assertFalse(EditorTargetGuard.matches(captured, snapshot(
                8, connection, "com.example", 42, "selected", "before", "after"), false));
        assertFalse(EditorTargetGuard.matches(captured, snapshot(
                7, new Object(), "com.example", 42, "selected", "before", "after"), false));
        assertFalse(EditorTargetGuard.matches(captured, snapshot(
                7, connection, "com.other", 42, "selected", "before", "after"), false));
        assertFalse(EditorTargetGuard.matches(captured, snapshot(
                7, connection, "com.example", 43, "selected", "before", "after"), false));
        assertFalse(EditorTargetGuard.matches(captured, snapshot(
                7, connection, "com.example", 42, "changed", "before", "after"), false));
        assertFalse(EditorTargetGuard.matches(captured, snapshot(
                7, connection, "com.example", 42, "selected", "moved", "after"), false));
        assertFalse(EditorTargetGuard.matches(captured, snapshot(
                7, connection, "com.example", 42, "selected", "before", "moved"), false));
        assertFalse(EditorTargetGuard.matches(captured, captured, true));
    }

    private static EditorTargetGuard.Snapshot snapshot(
            long epoch,
            Object connection,
            String app,
            int field,
            String selection,
            String before,
            String after) {
        return new EditorTargetGuard.Snapshot(
                epoch, connection, app, field, selection, before, after);
    }
}
