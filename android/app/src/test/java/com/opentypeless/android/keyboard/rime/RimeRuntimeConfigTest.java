package com.opentypeless.android.keyboard.rime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class RimeRuntimeConfigTest {
    @Test
    public void missingSchemaFallsBackToFirstInstalledSchema() {
        RimeRuntimeConfig config = RimeRuntimeConfig.resolved(
                List.of("first", "second"), "removed", false, true, false);

        assertEquals("first", config.schemaId());
        assertFalse(config.simplifiedOutput());
        assertTrue(config.asciiPunctuation());
        assertFalse(config.fullShape());
    }

    @Test
    public void fullShapeDisablesAsciiPunctuationDeterministically() {
        RimeRuntimeConfig config = RimeRuntimeConfig.resolved(
                List.of("local"), "local", true, true, true);

        assertTrue(config.fullShape());
        assertFalse(config.asciiPunctuation());
    }

    @Test
    public void schemaAndOptionVocabularyAreClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> RimeRuntimeConfig.defaults("../escape"));
        assertThrows(IllegalArgumentException.class,
                () -> RimeRuntimeConfig.resolved(List.of(), null, true, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> RimeRuntimeConfig.defaults("local").optionValue("unknown"));
        assertEquals(List.of("simplification", "ascii_punct", "full_shape"),
                RimeRuntimeConfig.supportedOptions());
    }

    @Test
    public void toStringContainsNoUserTextBeyondBoundedSchemaIdentity() {
        String rendered = new RimeRuntimeConfig("local", true, false, true).toString();
        assertTrue(rendered.contains("schemaId=local"));
        assertFalse(rendered.contains("candidate"));
    }
}
