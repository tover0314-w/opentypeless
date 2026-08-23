package com.opentypeless.android.editor;

import java.util.Objects;

/**
 * Closed producer request for atomic commit-record association.
 *
 * <p>A producer may request no record, or request one while supplying only explicit raw-transcript
 * presence. Inserted text, actual source, original session, fingerprint and identifier all remain
 * authoritative transaction-host inputs.
 */
public sealed interface CommitRecordRequest permits
        CommitRecordRequest.None,
        CommitRecordRequest.Requested {

    /** The transaction must not create a commit record. */
    record None() implements CommitRecordRequest {}

    /** The transaction may create a record if and only if it applies an eligible text operation. */
    record Requested(CommitRecord.RawTranscript rawTranscript) implements CommitRecordRequest {
        public Requested {
            rawTranscript = Objects.requireNonNull(rawTranscript, "rawTranscript");
        }

        @Override
        public String toString() {
            return "CommitRecordRequest.Requested{rawTranscript="
                    + (rawTranscript instanceof CommitRecord.RawTranscript.Present
                            ? "PRESENT" : "ABSENT")
                    + '}';
        }
    }
}
