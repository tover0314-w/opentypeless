package com.opentypeless.android.keyboard.emoji;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable, bounded MRU of catalog identifiers; it never stores editor or application context. */
public final class EmojiRecents {
    public static final int MAX_ENTRIES = 21;

    private final List<String> entries;

    private EmojiRecents(List<String> entries) {
        this.entries = List.copyOf(entries);
    }

    public static EmojiRecents empty() {
        return new EmojiRecents(List.of());
    }

    public static EmojiRecents fromStored(List<String> stored) {
        Objects.requireNonNull(stored, "stored");
        LinkedHashSet<String> accepted = new LinkedHashSet<>();
        for (String emoji : stored) {
            if (EmojiCatalog.contains(emoji)) accepted.add(emoji);
            if (accepted.size() == MAX_ENTRIES) break;
        }
        return new EmojiRecents(List.copyOf(accepted));
    }

    public EmojiRecents record(String emoji) {
        if (!EmojiCatalog.contains(emoji)) {
            throw new IllegalArgumentException("emoji is outside the pinned catalog");
        }
        ArrayList<String> next = new ArrayList<>(MAX_ENTRIES);
        next.add(emoji);
        for (String entry : entries) {
            if (!emoji.equals(entry)) next.add(entry);
            if (next.size() == MAX_ENTRIES) break;
        }
        return new EmojiRecents(next);
    }

    public List<String> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public String toString() {
        return "EmojiRecents{count=" + entries.size() + '}';
    }
}
