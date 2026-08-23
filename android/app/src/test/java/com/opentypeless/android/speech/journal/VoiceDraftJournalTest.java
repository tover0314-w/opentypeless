package com.opentypeless.android.speech.journal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.speech.core.DeliveryState;
import com.opentypeless.android.speech.core.RevisionOrigin;
import com.opentypeless.android.speech.core.RevisionStage;
import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SegmentRevision;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.speech.core.TerminalReason;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Test;

public final class VoiceDraftJournalTest {
    private static final SecretKey KEY = new SecretKeySpec(new byte[] {
        3, 1, 4, 1, 5, 9, 2, 6, 5, 3, 5, 8, 9, 7, 9, 3,
        2, 3, 8, 4, 6, 2, 6, 4, 3, 3, 8, 3, 2, 7, 9, 5
    }, "AES");
    private final List<File> temporaryDirectories = new ArrayList<>();

    @After
    public void cleanTemporaryDirectories() throws Exception {
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
    public void multiSegmentAudioTextDeliveryAndTerminalStateSurviveRecreation() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        File directory = tempDirectory();
        VoiceDraftJournal first = journal(directory, JournalLimits.DEFAULT, clock);
        JournalSessionMetadata metadata = metadata("recovery-session", 7L, clock.get());
        VoiceDraftJournal.Session session = first.startSession(metadata);

        assertEquals(JournalWriteResult.WRITTEN, session.openSegment(1L, SegmentJoin.NONE));
        byte[] audioOne = pcm(1, 2, 3, 4);
        assertEquals(
                JournalWriteResult.WRITTEN,
                session.appendAudioChunk(1L, 0L, 0L, 16_000, audioOne));
        assertEquals(
                JournalWriteResult.WRITTEN,
                session.appendRevision(revision(
                        metadata.sessionId(), 1L, 1L, RevisionStage.LIVE, "用户的秘密词")));
        assertEquals(JournalWriteResult.WRITTEN, session.sealSegment(1L));
        assertEquals(
                JournalWriteResult.WRITTEN,
                session.updateDelivery(1L, DeliveryState.COMPOSING));

        assertEquals(JournalWriteResult.WRITTEN, session.openSegment(2L, SegmentJoin.SPACE));
        byte[] audioTwo = pcm(5, 6, 7);
        assertEquals(
                JournalWriteResult.WRITTEN,
                session.appendAudioChunk(2L, 0L, 4L, 16_000, audioTwo));
        assertEquals(
                JournalWriteResult.WRITTEN,
                session.appendRevision(refined(
                        metadata.sessionId(), 2L, 1L, "OpenTypeless")));
        assertEquals(JournalWriteResult.WRITTEN, session.sealSegment(2L));
        assertEquals(
                JournalWriteResult.WRITTEN,
                session.end(TerminalReason.USER_FINISH));
        session.close();

        VoiceDraftJournal recreated = journal(directory, JournalLimits.DEFAULT, clock);
        JournalRecovery recovery = recreated.read(
                new JournalToken(metadata.sessionId(), metadata.generation()));

        assertEquals(metadata, recovery.metadata());
        assertEquals("用户的秘密词 OpenTypeless", recovery.renderedText());
        assertTrue(recovery.ended());
        assertEquals(TerminalReason.USER_FINISH, recovery.terminalReason());
        assertEquals(2, recovery.segments().size());
        assertArrayEquals(
                audioOne, recovery.segments().get(0).audioChunks().get(0).pcm16LittleEndian());
        assertArrayEquals(
                audioTwo, recovery.segments().get(1).audioChunks().get(0).pcm16LittleEndian());
        assertEquals(
                DeliveryState.COMPOSING, recovery.segments().get(0).deliveryState());
    }

    @Test
    public void plaintextTextAndPcmAreAbsentFromOnDiskJournal() throws Exception {
        AtomicLong clock = new AtomicLong(2_000L);
        File directory = tempDirectory();
        VoiceDraftJournal journal = journal(directory, JournalLimits.DEFAULT, clock);
        JournalSessionMetadata metadata = metadata("opaque-session-id", 1L, clock.get());
        VoiceDraftJournal.Session session = journal.startSession(metadata);
        session.openSegment(1L, SegmentJoin.NONE);
        byte[] audio = new byte[] {11, 22, 33, 44, 55, 66, 77, 88};
        session.appendAudioChunk(1L, 0L, 0L, 16_000, audio);
        String seededText = "SEED-PRIVATE-语音文本-948271";
        session.appendRevision(revision(
                metadata.sessionId(), 1L, 1L, RevisionStage.LIVE, seededText));

        byte[] stored = Files.readAllBytes(journalFile(directory).toPath());

        assertFalse(contains(stored, seededText.getBytes(StandardCharsets.UTF_8)));
        assertFalse(contains(stored, audio));
        assertTrue(stored.length > audio.length);
    }

