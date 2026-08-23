package com.opentypeless.android.keyboard.field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.text.InputType;
import com.opentypeless.android.context.FieldKind;
import org.junit.Test;

public final class KeyboardFieldProfileTest {
    @Test
    public void metadataSelectsEverySpecializedProfile() {
        assertEquals(KeyboardFieldProfile.EMAIL, profile(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                FieldKind.EMAIL_ADDRESS));
        assertEquals(KeyboardFieldProfile.URI, profile(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                FieldKind.URI));
        assertEquals(KeyboardFieldProfile.PHONE,
                profile(InputType.TYPE_CLASS_PHONE, FieldKind.NUMBER));
        assertEquals(KeyboardFieldProfile.NUMBER, profile(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL,
                FieldKind.NUMBER));
        assertEquals(KeyboardFieldProfile.DATE, profile(
                InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE,
                FieldKind.NUMBER));
        assertEquals(KeyboardFieldProfile.PASSWORD, profile(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                FieldKind.SENSITIVE));
    }

    @Test
    public void sensitiveClassificationAlwaysWinsAndUnknownFallsBack() {
        assertEquals(KeyboardFieldProfile.PASSWORD,
                profile(InputType.TYPE_CLASS_PHONE, FieldKind.SENSITIVE));
        assertEquals(KeyboardFieldProfile.GENERAL,
                profile(InputType.TYPE_CLASS_TEXT, FieldKind.GENERAL));
        assertFalse(KeyboardFieldProfile.GENERAL.usesNumericPanel());
        assertTrue(KeyboardFieldProfile.PHONE.usesNumericPanel());
        assertTrue(KeyboardFieldProfile.NUMBER.usesNumericPanel());
        assertTrue(KeyboardFieldProfile.DATE.usesNumericPanel());
    }

    private static KeyboardFieldProfile profile(int inputType, FieldKind fieldKind) {
        return KeyboardFieldProfile.fromInputType(inputType, fieldKind);
    }
}
