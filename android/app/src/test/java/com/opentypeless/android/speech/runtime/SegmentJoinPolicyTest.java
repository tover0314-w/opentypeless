package com.opentypeless.android.speech.runtime;

import static org.junit.Assert.assertEquals;

import com.opentypeless.android.speech.core.SegmentJoin;
import org.junit.Test;

public final class SegmentJoinPolicyTest {
    @Test
    public void joinsLatinWordsWithOneSpace() {
        assertEquals(SegmentJoin.SPACE, SegmentJoinPolicy.choose("hello", "world", "en-US"));
        assertEquals(SegmentJoin.SPACE, SegmentJoinPolicy.choose("OpenTypeless", "2", "zh-CN"));
    }

    @Test
    public void joinsHanWithoutInventingWhitespace() {
        assertEquals(SegmentJoin.NONE, SegmentJoinPolicy.choose("第一段。", "第二段", "zh-CN"));
        assertEquals(SegmentJoin.NONE, SegmentJoinPolicy.choose("中文", "OpenTypeless", "zh-CN"));
    }

    @Test
    public void firstSegmentNeverHasALeadingDelimiter() {
        assertEquals(SegmentJoin.NONE, SegmentJoinPolicy.choose("", "hello", "en-US"));
    }
}
