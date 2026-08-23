package com.opentypeless.android.speech.audio;

import java.util.List;
import java.util.Objects;

/** Atomic result of feeding one capture frame or explicit finish into the segmenter. */
public record SegmentAudioUpdate(
        List<BoundarySignal> boundarySignals,
        List<SegmentAudio> closedSegments) {
    public static final SegmentAudioUpdate EMPTY =
            new SegmentAudioUpdate(List.of(), List.of());

    public SegmentAudioUpdate {
        boundarySignals = List.copyOf(Objects.requireNonNull(boundarySignals, "boundarySignals"));
        closedSegments = List.copyOf(Objects.requireNonNull(closedSegments, "closedSegments"));
    }
}
