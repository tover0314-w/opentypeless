package com.opentypeless.android.keyboard.clipboard;

import com.opentypeless.android.editor.EditorOperation;
import com.opentypeless.android.editor.EditorSessionLimits;
import java.util.Objects;

/** Immutable, bounded current-clipboard snapshot with content-free diagnostics. */
public final class ClipboardPanelSnapshot {
    public enum State { TEXT, EMPTY, UNSUPPORTED, TOO_LARGE, UNAVAILABLE }

    public static final int MAX_TEXT_CODE_POINTS = EditorOperation.MAX_TEXT_CODE_POINTS;
    public static final int DEFAULT_PREVIEW_CODE_POINTS = 180;

    private final State state;
    private final String text;

    private ClipboardPanelSnapshot(State state, String text) {
        this.state = Objects.requireNonNull(state, "state");
        this.text = Objects.requireNonNull(text, "text");
        if ((state == State.TEXT) != !text.isEmpty()) {
            throw new IllegalArgumentException("only TEXT may retain clipboard text");
        }
    }

    public static ClipboardPanelSnapshot fromPrimaryText(CharSequence value) {
        if (value == null) return unsupported();
        final String text;
        try {
            text = value.toString();
        } catch (RuntimeException unavailable) {
            return unavailable();
        }
        if (text.isEmpty()) return empty();
        if (text.length() > MAX_TEXT_CODE_POINTS * 2) return tooLarge();
        try {
            EditorSessionLimits.requireWellFormedUtf16(text, "clipboardText");
        } catch (IllegalArgumentException invalid) {
            return unsupported();
        }
        if (text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS) return tooLarge();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            if (Character.isISOControl(codePoint)
                    && codePoint != '\t'
                    && codePoint != '\n'
                    && codePoint != '\r') {
                return unsupported();
            }
            offset += Character.charCount(codePoint);
        }
        return new ClipboardPanelSnapshot(State.TEXT, text);
    }

    public static ClipboardPanelSnapshot empty() {
        return new ClipboardPanelSnapshot(State.EMPTY, "");
    }

    public static ClipboardPanelSnapshot unsupported() {
        return new ClipboardPanelSnapshot(State.UNSUPPORTED, "");
    }

    public static ClipboardPanelSnapshot tooLarge() {
        return new ClipboardPanelSnapshot(State.TOO_LARGE, "");
    }

    public static ClipboardPanelSnapshot unavailable() {
        return new ClipboardPanelSnapshot(State.UNAVAILABLE, "");
    }

    public State state() {
        return state;
    }

    public boolean hasText() {
        return state == State.TEXT;
    }

    public String text() {
        if (!hasText()) throw new IllegalStateException("snapshot contains no text");
        return text;
    }

    public String preview() {
        return preview(DEFAULT_PREVIEW_CODE_POINTS);
    }

    public String preview(int maximumCodePoints) {
        if (!hasText()) throw new IllegalStateException("snapshot contains no text");
        if (maximumCodePoints <= 0) {
            throw new IllegalArgumentException("maximumCodePoints must be positive");
        }
        int count = text.codePointCount(0, text.length());
        if (count <= maximumCodePoints) return text;
        int end = text.offsetByCodePoints(0, maximumCodePoints);
        return text.substring(0, end) + '\u2026';
    }

    @Override
    public String toString() {
        return "ClipboardPanelSnapshot{state=" + state
                + ", textCodePoints="
                + (hasText() ? text.codePointCount(0, text.length()) : 0)
                + '}';
    }
}
