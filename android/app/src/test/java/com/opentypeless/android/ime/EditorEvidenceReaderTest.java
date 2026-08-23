package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputConnection;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class EditorEvidenceReaderTest {
    @Test
    public void sensitiveSelectionPathCallsNoEditorGetter() {
        Trace trace = new Trace();
        InputConnection connection = connection(trace, "selected", new ExtractedText(),
                "before", "after");

        EditorEvidenceReader.SelectionResult result =
                EditorEvidenceReader.readSelectionOnce(connection, true, true);

        assertEquals(new EditorEvidenceReader.Rejected(EditorEvidenceReader.Failure.SENSITIVE), result);
        assertTrue(trace.calls.isEmpty());
    }

    @Test
    public void selectionStageReadsRequestedGettersOnceAndNoSurroundingText() {
        Trace trace = new Trace();
        ExtractedText extracted = new ExtractedText();
        InputConnection connection = connection(trace, "selected", extracted, "before", "after");

        EditorEvidenceReader.SelectionEvidence evidence =
                (EditorEvidenceReader.SelectionEvidence)
                        EditorEvidenceReader.readSelectionOnce(connection, false, true);

        assertEquals("selected", evidence.selectedText());
        assertTrue(evidence.selectedTextAvailable());
        assertTrue(evidence.extractedTextAvailable());
        assertEquals(0, evidence.extractedSelectionStart());
        assertEquals(0, evidence.extractedSelectionEnd());
        assertEquals(List.of("getSelectedText", "getExtractedText", "selected.toString"), trace.calls);
        assertTrue(!trace.counts.containsKey("getTextBeforeCursor"));
        assertTrue(!trace.counts.containsKey("getTextAfterCursor"));
        assertTrue(evidence.toString().contains("<redacted>"));
        assertTrue(!evidence.toString().contains("selected"));
    }

    @Test
    public void knownSelectionSkipsExtractedTextAndStillDoesNotReadSurrounding() {
        Trace trace = new Trace();
        InputConnection connection = connection(trace, null, new ExtractedText(), "before", "after");

        EditorEvidenceReader.SelectionEvidence evidence =
                (EditorEvidenceReader.SelectionEvidence)
                        EditorEvidenceReader.readSelectionOnce(connection, false, false);

        assertTrue(!evidence.selectedTextAvailable());
        assertEquals("", evidence.selectedText());
        assertTrue(!evidence.extractedTextAvailable());
        assertEquals(List.of("getSelectedText"), trace.calls);
    }

    @Test
    public void surroundingStagePreservesLegacyGetterAndMaterializationOrder() {
        Trace trace = new Trace();
        InputConnection connection = connection(trace, "selected", new ExtractedText(),
                "012345", "abcdef");

        EditorEvidenceReader.SurroundingEvidence evidence =
                (EditorEvidenceReader.SurroundingEvidence)
                        EditorEvidenceReader.readSurroundingOnce(connection, 800, 3);

        assertEquals("345", evidence.beforeFingerprint());
        assertEquals("012345", evidence.beforeContext());
        assertEquals("abc", evidence.afterFingerprint());
        assertEquals("012345", evidence.precedingContext());
        assertEquals("abcdef", evidence.afterContext());
        assertTrue(evidence.beforeTextAvailable());
        assertTrue(evidence.afterTextAvailable());
        assertEquals("012345", evidence.shadowBeforeText());
        assertEquals("abcdef", evidence.shadowAfterText());
        assertEquals(
                List.of(
                        "getTextBeforeCursor",
                        "before.toString",
                        "getTextAfterCursor",
                        "after.toString",
                        "before.toString"),
                trace.calls);
        assertEquals(Integer.valueOf(1), trace.counts.get("getTextBeforeCursor"));
        assertEquals(Integer.valueOf(1), trace.counts.get("getTextAfterCursor"));
        assertTrue(!evidence.toString().contains("012345"));
    }

    @Test
    public void nullAndEmptySurroundingEvidenceRemainDistinctForShadowCapture() {
        Trace nullTrace = new Trace();
        InputConnection nullConnection = connection(nullTrace, "", null, null, null);
        EditorEvidenceReader.SurroundingEvidence unavailable =
                (EditorEvidenceReader.SurroundingEvidence)
                        EditorEvidenceReader.readSurroundingOnce(nullConnection, 800, 64);
        assertTrue(!unavailable.beforeTextAvailable());
        assertTrue(!unavailable.afterTextAvailable());
        assertNull(unavailable.shadowBeforeText());
        assertNull(unavailable.shadowAfterText());
        assertEquals("", unavailable.beforeFingerprint());
        assertEquals("", unavailable.afterFingerprint());
        assertEquals("", unavailable.precedingContext());

        Trace emptyTrace = new Trace();
        InputConnection emptyConnection = connection(emptyTrace, "", null, "", "");
        EditorEvidenceReader.SurroundingEvidence empty =
                (EditorEvidenceReader.SurroundingEvidence)
                        EditorEvidenceReader.readSurroundingOnce(emptyConnection, 800, 64);
        assertTrue(empty.beforeTextAvailable());
        assertTrue(empty.afterTextAvailable());
        assertEquals("", empty.shadowBeforeText());
        assertEquals("", empty.shadowAfterText());
    }

    @Test
    public void readFailureIsContentFreeAndStopsTheStage() {
        Trace trace = new Trace();
        trace.throwAt = "before.toString";
        InputConnection connection = connection(trace, "", null, "secret-before", "secret-after");

        EditorEvidenceReader.SurroundingResult result =
                EditorEvidenceReader.readSurroundingOnce(connection, 800, 64);

        assertEquals(new EditorEvidenceReader.Rejected(EditorEvidenceReader.Failure.READ_FAILED), result);
        assertEquals(List.of("getTextBeforeCursor", "before.toString"), trace.calls);
        assertTrue(!result.toString().contains("secret"));
    }

    private static InputConnection connection(
            Trace trace,
            String selected,
            ExtractedText extracted,
            String before,
            String after) {
        return (InputConnection) Proxy.newProxyInstance(
                InputConnection.class.getClassLoader(),
                new Class<?>[]{InputConnection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getSelectedText" -> {
                        trace.add("getSelectedText");
                        yield selected == null ? null : trace.sequence("selected", selected);
                    }
                    case "getExtractedText" -> {
                        trace.add("getExtractedText");
                        yield extracted;
                    }
                    case "getTextBeforeCursor" -> {
                        trace.add("getTextBeforeCursor");
                        yield before == null ? null : trace.sequence("before", before);
                    }
                    case "getTextAfterCursor" -> {
                        trace.add("getTextAfterCursor");
                        yield after == null ? null : trace.sequence("after", after);
                    }
                    case "toString" -> "TracingInputConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError("unexpected call: " + method.getName());
                });
    }

    private static final class Trace {
        final List<String> calls = new ArrayList<>();
        final Map<String, Integer> counts = new HashMap<>();
        String throwAt;

        void add(String value) {
            calls.add(value);
            counts.merge(value, 1, Integer::sum);
            if (value.equals(throwAt)) throw new IllegalStateException("secret");
        }

        CharSequence sequence(String label, String value) {
            return new CharSequence() {
                @Override public int length() { return value.length(); }
                @Override public char charAt(int index) { return value.charAt(index); }
                @Override public CharSequence subSequence(int start, int end) {
                    return value.subSequence(start, end);
                }
                @Override public String toString() {
                    add(label + ".toString");
                    return value;
                }
            };
        }
    }
}
