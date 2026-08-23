package com.opentypeless.android.speech.journal;

import com.opentypeless.android.speech.core.TerminalReason;
import java.util.List;
import java.util.Objects;

/** Authenticated recovered session. Audio is included only for explicit recovery reads. */
public record JournalRecovery(
        JournalToken token,
        JournalSessionMetadata metadata,
        List<JournalSegmentRecovery> segments,
        boolean ended,
        TerminalReason terminalReason) {
    public JournalRecovery {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(metadata, "metadata");
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        Objects.requireNonNull(terminalReason, "terminalReason");
    }

    public String renderedText() {
        StringBuilder result = new StringBuilder();
        for (JournalSegmentRecovery segment : segments) {
            if (segment.latestRevision().isEmpty()) continue;
            String text = segment.latestRevision().get().fullText();
            if (text.isEmpty()) continue;
            if (result.length() > 0) result.append(segment.joinBefore().delimiter());
            result.append(text);
        }
        return result.toString();
    }
}
