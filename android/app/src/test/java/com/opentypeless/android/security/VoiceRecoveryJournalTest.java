package com.opentypeless.android.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.spec.SecretKeySpec;

public final class VoiceRecoveryJournalTest {
    private static final String FIRST_ID = "recovery_session_0001";
    private static final String SECOND_ID = "recovery_session_0002";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void audioAndCompletedTextRoundTripWithoutPlaintextAtRest() throws Exception {
        VoiceRecoveryJournal journal = journal();
        byte[] wav = "RIFF-private-spoken-audio".getBytes(StandardCharsets.UTF_8);

        journal.saveAudio(
                FIRST_ID, "OPENAI_COMPATIBLE", "zh",
                "https://speech.example/v1", "whisper-test", 1234L, 820L,
                false, false, wav);

        assertTrue(journal.hasPending());
        assertEquals(FIRST_ID, journal.pendingId());
        VoiceRecoveryJournal.Entry audio = journal.read();
        assertNotNull(audio);
        assertEquals(VoiceRecoveryJournal.Kind.AUDIO, audio.kind());
        assertEquals("OPENAI_COMPATIBLE", audio.backend());
        assertArrayEquals(wav, audio.wav());
        String storedAudio = new String(
                Files.readAllBytes(journal.pendingFileForTest().toPath()),
                StandardCharsets.ISO_8859_1);
        assertFalse(storedAudio.contains("private-spoken-audio"));
        assertFalse(storedAudio.contains("OPENAI_COMPATIBLE"));

        journal.complete(
                FIRST_ID, "OPENAI_COMPATIBLE", "zh",
                "https://speech.example/v1", "whisper-test", 1234L, 820L,
                false, false, "你好，世界。");

        VoiceRecoveryJournal.Entry text = journal.read();
        assertEquals(VoiceRecoveryJournal.Kind.COMPLETED_TEXT, text.kind());
        assertEquals("你好，世界。", text.completedText());
        assertEquals(0, text.wav().length);
        byte[] storedText = Files.readAllBytes(journal.pendingFileForTest().toPath());
        assertFalse(new String(storedText, StandardCharsets.UTF_8).contains("你好，世界。"));
    }

    @Test
    public void unresolvedEntryCannotBeOverwrittenAndAcknowledgementIsIdBound() {
        VoiceRecoveryJournal journal = journal();
        journal.saveAudio(
                FIRST_ID, "LOCAL_OFFLINE", "en", "", "sensevoice-small-int8", 1L, 500L,
                false, false, new byte[] {1, 2, 3});

        assertThrows(IllegalStateException.class, () -> journal.saveAudio(
                SECOND_ID, "LOCAL_OFFLINE", "en", "", "sensevoice-small-int8", 2L, 600L,
                false, false, new byte[] {4, 5, 6}));
        assertFalse(journal.discard(SECOND_ID));
        assertTrue(journal.hasPending());
        assertTrue(journal.discard(FIRST_ID));
        assertFalse(journal.hasPending());
    }

    @Test
    public void authenticatedTamperingFailsClosedAndCanStillBeExplicitlyDiscarded() throws Exception {
        VoiceRecoveryJournal journal = journal();
        journal.saveAudio(
                FIRST_ID, "LOCAL_OFFLINE", "zh", "", "sensevoice-small-int8", 1L, 500L,
                false, false, new byte[] {1, 2, 3, 4, 5});
        byte[] stored = Files.readAllBytes(journal.pendingFileForTest().toPath());
        stored[stored.length - 1] ^= 0x01;
        Files.write(journal.pendingFileForTest().toPath(), stored);

        assertThrows(IllegalStateException.class, journal::read);
        assertTrue(journal.discard(FIRST_ID));
        assertFalse(journal.hasPending());
    }

    @Test
    public void explicitDiscardThatRacesTheWriteCannotLeaveALateCheckpoint() {
        VoiceRecoveryJournal journal = journal();
        AtomicInteger acceptanceChecks = new AtomicInteger();

        boolean saved = journal.saveAudioIfAccepted(
                FIRST_ID, "LOCAL_OFFLINE", "zh", "", "sensevoice-small-int8",
                1L, 500L, false, false, new byte[] {1, 2, 3, 4},
                () -> acceptanceChecks.incrementAndGet() == 1);

        assertFalse(saved);
        assertEquals(2, acceptanceChecks.get());
        assertFalse(journal.hasPending());
    }

    private VoiceRecoveryJournal journal() {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) key[index] = (byte) (index + 1);
        return new VoiceRecoveryJournal(
                temporary.getRoot(),
                new SecretKeySpec(key, "AES"));
    }
}
