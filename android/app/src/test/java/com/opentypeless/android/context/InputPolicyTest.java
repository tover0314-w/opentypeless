package com.opentypeless.android.context;

import static org.junit.Assert.assertEquals;

import com.opentypeless.android.settings.ProcessingMode;

import org.junit.Test;

public final class InputPolicyTest {
    @Test
    public void autoNeverUsesGenerativeEditingForStructuredFields() {
        for (FieldKind kind : new FieldKind[]{
                FieldKind.EMAIL_ADDRESS,
                FieldKind.URI,
                FieldKind.NUMBER,
                FieldKind.PERSON_NAME,
                FieldKind.SEARCH,
                FieldKind.SENSITIVE}) {
            assertEquals(ProcessingMode.VERBATIM, InputPolicy.resolve(
                    ProcessingMode.AUTO,
                    new InputContext("app", kind, "", "", true)));
        }
    }

    @Test
    public void autoUsesSmartForProseAndSelection() {
        assertEquals(ProcessingMode.SMART, InputPolicy.resolve(
                ProcessingMode.AUTO,
                new InputContext("app", FieldKind.LONG_TEXT, "", "", true)));
        assertEquals(ProcessingMode.SMART, InputPolicy.resolve(
                ProcessingMode.AUTO,
                new InputContext("app", FieldKind.URI, "selected", "", true)));
    }

    @Test
    public void explicitModeAlwaysWins() {
        assertEquals(ProcessingMode.TRANSLATE, InputPolicy.resolve(
                ProcessingMode.TRANSLATE,
                new InputContext("app", FieldKind.NUMBER, "", "", true)));
    }
}
