package com.opentypeless.android.recognition;

import android.os.SystemClock;

import java.util.Arrays;

final class StandardRecognitionAccess {
    private static final RecognitionAccessController LIMITER =
            new RecognitionAccessController(30, 10 * 60_000L, SystemClock::elapsedRealtime);

    private StandardRecognitionAccess() {}

    static RecognitionAccessController.Decision forService(
            StandardRecognitionSettings.Snapshot settings,
            String packageName,
            String[] packagesForUid) {
        boolean identityMatches = packageName != null
                && packagesForUid != null
                && Arrays.asList(packagesForUid).contains(packageName);
        return LIMITER.authorize(
                settings.enabled(),
                settings.allows(packageName),
                identityMatches,
                "package:" + packageName);
    }

    static RecognitionAccessController.Decision forActivity(
            StandardRecognitionSettings.Snapshot settings,
            String packageName) {
        return LIMITER.authorize(
                settings.enabled(),
                settings.allows(packageName),
                packageName != null && !packageName.isBlank(),
                "package:" + packageName);
    }
}
