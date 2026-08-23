package com.opentypeless.android.speech.delivery;

import java.util.Objects;

/** Stable sealed prefix plus one replaceable composing tail. */
public record ProjectionDocument(String sealedPrefix, String composingTail) {
    public ProjectionDocument {
        sealedPrefix = Objects.requireNonNullElse(sealedPrefix, "");
        composingTail = Objects.requireNonNullElse(composingTail, "");
        String full = sealedPrefix + composingTail;
        if (full.codePointCount(0, full.length()) > EditorProjectionLimits.MAX_DRAFT_CODE_POINTS) {
            throw new IllegalArgumentException("projection document is too long");
        }
    }

    public static ProjectionDocument shortDraft(String text) {
        return new ProjectionDocument("", text);
    }

    public String fullText() {
        return sealedPrefix + composingTail;
    }
}
