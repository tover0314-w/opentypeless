package com.opentypeless.android.speech.delivery;

import static org.junit.Assert.assertEquals;

import com.opentypeless.android.speech.core.CaptureState;
import com.opentypeless.android.speech.core.DeliveryState;
import com.opentypeless.android.speech.core.RevisionOrigin;
import com.opentypeless.android.speech.core.RevisionStage;
import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SegmentRevision;
import com.opentypeless.android.speech.core.SegmentStage;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.speech.core.TerminalReason;
import com.opentypeless.android.speech.core.VoiceDraft;
import com.opentypeless.android.speech.core.VoiceSegment;
import java.util.List;
import org.junit.Test;

public final class VoiceDraftProjectionPlannerTest {
    private static final SessionId SESSION = new SessionId("projection-plan");

    @Test
    public void shortModeKeepsTheWholeRenderedDocumentComposing() {
        VoiceDraft draft = draft(
                segment(1L, SegmentJoin.NONE, SegmentStage.SEALED, "你好。"),
                segment(2L, SegmentJoin.NEWLINE, SegmentStage.OPEN, "world"));

        ProjectionDocument document =
                VoiceDraftProjectionPlanner.plan(draft, ProjectionMode.SHORT_DICTATION);

        assertEquals("", document.sealedPrefix());
        assertEquals("你好。\nworld", document.composingTail());
    }

    @Test
    public void longModeCommitsOnlyContiguousLeadingSealedSegments() {
        VoiceDraft draft = draft(
                segment(1L, SegmentJoin.NONE, SegmentStage.SEALED, "first."),
                segment(2L, SegmentJoin.SPACE, SegmentStage.SEALED, "second."),
                segment(3L, SegmentJoin.SPACE, SegmentStage.REFINING, "third live"),
                segment(4L, SegmentJoin.NEWLINE, SegmentStage.SEALED, "late quality"));

        ProjectionDocument document =
                VoiceDraftProjectionPlanner.plan(draft, ProjectionMode.LONG_DICTATION);

        assertEquals("first. second.", document.sealedPrefix());
        assertEquals(" third live\nlate quality", document.composingTail());
        assertEquals(draft.renderedText(), document.fullText());
    }

    @Test
    public void anEmptyUnsealedSegmentPreventsLaterTextFromBecomingStable() {
        VoiceDraft draft = draft(
                segment(1L, SegmentJoin.NONE, SegmentStage.SEALED, "stable"),
                VoiceSegment.open(2L, SegmentJoin.SPACE),
                segment(3L, SegmentJoin.SPACE, SegmentStage.SEALED, "later"));

        ProjectionDocument document =
                VoiceDraftProjectionPlanner.plan(draft, ProjectionMode.LONG_DICTATION);

        assertEquals("stable", document.sealedPrefix());
        assertEquals(" later", document.composingTail());
    }

    private static VoiceDraft draft(VoiceSegment... segments) {
        return new VoiceDraft(
                SESSION,
                CaptureState.ENDED,
                List.of(segments),
                null,
                TerminalReason.USER_FINISH);
    }

    private static VoiceSegment segment(
            long id,
            SegmentJoin join,
            SegmentStage stage,
            String text) {
        RevisionStage revisionStage = stage == SegmentStage.SEALED
                || stage == SegmentStage.REFINING
                ? RevisionStage.REFINED
                : RevisionStage.LIVE;
        SegmentRevision revision = SegmentRevision.text(
                SESSION,
                id,
                1L,
                revisionStage,
                text,
                stage == SegmentStage.OPEN
                        ? RevisionOrigin.STREAM_ASR
                        : RevisionOrigin.QUALITY_ASR,
                stage != SegmentStage.OPEN);
        return new VoiceSegment(
                id,
                join,
                stage,
                DeliveryState.NOT_PROJECTED,
                List.of(revision),
                0);
    }
}
