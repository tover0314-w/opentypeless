package com.opentypeless.android.config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.OverrideValueCodec.DbRow;
import com.opentypeless.android.config.OverrideValueCodec.FormatException;
import com.opentypeless.android.config.OverrideValueCodec.ScalarCodec;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class OverrideValueCodecTest {
    private static final OverrideValueCodec<String> STRINGS =
            new OverrideValueCodec<>(new ScalarCodec<>() {
                @Override
                public String encode(String value) {
                    return value;
                }

                @Override
                public String decode(String encodedValue) {
                    return encodedValue;
                }
            });

    private static final OverrideValueCodec<Boolean> BOOLEANS =
            new OverrideValueCodec<>(new ScalarCodec<>() {
                @Override
                public String encode(Boolean value) {
                    return value ? "true" : "false";
                }

                @Override
                public Boolean decode(String encodedValue) {
                    return switch (encodedValue) {
                        case "true" -> true;
                        case "false" -> false;
                        default -> throw new IllegalArgumentException("invalid boolean");
                    };
                }
            });

    @Test
    public void codecFamilyHasExactContentFreePersistenceShape() {
        assertTrue(Modifier.isFinal(OverrideValueCodec.class.getModifiers()));
        assertTrue(DbRow.class.isRecord());
        assertArrayEquals(
                new String[]{"formatVersion", "state", "valuePresent", "encodedValue"},
                java.util.Arrays.stream(DbRow.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toArray(String[]::new));
        assertArrayEquals(
                new Class<?>[]{int.class, String.class, boolean.class, String.class},
                java.util.Arrays.stream(DbRow.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getType)
                        .toArray(Class<?>[]::new));
        assertTrue(Modifier.isFinal(FormatException.class.getModifiers()));
        assertTrue(IllegalArgumentException.class.isAssignableFrom(FormatException.class));
        for (Class<?> type : new Class<?>[]{
                OverrideValueCodec.class,
                DbRow.class,
                ScalarCodec.class}) {
            assertFalse(Serializable.class.isAssignableFrom(type));
        }
    }

    @Test
    public void canonicalJsonPreservesAllThreeStatesEmptyAndFalse() {
        assertEquals("[1,\"inherit\",false]", STRINGS.toJson(OverrideValue.inherit()));
        assertEquals("[1,\"disabled\",false]", STRINGS.toJson(OverrideValue.disabled()));
        assertEquals("[1,\"value\",true,\"\"]", STRINGS.toJson(OverrideValue.value("")));
        assertEquals(
                "[1,\"value\",true,\"false\"]",
                BOOLEANS.toJson(OverrideValue.value(false)));

        assertEquals(OverrideValue.inherit(), STRINGS.fromJson("[1,\"inherit\",false]"));
        assertEquals(OverrideValue.disabled(), STRINGS.fromJson("[1,\"disabled\",false]"));
        assertEquals(OverrideValue.value(""), STRINGS.fromJson("[1,\"value\",true,\"\"]"));
        assertEquals(
                OverrideValue.value(false),
                BOOLEANS.fromJson("[1,\"value\",true,\"false\"]"));
    }

    @Test
    public void exactDbRowsPreserveAllThreeStatesEmptyAndFalse() {
        assertEquals(
                new DbRow(1, "inherit", false, null),
                STRINGS.toDbRow(OverrideValue.inherit()));
        assertEquals(
                new DbRow(1, "disabled", false, null),
                STRINGS.toDbRow(OverrideValue.disabled()));
        assertEquals(
                new DbRow(1, "value", true, ""),
                STRINGS.toDbRow(OverrideValue.value("")));
        assertEquals(
                new DbRow(1, "value", true, "false"),
                BOOLEANS.toDbRow(OverrideValue.value(false)));

        assertEquals(
                OverrideValue.inherit(),
                STRINGS.fromDbRow(new DbRow(1, "inherit", false, null)));
        assertEquals(
                OverrideValue.disabled(),
                STRINGS.fromDbRow(new DbRow(1, "disabled", false, null)));
        assertEquals(
                OverrideValue.value(""),
                STRINGS.fromDbRow(new DbRow(1, "value", true, "")));
        assertEquals(
                OverrideValue.value(false),
                BOOLEANS.fromDbRow(new DbRow(1, "value", true, "false")));
    }

    @Test
    public void jsonAndDbRepresentationsRoundTripAcrossEachOther() {
        for (OverrideValue<String> value : java.util.List.of(
                OverrideValue.<String>inherit(),
                OverrideValue.<String>disabled(),
                OverrideValue.value(""),
                OverrideValue.value("cfg003-😀"))) {
            OverrideValue<String> fromJson = STRINGS.fromJson(STRINGS.toJson(value));
            OverrideValue<String> fromDb = STRINGS.fromDbRow(STRINGS.toDbRow(fromJson));
            assertEquals(value, fromDb);
            assertEquals(STRINGS.toJson(value), STRINGS.toJson(fromDb));
        }
    }

    @Test
    public void jsonRejectsUnknownCoercedMissingExtraAndTrailingInputs() {
        for (String invalid : new String[]{
                "[]",
                "[1,\"inherit\"]",
                "[1,\"inherit\",false,\"extra\"]",
                "[1,\"value\",true]",
                "[1,\"value\",false,\"x\"]",
                "[1,\"disabled\",true,\"x\"]",
                "[2,\"inherit\",false]",
                "[1.0,\"inherit\",false]",
                "[1,\"unknown\",false]",
                "[1,true,false]",
                "[1,\"inherit\",0]",
                "[1,\"value\",true,false]",
                "[1,\"value\",true,null]",
                "[1,\"value\",true,[\"nested\"]]",
                "[1,\"inherit\",false] trailing",
                "{\"format\":1}",
                "not-json"}) {
            assertThrows(FormatException.class, () -> STRINGS.fromJson(invalid));
        }
        assertEquals(
                OverrideValue.inherit(),
                STRINGS.fromJson("  [1,\"inherit\",false] \n"));
    }

    @Test
    public void dbRowsRejectUnknownVersionStateAndPresenceContradictions() {
        assertThrows(FormatException.class, () -> new DbRow(0, "inherit", false, null));
        assertThrows(FormatException.class, () -> new DbRow(2, "inherit", false, null));
        assertThrows(FormatException.class, () -> new DbRow(1, null, false, null));
        assertThrows(FormatException.class, () -> new DbRow(1, "INHERIT", false, null));
        assertThrows(FormatException.class, () -> new DbRow(1, "unknown", false, null));
        assertThrows(FormatException.class, () -> new DbRow(1, "inherit", true, ""));
        assertThrows(FormatException.class, () -> new DbRow(1, "inherit", false, ""));
        assertThrows(FormatException.class, () -> new DbRow(1, "disabled", true, "false"));
        assertThrows(FormatException.class, () -> new DbRow(1, "value", false, null));
        assertThrows(FormatException.class, () -> new DbRow(1, "value", true, null));
    }

    @Test
    public void scalarAndJsonBoundsAreExactAndUnicodeMustBeWellFormed() {
        String maximum = "😀".repeat(
                OverrideValueCodec.MAX_ENCODED_VALUE_UTF16_UNITS / 2);
        assertEquals(
                OverrideValue.value(maximum),
                STRINGS.fromJson(STRINGS.toJson(OverrideValue.value(maximum))));

        String escapedMaximum = "\u0000".repeat(
                OverrideValueCodec.MAX_ENCODED_VALUE_UTF16_UNITS);
        assertEquals(
                OverrideValue.value(escapedMaximum),
                STRINGS.fromJson(STRINGS.toJson(OverrideValue.value(escapedMaximum))));

        assertThrows(
                FormatException.class,
                () -> STRINGS.toDbRow(OverrideValue.value(
                        "a".repeat(OverrideValueCodec.MAX_ENCODED_VALUE_UTF16_UNITS + 1))));
        assertThrows(
                FormatException.class,
                () -> STRINGS.fromJson(
                        " ".repeat(OverrideValueCodec.MAX_JSON_UTF16_UNITS + 1)));
        assertThrows(
                FormatException.class,
                () -> STRINGS.toDbRow(OverrideValue.value("\uD800")));
        assertThrows(
                FormatException.class,
                () -> new DbRow(1, "value", true, "\uDC00"));
        assertThrows(
                FormatException.class,
                () -> STRINGS.fromJson("[1,\"value\",true,\"\uD800\"]"));
    }

    @Test
    public void scalarAdapterRunsOnlyForValueAndFailuresRemainRedacted() {
        AtomicInteger encodeCalls = new AtomicInteger();
        AtomicInteger decodeCalls = new AtomicInteger();
        String secret = "cfg003-adapter-secret";
        OverrideValueCodec<String> hostile = new OverrideValueCodec<>(new ScalarCodec<>() {
            @Override
            public String encode(String value) {
                encodeCalls.incrementAndGet();
                throw new IllegalStateException(secret);
            }

            @Override
            public String decode(String encodedValue) {
                decodeCalls.incrementAndGet();
                throw new IllegalStateException(secret);
            }
        });

        hostile.toJson(OverrideValue.inherit());
        hostile.toDbRow(OverrideValue.disabled());
        hostile.fromJson("[1,\"inherit\",false]");
        hostile.fromDbRow(new DbRow(1, "disabled", false, null));
        assertEquals(0, encodeCalls.get());
        assertEquals(0, decodeCalls.get());

        FormatException encodeFailure = assertThrows(
                FormatException.class,
                () -> hostile.toJson(OverrideValue.value(secret)));
        FormatException decodeFailure = assertThrows(
                FormatException.class,
                () -> hostile.fromJson("[1,\"value\",true,\"opaque\"]"));
        assertEquals(1, encodeCalls.get());
        assertEquals(1, decodeCalls.get());
        assertFalse(encodeFailure.toString().contains(secret));
        assertFalse(decodeFailure.toString().contains(secret));
        assertEquals(null, encodeFailure.getCause());
        assertEquals(null, decodeFailure.getCause());

        OverrideValueCodec<String> nullCodec = new OverrideValueCodec<>(new ScalarCodec<>() {
            @Override public String encode(String value) { return null; }

            @Override public String decode(String encodedValue) { return null; }
        });
        assertThrows(
                FormatException.class,
                () -> nullCodec.toJson(OverrideValue.value("value")));
        assertThrows(
                FormatException.class,
                () -> nullCodec.fromJson("[1,\"value\",true,\"value\"]"));
    }

    @Test
    public void diagnosticsNeverExposeScalarOrAdapterIdentity() {
        String secret = "cfg003-diagnostic-secret";
        DbRow row = STRINGS.toDbRow(OverrideValue.value(secret));
        assertFalse(row.toString().contains(secret));
        assertFalse(STRINGS.toString().contains(STRINGS.getClass().getName() + "@"));
        assertEquals("OverrideValueCodec{scalarCodec=<redacted>}", STRINGS.toString());
        assertTrue(row.toString().contains("encodedValue=<redacted>"));
    }
}
