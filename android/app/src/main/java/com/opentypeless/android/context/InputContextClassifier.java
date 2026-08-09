package com.opentypeless.android.context;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

public final class InputContextClassifier {
    private InputContextClassifier() {}

    public static FieldKind classify(EditorInfo info) {
        if (info == null) return FieldKind.GENERAL;
        int inputClass = info.inputType & InputType.TYPE_MASK_CLASS;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;

        if (isSensitive(inputClass, variation)) return FieldKind.SENSITIVE;
        if (inputClass == InputType.TYPE_CLASS_NUMBER
                || inputClass == InputType.TYPE_CLASS_PHONE
                || inputClass == InputType.TYPE_CLASS_DATETIME) {
            return FieldKind.NUMBER;
        }
        if (inputClass != InputType.TYPE_CLASS_TEXT) return FieldKind.GENERAL;

        return switch (variation) {
            case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL_ADDRESS;
            case InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URI;
            case InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> FieldKind.PERSON_NAME;
            case InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> FieldKind.SHORT_MESSAGE;
            case InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE -> FieldKind.LONG_TEXT;
            case InputType.TYPE_TEXT_VARIATION_FILTER -> FieldKind.SEARCH;
            default -> isSearchAction(info.imeOptions) ? FieldKind.SEARCH : FieldKind.GENERAL;
        };
    }

    public static boolean isSensitive(int inputClass, int variation) {
        return (inputClass == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || (inputClass == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    private static boolean isSearchAction(int imeOptions) {
        return (imeOptions & EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH;
    }
}
