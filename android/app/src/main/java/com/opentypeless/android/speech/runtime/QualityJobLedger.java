package com.opentypeless.android.speech.runtime;

import com.opentypeless.android.speech.core.SessionId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure bounded ownership ledger for quality work. Native workers receive only returned tokens;
 * every late completion must be claimed through this ledger before it can reach the reducer.
 */
public final class QualityJobLedger {
    private final SessionId sessionId;
    private final long generation;
    private final RuntimeStrategyDecision strategy;
    private final Map<QualityJobToken, QualityJobState> jobs = new LinkedHashMap<>();
    private long lastSegmentId;
    private long nextJobId = 1L;
    private boolean cancelled;

    public QualityJobLedger(
            SessionId sessionId,
            long generation,
            RuntimeStrategyDecision strategy) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (generation <= 0L) throw new IllegalArgumentException("generation must be positive");
        this.generation = generation;
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    public synchronized QualityJobUpdate enqueue(long segmentId) {
        if (!qualityEnabled()) {
            return update(QualityJobDisposition.SKIPPED_STRATEGY, null,
                    "runtime strategy has no quality pass");
        }
        if (cancelled) {
            return update(QualityJobDisposition.REJECTED_STATE, null, "session is cancelled");
        }
        if (segmentId <= lastSegmentId) {
            return update(QualityJobDisposition.REJECTED_STATE, null,
                    "quality segments must be strictly monotonic");
        }
        if (unfinishedCount() >= strategy.maximumPendingQualityJobs()) {
            return update(QualityJobDisposition.REJECTED_BOUNDS, null,
                    "quality queue is full; streaming revision must be retained");
        }
        QualityJobToken token = new QualityJobToken(
                sessionId, generation, segmentId, nextJobId++);
        jobs.put(token, QualityJobState.QUEUED);
        lastSegmentId = segmentId;
        return update(QualityJobDisposition.APPLIED, token, "quality job queued");
    }

    public synchronized List<QualityJobToken> claimAvailable() {
        if (cancelled || !qualityEnabled()) return List.of();
        int capacity = strategy.maximumConcurrentQualityJobs() - runningCount();
        if (capacity <= 0) return List.of();
        ArrayList<QualityJobToken> claimed = new ArrayList<>(capacity);
        for (Map.Entry<QualityJobToken, QualityJobState> entry : jobs.entrySet()) {
            if (entry.getValue() != QualityJobState.QUEUED) continue;
            entry.setValue(QualityJobState.RUNNING);
            claimed.add(entry.getKey());
            if (claimed.size() == capacity) break;
        }
        return List.copyOf(claimed);
    }

    public synchronized QualityJobUpdate complete(QualityJobToken token) {
        return terminal(token, QualityJobState.COMPLETED, "quality result accepted");
    }

    public synchronized QualityJobUpdate fail(QualityJobToken token) {
        return terminal(token, QualityJobState.FAILED,
                "quality failed; streaming revision remains safe fallback");
    }

    public synchronized QualityJobUpdate timeout(QualityJobToken token) {
        return terminal(token, QualityJobState.TIMED_OUT,
                "quality deadline expired; streaming revision remains safe fallback");
    }

    public synchronized void cancelAll() {
        cancelled = true;
        jobs.replaceAll((ignored, state) -> terminal(state) ? state : QualityJobState.CANCELLED);
    }

    public synchronized Optional<QualityJobState> state(QualityJobToken token) {
        return Optional.ofNullable(jobs.get(token));
    }

    public synchronized int unfinishedCount() {
        return (int) jobs.values().stream().filter(state -> !terminal(state)).count();
    }

    public synchronized boolean cancelled() {
        return cancelled;
    }

    private QualityJobUpdate terminal(
            QualityJobToken token,
            QualityJobState terminalState,
            String detail) {
        if (!owned(token)) {
            return update(QualityJobDisposition.REJECTED_SESSION, token,
                    "quality completion belongs to another session generation");
        }
        QualityJobState current = jobs.get(token);
        if (current == null) {
            return update(QualityJobDisposition.REJECTED_STATE, token, "quality job is unknown");
        }
        if (current == terminalState) {
            return update(QualityJobDisposition.IGNORED_DUPLICATE, token,
                    "quality terminal state already applied");
        }
        if (current != QualityJobState.RUNNING || cancelled) {
            return update(QualityJobDisposition.REJECTED_STATE, token,
                    "late quality completion is no longer owned");
        }
        jobs.put(token, terminalState);
        return update(QualityJobDisposition.APPLIED, token, detail);
    }

    private boolean owned(QualityJobToken token) {
        return token != null
                && token.sessionId().equals(sessionId)
                && token.generation() == generation;
    }

    private boolean qualityEnabled() {
        return strategy.strategy() == RuntimeStrategy.SEQUENTIAL_TWO_PASS
                || strategy.strategy() == RuntimeStrategy.CONCURRENT_TWO_PASS;
    }

    private int runningCount() {
        return (int) jobs.values().stream()
                .filter(state -> state == QualityJobState.RUNNING)
                .count();
    }

    private static boolean terminal(QualityJobState state) {
        return state == QualityJobState.COMPLETED
                || state == QualityJobState.FAILED
                || state == QualityJobState.TIMED_OUT
                || state == QualityJobState.CANCELLED;
    }

    private static QualityJobUpdate update(
            QualityJobDisposition disposition,
            QualityJobToken token,
            String detail) {
        return new QualityJobUpdate(disposition, Optional.ofNullable(token), detail);
    }
}
