package com.opentypeless.android.speech.journal;

import com.opentypeless.android.speech.core.DeliveryState;
import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SegmentRevision;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record JournalSegmentRecovery(
        long segmentId,
        SegmentJoin joinBefore,
        List<JournalAudioChunk> audioChunks,
        Optional<SegmentRevision> latestRevision,
        boolean sealed,
        DeliveryState deliveryState) {
    public JournalSegmentRecovery {
        if (segmentId <= 0L) throw new IllegalArgumentException("segment id must be positive");
        Objects.requireNonNull(joinBefore, "joinBefore");
        audioChunks = List.copyOf(Objects.requireNonNull(audioChunks, "audioChunks"));
        latestRevision = Objects.requireNonNull(latestRevision, "latestRevision");
        Objects.requireNonNull(deliveryState, "deliveryState");
    }
}
