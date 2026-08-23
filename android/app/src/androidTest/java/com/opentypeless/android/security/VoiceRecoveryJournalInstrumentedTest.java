package com.opentypeless.android.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public final class VoiceRecoveryJournalInstrumentedTest {
    private static final String ID = "device_recovery_session_0001";
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        new VoiceRecoveryJournal(context).discardAny();
    }

    @After
    public void tearDown() {
        new VoiceRecoveryJournal(context).discardAny();
    }

    @Test
    public void androidKeystoreEntrySurvivesRecreationAndNeverStoresPlainContent()
            throws Exception {
        byte[] wav = "RIFF-xiaomi-private-utterance".getBytes(StandardCharsets.UTF_8);
        VoiceRecoveryJournal first = new VoiceRecoveryJournal(context);
        first.saveAudio(
                ID, "OPENAI_COMPATIBLE", "zh",
                "https://speech.example/v1", "whisper-test", 100L, 450L,
                false, false, wav);

        File file = new File(context.getNoBackupFilesDir(), "voice-recovery/pending.otvr");
        byte[] encryptedAudio = Files.readAllBytes(file.toPath());
        String audioBytes = new String(encryptedAudio, StandardCharsets.ISO_8859_1);
        assertFalse(audioBytes.contains("xiaomi-private-utterance"));
        assertFalse(audioBytes.contains("OPENAI_COMPATIBLE"));

        VoiceRecoveryJournal recreated = new VoiceRecoveryJournal(context);
        VoiceRecoveryJournal.Entry audio = recreated.read();
        assertEquals(ID, audio.id());
        assertArrayEquals(wav, audio.wav());

        recreated.complete(
                ID, audio.backend(), audio.language(), audio.endpoint(), audio.model(),
                audio.createdAtMillis(),
                audio.durationMs(), false, false, "小米十五恢复成功。");
        VoiceRecoveryJournal.Entry text = new VoiceRecoveryJournal(context).read();
        assertEquals(VoiceRecoveryJournal.Kind.COMPLETED_TEXT, text.kind());
        assertEquals("小米十五恢复成功。", text.completedText());
        assertFalse(new String(
                Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .contains("小米十五恢复成功"));
        assertFalse(recreated.discard("another_session_0001"));
        assertTrue(recreated.discard(ID));
        assertFalse(recreated.hasPending());
    }

    @Test
    public void androidKeystoreCheckpointIsRemovedWhenDiscardWinsTheWriteRace() {
        AtomicInteger acceptanceChecks = new AtomicInteger();
        VoiceRecoveryJournal journal = new VoiceRecoveryJournal(context);

        boolean saved = journal.saveAudioIfAccepted(
                ID, "LOCAL_OFFLINE", "zh", "", "sensevoice-small-int8",
                100L, 450L, false, false, new byte[] {1, 2, 3, 4},
                () -> acceptanceChecks.incrementAndGet() == 1);

        assertFalse(saved);
        assertEquals(2, acceptanceChecks.get());
        assertFalse(journal.hasPending());
    }
}
