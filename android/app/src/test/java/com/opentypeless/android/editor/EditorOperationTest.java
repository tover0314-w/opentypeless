package com.opentypeless.android.editor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.Test;

public final class EditorOperationTest {
    private static final TextFingerprint HASH = selectedHash('a');

    @Test
    public void sealedModelContainsOnlyTheSevenAllowlistedOperations() {
        assertTrue(EditorOperation.class.isSealed());
        assertEquals(
                Set.of(
                        EditorOperation.SetComposition.class,
                        EditorOperation.CommitComposition.class,
                        EditorOperation.InsertText.class,
                        EditorOperation.ReplaceSelection.class,
                        EditorOperation.ReplaceLastCommit.class,
                        EditorOperation.DeleteBeforeCursor.class,
                        EditorOperation.PerformEditorAction.class),
                Set.of(EditorOperation.class.getPermittedSubclasses()));

        for (Class<?> variant : EditorOperation.class.getPermittedSubclasses()) {
            assertTrue(variant.isRecord());
            assertTrue(Modifier.isFinal(variant.getModifiers()));
            assertFalse(Serializable.class.isAssignableFrom(variant));
        }
    }

    @Test
    public void sourceAndCompositionOwnerHaveStableMinimalClosedValues() {
        assertArrayEquals(
                new OperationSource[]{
                        OperationSource.LATIN,
                        OperationSource.RIME,
                        OperationSource.VOICE,
                        OperationSource.ACTION,
                        OperationSource.UNDO,
                        OperationSource.RAW_RESTORE
                },
                OperationSource.values());
        assertArrayEquals(
                new CompositionOwner[]{
                        CompositionOwner.NONE,
                        CompositionOwner.LATIN,
                        CompositionOwner.RIME,
                        CompositionOwner.VOICE,
                        CompositionOwner.ACTION_PREVIEW
                },
                CompositionOwner.values());
        assertArrayEquals(
                new EditorAction[]{
                        EditorAction.GO,
                        EditorAction.SEARCH,
                        EditorAction.SEND,
                        EditorAction.NEXT,
                        EditorAction.DONE,
                        EditorAction.PREVIOUS
                },
                EditorAction.values());
    }

    @Test
    public void everyVariantIsAnImmutableValueAndCarriesSource() {
        EditorOperation[] operations = {
                new EditorOperation.SetComposition(
                        "partial", CompositionOwner.VOICE, 1, OperationSource.VOICE),
                new EditorOperation.CommitComposition(
                        CompositionOwner.RIME, 2, OperationSource.RIME),
                new EditorOperation.InsertText("insert", OperationSource.LATIN),
                new EditorOperation.ReplaceSelection(
                        new TextRange(8, 2), HASH, "replacement", OperationSource.ACTION),
                new EditorOperation.ReplaceLastCommit(
                        "commit-1", committedHash('b'), "replacement", OperationSource.UNDO),
                new EditorOperation.DeleteBeforeCursor(1, OperationSource.LATIN),
                new EditorOperation.PerformEditorAction(EditorAction.DONE, OperationSource.LATIN)
        };

        assertEquals(OperationSource.VOICE, operations[0].source());
        assertEquals(OperationSource.RIME, operations[1].source());
        assertEquals(OperationSource.LATIN, operations[2].source());
        assertEquals(OperationSource.ACTION, operations[3].source());
        assertEquals(OperationSource.UNDO, operations[4].source());
        assertEquals(OperationSource.LATIN, operations[5].source());
        assertEquals(OperationSource.LATIN, operations[6].source());

        EditorOperation.InsertText equal =
                new EditorOperation.InsertText("insert", OperationSource.LATIN);
        assertEquals(operations[2], equal);
        assertEquals(operations[2].hashCode(), equal.hashCode());
        assertNotEquals(operations[2],
                new EditorOperation.InsertText("changed", OperationSource.LATIN));
    }

