package com.opentypeless.android.speech.delivery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class EditorProjectionTest {
    @Test
    public void shortDraftReplacesCompositionAndCommitsExactlyOnce() {
        FakeConnection connection = new FakeConnection("note:", "!");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);

        assertEquals(
                ProjectionOutcome.APPLIED,
                projection.project(ProjectionDocument.shortDraft("hell")).outcome());
        assertEquals("note:hell!", connection.text());
        assertEquals(
                ProjectionOutcome.APPLIED,
                projection.project(ProjectionDocument.shortDraft("hello")).outcome());
        assertEquals("note:hello!", connection.text());
        int operations = connection.operations().size();
        assertEquals(
                ProjectionOutcome.UNCHANGED,
                projection.project(ProjectionDocument.shortDraft("hello")).outcome());
        assertEquals(operations, connection.operations().size());

        ProjectionResult finished =
                projection.finish(ProjectionDocument.shortDraft("hello."));

        assertEquals(ProjectionOutcome.COMMITTED, finished.outcome());
        assertEquals(ProjectionState.COMMITTED, projection.state());
        assertEquals("note:hello.!", connection.text());
        assertEquals(1, connection.operations().stream()
                .filter("finish"::equals)
                .count());
    }

    @Test
    public void longDraftCommitsOnlyMonotonicSealedPrefixAndKeepsTailComposing() {
        FakeConnection connection = new FakeConnection("A:", "!");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.LONG_DICTATION);
        projection.project(new ProjectionDocument("", "hello world"));

        ProjectionResult prefix =
                projection.project(new ProjectionDocument("hello ", "world"));

        assertEquals(ProjectionOutcome.APPLIED, prefix.outcome());
        assertEquals("A:hello world!", connection.text());
        assertTrue(connection.composing());
        assertEquals("world", connection.composingText());

        ProjectionResult finished =
                projection.finish(new ProjectionDocument("hello world.", ""));

        assertEquals(ProjectionOutcome.COMMITTED, finished.outcome());
        assertEquals("A:hello world.!", connection.text());
        assertFalse(connection.composing());
        assertTrue(connection.operations().contains("set:hello "));
        assertTrue(connection.operations().contains("set:world."));
    }

    @Test
    public void nonMonotonicCommittedPrefixStopsWritesAndMakesWholeDraftRecoverable() {
        FakeConnection connection = new FakeConnection("", "");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.LONG_DICTATION);
        projection.project(new ProjectionDocument("first ", "tail"));
        int operations = connection.operations().size();

        ProjectionResult rejected =
                projection.project(new ProjectionDocument("changed ", "tail"));

        assertEquals(ProjectionState.RECOVERABLE, rejected.state());
        assertEquals("changed tail", rejected.recoverableText().orElseThrow());
        assertEquals(operations, connection.operations().size());
    }

    @Test
    public void cursorMoveFreezesAutomaticWritesAndNeverTargetsNewLocation() {
        FakeConnection connection = new FakeConnection("before ", " after");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);
        projection.project(ProjectionDocument.shortDraft("draft"));
        connection.moveSelection(0);
        int operations = connection.operations().size();

        ProjectionResult result =
                projection.project(ProjectionDocument.shortDraft("draft later"));

        assertEquals(ProjectionOutcome.REJECTED_TARGET, result.outcome());
        assertEquals(ProjectionState.RECOVERABLE, result.state());
        assertEquals("draft later", result.recoverableText().orElseThrow());
        assertEquals(operations, connection.operations().size());
        assertFalse(connection.text().startsWith("draft later"));
    }

    @Test
    public void sameConnectionAndRepeatedTextCannotCrossAFieldBoundary() {
        FakeConnection connection = new FakeConnection("same", "same");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);
        projection.project(ProjectionDocument.shortDraft("voice"));
        connection.changeField(99, "samevoice", "same");
        int operations = connection.operations().size();

        ProjectionResult result =
                projection.project(ProjectionDocument.shortDraft("new voice"));

        assertEquals(ProjectionOutcome.REJECTED_TARGET, result.outcome());
        assertEquals(operations, connection.operations().size());
        assertEquals("samevoicesame", connection.text());
    }

    @Test
    public void falseWithoutMutationCreatesRecoverableCopyAndDoesNotRetry() {
        FakeConnection connection = new FakeConnection("", "");
        connection.setReturn = false;
        connection.applySet = false;
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);

        ProjectionResult result =
                projection.project(ProjectionDocument.shortDraft("recover me"));

        assertEquals(ProjectionOutcome.RECOVERY_REQUIRED, result.outcome());
        assertFalse(result.mutationUncertain());
        assertEquals("recover me", result.recoverableText().orElseThrow());
        assertEquals("", connection.text());
        assertEquals(
                ProjectionOutcome.REJECTED_STATE,
                projection.project(ProjectionDocument.shortDraft("second attempt")).outcome());
    }

    @Test
    public void falseAfterMutationIsMarkedUncertainAndNeverAutoDuplicates() {
        FakeConnection connection = new FakeConnection("", "");
        connection.setReturn = false;
        connection.applySet = true;
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);

        ProjectionResult result =
                projection.project(ProjectionDocument.shortDraft("visible once"));

        assertEquals(ProjectionOutcome.MUTATION_UNCERTAIN, result.outcome());
        assertTrue(result.mutationUncertain());
        assertEquals("visible once", connection.text());
        int operations = connection.operations().size();
        projection.project(ProjectionDocument.shortDraft("do not append"));
        assertEquals(operations, connection.operations().size());
        assertEquals("visible once", connection.text());
    }

    @Test
    public void exceptionWithUnchangedEditorPreservesRecoverableDraft() {
        FakeConnection connection = new FakeConnection("x", "y");
        connection.throwSet = true;
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);

        ProjectionResult result =
                projection.project(ProjectionDocument.shortDraft("safe"));

        assertEquals(ProjectionState.RECOVERABLE, result.state());
        assertFalse(result.mutationUncertain());
        assertEquals("safe", result.recoverableText().orElseThrow());
        assertEquals("xy", connection.text());
    }

    @Test
    public void finalCommitFalseKeepsVisibleTextAndFinalCopyRecoverable() {
        FakeConnection connection = new FakeConnection("", "");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);
        projection.project(ProjectionDocument.shortDraft("partial"));
        connection.finishReturn = false;

        ProjectionResult result =
                projection.finish(ProjectionDocument.shortDraft("authoritative final."));

        assertEquals(ProjectionOutcome.MUTATION_UNCERTAIN, result.outcome());
        assertEquals(ProjectionState.RECOVERABLE, result.state());
        assertEquals("authoritative final.", result.recoverableText().orElseThrow());
        assertEquals("authoritative final.", connection.text());
    }

    @Test
    public void lifecycleFreezeFinalizesOwnedCompositionWithoutDeletingText() {
        FakeConnection connection = new FakeConnection("prefix ", " suffix");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);
        projection.project(ProjectionDocument.shortDraft("visible draft"));

        ProjectionResult frozen = projection.freeze();

        assertEquals(ProjectionOutcome.FROZEN, frozen.outcome());
        assertEquals(ProjectionState.FROZEN, frozen.state());
        assertEquals("prefix visible draft suffix", connection.text());
        assertFalse(connection.composing());
        assertEquals(
                ProjectionOutcome.REJECTED_STATE,
                projection.project(ProjectionDocument.shortDraft("late result")).outcome());
    }

    @Test
    public void committedUnicodeInsertionHasOneSafeLogicalUndo() {
        FakeConnection connection = new FakeConnection("前", "后");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);
        projection.finish(ProjectionDocument.shortDraft("你好🙂."));
        SessionUndoLedger undo = projection.undoLedger().orElseThrow();

        UndoResult first = undo.undo(connection);
        UndoResult second = undo.undo(connection);

        assertEquals(UndoDisposition.APPLIED, first.disposition());
        assertEquals("前后", connection.text());
        assertEquals(UndoDisposition.IGNORED_CONSUMED, second.disposition());
    }

    @Test
    public void undoRejectsMovedCursorWithoutDeletingRepeatedSuffix() {
        FakeConnection connection = new FakeConnection("same ", " same");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);
        projection.finish(ProjectionDocument.shortDraft("words"));
        SessionUndoLedger undo = projection.undoLedger().orElseThrow();
        connection.moveSelection(0);

        UndoResult result = undo.undo(connection);

        assertEquals(UndoDisposition.REJECTED_TARGET, result.disposition());
        assertEquals("same words same", connection.text());
        assertFalse(undo.consumed());
    }

    @Test
    public void explicitDiscardRemovesOnlyProvenOwnedProjection() {
        FakeConnection connection = new FakeConnection("keep:", ":keep");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);
        projection.project(ProjectionDocument.shortDraft("discard"));

        ProjectionResult discarded = projection.discardConfirmed();

        assertEquals(ProjectionOutcome.DISCARDED, discarded.outcome());
        assertEquals(ProjectionState.DISCARDED, discarded.state());
        assertEquals("keep::keep", connection.text());
    }

    @Test
    public void explicitDiscardNeverDeletesAfterTargetChanged() {
        FakeConnection connection = new FakeConnection("keep:", ":keep");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);
        projection.project(ProjectionDocument.shortDraft("draft"));
        connection.moveSelection(0);

        ProjectionResult discarded = projection.discardConfirmed();

        assertEquals(ProjectionOutcome.DISCARD_EDITOR_RETAINED, discarded.outcome());
        assertEquals("keep:draft:keep", connection.text());
        assertEquals(ProjectionState.DISCARDED, discarded.state());
    }

    @Test
    public void captureFailsClosedForUnknownSelectionSelectedTextAndSensitiveFields() {
        FakeConnection unknown = new FakeConnection("", "");
        unknown.selectionStart = -1;
        unknown.selectionEnd = -1;
        assertThrows(
                IllegalArgumentException.class,
                () -> EditorProjection.capture(unknown, ProjectionMode.SHORT_DICTATION));

        FakeConnection selected = new FakeConnection("before", "after");
        selected.selectionStart = 1;
        selected.selectionEnd = 3;
        assertThrows(
                IllegalArgumentException.class,
                () -> EditorProjection.capture(selected, ProjectionMode.SHORT_DICTATION));

        FakeConnection sensitive = new FakeConnection("", "");
        sensitive.sensitive = true;
        assertThrows(
                IllegalArgumentException.class,
                () -> EditorProjection.capture(sensitive, ProjectionMode.SHORT_DICTATION));
    }

    @Test
    public void longFinishProducesOneUndoForCommittedPrefixAndTail() {
        FakeConnection connection = new FakeConnection("", "");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.LONG_DICTATION);
        projection.project(new ProjectionDocument("first ", "second"));
        projection.project(new ProjectionDocument("first second ", "third"));
        ProjectionResult finalResult =
                projection.finish(new ProjectionDocument("first second third.", ""));

        assertEquals(ProjectionOutcome.COMMITTED, finalResult.outcome());
        assertEquals("first second third.", connection.text());
        assertEquals(
                UndoDisposition.APPLIED,
                projection.undoLedger().orElseThrow().undo(connection).disposition());
        assertEquals("", connection.text());
    }

    private static final class FakeConnection implements ProjectionConnection {
        private final Object identity = new Object();
        private final List<String> operations = new ArrayList<>();
        private StringBuilder text;
        private long epoch = 1L;
        private String packageName = "com.example.editor";
        private int fieldId = 1;
        private int selectionStart;
        private int selectionEnd;
        private int composingStart = -1;
        private int composingEnd = -1;
        private boolean sensitive;
        private boolean setReturn = true;
        private boolean applySet = true;
        private boolean finishReturn = true;
        private boolean applyFinish = true;
        private boolean deleteReturn = true;
        private boolean applyDelete = true;
        private boolean throwSet;

        private FakeConnection(String before, String after) {
            text = new StringBuilder(before).append(after);
            selectionStart = before.length();
            selectionEnd = selectionStart;
        }

        @Override
        public Object identity() {
            return identity;
        }

        @Override
        public ProjectionSnapshot snapshot(int maximumBeforeUtf16, int maximumAfterUtf16) {
            int cursor = Math.max(0, Math.min(selectionStart, text.length()));
            int beforeStart = Math.max(0, cursor - maximumBeforeUtf16);
            int afterEnd = Math.min(text.length(), cursor + maximumAfterUtf16);
            return new ProjectionSnapshot(
                    identity,
                    new ProjectionContext(
                            epoch,
                            packageName,
                            fieldId,
                            selectionStart,
                            selectionEnd,
                            sensitive),
                    text.substring(beforeStart, cursor),
                    text.substring(cursor, afterEnd));
        }

        @Override
        public boolean beginBatchEdit() {
            operations.add("begin");
            return true;
        }

        @Override
        public boolean endBatchEdit() {
            operations.add("end");
            return true;
        }

        @Override
        public boolean setComposingText(String value) {
            operations.add("set:" + value);
            if (throwSet) throw new IllegalStateException("set failed");
            if (applySet) {
                int start = composingStart >= 0 ? composingStart : selectionStart;
                int end = composingEnd >= 0 ? composingEnd : selectionEnd;
                text.replace(start, end, value);
                if (value.isEmpty()) {
                    composingStart = -1;
                    composingEnd = -1;
                    selectionStart = start;
                } else {
                    composingStart = start;
                    composingEnd = start + value.length();
                    selectionStart = composingEnd;
                }
                selectionEnd = selectionStart;
            }
            return setReturn;
        }

        @Override
        public boolean finishComposingText() {
            operations.add("finish");
            if (applyFinish) {
                composingStart = -1;
                composingEnd = -1;
            }
            return finishReturn;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeCodePoints, int afterCodePoints) {
            operations.add("delete:" + beforeCodePoints + ":" + afterCodePoints);
            if (applyDelete) {
                int start = text.toString().offsetByCodePoints(selectionStart, -beforeCodePoints);
                int end = text.toString().offsetByCodePoints(selectionEnd, afterCodePoints);
                text.delete(start, end);
                selectionStart = start;
                selectionEnd = start;
                composingStart = -1;
                composingEnd = -1;
            }
            return deleteReturn;
        }

        private String text() {
            return text.toString();
        }

        private List<String> operations() {
            return List.copyOf(operations);
        }

        private boolean composing() {
            return composingStart >= 0;
        }

        private String composingText() {
            return composing() ? text.substring(composingStart, composingEnd) : "";
        }

        private void moveSelection(int selection) {
            selectionStart = selection;
            selectionEnd = selection;
            composingStart = -1;
            composingEnd = -1;
        }

        private void changeField(int newFieldId, String before, String after) {
            fieldId = newFieldId;
            epoch++;
            text = new StringBuilder(before).append(after);
            selectionStart = before.length();
            selectionEnd = selectionStart;
            composingStart = -1;
            composingEnd = -1;
        }
    }
}
