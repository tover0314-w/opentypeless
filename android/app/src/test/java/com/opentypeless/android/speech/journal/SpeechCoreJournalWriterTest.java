package com.opentypeless.android.speech.journal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.speech.audio.HardBoundaryReason;
import com.opentypeless.android.speech.audio.SegmentAudio;
import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.speech.core.TerminalReason;
import com.opentypeless.android.speech.engine.EngineCapabilities;
import com.opentypeless.android.speech.engine.EngineCapability;
import com.opentypeless.android.speech.engine.EngineDescriptor;
import com.opentypeless.android.speech.engine.ProcessingLocation;
import com.opentypeless.android.speech.runtime.CoordinatorUpdate;
import com.opentypeless.android.speech.runtime.RuntimeStrategy;
import com.opentypeless.android.speech.runtime.RuntimeStrategyDecision;
import com.opentypeless.android.speech.runtime.SpeechCoreCoordinator;
import com.opentypeless.android.speech.runtime.SpeechSessionToken;
import com.opentypeless.android.speech.runtime.StreamingRevisionInput;
import com.opentypeless.android.speech.transform.SegmentTransformPolicy;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Test;

public final class SpeechCoreJournalWriterTest {
    private static final SecretKey KEY = new SecretKeySpec(new byte[] {
        8, 5, 3, 2, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 1,
        1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 1, 1, 2, 3, 5, 8
    }, "AES");
    private final List<File> temporaryDirectories = new ArrayList<>();

