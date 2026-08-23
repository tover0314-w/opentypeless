package com.opentypeless.android.editor;

/** Minimal composition capability identity. CMP-001 adds composition state and invariants. */
public enum CompositionOwner {
    NONE,
    LATIN,
    RIME,
    VOICE,
    ACTION_PREVIEW
}
