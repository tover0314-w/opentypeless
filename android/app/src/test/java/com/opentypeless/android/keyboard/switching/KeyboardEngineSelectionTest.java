package com.opentypeless.android.keyboard.switching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.Test;

public final class KeyboardEngineSelectionTest {
    @Test
    public void latinOnlyIsSafeAndCannotPretendRimeExists() {
        KeyboardEngineSelection state = KeyboardEngineSelection.latinOnly();

        assertEquals(KeyboardEngineSelection.Engine.LATIN, state.active());
        assertEquals(Set.of(KeyboardEngineSelection.Engine.LATIN), state.available());
        assertFalse(state.hasAlternative());
        KeyboardEngineSelection.CycleResult result = state.cycle();
        assertTrue(result instanceof KeyboardEngineSelection.Unavailable);
        assertSame(state, result.state());
    }

    @Test
    public void exactlyTwoRegisteredEnginesCycleWithMonotonicRevision() {
        KeyboardEngineSelection latin = KeyboardEngineSelection.of(
                KeyboardEngineSelection.Engine.LATIN,
                EnumSet.allOf(KeyboardEngineSelection.Engine.class),
                7L);

        KeyboardEngineSelection rime = latin.cycle().state();
        KeyboardEngineSelection roundTrip = rime.cycle().state();

        assertEquals(KeyboardEngineSelection.Engine.RIME, rime.active());
        assertEquals(8L, rime.revision());
        assertEquals(KeyboardEngineSelection.Engine.LATIN, roundTrip.active());
        assertEquals(9L, roundTrip.revision());
    }

    @Test
    public void availabilityRemovalFallsBackOnlyToLatin() {
        KeyboardEngineSelection rime = KeyboardEngineSelection.of(
                KeyboardEngineSelection.Engine.RIME,
                EnumSet.allOf(KeyboardEngineSelection.Engine.class),
                3L);

        KeyboardEngineSelection latin = rime.withAvailability(
                EnumSet.of(KeyboardEngineSelection.Engine.LATIN));

        assertEquals(KeyboardEngineSelection.Engine.LATIN, latin.active());
        assertEquals(4L, latin.revision());
        assertFalse(latin.hasAlternative());
        assertThrows(IllegalArgumentException.class,
                () -> rime.withAvailability(EnumSet.of(KeyboardEngineSelection.Engine.RIME)));
    }

    @Test
    public void availableProcessPreferenceRestoresRimeWithoutPretendingItExists() {
        KeyboardEngineSelection latinOnly = KeyboardEngineSelection.latinOnly();
        KeyboardEngineSelection stillLatin = latinOnly.withAvailabilityAndPreference(
                EnumSet.of(KeyboardEngineSelection.Engine.LATIN),
                KeyboardEngineSelection.Engine.RIME);
        assertEquals(KeyboardEngineSelection.Engine.LATIN, stillLatin.active());

        KeyboardEngineSelection restored = stillLatin.withAvailabilityAndPreference(
                EnumSet.allOf(KeyboardEngineSelection.Engine.class),
                KeyboardEngineSelection.Engine.RIME);
        assertEquals(KeyboardEngineSelection.Engine.RIME, restored.active());
        assertTrue(restored.revision() > stillLatin.revision());
    }

    @Test
    public void invalidEmptyMissingActiveAndExhaustedStatesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> KeyboardEngineSelection.of(
                KeyboardEngineSelection.Engine.RIME,
                EnumSet.of(KeyboardEngineSelection.Engine.LATIN),
                1L));
        assertThrows(IllegalArgumentException.class, () -> KeyboardEngineSelection.of(
                KeyboardEngineSelection.Engine.LATIN,
                EnumSet.noneOf(KeyboardEngineSelection.Engine.class),
                1L));
        assertThrows(IllegalArgumentException.class, () -> KeyboardEngineSelection.of(
                KeyboardEngineSelection.Engine.LATIN,
                EnumSet.of(KeyboardEngineSelection.Engine.LATIN),
                0L));
        KeyboardEngineSelection exhausted = KeyboardEngineSelection.of(
                KeyboardEngineSelection.Engine.LATIN,
                EnumSet.allOf(KeyboardEngineSelection.Engine.class),
                Long.MAX_VALUE);
        assertThrows(IllegalStateException.class, exhausted::cycle);
    }

    @Test
    public void diagnosticsContainOnlyBoundedIdentityAndCounts() {
        String rendered = KeyboardEngineSelection.latinOnly().toString();

        assertTrue(rendered.contains("LATIN"));
        assertTrue(rendered.contains("availableCount=1"));
        assertFalse(rendered.contains("candidate"));
    }
}
