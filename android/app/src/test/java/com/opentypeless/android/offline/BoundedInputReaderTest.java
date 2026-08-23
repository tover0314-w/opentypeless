package com.opentypeless.android.offline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;

import org.junit.Test;

public final class BoundedInputReaderTest {
    @Test
    public void readsPayloadAtBothInclusiveBounds() throws Exception {
        byte[] minimum = new byte[44];
        byte[] maximum = new byte[128];

        assertArrayEquals(
                minimum,
                BoundedInputReader.read(new ByteArrayInputStream(minimum), 44, 128));
        assertArrayEquals(
                maximum,
                BoundedInputReader.read(new ByteArrayInputStream(maximum), 44, 128));
    }

    @Test
    public void rejectsTruncatedAudio() {
        assertThrows(
                IllegalStateException.class,
                () -> BoundedInputReader.read(new ByteArrayInputStream(new byte[43]), 44, 128));
    }

    @Test
    public void rejectsOneByteBeyondLimitBeforeGrowingOutput() {
        assertThrows(
                IllegalStateException.class,
                () -> BoundedInputReader.read(new ByteArrayInputStream(new byte[129]), 44, 128));
    }

    @Test
    public void rejectsInvalidBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BoundedInputReader.read(new ByteArrayInputStream(new byte[0]), 5, 4));
    }
}
