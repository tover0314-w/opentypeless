package com.opentypeless.android.config;

import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONTokener;

/**
 * Versioned, bounded JSON/DB encoding seam for one {@link OverrideValue} scalar.
 *
 * <p>The codec performs no I/O and owns no database, file, Android, network, or secret
 * capability. A future configuration repository supplies an audited type-specific scalar codec
 * and maps {@link DbRow} to its versioned schema.
 */
public final class OverrideValueCodec<T> {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_JSON_UTF16_UNITS = 32_768;
    public static final int MAX_ENCODED_VALUE_UTF16_UNITS = 4_096;

    private static final String STATE_INHERIT = "inherit";
    private static final String STATE_DISABLED = "disabled";
    private static final String STATE_VALUE = "value";

    private final ScalarCodec<T> scalarCodec;

    public OverrideValueCodec(ScalarCodec<T> scalarCodec) {
        this.scalarCodec = Objects.requireNonNull(scalarCodec, "scalarCodec");
    }

    /** Encodes the exact state into the four scalar columns expected by a future DB adapter. */
    public DbRow toDbRow(OverrideValue<T> value) {
        OverrideValue<T> safe = Objects.requireNonNull(value, "value");
        if (safe instanceof OverrideValue.Inherit<?>) {
            return new DbRow(FORMAT_VERSION, STATE_INHERIT, false, null);
        }
        if (safe instanceof OverrideValue.Disabled<?>) {
            return new DbRow(FORMAT_VERSION, STATE_DISABLED, false, null);
        }
        if (safe instanceof OverrideValue.Value<?> explicit) {
            @SuppressWarnings("unchecked")
            T typed = (T) explicit.value();
            return new DbRow(
                    FORMAT_VERSION,
                    STATE_VALUE,
                    true,
                    encodeScalar(typed));
        }
        throw new FormatException("unsupported override state");
    }

    /** Decodes an already bounded DB row; unknown or contradictory rows fail closed. */
    public OverrideValue<T> fromDbRow(DbRow row) {
        DbRow safe = Objects.requireNonNull(row, "row");
        return switch (safe.state()) {
            case STATE_INHERIT -> OverrideValue.inherit();
            case STATE_DISABLED -> OverrideValue.disabled();
            case STATE_VALUE -> OverrideValue.value(decodeScalar(safe.encodedValue()));
            default -> throw new FormatException("unsupported override state");
        };
    }

    /** Returns the canonical exact-array JSON representation. */
    public String toJson(OverrideValue<T> value) {
        DbRow row = toDbRow(value);
        JSONArray array = new JSONArray()
                .put(row.formatVersion())
                .put(row.state())
                .put(row.valuePresent());
        if (row.valuePresent()) array.put(row.encodedValue());
        String json = array.toString();
        if (json.length() > MAX_JSON_UTF16_UNITS) {
            throw new FormatException("encoded override JSON exceeds its bound");
        }
        return json;
    }

    /** Parses only version 1 exact-array JSON without type coercion or ignored trailing data. */
    public OverrideValue<T> fromJson(String json) {
        String safe = requireWellFormedBounded(
                json,
                MAX_JSON_UTF16_UNITS,
                "override JSON is invalid");
        try {
            JSONTokener tokener = new JSONTokener(safe);
            Object root = tokener.nextValue();
            if (!(root instanceof JSONArray array) || tokener.nextClean() != 0) {
                throw new FormatException("override JSON must contain one exact array");
            }
            if (array.length() != 3 && array.length() != 4) {
                throw new FormatException("override JSON has an invalid item count");
            }
            Object version = array.get(0);
            Object state = array.get(1);
            Object present = array.get(2);
            if (!(version instanceof Integer)
                    || !(state instanceof String)
                    || !(present instanceof Boolean)) {
                throw new FormatException("override JSON item types are invalid");
            }
            boolean valuePresent = (Boolean) present;
            Object encoded = array.length() == 4 ? array.get(3) : null;
            if (encoded != null && !(encoded instanceof String)) {
                throw new FormatException("override JSON scalar type is invalid");
            }
            return fromDbRow(new DbRow(
                    (Integer) version,
                    (String) state,
                    valuePresent,
                    (String) encoded));
        } catch (JSONException | StackOverflowError error) {
            throw new FormatException("override JSON is malformed");
        }
    }

    @Override
    public String toString() {
        return "OverrideValueCodec{scalarCodec=<redacted>}";
    }

    private String encodeScalar(T value) {
        try {
            return requireWellFormedBounded(
                    scalarCodec.encode(Objects.requireNonNull(value, "value")),
                    MAX_ENCODED_VALUE_UTF16_UNITS,
                    "encoded override scalar is invalid");
        } catch (FormatException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new FormatException("override scalar encoder failed");
        }
    }

    private T decodeScalar(String encodedValue) {
        String safe = requireWellFormedBounded(
                encodedValue,
                MAX_ENCODED_VALUE_UTF16_UNITS,
                "encoded override scalar is invalid");
        try {
            T decoded = scalarCodec.decode(safe);
            if (decoded == null) {
                throw new FormatException("override scalar decoder returned null");
            }
            return decoded;
        } catch (FormatException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new FormatException("override scalar decoder failed");
        }
    }

    private static String requireWellFormedBounded(String value, int limit, String errorMessage) {
        if (value == null || value.length() > limit) {
            throw new FormatException(errorMessage);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new FormatException(errorMessage);
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new FormatException(errorMessage);
            }
        }
        return value;
    }

    /** Type-specific, deterministic scalar conversion owned by a future config repository. */
    public interface ScalarCodec<T> {
        String encode(T value);

        T decode(String encodedValue);
    }

    /** Exact four-column DB representation; no null/empty sentinel is interpreted implicitly. */
    public record DbRow(
            int formatVersion,
            String state,
            boolean valuePresent,
            String encodedValue) {
        public DbRow {
            if (formatVersion != FORMAT_VERSION
                    || !(STATE_INHERIT.equals(state)
                            || STATE_DISABLED.equals(state)
                            || STATE_VALUE.equals(state))) {
                throw new FormatException("override DB row version or state is invalid");
            }
            boolean valueState = STATE_VALUE.equals(state);
            if (valuePresent != valueState
                    || (valueState ? encodedValue == null : encodedValue != null)) {
                throw new FormatException("override DB row presence is contradictory");
            }
            if (encodedValue != null) {
                encodedValue = requireWellFormedBounded(
                        encodedValue,
                        MAX_ENCODED_VALUE_UTF16_UNITS,
                        "encoded override scalar is invalid");
            }
        }

        @Override
        public String toString() {
            return "DbRow{formatVersion=" + formatVersion
                    + ", state=" + state
                    + ", valuePresent=" + valuePresent
                    + ", encodedValue=<redacted>}";
        }
    }

    /** Stable content-free classification for malformed persisted overrides. */
    public static final class FormatException extends IllegalArgumentException {
        private FormatException(String message) {
            super(message);
        }
    }
}
