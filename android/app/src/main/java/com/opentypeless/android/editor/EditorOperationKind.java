package com.opentypeless.android.editor;

/** Closed, content-free semantic kind used by transaction audit metadata. */
public enum EditorOperationKind {
    SET_COMPOSITION,
    COMMIT_COMPOSITION,
    INSERT_TEXT,
    REPLACE_SELECTION,
    REPLACE_LAST_COMMIT,
    DELETE_BEFORE_CURSOR,
    PERFORM_EDITOR_ACTION
}
