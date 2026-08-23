package com.opentypeless.android.config;

import com.opentypeless.android.config.EffectiveProfile.ResolutionExplanation;
import com.opentypeless.android.config.EffectiveProfile.ResolvedValue;
import com.opentypeless.android.config.EffectiveProfile.RuleSource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable UI projection of one already-resolved effective profile. */
public final class RuleExplanationModel {
    private static final List<RuleSource> PRECEDENCE = List.of(
            RuleSource.HARD_SAFETY,
            RuleSource.SESSION,
            RuleSource.FIELD,
            RuleSource.APPLICATION,
            RuleSource.GLOBAL,
            RuleSource.PROVIDER_DEFAULT);

    private final List<Item> items;
    private final Map<Feature, Item> byFeature;

    private RuleExplanationModel(List<Item> items) {
        this.items = List.copyOf(items);
        EnumMap<Feature, Item> index = new EnumMap<>(Feature.class);
        for (Item item : this.items) {
            Item present = Objects.requireNonNull(item, "item");
            if (index.put(present.feature(), present) != null) {
                throw new IllegalArgumentException("duplicate explanation feature");
            }
        }
        if (index.size() != Feature.values().length) {
            throw new IllegalArgumentException("explanation model is incomplete");
        }
        byFeature = Map.copyOf(index);
    }

    /** Projects resolver output without reading rules or recomputing precedence. */
    public static RuleExplanationModel from(EffectiveProfile profile) {
        EffectiveProfile resolved = Objects.requireNonNull(profile, "profile");
        return new RuleExplanationModel(List.of(
                identifier(Feature.KEYBOARD_LAYOUT, resolved.keyboardLayoutId()),
                identifier(Feature.VOICE_ROUTE, resolved.voiceRouteId()),
                processing(resolved.processingMode()),
                bool(Feature.SEND_CONTEXT, resolved.sendContext()),
                bool(Feature.HISTORY, resolved.historyEnabled()),
                identifier(Feature.ACTION_SET, resolved.actionSetId())));
    }

    public List<Item> items() {
        return items;
    }

    public Item item(Feature feature) {
        return byFeature.get(Objects.requireNonNull(feature, "feature"));
    }

    /** Full resolver precedence vocabulary for presentation only; selected source is on each item. */
    public static List<RuleSource> precedence() {
        return PRECEDENCE;
    }

    @Override
    public String toString() {
        return "RuleExplanationModel{values=<redacted>, itemCount=" + items.size() + "}";
    }

    private static Item identifier(Feature feature, ResolvedValue<String> resolved) {
        return item(feature, resolved, value -> new DisplayValue.Identifier((String) value));
    }

    private static Item processing(ResolvedValue<ProcessingMode> resolved) {
        return item(
                Feature.PROCESSING_MODE,
                resolved,
                value -> new DisplayValue.Processing((ProcessingMode) value));
    }

    private static Item bool(Feature feature, ResolvedValue<Boolean> resolved) {
        return item(feature, resolved, value -> new DisplayValue.BooleanValue((Boolean) value));
    }

    private static <T> Item item(
            Feature feature,
            ResolvedValue<T> resolved,
            ExplicitValueFactory factory) {
        ResolvedValue<T> terminal = Objects.requireNonNull(resolved, "resolved");
        DisplayValue value;
        if (terminal.value() instanceof OverrideValue.Disabled<?>) {
            value = DisplayValue.Disabled.INSTANCE;
        } else if (terminal.value() instanceof OverrideValue.Value<?> explicit) {
            value = factory.create(explicit.value());
        } else {
            throw new IllegalArgumentException("resolved value is not terminal");
        }
        return new Item(feature, value, terminal.source(), terminal.explanation());
    }

    @FunctionalInterface
    private interface ExplicitValueFactory {
        DisplayValue create(Object value);
    }

    /** Stable UI row order and localization key. */
    public enum Feature {
        KEYBOARD_LAYOUT,
        VOICE_ROUTE,
        PROCESSING_MODE,
        SEND_CONTEXT,
        HISTORY,
        ACTION_SET
    }

    /** Typed terminal display value; no empty-string or null sentinel is used for Disabled. */
    public sealed interface DisplayValue permits DisplayValue.Disabled,
            DisplayValue.Identifier, DisplayValue.Processing, DisplayValue.BooleanValue {
        enum Disabled implements DisplayValue {
            INSTANCE;

            @Override
            public String toString() {
                return "DisplayValue.Disabled";
            }
        }

        record Identifier(String value) implements DisplayValue {
            public Identifier {
                value = RuleOverrides.requireConfigId(value, "display identifier");
            }

            @Override
            public String toString() {
                return "DisplayValue.Identifier{value=<redacted>}";
            }
        }

        record Processing(ProcessingMode value) implements DisplayValue {
            public Processing {
                value = Objects.requireNonNull(value, "value");
            }

            @Override
            public String toString() {
                return "DisplayValue.Processing{value=<redacted>}";
            }
        }

        record BooleanValue(boolean value) implements DisplayValue {
            @Override
            public String toString() {
                return "DisplayValue.BooleanValue{value=<redacted>}";
            }
        }
    }

    /** One terminal effective value plus the exact resolver source and explanation. */
    public record Item(
            Feature feature,
            DisplayValue value,
            RuleSource source,
            ResolutionExplanation explanation) {
        public Item {
            feature = Objects.requireNonNull(feature, "feature");
            value = Objects.requireNonNull(value, "value");
            source = Objects.requireNonNull(source, "source");
            explanation = Objects.requireNonNull(explanation, "explanation");
            boolean disabled = value instanceof DisplayValue.Disabled;
            boolean valid;
            if (explanation == ResolutionExplanation.HARD_SENSITIVE_FIELD) {
                valid = source == RuleSource.HARD_SAFETY;
            } else if (explanation == ResolutionExplanation.REQUIRED_GLOBAL_VALUE) {
                valid = source == RuleSource.GLOBAL && !disabled;
            } else if (explanation == ResolutionExplanation.EXPLICIT_VALUE) {
                valid = source != RuleSource.HARD_SAFETY && !disabled;
            } else {
                valid = source != RuleSource.HARD_SAFETY && disabled;
            }
            if (!valid) {
                throw new IllegalArgumentException("explanation item is inconsistent");
            }
            if (feature == Feature.KEYBOARD_LAYOUT
                    && (source != RuleSource.GLOBAL
                            || explanation != ResolutionExplanation.REQUIRED_GLOBAL_VALUE)) {
                throw new IllegalArgumentException("keyboard explanation must be global");
            }
        }

        @Override
        public String toString() {
            return "RuleExplanationItem{feature=" + feature
                    + ", value=<redacted>, source=" + source
                    + ", explanation=" + explanation + "}";
        }
    }
}
