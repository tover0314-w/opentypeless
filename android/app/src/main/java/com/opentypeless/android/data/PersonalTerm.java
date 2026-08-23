package com.opentypeless.android.data;

import java.util.ArrayList;
import java.util.List;

public record PersonalTerm(
        long id,
        String canonical,
        String pronunciation,
        String aliases,
        String appScope,
        int useCount,
        boolean enabled) {

    public static final int MAX_ALIASES = 16;

    public List<String> aliasList() {
        List<String> values = new ArrayList<>();
        if (aliases == null || aliases.isBlank()) return values;
        for (String value : aliases.split("[,，;；\\n]")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty() && !trimmed.equals(canonical) && !values.contains(trimmed)) {
                values.add(trimmed);
                // Legacy databases may contain values written before aliases were bounded. Keep
                // recognition work deterministic even when such a row is encountered.
                if (values.size() == MAX_ALIASES) break;
            }
        }
        return List.copyOf(values);
    }
}
