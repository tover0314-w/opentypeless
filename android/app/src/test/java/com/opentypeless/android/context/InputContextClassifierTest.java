package com.opentypeless.android.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import com.opentypeless.android.context.InputContextClassifier.PrivacyClassification;
import com.opentypeless.android.context.InputContextClassifier.Sensitivity;
import java.util.List;
import org.junit.Test;

public final class InputContextClassifierTest {
    @Test
    public void everyPlatformPasswordVariationIsSensitive() {
        for (int inputType : List.of(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
            assertClassification(inputType, 0, null, null, null, null,
                    Sensitivity.PASSWORD, false);
        }
    }

    @Test
    public void otpMarkersAreRecognizedAcrossBoundedMetadataChannels() {
        assertClassification(text(), 0, "oneTimeCode", null, null, null,
                Sensitivity.ONE_TIME_CODE, false);
        assertClassification(number(), 0, null, "Verification code", null, null,
                Sensitivity.ONE_TIME_CODE, false);
        assertClassification(number(), 0, null, null, "短信验证码", null,
                Sensitivity.ONE_TIME_CODE, false);
        assertClassification(number(), 0, null, null, null, "semantic=sms_otp",
                Sensitivity.ONE_TIME_CODE, false);
    }

    @Test
    public void paymentMarkersAreRecognizedWithoutTreatingEveryNumberAsSensitive() {
        assertClassification(number(), 0, "creditCardNumber", null, null, null,
                Sensitivity.PAYMENT, false);
        assertClassification(number(), 0, null, "银行卡号", null, null,
                Sensitivity.PAYMENT, false);
        assertClassification(number(), 0, null, null, "CVV", null,
                Sensitivity.PAYMENT, false);
        assertClassification(number(), 0, null, null, null, "payment_card",
                Sensitivity.PAYMENT, false);
        assertClassification(number(), 0, null, null, "Decimal number", null,
                Sensitivity.NONE, true);
    }

    @Test
    public void identityMarkersAreSensitiveButOrdinaryPersonNamesRemainOrdinary() {
        assertClassification(text(), 0, "identityNumber", null, null, null,
                Sensitivity.IDENTITY, false);
        assertClassification(text(), 0, null, "Passport number", null, null,
                Sensitivity.IDENTITY, false);
        assertClassification(text(), 0, null, null, "身份证号码", null,
                Sensitivity.IDENTITY, false);
        assertClassification(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                0, "displayName", "Person name", null, null,
                Sensitivity.NONE, true);
    }

    @Test
    public void noPersonalizedLearningOnlyTightensLearning() {
        PrivacyClassification classification = classify(
                text(), EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
                "message", null, null, null);
        assertEquals(Sensitivity.NONE, classification.sensitivity());
        assertFalse(classification.sensitive());
        assertFalse(classification.learningAllowed());
    }

    @Test
    public void nullMalformedAndOversizedMetadataFailClosed() {
        PrivacyClassification missing = InputContextClassifier.classifyPrivacy(null);
        assertEquals(Sensitivity.UNTRUSTED_METADATA, missing.sensitivity());
        assertFalse(missing.learningAllowed());
        assertClassification(text(), 0, "field\u202e", null, null, null,
                Sensitivity.UNTRUSTED_METADATA, false);
        assertClassification(text(), 0, "field\ud800", null, null, null,
                Sensitivity.UNTRUSTED_METADATA, false);
        assertClassification(text(), 0, "x".repeat(129), null, null, null,
                Sensitivity.UNTRUSTED_METADATA, false);
        assertClassification(text(), 0, "x".repeat(100), "y".repeat(100),
                "z".repeat(57), null, Sensitivity.UNTRUSTED_METADATA, false);
        assertClassification(text(), 0, "\ufb03".repeat(40), "\ufb03".repeat(40),
                "\ufb03".repeat(40), null, Sensitivity.UNTRUSTED_METADATA, false);
    }

    @Test
    public void nearMissesDoNotBroadenOrdinaryFields() {
        for (String value : List.of(
                "photo", "cardinal direction", "identity matrix", "passage", "credit score")) {
            assertClassification(text(), 0, value, value, value, value,
                    Sensitivity.NONE, true);
        }
    }

    @Test
    public void fieldKindProjectionCanOnlyPromoteToSensitive() {
        assertEquals(FieldKind.SENSITIVE, classifyField(
                number(), 0, "otp", null, null, null));
        assertEquals(FieldKind.EMAIL_ADDRESS, classifyField(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                0, "email", null, null, null));
        assertEquals(FieldKind.NUMBER, classifyField(
                number(), 0, "quantity", null, null, null));
    }

    @Test
    public void classificationShapeAndDiagnosticsAreContentFree() {
        assertEquals(new Sensitivity[] {
            Sensitivity.NONE,
            Sensitivity.PASSWORD,
            Sensitivity.ONE_TIME_CODE,
            Sensitivity.PAYMENT,
            Sensitivity.IDENTITY,
            Sensitivity.UNTRUSTED_METADATA
        }, Sensitivity.values());
        PrivacyClassification classification = classify(
                number(), 0, "secretPaymentCardNumber", null, null, null);
        assertTrue(classification.sensitive());
        assertFalse(classification.toString().contains("secretPaymentCardNumber"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PrivacyClassification(Sensitivity.PAYMENT, true));
        assertThrows(
                NullPointerException.class,
                () -> new PrivacyClassification(null, false));
    }

    private static PrivacyClassification classify(
            int inputType,
            int imeOptions,
            CharSequence fieldName,
            CharSequence label,
            CharSequence hint,
            CharSequence privateOptions) {
        return InputContextClassifier.classifyPrivacyValues(
                inputType, imeOptions, fieldName, label, hint, privateOptions);
    }

    private static FieldKind classifyField(
            int inputType,
            int imeOptions,
            CharSequence fieldName,
            CharSequence label,
            CharSequence hint,
            CharSequence privateOptions) {
        return InputContextClassifier.classifyValues(
                inputType, imeOptions, fieldName, label, hint, privateOptions);
    }

    private static void assertClassification(
            int inputType,
            int imeOptions,
            CharSequence fieldName,
            CharSequence label,
            CharSequence hint,
            CharSequence privateOptions,
            Sensitivity sensitivity,
            boolean learningAllowed) {
        PrivacyClassification classification = classify(
                inputType, imeOptions, fieldName, label, hint, privateOptions);
        assertEquals(sensitivity, classification.sensitivity());
        assertEquals(sensitivity != Sensitivity.NONE, classification.sensitive());
        assertEquals(learningAllowed, classification.learningAllowed());
    }

    private static int text() {
        return InputType.TYPE_CLASS_TEXT;
    }

    private static int number() {
        return InputType.TYPE_CLASS_NUMBER;
    }
}
