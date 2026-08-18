package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.transform.IntegrityResult;
import com.opentypeless.android.transform.TranscriptIntegrityGuard;

import org.junit.Test;

import java.lang.reflect.Modifier;

public final class TranscriptIntegrityGuardStageTest {
    @Test
    public void delegatesSafeAndUnsafeCandidatesToTheExistingGuardExactly() {
        TranscriptIntegrityGuardStage stage = new TranscriptIntegrityGuardStage();
        TextProcessingPipeline.IntegrityRequest safe = request(
                "Meet Alex at 10:30",
                "Meet Alex at 10:30");
        TextProcessingPipeline.IntegrityRequest unsafe = request(
                "Meet Alex at 10:30",
                "Meet Blake at 11:30");

        assertEquals(
                TranscriptIntegrityGuard.validate(
                        safe.sourceText(),
                        safe.candidateText(),
                        safe.mode(),
                        safe.personalization()),
                stage.apply(safe));
        assertEquals(
                TranscriptIntegrityGuard.validate(
                        unsafe.sourceText(),
                        unsafe.candidateText(),
                        unsafe.mode(),
                        unsafe.personalization()),
                stage.apply(unsafe));
        assertTrue(stage.apply(safe).safe());
        assertFalse(stage.apply(unsafe).safe());
    }

    @Test
    public void preservesTranslationModeIntegritySemantics() {
        TextProcessingPipeline.IntegrityRequest request =
                new TextProcessingPipeline.IntegrityRequest(
                        "The amount is USD 20",
                        "金额是 USD 20",
                        ProcessingMode.TRANSLATE,
                        PersonalizationSnapshot.empty());

        IntegrityResult expected = TranscriptIntegrityGuard.validate(
                request.sourceText(),
                request.candidateText(),
                request.mode(),
                request.personalization());

        assertEquals(expected, new TranscriptIntegrityGuardStage().apply(request));
    }

    @Test
    public void stageIsPackageConfinedStatelessFinalAndRejectsNullRequests() {
        assertTrue(Modifier.isFinal(TranscriptIntegrityGuardStage.class.getModifiers()));
        assertFalse(Modifier.isPublic(TranscriptIntegrityGuardStage.class.getModifiers()));
        assertEquals(0, TranscriptIntegrityGuardStage.class.getDeclaredFields().length);
        assertThrows(
                NullPointerException.class,
                () -> new TranscriptIntegrityGuardStage().apply(null));
    }

    private static TextProcessingPipeline.IntegrityRequest request(
            String source,
            String candidate) {
        return new TextProcessingPipeline.IntegrityRequest(
                source,
                candidate,
                ProcessingMode.SMART,
                PersonalizationSnapshot.empty());
    }
}
