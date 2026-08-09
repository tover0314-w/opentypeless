package com.opentypeless.android.recognition;

/** Pure session state used to keep asynchronous preparation linearizable. */
final class RecognitionPreparationState {
    enum Phase { IDLE, PREPARING, RUNNING, SHUTDOWN }
    enum StopAction { NONE, FAIL_PREPARATION, STOP_PIPELINE }

    private long generation;
    private long activeToken;
    private Phase phase = Phase.IDLE;

    long begin() {
        if (phase != Phase.IDLE) return 0L;
        activeToken = ++generation;
        phase = Phase.PREPARING;
        return activeToken;
    }

    boolean beginPipeline(long token) {
        if (!isCurrent(token) || phase != Phase.PREPARING) return false;
        phase = Phase.RUNNING;
        return true;
    }

    StopAction stop() {
        if (phase == Phase.PREPARING) {
            invalidateActive();
            return StopAction.FAIL_PREPARATION;
        }
        return phase == Phase.RUNNING ? StopAction.STOP_PIPELINE : StopAction.NONE;
    }

    boolean cancel() {
        if (phase != Phase.PREPARING && phase != Phase.RUNNING) return false;
        invalidateActive();
        return true;
    }

    boolean finish(long token) {
        if (!isCurrent(token)) return false;
        invalidateActive();
        return true;
    }

    boolean isCurrent(long token) {
        return token != 0L
                && activeToken == token
                && (phase == Phase.PREPARING || phase == Phase.RUNNING);
    }

    boolean shutdown() {
        if (phase == Phase.SHUTDOWN) return false;
        generation++;
        activeToken = 0L;
        phase = Phase.SHUTDOWN;
        return true;
    }

    Phase phase() {
        return phase;
    }

    private void invalidateActive() {
        generation++;
        activeToken = 0L;
        phase = Phase.IDLE;
    }
}
