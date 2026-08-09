package com.opentypeless.android.data;

public record HistoryEntry(
        long id,
        long createdAt,
        String appPackage,
        String fieldKind,
        String mode,
        String backend,
        String rawText,
        String finalText,
        long durationMs) {}
