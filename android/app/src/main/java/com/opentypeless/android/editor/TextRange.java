package com.opentypeless.android.editor;

/** Exact editor selection coordinates. Direction is preserved; unknown is never writable. */
public record TextRange(int start, int end) {
    public static final TextRange UNKNOWN = new TextRange(-1, -1);

    public TextRange {
        boolean unknown = start == -1 && end == -1;
        boolean known = start >= 0 && end >= 0;
        if (!unknown && !known) {
            throw new IllegalArgumentException(
                    "selection must be fully known or exactly (-1, -1)");
        }
    }

    public boolean isKnown() {
        return start >= 0;
    }

    public boolean isCollapsed() {
        return isKnown() && start == end;
    }

    public boolean hasSelection() {
        return isKnown() && start != end;
    }
}
