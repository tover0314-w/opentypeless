package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.offline.OfflineModelStore;
import com.opentypeless.android.offline.OfflineStreamingModelSpec;
import com.opentypeless.android.offline.OfflineStreamingModelStore;
import com.opentypeless.android.offline.OfflineStreamingRecognizer;
import com.opentypeless.android.speech.core.SessionId;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** STR-006 device contract against both private-process on-device model stages. */
@RunWith(AndroidJUnit4.class)
public final class TwoStageStreamingProviderInstrumentedTest {
    private static final String AUDIO_FILE = "str004-official-0.wav";
    private static final long AUDIO_BYTES = 321_744L;
    private static final String AUDIO_SHA256 =
            "7d93384ca14702cc584a7a33fe2fed92e89e708549161cb12ea38c916882103b";
    private static final int FRAME_BYTES = 16_000 * 2 * 40 / 1_000;

    @Test
    public void realTwoStageProviderPublishesPartialThenOneSenseVoiceFinal() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File audio = requireModelsAndPinnedFixture(context);
        byte[] wav = readAll(audio);
        byte[] pcm = Arrays.copyOfRange(wav, 44, wav.length);
        SessionId sessionId = SessionId.of("str006-real-two-stage");
        RecordingSink sink = new RecordingSink(sessionId);

        try (TwoStageStreamingProvider provider = TwoStageStreamingProvider.create(context)) {
            assertTrue(provider.probe() instanceof ProviderRegistry.ObservedAvailable);
            TwoStageStreamingProvider.StartRequest request =
                    new TwoStageStreamingProvider.StartRequest(sessionId, "zh-CN", true);
            assertTrue(provider.prepare(request) instanceof RecognitionProvider.Prepared);
            TwoStageStreamingProvider.StreamingSession session = provider.start(request, sink);
            assertTrue("Composite provider did not become ready",
                    sink.ready.await(35L, TimeUnit.SECONDS));

            for (int offset = 0; offset < pcm.length; offset += FRAME_BYTES) {
                int end = Math.min(pcm.length, offset + FRAME_BYTES);
                byte[] frame = Arrays.copyOfRange(pcm, offset, end);
                assertTrue("Composite rejected bounded PCM",
                        session.acceptPcm(frame, frame.length));
                SystemClock.sleep(Math.max(1L, frame.length * 1_000L / 32_000L));
            }
            session.stop();
            assertTrue("Composite provider did not publish a terminal",
                    sink.terminal.await(75L, TimeUnit.SECONDS));

            assertFalse(sink.events.isEmpty());
            assertTrue(sink.events.get(0) instanceof RecognitionEvent.Preparing);
            assertTrue(sink.events.stream().anyMatch(RecognitionEvent.Ready.class::isInstance));
            assertTrue(sink.events.stream().anyMatch(RecognitionEvent.Partial.class::isInstance));
            RecognitionEvent terminal = sink.events.get(sink.events.size() - 1);
            assertTrue(terminal instanceof RecognitionEvent.Final);
            String finalText = ((RecognitionEvent.Final) terminal).text();
            assertTrue(finalText.codePointCount(0, finalText.length()) > 0);
            assertEquals(pcm.length, session.acceptedPcmBytes());
            assertTrue(sink.dispositions.stream().allMatch(
                    RecognitionEventValidator.Disposition.ACCEPTED::equals));
            assertEquals(1L, sink.events.stream().filter(RecognitionEvent::terminal).count());
        } finally {
            Arrays.fill(pcm, (byte) 0);
            Arrays.fill(wav, (byte) 0);
        }
    }

    @Test
    public void emptyAndCancelledSessionsNeverStartASecondTerminal() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        requireModelsAndPinnedFixture(context);
        try (TwoStageStreamingProvider provider = TwoStageStreamingProvider.create(context)) {
            SessionId emptyId = SessionId.of("str006-empty");
            RecordingSink emptySink = new RecordingSink(emptyId);
            TwoStageStreamingProvider.StreamingSession empty = provider.start(
                    new TwoStageStreamingProvider.StartRequest(emptyId, "", false), emptySink);
            empty.stop();
            assertTrue(emptySink.terminal.await(5L, TimeUnit.SECONDS));
            assertTrue(emptySink.events.get(emptySink.events.size() - 1)
                    instanceof RecognitionEvent.Failure);
            assertEquals(1L, emptySink.events.stream().filter(RecognitionEvent::terminal).count());

            SessionId cancelId = SessionId.of("str006-cancel");
            RecordingSink cancelSink = new RecordingSink(cancelId);
            TwoStageStreamingProvider.StreamingSession cancelled = provider.start(
                    new TwoStageStreamingProvider.StartRequest(cancelId, "", false), cancelSink);
            assertTrue(cancelSink.ready.await(35L, TimeUnit.SECONDS));
            assertTrue(cancelled.acceptPcm(new byte[FRAME_BYTES], FRAME_BYTES));
            cancelled.cancel();
            assertTrue(cancelSink.terminal.await(5L, TimeUnit.SECONDS));
            assertTrue(cancelSink.events.get(cancelSink.events.size() - 1)
                    instanceof RecognitionEvent.Cancelled);
            assertEquals(1L, cancelSink.events.stream().filter(RecognitionEvent::terminal).count());
            assertFalse(cancelled.acceptPcm(new byte[]{1, 2}, 2));
        }
    }

    private static File requireModelsAndPinnedFixture(Context context)
            throws IOException, NoSuchAlgorithmException {
        Assume.assumeTrue(OfflineStreamingRecognizer.isInstalled(context));
        Assume.assumeTrue(OfflineModelStore.status(context) == OfflineModelStore.Status.INSTALLED);
        OfflineStreamingModelStore.InstalledModel model =
                OfflineStreamingModelStore.requireVerified(context);
        assertEquals(OfflineStreamingModelSpec.PARAFORMER_REVISION,
                OfflineStreamingModelSpec.REALTIME.revision());
        File audio = new File(model.directory(), AUDIO_FILE);
        Assume.assumeTrue(audio.isFile());
        assertEquals(AUDIO_BYTES, audio.length());
        assertEquals(AUDIO_SHA256, sha256(audio));
        return audio;
    }

    private static byte[] readAll(File file) throws IOException {
        if (file.length() != AUDIO_BYTES) throw new IOException("Pinned WAV size is invalid");
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) throw new IOException("Pinned WAV ended early");
                offset += read;
            }
        }
        return data;
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static final class RecordingSink implements RecognitionProvider.EventSink {
        private final RecognitionEventValidator validator;
        private final List<RecognitionEvent> events =
                Collections.synchronizedList(new ArrayList<>());
        private final List<RecognitionEventValidator.Disposition> dispositions =
                Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);

        private RecordingSink(SessionId sessionId) {
            validator = new RecognitionEventValidator(sessionId);
        }

        @Override
        public void onEvent(RecognitionEvent event) {
            dispositions.add(validator.accept(event));
            events.add(event);
            if (event instanceof RecognitionEvent.Ready) ready.countDown();
            if (event.terminal()) terminal.countDown();
        }
    }
}
