package com.opentypeless.android.diagnostics;

import com.opentypeless.android.settings.RecognitionBackend;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, transcript-free timing trace for one recognition session.
 *
 * <p>Callers provide elapsed-realtime values so unit tests remain deterministic and wall-clock
 * changes cannot produce negative latency.</p>
 */
public final class RecognitionDiagnostics {
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong(
            Math.max(1L, System.currentTimeMillis()) * 1_000L);

    public enum Status {
        ACTIVE,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    public record Snapshot(
            long sessionId,
            long startedAtEpochMs,
            RecognitionRoute route,
            String languageTag,
            Status status,
            long readyLatencyMs,
            long firstPartialLatencyMs,
            long releaseToRawFinalLatencyMs,
            long textProcessingLatencyMs,
            long releaseToTerminalLatencyMs,
            long terminalLatencyMs,
            long audioDurationMs,
            int finalCodePointCount,
            boolean recoveredPartial) {

        public Snapshot {
            if (sessionId <= 0L) throw new IllegalArgumentException("Invalid session id");
            if (route == null) throw new IllegalArgumentException("Recognition route is required");
            languageTag = sanitizeLanguageTag(languageTag);
            status = status == null ? Status.ACTIVE : status;
            readyLatencyMs = normalizeMetric(readyLatencyMs);
            firstPartialLatencyMs = normalizeMetric(firstPartialLatencyMs);
            releaseToRawFinalLatencyMs = normalizeMetric(releaseToRawFinalLatencyMs);
            textProcessingLatencyMs = normalizeMetric(textProcessingLatencyMs);
            releaseToTerminalLatencyMs = normalizeMetric(releaseToTerminalLatencyMs);
            terminalLatencyMs = normalizeMetric(terminalLatencyMs);
            audioDurationMs = normalizeMetric(audioDurationMs);
            finalCodePointCount = Math.max(-1, finalCodePointCount);
        }

        public boolean terminal() {
            return status != Status.ACTIVE;
        }

        /** Backward-compatible constructor for callers that do not provide stage timing yet. */
        public Snapshot(
                long sessionId,
                long startedAtEpochMs,
                RecognitionRoute route,
                String languageTag,
                Status status,
                long readyLatencyMs,
                long firstPartialLatencyMs,
                long terminalLatencyMs,
                long audioDurationMs,
                int finalCodePointCount,
                boolean recoveredPartial) {
            this(
                    sessionId,
                    startedAtEpochMs,
                    route,
                    languageTag,
                    status,
                    readyLatencyMs,
                    firstPartialLatencyMs,
                    -1L,
                    -1L,
                    -1L,
                    terminalLatencyMs,
                    audioDurationMs,
                    finalCodePointCount,
                    recoveredPartial);
        }
    }

    private final long sessionId;
    private final long startedAtEpochMs;
    private final long startedAtElapsedMs;
    private final String languageTag;
    private RecognitionRoute route;
    private Status status = Status.ACTIVE;
    private long readyLatencyMs = -1L;
    private long firstPartialLatencyMs = -1L;
    private long stopRequestedAtElapsedMs = -1L;
    private long rawFinalAtElapsedMs = -1L;
    private long releaseToRawFinalLatencyMs = -1L;
    private long textProcessingLatencyMs = -1L;
    private long releaseToTerminalLatencyMs = -1L;
    private long terminalLatencyMs = -1L;
    private long audioDurationMs = -1L;
    private int finalCodePointCount = -1;
    private boolean recoveredPartial;

    private RecognitionDiagnostics(
            long sessionId,
            long startedAtEpochMs,
            long startedAtElapsedMs,
            RecognitionRoute route,
            String languageTag) {
        this.sessionId = sessionId;
        this.startedAtEpochMs = Math.max(0L, startedAtEpochMs);
        this.startedAtElapsedMs = Math.max(0L, startedAtElapsedMs);
        this.route = route;
        this.languageTag = sanitizeLanguageTag(languageTag);
    }

    public static RecognitionDiagnostics start(
            RecognitionBackend selectedBackend,
            String languageTag,
            long startedAtEpochMs,
            long startedAtElapsedMs) {
        return new RecognitionDiagnostics(
                NEXT_SESSION_ID.incrementAndGet(),
                startedAtEpochMs,
                startedAtElapsedMs,
                RecognitionRoute.direct(selectedBackend),
                languageTag);
    }

