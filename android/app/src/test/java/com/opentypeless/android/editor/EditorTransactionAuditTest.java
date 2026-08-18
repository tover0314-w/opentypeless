package com.opentypeless.android.editor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.Parcelable;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.List;
import org.junit.Test;

public final class EditorTransactionAuditTest {
    @Test
    public void exactShapeIsClosedImmutableAndContentFree() {
        assertTrue(EditorTransactionAudit.class.isRecord());
        assertTrue(Modifier.isFinal(EditorTransactionAudit.class.getModifiers()));
        assertFalse(Serializable.class.isAssignableFrom(EditorTransactionAudit.class));
        assertFalse(Parcelable.class.isAssignableFrom(EditorTransactionAudit.class));

        RecordComponent[] components = EditorTransactionAudit.class.getRecordComponents();
        assertEquals(3, components.length);
        assertComponent(components[0], "source", OperationSource.class);
        assertComponent(components[1], "operationKind", EditorOperationKind.class);
        assertComponent(components[2], "result", EditorTransactionResult.class);

        assertArrayEquals(
                new EditorOperationKind[]{
                        EditorOperationKind.SET_COMPOSITION,
                        EditorOperationKind.COMMIT_COMPOSITION,
                        EditorOperationKind.INSERT_TEXT,
                        EditorOperationKind.REPLACE_SELECTION,
                        EditorOperationKind.REPLACE_LAST_COMMIT,
                        EditorOperationKind.DELETE_BEFORE_CURSOR,
                        EditorOperationKind.PERFORM_EDITOR_ACTION
                },
                EditorOperationKind.values());

        for (var field : EditorTransactionAudit.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            assertFalse(type == String.class || CharSequence.class.isAssignableFrom(type));
            assertFalse(type == EditorOperation.class);
            assertFalse(type == EditorSessionSnapshot.class);
            assertFalse(type == TextFingerprint.class);
            assertFalse(type == CommitRecord.class);
            assertFalse(type == TransactionReceipt.class);
            assertFalse(Throwable.class.isAssignableFrom(type));
            assertFalse(type.getName().startsWith("android.view.inputmethod."));
        }
    }

    @Test
    public void allSourcesKindsAndTerminalResultsRoundTripWithoutPayload() {
        TransactionFailure original = new TransactionFailure(
                TransactionFailurePhase.APPLY,
                TransactionFailureStep.INSERT_TEXT,
                TransactionFailureKind.EDITOR_REJECTED);
        TransactionFailure rollback = new TransactionFailure(
                TransactionFailurePhase.ROLLBACK,
                TransactionFailureStep.VERIFY_EDITOR_STATE,
                TransactionFailureKind.OUTCOME_UNCONFIRMED);
        List<EditorTransactionResult> results = List.of(
                new EditorTransactionResult.Applied(),
                new EditorTransactionResult.TargetChanged(TargetChangeReason.EPOCH_CHANGED),
                new EditorTransactionResult.Rejected(RejectionReason.OPERATION_NOT_SUPPORTED),
                new EditorTransactionResult.RolledBack(original),
                new EditorTransactionResult.RollbackFailed(original, rollback));

        for (OperationSource source : OperationSource.values()) {
            for (EditorOperationKind kind : EditorOperationKind.values()) {
                for (EditorTransactionResult result : results) {
                    EditorTransactionAudit audit =
                            new EditorTransactionAudit(source, kind, result);
                    assertEquals(source, audit.source());
                    assertEquals(kind, audit.operationKind());
                    assertTrue(audit.result() == result);
                    assertNotNull(audit.toString());
                }
            }
        }
    }

    @Test
    public void nullsAreRejectedAndToStringContainsOnlyClosedMetadata() {
        EditorTransactionResult.Applied applied = new EditorTransactionResult.Applied();
        assertThrows(
                NullPointerException.class,
                () -> new EditorTransactionAudit(
                        null, EditorOperationKind.INSERT_TEXT, applied));
        assertThrows(
                NullPointerException.class,
                () -> new EditorTransactionAudit(
                        OperationSource.VOICE, null, applied));
        assertThrows(
                NullPointerException.class,
                () -> new EditorTransactionAudit(
                        OperationSource.VOICE, EditorOperationKind.INSERT_TEXT, null));

        EditorTransactionAudit audit = new EditorTransactionAudit(
                OperationSource.VOICE,
                EditorOperationKind.INSERT_TEXT,
                applied);
        assertEquals(
                "EditorTransactionAudit{source=VOICE, operationKind=INSERT_TEXT, result=Applied[]}",
                audit.toString());
        assertFalse(audit.toString().contains("text="));
        assertFalse(audit.toString().contains("commit"));
        assertFalse(audit.toString().contains("session"));
        assertFalse(audit.toString().contains("selection"));
    }

    private static void assertComponent(
            RecordComponent component, String name, Class<?> type) {
        assertEquals(name, component.getName());
        assertEquals(type, component.getType());
    }
}
