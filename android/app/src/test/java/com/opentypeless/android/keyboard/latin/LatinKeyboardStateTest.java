package com.opentypeless.android.keyboard.latin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LatinKeyboardStateTest {
    @Test
    public void lowercaseIsTheDefaultAndDoesNotChangeAfterLetters() {
        LatinKeyboardState state = new LatinKeyboardState();

        assertFalse(state.uppercase());
        assertEquals("a", state.consumeLetter('a'));
        assertEquals("z", state.consumeLetter('z'));
        assertEquals(LatinKeyboardState.ShiftMode.LOWER, state.shiftMode());
    }

    @Test
    public void singleShiftUppercasesExactlyOneLetter() {
        LatinKeyboardState state = new LatinKeyboardState();

        assertEquals(LatinKeyboardState.ShiftMode.SHIFTED, state.pressShift(1_000L));
        assertEquals("A", state.displayLetter('a'));
        assertEquals("B", state.consumeLetter('b'));
        assertEquals(LatinKeyboardState.ShiftMode.LOWER, state.shiftMode());
        assertEquals("c", state.consumeLetter('c'));
    }

    @Test
    public void doubleShiftLocksCapsUntilShiftIsPressedAgain() {
        LatinKeyboardState state = new LatinKeyboardState();

        state.pressShift(1_000L);
        assertEquals(LatinKeyboardState.ShiftMode.CAPS_LOCKED, state.pressShift(1_399L));
        assertEquals("A", state.consumeLetter('a'));
        assertEquals("B", state.consumeLetter('b'));
        assertEquals(LatinKeyboardState.ShiftMode.CAPS_LOCKED, state.shiftMode());
        assertEquals(LatinKeyboardState.ShiftMode.LOWER, state.pressShift(1_500L));
    }

    @Test
    public void lateOrRegressingSecondTapCannotLockCaps() {
        LatinKeyboardState late = new LatinKeyboardState();
        late.pressShift(1_000L);
        assertEquals(LatinKeyboardState.ShiftMode.LOWER, late.pressShift(1_401L));

        LatinKeyboardState regressing = new LatinKeyboardState();
        regressing.pressShift(1_000L);
        assertEquals(LatinKeyboardState.ShiftMode.LOWER, regressing.pressShift(999L));
    }

    @Test
    public void invalidLetterAndNegativeClockFailClosed() {
        LatinKeyboardState state = new LatinKeyboardState();

        assertThrows(IllegalArgumentException.class, () -> state.consumeLetter('A'));
        assertThrows(IllegalArgumentException.class, () -> state.displayLetter('1'));
        assertThrows(IllegalArgumentException.class, () -> state.pressShift(-1L));
    }

    @Test
    public void symbolsToggleStartsOnPrimaryAndReturnsToLowercaseLetters() {
        LatinKeyboardState state = new LatinKeyboardState();
        state.pressShift(1_000L);

        assertEquals(
                LatinKeyboardState.Layer.SYMBOLS_PRIMARY,
                state.pressSymbolsToggle());
        assertEquals(LatinKeyboardState.ShiftMode.LOWER, state.shiftMode());
        assertEquals(LatinKeyboardState.Layer.LETTERS, state.pressSymbolsToggle());
        assertEquals("a", state.displayLetter('a'));
    }

    @Test
    public void symbolPageCyclesOnlyWhileSymbolsAreOpen() {
        LatinKeyboardState state = new LatinKeyboardState();

        assertThrows(IllegalStateException.class, state::pressSymbolPage);
        state.pressSymbolsToggle();
        assertEquals(
                LatinKeyboardState.Layer.SYMBOLS_SECONDARY,
                state.pressSymbolPage());
        assertEquals(
                LatinKeyboardState.Layer.SYMBOLS_PRIMARY,
                state.pressSymbolPage());
    }
}
