package com.opentypeless.android.context;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

public final class InputContextClassifier {
    private static final int MAX_METADATA_CODE_POINTS = 256;
    private static final int MAX_METADATA_FIELD_CODE_POINTS = 128;

    private InputContextClassifier() {}

    /** Closed, content-free reason for a field-level privacy restriction. */
    public enum Sensitivity {
        NONE,
        PASSWORD,
        ONE_TIME_CODE,
        PAYMENT,
        IDENTITY,
        UNTRUSTED_METADATA
    }

    /** Metadata-only privacy result; sensitive classifications can never allow learning. */
    public record PrivacyClassification(Sensitivity sensitivity, boolean learningAllowed) {
        public PrivacyClassification {
            sensitivity = Objects.requireNonNull(sensitivity, "sensitivity");
            if (sensitivity != Sensitivity.NONE && learningAllowed) {
                throw new IllegalArgumentException("sensitive fields cannot allow learning");
            }
        }

        public boolean sensitive() {
            return sensitivity != Sensitivity.NONE;
        }

        @Override
        public String toString() {
            return "PrivacyClassification{sensitivity=" + sensitivity
                    + ", learningAllowed=" + learningAllowed + "}";
        }
    }

    public static FieldKind classify(EditorInfo info) {
        if (info == null) return FieldKind.SENSITIVE;
        return classifyValues(
                info.inputType,
                info.imeOptions,
                info.fieldName,
                info.label,
                info.hintText,
                info.privateImeOptions);
    }

    static FieldKind classifyValues(
            int inputType,
            int imeOptions,
            CharSequence fieldName,
            CharSequence label,
            CharSequence hintText,
            CharSequence privateImeOptions) {
        PrivacyClassification privacy = classifyPrivacyValues(
                inputType, imeOptions, fieldName, label, hintText, privateImeOptions);
        if (privacy.sensitive()) return FieldKind.SENSITIVE;
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;

        if (inputClass == InputType.TYPE_CLASS_NUMBER
                || inputClass == InputType.TYPE_CLASS_PHONE
                || inputClass == InputType.TYPE_CLASS_DATETIME) {
            return FieldKind.NUMBER;
        }
        if (inputClass != InputType.TYPE_CLASS_TEXT) return FieldKind.GENERAL;

        return switch (variation) {
            case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL_ADDRESS;
            case InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URI;
            case InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> FieldKind.PERSON_NAME;
            case InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> FieldKind.SHORT_MESSAGE;
            case InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE -> FieldKind.LONG_TEXT;
            case InputType.TYPE_TEXT_VARIATION_FILTER -> FieldKind.SEARCH;
            default -> isSearchAction(imeOptions) ? FieldKind.SEARCH : FieldKind.GENERAL;
        };
    }

    public static PrivacyClassification classifyPrivacy(EditorInfo info) {
        if (info == null) return restricted(Sensitivity.UNTRUSTED_METADATA);
        return classifyPrivacyValues(
                info.inputType,
                info.imeOptions,
                info.fieldName,
                info.label,
                info.hintText,
                info.privateImeOptions);
    }

