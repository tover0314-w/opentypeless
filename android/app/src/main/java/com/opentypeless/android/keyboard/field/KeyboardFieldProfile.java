package com.opentypeless.android.keyboard.field;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import com.opentypeless.android.context.FieldKind;
import java.util.Objects;

/** Closed, metadata-only keyboard layout selection for KBD-004. */
public enum KeyboardFieldProfile {
    GENERAL,
    EMAIL,
    URI,
    PHONE,
    NUMBER,
    DATE,
    PASSWORD;

    public static KeyboardFieldProfile from(EditorInfo info, FieldKind fieldKind) {
        Objects.requireNonNull(fieldKind, "fieldKind");
        return fromInputType(info == null ? InputType.TYPE_NULL : info.inputType, fieldKind);
    }

    public static KeyboardFieldProfile fromInputType(int inputType, FieldKind fieldKind) {
        Objects.requireNonNull(fieldKind, "fieldKind");
        if (fieldKind == FieldKind.SENSITIVE) return PASSWORD;
        if (fieldKind == FieldKind.EMAIL_ADDRESS) return EMAIL;
        if (fieldKind == FieldKind.URI) return URI;

        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        return switch (inputClass) {
            case InputType.TYPE_CLASS_PHONE -> PHONE;
            case InputType.TYPE_CLASS_NUMBER -> NUMBER;
            case InputType.TYPE_CLASS_DATETIME -> DATE;
            default -> GENERAL;
        };
    }

    public boolean usesNumericPanel() {
        return this == PHONE || this == NUMBER || this == DATE;
    }
}
