package com.opentypeless.android.data;

public record CorrectionRule(
        long id,
        String pattern,
        String replacement,
        String appScope,
        int useCount,
        boolean enabled) {}
