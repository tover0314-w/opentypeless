package com.opentypeless.android.speech.journal;

/** Bounded storage policy for encrypted Speech Core v2 sessions. */
public record JournalLimits(
        int maxSessions,
        long maxTotalBytes,
        long maxSessionBytes,
        int maxRecordsPerSession,
        int maxSegmentsPerSession,
        int maxAudioChunkBytes,
        int maxTextBytes,
        long recoveryTtlMs,
        long discardTombstoneTtlMs) {

    public static final JournalLimits DEFAULT = new JournalLimits(
            8,
            96L * 1024L * 1024L,
            32L * 1024L * 1024L,
            8_192,
            256,
            512 * 1024,
            256 * 1024,
            72L * 60L * 60L * 1_000L,
            60L * 60L * 1_000L);

    public JournalLimits {
        if (maxSessions <= 0
                || maxTotalBytes <= 0L
                || maxSessionBytes <= 0L
                || maxSessionBytes > maxTotalBytes
                || maxRecordsPerSession <= 0
                || maxSegmentsPerSession <= 0
                || maxAudioChunkBytes <= 0
                || maxTextBytes <= 0
                || recoveryTtlMs <= 0L
                || discardTombstoneTtlMs <= 0L
                || discardTombstoneTtlMs > recoveryTtlMs) {
            throw new IllegalArgumentException("journal limits are incoherent");
        }
    }
}
