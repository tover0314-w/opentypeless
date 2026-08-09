package com.opentypeless.android.personalization;

import java.util.List;

public record ProcessingResult(
        String text,
        List<Long> matchedTermIds,
        List<Long> matchedCorrectionIds) {}
