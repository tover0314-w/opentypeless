package com.opentypeless.android.keyboard.rime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PendingRimeSymbolsTest {
    @Test
    public void acceptsOnlyBoundedSingleSafeSymbols() {
        PendingRimeSymbols symbols = new PendingRimeSymbols();

        for (String symbol : new String[] {"1", "@", "?", "…", "中", "A", "\uD83D\uDE42"}) {
            assertTrue(symbols.offer(symbol));
        }
        assertFalse(symbols.offer("a"));
        assertFalse(symbols.offer(" "));
        assertFalse(symbols.offer("!?"));
        assertEquals(7, symbols.count());
        assertEquals("候选1@?…中A\uD83D\uDE42", symbols.appendTo("候选"));
        assertTrue(symbols.toString().contains("count=7"));
        assertFalse(symbols.toString().contains("候选"));
    }

    @Test
    public void queueRejectsNinthSymbolAndClearsExplicitly() {
        PendingRimeSymbols symbols = new PendingRimeSymbols();
        for (int index = 0; index < PendingRimeSymbols.MAXIMUM_SYMBOLS; index++) {
            assertTrue(symbols.offer("?"));
        }

        assertFalse(symbols.offer("!"));
        assertEquals(8, symbols.count());
        symbols.clear();
        assertTrue(symbols.isEmpty());
    }

    @Test
    public void punctuationOptionNormalizesOnlyCommaAndPeriod() {
        assertEquals(",", PendingRimeSymbols.normalize(",", true));
        assertEquals("，", PendingRimeSymbols.normalize(",", false));
        assertEquals("。", PendingRimeSymbols.normalize(".", false));
        assertEquals("?", PendingRimeSymbols.normalize("?", false));
        assertThrows(
                IllegalArgumentException.class,
                () -> PendingRimeSymbols.normalize("a", true));
    }
}
