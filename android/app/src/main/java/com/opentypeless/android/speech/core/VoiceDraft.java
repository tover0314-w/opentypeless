package com.opentypeless.android.speech.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Immutable, authoritative session-local document. */
public record VoiceDraft(
        SessionId sessionId,
        CaptureState captureState,
        List<VoiceSegment> segments,
        Long activeSegmentId,
        TerminalReason terminalReason) {

    public VoiceDraft {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(captureState, "captureState");
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        Objects.requireNonNull(terminalReason, "terminalReason");
        long previousSegmentId = 0L;
        boolean activeFound = activeSegmentId == null;
        for (VoiceSegment segment : segments) {
            if (segment.segmentId() <= previousSegmentId) {
                throw new IllegalArgumentException("segments must be strictly ordered");
            }
            previousSegmentId = segment.segmentId();
            if (activeSegmentId != null && segment.segmentId() == activeSegmentId) {
                if (segment.stage() != SegmentStage.OPEN
                        && segment.stage() != SegmentStage.SOFT_BOUNDARY) {
                    throw new IllegalArgumentException("active segment must still accept live work");
                }
                activeFound = true;
            }
            for (SegmentRevision revision : segment.revisions()) {
                if (!revision.sessionId().equals(sessionId)) {
                    throw new IllegalArgumentException("revision belongs to another session");
                }
            }
        }
        if (!activeFound) {
            throw new IllegalArgumentException("active segment is missing");
        }
        if (captureState == CaptureState.DISCARDED
                && terminalReason != TerminalReason.EXPLICIT_DISCARD) {
            throw new IllegalArgumentException("discarded draft needs explicit-discard reason");
        }
    }

    public static VoiceDraft initial(SessionId sessionId) {
        return new VoiceDraft(
                sessionId, CaptureState.IDLE, List.of(), null, TerminalReason.NONE);
    }

    public OptionalLong activeSegment() {
        return activeSegmentId == null
                ? OptionalLong.empty()
                : OptionalLong.of(activeSegmentId);
    }

    public Optional<VoiceSegment> segment(long segmentId) {
        return segments.stream().filter(segment -> segment.segmentId() == segmentId).findFirst();
    }

    public long maxSegmentId() {
        return segments.isEmpty() ? 0L : segments.get(segments.size() - 1).segmentId();
    }

    public String renderedText() {
        StringBuilder rendered = new StringBuilder();
        for (VoiceSegment segment : segments) {
            String text = segment.visibleText();
            if (text.isEmpty()) {
                continue;
            }
            if (rendered.length() > 0) {
                rendered.append(segment.joinBefore().delimiter());
            }
            rendered.append(text);
        }
        return rendered.toString();
    }

    public int visibleCodePoints() {
        String text = renderedText();
        return text.codePointCount(0, text.length());
    }

    VoiceDraft withCapture(CaptureState state, TerminalReason reason) {
        return new VoiceDraft(sessionId, state, segments, activeSegmentId, reason);
    }

    VoiceDraft addSegment(VoiceSegment segment) {
        ArrayList<VoiceSegment> updated = new ArrayList<>(segments);
        updated.add(segment);
        return new VoiceDraft(
                sessionId, captureState, updated, segment.segmentId(), terminalReason);
    }

    VoiceDraft replaceSegment(VoiceSegment replacement) {
        return replaceSegment(replacement, activeSegmentId);
    }

    VoiceDraft replaceSegmentAndClearActive(VoiceSegment replacement) {
        return replaceSegment(replacement, null);
    }

    private VoiceDraft replaceSegment(VoiceSegment replacement, Long updatedActiveSegmentId) {
        ArrayList<VoiceSegment> updated = new ArrayList<>(segments.size());
        boolean replaced = false;
        for (VoiceSegment segment : segments) {
            if (segment.segmentId() == replacement.segmentId()) {
                updated.add(replacement);
                replaced = true;
            } else {
                updated.add(segment);
            }
        }
        if (!replaced) {
            throw new IllegalArgumentException("replacement segment is missing");
        }
        return new VoiceDraft(
                sessionId, captureState, updated, updatedActiveSegmentId, terminalReason);
    }

    VoiceDraft discarded() {
        return new VoiceDraft(
                sessionId,
                CaptureState.DISCARDED,
                List.of(),
                null,
                TerminalReason.EXPLICIT_DISCARD);
    }
}
