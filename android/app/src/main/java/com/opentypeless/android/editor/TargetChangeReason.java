package com.opentypeless.android.editor;

/** Stable, content-free reasons why an editor target can no longer be trusted. */
public enum TargetChangeReason {
    NO_ACTIVE_SESSION,
    SESSION_REVOKED,
    EPOCH_CHANGED,
    CONNECTION_CHANGED,
    EDITOR_METADATA_CHANGED,
    SELECTION_CHANGED,
    SELECTED_TEXT_CHANGED,
    SURROUNDING_TEXT_CHANGED,
    SECURITY_STATE_CHANGED,
    EVIDENCE_UNAVAILABLE
}
