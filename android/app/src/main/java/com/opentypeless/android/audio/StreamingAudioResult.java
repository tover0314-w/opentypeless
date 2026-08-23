package com.opentypeless.android.audio;

/** Bounded metadata from a streaming capture; no complete recording is retained in memory. */
public record StreamingAudioResult(
        long durationMs,
        boolean reachedLimit,
        boolean autoStopped) {}
