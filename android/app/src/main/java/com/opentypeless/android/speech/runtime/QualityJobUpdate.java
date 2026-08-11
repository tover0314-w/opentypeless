package com.opentypeless.android.speech.runtime;

import java.util.Objects;
import java.util.Optional;

public record QualityJobUpdate(
        QualityJobDisposition disposition,
        Optional<QualityJobToken> token,
        String detail) {
    public QualityJobUpdate {
        Objects.requireNonNull(disposition, "disposition");
        token = Objects.requireNonNull(token, "token");
        detail = Objects.requireNonNullElse(detail, "");
    }
}