    @Test
    public void compositionOwnerAndSourceCompatibilityIsAnExactClosedMatrix() {
        EditorOperation.SetComposition emptyClear = new EditorOperation.SetComposition(
                "", CompositionOwner.VOICE, 1, OperationSource.VOICE);
        assertEquals("", emptyClear.text());
        assertEquals(1, emptyClear.revision());
        assertEquals(CompositionOwner.VOICE, emptyClear.owner());
        EditorOperation.CommitComposition commit = new EditorOperation.CommitComposition(
                CompositionOwner.VOICE, 7, OperationSource.VOICE);
        assertEquals(7, commit.expectedRevision());

        for (CompositionOwner owner : CompositionOwner.values()) {
            for (OperationSource source : OperationSource.values()) {
                boolean accepted = isCompatibleCompositionPair(owner, source);
                Runnable set = () -> new EditorOperation.SetComposition(
                        "partial", owner, 1, source);
                Runnable finish = () -> new EditorOperation.CommitComposition(owner, 1, source);
                if (accepted) {
                    set.run();
                    finish.run();
                } else {
                    assertIllegal(set);
                    assertIllegal(finish);
                }
            }
        }
        assertNullRejected(() -> new EditorOperation.SetComposition(
                "partial", null, 1, OperationSource.VOICE));
        assertNullRejected(() -> new EditorOperation.SetComposition(
                null, CompositionOwner.VOICE, 1, OperationSource.VOICE));
        assertNullRejected(() -> new EditorOperation.CommitComposition(
                null, 1, OperationSource.VOICE));
        assertNullRejected(() -> new EditorOperation.SetComposition(
                "partial", CompositionOwner.VOICE, 1, null));
        assertNullRejected(() -> new EditorOperation.CommitComposition(
                CompositionOwner.VOICE, 1, null));
    }

    @Test
    public void compositionRevisionAcceptsOnlyTheFullPositiveLongRange() {
        for (long revision : new long[]{1, Long.MAX_VALUE}) {
            assertEquals(revision, new EditorOperation.SetComposition(
                    "partial", CompositionOwner.VOICE, revision,
                    OperationSource.VOICE).revision());
            assertEquals(revision, new EditorOperation.CommitComposition(
                    CompositionOwner.VOICE, revision,
                    OperationSource.VOICE).expectedRevision());
        }
        for (long revision : new long[]{0, -1, Long.MIN_VALUE}) {
            assertIllegal(() -> new EditorOperation.SetComposition(
                    "partial", CompositionOwner.VOICE, revision, OperationSource.VOICE));
            assertIllegal(() -> new EditorOperation.CommitComposition(
                    CompositionOwner.VOICE, revision, OperationSource.VOICE));
        }
    }

    @Test
    public void everyTextOperationEnforcesCodePointUtf16AndControlBoundaries() {
        String emoji = "\uD83D\uDE00";
        String asciiMaximum = "a".repeat(EditorOperation.MAX_TEXT_CODE_POINTS);
        String astralMaximum = emoji.repeat(EditorOperation.MAX_TEXT_CODE_POINTS);
        String composed = "\u00e9";
        String decomposed = "e\u0301";

        for (Function<String, EditorOperation> factory : textOperationFactories()) {
            assertEquals(asciiMaximum, operationText(factory.apply(asciiMaximum)));
            assertEquals(astralMaximum, operationText(factory.apply(astralMaximum)));
            assertEquals(composed, operationText(factory.apply(composed)));
            assertEquals(decomposed, operationText(factory.apply(decomposed)));
            assertNotEquals(operationText(factory.apply(composed)),
                    operationText(factory.apply(decomposed)));
            assertEquals("line one\nline two\tvalue\r",
                    operationText(factory.apply("line one\nline two\tvalue\r")));

            assertIllegal(() -> factory.apply(asciiMaximum + "a"));
            assertIllegal(() -> factory.apply(astralMaximum + emoji));
            assertIllegal(() -> factory.apply("bad\uD800text"));
            assertIllegal(() -> factory.apply("bad\uDC00text"));
            assertNullRejected(() -> factory.apply(null));
            for (int codePoint = 0; codePoint <= 0x9f; codePoint++) {
                if (!Character.isISOControl(codePoint)
                        || codePoint == '\t'
                        || codePoint == '\n'
                        || codePoint == '\r') {
                    continue;
                }
                String control = new String(Character.toChars(codePoint));
                assertIllegal(() -> factory.apply("before" + control + "after"));
            }
        }

        new EditorOperation.SetComposition(
                "", CompositionOwner.VOICE, 1, OperationSource.VOICE);
        new EditorOperation.ReplaceSelection(
                new TextRange(1, 2), HASH, "", OperationSource.ACTION);
        new EditorOperation.ReplaceLastCommit(
                "commit-id", committedHash('b'), "", OperationSource.UNDO);
        assertIllegal(() -> new EditorOperation.InsertText("", OperationSource.LATIN));
        assertNullRejected(() -> new EditorOperation.InsertText(null, OperationSource.LATIN));
        assertNullRejected(() -> new EditorOperation.InsertText("value", null));
    }

