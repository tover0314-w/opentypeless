package com.opentypeless.android.offline;

import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.speech.transform.PunctuationTransform;

/** Accepts SenseVoice ITN punctuation only when the underlying words and facts are unchanged. */
public final class SafePunctuationRestorer {
    private static final int MAX_CODE_POINTS = 20_000;

    private SafePunctuationRestorer() {}

    public static String choose(String conservative, String punctuated, FieldKind fieldKind) {
        String raw = safe(conservative).trim();
        String candidate = safe(punctuated).trim();
        if (raw.isEmpty()) return raw;
        if (!prefersPunctuation(fieldKind)) return raw;
        if (!candidate.isEmpty()
                && candidate.codePointCount(0, candidate.length()) <= MAX_CODE_POINTS
                && PunctuationTransform.preservesLexicalContent(raw, candidate)) {
            return candidate;
        }
        return appendSafeTerminal(raw);
    }

    static String contentKey(String value) {
        return PunctuationTransform.contentKey(safe(value));
    }

    /** Whether the field can safely request the recognizer's formatted/ITN output directly. */
    public static boolean prefersPunctuation(FieldKind fieldKind) {
        FieldKind safeKind = fieldKind == null ? FieldKind.GENERAL : fieldKind;
        return safeKind == FieldKind.GENERAL
                || safeKind == FieldKind.SHORT_MESSAGE
                || safeKind == FieldKind.LONG_TEXT;
    }

    private static String appendSafeTerminal(String value) {
        int last = value.codePointBefore(value.length());
        if (".!?。！？…".codePoints().anyMatch(candidate -> candidate == last)) return value;
        boolean containsHan = value.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                        == Character.UnicodeScript.HAN);
        return value + (containsHan ? "。" : ".");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
