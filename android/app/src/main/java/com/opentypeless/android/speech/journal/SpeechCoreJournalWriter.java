package com.opentypeless.android.speech.journal;

import com.opentypeless.android.speech.audio.SegmentAudio;
import com.opentypeless.android.speech.core.DeliveryState;
import com.opentypeless.android.speech.core.VoiceDraftEvent;
import com.opentypeless.android.speech.runtime.CoordinatorUpdate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Worker-only adapter from accepted coordinator events to the encrypted v2 journal.
 *
 * <p>Disk/Keystore failure is reported as degraded durability and never mutates or clears the
 * authoritative in-process {@code VoiceDraft} carried by the coordinator update.
 */
public final class SpeechCoreJournalWriter implements AutoCloseable {
    private final VoiceDraftJournal.Session session;
    private boolean terminal;

    public SpeechCoreJournalWriter(
            VoiceDraftJournal journal,
            JournalSessionMetadata metadata) {
        session = Objects.requireNonNull(journal, "journal")
                .startSession(Objects.requireNonNull(metadata, "metadata"));
    }

    SpeechCoreJournalWriter(VoiceDraftJournal.Session session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public synchronized JournalToken token() {
        return session.token();
    }

    public synchronized JournalSyncReport sync(CoordinatorUpdate update) {
        Objects.requireNonNull(update, "update");
        MutableReport report = new MutableReport();
        if (terminal) {
            report.failures.add(new JournalSyncFailure("sync", "journal writer is terminal"));
            return report.finish(JournalSyncDisposition.TERMINAL);
        }
        for (VoiceDraftEvent event : update.acceptedEvents()) {
            try {
                if (event instanceof VoiceDraftEvent.OpenSegment opened) {
                    report.record(
                            "open_segment",
                            session.openSegment(opened.segmentId(), opened.joinBefore()));
                } else if (event instanceof VoiceDraftEvent.RevisionArrived revision) {
                    report.record("revision", session.appendRevision(revision.revision()));
                } else if (event instanceof VoiceDraftEvent.SealSegment sealed) {
                    report.record("seal_segment", session.sealSegment(sealed.segmentId()));
                } else if (event instanceof VoiceDraftEvent.DeliveryChanged delivery) {
                    report.record(
                            "delivery",
                            session.updateDelivery(
                                    delivery.segmentId(), delivery.deliveryState()));
                } else if (event instanceof VoiceDraftEvent.TargetDetached detached) {
                    report.record(
                            "target_detached",
                            session.updateDelivery(detached.segmentId(), DeliveryState.FROZEN));
                } else if (event instanceof VoiceDraftEvent.CaptureEnded ended) {
                    report.record("capture_ended", session.end(ended.reason()));
                } else if (event instanceof VoiceDraftEvent.CaptureFailed failed) {
                    report.record("capture_failed", session.end(failed.reason()));
                } else if (event instanceof VoiceDraftEvent.ExplicitDiscard) {
                    report.record("explicit_discard", session.discard());
                    terminal = true;
                }
            } catch (RuntimeException failure) {
                report.failures.add(new JournalSyncFailure(
                        operation(event), failure.getClass().getSimpleName()));
            }
        }
        return report.finish(terminal
                ? JournalSyncDisposition.TERMINAL
                : report.failures.isEmpty()
                        ? JournalSyncDisposition.SYNCED
                        : JournalSyncDisposition.DEGRADED_IN_PROCESS_DRAFT_PRESERVED);
    }

    /** Persists one hard-closed PCM segment; callers should zeroize {@code audio} afterwards. */
    public synchronized JournalSyncReport syncAudio(SegmentAudio audio) {
        Objects.requireNonNull(audio, "audio");
        MutableReport report = new MutableReport();
        if (terminal) {
            report.failures.add(new JournalSyncFailure("audio", "journal writer is terminal"));
            return report.finish(JournalSyncDisposition.TERMINAL);
        }
        short[] samples = audio.samples();
        byte[] pcm = new byte[Math.multiplyExact(samples.length, 2)];
        try {
            for (int index = 0; index < samples.length; index++) {
                pcm[index * 2] = (byte) (samples[index] & 0xff);
                pcm[index * 2 + 1] = (byte) ((samples[index] >>> 8) & 0xff);
            }
            report.record(
                    "audio",
                    session.appendAudioChunk(
                            audio.segmentId(),
                            0L,
                            audio.audioStartSample(),
                            audio.sampleRate(),
                            pcm));
        } catch (RuntimeException failure) {
            report.failures.add(new JournalSyncFailure(
                    "audio", failure.getClass().getSimpleName()));
        } finally {
            Arrays.fill(samples, (short) 0);
            Arrays.fill(pcm, (byte) 0);
        }
        return report.finish(report.failures.isEmpty()
                ? JournalSyncDisposition.SYNCED
                : JournalSyncDisposition.DEGRADED_IN_PROCESS_DRAFT_PRESERVED);
    }

    /** Call only after the editor/recovery consumer acknowledged the exact owned draft. */
    public synchronized JournalSyncReport acknowledge() {
        MutableReport report = new MutableReport();
        if (terminal) {
            report.failures.add(new JournalSyncFailure("acknowledge", "journal writer is terminal"));
            return report.finish(JournalSyncDisposition.TERMINAL);
        }
        try {
            report.record("acknowledge", session.acknowledge());
        } catch (RuntimeException failure) {
            report.failures.add(new JournalSyncFailure(
                    "acknowledge", failure.getClass().getSimpleName()));
        }
        if (report.failures.isEmpty()) terminal = true;
        return report.finish(terminal
                ? JournalSyncDisposition.TERMINAL
                : JournalSyncDisposition.DEGRADED_IN_PROCESS_DRAFT_PRESERVED);
    }

    @Override
    public synchronized void close() {
        session.close();
        terminal = true;
    }

    private static String operation(VoiceDraftEvent event) {
        return event.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    private static final class MutableReport {
        private int written;
        private int duplicates;
        private final List<JournalSyncFailure> failures = new ArrayList<>();

        private void record(String operation, JournalWriteResult result) {
            if (result == JournalWriteResult.WRITTEN) {
                written++;
            } else if (result == JournalWriteResult.IGNORED_DUPLICATE) {
                duplicates++;
            } else {
                failures.add(new JournalSyncFailure(operation, result.name()));
            }
        }

        private JournalSyncReport finish(JournalSyncDisposition disposition) {
            return new JournalSyncReport(disposition, written, duplicates, failures);
        }
    }
}
