package com.opentypeless.android.context;

import com.opentypeless.android.settings.ProcessingMode;

public final class InputPolicy {
    private InputPolicy() {}

    public static ProcessingMode resolve(ProcessingMode requested, InputContext context) {
        if (requested != ProcessingMode.AUTO) return requested;
        if (context.hasSelection()) return ProcessingMode.SMART;
        return switch (context.fieldKind()) {
            case EMAIL_ADDRESS, URI, NUMBER, PERSON_NAME, SEARCH, SENSITIVE -> ProcessingMode.VERBATIM;
            case SHORT_MESSAGE, LONG_TEXT, GENERAL -> ProcessingMode.SMART;
        };
    }
}
