package com.opentypeless.android.speech.journal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.speech.core.DeliveryState;
import com.opentypeless.android.speech.core.RevisionOrigin;
import com.opentypeless.android.speech.core.RevisionStage;
import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SegmentRevision;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.speech.core.TerminalReason;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;

/** Real AndroidKeyStore and filesystem contract for the Speech Core v2 journal. */
@RunWith(AndroidJUnit4.class)
public final class VoiceDraftJournalInstrumentedTest {
    private Context context;
    private File directory;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        directory = new File(context.getNoBackupFilesDir(), "speech-core-v2-journal");
        deleteTree(directory);
    }

    @After
    public void tearDown() throws Exception {
        deleteTree(directory);
    }

    @Test
    public void androidKeyStoreRecoversMultipleSegmentsWithoutPlaintextAtRest() throws Exception {
        VoiceDraftJournal first = new VoiceDraftJournal(context);
        JournalSessionMetadata metadata = metadata("device-keystore-recovery", 11L);
        VoiceDraftJournal.Session session = first.startSession(metadata);

        byte[] firstAudio = pcm(101, -203, 307, -409);
        assertEquals(JournalWriteResult.WRITTEN, session.openSegment(1L, SegmentJoin.NONE));
        assertEquals(
                JournalWriteResult.WRITTEN,
                session.appendAudioChunk(1L, 0L, 0L, 16_000, firstAudio));
        assertEquals(
                JournalWriteResult.WRITTEN,
                session.appendRevision(revision(
                        metadata.sessionId(), 1L, 1L, RevisionStage.LIVE,
                        "小米十五的私密实时草稿")));
        assertEquals(JournalWriteResult.WRITTEN, session.sealSegment(1L));
        assertEquals(
                JournalWriteResult.WRITTEN,
                session.updateDelivery(1L, DeliveryState.COMPOSING));

        byte[] secondAudio = pcm(503, -607, 709);
        assertEquals(JournalWriteResult.WRITTEN, session.openSegment(2L, SegmentJoin.SPACE));
        assertEquals(
                JournalWriteResult.WRITTEN,
                session.appendAudioChunk(2L, 0L, 4L, 16_000, secondAudio));
        assertEquals(
                JournalWriteResult.WRITTEN,
                session.appendRevision(revision(
                        metadata.sessionId(), 2L, 1L, RevisionStage.REFINED,
                        "OpenTypeless final phrase")));
        assertEquals(JournalWriteResult.WRITTEN, session.sealSegment(2L));
        assertEquals(JournalWriteResult.WRITTEN, session.end(TerminalReason.USER_FINISH));
        session.close();

        byte[] stored = Files.readAllBytes(singleJournal().toPath());
        assertFalse(contains(
                stored, "小米十五的私密实时草稿".getBytes(StandardCharsets.UTF_8)));
        assertFalse(contains(
                stored, "OpenTypeless final phrase".getBytes(StandardCharsets.UTF_8)));
        assertFalse(contains(stored, firstAudio));
        assertFalse(contains(stored, secondAudio));

        VoiceDraftJournal recreated = new VoiceDraftJournal(context);
        JournalRecovery recovered = recreated.read(session.token());
        assertEquals("小米十五的私密实时草稿 OpenTypeless final phrase", recovered.renderedText());
        assertTrue(recovered.ended());
        assertEquals(TerminalReason.USER_FINISH, recovered.terminalReason());
        assertEquals(2, recovered.segments().size());
        assertArrayEquals(
                firstAudio,
                recovered.segments().get(0).audioChunks().get(0).pcm16LittleEndian());
        assertArrayEquals(
                secondAudio,
                recovered.segments().get(1).audioChunks().get(0).pcm16LittleEndian());
    }

    @Test
    public void crashTruncatedTailRepairsAndPreservesLastAuthenticatedRevision() throws Exception {
        VoiceDraftJournal journal = new VoiceDraftJournal(context);
        JournalSessionMetadata metadata = metadata("device-tail-repair", 12L);
        VoiceDraftJournal.Session session = journal.startSession(metadata);
        session.openSegment(1L, SegmentJoin.NONE);
        session.appendRevision(revision(
                metadata.sessionId(), 1L, 1L, RevisionStage.LIVE, "authenticated prefix"));
        File file = singleJournal();
        long authenticatedLength = file.length();
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.seek(output.length());
            output.writeInt(0x4f525632);
            output.writeLong(4L);
            output.writeInt(12);
            output.writeInt(128);
            output.write(new byte[] {1, 2, 3});
            output.getFD().sync();
        }

        JournalRecovery recovered = new VoiceDraftJournal(context).read(session.token());

        assertEquals("authenticated prefix", recovered.renderedText());
        assertEquals(authenticatedLength, file.length());
    }

    @Test
    public void discardCompactsAuthenticatedContentAndLateHandleCannotResurrectIt()
            throws Exception {
        VoiceDraftJournal ownerJournal = new VoiceDraftJournal(context);
        JournalSessionMetadata metadata = metadata("device-discard", 13L);
        VoiceDraftJournal.Session owner = ownerJournal.startSession(metadata);
        owner.openSegment(1L, SegmentJoin.NONE);
        owner.appendAudioChunk(1L, 0L, 0L, 16_000, new byte[64 * 1024]);
        owner.appendRevision(revision(
                metadata.sessionId(), 1L, 1L, RevisionStage.LIVE, "must disappear"));
        VoiceDraftJournal.Session late = new VoiceDraftJournal(context).resume(owner.token());
        long before = singleJournal().length();

        assertEquals(JournalWriteResult.WRITTEN, owner.discard());

        assertTrue(singleJournal().length() < before);
        assertEquals(
                JournalWriteResult.REJECTED_TERMINAL,
                late.appendRevision(revision(
                        metadata.sessionId(), 1L, 2L, RevisionStage.LIVE,
                        "late callback must not return")));
        assertNull(ownerJournal.read(owner.token()));
        assertTrue(ownerJournal.listRecoverable().isEmpty());
    }

    @Test
    public void acknowledgementDeletesRecoveredSessionAndRejectsLateAudio() throws Exception {
        VoiceDraftJournal journal = new VoiceDraftJournal(context);
        JournalSessionMetadata metadata = metadata("device-ack", 14L);
        VoiceDraftJournal.Session owner = journal.startSession(metadata);
        owner.openSegment(1L, SegmentJoin.NONE);
        VoiceDraftJournal.Session late = new VoiceDraftJournal(context).resume(owner.token());

        assertEquals(JournalWriteResult.WRITTEN, owner.acknowledge());

        assertFalse(hasJournalFile());
        assertEquals(
                JournalWriteResult.REJECTED_TERMINAL,
                late.appendAudioChunk(1L, 0L, 0L, 16_000, pcm(1, 2)));
        assertNull(journal.read(owner.token()));
    }

    private static JournalSessionMetadata metadata(String id, long generation) {
        return new JournalSessionMetadata(
                new SessionId(id),
                generation,
                System.currentTimeMillis(),
                "offline-two-pass",
                "paraformer-int8+sensevoice-int8",
                "zh-CN",
                16_000);
    }

    private static SegmentRevision revision(
            SessionId sessionId,
            long segmentId,
            long revisionId,
            RevisionStage stage,
            String text) {
        return SegmentRevision.text(
                sessionId,
                segmentId,
                revisionId,
                stage,
                text,
                stage == RevisionStage.LIVE
                        ? RevisionOrigin.STREAM_ASR
                        : RevisionOrigin.QUALITY_ASR,
                stage != RevisionStage.LIVE);
    }

    private File singleJournal() {
        File[] journals = directory.listFiles(
                file -> file.isFile() && file.getName().endsWith(".otv2"));
        assertTrue(journals != null && journals.length == 1);
        return journals[0];
    }

    private boolean hasJournalFile() {
        File[] journals = directory.listFiles(
                file -> file.isFile() && file.getName().endsWith(".otv2"));
        return journals != null && journals.length > 0;
    }

    private static byte[] pcm(int... samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int index = 0; index < samples.length; index++) {
            bytes[index * 2] = (byte) (samples[index] & 0xff);
            bytes[index * 2 + 1] = (byte) ((samples[index] >>> 8) & 0xff);
        }
        return bytes;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int index = 0; index <= haystack.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static void deleteTree(File root) throws Exception {
        if (!root.exists()) return;
        try (var paths = Files.walk(root.toPath())) {
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
