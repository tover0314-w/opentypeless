package com.opentypeless.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AppVisualSystemTest {
    @Test
    public void compactAtNarrowWidth() {
        assertTrue(AppVisualSystem.compactFor(320, 1.0f));
        assertFalse(AppVisualSystem.compactFor(360, 1.0f));
    }

    @Test
    public void compactAtLargeFont() {
        assertTrue(AppVisualSystem.compactFor(411, 1.3f));
        assertFalse(AppVisualSystem.compactFor(411, 1.29f));
    }
}
