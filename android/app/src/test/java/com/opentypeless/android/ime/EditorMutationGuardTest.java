package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;

import android.view.inputmethod.InputConnection;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

public final class EditorMutationGuardTest {
    @Test
    public void rejectedDeleteNeverCommitsReplacement() {
        FakeInputConnection fake = new FakeInputConnection();
        fake.deleteAccepted = false;

        assertEquals(
                OpenTypelessImeService.EditorMutationResult.DELETE_REJECTED,
                replace(fake, 4, "raw", "edited"));
        assertEquals(1, fake.deleteCalls);
        assertEquals(0, fake.commitCalls);
        assertEquals(1, fake.endCalls);
    }

    @Test
    public void rejectedCommitRollsBackDeletedVoiceText() {
        FakeInputConnection fake = new FakeInputConnection(false, true);

        assertEquals(
                OpenTypelessImeService.EditorMutationResult.ROLLED_BACK,
                replace(fake, 6, "raw", "edited"));
        assertEquals(List.of("raw", "edited"), fake.committedTexts);
        assertEquals(2, fake.commitCalls);
    }

    @Test
    public void failedRollbackIsReportedWithoutThrowing() {
        FakeInputConnection fake = new FakeInputConnection(false, false);

        assertEquals(
                OpenTypelessImeService.EditorMutationResult.ROLLBACK_FAILED,
                replace(fake, 6, "raw", "edited"));
        assertEquals(List.of("raw", "edited"), fake.committedTexts);
    }

    @Test
    public void directCommitRejectionDoesNotAttemptRollback() {
        FakeInputConnection fake = new FakeInputConnection(false);

        assertEquals(
                OpenTypelessImeService.EditorMutationResult.COMMIT_REJECTED,
                replace(fake, 0, "voice result", ""));
        assertEquals(List.of("voice result"), fake.committedTexts);
    }

    @Test
    public void runtimeFailureIsContainedAndDoesNotSpeculateWithAnotherWrite() {
        FakeInputConnection fake = new FakeInputConnection();
        fake.throwOnDelete = true;

        assertEquals(
                OpenTypelessImeService.EditorMutationResult.CONNECTION_ERROR,
                replace(fake, 6, "raw", "edited"));
        assertEquals(0, fake.commitCalls);
        assertEquals(1, fake.endCalls);
    }

    @Test
    public void runtimeCommitFailureIsContainedWithoutSpeculativeRollback() {
        FakeInputConnection fake = new FakeInputConnection();
        fake.throwOnCommitCall = 1;

        assertEquals(
                OpenTypelessImeService.EditorMutationResult.CONNECTION_ERROR,
                replace(fake, 6, "raw", "edited"));
        assertEquals(1, fake.commitCalls);
        assertEquals(List.of(), fake.committedTexts);
        assertEquals(1, fake.endCalls);
    }

    @Test
    public void cleanupFailureCannotTurnAnAppliedMutationIntoImeCrash() {
        FakeInputConnection fake = new FakeInputConnection(true);
        fake.throwOnEnd = true;

        assertEquals(
                OpenTypelessImeService.EditorMutationResult.APPLIED,
                replace(fake, 0, "voice result", ""));
        assertEquals(1, fake.endCalls);
    }

    @Test
    public void provisionalCompositionCanBeRevisedThenCommittedAsFinalText() {
        FakeInputConnection fake = new FakeInputConnection(true);

        assertEquals(true, OpenTypelessImeService.guardedSetComposingText(
                fake.connection(), "我现在在用百"));
        assertEquals(true, OpenTypelessImeService.guardedSetComposingText(
                fake.connection(), "我现在正在使用百度输入法"));
        assertEquals(
                OpenTypelessImeService.EditorMutationResult.APPLIED,
                OpenTypelessImeService.guardedCommitComposition(
                        fake.connection(), "我现在正在使用百度输入法。"));

        assertEquals(List.of("我现在在用百", "我现在正在使用百度输入法"),
                fake.composingTexts);
        assertEquals(List.of("我现在正在使用百度输入法。"), fake.committedTexts);
    }

    @Test
    public void rejectedCompositionDoesNotBecomeACommittedMutation() {
        FakeInputConnection fake = new FakeInputConnection();
        fake.composingAccepted = false;

        assertEquals(false, OpenTypelessImeService.guardedSetComposingText(
                fake.connection(), "draft"));
        assertEquals(0, fake.commitCalls);
        assertEquals(1, fake.endCalls);
    }

    private static OpenTypelessImeService.EditorMutationResult replace(
            FakeInputConnection fake,
            int deleteBefore,
            String replacement,
            String rollback) {
        return OpenTypelessImeService.guardedReplace(
                fake.connection(), deleteBefore, replacement, rollback);
    }

    private static final class FakeInputConnection implements InvocationHandler {
        final Deque<Boolean> commitResults = new ArrayDeque<>();
        final List<String> committedTexts = new ArrayList<>();
        final List<String> composingTexts = new ArrayList<>();
        boolean deleteAccepted = true;
        boolean composingAccepted = true;
        boolean throwOnDelete;
        boolean throwOnEnd;
        int throwOnCommitCall = -1;
        int deleteCalls;
        int commitCalls;
        int endCalls;

        FakeInputConnection(Boolean... commitResults) {
            this.commitResults.addAll(Arrays.asList(commitResults));
        }

        InputConnection connection() {
            return (InputConnection) Proxy.newProxyInstance(
                    InputConnection.class.getClassLoader(),
                    new Class<?>[]{InputConnection.class},
                    this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "beginBatchEdit" -> true;
                case "endBatchEdit" -> {
                    endCalls++;
                    if (throwOnEnd) throw new IllegalStateException("editor disappeared");
                    yield true;
                }
                case "deleteSurroundingTextInCodePoints" -> {
                    deleteCalls++;
                    if (throwOnDelete) throw new IllegalStateException("editor disappeared");
                    yield deleteAccepted;
                }
                case "commitText" -> {
                    commitCalls++;
                    if (commitCalls == throwOnCommitCall) {
                        throw new IllegalStateException("editor disappeared");
                    }
                    committedTexts.add(String.valueOf(arguments[0]));
                    yield commitResults.isEmpty() || commitResults.removeFirst();
                }
                case "setComposingText" -> {
                    composingTexts.add(String.valueOf(arguments[0]));
                    yield composingAccepted;
                }
                case "finishComposingText" -> true;
                case "toString" -> "FakeInputConnection";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> defaultValue(method.getReturnType());
            };
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            if (type == char.class) return '\0';
            return null;
        }
    }
}
