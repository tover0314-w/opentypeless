package com.opentypeless.android.speech.delivery;

import java.util.Objects;

public record UndoResult(UndoDisposition disposition, String detail) {
    public UndoResult {
        Objects.requireNonNull(disposition, "disposition");
        detail = Objects.requireNonNullElse(detail, "");
    }
}
