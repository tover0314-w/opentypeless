package com.opentypeless.android.audio;

import java.util.concurrent.atomic.AtomicReference;

public final class RecordingSession {
    public enum EndState { ACTIVE, STOPPED, CANCELLED }

    private final AtomicReference<EndState> state = new AtomicReference<>(EndState.ACTIVE);
    private final boolean userControlledEndpointing;

    public RecordingSession() {
        this(false);
    }

    public RecordingSession(boolean userControlledEndpointing) {
        this.userControlledEndpointing = userControlledEndpointing;
    }

    public void stop() {
        state.compareAndSet(EndState.ACTIVE, EndState.STOPPED);
    }

    public void cancel() {
        state.set(EndState.CANCELLED);
    }

    public boolean isActive() {
        return state.get() == EndState.ACTIVE;
    }

    public boolean isCancelled() {
        return state.get() == EndState.CANCELLED;
    }

    public EndState endState() {
        return state.get();
    }

    public boolean userControlledEndpointing() {
        return userControlledEndpointing;
    }
}