    public synchronized boolean updateRoute(RecognitionRoute nextRoute) {
        if (status != Status.ACTIVE || nextRoute == null || route.equals(nextRoute)) return false;
        route = nextRoute;
        return true;
    }

    public synchronized boolean markReady(long elapsedRealtimeMs) {
        if (status != Status.ACTIVE || readyLatencyMs >= 0L) return false;
        readyLatencyMs = latency(elapsedRealtimeMs);
        return true;
    }

    public synchronized boolean markFirstPartial(long elapsedRealtimeMs) {
        if (status != Status.ACTIVE || firstPartialLatencyMs >= 0L) return false;
        firstPartialLatencyMs = latency(elapsedRealtimeMs);
        return true;
    }

    public synchronized boolean markStopRequested(long elapsedRealtimeMs) {
        if (status != Status.ACTIVE || stopRequestedAtElapsedMs >= 0L) return false;
        stopRequestedAtElapsedMs = Math.max(0L, elapsedRealtimeMs);
        return true;
    }

    public synchronized boolean markRawFinal(long elapsedRealtimeMs) {
        if (status != Status.ACTIVE || rawFinalAtElapsedMs >= 0L) return false;
        rawFinalAtElapsedMs = Math.max(0L, elapsedRealtimeMs);
        if (stopRequestedAtElapsedMs >= 0L) {
            releaseToRawFinalLatencyMs = Math.max(
                    0L, rawFinalAtElapsedMs - stopRequestedAtElapsedMs);
        }
        return true;
    }

    public synchronized boolean succeed(
            long elapsedRealtimeMs,
            long durationMs,
            String finalText,
            boolean recovered) {
        if (status != Status.ACTIVE) return false;
        status = Status.SUCCEEDED;
        updateTerminalStageMetrics(elapsedRealtimeMs);
        terminalLatencyMs = latency(elapsedRealtimeMs);
        audioDurationMs = Math.max(0L, durationMs);
        String safe = finalText == null ? "" : finalText;
        finalCodePointCount = safe.codePointCount(0, safe.length());
        recoveredPartial = recovered;
        return true;
    }

    public synchronized boolean fail(long elapsedRealtimeMs) {
        return finish(Status.FAILED, elapsedRealtimeMs);
    }

    public synchronized boolean cancel(long elapsedRealtimeMs) {
        return finish(Status.CANCELLED, elapsedRealtimeMs);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                sessionId,
                startedAtEpochMs,
                route,
                languageTag,
                status,
                readyLatencyMs,
                firstPartialLatencyMs,
                releaseToRawFinalLatencyMs,
                textProcessingLatencyMs,
                releaseToTerminalLatencyMs,
                terminalLatencyMs,
                audioDurationMs,
                finalCodePointCount,
                recoveredPartial);
    }

    private boolean finish(Status terminalStatus, long elapsedRealtimeMs) {
        if (status != Status.ACTIVE) return false;
        status = terminalStatus;
        updateTerminalStageMetrics(elapsedRealtimeMs);
        terminalLatencyMs = latency(elapsedRealtimeMs);
        return true;
    }

    private void updateTerminalStageMetrics(long elapsedRealtimeMs) {
        long safeTerminal = Math.max(0L, elapsedRealtimeMs);
        if (rawFinalAtElapsedMs >= 0L) {
            textProcessingLatencyMs = Math.max(0L, safeTerminal - rawFinalAtElapsedMs);
        }
        if (stopRequestedAtElapsedMs >= 0L) {
            releaseToTerminalLatencyMs = Math.max(
                    0L, safeTerminal - stopRequestedAtElapsedMs);
        }
    }

    private long latency(long elapsedRealtimeMs) {
        return Math.max(0L, elapsedRealtimeMs - startedAtElapsedMs);
    }

    private static long normalizeMetric(long value) {
        return value < 0L ? -1L : value;
    }

    static String sanitizeLanguageTag(String value) {
        String normalized = value == null ? "" : value.trim().replace('_', '-');
        if (normalized.isEmpty()) return "und";
        if (normalized.length() > 35 || !normalized.matches("[A-Za-z0-9-]+")) return "und";
        String canonical = Locale.forLanguageTag(normalized).toLanguageTag();
        return canonical.isBlank() ? "und" : canonical;
    }
}
