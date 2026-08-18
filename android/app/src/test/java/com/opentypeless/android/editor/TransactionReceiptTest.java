package com.opentypeless.android.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.opentypeless.android.context.FieldKind;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Set;
import org.junit.Test;

public final class TransactionReceiptTest {
    @Test
    public void withoutCommitCarriesEveryTerminalResultIncludingZeroFieldApplied() {
        EditorTransactionResult[] results = {
                new EditorTransactionResult.Applied(),
                new EditorTransactionResult.TargetChanged(TargetChangeReason.EPOCH_CHANGED),
                new EditorTransactionResult.Rejected(RejectionReason.POLICY_DENIED),
                new EditorTransactionResult.RolledBack(new TransactionFailure(
                        TransactionFailurePhase.APPLY,
                        TransactionFailureStep.INSERT_TEXT,
                        TransactionFailureKind.EDITOR_REJECTED)),
                new EditorTransactionResult.RollbackFailed(
                        new TransactionFailure(
                                TransactionFailurePhase.APPLY,
                                TransactionFailureStep.INSERT_TEXT,
                                TransactionFailureKind.RUNTIME_FAILURE),
                        new TransactionFailure(
                                TransactionFailurePhase.ROLLBACK,
                                TransactionFailureStep.VERIFY_EDITOR_STATE,
                                TransactionFailureKind.OUTCOME_UNCONFIRMED))
        };

        assertEquals(0, EditorTransactionResult.Applied.class.getRecordComponents().length);
        for (EditorTransactionResult result : results) {
            TransactionReceipt.WithoutCommit receipt =
                    new TransactionReceipt.WithoutCommit(result);
            assertSame(result, receipt.result());
        }
    }

    @Test
    public void committedRequiresExactAppliedAndExactRecordInOneImmutableEnvelope() {
        EditorTransactionResult.Applied applied = new EditorTransactionResult.Applied();
        CommitRecord record = record("private inserted", "private raw");
        TransactionReceipt.Committed committed =
                new TransactionReceipt.Committed(applied, record);

        assertSame(applied, committed.result());
        assertSame(record, committed.record());

        RecordComponent[] components = TransactionReceipt.Committed.class.getRecordComponents();
        assertEquals(2, components.length);
        assertEquals("result", components[0].getName());
        assertEquals(EditorTransactionResult.Applied.class, components[0].getType());
        assertEquals("record", components[1].getName());
        assertEquals(CommitRecord.class, components[1].getType());

        assertNullRejected(() -> new TransactionReceipt.Committed(null, record));
        assertNullRejected(() -> new TransactionReceipt.Committed(applied, null));
        assertNullRejected(() -> new TransactionReceipt.WithoutCommit(null));
    }

    @Test
    public void receiptFamilyIsClosedFinalNonSerializableAndHasNoMutableLatestSlot() {
        assertTrue(TransactionReceipt.class.isSealed());
        assertEquals(
                Set.of(
                        TransactionReceipt.WithoutCommit.class,
                        TransactionReceipt.Committed.class),
                Set.of(TransactionReceipt.class.getPermittedSubclasses()));
        assertFalse(Serializable.class.isAssignableFrom(TransactionReceipt.class));

        for (Class<?> variant : TransactionReceipt.class.getPermittedSubclasses()) {
            assertTrue(variant.isRecord());
            assertTrue(Modifier.isFinal(variant.getModifiers()));
            assertFalse(Serializable.class.isAssignableFrom(variant));
            for (java.lang.reflect.Field field : variant.getDeclaredFields()) {
                assertTrue(Modifier.isPrivate(field.getModifiers()));
                assertTrue(Modifier.isFinal(field.getModifiers()));
                assertFalse(Modifier.isStatic(field.getModifiers()));
                assertFalse(field.getName().toLowerCase().contains("latest"));
            }
            for (java.lang.reflect.Method method : variant.getDeclaredMethods()) {
                assertFalse(method.getName().toLowerCase().contains("latest"));
            }
        }
    }

    @Test
    public void committedDiagnosticDoesNotExposeAnyRecordContentOrIdentity() {
        String inserted = "receipt-private-inserted";
        String raw = "receipt-private-raw";
        CommitRecord record = record(inserted, raw);
        TransactionReceipt.Committed receipt = new TransactionReceipt.Committed(
                new EditorTransactionResult.Applied(), record);

        String diagnostic = receipt.toString();
        assertFalse(diagnostic.contains(record.commitId()));
        assertFalse(diagnostic.contains(inserted));
        assertFalse(diagnostic.contains(raw));
        assertFalse(diagnostic.contains("com.receipt.private"));
        assertFalse(diagnostic.contains(record.insertedTextFingerprint().sha256Hex()));
    }

    private static CommitRecord record(String insertedText, String rawText) {
        EditorSessionSnapshot origin = EditorSessionSnapshot.capture(
                1,
                2,
                "com.receipt.private",
                3,
                FieldKind.GENERAL,
                4,
                5,
                new TextRange(6, 6),
                "",
                "before",
                "after",
                true,
                false,
                7);
        return CommitRecord.create(
                "receipt-id",
                OperationSource.VOICE,
                origin,
                insertedText,
                new CommitRecord.RawTranscript.Present(rawText));
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
