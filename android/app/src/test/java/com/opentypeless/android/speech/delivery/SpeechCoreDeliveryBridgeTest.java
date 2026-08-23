package com.opentypeless.android.speech.delivery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.speech.core.DeliveryState;
import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.speech.engine.EngineCapabilities;
import com.opentypeless.android.speech.engine.EngineCapability;
import com.opentypeless.android.speech.engine.EngineDescriptor;
import com.opentypeless.android.speech.engine.ProcessingLocation;
import com.opentypeless.android.speech.runtime.RuntimeStrategy;
import com.opentypeless.android.speech.runtime.RuntimeStrategyDecision;
import com.opentypeless.android.speech.runtime.SpeechCoreCoordinator;
import com.opentypeless.android.speech.runtime.SpeechSessionToken;
import com.opentypeless.android.speech.runtime.StreamingRevisionInput;
import com.opentypeless.android.speech.transform.SegmentTransformPolicy;
import java.util.List;
import org.junit.Test;

public final class SpeechCoreDeliveryBridgeTest {
    private static final SpeechSessionToken TOKEN =
            new SpeechSessionToken(new SessionId("delivery-bridge"), 1L);

    @Test
    public void partialSealAndFinalCommitStayOneLogicalInsertion() {
        SpeechCoreCoordinator coordinator = coordinator();
        start(coordinator);
        coordinator.openSegment(TOKEN, 1L, SegmentJoin.NONE);
        coordinator.liveRevision(StreamingRevisionInput.text(TOKEN, 1L, 1L, "hello"));
        FakeConnection connection = new FakeConnection("before ", " after");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.LONG_DICTATION);
        SpeechCoreDeliveryBridge bridge =
                new SpeechCoreDeliveryBridge(coordinator, projection, ProjectionMode.LONG_DICTATION);

        DeliveryBridgeUpdate partial = bridge.projectCurrent();

        assertEquals("before hello after", connection.text());
        assertEquals(ProjectionOutcome.APPLIED, partial.projection().outcome());
        assertEquals(
                DeliveryState.COMPOSING,
                coordinator.draft().segment(1L).orElseThrow().deliveryState());

        coordinator.hardBoundary(TOKEN, 1L);
        DeliveryBridgeUpdate sealed = bridge.projectCurrent();
        DeliveryBridgeUpdate finished = bridge.finishCurrent();

