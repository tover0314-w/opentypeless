package com.opentypeless.android.editor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.opentypeless.android.context.FieldKind;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.Test;

public final class CommitRecordTest {
    private static final CommitRecord.RawTranscript.Absent NO_RAW =
            new CommitRecord.RawTranscript.Absent();
    private static final AtomicInteger NEXT_TEST_ID = new AtomicInteger();

    @Test
    public void createsAHostIdentifiedRecordForEveryEligibleActualSource() {
        EditorSessionSnapshot collapsed = snapshot(
                1010101, 2020202, new TextRange(7, 7), "", true, false);

        for (OperationSource source : new OperationSource[]{
                OperationSource.VOICE,
                OperationSource.ACTION}) {
            CommitRecord record = createRecord(source, collapsed, "applied", NO_RAW);
            assertEquals(source, record.source());
            assertSame(collapsed, record.originalSession());
            assertEquals("applied", record.insertedText());
            assertSame(NO_RAW, record.rawTranscript());
            assertTrue(record.learningAllowed());
            assertEquals(
                    Sha256EditorTextHasher.INSTANCE.committedText("applied"),
                    record.insertedTextFingerprint());
            assertEquals(FingerprintDomain.COMMITTED_TEXT,
                    record.insertedTextFingerprint().domain());
            assertTrue(record.commitId().startsWith("test-commit-"));
        }

        assertIllegal(() -> createRecord(
                OperationSource.LATIN, collapsed, "applied", NO_RAW));
        assertIllegal(() -> createRecord(
                OperationSource.RIME, collapsed, "applied", NO_RAW));
        assertIllegal(() -> createRecord(
                OperationSource.UNDO, collapsed, "applied", NO_RAW));
        assertIllegal(() -> createRecord(
                OperationSource.RAW_RESTORE, collapsed, "applied", NO_RAW));
    }

    @Test
    public void acceptsKnownCollapsedOrSelectedOriginsAndRejectsUnknownOrSensitiveOrigins() {
        EditorSessionSnapshot collapsed = snapshot(
                1, 2, new TextRange(3, 3), "", false, false);
        CommitRecord noLearning = createRecord(
                OperationSource.VOICE, collapsed, "x", NO_RAW);
        assertFalse(noLearning.learningAllowed());
        assertFalse(noLearning.originalSession().learningAllowed());

        EditorSessionSnapshot selected = snapshot(
                1, 2, new TextRange(9, 4), "value", true, false);
        assertEquals(new TextRange(9, 4), createRecord(
                OperationSource.ACTION, selected, "replacement", NO_RAW)
                .originalSession().selection());

        EditorSessionSnapshot unknown = snapshot(
                1, 2, TextRange.UNKNOWN, "", true, false);
        assertIllegal(() -> createRecord(
                OperationSource.VOICE, unknown, "x", NO_RAW));

        EditorSessionSnapshot sensitive = EditorSessionSnapshot.capture(
                1,
                2,
                "com.private.sensitive",
                77,
                FieldKind.SENSITIVE,
                129,
                6,
                new TextRange(4, 9),
                "",
                "",
                "",
                false,
                true,
                3);
        assertIllegal(() -> createRecord(
                OperationSource.VOICE, sensitive, "secret", NO_RAW));
    }

    @Test
    public void insertedTextUsesStrictUtf16CodePointAndControlBoundaries() {
        EditorSessionSnapshot origin = snapshot(
                1, 2, new TextRange(0, 0), "", true, false);
        String emoji = "\uD83D\uDE00";
        String asciiMaximum = "a".repeat(EditorOperationLimits.MAX_TEXT_CODE_POINTS);
        String astralMaximum = emoji.repeat(EditorOperationLimits.MAX_TEXT_CODE_POINTS);

        assertEquals("", createRecord(
                OperationSource.ACTION, origin, "", NO_RAW).insertedText());
        assertEquals(asciiMaximum, createRecord(
                OperationSource.VOICE, origin, asciiMaximum, NO_RAW).insertedText());
        assertEquals(astralMaximum, createRecord(
                OperationSource.ACTION, origin, astralMaximum, NO_RAW).insertedText());
        assertEquals("line one\nline two\tvalue\r", createRecord(
                OperationSource.VOICE,
                origin,
                "line one\nline two\tvalue\r",
                NO_RAW).insertedText());

        assertIllegal(() -> createRecord(
                OperationSource.VOICE, origin, asciiMaximum + "a", NO_RAW));
        assertIllegal(() -> createRecord(
                OperationSource.VOICE, origin, astralMaximum + emoji, NO_RAW));
        assertIllegal(() -> createRecord(
                OperationSource.VOICE, origin, "bad\uD800text", NO_RAW));
        assertIllegal(() -> createRecord(
                OperationSource.VOICE, origin, "bad\uDC00text", NO_RAW));

        for (int codePoint = 0; codePoint <= 0x9f; codePoint++) {
            if (!Character.isISOControl(codePoint)
                    || codePoint == '\t'
                    || codePoint == '\n'
                    || codePoint == '\r') {
                continue;
            }
            String control = new String(Character.toChars(codePoint));
            assertIllegal(() -> createRecord(
                    OperationSource.VOICE, origin, "before" + control + "after", NO_RAW));
        }
    }

