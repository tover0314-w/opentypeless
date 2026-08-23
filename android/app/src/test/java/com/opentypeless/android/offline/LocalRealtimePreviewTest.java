package com.opentypeless.android.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class LocalRealtimePreviewTest {
    @Test
    public void emitsRevisableWavPrefixesAtBoundedIntervals() throws Exception {
        AtomicInteger decodes = new AtomicInteger();
        CountDownLatch updates = new CountDownLatch(2);
        List<String> partials = new ArrayList<>();
        LocalRealtimePreview preview = new LocalRealtimePreview(
                wav -> {
                    assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
                    return "draft " + decodes.incrementAndGet();
                },
                text -> {
                    synchronized (partials) {
                        partials.add(text);
                    }
                    updates.countDown();
                });
        try {
            byte[] interval = new byte[LocalRealtimePreview.STEP_PCM_BYTES];
            preview.accept(interval, interval.length);
            awaitCount(updates, 1);
            preview.accept(interval, interval.length);
            assertTrue(updates.await(2, TimeUnit.SECONDS));
        } finally {
            preview.close();
        }

        assertEquals(List.of("draft 1", "draft 2"), partials);
    }

    @Test
    public void closePreventsLaterAudioFromSchedulingWork() {
        AtomicInteger decodes = new AtomicInteger();
        LocalRealtimePreview preview = new LocalRealtimePreview(
                wav -> "draft " + decodes.incrementAndGet(),
                ignored -> {});
        preview.close();
        byte[] interval = new byte[LocalRealtimePreview.STEP_PCM_BYTES];
        preview.accept(interval, interval.length);
        assertEquals(0, decodes.get());
    }

    @Test
    public void prefixBufferIsHardCappedAndClearedOnClose() throws Exception {
        CountDownLatch decoded = new CountDownLatch(1);
        AtomicInteger wavLength = new AtomicInteger();
        AtomicReference<byte[]> decodedWav = new AtomicReference<>();
        LocalRealtimePreview preview = new LocalRealtimePreview(
                wav -> {
                    wavLength.set(wav.length);
                    decodedWav.set(wav);
                    decoded.countDown();
                    return "bounded";
                },
                ignored -> {});
        byte[] oversized = new byte[LocalRealtimePreview.MAX_PCM_BYTES + 123];
        Arrays.fill(oversized, (byte) 9);
        preview.accept(oversized, oversized.length);
        assertTrue(decoded.await(2, TimeUnit.SECONDS));
        preview.close();

        assertEquals(LocalRealtimePreview.MAX_PCM_BYTES + 44, wavLength.get());
        assertTrue(Arrays.equals(
                new byte[decodedWav.get().length],
                decodedWav.get()));
        Field pcmField = LocalRealtimePreview.class.getDeclaredField("pcm");
        pcmField.setAccessible(true);
        byte[] retained = (byte[]) pcmField.get(preview);
        assertTrue(Arrays.equals(new byte[retained.length], retained));
        assertFalse(preview.toString().contains("bounded"));
    }

    @Test
    public void coalescesWhileDecodingAndCancelDropsQueuedOrLateWork() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        AtomicInteger decodes = new AtomicInteger();
        LocalRealtimePreview preview = new LocalRealtimePreview(
                wav -> {
                    int decode = decodes.incrementAndGet();
                    if (decode == 1) {
                        firstStarted.countDown();
                        try {
                            releaseFirst.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }
                    } else {
                        secondFinished.countDown();
                    }
                    return "draft " + decode;
                },
                ignored -> {});
        byte[] interval = new byte[LocalRealtimePreview.STEP_PCM_BYTES];
        preview.accept(interval, interval.length);
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        preview.accept(interval, interval.length);
        preview.accept(interval, interval.length);
        releaseFirst.countDown();
        assertTrue(secondFinished.await(2, TimeUnit.SECONDS));

        preview.cancel();
        preview.accept(interval, interval.length);
        assertEquals(2, decodes.get());
        assertTrue(preview.toString().contains("closed=true"));
    }

    private static void awaitCount(CountDownLatch latch, long expectedRemaining)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (latch.getCount() > expectedRemaining && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expectedRemaining, latch.getCount());
    }
}
