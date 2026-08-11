package com.opentypeless.android.settings;

import static org.junit.Assert.assertEquals;

import android.os.Build;

import org.junit.Test;

public final class SettingsRepositoryDefaultRouteTest {
    @Test
    public void api31AndNewerDefaultToThePrivacyPreservingOnDeviceRoute() {
        assertEquals(
                RecognitionBackend.SYSTEM_ON_DEVICE,
                SettingsRepository.defaultBackendForSdk(Build.VERSION_CODES.S));
        assertEquals(
                RecognitionBackend.SYSTEM_ON_DEVICE,
                SettingsRepository.defaultBackendForSdk(36));
    }

    @Test
    public void olderDevicesUseThePlatformSpeechRouteWithoutProbingDuringLoad() {
        assertEquals(
                RecognitionBackend.SYSTEM_DEFAULT,
                SettingsRepository.defaultBackendForSdk(Build.VERSION_CODES.O));
        assertEquals(
                RecognitionBackend.SYSTEM_DEFAULT,
                SettingsRepository.defaultBackendForSdk(Build.VERSION_CODES.R));
    }
}
