package com.opentypeless.android.ime;

import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;

public record DictationRequest(
        AppSettings settings,
        ProcessingMode requestedMode,
        InputContext inputContext,
        PersonalizationSnapshot personalization,
        CaptureMode captureMode) {

    public enum CaptureMode {
        SINGLE_UTTERANCE(false),
        HOLD_TO_TALK(true),
        CONTINUOUS(true);

        private final boolean userControlledEndpointing;

        CaptureMode(boolean userControlledEndpointing) {
            this.userControlledEndpointing = userControlledEndpointing;
        }

        public boolean userControlledEndpointing() {
            return userControlledEndpointing;
        }
    }

    public DictationRequest {
        captureMode = captureMode == null ? CaptureMode.SINGLE_UTTERANCE : captureMode;
    }

    public DictationRequest(
            AppSettings settings,
            ProcessingMode requestedMode,
            InputContext inputContext,
            PersonalizationSnapshot personalization) {
        this(
                settings,
                requestedMode,
                inputContext,
                personalization,
                CaptureMode.SINGLE_UTTERANCE);
    }
}
