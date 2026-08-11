package com.opentypeless.android.speech.delivery;

import com.opentypeless.android.speech.core.VoiceDraft;
import com.opentypeless.android.speech.runtime.CoordinatorUpdate;
import java.util.List;
import java.util.Objects;

/** Result of one projection operation plus the delivery-state events accepted by the core. */
public record DeliveryBridgeUpdate(
        ProjectionResult projection,
        VoiceDraft draft,
        List<CoordinatorUpdate> coordinatorUpdates) {
    public DeliveryBridgeUpdate {
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(draft, "draft");
        coordinatorUpdates = List.copyOf(
                Objects.requireNonNull(coordinatorUpdates, "coordinatorUpdates"));
    }
}