    @Test
    public void replaceSelectionBindsKnownRangeAndSelectedTextFingerprint() {
        EditorOperation.ReplaceSelection delete = new EditorOperation.ReplaceSelection(
                new TextRange(7, 2), HASH, "", OperationSource.ACTION);
        assertEquals(new TextRange(7, 2), delete.expectedSelection());
        assertEquals(HASH, delete.expectedTextHash());
        assertEquals("", delete.text());

        assertEquals(new TextRange(0, 8_000), new EditorOperation.ReplaceSelection(
                new TextRange(0, 8_000), HASH, "forward", OperationSource.ACTION)
                .expectedSelection());
        assertEquals(new TextRange(8_000, 0), new EditorOperation.ReplaceSelection(
                new TextRange(8_000, 0), HASH, "reverse", OperationSource.ACTION)
                .expectedSelection());

        assertIllegal(() -> new EditorOperation.ReplaceSelection(
                TextRange.UNKNOWN, HASH, "replacement", OperationSource.ACTION));
        assertIllegal(() -> new EditorOperation.ReplaceSelection(
                new TextRange(2, 2), HASH, "replacement", OperationSource.ACTION));
        assertIllegal(() -> new EditorOperation.ReplaceSelection(
                new TextRange(2, 7),
                new TextFingerprint(FingerprintDomain.BEFORE_CONTEXT, "a".repeat(64)),
                "replacement",
                OperationSource.ACTION));
        assertIllegal(() -> new EditorOperation.ReplaceSelection(
                new TextRange(2, 7),
                new TextFingerprint(FingerprintDomain.CONTEXT_V1, "a".repeat(64)),
                "replacement",
                OperationSource.ACTION));
        assertIllegal(() -> new EditorOperation.ReplaceSelection(
                new TextRange(0, 8_001),
                HASH,
                "replacement",
                OperationSource.ACTION));
        assertIllegal(() -> new EditorOperation.ReplaceSelection(
                new TextRange(8_001, 0),
                HASH,
                "replacement",
                OperationSource.ACTION));
        assertIllegal(() -> new EditorOperation.ReplaceSelection(
                new TextRange(0, Integer.MAX_VALUE),
                HASH,
                "replacement",
                OperationSource.ACTION));
        assertIllegal(() -> new EditorOperation.ReplaceSelection(
                new TextRange(Integer.MAX_VALUE, 0),
                HASH,
                "replacement",
                OperationSource.ACTION));
        assertNullRejected(() -> new EditorOperation.ReplaceSelection(
                null, HASH, "replacement", OperationSource.ACTION));
        assertNullRejected(() -> new EditorOperation.ReplaceSelection(
                new TextRange(2, 7), null, "replacement", OperationSource.ACTION));
        assertNullRejected(() -> new EditorOperation.ReplaceSelection(
                new TextRange(2, 7), HASH, null, OperationSource.ACTION));
        assertNullRejected(() -> new EditorOperation.ReplaceSelection(
                new TextRange(2, 7), HASH, "replacement", null));
    }