    @Test
    public void rawTranscriptIsExplicitNonEmptyBoundedAndVoiceOnly() {
        assertTrue(CommitRecord.RawTranscript.class.isSealed());
        assertEquals(
                Set.of(
                        CommitRecord.RawTranscript.Absent.class,
                        CommitRecord.RawTranscript.Present.class),
                Set.of(CommitRecord.RawTranscript.class.getPermittedSubclasses()));
        assertTrue(CommitRecord.RawTranscript.Absent.class.isRecord());
        assertTrue(CommitRecord.RawTranscript.Present.class.isRecord());
        assertFalse(Serializable.class.isAssignableFrom(CommitRecord.RawTranscript.class));
        assertFalse(Serializable.class.isAssignableFrom(
                CommitRecord.RawTranscript.Absent.class));
        assertFalse(Serializable.class.isAssignableFrom(
                CommitRecord.RawTranscript.Present.class));

        EditorSessionSnapshot origin = snapshot(
                1, 2, new TextRange(1, 1), "", true, false);
        String emoji = "\uD83D\uDE00";
        String asciiMaximum = "r".repeat(EditorOperationLimits.MAX_TEXT_CODE_POINTS);
        String astralMaximum = emoji.repeat(EditorOperationLimits.MAX_TEXT_CODE_POINTS);
        CommitRecord.RawTranscript.Present raw =
                new CommitRecord.RawTranscript.Present("voice raw");
        assertSame(raw, createRecord(
                OperationSource.VOICE, origin, "final", raw).rawTranscript());
        assertEquals(asciiMaximum,
                new CommitRecord.RawTranscript.Present(asciiMaximum).text());
        assertEquals(astralMaximum,
                new CommitRecord.RawTranscript.Present(astralMaximum).text());
        assertEquals("line\nvalue\tend\r",
                new CommitRecord.RawTranscript.Present("line\nvalue\tend\r").text());

        for (OperationSource source : new OperationSource[]{
                OperationSource.LATIN,
                OperationSource.RIME,
                OperationSource.ACTION}) {
            assertIllegal(() -> createRecord(source, origin, "final", raw));
        }
        assertIllegal(() -> new CommitRecord.RawTranscript.Present(""));
        assertIllegal(() -> new CommitRecord.RawTranscript.Present(asciiMaximum + "r"));
        assertIllegal(() -> new CommitRecord.RawTranscript.Present(astralMaximum + emoji));
        assertIllegal(() -> new CommitRecord.RawTranscript.Present("bad\uD800raw"));
        assertIllegal(() -> new CommitRecord.RawTranscript.Present("bad\uDC00raw"));
        assertIllegal(() -> new CommitRecord.RawTranscript.Present("bad\u0000raw"));
        assertNullRejected(() -> new CommitRecord.RawTranscript.Present(null));
    }