        assertEquals(ProjectionOutcome.APPLIED, sealed.projection().outcome());
        assertEquals(ProjectionOutcome.COMMITTED, finished.projection().outcome());
        assertEquals("before hello. after", connection.text());
        assertEquals(
                DeliveryState.COMMITTED,
                coordinator.draft().segment(1L).orElseThrow().deliveryState());
        assertEquals(
                UndoDisposition.APPLIED,
                projection.undoLedger().orElseThrow().undo(connection).disposition());
        assertEquals("before  after", connection.text());
    }

    @Test
    public void targetChangeMovesOnlyUncommittedSegmentToRecovery() {
        SpeechCoreCoordinator coordinator = coordinator();
        start(coordinator);
        coordinator.openSegment(TOKEN, 1L, SegmentJoin.NONE);
        coordinator.liveRevision(StreamingRevisionInput.text(TOKEN, 1L, 1L, "draft"));
        FakeConnection connection = new FakeConnection("", "");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);
        SpeechCoreDeliveryBridge bridge =
                new SpeechCoreDeliveryBridge(coordinator, projection, ProjectionMode.SHORT_DICTATION);
        bridge.projectCurrent();
        connection.selectionStart = 0;
        connection.selectionEnd = 0;
        coordinator.liveRevision(StreamingRevisionInput.text(TOKEN, 1L, 2L, "draft later"));

        DeliveryBridgeUpdate recovered = bridge.projectCurrent();

        assertEquals(ProjectionState.RECOVERABLE, recovered.projection().state());
        assertEquals("draft later", recovered.projection().recoverableText().orElseThrow());
        assertEquals(
                DeliveryState.RECOVERABLE,
                coordinator.draft().segment(1L).orElseThrow().deliveryState());
        assertEquals("draft", connection.text());
    }

    @Test
    public void lifecycleFreezeNeverLetsLateCoreTextWriteAgain() {
        SpeechCoreCoordinator coordinator = coordinator();
        start(coordinator);
        coordinator.openSegment(TOKEN, 1L, SegmentJoin.NONE);
        coordinator.liveRevision(StreamingRevisionInput.text(TOKEN, 1L, 1L, "visible"));
        FakeConnection connection = new FakeConnection("", "");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);
        SpeechCoreDeliveryBridge bridge =
                new SpeechCoreDeliveryBridge(coordinator, projection, ProjectionMode.SHORT_DICTATION);
        bridge.projectCurrent();

        DeliveryBridgeUpdate frozen = bridge.freezeCurrent();
        coordinator.liveRevision(StreamingRevisionInput.text(TOKEN, 1L, 2L, "late text"));
        DeliveryBridgeUpdate late = bridge.projectCurrent();

        assertEquals(ProjectionState.FROZEN, frozen.projection().state());
        assertEquals(
                DeliveryState.FROZEN,
                coordinator.draft().segment(1L).orElseThrow().deliveryState());
        assertEquals(ProjectionOutcome.REJECTED_STATE, late.projection().outcome());
        assertEquals("visible", connection.text());
    }

    @Test
    public void explicitDiscardClearsCoreOnlyAfterConfirmedUserAction() {
        SpeechCoreCoordinator coordinator = coordinator();
        start(coordinator);
        coordinator.openSegment(TOKEN, 1L, SegmentJoin.NONE);
        coordinator.liveRevision(StreamingRevisionInput.text(TOKEN, 1L, 1L, "private"));
        FakeConnection connection = new FakeConnection("", "");
        EditorProjection projection =
                EditorProjection.capture(connection, ProjectionMode.SHORT_DICTATION);
        SpeechCoreDeliveryBridge bridge =
                new SpeechCoreDeliveryBridge(coordinator, projection, ProjectionMode.SHORT_DICTATION);
        bridge.projectCurrent();

        DeliveryBridgeUpdate discarded = bridge.discardConfirmed();

        assertEquals(ProjectionOutcome.DISCARDED, discarded.projection().outcome());
        assertEquals("", connection.text());
        assertEquals("", coordinator.draft().renderedText());
        assertFalse(coordinator.draft().segments().iterator().hasNext());
    }

    private static SpeechCoreCoordinator coordinator() {
        EngineDescriptor streaming = new EngineDescriptor(
                "stream",
                "Stream",
                "test",
                ProcessingLocation.ON_DEVICE,
                EngineCapabilities.of(
                        EngineCapability.LIVE_REVISIONS,
                        EngineCapability.ON_DEVICE));
        RuntimeStrategyDecision strategy = new RuntimeStrategyDecision(
                RuntimeStrategy.STREAMING_ONLY, 0, 0, 0L, List.of("test"));
        return new SpeechCoreCoordinator(
                TOKEN,
                streaming,
                null,
                strategy,
                PersonalizationSnapshot.empty(),
                SegmentTransformPolicy.DEFAULT);
    }

    private static void start(SpeechCoreCoordinator coordinator) {
        coordinator.prepare(TOKEN);
        coordinator.ready(TOKEN);
    }

    private static final class FakeConnection implements ProjectionConnection {
        private final Object identity = new Object();
        private final StringBuilder text;
        private int selectionStart;
        private int selectionEnd;
        private int composingStart = -1;
        private int composingEnd = -1;

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
            return new ProjectionSnapshot(
                    identity,
                    new ProjectionContext(
                            1L,
                            "com.example.editor",
                            7,
                            selectionStart,
                            selectionEnd,
                            false),
                    text.substring(Math.max(0, cursor - maximumBeforeUtf16), cursor),
                    text.substring(cursor, Math.min(text.length(), cursor + maximumAfterUtf16)));
        }

        @Override
        public boolean beginBatchEdit() {
            return true;
        }

        @Override
        public boolean endBatchEdit() {
            return true;
        }

        @Override
        public boolean setComposingText(String value) {
            int start = composingStart >= 0 ? composingStart : selectionStart;
            int end = composingEnd >= 0 ? composingEnd : selectionEnd;
            text.replace(start, end, value);
            composingStart = start;
            composingEnd = start + value.length();
            selectionStart = composingEnd;
            selectionEnd = selectionStart;
            return true;
        }

        @Override
        public boolean finishComposingText() {
            composingStart = -1;
            composingEnd = -1;
            return true;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeCodePoints, int afterCodePoints) {
            int start = text.offsetByCodePoints(selectionStart, -beforeCodePoints);
            int end = text.offsetByCodePoints(selectionEnd, afterCodePoints);
            text.delete(start, end);
            selectionStart = start;
            selectionEnd = start;
            composingStart = -1;
            composingEnd = -1;
            return true;
        }

        private String text() {
            return text.toString();
        }
    }
}
