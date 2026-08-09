package com.opentypeless.android.ime;

import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;

public record DictationRequest(
        AppSettings settings,
        ProcessingMode requestedMode,
        InputContext inputContext,
        PersonalizationSnapshot personalization) {}
