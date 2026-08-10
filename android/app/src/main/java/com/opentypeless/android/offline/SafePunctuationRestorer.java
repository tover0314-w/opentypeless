package com.opentypeless.android.offline;

import com.opentypeless.android.context.FieldKind;

import java.text.Normalizer;
import java.util.Locale;

/** Accepts SenseVoice ITN punctuation only when the underlying words and facts are unchanged. */
public final class SafePunctuationRestorer {
    private static final int MAX_CODE_POINTS = 20_000;

    private SafePunctuationRestorer() {}

    public static String choose(String conservative, String punctuated, FieldKind fieldKind) {
        String raw = safe(conservative).trim();
        String candidate = safe(punctuated).trim();
        if (raw.isEmpty()) return raw;
        if (!punctuationAppropriate(fieldKind)) return raw;
        if (!candidate.isEmpty()
                && candidate.codePointCount(0, candidate.length()) <= MAX_CODE_POINTS
                && contentKey(raw).equals(contentKey(candidate))) {
            return candidate;
        }
        return appendSafeTerminal(raw);
    }

    static String contentKey(String value) {
        String normalized = Normalizer.normalize(safe(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        // Keep word boundaries so an ITN candidate such as "icecream" cannot be accepted as a
        // punctuation-only rewrite of "ice cream".
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isPunctuation(codePoint)) continue;
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = result.length() > 0;
                continue;
            }
            if (pendingSpace) result.append(' ');
            pendingSpace = false;
            result.appendCodePoint(codePoint);
        }
        return result.toString();
    }

    private static boolean punctuationAppropriate(FieldKind fieldKind) {
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

    private static boolean isPunctuation(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
