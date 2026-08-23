package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;

import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;
import com.opentypeless.android.speech.delivery.ProjectionMode;
import org.junit.Test;

public final class LocalSpeechCoreV2SessionTest {
    @Test
    public void longExactDictationCanSealCompletedSegments() {
        assertEquals(
                ProjectionMode.LONG_DICTATION,
                LocalSpeechCoreV2Session.chooseProjectionMode(request(
                        ProcessingMode.VERBATIM, false, DictationRequest.CaptureMode.CONTINUOUS)));
    }

    @Test
    public void configuredGlobalSmartRewriteKeepsWholeDocumentComposing() {
        assertEquals(
                ProjectionMode.SHORT_DICTATION,
                LocalSpeechCoreV2Session.chooseProjectionMode(request(
                        ProcessingMode.SMART, true, DictationRequest.CaptureMode.CONTINUOUS)));
        assertEquals(
                ProjectionMode.SHORT_DICTATION,
                LocalSpeechCoreV2Session.chooseProjectionMode(request(
                        ProcessingMode.AUTO, true, DictationRequest.CaptureMode.CONTINUOUS)));
    }

    @Test
    public void smartWithoutAConfiguredLlmUsesSafeSegmentDelivery() {
        assertEquals(
                ProjectionMode.LONG_DICTATION,
                LocalSpeechCoreV2Session.chooseProjectionMode(request(
                        ProcessingMode.SMART, false, DictationRequest.CaptureMode.CONTINUOUS)));
    }

    @Test
    public void holdToTalkAlwaysKeepsOneReplaceableComposition() {
        assertEquals(
                ProjectionMode.SHORT_DICTATION,
                LocalSpeechCoreV2Session.chooseProjectionMode(request(
                        ProcessingMode.VERBATIM, false, DictationRequest.CaptureMode.HOLD_TO_TALK)));
    }

    private static DictationRequest request(
            ProcessingMode mode,
            boolean llmConfigured,
            DictationRequest.CaptureMode captureMode) {
        AppSettings settings = new AppSettings(
                RecognitionBackend.LOCAL_OFFLINE,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "zh-CN",
                ProcessingMode.AUTO,
                llmConfigured,
                llmConfigured ? "https://example.com/v1" : "",
                "",
                llmConfigured ? "small-edit-model" : "",
                "English",
                "",
                true,
                false,
                false,
                180);
        return new DictationRequest(
                settings,
                mode,
                new InputContext(
                        "com.example.editor",
                        FieldKind.GENERAL,
                        "",
                        "",
                        true),
                PersonalizationSnapshot.empty(),
                captureMode);
    }
}
