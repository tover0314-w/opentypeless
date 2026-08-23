package com.opentypeless.android.speech.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StreamingHypothesisSlicerTest {
    @Test
    public void fullSessionHypothesisBecomesOneReplaceableActiveSegment() {
        StreamingHypothesisSlicer slicer = new StreamingHypothesisSlicer();

        assertEquals("你好", slicer.accept("你好").segmentText());
        assertEquals("你好世界", slicer.accept("你好世界").segmentText());
        slicer.sealAtCurrentHypothesis();

        StreamingHypothesisSlicer.Slice next = slicer.accept("你好世界 下一段");
        assertEquals("下一段", next.segmentText());
        assertTrue(next.reliable());
        assertFalse(next.earlierRewriteObserved());
    }

    @Test
    public void rewriteBeforeHardBoundaryNeverInventsANewSuffix() {
        StreamingHypothesisSlicer slicer = new StreamingHypothesisSlicer();
        slicer.accept("Cloud flare");
        slicer.sealAtCurrentHypothesis();

        StreamingHypothesisSlicer.Slice rewritten = slicer.accept("Cloudflare is ready");

        assertEquals("", rewritten.segmentText());
        assertFalse(rewritten.reliable());
        assertTrue(rewritten.earlierRewriteObserved());
    }

    @Test
    public void transientShorterCallbackRetainsTheCurrentVisibleTail() {
        StreamingHypothesisSlicer slicer = new StreamingHypothesisSlicer();
        slicer.accept("first");
        slicer.sealAtCurrentHypothesis();
        slicer.accept("first second");

        StreamingHypothesisSlicer.Slice transientUpdate = slicer.accept("fir");

        assertEquals("second", transientUpdate.segmentText());
        assertTrue(transientUpdate.earlierRewriteObserved());
    }
}
