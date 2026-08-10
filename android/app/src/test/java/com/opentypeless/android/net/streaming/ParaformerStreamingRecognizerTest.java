package com.opentypeless.android.net.streaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public final class ParaformerStreamingRecognizerTest {
    @Test
    public void websocketSessionStreamsBinaryAndReturnsFinalPunctuatedText() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            CountDownLatch audioReceived = new CountDownLatch(1);
            server.enqueue(new MockResponse().withWebSocketUpgrade(
                    successfulServer(audioReceived)));
            server.start();
            ParaformerStreamingRecognizer recognizer = new ParaformerStreamingRecognizer();
            List<String> revisions = new ArrayList<>();
            ParaformerStreamingRecognizer.Session session = recognizer.new Session(
                    "2bf83b9a-baeb-4fda-8d9a-123456789012",
                    settings(),
                    (stable, unstable) -> revisions.add(stable + unstable));
            try {
                session.connect(webSocketUrl(server), "secret-key");
                session.awaitStarted();
                session.sendAudio(new byte[]{1, 2, 3, 4}, 0, 4);
                assertTrue(audioReceived.await(2, TimeUnit.SECONDS));

                assertEquals("你好。", session.finishAndAwait());
                assertEquals(List.of("你好", "你好。"), revisions);
                RecordedRequest handshake = server.takeRequest(2, TimeUnit.SECONDS);
                assertEquals("Bearer secret-key", handshake.getHeader("Authorization"));
            } finally {
                session.close();
                recognizer.shutdown();
            }
        }
    }

    @Test
    public void handshakeAuthenticationFailureIsActionableAndBounded() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401));
            server.start();
            ParaformerStreamingRecognizer recognizer = new ParaformerStreamingRecognizer();
            ParaformerStreamingRecognizer.Session session = recognizer.new Session(
                    "2bf83b9a-baeb-4fda-8d9a-123456789012",
                    settings(),
                    (stable, unstable) -> {});
            try {
                session.connect(webSocketUrl(server), "wrong-key");
                IllegalStateException error = assertThrows(
                        IllegalStateException.class,
                        session::awaitStarted);
                assertTrue(error.getMessage().contains("authentication failed"));
            } finally {
                session.close();
                recognizer.shutdown();
            }
        }
    }

    @Test
    public void malformedProviderEventFailsClosedWithoutWaitingForTheTerminalTimeout()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                private String taskId;

                @Override
                public void onMessage(WebSocket socket, String text) {
                    JSONObject header = header(text);
                    String action = string(header, "action");
                    taskId = string(header, "task_id");
                    if (action.equals("run-task")) {
                        socket.send(event("task-started", taskId, ""));
                    } else if (action.equals("finish-task")) {
                        socket.send("{not-json");
                    }
                }
            }));
            server.start();
            ParaformerStreamingRecognizer recognizer = new ParaformerStreamingRecognizer();
            ParaformerStreamingRecognizer.Session session = recognizer.new Session(
                    "2bf83b9a-baeb-4fda-8d9a-123456789012",
                    settings(),
                    (stable, unstable) -> {});
            try {
                session.connect(webSocketUrl(server), "secret-key");
                session.awaitStarted();
                IllegalStateException error = assertThrows(
                        IllegalStateException.class,
                        session::finishAndAwait);
                assertTrue(error.getMessage().contains("invalid event"));
            } finally {
                session.close();
                recognizer.shutdown();
            }
        }
    }

    @Test
    public void cancellationUnblocksAHandshakeThatHasNotStartedTheTask() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {}));
            server.start();
            ParaformerStreamingRecognizer recognizer = new ParaformerStreamingRecognizer();
            ParaformerStreamingRecognizer.Session session = recognizer.new Session(
                    "2bf83b9a-baeb-4fda-8d9a-123456789012",
                    settings(),
                    (stable, unstable) -> {});
            try {
                session.connect(webSocketUrl(server), "secret-key");
                session.cancel();
                assertThrows(CancellationException.class, session::awaitStarted);
            } finally {
                session.close();
                recognizer.shutdown();
            }
        }
    }

    @Test
    public void websocketRedirectIsRejectedWithoutForwardingTheBearerCredential() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", server.url("/capture")));
            server.enqueue(new MockResponse().setResponseCode(200));
            ParaformerStreamingRecognizer recognizer = new ParaformerStreamingRecognizer();
            ParaformerStreamingRecognizer.Session session = recognizer.new Session(
                    "2bf83b9a-baeb-4fda-8d9a-123456789012",
                    settings(),
                    (stable, unstable) -> {});
            try {
                session.connect(webSocketUrl(server), "secret-key");
                IllegalStateException error = assertThrows(
                        IllegalStateException.class,
                        session::awaitStarted);
                assertTrue(error.getMessage().contains("handshake failed (302)"));
                RecordedRequest handshake = server.takeRequest(2, TimeUnit.SECONDS);
                assertEquals("Bearer secret-key", handshake.getHeader("Authorization"));
                assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS));
            } finally {
                session.close();
                recognizer.shutdown();
            }
        }
    }

    private static WebSocketListener successfulServer(CountDownLatch audioReceived) {
        return new WebSocketListener() {
            private String taskId;

            @Override
            public void onMessage(WebSocket socket, String text) {
                JSONObject header = header(text);
                String action = string(header, "action");
                taskId = string(header, "task_id");
                if (action.equals("run-task")) {
                    socket.send(event("task-started", taskId, ""));
                } else if (action.equals("finish-task")) {
                    socket.send(result(taskId, "你好", false));
                    socket.send(result(taskId, "你好。", true));
                    socket.send(event("task-finished", taskId, ""));
                }
            }

            @Override
            public void onMessage(WebSocket socket, ByteString bytes) {
                if (bytes.size() > 0) audioReceived.countDown();
            }
        };
    }

    private static String event(String event, String taskId, String extra) {
        return "{\"header\":{\"task_id\":\"" + taskId
                + "\",\"event\":\"" + event + "\"" + extra + "},\"payload\":{}}";
    }

    private static String result(String taskId, String text, boolean sentenceEnd) {
        return "{\"header\":{\"task_id\":\"" + taskId
                + "\",\"event\":\"result-generated\"},\"payload\":{\"output\":{\"sentence\":{"
                + "\"begin_time\":170,\"text\":" + JSONObject.quote(text)
                + ",\"heartbeat\":false,\"sentence_end\":" + sentenceEnd + "}}}}";
    }

    private static String webSocketUrl(MockWebServer server) {
        return server.url("/api-ws/v1/inference").toString().replaceFirst("^http", "ws");
    }

    private static JSONObject header(String text) {
        try {
            return new JSONObject(text).getJSONObject("header");
        } catch (Exception error) {
            throw new AssertionError("Invalid client command", error);
        }
    }

    private static String string(JSONObject object, String key) {
        try {
            return object.getString(key);
        } catch (Exception error) {
            throw new AssertionError("Missing client command field: " + key, error);
        }
    }

    private static AppSettings settings() {
        return new AppSettings(
                RecognitionBackend.DASHSCOPE_STREAMING,
                "https://api.openai.com/v1",
                "",
                "whisper-1",
                "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
                "secret-key",
                "paraformer-realtime-v2",
                "",
                "zh-CN",
                ProcessingMode.VERBATIM,
                false,
                "",
                "",
                "",
                "",
                "",
                false,
                false,
                false,
                60);
    }
}
