package com.opentypeless.android.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Bounded, Android-free searchable view of launchable applications. */
public final class AppPickerModel {
    public static final int MAX_ENTRIES = 2_048;
    public static final int MAX_LABEL_CODE_POINTS = 128;
    public static final int MAX_QUERY_CODE_POINTS = 128;

    private static final Comparator<Entry> ENTRY_ORDER = Comparator
            .comparing(Entry::label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::label)
            .thenComparing(Entry::packageName);

    private final List<Entry> entries;

    public AppPickerModel(List<Entry> candidates) {
        List<Entry> safe = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (safe.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("App picker entry count exceeds its bound");
        }
        List<Entry> sorted = new ArrayList<>(safe);
        sorted.sort(ENTRY_ORDER);
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (Entry entry : sorted) {
            Entry present = Objects.requireNonNull(entry, "entry");
            unique.putIfAbsent(present.packageName(), present);
        }
        entries = List.copyOf(unique.values());
    }

    public List<Entry> entries() {
        return entries;
    }

    public List<Entry> search(String query) {
        String normalized = normalizeQuery(query);
        if (normalized.isEmpty()) return entries;
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.searchLabel().contains(normalized)
                    || entry.searchPackage().contains(normalized)) {
                matches.add(entry);
            }
        }
        return List.copyOf(matches);
    }

    @Override
    public String toString() {
        return "AppPickerModel{entries=<redacted>, count=" + entries.size() + "}";
    }

    public record Entry(String label, String packageName) {
        public Entry {
            packageName = RuleOverrides.requirePackageName(packageName);
            label = requireLabel(label, packageName);
        }

        String searchLabel() {
            return label.toLowerCase(Locale.ROOT);
        }

        String searchPackage() {
            return packageName.toLowerCase(Locale.ROOT);
        }

        @Override
        public String toString() {
            return "AppPickerEntry{label=<redacted>, packageName=<redacted>}";
        }
    }

    private static String requireLabel(String value, String fallback) {
        String safe = Objects.requireNonNull(value, "label").strip();
        if (safe.isEmpty()) safe = fallback;
        requireWellFormed(safe, "label");
        if (safe.codePointCount(0, safe.length()) > MAX_LABEL_CODE_POINTS) {
            throw new IllegalArgumentException("App label exceeds its bound");
        }
        for (int index = 0; index < safe.length(); index++) {
            if (Character.isISOControl(safe.charAt(index))) {
                throw new IllegalArgumentException("App label contains a control character");
            }
        }
        return safe;
    }

    private static String normalizeQuery(String value) {
        String safe = Objects.requireNonNull(value, "query").strip();
        requireWellFormed(safe, "query");
        if (safe.codePointCount(0, safe.length()) > MAX_QUERY_CODE_POINTS) {
            throw new IllegalArgumentException("Search query exceeds its bound");
        }
        for (int index = 0; index < safe.length(); index++) {
            if (Character.isISOControl(safe.charAt(index))) {
                throw new IllegalArgumentException("Search query contains a control character");
            }
        }
        return safe.toLowerCase(Locale.ROOT);
    }

    private static void requireWellFormed(String value, String name) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(name + " is not well-formed UTF-16");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(name + " is not well-formed UTF-16");
            }
        }
    }
}
