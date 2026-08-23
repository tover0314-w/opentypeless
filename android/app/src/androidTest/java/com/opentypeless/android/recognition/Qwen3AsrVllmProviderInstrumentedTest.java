package com.opentypeless.android.recognition;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.opentypeless.android.config.ProviderConfig;
import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.config.SecretRef;
import com.opentypeless.android.net.streaming.Qwen3AsrVllmClient;
import com.opentypeless.android.speech.core.SessionId;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

@RunWith(AndroidJUnit4.class)
public final class Qwen3AsrVllmProviderInstrumentedTest {
    private static final String MODEL = "Qwen/Qwen3-ASR-0.6B";

    @Test
    public void localFakeVllmStreamsFixedChineseEnglishAndMixedSamplesOnArt() throws Exception {
        String[] samples = {"你好世界", "open settings", "明天 review the plan"};
        for (int index = 0; index < samples.length; index++) {
            String transcript = samples[index];
            byte[] expectedPcm = {(byte) index, 2, 3, 4};
            List<byte[]> receivedPcm = new ArrayList<>();
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(jsonResponse("""
                        {"object":"list","data":[{"id":"Qwen/Qwen3-ASR-0.6B"}]}
                        """));
                server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket socket, okhttp3.Response response) {
                        socket.send("{\"type\":\"session.created\",\"id\":\"art\",\"created\":1}");
                    }

                    @Override
                    public void onMessage(WebSocket socket, String text) {
                        try {
                            JSONObject frame = new JSONObject(text);
                            String type = frame.getString("type");
                            if ("input_audio_buffer.append".equals(type)) {
                                receivedPcm.add(Base64.getDecoder().decode(
                                        frame.getString("audio")));
                            } else if ("input_audio_buffer.commit".equals(type)) {
                                socket.send(new JSONObject()
                                        .put("type", "transcription.delta")
                                        .put("delta", transcript)
                                        .toString());
                                socket.send(new JSONObject()
                                        .put("type", "transcription.done")
                                        .put("text", transcript)
                                        .put("usage", JSONObject.NULL)
                                        .toString());
                            }
                        } catch (Exception error) {
                            throw new AssertionError("invalid client protocol frame", error);
                        }
                    }

                    @Override
                    public void onClosing(WebSocket socket, int code, String reason) {
                        socket.close(code, reason);
                    }
                }));
                server.start();

                Qwen3AsrVllmProvider provider = provider(server);
                try {
                    List<ProviderRegistry.ProbeObservation> probes = new ArrayList<>();
                    assertTrue(provider.refreshCapabilities(probes::add));
                    assertTrue(probes.get(0) instanceof ProviderRegistry.ObservedAvailable);
                    assertEquals(
                            RecognitionRoute.PrivacyClass.LOCAL_NETWORK,
                            provider.descriptor().capabilities().privacyClass());

                    WebSocketStreamingProvider.StartRequest request =
                            new WebSocketStreamingProvider.StartRequest(
                                    SessionId.of("qwen-art-" + (index + 1)), "");
                    RecordingSink sink = new RecordingSink();
                    WebSocketStreamingProvider.StreamingSession session =
                            provider.start(request, sink);
                    assertTrue(sink.ready.await(3L, TimeUnit.SECONDS));
                    assertTrue(session.acceptPcm(expectedPcm, expectedPcm.length));
                    session.stop();
                    assertTrue(sink.terminal.await(3L, TimeUnit.SECONDS));
                    assertEquals(
                            List.of("Preparing", "Ready", "Partial", "Endpoint", "Final"),
                            sink.kinds());
                    assertEquals(transcript, ((RecognitionEvent.Final)
                            sink.events.get(sink.events.size() - 1)).text());
                    assertEquals(1, receivedPcm.size());
                    assertArrayEquals(expectedPcm, receivedPcm.get(0));
                } finally {
                    provider.close();
                }
            }
        }
    }

    @Test
    public void missingModelAndServerErrorStayContentFreeAndNeverAcceptAudioOnArt()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(jsonResponse("{\"object\":\"list\",\"data\":[]}"));
            server.start();
            Qwen3AsrVllmProvider provider = provider(server);
            try {
                List<ProviderRegistry.ProbeObservation> probes = new ArrayList<>();
                assertTrue(provider.refreshCapabilities(probes::add));
                ProviderRegistry.ObservedUnavailable unavailable =
                        (ProviderRegistry.ObservedUnavailable) probes.get(0);
                assertEquals(
                        RecognitionRoute.FailureClass.MODEL_MISSING,
                        unavailable.failureClass());
                WebSocketStreamingProvider.StartRequest request =
                        new WebSocketStreamingProvider.StartRequest(
                                SessionId.of("qwen-art-missing"), "");
                RecordingSink sink = new RecordingSink();
                WebSocketStreamingProvider.StreamingSession rejected =
                        provider.start(request, sink);
                assertFalse(rejected.acceptPcm(new byte[]{1, 2}, 2));
                assertEquals(1, sink.events.size());
                assertEquals(
                        RecognitionRoute.FailureClass.MODEL_MISSING,
                        ((RecognitionEvent.Failure) sink.events.get(0)).failureClass());
                assertFalse(provider.toString().contains(MODEL));
            } finally {
                provider.close();
            }
        }
    }

    private static Qwen3AsrVllmProvider provider(MockWebServer server) {
        ProviderConfig.Asr config = new ProviderConfig.Asr(
                "qwen.art",
                "Qwen ART",
                Optional.of(new ProviderConfig.Endpoint(server.url("/").toString())),
                Optional.of(MODEL),
                Optional.empty(),
                true);
        return new Qwen3AsrVllmProvider(
                config,
                new Qwen3AsrVllmClient(),
                noSecretAccess(),
                new InlineWorker());
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static Qwen3AsrVllmProvider.CredentialAccess noSecretAccess() {
        return new Qwen3AsrVllmProvider.CredentialAccess() {
            @Override
            public <T> T use(
                    SecretRef reference,
                    Qwen3AsrVllmProvider.CredentialOperation<T> operation) {
                throw new AssertionError("credential access was not expected");
            }
        };
    }

    private static final class InlineWorker implements Qwen3AsrVllmProvider.ProbeWorker {
        @Override
        public void execute(Runnable action) {
            action.run();
        }

        @Override
        public void close() {}
    }

    private static final class RecordingSink implements RecognitionProvider.EventSink {
        private final List<RecognitionEvent> events = new ArrayList<>();
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public synchronized void onEvent(RecognitionEvent event) {
            events.add(event);
            if (event instanceof RecognitionEvent.Ready) ready.countDown();
            if (event.terminal()) terminal.countDown();
        }

        private synchronized List<String> kinds() {
            ArrayList<String> values = new ArrayList<>();
            for (RecognitionEvent event : events) {
                values.add(event.getClass().getSimpleName());
            }
            return values;
        }
    }
}
