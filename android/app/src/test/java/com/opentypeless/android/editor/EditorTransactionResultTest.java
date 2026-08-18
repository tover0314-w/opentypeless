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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class EditorTransactionResultTest {
    private static final TransactionFailure APPLY_REJECTED = failure(
            TransactionFailurePhase.APPLY,
            TransactionFailureStep.INSERT_TEXT,
            TransactionFailureKind.EDITOR_REJECTED);
    private static final TransactionFailure ROLLBACK_REJECTED = failure(
            TransactionFailurePhase.ROLLBACK,
            TransactionFailureStep.RESTORE_TEXT,
            TransactionFailureKind.EDITOR_REJECTED);

    @Test
    public void sealedModelContainsExactlyFiveImmutableContentFreeResults() {
        assertTrue(EditorTransactionResult.class.isSealed());
        assertEquals(
                Set.of(
                        EditorTransactionResult.Applied.class,
                        EditorTransactionResult.TargetChanged.class,
                        EditorTransactionResult.Rejected.class,
                        EditorTransactionResult.RolledBack.class,
                        EditorTransactionResult.RollbackFailed.class),
                Set.of(EditorTransactionResult.class.getPermittedSubclasses()));

        for (Class<?> variant : EditorTransactionResult.class.getPermittedSubclasses()) {
            assertTrue(variant.isRecord());
            assertTrue(Modifier.isFinal(variant.getModifiers()));
            assertFalse(Serializable.class.isAssignableFrom(variant));
            assertTrue(Arrays.stream(variant.getAnnotations())
                    .noneMatch(annotation -> annotation.annotationType().getName()
                            .startsWith("kotlinx.serialization")));
        }

        assertEquals(0, EditorTransactionResult.Applied.class.getRecordComponents().length);
        assertEquals(new EditorTransactionResult.Applied(),
                new EditorTransactionResult.Applied());
    }

    @Test
    public void classificationsHaveStableClosedValues() {
        assertArrayEquals(
                new TargetChangeReason[]{
                        TargetChangeReason.NO_ACTIVE_SESSION,
                        TargetChangeReason.SESSION_REVOKED,
                        TargetChangeReason.EPOCH_CHANGED,
                        TargetChangeReason.CONNECTION_CHANGED,
                        TargetChangeReason.EDITOR_METADATA_CHANGED,
                        TargetChangeReason.SELECTION_CHANGED,
                        TargetChangeReason.SELECTED_TEXT_CHANGED,
                        TargetChangeReason.SURROUNDING_TEXT_CHANGED,
                        TargetChangeReason.SECURITY_STATE_CHANGED,
                        TargetChangeReason.EVIDENCE_UNAVAILABLE
                },
                TargetChangeReason.values());
        assertArrayEquals(
                new RejectionReason[]{
                        RejectionReason.OPERATION_NOT_SUPPORTED,
                        RejectionReason.POLICY_DENIED,
                        RejectionReason.SENSITIVE_FIELD,
                        RejectionReason.COMPOSITION_OWNER_MISMATCH,
                        RejectionReason.COMPOSITION_REVISION_MISMATCH,
                        RejectionReason.COMMIT_RECORD_UNAVAILABLE,
                        RejectionReason.EDITOR_ACTION_UNAVAILABLE,
                        RejectionReason.ROLLBACK_PRECONDITION_UNAVAILABLE,
                        RejectionReason.BATCH_EDIT_REJECTED
                },
                RejectionReason.values());
        assertArrayEquals(
                new TransactionFailurePhase[]{
                        TransactionFailurePhase.APPLY,
                        TransactionFailurePhase.ROLLBACK
                },
                TransactionFailurePhase.values());
        assertArrayEquals(
                new TransactionFailureStep[]{
                        TransactionFailureStep.DELETE_TEXT,
                        TransactionFailureStep.INSERT_TEXT,
                        TransactionFailureStep.SET_COMPOSITION,
                        TransactionFailureStep.FINISH_COMPOSITION,
                        TransactionFailureStep.SET_SELECTION,
                        TransactionFailureStep.PERFORM_EDITOR_ACTION,
                        TransactionFailureStep.RESTORE_TEXT,
                        TransactionFailureStep.RESTORE_SELECTION,
                        TransactionFailureStep.RESTORE_COMPOSITION,
                        TransactionFailureStep.VERIFY_EDITOR_STATE
                },
                TransactionFailureStep.values());
        assertArrayEquals(
                new TransactionFailureKind[]{
                        TransactionFailureKind.EDITOR_REJECTED,
                        TransactionFailureKind.RUNTIME_FAILURE,
                        TransactionFailureKind.OUTCOME_UNCONFIRMED,
                        TransactionFailureKind.TARGET_INVALIDATED,
                        TransactionFailureKind.NOT_SAFE_TO_ATTEMPT
                },
                TransactionFailureKind.values());
    }

    @Test
    public void failurePhaseAndStepCompatibilityIsAnExactMatrix() {
        for (TransactionFailurePhase phase : TransactionFailurePhase.values()) {
            for (TransactionFailureStep step : TransactionFailureStep.values()) {
                Runnable construction = () -> failure(
                        phase, step, TransactionFailureKind.RUNTIME_FAILURE);
                if (step.phase() == phase) {
                    TransactionFailure accepted = failure(
                            phase, step, TransactionFailureKind.RUNTIME_FAILURE);
                    assertEquals(phase, accepted.phase());
                    assertEquals(step, accepted.step());
                } else {
                    assertIllegal(construction);
                }
            }
        }

        assertNullRejected(() -> new TransactionFailure(
                null,
                TransactionFailureStep.INSERT_TEXT,
                TransactionFailureKind.EDITOR_REJECTED));
        assertNullRejected(() -> new TransactionFailure(
                TransactionFailurePhase.APPLY,
                null,
                TransactionFailureKind.EDITOR_REJECTED));
        assertNullRejected(() -> new TransactionFailure(
                TransactionFailurePhase.APPLY,
                TransactionFailureStep.INSERT_TEXT,
                null));
        for (TransactionFailureStep step : TransactionFailureStep.values()) {
            Runnable construction = () -> failure(
                    step.phase(), step, TransactionFailureKind.NOT_SAFE_TO_ATTEMPT);
            if (step.isRestoreStep()) {
                construction.run();
            } else {
                assertIllegal(construction);
            }
        }
    }

    @Test
    public void everyClassificationRetainsValueSemanticsWithoutMessages() {
        for (TargetChangeReason reason : TargetChangeReason.values()) {
            EditorTransactionResult.TargetChanged first =
                    new EditorTransactionResult.TargetChanged(reason);
            EditorTransactionResult.TargetChanged equal =
                    new EditorTransactionResult.TargetChanged(reason);
            assertEquals(first, equal);
            assertEquals(first.hashCode(), equal.hashCode());
            assertTrue(first.toString().contains(reason.name()));
        }
        for (RejectionReason reason : RejectionReason.values()) {
            EditorTransactionResult.Rejected first =
                    new EditorTransactionResult.Rejected(reason);
            EditorTransactionResult.Rejected equal =
                    new EditorTransactionResult.Rejected(reason);
            assertEquals(first, equal);
            assertEquals(first.hashCode(), equal.hashCode());
            assertTrue(first.toString().contains(reason.name()));
        }
        for (TransactionFailureKind kind : TransactionFailureKind.values()) {
            TransactionFailurePhase phase = kind == TransactionFailureKind.NOT_SAFE_TO_ATTEMPT
                    ? TransactionFailurePhase.ROLLBACK
                    : TransactionFailurePhase.APPLY;
            TransactionFailureStep step = kind == TransactionFailureKind.NOT_SAFE_TO_ATTEMPT
                    ? TransactionFailureStep.RESTORE_TEXT
                    : TransactionFailureStep.DELETE_TEXT;
            TransactionFailure first = failure(
                    phase, step, kind);
            TransactionFailure equal = failure(
                    phase, step, kind);
            assertEquals(first, equal);
            assertEquals(first.hashCode(), equal.hashCode());
            assertTrue(first.toString().contains(kind.name()));
        }
        assertNotEquals(
                new EditorTransactionResult.TargetChanged(TargetChangeReason.EPOCH_CHANGED),
                new EditorTransactionResult.TargetChanged(TargetChangeReason.CONNECTION_CHANGED));
    }

    @Test
    public void resultPayloadsRejectNullAndEnforceRollbackPhaseInvariants() {
        assertNullRejected(() -> new EditorTransactionResult.TargetChanged(null));
        assertNullRejected(() -> new EditorTransactionResult.Rejected(null));
        assertNullRejected(() -> new EditorTransactionResult.RolledBack(null));
        assertNullRejected(() -> new EditorTransactionResult.RollbackFailed(
                null, ROLLBACK_REJECTED));
        assertNullRejected(() -> new EditorTransactionResult.RollbackFailed(
                APPLY_REJECTED, null));

        assertEquals(APPLY_REJECTED,
                new EditorTransactionResult.RolledBack(APPLY_REJECTED).originalFailure());
        assertIllegal(() -> new EditorTransactionResult.RolledBack(ROLLBACK_REJECTED));
        assertIllegal(() -> new EditorTransactionResult.RollbackFailed(
                ROLLBACK_REJECTED, ROLLBACK_REJECTED));
        assertIllegal(() -> new EditorTransactionResult.RollbackFailed(
                APPLY_REJECTED, APPLY_REJECTED));
    }

    @Test
    public void uncertainMutatorOutcomeCannotBeRepresentedAsRejected() {
        TransactionFailure uncertainApply = failure(
                TransactionFailurePhase.APPLY,
                TransactionFailureStep.FINISH_COMPOSITION,
                TransactionFailureKind.OUTCOME_UNCONFIRMED);
        TransactionFailure unsafeRollback = failure(
                TransactionFailurePhase.ROLLBACK,
                TransactionFailureStep.RESTORE_TEXT,
                TransactionFailureKind.NOT_SAFE_TO_ATTEMPT);
        EditorTransactionResult result = new EditorTransactionResult.RollbackFailed(
                uncertainApply, unsafeRollback);

        assertTrue(result instanceof EditorTransactionResult.RollbackFailed);
        assertFalse(result instanceof EditorTransactionResult.Rejected);
        assertEquals(uncertainApply,
                ((EditorTransactionResult.RollbackFailed) result).originalFailure());

        // A subsequently complete and verified restoration may safely classify the same original
        // uncertain apply as RolledBack.
        assertEquals(uncertainApply,
                new EditorTransactionResult.RolledBack(uncertainApply).originalFailure());
    }

    @Test
    public void resultContractCannotCarryCommitRecordTextThrowableOrAndroidCapability() {
        Map<Class<?>, List<Class<?>>> exactComponents = Map.of(
                EditorTransactionResult.Applied.class, List.of(),
                EditorTransactionResult.TargetChanged.class, List.of(TargetChangeReason.class),
                EditorTransactionResult.Rejected.class, List.of(RejectionReason.class),
                EditorTransactionResult.RolledBack.class, List.of(TransactionFailure.class),
                EditorTransactionResult.RollbackFailed.class,
                List.of(TransactionFailure.class, TransactionFailure.class));

        for (Map.Entry<Class<?>, List<Class<?>>> entry : exactComponents.entrySet()) {
            List<Class<?>> actual = Arrays.stream(entry.getKey().getRecordComponents())
                    .map(RecordComponent::getType)
                    .toList();
            assertEquals(entry.getValue(), actual);
        }
        assertEquals(
                List.of(
                        TransactionFailurePhase.class,
                        TransactionFailureStep.class,
                        TransactionFailureKind.class),
                Arrays.stream(TransactionFailure.class.getRecordComponents())
                        .map(RecordComponent::getType)
                        .toList());

        Set<String> componentTypes = Arrays.stream(EditorTransactionResult.class
                        .getPermittedSubclasses())
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(component -> component.getType().getName())
                .collect(java.util.stream.Collectors.toSet());
        componentTypes.addAll(Arrays.stream(TransactionFailure.class.getRecordComponents())
                .map(component -> component.getType().getName())
                .toList());
        assertTrue(componentTypes.stream().noneMatch(name ->
                name.equals(String.class.getName())
                        || name.equals(Object.class.getName())
                        || name.equals(Throwable.class.getName())
                        || name.startsWith("java.util.Optional")
                        || name.startsWith("java.util.Collection")
                        || name.startsWith("android.")
                        || name.startsWith("androidx.")
                        || name.contains("InputConnection")
                        || name.contains("CommitRecord")));
    }

    @Test
    public void hostileExceptionBodyHasNoPathIntoFailureOrResultDiagnostics() {
        String sentinel = "PRIVATE-OEM-EXCEPTION-BODY";
        RuntimeException hostile = new RuntimeException(sentinel);
        EditorTransactionResult result = new EditorTransactionResult.RollbackFailed(
                failure(
                        TransactionFailurePhase.APPLY,
                        TransactionFailureStep.INSERT_TEXT,
                        TransactionFailureKind.OUTCOME_UNCONFIRMED),
                failure(
                        TransactionFailurePhase.ROLLBACK,
                        TransactionFailureStep.RESTORE_TEXT,
                        TransactionFailureKind.NOT_SAFE_TO_ATTEMPT));

        assertFalse(result.toString().contains(sentinel));
        assertFalse(((EditorTransactionResult.RollbackFailed) result)
                .originalFailure().toString().contains(sentinel));
        assertFalse(((EditorTransactionResult.RollbackFailed) result)
                .rollbackFailure().toString().contains(sentinel));
        assertFalse(Arrays.stream(EditorTransactionResult.class.getPermittedSubclasses())
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .anyMatch(component -> Throwable.class.isAssignableFrom(component.getType())));
        assertEquals(sentinel, hostile.getMessage()); // Sentinel exists only outside the model.
    }

    @Test
    public void resultContractExposesNoArbitraryExecutionMethod() {
        Set<String> forbiddenMethodNames = Set.of(
                "commitText",
                "setComposingText",
                "finishComposingText",
                "deleteSurroundingText",
                "sendKeyEvent",
                "launchIntent",
                "execute");
        assertTrue(Arrays.stream(EditorTransactionResult.class.getDeclaredMethods())
                .noneMatch(method -> forbiddenMethodNames.contains(method.getName())));
    }

    private static TransactionFailure failure(
            TransactionFailurePhase phase,
            TransactionFailureStep step,
            TransactionFailureKind kind) {
        return new TransactionFailure(phase, step, kind);
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
