package com.opentypeless.android.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Set;
import org.junit.Test;

public final class Sha256EditorTextHasherTest {
    private final EditorTextHasher hasher = Sha256EditorTextHasher.INSTANCE;

    @Test
    public void usesStableVersionedDomainSeparatedFrames() {
        assertEquals(1, FingerprintDomain.SELECTED_TEXT.stableId());
        assertEquals(2, FingerprintDomain.BEFORE_CONTEXT.stableId());
        assertEquals(3, FingerprintDomain.AFTER_CONTEXT.stableId());
        assertEquals(4, FingerprintDomain.CONTEXT_V1.stableId());
        assertEquals(5, FingerprintDomain.COMMITTED_TEXT.stableId());

        assertEquals(
                "21251a9584eeb3e216c6884352792b7d9a88d3ffabd7749daeb5b536ca72ae35",
                hasher.selectedText("hello").sha256Hex());
        assertEquals(
                "3c502dd41d727b0608a60e37ad12666a0aa1ff01946a17a2a06e39ac8d4e29dd",
                hasher.beforeContext("").sha256Hex());
        assertEquals(
                "c40de67b13fb77a09512503168a1eac64039d85acd00e002670e1e03ee8eff8b",
                hasher.afterContext("").sha256Hex());
        assertEquals(
                "75d2069fd547d43f59c2c4e0021940ec8aa01d38d64fb6ad10f5013afa141b3e",
                hasher.context("", "", "").sha256Hex());
        assertEquals(
                "109aa98d9fe22e8dd6bcb12b5ad2e520ba3f6340e5d85d083e5fa0e9b4f378eb",
                hasher.committedText("hello").sha256Hex());

        assertEquals(5, Set.of(
                hasher.selectedText("same"),
                hasher.beforeContext("same"),
                hasher.afterContext("same"),
                hasher.context("", "same", ""),
                hasher.committedText("same")).size());
        assertNotEquals(hasher.context("ab", "c", ""), hasher.context("a", "bc", ""));
    }

    @Test
    public void preservesExactUnicodeWithoutNormalization() {
        assertNotEquals(hasher.selectedText("\u00e9"), hasher.selectedText("e\u0301"));
        assertEquals(hasher.selectedText("\uD83D\uDC69\u200D\uD83D\uDCBB"),
                hasher.selectedText("\uD83D\uDC69\u200D\uD83D\uDCBB"));
        assertIllegal(() -> hasher.selectedText("\uD800"));
        assertIllegal(() -> hasher.beforeContext("ok\uDC00"));
        assertIllegal(() -> hasher.committedText("bad\u0000text"));
        assertIllegal(() -> hasher.committedText("bad\uD800text"));
    }

    @Test
    public void boundsBeforeAndAfterByCodePointWithoutSplittingSurrogates() {
        String emoji = "\uD83D\uDE00";
        String before = "discard" + emoji.repeat(64);
        String after = emoji.repeat(64) + "discard";

        assertEquals(hasher.beforeContext(emoji.repeat(64)), hasher.beforeContext(before));
        assertEquals(hasher.afterContext(emoji.repeat(64)), hasher.afterContext(after));
        assertEquals(64,
                EditorSessionLimits.boundedBeforeTail(before).codePointCount(
                        0, EditorSessionLimits.boundedBeforeTail(before).length()));
        assertEquals(64,
                EditorSessionLimits.boundedAfterHead(after).codePointCount(
                        0, EditorSessionLimits.boundedAfterHead(after).length()));
    }

    @Test
    public void boundsCommittedTextByOperationCodePointsWithoutSplittingSurrogates() {
        String emoji = "\uD83D\uDE00";
        String maximum = emoji.repeat(EditorOperation.MAX_TEXT_CODE_POINTS);
        assertEquals(
                FingerprintDomain.COMMITTED_TEXT,
                hasher.committedText(maximum).domain());
        assertIllegal(() -> hasher.committedText(maximum + emoji));
    }

    @Test
    public void everyContextComponentChangesCompositeFingerprint() {
        TextFingerprint baseline = hasher.context("before", "selected", "after");
        assertNotEquals(baseline, hasher.context("changed", "selected", "after"));
        assertNotEquals(baseline, hasher.context("before", "changed", "after"));
        assertNotEquals(baseline, hasher.context("before", "selected", "changed"));
    }

    @Test
    public void rejectsNullAndMalformedFingerprints() {
        assertNullRejected(() -> hasher.selectedText(null));
        assertNullRejected(() -> hasher.context("", null, ""));
        assertNullRejected(() -> hasher.committedText(null));
        assertNullRejected(() -> new TextFingerprint(null, "0".repeat(64)));
        assertNullRejected(() -> new TextFingerprint(FingerprintDomain.SELECTED_TEXT, null));
        assertIllegal(() -> new TextFingerprint(FingerprintDomain.SELECTED_TEXT, "0".repeat(63)));
        assertIllegal(() -> new TextFingerprint(FingerprintDomain.SELECTED_TEXT, "A".repeat(64)));
        assertIllegal(() -> new TextFingerprint(FingerprintDomain.SELECTED_TEXT, "g".repeat(64)));
    }

    @Test
    public void secureComparisonRequiresMatchingDomainAndDigest() {
        TextFingerprint value = hasher.selectedText("value");
        assertTrue(value.securelyMatches(hasher.selectedText("value")));
        assertFalse(value.securelyMatches(hasher.selectedText("other")));
        assertFalse(value.securelyMatches(hasher.beforeContext("value")));
        assertFalse(value.securelyMatches(null));
        assertFalse(value.toString().contains(value.sha256Hex()));
        assertTrue(value.toString().contains("<redacted>"));
        assertEquals(
                FingerprintDomain.COMMITTED_TEXT,
                hasher.committedText("").domain());
    }

    private static void assertIllegal(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertNullRejected(Runnable action) {
        try {
            action.run();
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // Expected.
        }
    }
}
