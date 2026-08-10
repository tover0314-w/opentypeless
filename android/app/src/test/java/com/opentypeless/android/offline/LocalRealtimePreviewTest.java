package com.opentypeless.android.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static void awaitCount(CountDownLatch latch, long expectedRemaining)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (latch.getCount() > expectedRemaining && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expectedRemaining, latch.getCount());
    }
}