    @After
    public void clean() throws Exception {
        for (File directory : temporaryDirectories) {
            if (!directory.exists()) continue;
            try (var paths = Files.walk(directory.toPath())) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
            }
        }
    }

    @Test
    public void coordinatorAudioRevisionsAndTerminalStateRecoverAsOneSession() throws Exception {
        Fixture fixture = fixture("writer-recovery", 1L);
        assertSynced(fixture.writer.sync(fixture.coordinator.prepare(fixture.token)));
        assertSynced(fixture.writer.sync(fixture.coordinator.ready(fixture.token)));
        assertSynced(fixture.writer.sync(fixture.coordinator.openSegment(
                fixture.token, 1L, SegmentJoin.NONE)));
        assertSynced(fixture.writer.sync(fixture.coordinator.liveRevision(
                StreamingRevisionInput.text(fixture.token, 1L, 1L, "recover this segment"))));
        SegmentAudio audio = new SegmentAudio(
                1L,
                0L,
                160L,
                160L,
                16_000,
                HardBoundaryReason.EXPLICIT_FINISH,
                new short[160]);
        assertSynced(fixture.writer.syncAudio(audio));
        assertSynced(fixture.writer.sync(fixture.coordinator.hardBoundary(fixture.token, 1L)));
        assertSynced(fixture.writer.sync(fixture.coordinator.stopRequested(fixture.token)));
        assertSynced(fixture.writer.sync(fixture.coordinator.captureEnded(
                fixture.token, TerminalReason.USER_FINISH)));
        fixture.writer.close();

        JournalRecovery recovery = fixture.journal.read(
                new JournalToken(fixture.token.sessionId(), fixture.token.generation()));

        assertEquals("recover this segment.", recovery.renderedText());
        assertTrue(recovery.ended());
        assertEquals(TerminalReason.USER_FINISH, recovery.terminalReason());
        assertEquals(1, recovery.segments().size());
        assertEquals(320,
                recovery.segments().get(0).audioChunks().get(0).pcm16LittleEndian().length);
        assertTrue(recovery.segments().get(0).sealed());
    }

    @Test
    public void explicitDiscardTombstoneWinsAgainstLateCoordinatorWork() throws Exception {
        Fixture fixture = fixture("writer-discard", 2L);
        fixture.writer.sync(fixture.coordinator.prepare(fixture.token));
        fixture.writer.sync(fixture.coordinator.ready(fixture.token));
        fixture.writer.sync(fixture.coordinator.openSegment(
                fixture.token, 1L, SegmentJoin.NONE));
        fixture.writer.sync(fixture.coordinator.liveRevision(
                StreamingRevisionInput.text(fixture.token, 1L, 1L, "private words")));

        JournalSyncReport discarded = fixture.writer.sync(
                fixture.coordinator.explicitDiscard(fixture.token));
        CoordinatorUpdate late = fixture.coordinator.liveRevision(
                StreamingRevisionInput.text(fixture.token, 1L, 2L, "must not return"));
        JournalSyncReport lateSync = fixture.writer.sync(late);

        assertEquals(JournalSyncDisposition.TERMINAL, discarded.disposition());
        assertEquals(JournalSyncDisposition.TERMINAL, lateSync.disposition());
        assertNull(fixture.journal.read(fixture.writer.token()));
        assertTrue(fixture.journal.listRecoverable().isEmpty());
    }

    @Test
    public void acknowledgedExternalOwnerMakesLateWriterDegradedWithoutChangingDraft()
            throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        File directory = tempDirectory();
        VoiceDraftJournal journal = new VoiceDraftJournal(
                directory, KEY, JournalLimits.DEFAULT, clock::get);
        SpeechSessionToken token =
                new SpeechSessionToken(new SessionId("external-ack"), 3L);
        VoiceDraftJournal.Session owner = journal.startSession(metadata(token, clock.get()));
        VoiceDraftJournal.Session late = journal.resume(owner.token());
        SpeechCoreJournalWriter writer = new SpeechCoreJournalWriter(late);
        SpeechCoreCoordinator coordinator = coordinator(token);
        coordinator.prepare(token);
        coordinator.ready(token);
        CoordinatorUpdate opened = coordinator.openSegment(token, 1L, SegmentJoin.NONE);
        owner.acknowledge();

        JournalSyncReport report = writer.sync(opened);

        assertEquals(
                JournalSyncDisposition.DEGRADED_IN_PROCESS_DRAFT_PRESERVED,
                report.disposition());
        assertFalse(report.failures().isEmpty());
        assertEquals(1, coordinator.draft().segments().size());
        assertNull(journal.read(owner.token()));
    }

    @Test
    public void acknowledgementDeletesExactOwnedJournal() throws Exception {
        Fixture fixture = fixture("writer-ack", 4L);
        fixture.writer.sync(fixture.coordinator.prepare(fixture.token));
        fixture.writer.sync(fixture.coordinator.ready(fixture.token));

        JournalSyncReport acknowledged = fixture.writer.acknowledge();

        assertEquals(JournalSyncDisposition.TERMINAL, acknowledged.disposition());
        assertNull(fixture.journal.read(fixture.writer.token()));
        assertTrue(fixture.journal.listRecoverable().isEmpty());
    }

    private Fixture fixture(String id, long generation) throws Exception {
        AtomicLong clock = new AtomicLong(1_000L + generation);
        File directory = tempDirectory();
        VoiceDraftJournal journal = new VoiceDraftJournal(
                directory, KEY, JournalLimits.DEFAULT, clock::get);
        SpeechSessionToken token = new SpeechSessionToken(new SessionId(id), generation);
        SpeechCoreCoordinator coordinator = coordinator(token);
        SpeechCoreJournalWriter writer = new SpeechCoreJournalWriter(
                journal, metadata(token, clock.get()));
        return new Fixture(journal, writer, coordinator, token);
    }

    private static SpeechCoreCoordinator coordinator(SpeechSessionToken token) {
        EngineDescriptor streaming = new EngineDescriptor(
                "stream",
                "Streaming",
                "v1",
                ProcessingLocation.ON_DEVICE,
                EngineCapabilities.of(
                        EngineCapability.LIVE_REVISIONS,
                        EngineCapability.ON_DEVICE));
        RuntimeStrategyDecision strategy = new RuntimeStrategyDecision(
                RuntimeStrategy.STREAMING_ONLY, 0, 0, 0L, List.of("test"));
        return new SpeechCoreCoordinator(
                token,
                streaming,
                null,
                strategy,
                PersonalizationSnapshot.empty(),
                SegmentTransformPolicy.DEFAULT);
    }

    private static JournalSessionMetadata metadata(SpeechSessionToken token, long now) {
        return new JournalSessionMetadata(
                token.sessionId(), token.generation(), now,
                "stream", "v1", "en-US", 16_000);
    }

    private File tempDirectory() throws Exception {
        File directory = Files.createTempDirectory("speech-core-journal-writer-").toFile();
        temporaryDirectories.add(directory);
        return directory;
    }

    private static void assertSynced(JournalSyncReport report) {
        assertEquals(JournalSyncDisposition.SYNCED, report.disposition());
        assertTrue(report.failures().isEmpty());
    }

    private record Fixture(
            VoiceDraftJournal journal,
            SpeechCoreJournalWriter writer,
            SpeechCoreCoordinator coordinator,
            SpeechSessionToken token) {}
}