    @Test
    public void truncatedTailIsAtomicallyIgnoredAndRepaired() throws Exception {
        AtomicLong clock = new AtomicLong(3_000L);
        File directory = tempDirectory();
        VoiceDraftJournal journal = journal(directory, JournalLimits.DEFAULT, clock);
        JournalSessionMetadata metadata = metadata("truncated-tail", 1L, clock.get());
        VoiceDraftJournal.Session session = journal.startSession(metadata);
        session.openSegment(1L, SegmentJoin.NONE);
        session.appendRevision(revision(
                metadata.sessionId(), 1L, 1L, RevisionStage.LIVE, "safe prefix"));
        File file = journalFile(directory);
        long validLength = file.length();
        Files.write(
                file.toPath(),
                new byte[] {0x4f, 0x52, 0x56, 0x32, 0, 0, 0},
                StandardOpenOption.APPEND);

        JournalRecovery recovered = journal.read(session.token());

        assertEquals("safe prefix", recovered.renderedText());
        assertEquals(validLength, file.length());
    }

    @Test
    public void authenticatedTamperIsQuarantinedAndDoesNotBlockNewSession() throws Exception {
        AtomicLong clock = new AtomicLong(4_000L);
        File directory = tempDirectory();
        VoiceDraftJournal journal = journal(directory, JournalLimits.DEFAULT, clock);
        JournalSessionMetadata metadata = metadata("tamper", 1L, clock.get());
        VoiceDraftJournal.Session session = journal.startSession(metadata);
        session.openSegment(1L, SegmentJoin.NONE);
        session.appendRevision(revision(
                metadata.sessionId(), 1L, 1L, RevisionStage.LIVE, "authenticated"));
        File file = journalFile(directory);
        byte[] bytes = Files.readAllBytes(file.toPath());
        bytes[bytes.length - 1] ^= 0x55;
        Files.write(file.toPath(), bytes);

        assertThrows(IllegalStateException.class, () -> journal.read(session.token()));
        assertTrue(journal.listRecoverable().isEmpty());
        assertFalse(file.exists());

        VoiceDraftJournal.Session safe = journal.startSession(
                metadata("new-safe-session", 1L, clock.incrementAndGet()));
        assertEquals(JournalWriteResult.WRITTEN, safe.openSegment(1L, SegmentJoin.NONE));
    }

    @Test
    public void discardCompactsToTombstoneAndLateHandlesCannotResurrectDraft() throws Exception {
        AtomicLong clock = new AtomicLong(5_000L);
        File directory = tempDirectory();
        VoiceDraftJournal journalA = journal(directory, JournalLimits.DEFAULT, clock);
        JournalSessionMetadata metadata = metadata("discard-race", 9L, clock.get());
        VoiceDraftJournal.Session owner = journalA.startSession(metadata);
        owner.openSegment(1L, SegmentJoin.NONE);
        owner.appendAudioChunk(1L, 0L, 0L, 16_000, new byte[64 * 1024]);
        owner.appendRevision(revision(
                metadata.sessionId(), 1L, 1L, RevisionStage.LIVE, "discard me"));
        long before = journalFile(directory).length();

        VoiceDraftJournal journalB = journal(directory, JournalLimits.DEFAULT, clock);
        VoiceDraftJournal.Session late = journalB.resume(owner.token());
        assertEquals(JournalWriteResult.WRITTEN, owner.discard());
        long after = journalFile(directory).length();

        assertTrue(after < before);
        assertEquals(
                JournalWriteResult.REJECTED_TERMINAL,
                late.appendRevision(revision(
                        metadata.sessionId(), 1L, 2L, RevisionStage.LIVE, "must not return")));
        assertNull(journalA.read(owner.token()));
        assertTrue(journalA.listRecoverable().isEmpty());
    }

    @Test
    public void acknowledgementDeletesOwnedFileAndRejectsLateCallback() throws Exception {
        AtomicLong clock = new AtomicLong(6_000L);
        File directory = tempDirectory();
        VoiceDraftJournal journal = journal(directory, JournalLimits.DEFAULT, clock);
        JournalSessionMetadata metadata = metadata("ack", 2L, clock.get());
        VoiceDraftJournal.Session owner = journal.startSession(metadata);
        owner.openSegment(1L, SegmentJoin.NONE);
        VoiceDraftJournal.Session late = journal.resume(owner.token());

        assertEquals(JournalWriteResult.WRITTEN, owner.acknowledge());
        assertFalse(journalFileOrNull(directory) != null);
        assertEquals(
                JournalWriteResult.REJECTED_TERMINAL,
                late.appendAudioChunk(1L, 0L, 0L, 16_000, pcm(1, 2)));
        assertNull(journal.read(owner.token()));
    }

