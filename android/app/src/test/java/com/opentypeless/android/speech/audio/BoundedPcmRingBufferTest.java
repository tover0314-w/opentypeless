package com.opentypeless.android.speech.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class BoundedPcmRingBufferTest {
    @Test
    public void contiguousChunksCanBeSlicedAcrossAppendBoundaries() {
        BoundedPcmRingBuffer ring = new BoundedPcmRingBuffer(1_000, 10);
        ring.append(new Pcm16Chunk(0L, new short[] {0, 1, 2, 3, 4}));
        ring.append(new Pcm16Chunk(5L, new short[] {5, 6, 7, 8, 9}));

        assertArrayEquals(new short[] {3, 4, 5, 6, 7}, ring.slice(3L, 8L));
        assertEquals(0L, ring.retainedStartSample());
        assertEquals(10L, ring.endSample());
    }

    @Test
    public void overflowRetainsOnlyBoundedNewestAudio() {
        BoundedPcmRingBuffer ring = new BoundedPcmRingBuffer(1_000, 5);
        ring.append(new Pcm16Chunk(0L, new short[] {0, 1, 2, 3}));
        ring.append(new Pcm16Chunk(4L, new short[] {4, 5, 6, 7}));

        assertEquals(3L, ring.retainedStartSample());
        assertArrayEquals(new short[] {3, 4, 5, 6, 7}, ring.slice(3L, 8L));
        assertThrows(IllegalArgumentException.class, () -> ring.slice(2L, 4L));
    }

    @Test
    public void chunksAndSlicesDoNotExposeMutableBackingStorage() {
        short[] source = new short[] {1, 2, 3};
        Pcm16Chunk chunk = new Pcm16Chunk(0L, source);
        source[0] = 99;
        short[] accessor = chunk.samples();
        accessor[1] = 88;

        BoundedPcmRingBuffer ring = new BoundedPcmRingBuffer(1_000, 10);
        ring.append(chunk);
        short[] first = ring.slice(0L, 3L);
        first[2] = 77;

        assertArrayEquals(new short[] {1, 2, 3}, ring.slice(0L, 3L));
    }

    @Test
    public void discontinuityAndUnavailableSliceFailClosed() {
        BoundedPcmRingBuffer ring = new BoundedPcmRingBuffer(1_000, 10);
        ring.append(new Pcm16Chunk(10L, new short[] {1, 2, 3}));

        assertThrows(
                IllegalArgumentException.class,
                () -> ring.append(new Pcm16Chunk(14L, new short[] {4})));
        assertThrows(IllegalArgumentException.class, () -> ring.slice(9L, 11L));
        assertThrows(IllegalArgumentException.class, () -> ring.slice(10L, 14L));
        assertThrows(IllegalArgumentException.class, () -> ring.slice(11L, 11L));
    }

    @Test
    public void closeZeroizesAndPermanentlyRejectsUse() {
        BoundedPcmRingBuffer ring = new BoundedPcmRingBuffer(1_000, 10);
        ring.append(new Pcm16Chunk(0L, new short[] {1, 2, 3}));

        ring.close();

        assertThrows(IllegalStateException.class, () -> ring.slice(0L, 1L));
        assertThrows(
                IllegalStateException.class,
                () -> ring.append(new Pcm16Chunk(3L, new short[] {4})));
    }
}
