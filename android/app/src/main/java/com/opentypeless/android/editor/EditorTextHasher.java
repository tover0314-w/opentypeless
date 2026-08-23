package com.opentypeless.android.editor;

/** Creates exact, bounded fingerprints without retaining surrounding editor plaintext. */
public interface EditorTextHasher {
    TextFingerprint selectedText(String selectedText);

    TextFingerprint beforeContext(String fullBeforeText);

    TextFingerprint afterContext(String fullAfterText);

    TextFingerprint context(String fullBeforeText, String selectedText, String fullAfterText);

    /** Domain-separated fingerprint for ReplaceLastCommit validation. */
    TextFingerprint committedText(String committedText);
}