    @Test
    public void duplicateStaleConflictAndGapWritesAreExplicit() throws Exception {
        AtomicLong clock = new AtomicLong(7_000L);
        File directory = tempDirectory();
        VoiceDraftJournal journal = journal(directory, JournalLimits.DEFAULT, clock);
        JournalSessionMetadata metadata = metadata("idempotence", 1L, clock.get());
        VoiceDraftJournal.Session session = journal.startSession(metadata);
        assertEquals(JournalWriteResult.WRITTEN, session.openSegment(1L, SegmentJoin.NONE));
        assertEquals(
                JournalWriteResult.IGNORED_DUPLICATE,
                session.openSegment(1L, SegmentJoin.NONE));

        byte[] firstAudio = pcm(1, 2, 3);
        assertEquals(
                JournalWriteResult.WRITTEN,
                session.appendAudioChunk(1L, 0L, 0L, 16_000, firstAudio));
        assertEquals(
                JournalWriteResult.IGNORED_DUPLICATE,
                session.appendAudioChunk(1L, 0L, 0L, 16_000, firstAudio));
        assertEquals(
                JournalWriteResult.REJECTED_CONFLICT,
                session.appendAudioChunk(1L, 0L, 0L, 16_000, pcm(9, 9, 9)));
        assertEquals(
                JournalWriteResult.REJECTED_CONFLICT,
                session.appendAudioChunk(1L, 1L, 99L, 16_000, pcm(4, 5)));

        SegmentRevision first = revision(
                metadata.sessionId(), 1L, 1L, RevisionStage.LIVE, "one");
        assertEquals(JournalWriteResult.WRITTEN, session.appendRevision(first));
        assertEquals(JournalWriteResult.IGNORED_DUPLICATE, session.appendRevision(first));
        assertEquals(
                JournalWriteResult.REJECTED_CONFLICT,
                session.appendRevision(revision(
                        metadata.sessionId(), 1L, 1L, RevisionStage.LIVE, "different")));
    }

