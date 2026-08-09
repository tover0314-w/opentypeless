package com.opentypeless.android.audio;

public record RecordedAudio(
        byte[] wav,
        long durationMs,
        boolean reachedLimit,
        boolean autoStopped) {}