    static PrivacyClassification classifyPrivacyValues(
            int inputType,
            int imeOptions,
            CharSequence fieldName,
            CharSequence label,
            CharSequence hintText,
            CharSequence privateImeOptions) {
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (isSensitive(inputClass, variation)) return restricted(Sensitivity.PASSWORD);

        Metadata metadata = normalizeMetadata(fieldName, label, hintText, privateImeOptions);
        if (!metadata.valid()) return restricted(Sensitivity.UNTRUSTED_METADATA);
        String words = metadata.words();
        String compact = metadata.compact();
        if (matchesPayment(words, compact)) return restricted(Sensitivity.PAYMENT);
        if (matchesOneTimeCode(words, compact)) return restricted(Sensitivity.ONE_TIME_CODE);
        if (matchesIdentity(words, compact)) return restricted(Sensitivity.IDENTITY);

        boolean learningAllowed =
                (imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
        return new PrivacyClassification(Sensitivity.NONE, learningAllowed);
    }

    public static boolean isSensitive(int inputClass, int variation) {
        return (inputClass == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || (inputClass == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    private static PrivacyClassification restricted(Sensitivity sensitivity) {
        return new PrivacyClassification(sensitivity, false);
    }

    private static boolean matchesOneTimeCode(String words, String compact) {
        return containsWord(words, "otp")
                || containsWord(words, "2fa")
                || containsPhrase(words, "one time password")
                || containsPhrase(words, "one time code")
                || containsPhrase(words, "verification code")
                || containsPhrase(words, "verify code")
                || containsPhrase(words, "sms code")
                || containsPhrase(words, "sms otp")
                || containsPhrase(words, "auth code")
                || containsPhrase(words, "two factor code")
                || containsPhrase(words, "security code")
                || compact.contains("onetimepassword")
                || compact.contains("onetimecode")
                || compact.contains("verificationcode")
                || compact.contains("smscode")
                || compact.contains("twofactorcode")
                || compact.contains("验证码")
                || compact.contains("动态码")
                || compact.contains("短信码");
    }

    private static boolean matchesPayment(String words, String compact) {
        return containsWord(words, "cvv")
                || containsWord(words, "cvc")
                || containsPhrase(words, "credit card")
                || containsPhrase(words, "debit card")
                || containsPhrase(words, "payment card")
                || containsPhrase(words, "bank card")
                || containsPhrase(words, "card number")
                || compact.contains("creditcard")
                || compact.contains("debitcard")
                || compact.contains("paymentcard")
                || compact.contains("bankcard")
                || compact.contains("cardnumber")
                || compact.contains("银行卡")
                || compact.contains("支付卡")
                || compact.contains("卡号");
    }

    private static boolean matchesIdentity(String words, String compact) {
        return containsWord(words, "ssn")
                || containsPhrase(words, "identity number")
                || containsPhrase(words, "id number")
                || containsPhrase(words, "national id")
                || containsPhrase(words, "government id")
                || containsPhrase(words, "passport number")
                || containsPhrase(words, "social security")
                || containsPhrase(words, "tax id")
                || compact.contains("identitynumber")
                || compact.contains("nationalid")
                || compact.contains("governmentid")
                || compact.contains("passportnumber")
                || compact.contains("socialsecurity")
                || compact.contains("身份证")
                || compact.contains("身份号码")
                || compact.contains("护照号");
    }

    private static Metadata normalizeMetadata(CharSequence... values) {
        int totalCodePoints = 0;
        int totalNormalizedCodePoints = 0;
        StringBuilder words = new StringBuilder();
        StringBuilder compact = new StringBuilder();
        for (CharSequence value : values) {
            if (value == null) continue;
            String raw = value.toString();
            if (hasUnpairedSurrogate(raw)) return Metadata.invalid();
            int count = raw.codePointCount(0, raw.length());
            totalCodePoints += count;
            if (count > MAX_METADATA_FIELD_CODE_POINTS
                    || totalCodePoints > MAX_METADATA_CODE_POINTS) {
                return Metadata.invalid();
            }
            String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT);
            int normalizedCount = normalized.codePointCount(0, normalized.length());
            totalNormalizedCodePoints += normalizedCount;
            if (normalizedCount > MAX_METADATA_FIELD_CODE_POINTS
                    || totalNormalizedCodePoints > MAX_METADATA_CODE_POINTS) {
                return Metadata.invalid();
            }
            if (words.length() > 0) words.append(' ');
            for (int offset = 0; offset < normalized.length(); ) {
                int codePoint = normalized.codePointAt(offset);
                offset += Character.charCount(codePoint);
                if (unsafeMetadataCodePoint(codePoint)) return Metadata.invalid();
                if (Character.isLetterOrDigit(codePoint)) {
                    words.appendCodePoint(codePoint);
                    compact.appendCodePoint(codePoint);
                } else if (words.length() > 0 && words.charAt(words.length() - 1) != ' ') {
                    words.append(' ');
                }
            }
        }
        return new Metadata(collapseSpaces(words.toString()), compact.toString(), true);
    }

    private static boolean containsWord(String words, String value) {
        return containsPhrase(words, value);
    }

    private static boolean containsPhrase(String words, String value) {
        return (" " + words + " ").contains(" " + value + " ");
    }

    private static String collapseSpaces(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean previousSpace = true;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == ' ') {
                if (!previousSpace) result.append(current);
                previousSpace = true;
            } else {
                result.append(current);
                previousSpace = false;
            }
        }
        int length = result.length();
        if (length > 0 && result.charAt(length - 1) == ' ') result.setLength(length - 1);
        return result.toString();
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) return true;
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean unsafeMetadataCodePoint(int codePoint) {
        return Character.isISOControl(codePoint)
                || codePoint == 0x200e
                || codePoint == 0x200f
                || (codePoint >= 0x202a && codePoint <= 0x202e)
                || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    private record Metadata(String words, String compact, boolean valid) {
        private static Metadata invalid() {
            return new Metadata("", "", false);
        }
    }

    private static boolean isSearchAction(int imeOptions) {
        return (imeOptions & EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH;
    }
}