    @Test
    public void twoProcessLikeHandlesLinearizeSameChunkToWrittenThenDuplicate() throws Exception {
        AtomicLong clock = new AtomicLong(8_000L);
        File directory = tempDirectory();
        VoiceDraftJournal firstJournal = journal(directory, JournalLimits.DEFAULT, clock);
        JournalSessionMetadata metadata = metadata("concurrent", 1L, clock.get());
        VoiceDraftJournal.Session first = firstJournal.startSession(metadata);
        first.openSegment(1L, SegmentJoin.NONE);
        VoiceDraftJournal.Session second =
                journal(directory, JournalLimits.DEFAULT, clock).resume(first.token());
        byte[] audio = pcm(1, 2, 3, 4);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<JournalWriteResult>> tasks = List.of(
                    () -> first.appendAudioChunk(1L, 0L, 0L, 16_000, audio),
                    () -> second.appendAudioChunk(1L, 0L, 0L, 16_000, audio));
            List<Future<JournalWriteResult>> futures = executor.invokeAll(tasks);
            List<JournalWriteResult> results =
                    List.of(futures.get(0).get(), futures.get(1).get());

            assertEquals(1L, results.stream()
                    .filter(result -> result == JournalWriteResult.WRITTEN)
                    .count());
            assertEquals(1L, results.stream()
                    .filter(result -> result == JournalWriteResult.IGNORED_DUPLICATE)
                    .count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void boundsAndRecordQuotaPreserveExistingRecovery() throws Exception {
        AtomicLong clock = new AtomicLong(9_000L);
        File directory = tempDirectory();
        JournalLimits small = new JournalLimits(
                2, 1_000L, 600L, 3, 2, 512, 128, 10_000L, 1_000L);
        VoiceDraftJournal journal = journal(directory, small, clock);
        JournalSessionMetadata metadata = metadata("bounded", 1L, clock.get());
        VoiceDraftJournal.Session session = journal.startSession(metadata);
        assertEquals(JournalWriteResult.WRITTEN, session.openSegment(1L, SegmentJoin.NONE));

        assertEquals(
                JournalWriteResult.REJECTED_BOUNDS,
                session.appendAudioChunk(1L, 0L, 0L, 16_000, new byte[512]));
        assertEquals(
                JournalWriteResult.WRITTEN,
                session.appendRevision(revision(
                        metadata.sessionId(), 1L, 1L, RevisionStage.LIVE, "kept")));
        assertEquals(
                JournalWriteResult.REJECTED_BOUNDS,
                session.updateDelivery(1L, DeliveryState.COMPOSING));
        assertEquals("kept", journal.read(session.token()).renderedText());
    }

    @Test
    public void ttlAndTombstoneCleanupAreBoundedAndGenerationSpecific() throws Exception {
        AtomicLong clock = new AtomicLong(10_000L);
        File directory = tempDirectory();
        JournalLimits limits = new JournalLimits(
                2, 10_000L, 5_000L, 100, 10, 1_024, 1_024, 1_000L, 100L);
        VoiceDraftJournal journal = journal(directory, limits, clock);
        JournalSessionMetadata metadata = metadata("ttl", 4L, clock.get());
        VoiceDraftJournal.Session session = journal.startSession(metadata);
        session.openSegment(1L, SegmentJoin.NONE);
        assertNull(journal.resume(new JournalToken(metadata.sessionId(), 5L)));

        assertEquals(JournalWriteResult.WRITTEN, session.discard());
        clock.addAndGet(101L);
        journal.cleanupExpired();
        assertNull(journalFileOrNull(directory));

        JournalSessionMetadata expiring = metadata("expires", 1L, clock.get());
        VoiceDraftJournal.Session old = journal.startSession(expiring);
        old.openSegment(1L, SegmentJoin.NONE);
        clock.addAndGet(1_001L);
        journal.cleanupExpired();
        assertNull(journal.read(old.token()));
    }

    @Test
    public void invalidDeliveryTransitionAndPostEndCaptureAreRejected() throws Exception {
        AtomicLong clock = new AtomicLong(11_000L);
        File directory = tempDirectory();
        VoiceDraftJournal journal = journal(directory, JournalLimits.DEFAULT, clock);
        JournalSessionMetadata metadata = metadata("terminal", 1L, clock.get());
        VoiceDraftJournal.Session session = journal.startSession(metadata);
        session.openSegment(1L, SegmentJoin.NONE);

        assertEquals(
                JournalWriteResult.REJECTED_CONFLICT,
                session.updateDelivery(1L, DeliveryState.COMMITTED));
        assertEquals(JournalWriteResult.WRITTEN, session.end(TerminalReason.USER_FINISH));
        assertEquals(
                JournalWriteResult.REJECTED_TERMINAL,
                session.appendAudioChunk(1L, 0L, 0L, 16_000, pcm(1, 2)));
        assertEquals(
                JournalWriteResult.REJECTED_TERMINAL,
                session.openSegment(2L, SegmentJoin.SPACE));
    }

    private VoiceDraftJournal journal(
            File directory, JournalLimits limits, AtomicLong clock) {
        return new VoiceDraftJournal(directory, KEY, limits, clock::get);
    }

    private File tempDirectory() throws Exception {
        File directory = Files.createTempDirectory("speech-core-v2-journal-test-").toFile();
        temporaryDirectories.add(directory);
        return directory;
    }

    private static JournalSessionMetadata metadata(
            String session, long generation, long createdAt) {
        return new JournalSessionMetadata(
                SessionId.of(session),
                generation,
                createdAt,
                "local-two-pass",
                "pinned-model-revision",
                "zh-CN",
                16_000);
    }

    private static SegmentRevision revision(
            SessionId session,
            long segmentId,
            long revisionId,
            RevisionStage stage,
            String text) {
        return SegmentRevision.text(
                session,
                segmentId,
                revisionId,
                stage,
                text,
                RevisionOrigin.STREAM_ASR,
                false);
    }

    private static SegmentRevision refined(
            SessionId session, long segmentId, long revisionId, String text) {
        return SegmentRevision.text(
                session,
                segmentId,
                revisionId,
                RevisionStage.REFINED,
                text,
                RevisionOrigin.QUALITY_ASR,
                true);
    }

    private static byte[] pcm(int... samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int index = 0; index < samples.length; index++) {
            bytes[index * 2] = (byte) (samples[index] & 0xff);
            bytes[index * 2 + 1] = (byte) ((samples[index] >>> 8) & 0xff);
        }
        return bytes;
    }

    private static File journalFile(File directory) {
        File result = journalFileOrNull(directory);
        if (result == null) throw new AssertionError("journal file is missing");
        return result;
    }

    private static File journalFileOrNull(File directory) {
        File[] files = directory.listFiles(file -> file.getName().endsWith(".otv2"));
        if (files == null || files.length == 0) return null;
        if (files.length != 1) throw new AssertionError("unexpected journal file count");
        return files[0];
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[start + index] != needle[index]) continue outer;
            }
            return true;
        }
        return false;
    }
}
