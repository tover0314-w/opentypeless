package com.opentypeless.android.speech.delivery;

import com.opentypeless.android.speech.core.SegmentStage;
import com.opentypeless.android.speech.core.VoiceDraft;
import com.opentypeless.android.speech.core.VoiceSegment;
import java.util.Objects;

/** Pure mapping from the authoritative speech document to an editor projection document. */
public final class VoiceDraftProjectionPlanner {
    private VoiceDraftProjectionPlanner() {}

    public static ProjectionDocument plan(VoiceDraft draft, ProjectionMode mode) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(mode, "mode");
        if (mode == ProjectionMode.SHORT_DICTATION) {
            return ProjectionDocument.shortDraft(draft.renderedText());
        }

        StringBuilder rendered = new StringBuilder();
        int sealedPrefixEnd = 0;
        boolean leadingSegmentsAreSealed = true;
        for (VoiceSegment segment : draft.segments()) {
            String text = segment.visibleText();
            if (text.isEmpty()) {
                if (segment.stage() != SegmentStage.SEALED) {
                    leadingSegmentsAreSealed = false;
                }
                continue;
            }
            if (rendered.length() > 0) {
                rendered.append(segment.joinBefore().delimiter());
            }
            rendered.append(text);
            if (leadingSegmentsAreSealed && segment.stage() == SegmentStage.SEALED) {
                sealedPrefixEnd = rendered.length();
            } else {
                leadingSegmentsAreSealed = false;
            }
        }
        return new ProjectionDocument(
                rendered.substring(0, sealedPrefixEnd),
                rendered.substring(sealedPrefixEnd));
    }
}
