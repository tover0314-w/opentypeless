package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

public final class VoiceCompositionSessionTest {
    @Test
    public void replacesEachPartialInsteadOfAppendingAndRejectsStaleRevisions() {
        FakeConnection fake = new FakeConnection();
        VoiceCompositionSession session = new VoiceCompositionSession(fake.connection(), 4, 4);

        assertEquals(VoiceCompositionSession.ApplyResult.APPLIED, session.apply(partial(1, "你好")));
        assertEquals(VoiceCompositionSession.ApplyResult.APPLIED, session.apply(partial(2, "你好，世界")));
        assertEquals(VoiceCompositionSession.ApplyResult.STALE, session.apply(partial(1, "旧结果")));
        assertEquals(List.of("你好", "你好，世界"), fake.composingTexts);
        assertEquals("你好，世界", session.composingText());
    }

    @Test
    public void blankRevisionNeverErasesTheLastVisiblePartial() {
        FakeConnection fake = new FakeConnection();
        VoiceCompositionSession session = new VoiceCompositionSession(fake.connection(), 4, 4);

        assertEquals(VoiceCompositionSession.ApplyResult.APPLIED, session.apply(partial(1, "你好")));
        assertEquals(VoiceCompositionSession.ApplyResult.UNCHANGED, session.apply(partial(2, "   ")));
        assertEquals("你好", session.composingText());
        assertEquals(List.of("你好"), fake.composingTexts);
        assertEquals(VoiceCompositionSession.ApplyResult.APPLIED,
                session.apply(partial(3, "你好，世界")));
    }

    @Test
    public void acceptsOwnedSelectionCallbacksButRejectsUserCursorMovement() {
        FakeConnection fake = new FakeConnection();
        VoiceCompositionSession session = new VoiceCompositionSession(fake.connection(), 10, 10);
        session.apply(partial(1, "abc"));
        session.apply(partial(2, "abcdef"));

        assertTrue(session.acceptsSelection(13, 13, 10, 13));
        assertTrue(session.acceptsSelection(16, 16, -1, -1));
        assertFalse(session.acceptsSelection(9, 9, -1, -1));
        assertFalse(session.acceptsSelection(16, 16, 11, 16));
    }

    @Test
    public void finalTextReplacesCompositionAndCancelRemovesIt() {
        FakeConnection committed = new FakeConnection();
        VoiceCompositionSession first = new VoiceCompositionSession(committed.connection(), 0, 0);
        first.apply(partial(1, "partial"));
        assertTrue(first.commitFinal("final"));
        assertEquals(List.of("final"), committed.committedTexts);
        assertFalse(first.ownsComposition());

        FakeConnection cancelled = new FakeConnection();
        VoiceCompositionSession second = new VoiceCompositionSession(cancelled.connection(), 0, 0);
        second.apply(partial(1, "partial"));
        assertTrue(second.cancel());
        assertEquals(List.of("partial", ""), cancelled.composingTexts);
        assertEquals(1, cancelled.finishCalls);
    }

    @Test
    public void lifecycleStopFinalizesCompositionInsteadOfDeletingIt() {
        FakeConnection fake = new FakeConnection();
        VoiceCompositionSession session = new VoiceCompositionSession(fake.connection(), 0, 0);
        session.apply(partial(1, "保留这段文字"));

        assertTrue(session.preserve());
        assertEquals(List.of("保留这段文字"), fake.composingTexts);
        assertEquals(1, fake.finishCalls);
        assertFalse(session.ownsComposition());
    }

    @Test
    public void selectionAndRejectedEditorDisableUnsafeComposition() {
        FakeConnection selected = new FakeConnection();
        VoiceCompositionSession selectionSession = new VoiceCompositionSession(
                selected.connection(), 2, 5);
        assertEquals(
                VoiceCompositionSession.ApplyResult.DISABLED,
                selectionSession.apply(partial(1, "preview only")));

        FakeConnection rejected = new FakeConnection(false);
        VoiceCompositionSession rejectedSession = new VoiceCompositionSession(
                rejected.connection(), 2, 2);
        assertEquals(
                VoiceCompositionSession.ApplyResult.REJECTED,
                rejectedSession.apply(partial(1, "not inserted")));
        assertFalse(rejectedSession.ownsComposition());
    }

    @Test
    public void cancelReportsWhenAnEditorLeftTheOwnedDraftCommitted() {
        FakeConnection fake = new FakeConnection();
        VoiceCompositionSession session = new VoiceCompositionSession(fake.connection(), 0, 0);
        session.apply(partial(1, "partial"));
        fake.beforeCursor = "partial";

        assertFalse(session.cancel());
        assertFalse(session.ownsComposition());
    }

    private static TranscriptUpdate partial(long sequence, String text) {
        return TranscriptUpdate.unstable(
                sequence, text, TranscriptUpdate.Source.ANDROID_SYSTEM);
    }

    private static final class FakeConnection implements InvocationHandler {
        final Deque<Boolean> composingResults = new ArrayDeque<>();
        final List<String> composingTexts = new ArrayList<>();
        final List<String> committedTexts = new ArrayList<>();
        int finishCalls;
        String beforeCursor;

        FakeConnection(Boolean... composingResults) {
            this.composingResults.addAll(Arrays.asList(composingResults));
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
                case "setComposingText" -> {
                    composingTexts.add(String.valueOf(arguments[0]));
                    yield composingResults.isEmpty() || composingResults.removeFirst();
                }
                case "commitText" -> {
                    committedTexts.add(String.valueOf(arguments[0]));
                    yield true;
                }
                case "finishComposingText" -> {
                    finishCalls++;
                    yield true;
                }
                case "getTextBeforeCursor" -> beforeCursor;
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
