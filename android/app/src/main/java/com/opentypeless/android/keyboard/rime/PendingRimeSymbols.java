package com.opentypeless.android.keyboard.rime;

import java.util.Objects;

/** Bounded, transient symbols queued behind one exact Rime candidate commit. */
public final class PendingRimeSymbols {
    public static final int MAXIMUM_SYMBOLS = 8;

    private final StringBuilder value = new StringBuilder();

    public boolean offer(String symbol) {
        if (!isSingleSafeSymbol(symbol) || count() >= MAXIMUM_SYMBOLS) return false;
        value.append(symbol);
        return true;
    }

    public boolean isEmpty() {
        return value.length() == 0;
    }

    public int count() {
        return value.codePointCount(0, value.length());
    }

    public String text() {
        return value.toString();
    }

    public String appendTo(String committedText) {
        return Objects.requireNonNull(committedText, "committedText") + value;
    }

    public void clear() {
        value.setLength(0);
    }

    public static boolean isSingleSafeSymbol(String text) {
        if (text == null || text.codePointCount(0, text.length()) != 1) return false;
        int codePoint = text.codePointAt(0);
        if (codePoint >= 'a' && codePoint <= 'z') return false;
        if (codePoint == ' ' || Character.isISOControl(codePoint)) return false;
        try {
            RimeInputEngine.Key.printable(codePoint);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static String normalize(String symbol, boolean asciiPunctuation) {
        if (!isSingleSafeSymbol(symbol)) {
            throw new IllegalArgumentException("Rime symbol must be one safe non-letter scalar");
        }
        if (asciiPunctuation) return symbol;
        return switch (symbol) {
            case "," -> "，";
            case "." -> "。";
            default -> symbol;
        };
    }

    @Override
    public String toString() {
        return "PendingRimeSymbols{count=" + count() + ", <redacted>}";
    }
}
