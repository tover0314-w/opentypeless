package com.opentypeless.android.recognition;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

public final class ProviderCapabilitiesTest {
    @Test
    public void onlyExplicitOnDeviceRouteClaimsOfflineGuarantee() {
        assertTrue(ProviderCapabilities.forBackend(
                RecognitionBackend.SYSTEM_ON_DEVICE).guaranteedOnDevice());
        assertFalse(ProviderCapabilities.forBackend(
                RecognitionBackend.SYSTEM_DEFAULT).guaranteedOnDevice());
        assertFalse(ProviderCapabilities.forBackend(
                RecognitionBackend.OPENAI_COMPATIBLE).guaranteedOnDevice());
    }

    @Test
    public void injectsPersonalizationOnlyThroughSupportedMechanism() {
        assertTrue(ProviderCapabilities.forBackend(
                RecognitionBackend.OPENAI_COMPATIBLE).asrPrompt());
        assertFalse(ProviderCapabilities.forBackend(
                RecognitionBackend.OPENAI_COMPATIBLE).biasingStrings());
        assertTrue(ProviderCapabilities.forBackend(
                RecognitionBackend.SYSTEM_ON_DEVICE).biasingStrings());
    }
}
