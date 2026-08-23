package com.opentypeless.android.keyboard.latin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DownFlickGestureTest {
    @Test
    public void shortMovementDelegatesOrdinaryTap() {
        DownFlickGesture gesture = new DownFlickGesture(12f);

        gesture.down(20f, 20f);
        assertFalse(gesture.move(22f, 28f));

        assertEquals(DownFlickGesture.ReleaseAction.DELEGATE_TAP, gesture.up());
    }

    @Test
    public void downwardDominantMovementCommitsAlternateOnce() {
        DownFlickGesture gesture = new DownFlickGesture(12f);

        gesture.down(20f, 20f);
        assertTrue(gesture.move(24f, 33f));
        assertTrue(gesture.move(25f, 48f));

        assertEquals(DownFlickGesture.ReleaseAction.COMMIT_ALTERNATE, gesture.up());
        assertEquals(DownFlickGesture.ReleaseAction.CONSUME, gesture.up());
    }

    @Test
    public void horizontalOrUpwardMovementConsumesWithoutTyping() {
        DownFlickGesture horizontal = new DownFlickGesture(12f);
        horizontal.down(20f, 20f);
        assertTrue(horizontal.move(40f, 24f));
        assertEquals(DownFlickGesture.ReleaseAction.CONSUME, horizontal.up());

        DownFlickGesture upward = new DownFlickGesture(12f);
        upward.down(20f, 30f);
        assertTrue(upward.move(20f, 12f));
        assertEquals(DownFlickGesture.ReleaseAction.CONSUME, upward.up());
    }

    @Test
    public void longPressAndFlickCannotBothCommit() {
        DownFlickGesture held = new DownFlickGesture(12f);
        held.down(20f, 20f);
        assertTrue(held.commitLongPress());
        assertFalse(held.move(20f, 45f));
        assertEquals(DownFlickGesture.ReleaseAction.CONSUME, held.up());

        DownFlickGesture flicked = new DownFlickGesture(12f);
        flicked.down(20f, 20f);
        assertTrue(flicked.move(20f, 45f));
        assertFalse(flicked.commitLongPress());
        assertEquals(DownFlickGesture.ReleaseAction.COMMIT_ALTERNATE, flicked.up());
    }
}
