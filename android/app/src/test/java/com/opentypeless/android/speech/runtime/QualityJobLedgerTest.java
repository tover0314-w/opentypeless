package com.opentypeless.android.speech.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.speech.core.SessionId;
import java.util.List;
import org.junit.Test;

public final class QualityJobLedgerTest {
    private static final SessionId SESSION = new SessionId("quality-ledger");

    @Test
    public void sequentialStrategyClaimsOneJobAndRetainsOrderedQueue() {
        QualityJobLedger ledger = ledger(RuntimeStrategy.SEQUENTIAL_TWO_PASS, 1, 4);
        QualityJobToken one = enqueue(ledger, 1L);
        QualityJobToken two = enqueue(ledger, 2L);

        assertEquals(List.of(one), ledger.claimAvailable());
        assertTrue(ledger.claimAvailable().isEmpty());
        assertEquals(QualityJobDisposition.APPLIED, ledger.complete(one).disposition());
        assertEquals(List.of(two), ledger.claimAvailable());
    }

    @Test
    public void outOfOrderConcurrentCompletionRemainsSegmentBound() {
        QualityJobLedger ledger = ledger(RuntimeStrategy.CONCURRENT_TWO_PASS, 2, 4);
        QualityJobToken one = enqueue(ledger, 1L);
        QualityJobToken two = enqueue(ledger, 2L);
        assertEquals(List.of(one, two), ledger.claimAvailable());

        assertEquals(QualityJobDisposition.APPLIED, ledger.complete(two).disposition());
        assertEquals(QualityJobState.COMPLETED, ledger.state(two).orElseThrow());
        assertEquals(QualityJobState.RUNNING, ledger.state(one).orElseThrow());
        assertEquals(QualityJobDisposition.APPLIED, ledger.complete(one).disposition());
    }

    @Test
    public void duplicateAndForeignCompletionCannotReachReducer() {
        QualityJobLedger ledger = ledger(RuntimeStrategy.CONCURRENT_TWO_PASS, 1, 4);
        QualityJobToken owned = enqueue(ledger, 1L);
        ledger.claimAvailable();
        assertEquals(QualityJobDisposition.APPLIED, ledger.complete(owned).disposition());
        assertEquals(
                QualityJobDisposition.IGNORED_DUPLICATE,
                ledger.complete(owned).disposition());

        QualityJobToken foreign = new QualityJobToken(
                new SessionId("other-session"), 1L, 1L, 1L);
        assertEquals(
                QualityJobDisposition.REJECTED_SESSION,
                ledger.complete(foreign).disposition());
    }

    @Test
    public void cancelInvalidatesEveryLateNativeCallback() {
        QualityJobLedger ledger = ledger(RuntimeStrategy.CONCURRENT_TWO_PASS, 2, 4);
        QualityJobToken one = enqueue(ledger, 1L);
        QualityJobToken two = enqueue(ledger, 2L);
        ledger.claimAvailable();

        ledger.cancelAll();

        assertEquals(QualityJobState.CANCELLED, ledger.state(one).orElseThrow());
        assertEquals(QualityJobState.CANCELLED, ledger.state(two).orElseThrow());
        assertEquals(
                QualityJobDisposition.REJECTED_STATE,
                ledger.complete(one).disposition());
        assertTrue(ledger.cancelled());
    }

    @Test
    public void boundedQueueFallsBackToStreamingWithoutEvictingOwnedJobs() {
        QualityJobLedger ledger = ledger(RuntimeStrategy.SEQUENTIAL_TWO_PASS, 1, 2);
        enqueue(ledger, 1L);
        enqueue(ledger, 2L);

        QualityJobUpdate rejected = ledger.enqueue(3L);

        assertEquals(QualityJobDisposition.REJECTED_BOUNDS, rejected.disposition());
        assertEquals(2, ledger.unfinishedCount());
    }

    @Test
    public void streamingOnlyStrategyNeverCreatesQualityWork() {
        QualityJobLedger ledger = ledger(RuntimeStrategy.STREAMING_ONLY, 0, 0);
        QualityJobUpdate update = ledger.enqueue(1L);

        assertEquals(QualityJobDisposition.SKIPPED_STRATEGY, update.disposition());
        assertTrue(update.token().isEmpty());
        assertTrue(ledger.claimAvailable().isEmpty());
    }

    @Test
    public void failedAndTimedOutJobsKeepSafeStreamingFallback() {
        QualityJobLedger ledger = ledger(RuntimeStrategy.CONCURRENT_TWO_PASS, 2, 4);
        QualityJobToken failed = enqueue(ledger, 1L);
        QualityJobToken timedOut = enqueue(ledger, 2L);
        ledger.claimAvailable();

        assertEquals(QualityJobDisposition.APPLIED, ledger.fail(failed).disposition());
        assertEquals(QualityJobDisposition.APPLIED, ledger.timeout(timedOut).disposition());
        assertEquals(QualityJobState.FAILED, ledger.state(failed).orElseThrow());
        assertEquals(QualityJobState.TIMED_OUT, ledger.state(timedOut).orElseThrow());
    }

    @Test
    public void segmentIdsAreStrictlyMonotonic() {
        QualityJobLedger ledger = ledger(RuntimeStrategy.SEQUENTIAL_TWO_PASS, 1, 4);
        enqueue(ledger, 2L);

        assertEquals(QualityJobDisposition.REJECTED_STATE, ledger.enqueue(2L).disposition());
        assertEquals(QualityJobDisposition.REJECTED_STATE, ledger.enqueue(1L).disposition());
    }

    private static QualityJobLedger ledger(
            RuntimeStrategy strategy,
            int concurrent,
            int pending) {
        return new QualityJobLedger(
                SESSION,
                1L,
                new RuntimeStrategyDecision(
                        strategy,
                        concurrent,
                        pending,
                        strategy == RuntimeStrategy.STREAMING_ONLY ? 0L : 1_200L,
                        List.of("test")));
    }

    private static QualityJobToken enqueue(QualityJobLedger ledger, long segmentId) {
        QualityJobUpdate update = ledger.enqueue(segmentId);
        assertEquals(QualityJobDisposition.APPLIED, update.disposition());
        return update.token().orElseThrow();
    }
}
