package com.opentypeless.android.speech.runtime;

import com.opentypeless.android.speech.core.SegmentJoin;
import java.util.Locale;

/** Script-aware boundary spacing for independently decoded speech segments. */
public final class SegmentJoinPolicy {
    private SegmentJoinPolicy() {}

    public static SegmentJoin choose(String previousText, String nextText, String languageTag) {
        if (previousText == null || previousText.isBlank()) return SegmentJoin.NONE;
        String next = nextText == null ? "" : nextText.stripLeading();
        int previous = lastVisibleCodePoint(previousText);
        int first = next.isEmpty() ? -1 : next.codePointAt(0);
        if (previous >= 0 && first >= 0) {
            if (requiresLatinSpacing(previous) && requiresLatinSpacing(first)) {
                return SegmentJoin.SPACE;
            }
            if (isHan(previous) || isHan(first)) return SegmentJoin.NONE;
        }
        String language = languageTag == null ? "" : languageTag.toLowerCase(Locale.ROOT);
        return language.startsWith("en") ? SegmentJoin.SPACE : SegmentJoin.NONE;
    }

    private static int lastVisibleCodePoint(String value) {
        for (int offset = value.length(); offset > 0;) {
            int codePoint = value.codePointBefore(offset);
            offset -= Character.charCount(codePoint);
            if (!Character.isWhitespace(codePoint)) return codePoint;
        }
        return -1;
    }

    private static boolean requiresLatinSpacing(int codePoint) {
        return Character.isLetterOrDigit(codePoint) && !isHan(codePoint);
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }
}
