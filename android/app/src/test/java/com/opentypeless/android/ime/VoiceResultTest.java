package com.opentypeless.android.ime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;

import org.junit.Test;

import java.io.Serializable;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

public final class VoiceResultTest {
    @Test
    public void exactImmutableShapeKeepsEveryTextStageInOneArtifact() {
        RecordComponent[] voice = VoiceResult.class.getRecordComponents();
        assertArrayEquals(
                new String[] {
                    "rawText", "deterministicText", "candidateText", "finalText", "provenance"
                },
                componentNames(voice));
        assertArrayEquals(
                new Class<?>[] {
                    String.class, String.class, String.class, String.class, List.class
                },
                componentTypes(voice));
        assertArrayEquals(
                new String[] {"stage", "disposition"},
                componentNames(StageProvenance.class.getRecordComponents()));
        assertArrayEquals(
                new String[] {
                    "voiceResult",
                    "outcome",
                    "mode",
                    "backend",
                    "durationMs",
                    "reachedRecordingLimit",
                    "recoveredPartial",
                    "matchedTermIds",
                    "matchedCorrectionIds",
                    "recoveryId"
                },
                componentNames(DictationResult.class.getRecordComponents()));
        assertArrayEquals(
                new StageProvenance.Stage[] {
                    StageProvenance.Stage.RECOGNITION,
                    StageProvenance.Stage.DETERMINISTIC,
                    StageProvenance.Stage.LOCAL_COMMAND,
                    StageProvenance.Stage.OPTIONAL_LLM,
                    StageProvenance.Stage.INTEGRITY_GUARD,
                    StageProvenance.Stage.FINALIZATION
                },
                StageProvenance.Stage.values());

        ArrayList<StageProvenance> mutable = new ArrayList<>(normalProvenance());
        VoiceResult result = new VoiceResult("raw", "exact", "exact", "exact", mutable);
        mutable.clear();

        assertEquals(6, result.provenance().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.provenance().add(result.provenance().get(0)));
    }

    @Test
    public void terminalPathsRecordCommandCandidateIntegrityAndFallbackExactly() {
        VoiceResult command = VoiceResult.processed(
                "raw",
                "deterministic",
                "command",
                "command",
                StageProvenance.Disposition.APPLIED,
                StageProvenance.Disposition.SKIPPED,
                StageProvenance.Disposition.SKIPPED,
                StageProvenance.Disposition.PUBLISHED);
        assertEquals("command", command.candidateText());
        assertFalse(command.aiOutputAccepted());

        VoiceResult accepted = VoiceResult.processed(
                "raw",
                "deterministic",
                "candidate",
                "candidate",
                StageProvenance.Disposition.SKIPPED,
                StageProvenance.Disposition.APPLIED,
                StageProvenance.Disposition.ACCEPTED,
                StageProvenance.Disposition.PUBLISHED);
        assertTrue(accepted.aiOutputAccepted());

        VoiceResult blocked = VoiceResult.processed(
                "raw",
                "deterministic",
                "unsafe candidate",
                "deterministic",
                StageProvenance.Disposition.SKIPPED,
                StageProvenance.Disposition.APPLIED,
                StageProvenance.Disposition.REJECTED,
                StageProvenance.Disposition.FALLBACK);
        assertEquals("unsafe candidate", blocked.candidateText());
        assertEquals("deterministic", blocked.finalText());
        assertFalse(blocked.aiOutputAccepted());

        VoiceResult failed = VoiceResult.processed(
                "raw",
                "deterministic",
                "deterministic",
                "deterministic",
                StageProvenance.Disposition.SKIPPED,
                StageProvenance.Disposition.FAILED,
                StageProvenance.Disposition.SKIPPED,
                StageProvenance.Disposition.FALLBACK);
        assertEquals(
                StageProvenance.Disposition.FAILED,
                failed.provenance().get(StageProvenance.Stage.OPTIONAL_LLM.ordinal())
                        .disposition());
    }

    @Test
    public void recoveredArtifactSkipsProcessingWithoutInventingAnotherTextVersion() {
        VoiceResult recovered = VoiceResult.recovered("recovered 😀");

        assertEquals(recovered.rawText(), recovered.deterministicText());
        assertEquals(recovered.rawText(), recovered.candidateText());
        assertEquals(recovered.rawText(), recovered.finalText());
        assertEquals(
                StageProvenance.Disposition.RECOVERED,
                recovered.provenance().get(0).disposition());
        assertFalse(recovered.aiOutputAccepted());
    }

