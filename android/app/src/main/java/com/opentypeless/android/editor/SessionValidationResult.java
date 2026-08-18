package com.opentypeless.android.editor;

import java.util.Objects;

/** Content-free result of comparing an expected editor session with current evidence. */
public sealed interface SessionValidationResult
        permits SessionValidationResult.Valid, SessionValidationResult.Invalid {
    /** All required target evidence still matches. */
    record Valid() implements SessionValidationResult {}

    /** The target is no longer safe to use. */
    record Invalid(TargetChangeReason reason) implements SessionValidationResult {
        public Invalid {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String toString() {
            return "Invalid{reason=" + reason + '}';
        }
    }
}
