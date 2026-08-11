package com.opentypeless.android.speech.delivery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.inputmethod.InputConnection;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.lang.reflect.Proxy;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Device-runtime contract for Android method signatures and adapter readback semantics. */
@RunWith(AndroidJUnit4.class)
public final class AndroidInputConnectionAdapterInstrumentedTest {
    @Test
    public void unicodeCompositionFinalAndUndoUseTheRealAndroidInterface() {
        FakeEditor editor = new FakeEditor("前", "后");
        editor.epoch = 1L;
        editor.fieldId = 7;
        AndroidInputConnectionAdapter adapter =
                new AndroidInputConnectionAdapter(editor.connection(), editor::currentContext);
        EditorProjection projection =
                EditorProjection.capture(adapter, ProjectionMode.SHORT_DICTATION);

        assertEquals(
                ProjectionOutcome.APPLIED,
                projection.project(ProjectionDocument.shortDraft("你好🙂")).outcome());
        ProjectionResult committed =
                projection.finish(ProjectionDocument.shortDraft("你好🙂。"));

        assertEquals(ProjectionOutcome.COMMITTED, committed.outcome());
        assertEquals("前你好🙂。后", editor.text());
        assertEquals(
                UndoDisposition.APPLIED,
                projection.undoLedger().orElseThrow().undo(adapter).disposition());
        assertEquals("前后", editor.text());
    }

    @Test
    public void fieldEpochChangeRejectsLateTextWithoutCallingMutationAgain() {
        FakeEditor editor = new FakeEditor("", "");
        editor.epoch = 3L;
        editor.fieldId = 11;
        AndroidInputConnectionAdapter adapter =
                new AndroidInputConnectionAdapter(editor.connection(), editor::currentContext);
        EditorProjection projection =
                EditorProjection.capture(adapter, ProjectionMode.SHORT_DICTATION);
        projection.project(ProjectionDocument.shortDraft("visible"));
        int mutations = editor.mutations;
        editor.epoch = 4L;
        editor.fieldId = 12;

        ProjectionResult late =
                projection.project(ProjectionDocument.shortDraft("must not cross fields"));

        assertEquals(ProjectionOutcome.REJECTED_TARGET, late.outcome());
        assertEquals("visible", editor.text());
        assertEquals(mutations, editor.mutations);
    }

    @Test
    public void editorFalseAfterApplyingIsDetectedAsMutationUncertain() {
        FakeEditor editor = new FakeEditor("", "");
        editor.returnSet = false;
        EditorProjection projection = EditorProjection.capture(
                new AndroidInputConnectionAdapter(editor.connection(), editor::currentContext),
                ProjectionMode.SHORT_DICTATION);

        ProjectionResult result =
                projection.project(ProjectionDocument.shortDraft("only once"));

        assertEquals(ProjectionOutcome.MUTATION_UNCERTAIN, result.outcome());
        assertTrue(result.mutationUncertain());
        assertEquals("only once", editor.text());
    }

    private static final class FakeEditor {
        private final StringBuilder text;
        private int selection;
        private int composingStart = -1;
        private int composingEnd = -1;
        private int mutations;
        private boolean returnSet = true;
        private long epoch = 1L;
        private int fieldId = 1;
        private final InputConnection connection;

        private FakeEditor(String before, String after) {
            text = new StringBuilder(before).append(after);
            selection = before.length();
            connection = (InputConnection) Proxy.newProxyInstance(
                    InputConnection.class.getClassLoader(),
                    new Class<?>[] {InputConnection.class},
                    (proxy, method, arguments) -> {
                        String name = method.getName();
                        if (name.equals("getTextBeforeCursor")) {
                            int maximum = (Integer) arguments[0];
                            return text.substring(Math.max(0, selection - maximum), selection);
                        }
                        if (name.equals("getTextAfterCursor")) {
                            int maximum = (Integer) arguments[0];
                            return text.substring(selection, Math.min(text.length(), selection + maximum));
                        }
                        if (name.equals("beginBatchEdit") || name.equals("endBatchEdit")) {
                            return true;
                        }
                        if (name.equals("setComposingText")) {
                            mutations++;
                            String value = String.valueOf(arguments[0]);
                            int start = composingStart >= 0 ? composingStart : selection;
                            int end = composingEnd >= 0 ? composingEnd : selection;
                            text.replace(start, end, value);
                            composingStart = start;
                            composingEnd = start + value.length();
                            selection = composingEnd;
                            return returnSet;
                        }
                        if (name.equals("finishComposingText")) {
                            mutations++;
                            composingStart = -1;
                            composingEnd = -1;
                            return true;
                        }
                        if (name.equals("deleteSurroundingTextInCodePoints")) {
                            mutations++;
                            int deleteBefore = (Integer) arguments[0];
                            int deleteAfter = (Integer) arguments[1];
                            int start = text.offsetByCodePoints(selection, -deleteBefore);
                            int end = text.offsetByCodePoints(selection, deleteAfter);
                            text.delete(start, end);
                            selection = start;
                            composingStart = -1;
                            composingEnd = -1;
                            return true;
                        }
                        if (name.equals("hashCode")) return System.identityHashCode(proxy);
                        if (name.equals("equals")) return proxy == arguments[0];
                        if (name.equals("toString")) return "FakeInputConnection";
                        Class<?> returnType = method.getReturnType();
                        if (returnType == boolean.class) return false;
                        if (returnType == int.class) return 0;
                        return null;
                    });
        }

        private ProjectionContext currentContext() {
            return new ProjectionContext(
                    epoch,
                    "com.example.editor",
                    fieldId,
                    selection,
                    selection,
                    false);
        }

        private InputConnection connection() {
            return connection;
        }

        private String text() {
            return text.toString();
        }
    }
}
