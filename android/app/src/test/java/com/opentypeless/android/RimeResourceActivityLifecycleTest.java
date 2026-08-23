package com.opentypeless.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RimeResourceActivityLifecycleTest {
    @Test
    public void pickerResumeCannotInvalidateActiveImportGeneration() {
        assertFalse(RimeResourceActivity.shouldRefreshOnResume(true));
        assertTrue(RimeResourceActivity.shouldRefreshOnResume(false));
    }
}
