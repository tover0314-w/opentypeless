package com.opentypeless.android.editor.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.editor.CommitRecord;
import com.opentypeless.android.editor.EditorSessionSnapshot;
import com.opentypeless.android.editor.OperationSource;
import com.opentypeless.android.editor.TextRange;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class CommitLedgerTest {
    @Test
    public void exactIdAndSameEditorSessionAreRequiredForResolution() {
        CommitLedger ledger = new CommitLedger(() -> "exact-id");
        EditorSessionSnapshot original = snapshot(1, 11, 0, true);
        CommitLedger.Reservation reservation = ledger.reserve(
                original,
                OperationSource.VOICE,
                "inserted-private",
                new CommitRecord.RawTranscript.Present("raw-private"));
        CommitRecord record = reservation.publish();

        assertTrue(record.commitId().endsWith("-exact-id"));
        assertFalse(record.commitId().contains("inserted-private"));
        assertFalse(record.commitId().contains("raw-private"));
        assertFalse(record.commitId().contains(record.insertedTextFingerprint().sha256Hex()));
        assertSame(record, ledger.resolve(record.commitId(), original).orElseThrow());
        assertTrue(ledger.resolve("wrong-id", original).isEmpty());
        assertTrue(ledger.resolve(null, original).isEmpty());
        assertTrue(ledger.resolve("exact-id", null).isEmpty());
        assertSame(record, ledger.resolve(
                record.commitId(), snapshot(1, 11, 4, true)).orElseThrow());
        assertTrue(ledger.resolve(
                record.commitId(), snapshot(2, 12, 0, true)).isEmpty());
        assertTrue(ledger.resolve(
                record.commitId(), snapshotWithMetadata(1, 11, 0, true, 2, 0)).isEmpty());
        assertTrue(ledger.resolve(
                record.commitId(), snapshotWithMetadata(1, 11, 0, true, 1, 6)).isEmpty());
        assertFalse(ledger.toString().contains("private"));
        assertFalse(reservation.toString().contains("private"));
    }

    @Test
    public void publishingReplacesTheSingleSlotAndConsumeIsExactAndTerminal() {
        AtomicInteger ids = new AtomicInteger();
        CommitLedger ledger = new CommitLedger(() -> "id-" + ids.incrementAndGet());
        EditorSessionSnapshot session = snapshot(1, 11, 0, true);

        CommitRecord first = ledger.reserve(
                session,
                OperationSource.VOICE,
                "first",
                new CommitRecord.RawTranscript.Absent()).publish();
        CommitRecord second = ledger.reserve(
                session,
                OperationSource.ACTION,
                "second",
                new CommitRecord.RawTranscript.Absent()).publish();

        assertNotEquals(first.commitId(), second.commitId());
        assertEquals(1, ledger.sizeForTest());
        assertTrue(ledger.resolve(first.commitId(), session).isEmpty());
        assertSame(second, ledger.resolve(second.commitId(), session).orElseThrow());
        assertTrue(ledger.consume("wrong-id", session).isEmpty());
        assertEquals(1, ledger.sizeForTest());
        assertSame(second, ledger.consume(second.commitId(), session).orElseThrow());
        assertEquals(0, ledger.sizeForTest());
        assertTrue(ledger.consume(second.commitId(), session).isEmpty());
    }

    @Test
    public void repeatedOpaqueSourceCannotCauseAnAbaIdentifier() {
        AtomicInteger calls = new AtomicInteger();
        CommitLedger ledger = new CommitLedger(() -> {
            calls.incrementAndGet();
            return "duplicate";
        });
        EditorSessionSnapshot session = snapshot(1, 11, 0, true);
        CommitRecord first = ledger.reserve(
                session,
                OperationSource.VOICE,
                "first",
                new CommitRecord.RawTranscript.Absent()).publish();

        CommitLedger.Reservation replacement = ledger.reserve(
                session,
                OperationSource.VOICE,
                "second",
                new CommitRecord.RawTranscript.Absent());

        assertEquals(2, calls.get());
        assertSame(first, ledger.resolve(first.commitId(), session).orElseThrow());
        CommitRecord second = replacement.publish();
        assertNotEquals(first.commitId(), second.commitId());
        assertTrue(first.commitId().endsWith("-duplicate"));
        assertTrue(second.commitId().endsWith("-duplicate"));
        assertTrue(ledger.resolve(first.commitId(), session).isEmpty());
        assertSame(second, ledger.resolve(second.commitId(), session).orElseThrow());
    }

    @Test
    public void generatorFailureAndInvalidIdPublishNothing() {
        EditorSessionSnapshot session = snapshot(1, 11, 0, true);

        AtomicInteger throwingCalls = new AtomicInteger();
        CommitLedger throwing = new CommitLedger(() -> {
            throwingCalls.incrementAndGet();
            throw new IllegalStateException("PRIVATE_GENERATOR_FAILURE");
        });
        assertTrue(throwing.reserve(
                session,
                OperationSource.VOICE,
                "text",
                new CommitRecord.RawTranscript.Absent()) == null);
        assertEquals(1, throwingCalls.get());
        assertEquals(0, throwing.sizeForTest());

        CommitLedger invalid = new CommitLedger(() -> " ");
        assertTrue(invalid.reserve(
                session,
                OperationSource.VOICE,
                "text",
                new CommitRecord.RawTranscript.Absent()) == null);
        assertEquals(0, invalid.sizeForTest());

    }

    @Test
    public void opaqueSourceNeverReusesAnIdAfterConsumeOrAcrossLedgerInstances() {
        EditorSessionSnapshot session = snapshot(1, 11, 0, true);
        CommitLedger firstLedger = new CommitLedger(() -> "same");
        CommitRecord first = firstLedger.reserve(
                session,
                OperationSource.VOICE,
                "text",
                new CommitRecord.RawTranscript.Absent()).publish();
        assertSame(first,
                firstLedger.consume(first.commitId(), session).orElseThrow());
        CommitRecord afterConsume = firstLedger.reserve(
                session,
                OperationSource.VOICE,
                "text",
                new CommitRecord.RawTranscript.Absent()).publish();

        CommitLedger secondLedger = new CommitLedger(() -> "same");
        CommitRecord otherLedger = secondLedger.reserve(
                session,
                OperationSource.VOICE,
                "text",
                new CommitRecord.RawTranscript.Absent()).publish();

        assertNotEquals(first.commitId(), afterConsume.commitId());
        assertNotEquals(first.commitId(), otherLedger.commitId());
        assertNotEquals(afterConsume.commitId(), otherLedger.commitId());
        assertTrue(first.commitId().endsWith("-same"));
        assertTrue(afterConsume.commitId().endsWith("-same"));
        assertTrue(otherLedger.commitId().endsWith("-same"));
    }

    @Test
    public void oneActiveReservationPreventsReentrantReservationUntilAbort() {
        AtomicInteger ids = new AtomicInteger();
        CommitLedger ledger = new CommitLedger(() -> "id-" + ids.incrementAndGet());
        EditorSessionSnapshot session = snapshot(1, 11, 0, true);
        CommitLedger.Reservation first = ledger.reserve(
                session,
                OperationSource.VOICE,
                "first",
                new CommitRecord.RawTranscript.Absent());

        assertTrue(ledger.reserve(
                session,
                OperationSource.VOICE,
                "reentrant",
                new CommitRecord.RawTranscript.Absent()) == null);
        assertEquals(1, ids.get());
        first.abort();

        CommitLedger.Reservation afterAbort = ledger.reserve(
                session,
                OperationSource.VOICE,
                "after",
                new CommitRecord.RawTranscript.Absent());
        assertEquals(2, ids.get());
        assertTrue(afterAbort.publish().commitId().endsWith("-id-2"));
    }

    @Test
    public void allLedgerAccessIsOwnerThreadConfined() throws Exception {
        CommitLedger ledger = new CommitLedger(() -> "id");
        EditorSessionSnapshot session = snapshot(1, 11, 0, true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertOffOwner(executor, () -> ledger.reserve(
                    session,
                    OperationSource.VOICE,
                    "private",
                    new CommitRecord.RawTranscript.Absent()));
            assertOffOwner(executor, () -> ledger.resolve("id", session));
            assertOffOwner(executor, () -> ledger.consume("id", session));
            assertOffOwner(executor, ledger::revokeAll);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(0, ledger.sizeForTest());
        assertTrue(ledger.reserve(
                session,
                OperationSource.VOICE,
                "owner",
                new CommitRecord.RawTranscript.Absent()).publish().commitId().endsWith("-id"));
    }

    @Test
    public void shapeIsOneRecordSlotWithNoAmbiguousLatestLookupApi() {
        int commitRecordFields = 0;
        for (Field field : CommitLedger.class.getDeclaredFields()) {
            if (field.getType() == CommitRecord.class) commitRecordFields++;
            assertFalse(Map.class.isAssignableFrom(field.getType()));
            assertFalse(Collection.class.isAssignableFrom(field.getType()));
        }
        assertEquals(1, commitRecordFields);

        for (Method method : CommitLedger.class.getDeclaredMethods()) {
            String lower = method.getName().toLowerCase();
            assertFalse(lower.contains("latest"));
            assertFalse(lower.equals("last"));
            assertFalse(lower.equals("peek"));
            assertFalse(lower.equals("take"));
            assertFalse(lower.equals("poll"));
            assertFalse(lower.equals("current"));
        }
    }

    private static EditorSessionSnapshot snapshot(
            long epoch, long token, int cursor, boolean learningAllowed) {
        return snapshotWithMetadata(epoch, token, cursor, learningAllowed, 1, 0);
    }

    private static EditorSessionSnapshot snapshotWithMetadata(
            long epoch,
            long token,
            int cursor,
            boolean learningAllowed,
            int inputType,
            int imeOptions) {
        return EditorSessionSnapshot.capture(
                epoch,
                token,
                "app",
                7,
                FieldKind.GENERAL,
                inputType,
                imeOptions,
                new TextRange(cursor, cursor),
                "",
                "",
                "",
                learningAllowed,
                false,
                1);
    }

    private static void assertOffOwner(ExecutorService executor, Runnable action)
            throws Exception {
        try {
            executor.submit(action).get();
            fail("expected owner-thread rejection");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
            assertFalse(expected.getCause().toString().contains("private"));
        }
    }
}
