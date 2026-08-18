package com.opentypeless.android.editor;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** SHA-256 over a versioned, domain-separated, length-framed UTF-8 representation. */
public final class Sha256EditorTextHasher implements EditorTextHasher {
    public static final Sha256EditorTextHasher INSTANCE = new Sha256EditorTextHasher();

    private static final byte[] MAGIC =
            "OpenTypeless.EditorFingerprint".getBytes(StandardCharsets.US_ASCII);
    private static final int FRAME_VERSION = 1;

    private Sha256EditorTextHasher() {}

    @Override
    public TextFingerprint selectedText(String selectedText) {
        EditorSessionLimits.requireSelectedText(selectedText);
        return fingerprint(FingerprintDomain.SELECTED_TEXT, selectedText);
    }

    @Override
    public TextFingerprint beforeContext(String fullBeforeText) {
        return fingerprint(
                FingerprintDomain.BEFORE_CONTEXT,
                EditorSessionLimits.boundedBeforeTail(fullBeforeText));
    }

    @Override
    public TextFingerprint afterContext(String fullAfterText) {
        return fingerprint(
                FingerprintDomain.AFTER_CONTEXT,
                EditorSessionLimits.boundedAfterHead(fullAfterText));
    }

    @Override
    public TextFingerprint context(
            String fullBeforeText, String selectedText, String fullAfterText) {
        EditorSessionLimits.requireSelectedText(selectedText);
        return fingerprint(
                FingerprintDomain.CONTEXT_V1,
                EditorSessionLimits.boundedBeforeTail(fullBeforeText),
                selectedText,
                EditorSessionLimits.boundedAfterHead(fullAfterText));
    }

    @Override
    public TextFingerprint committedText(String committedText) {
        return fingerprint(
                FingerprintDomain.COMMITTED_TEXT,
                EditorOperationLimits.requireText(committedText, "committedText", true));
    }

    private static TextFingerprint fingerprint(FingerprintDomain domain, String... components) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(components, "components");
        MessageDigest digest = sha256();
        digest.update(MAGIC);
        updateInt(digest, FRAME_VERSION);
        updateInt(digest, domain.stableId());
        updateInt(digest, components.length);
        for (int index = 0; index < components.length; index++) {
            String component = Objects.requireNonNull(components[index], "component[" + index + "]");
            EditorSessionLimits.requireWellFormedUtf16(component, "component[" + index + "]");
            ByteBuffer bytes = encodeUtf8(component);
            updateInt(digest, bytes.remaining());
            digest.update(bytes);
        }
        return new TextFingerprint(domain, toLowerHex(digest.digest()));
    }

    private static ByteBuffer encodeUtf8(String value) {
        try {
            return StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("text cannot be encoded as strict UTF-8", error);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static String toLowerHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}