    @Test
    public void invalidStageOrderPairingAndTextRelationshipsAreRejected() {
        ArrayList<StageProvenance> wrongOrder = new ArrayList<>(normalProvenance());
        StageProvenance first = wrongOrder.get(0);
        wrongOrder.set(0, wrongOrder.get(1));
        wrongOrder.set(1, first);
        assertThrows(
                IllegalArgumentException.class,
                () -> new VoiceResult("raw", "exact", "exact", "exact", wrongOrder));
        assertThrows(
                IllegalArgumentException.class,
                () -> new StageProvenance(
                        StageProvenance.Stage.RECOGNITION,
                        StageProvenance.Disposition.ACCEPTED));
        assertThrows(
                IllegalArgumentException.class,
                () -> VoiceResult.processed(
                        "raw",
                        "deterministic",
                        "candidate",
                        "wrong final",
                        StageProvenance.Disposition.SKIPPED,
                        StageProvenance.Disposition.APPLIED,
                        StageProvenance.Disposition.ACCEPTED,
                        StageProvenance.Disposition.PUBLISHED));
        assertThrows(
                IllegalArgumentException.class,
                () -> VoiceResult.processed(
                        "raw",
                        "deterministic",
                        "command",
                        "command",
                        StageProvenance.Disposition.APPLIED,
                        StageProvenance.Disposition.APPLIED,
                        StageProvenance.Disposition.ACCEPTED,
                        StageProvenance.Disposition.PUBLISHED));
    }

    @Test
    public void textBoundaryRejectsNullMalformedAndOversizedExternalContent() {
        String maximum = "😀".repeat(VoiceResult.MAX_TEXT_CODE_POINTS);
        VoiceResult exact = VoiceResult.recovered(maximum);
        assertEquals(VoiceResult.MAX_TEXT_CODE_POINTS, exact.finalText().codePointCount(
                0, exact.finalText().length()));

        assertThrows(
                IllegalArgumentException.class,
                () -> VoiceResult.recovered(maximum + "x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> VoiceResult.recovered("broken\uD800"));
        assertThrows(NullPointerException.class, () -> VoiceResult.recovered(null));
    }

    @Test
    public void dictationCompatibilityAccessorsDelegateAndDiagnosticsStayRedacted() {
        String secret = "secret-voice-sentinel";
        VoiceResult voice = VoiceResult.processed(
                secret,
                "deterministic",
                "candidate",
                "candidate",
                StageProvenance.Disposition.SKIPPED,
                StageProvenance.Disposition.APPLIED,
                StageProvenance.Disposition.ACCEPTED,
                StageProvenance.Disposition.PUBLISHED);
        ArrayList<Long> termIds = new ArrayList<>(List.of(7L));
        DictationResult result = new DictationResult(
                voice,
                DictationResult.Outcome.SMART_EDITED,
                ProcessingMode.SMART,
                RecognitionBackend.LOCAL_OFFLINE,
                12L,
                false,
                false,
                termIds,
                List.of(9L),
                "recovery-id");
        termIds.clear();

        assertEquals(voice, result.voiceResult());
        assertEquals(secret, result.rawText());
        assertEquals("deterministic", result.personalizedText());
        assertEquals("candidate", result.finalText());
        assertTrue(result.aiOutputAccepted());
        assertEquals(List.of(7L), result.matchedTermIds());
        assertFalse(voice.toString().contains(secret));
        assertFalse(result.toString().contains(secret));
        assertFalse(Serializable.class.isAssignableFrom(VoiceResult.class));
        assertFalse(Serializable.class.isAssignableFrom(StageProvenance.class));
    }

    private static List<StageProvenance> normalProvenance() {
        return List.of(
                new StageProvenance(
                        StageProvenance.Stage.RECOGNITION,
                        StageProvenance.Disposition.CAPTURED),
                new StageProvenance(
                        StageProvenance.Stage.DETERMINISTIC,
                        StageProvenance.Disposition.APPLIED),
                new StageProvenance(
                        StageProvenance.Stage.LOCAL_COMMAND,
                        StageProvenance.Disposition.SKIPPED),
                new StageProvenance(
                        StageProvenance.Stage.OPTIONAL_LLM,
                        StageProvenance.Disposition.SKIPPED),
                new StageProvenance(
                        StageProvenance.Stage.INTEGRITY_GUARD,
                        StageProvenance.Disposition.SKIPPED),
                new StageProvenance(
                        StageProvenance.Stage.FINALIZATION,
                        StageProvenance.Disposition.PUBLISHED));
    }

    private static String[] componentNames(RecordComponent[] components) {
        String[] names = new String[components.length];
        for (int index = 0; index < components.length; index++) {
            names[index] = components[index].getName();
        }
        return names;
    }

    private static Class<?>[] componentTypes(RecordComponent[] components) {
        Class<?>[] types = new Class<?>[components.length];
        for (int index = 0; index < components.length; index++) {
            types[index] = components[index].getType();
        }
        return types;
    }
}