    @Test
    public void replaceLastCommitUsesBoundedOpaqueIdentityAndAllowsDeletion() {
        TextFingerprint committedHash = committedHash('b');
        EditorOperation.ReplaceLastCommit delete = new EditorOperation.ReplaceLastCommit(
                "commit-id", committedHash, "", OperationSource.UNDO);
        assertEquals("commit-id", delete.commitId());
        assertEquals("", delete.text());
        String asciiMaximum = "c".repeat(EditorOperation.MAX_COMMIT_ID_CODE_POINTS);
        String astralMaximum = "\uD83D\uDE00".repeat(EditorOperation.MAX_COMMIT_ID_CODE_POINTS);
        assertEquals(asciiMaximum, new EditorOperation.ReplaceLastCommit(
                asciiMaximum, committedHash, "value", OperationSource.UNDO).commitId());
        assertEquals(astralMaximum, new EditorOperation.ReplaceLastCommit(
                astralMaximum, committedHash, "value", OperationSource.UNDO).commitId());

        assertIllegal(() -> new EditorOperation.ReplaceLastCommit(
                "", committedHash, "value", OperationSource.UNDO));
        assertIllegal(() -> new EditorOperation.ReplaceLastCommit(
                "   ", committedHash, "value", OperationSource.UNDO));
        assertIllegal(() -> new EditorOperation.ReplaceLastCommit(
                "bad\ncommit", committedHash, "value", OperationSource.UNDO));
        assertIllegal(() -> new EditorOperation.ReplaceLastCommit(
                "c".repeat(EditorOperation.MAX_COMMIT_ID_CODE_POINTS + 1),
                committedHash,
                "value",
                OperationSource.UNDO));
        assertIllegal(() -> new EditorOperation.ReplaceLastCommit(
                "\uD83D\uDE00".repeat(EditorOperation.MAX_COMMIT_ID_CODE_POINTS + 1),
                committedHash,
                "value",
                OperationSource.UNDO));
        assertIllegal(() -> new EditorOperation.ReplaceLastCommit(
                "bad\uD800id", committedHash, "value", OperationSource.UNDO));
        assertIllegal(() -> new EditorOperation.ReplaceLastCommit(
                "bad\uDC00id", committedHash, "value", OperationSource.UNDO));
        assertIllegal(() -> new EditorOperation.ReplaceLastCommit(
                "commit-id", HASH, "value", OperationSource.UNDO));
        assertNullRejected(() -> new EditorOperation.ReplaceLastCommit(
                null, committedHash, "value", OperationSource.UNDO));
        assertNullRejected(() -> new EditorOperation.ReplaceLastCommit(
                "commit-id", null, "value", OperationSource.UNDO));
        assertNullRejected(() -> new EditorOperation.ReplaceLastCommit(
                "commit-id", committedHash, null, OperationSource.UNDO));
        assertNullRejected(() -> new EditorOperation.ReplaceLastCommit(
                "commit-id", committedHash, "value", null));
    }

    @Test
    public void deleteIsBoundedAndEditorActionIsTyped() {
        assertEquals(1, new EditorOperation.DeleteBeforeCursor(
                1, OperationSource.LATIN).codePoints());
        assertEquals(EditorOperation.MAX_DELETE_CODE_POINTS,
                new EditorOperation.DeleteBeforeCursor(
                        EditorOperation.MAX_DELETE_CODE_POINTS,
                        OperationSource.UNDO).codePoints());
        assertIllegal(() -> new EditorOperation.DeleteBeforeCursor(0, OperationSource.LATIN));
        assertIllegal(() -> new EditorOperation.DeleteBeforeCursor(-1, OperationSource.LATIN));
        assertIllegal(() -> new EditorOperation.DeleteBeforeCursor(
                EditorOperation.MAX_DELETE_CODE_POINTS + 1, OperationSource.UNDO));
        assertNullRejected(() -> new EditorOperation.DeleteBeforeCursor(1, null));

        for (EditorAction action : EditorAction.values()) {
            for (OperationSource accepted : new OperationSource[]{
                    OperationSource.LATIN, OperationSource.RIME}) {
                assertEquals(action, new EditorOperation.PerformEditorAction(
                        action, accepted).action());
            }
            for (OperationSource rejected : new OperationSource[]{
                    OperationSource.VOICE,
                    OperationSource.ACTION,
                    OperationSource.UNDO,
                    OperationSource.RAW_RESTORE}) {
                assertIllegal(() -> new EditorOperation.PerformEditorAction(action, rejected));
            }
        }
        assertNullRejected(() -> new EditorOperation.PerformEditorAction(
                null, OperationSource.LATIN));
        assertNullRejected(() -> new EditorOperation.PerformEditorAction(
                EditorAction.DONE, null));
    }

