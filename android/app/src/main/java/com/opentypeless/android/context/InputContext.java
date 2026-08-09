package com.opentypeless.android.context;

public record InputContext(
        String packageName,
        FieldKind fieldKind,
        String selectedText,
        String beforeCursor,
        boolean personalizedLearningAllowed) {

    public InputContext {
        packageName = packageName == null ? "" : packageName;
        fieldKind = fieldKind == null ? FieldKind.GENERAL : fieldKind;
        selectedText = selectedText == null ? "" : selectedText;
        beforeCursor = beforeCursor == null ? "" : beforeCursor;
    }

    public boolean hasSelection() {
        return !selectedText.trim().isEmpty();
    }
}