    @Test
    public void commitIdentityIsHostSuppliedStrictOpaqueAndFingerprintIsStillInternal() {
        EditorSessionSnapshot origin = snapshot(
                1, 2, new TextRange(0, 0), "", true, false);
        CommitRecord first = CommitRecord.create(
                "host-id-1", OperationSource.VOICE, origin, "same", NO_RAW);
        CommitRecord second = CommitRecord.create(
                "host-id-2", OperationSource.VOICE, origin, "same", NO_RAW);
        assertEquals("host-id-1", first.commitId());
        assertEquals("host-id-2", second.commitId());
        assertNotEquals(first.commitId(), second.commitId());
        assertEquals(first.insertedTextFingerprint(), second.insertedTextFingerprint());

        String asciiMaximum = "i".repeat(EditorOperation.MAX_COMMIT_ID_CODE_POINTS);
        String astralMaximum =
                "\uD83D\uDE00".repeat(EditorOperation.MAX_COMMIT_ID_CODE_POINTS);
        assertEquals(asciiMaximum, CommitRecord.create(
                asciiMaximum, OperationSource.VOICE, origin, "x", NO_RAW).commitId());
        assertEquals(astralMaximum, CommitRecord.create(
                astralMaximum, OperationSource.ACTION, origin, "x", NO_RAW).commitId());
        assertIllegal(() -> CommitRecord.create(
                "", OperationSource.VOICE, origin, "x", NO_RAW));
        assertIllegal(() -> CommitRecord.create(
                "   ", OperationSource.VOICE, origin, "x", NO_RAW));
        assertIllegal(() -> CommitRecord.create(
                asciiMaximum + "i", OperationSource.VOICE, origin, "x", NO_RAW));
        assertIllegal(() -> CommitRecord.create(
                astralMaximum + "\uD83D\uDE00",
                OperationSource.VOICE,
                origin,
                "x",
                NO_RAW));
        assertIllegal(() -> CommitRecord.create(
                "bad\nidentifier", OperationSource.VOICE, origin, "x", NO_RAW));
        assertIllegal(() -> CommitRecord.create(
                "bad\uD800identifier", OperationSource.VOICE, origin, "x", NO_RAW));
        assertIllegal(() -> CommitRecord.create(
                "bad\uDC00identifier", OperationSource.VOICE, origin, "x", NO_RAW));
        assertNullRejected(() -> CommitRecord.create(
                null, OperationSource.VOICE, origin, "x", NO_RAW));

        assertEquals(0, CommitRecord.class.getConstructors().length);
        Method[] factories = Arrays.stream(CommitRecord.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("create"))
                .toArray(Method[]::new);
        assertEquals(1, factories.length);
        assertTrue(Modifier.isPublic(factories[0].getModifiers()));
        assertTrue(Modifier.isStatic(factories[0].getModifiers()));
        assertArrayEquals(
                new Class<?>[]{
                        String.class,
                        OperationSource.class,
                        EditorSessionSnapshot.class,
                        String.class,
                        CommitRecord.RawTranscript.class},
                factories[0].getParameterTypes());
        for (Constructor<?> constructor : CommitRecord.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
    }

    @Test
    public void modelIsFinalImmutableNonSerializableAndHasNoLatestCommitSeam() {
        assertTrue(Modifier.isFinal(CommitRecord.class.getModifiers()));
        assertFalse(CommitRecord.class.isRecord());
        assertFalse(Serializable.class.isAssignableFrom(CommitRecord.class));
        for (Field field : CommitRecord.class.getDeclaredFields()) {
            assertTrue(Modifier.isPrivate(field.getModifiers()));
            assertTrue(Modifier.isFinal(field.getModifiers()));
            assertFalse(Modifier.isStatic(field.getModifiers()));
            assertFalse(Throwable.class.isAssignableFrom(field.getType()));
            assertFalse(field.getType().getName().startsWith("android."));
        }
        Set<String> methodNames = Arrays.stream(CommitRecord.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertFalse(methodNames.stream().anyMatch(
                name -> name.toLowerCase().contains("latest")));
    }

    @Test
    public void diagnosticsRedactIdentityTargetTextRawAndFingerprint() {
        String inserted = "private-inserted-value";
        String rawText = "private-raw-value";
        EditorSessionSnapshot origin = EditorSessionSnapshot.capture(
                1010101,
                2020202,
                "com.private.target",
                3030303,
                FieldKind.GENERAL,
                4040404,
                5050505,
                new TextRange(6060606, 6060611),
                "abcde",
                "private-before",
                "private-after",
                false,
                false,
                7070707);
        CommitRecord.RawTranscript.Present raw =
                new CommitRecord.RawTranscript.Present(rawText);
        CommitRecord record = createRecord(
                OperationSource.VOICE, origin, inserted, raw);

        String diagnostic = record.toString();
        assertFalse(diagnostic.contains(record.commitId()));
        assertFalse(diagnostic.contains("com.private.target"));
        assertFalse(diagnostic.contains("2020202"));
        assertFalse(diagnostic.contains("3030303"));
        assertFalse(diagnostic.contains("6060606"));
        assertFalse(diagnostic.contains(inserted));
        assertFalse(diagnostic.contains(rawText));
        assertFalse(diagnostic.contains(record.insertedTextFingerprint().sha256Hex()));
        assertFalse(raw.toString().contains(rawText));
    }

    @Test
    public void rejectsAllNullInputsWithoutLeakingPrivateValues() {
        EditorSessionSnapshot origin = snapshot(
                1, 2, new TextRange(0, 0), "", true, false);
        assertNullRejected(() -> createRecord(null, origin, "value", NO_RAW));
        assertNullRejected(() -> createRecord(
                OperationSource.VOICE, null, "value", NO_RAW));
        assertNullRejected(() -> createRecord(
                OperationSource.VOICE, origin, null, NO_RAW));
        assertNullRejected(() -> createRecord(
                OperationSource.VOICE, origin, "value", null));

        String privateMalformed = "private\uD800value";
        try {
            createRecord(OperationSource.VOICE, origin, privateMalformed, NO_RAW);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertFalse(String.valueOf(expected.getMessage()).contains(privateMalformed));
        }
    }

    private static CommitRecord createRecord(
            OperationSource source,
            EditorSessionSnapshot originalSession,
            String insertedText,
            CommitRecord.RawTranscript rawTranscript) {
        return CommitRecord.create(
                "test-commit-" + NEXT_TEST_ID.incrementAndGet(),
                source,
                originalSession,
                insertedText,
                rawTranscript);
    }

    private static EditorSessionSnapshot snapshot(
            long epoch,
            long token,
            TextRange selection,
            String selectedText,
            boolean learningAllowed,
            boolean sensitive) {
        return EditorSessionSnapshot.capture(
                epoch,
                token,
                "com.example",
                11,
                FieldKind.GENERAL,
                1,
                2,
                selection,
                selectedText,
                "before",
                "after",
                learningAllowed,
                sensitive,
                3);
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