    @Test
    public void diagnosticStringsAndValidationExceptionsNeverContainSensitiveValues()
            throws ReflectiveOperationException {
        String privateText = "private-operation-text";
        String privateCommit = "private-commit-id";
        TextFingerprint privateSelectionHash = selectedHash('d');
        TextFingerprint privateCommitHash = committedHash('e');
        EditorOperation[] operations = {
                new EditorOperation.SetComposition(
                        privateText, CompositionOwner.VOICE, 99, OperationSource.VOICE),
                new EditorOperation.InsertText(privateText, OperationSource.ACTION),
                new EditorOperation.ReplaceSelection(
                        new TextRange(123, 145),
                        privateSelectionHash,
                        privateText,
                        OperationSource.ACTION),
                new EditorOperation.ReplaceLastCommit(
                        privateCommit,
                        privateCommitHash,
                        privateText,
                        OperationSource.UNDO)
        };

        for (EditorOperation operation : operations) {
            String rendered = operation.toString();
            for (RecordComponent component : operation.getClass().getRecordComponents()) {
                Object value = component.getAccessor().invoke(operation);
                if (value instanceof String privateValue) {
                    assertFalse(rendered.contains(privateValue));
                } else if (value instanceof TextFingerprint fingerprint) {
                    assertFalse(rendered.contains(fingerprint.sha256Hex()));
                } else if (value instanceof TextRange range) {
                    assertFalse(rendered.contains(Integer.toString(range.start())));
                    assertFalse(rendered.contains(Integer.toString(range.end())));
                }
            }
        }

        String sentinel = "SENSITIVE-SENTINEL-DO-NOT-LOG";
        assertIllegalWithoutSentinel(
                () -> new EditorOperation.InsertText(
                        sentinel + '\u0000', OperationSource.ACTION),
                sentinel);
        assertIllegalWithoutSentinel(
                () -> new EditorOperation.ReplaceLastCommit(
                        sentinel + '\n', privateCommitHash, "value", OperationSource.UNDO),
                sentinel);
    }

    private static TextFingerprint selectedHash(char value) {
        return new TextFingerprint(FingerprintDomain.SELECTED_TEXT, String.valueOf(value).repeat(64));
    }

    private static TextFingerprint committedHash(char value) {
        return new TextFingerprint(FingerprintDomain.COMMITTED_TEXT, String.valueOf(value).repeat(64));
    }

    @Test
    public void operationContractHasNoAndroidTypeOrArbitraryExecutionMethod() {
        Set<String> forbiddenMethodNames = Set.of(
                "commitText",
                "setComposingText",
                "finishComposingText",
                "deleteSurroundingText",
                "sendKeyEvent",
                "launchIntent",
                "execute");
        assertTrue(Arrays.stream(EditorOperation.class.getDeclaredMethods())
                .noneMatch(method -> forbiddenMethodNames.contains(method.getName())));

        Set<String> componentTypeNames = Arrays.stream(EditorOperation.class.getPermittedSubclasses())
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(component -> component.getType().getName())
                .collect(Collectors.toSet());
        assertTrue(componentTypeNames.stream().noneMatch(name -> name.startsWith("android.")));
        assertTrue(componentTypeNames.stream()
                .noneMatch(name -> name.contains("InputConnection") || name.contains("KeyEvent")));
    }

    private static List<Function<String, EditorOperation>> textOperationFactories() {
        List<Function<String, EditorOperation>> factories = new ArrayList<>();
        factories.add(text -> new EditorOperation.SetComposition(
                text, CompositionOwner.VOICE, 1, OperationSource.VOICE));
        factories.add(text -> new EditorOperation.InsertText(text, OperationSource.LATIN));
        factories.add(text -> new EditorOperation.ReplaceSelection(
                new TextRange(1, 2), HASH, text, OperationSource.ACTION));
        factories.add(text -> new EditorOperation.ReplaceLastCommit(
                "commit-id", committedHash('b'), text, OperationSource.UNDO));
        return List.copyOf(factories);
    }

    private static String operationText(EditorOperation operation) {
        if (operation instanceof EditorOperation.SetComposition value) return value.text();
        if (operation instanceof EditorOperation.InsertText value) return value.text();
        if (operation instanceof EditorOperation.ReplaceSelection value) return value.text();
        if (operation instanceof EditorOperation.ReplaceLastCommit value) return value.text();
        throw new AssertionError("operation does not carry text: " + operation.getClass());
    }

    private static boolean isCompatibleCompositionPair(
            CompositionOwner owner, OperationSource source) {
        return (owner == CompositionOwner.LATIN && source == OperationSource.LATIN)
                || (owner == CompositionOwner.RIME && source == OperationSource.RIME)
                || (owner == CompositionOwner.VOICE && source == OperationSource.VOICE)
                || (owner == CompositionOwner.ACTION_PREVIEW
                && source == OperationSource.ACTION);
    }

    private static void assertIllegal(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertIllegalWithoutSentinel(Runnable action, String sentinel) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertFalse(String.valueOf(expected.getMessage()).contains(sentinel));
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
