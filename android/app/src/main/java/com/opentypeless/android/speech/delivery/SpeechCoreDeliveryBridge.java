package com.opentypeless.android.speech.delivery;

import com.opentypeless.android.speech.core.DeliveryState;
import com.opentypeless.android.speech.core.VoiceDraft;
import com.opentypeless.android.speech.core.VoiceSegment;
import com.opentypeless.android.speech.runtime.CoordinatorUpdate;
import com.opentypeless.android.speech.runtime.SpeechCoreCoordinator;
import com.opentypeless.android.speech.runtime.SpeechSessionToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Serial adapter between an Android editor projection and the pure speech coordinator.
 *
 * <p>The bridge never invents text. It projects the coordinator's current immutable document and
 * reports only delivery-state changes back to the reducer.
 */
public final class SpeechCoreDeliveryBridge {
    private final SpeechCoreCoordinator coordinator;
    private final SpeechSessionToken token;
    private final EditorProjection projection;
    private final ProjectionMode mode;

    public SpeechCoreDeliveryBridge(
            SpeechCoreCoordinator coordinator,
            EditorProjection projection,
            ProjectionMode mode) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.token = coordinator.token();
        this.projection = Objects.requireNonNull(projection, "projection");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public synchronized DeliveryBridgeUpdate projectCurrent() {
        ProjectionResult result = projection.project(
                VoiceDraftProjectionPlanner.plan(coordinator.draft(), mode));
        ArrayList<CoordinatorUpdate> updates = new ArrayList<>();
        if (result.state() == ProjectionState.ACTIVE
                && (result.outcome() == ProjectionOutcome.APPLIED
                        || result.outcome() == ProjectionOutcome.UNCHANGED)) {
            advanceVisibleSegments(DeliveryState.COMPOSING, updates);
        } else if (result.state() == ProjectionState.RECOVERABLE) {
            advanceVisibleSegments(DeliveryState.RECOVERABLE, updates);
        }
        return update(result, updates);
    }

    public synchronized DeliveryBridgeUpdate finishCurrent() {
        ProjectionResult result = projection.finish(
                VoiceDraftProjectionPlanner.plan(coordinator.draft(), mode));
        ArrayList<CoordinatorUpdate> updates = new ArrayList<>();
        if (result.state() == ProjectionState.COMMITTED) {
            // A final-only backend can reach finish without a prior project callback. The reducer
            // deliberately requires the observable NOT_PROJECTED -> COMPOSING -> COMMITTED path.
            advanceVisibleSegments(DeliveryState.COMPOSING, updates);
            advanceVisibleSegments(DeliveryState.COMMITTED, updates);
        } else if (result.state() == ProjectionState.RECOVERABLE) {
            advanceVisibleSegments(DeliveryState.RECOVERABLE, updates);
        }
        return update(result, updates);
    }

    public synchronized DeliveryBridgeUpdate freezeCurrent() {
        ProjectionResult result = projection.freeze();
        ArrayList<CoordinatorUpdate> updates = new ArrayList<>();
        if (result.state() == ProjectionState.FROZEN) {
            detachVisibleSegments(updates);
        } else if (result.state() == ProjectionState.RECOVERABLE) {
            advanceVisibleSegments(DeliveryState.RECOVERABLE, updates);
        }
        return update(result, updates);
    }

    public synchronized DeliveryBridgeUpdate discardConfirmed() {
        ProjectionResult result = projection.discardConfirmed();
        ArrayList<CoordinatorUpdate> updates = new ArrayList<>();
        if (result.state() == ProjectionState.DISCARDED) {
            updates.add(coordinator.explicitDiscard(token));
        }
        return update(result, updates);
    }

    private void advanceVisibleSegments(
            DeliveryState requested,
            List<CoordinatorUpdate> updates) {
        // Re-read after every event: the coordinator owns the authoritative immutable snapshot.
        List<Long> segmentIds = visibleSegmentIds();
        for (long segmentId : segmentIds) {
            VoiceSegment segment = coordinator.draft().segment(segmentId).orElseThrow();
            if (segment.deliveryState() == requested) continue;
            if (requested == DeliveryState.COMPOSING
                    && segment.deliveryState() != DeliveryState.NOT_PROJECTED) {
                continue;
            }
            if (requested == DeliveryState.COMMITTED
                    && segment.deliveryState() != DeliveryState.COMPOSING
                    && segment.deliveryState() != DeliveryState.RECOVERABLE) {
                continue;
            }
            if (requested == DeliveryState.RECOVERABLE
                    && segment.deliveryState() == DeliveryState.COMMITTED) {
                continue;
            }
            updates.add(coordinator.deliveryChanged(token, segmentId, requested));
        }
    }

    private void detachVisibleSegments(List<CoordinatorUpdate> updates) {
        List<Long> segmentIds = visibleSegmentIds();
        for (long segmentId : segmentIds) {
            VoiceSegment segment = coordinator.draft().segment(segmentId).orElseThrow();
            if (segment.deliveryState() == DeliveryState.COMPOSING) {
                updates.add(coordinator.targetDetached(token, segmentId));
            }
        }
    }

    private DeliveryBridgeUpdate update(
            ProjectionResult result,
            List<CoordinatorUpdate> updates) {
        return new DeliveryBridgeUpdate(result, coordinator.draft(), updates);
    }

    private List<Long> visibleSegmentIds() {
        ArrayList<Long> segmentIds = new ArrayList<>();
        for (VoiceSegment segment : coordinator.draft().segments()) {
            if (!segment.visibleText().isEmpty()) segmentIds.add(segment.segmentId());
        }
        return segmentIds;
    }
}
