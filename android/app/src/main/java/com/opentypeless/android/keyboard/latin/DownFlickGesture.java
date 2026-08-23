package com.opentypeless.android.keyboard.latin;

/** Small deterministic state machine for one letter-key downward flick. */
final class DownFlickGesture {
    enum ReleaseAction {
        DELEGATE_TAP,
        COMMIT_ALTERNATE,
        CONSUME
    }

    private final float threshold;
    private boolean active;
    private boolean movedBeyondThreshold;
    private boolean alternateArmed;
    private boolean longPressCommitted;
    private float downX;
    private float downY;

    DownFlickGesture(float threshold) {
        if (!(threshold > 0f) || !Float.isFinite(threshold)) {
            throw new IllegalArgumentException("flick threshold must be finite and positive");
        }
        this.threshold = threshold;
    }

    void down(float x, float y) {
        active = true;
        movedBeyondThreshold = false;
        alternateArmed = false;
        longPressCommitted = false;
        downX = x;
        downY = y;
    }

    boolean move(float x, float y) {
        if (!active || longPressCommitted) return false;
        float horizontal = Math.abs(x - downX);
        float vertical = y - downY;
        if (horizontal >= threshold || Math.abs(vertical) >= threshold) {
            movedBeyondThreshold = true;
        }
        if (vertical >= threshold && vertical > horizontal) {
            alternateArmed = true;
        }
        return movedBeyondThreshold;
    }

    boolean commitLongPress() {
        // Accessibility and instrumentation may invoke performLongClick without a preceding
        // MotionEvent stream. Preserve that ordinary Button contract.
        if (!active) return true;
        if (movedBeyondThreshold || alternateArmed || longPressCommitted) return false;
        longPressCommitted = true;
        return true;
    }

    ReleaseAction up() {
        if (!active) return ReleaseAction.CONSUME;
        ReleaseAction result = alternateArmed && !longPressCommitted
                ? ReleaseAction.COMMIT_ALTERNATE
                : movedBeyondThreshold || longPressCommitted
                        ? ReleaseAction.CONSUME
                        : ReleaseAction.DELEGATE_TAP;
        reset();
        return result;
    }

    void cancel() {
        reset();
    }

    private void reset() {
        active = false;
        movedBeyondThreshold = false;
        alternateArmed = false;
        longPressCommitted = false;
    }
}
