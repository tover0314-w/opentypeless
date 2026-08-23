package com.opentypeless.android.config;

import com.opentypeless.android.context.FieldKind;
import java.util.Objects;

/** One immutable field-kind rule within an exact application scope. */
public record FieldRule(FieldMatcher matcher, RuleOverrides overrides) {
    public FieldRule {
        matcher = Objects.requireNonNull(matcher, "matcher");
        overrides = Objects.requireNonNull(overrides, "overrides");
    }

    @Override
    public String toString() {
        return "FieldRule{matcher=" + matcher + ", overrides=<redacted>}";
    }

    /** Pure matching claim; CFG-005 owns matching order and hard-policy enforcement. */
    public record FieldMatcher(String packageName, FieldKind fieldKind) {
        public FieldMatcher {
            packageName = RuleOverrides.requirePackageName(packageName);
            fieldKind = Objects.requireNonNull(fieldKind, "fieldKind");
        }

        @Override
        public String toString() {
            return "FieldMatcher{packageName=<redacted>, fieldKind=" + fieldKind + "}";
        }
    }
}
