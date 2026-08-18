package com.opentypeless.android.editor.host;

import com.opentypeless.android.editor.CommitRecord;
import com.opentypeless.android.editor.EditorSessionSnapshot;
import com.opentypeless.android.editor.OperationSource;
import com.opentypeless.android.editor.CommitRecord.RawTranscript;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Owner-thread, process-local, fixed single-slot registry addressed only by exact commit ID. */
final class CommitLedger {
    private static final AtomicLong NEXT_PROCESS_GENERATION = new AtomicLong();

    @FunctionalInterface
    interface CommitIdSource {
        String nextId();
    }

    private final Thread ownerThread;
    private final CommitIdSource idSource;
    private CommitRecord activeRecord;
    private Reservation activeReservation;

    CommitLedger() {
        this(() -> UUID.randomUUID().toString());
    }

    CommitLedger(CommitIdSource idSource) {
        this.ownerThread = Thread.currentThread();
        this.idSource = Objects.requireNonNull(idSource, "idSource");
    }

    Reservation reserve(
            EditorSessionSnapshot originalSession,
            OperationSource source,
            String insertedText,
            RawTranscript rawTranscript) {
        requireOwnerThread();
        if (activeReservation != null) return null;
        String candidate;
        try {
            candidate = idSource.nextId();
        } catch (RuntimeException unavailable) {
            return null;
        }
        CommitRecord candidateRecord;
        try {
            candidateRecord = CommitRecord.create(
                    candidate, source, originalSession, insertedText, rawTranscript);
        } catch (RuntimeException invalid) {
            return null;
        }
        String rawId = candidateRecord.commitId();
        String prefix = nextProcessPrefix();
        if (prefix == null) return null;
        int rawCodePoints = rawId.codePointCount(0, rawId.length());
        int maximumRawCodePoints =
                com.opentypeless.android.editor.EditorOperation.MAX_COMMIT_ID_CODE_POINTS
                        - prefix.length();
        if (maximumRawCodePoints <= 0) return null;
        if (rawCodePoints > maximumRawCodePoints) {
            rawId = rawId.substring(
                    rawId.offsetByCodePoints(0, rawCodePoints - maximumRawCodePoints));
        }
        CommitRecord record;
        try {
            record = CommitRecord.create(
                    prefix + rawId, source, originalSession, insertedText, rawTranscript);
        } catch (RuntimeException invalid) {
            return null;
        }
        activeReservation = new Reservation(this, record);
        return activeReservation;
    }

    private static String nextProcessPrefix() {
        while (true) {
            long current = NEXT_PROCESS_GENERATION.get();
            if (current == Long.MAX_VALUE) return null;
            long next = current + 1L;
            if (NEXT_PROCESS_GENERATION.compareAndSet(current, next)) {
                return "g" + next + "-";
            }
        }
    }

    Optional<CommitRecord> resolve(String commitId, EditorSessionSnapshot currentSession) {
        requireOwnerThread();
        if (commitId == null || currentSession == null) return Optional.empty();
        CommitRecord record = activeRecord;
        if (record == null || !record.commitId().equals(commitId)) return Optional.empty();
        if (record == null || !sameEditor(record.originalSession(), currentSession)) {
            return Optional.empty();
        }
        return Optional.of(record);
    }

    // Identity resolution is not editor-write authorization. EDT-011/012 must still perform the
    // full live target, committed-text fingerprint and operation-specific precondition checks.

    Optional<CommitRecord> consume(String commitId, EditorSessionSnapshot currentSession) {
        requireOwnerThread();
        Optional<CommitRecord> resolved = resolve(commitId, currentSession);
        if (resolved.isPresent()) activeRecord = null;
        return resolved;
    }

    void revokeAll() {
        requireOwnerThread();
        activeRecord = null;
        if (activeReservation != null) activeReservation.abort();
    }

    int sizeForTest() {
        requireOwnerThread();
        return activeRecord == null ? 0 : 1;
    }

    private CommitRecord publish(Reservation reservation) {
        requireOwnerThread();
        if (reservation != activeReservation || reservation.closed) return null;
        reservation.closed = true;
        activeReservation = null;
        activeRecord = reservation.record;
        return reservation.record;
    }

    private void abort(Reservation reservation) {
        requireOwnerThread();
        if (reservation.closed) return;
        reservation.closed = true;
        if (reservation == activeReservation) activeReservation = null;
    }

    private static boolean sameEditor(
            EditorSessionSnapshot original, EditorSessionSnapshot current) {
        return original.epoch() == current.epoch()
                && original.connectionToken() == current.connectionToken()
                && original.fieldId() == current.fieldId()
                && original.packageName().equals(current.packageName())
                && original.fieldKind() == current.fieldKind()
                && original.inputType() == current.inputType()
                && original.imeOptions() == current.imeOptions()
                && original.sensitive() == current.sensitive()
                && original.learningAllowed() == current.learningAllowed();
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("commit ledger used off owner thread");
        }
    }

    @Override
    public String toString() {
        return "CommitLedger{<redacted>}";
    }

    static final class Reservation {
        private final CommitLedger ledger;
        private final CommitRecord record;
        private boolean closed;

        private Reservation(CommitLedger ledger, CommitRecord record) {
            this.ledger = ledger;
            this.record = record;
        }

        CommitRecord publish() {
            return ledger.publish(this);
        }

        void abort() {
            ledger.abort(this);
        }

        @Override
        public String toString() {
            return "Reservation{<redacted>}";
        }
    }
}
