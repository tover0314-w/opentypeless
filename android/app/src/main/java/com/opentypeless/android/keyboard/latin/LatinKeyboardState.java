package com.opentypeless.android.keyboard.latin;

/** Pure state machine for the KBD-002/KBD-003 ASCII and symbol layers. */
public final class LatinKeyboardState {
    public static final long CAPS_DOUBLE_TAP_MILLIS = 400L;

    public enum ShiftMode {
        LOWER,
        SHIFTED,
        CAPS_LOCKED
    }

    public enum Layer {
        LETTERS,
        SYMBOLS_PRIMARY,
        SYMBOLS_SECONDARY
    }

    private ShiftMode shiftMode = ShiftMode.LOWER;
    private Layer layer = Layer.LETTERS;
    private long shiftedAtMillis = -1L;

    public synchronized ShiftMode shiftMode() {
        return shiftMode;
    }

    public synchronized boolean uppercase() {
        return shiftMode != ShiftMode.LOWER;
    }

    public synchronized Layer layer() {
        return layer;
    }

    public synchronized void resetToLetters() {
        layer = Layer.LETTERS;
        resetShift();
    }

    public synchronized Layer pressSymbolsToggle() {
        layer = layer == Layer.LETTERS ? Layer.SYMBOLS_PRIMARY : Layer.LETTERS;
        resetShift();
        return layer;
    }

    public synchronized Layer pressSymbolPage() {
        if (layer == Layer.LETTERS) {
            throw new IllegalStateException("symbol page is unavailable on the letter layer");
        }
        layer = layer == Layer.SYMBOLS_PRIMARY
                ? Layer.SYMBOLS_SECONDARY
                : Layer.SYMBOLS_PRIMARY;
        return layer;
    }

    public synchronized ShiftMode pressShift(long uptimeMillis) {
        if (uptimeMillis < 0L) {
            throw new IllegalArgumentException("uptimeMillis must be non-negative");
        }
        if (shiftMode == ShiftMode.CAPS_LOCKED) {
            resetShift();
            return shiftMode;
        }
        if (shiftMode == ShiftMode.SHIFTED) {
            boolean doubleTap = shiftedAtMillis >= 0L
                    && uptimeMillis >= shiftedAtMillis
                    && uptimeMillis - shiftedAtMillis <= CAPS_DOUBLE_TAP_MILLIS;
            shiftMode = doubleTap ? ShiftMode.CAPS_LOCKED : ShiftMode.LOWER;
            shiftedAtMillis = -1L;
            return shiftMode;
        }
        shiftMode = ShiftMode.SHIFTED;
        shiftedAtMillis = uptimeMillis;
        return shiftMode;
    }

    public synchronized String consumeLetter(char asciiLetter) {
        if (asciiLetter < 'a' || asciiLetter > 'z') {
            throw new IllegalArgumentException("letter must be lowercase ASCII");
        }
        char output = uppercase() ? Character.toUpperCase(asciiLetter) : asciiLetter;
        if (shiftMode == ShiftMode.SHIFTED) {
            shiftMode = ShiftMode.LOWER;
            shiftedAtMillis = -1L;
        }
        return Character.toString(output);
    }

    public synchronized String displayLetter(char asciiLetter) {
        if (asciiLetter < 'a' || asciiLetter > 'z') {
            throw new IllegalArgumentException("letter must be lowercase ASCII");
        }
        return Character.toString(uppercase()
                ? Character.toUpperCase(asciiLetter)
                : asciiLetter);
    }

    private void resetShift() {
        shiftMode = ShiftMode.LOWER;
        shiftedAtMillis = -1L;
    }
}
