package com.opentypeless.android.speech.transform;

import java.util.Objects;

/** Redacted decision record. It intentionally stores no transcript content. */
public record TransformAudit(
        TransformKind kind,
        TransformDisposition disposition,
        String reason) {
    public TransformAudit {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(disposition, "disposition");
        reason = Objects.requireNonNullElse(reason, "");
        if (reason.codePointCount(0, reason.length()) > 240) {
            throw new IllegalArgumentException("transform audit reason is too long");
        }
    }
}
