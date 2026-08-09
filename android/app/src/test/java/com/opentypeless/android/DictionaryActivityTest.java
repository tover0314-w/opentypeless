package com.opentypeless.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DictionaryActivityTest {
    @Test
    public void previewMakesControlAndBidirectionalFormattingCharactersVisible() {
        assertEquals(
                "safe⟦U+202E⟧evil⟦U+000A⟧",
                DictionaryActivity.previewValue("safe\u202Eevil\n"));
    }
}
