package com.opentypeless.android.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LocalOfflineRecognizerTest {
    @Test
    public void supportsOnlyPackaged64BitAbis() {
        assertTrue(LocalOfflineRecognizer.supportsAbi(new String[] {"arm64-v8a", "armeabi-v7a"}));
        assertTrue(LocalOfflineRecognizer.supportsAbi(new String[] {"x86_64", "x86"}));
        assertFalse(LocalOfflineRecognizer.supportsAbi(new String[] {"armeabi-v7a", "x86"}));
        assertFalse(LocalOfflineRecognizer.supportsAbi(new String[0]));
        assertFalse(LocalOfflineRecognizer.supportsAbi(null));
    }

    @Test
    public void locksOnlyExplicitMandarinLanguageFamilies() {
        assertEquals("zh", LocalOfflineRecognizer.senseVoiceLanguage("zh"));
        assertEquals("zh", LocalOfflineRecognizer.senseVoiceLanguage(" ZH_hans-CN "));
        assertEquals("zh", LocalOfflineRecognizer.senseVoiceLanguage("cmn-Hans-CN"));
        assertEquals("auto", LocalOfflineRecognizer.senseVoiceLanguage("en-US"));
        assertEquals("auto", LocalOfflineRecognizer.senseVoiceLanguage("yue-HK"));
        assertEquals("auto", LocalOfflineRecognizer.senseVoiceLanguage(""));
        assertEquals("auto", LocalOfflineRecognizer.senseVoiceLanguage(null));
    }
}
