package com.opentypeless.android.config;

import java.util.Objects;

/**
 * One explicit configuration override: inherit, disable, or use a non-null value.
 *
 * <p>This pure value is not a resolver or persistence authority. In particular, an empty string
 * or {@code false} inside {@link Value} remains an explicit value and is never a sentinel for one
 * of the other states.
 */
public sealed interface OverrideValue<T>
        permits OverrideValue.Inherit, OverrideValue.Disabled, OverrideValue.Value {

    /** Returns the single stateless inherit value. */
    static <T> OverrideValue<T> inherit() {
        return Inherit.instance();
    }

    /** Returns the single stateless disabled value. */
    static <T> OverrideValue<T> disabled() {
        return Disabled.instance();
    }

    /** Returns an explicit non-null value, including an empty string or {@code false}. */
    static <T> OverrideValue<T> value(T value) {
        return new Value<>(value);
    }

    /** Stateless singleton representing delegation to the next lower configuration layer. */
    final class Inherit<T> implements OverrideValue<T> {
        private static final Inherit<?> INSTANCE = new Inherit<>();

        private Inherit() {}

        @SuppressWarnings("unchecked")
        private static <T> Inherit<T> instance() {
            return (Inherit<T>) INSTANCE;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Inherit<?>;
        }

        @Override
        public int hashCode() {
            return Inherit.class.hashCode();
        }

        @Override
        public String toString() {
            return "OverrideValue.Inherit";
        }
    }

    /** Stateless singleton representing an explicit disabled setting. */
    final class Disabled<T> implements OverrideValue<T> {
        private static final Disabled<?> INSTANCE = new Disabled<>();

        private Disabled() {}

        @SuppressWarnings("unchecked")
        private static <T> Disabled<T> instance() {
            return (Disabled<T>) INSTANCE;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Disabled<?>;
        }

        @Override
        public int hashCode() {
            return Disabled.class.hashCode();
        }

        @Override
        public String toString() {
            return "OverrideValue.Disabled";
        }
    }

    /** Explicit non-null setting value. */
    record Value<T>(T value) implements OverrideValue<T> {
        public Value {
            value = Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return "OverrideValue.Value{value=<redacted>}";
        }
    }
}
