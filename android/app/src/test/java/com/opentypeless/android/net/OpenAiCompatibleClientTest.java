package com.opentypeless.android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;

import java.io.IOException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class OpenAiCompatibleClientTest {
    private MockWebServer server;

    @Before
    public void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    public void sendsAudioPromptAndCredentialsToExplicitEndpoint() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"OpenTypeless works\"}"));
        AppSettings settings = settings(baseUrl(), "secret-token");

        String text = new OpenAiCompatibleClient().transcribe(
                new byte[]{1, 2, 3, 4}, settings, "Expected term: OpenTypeless");

        assertEquals("OpenTypeless works", text);
        RecordedRequest request = server.takeRequest();
        assertEquals("/v1/audio/transcriptions", request.getPath());
        assertEquals("Bearer secret-token", request.getHeader("Authorization"));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("name=\"model\""));
        assertTrue(body.contains("whisper-test"));
        assertTrue(body.contains("Expected term: OpenTypeless"));
        assertTrue(body.contains("filename=\"recording.wav\""));
    }

    @Test
    public void rejectsRedirectWithoutFollowingOrLeakingAuthorization() {
        server.enqueue(new MockResponse()
                .setResponseCode(307)
                .setHeader("Location", server.url("/stolen")));

        Exception error = assertThrows(Exception.class, () -> new OpenAiCompatibleClient()
                .transcribe(new byte[]{1, 2}, settings(baseUrl(), "top-secret"), ""));

        assertTrue(error.getMessage().contains("redirect was rejected"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void providerErrorNeverEchoesSensitiveResponseBody() {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("x-request-id", "req-safe_123")
                .setBody("transcript=private words and key=secret"));

        Exception error = assertThrows(Exception.class, () -> new OpenAiCompatibleClient()
                .transcribe(new byte[]{1, 2}, settings(baseUrl(), "token"), ""));

        assertTrue(error.getMessage().contains("HTTP 400"));
        assertTrue(error.getMessage().contains("req-safe_123"));
        assertFalse(error.getMessage().contains("private words"));
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test
    public void rejectsControlCharactersBeforeOpeningARequest() {
        AppSettings unsafe = settings(baseUrl(), "token\r\nX-Leak: yes");
        Exception error = assertThrows(Exception.class, () -> new OpenAiCompatibleClient()
                .transcribe(new byte[]{1, 2}, unsafe, ""));
        assertTrue(error.getMessage().contains("control characters"));
        assertEquals(0, server.getRequestCount());

        AppSettings legacyControl = settings(baseUrl(), "token\u0007value");
        assertThrows(Exception.class, () -> new OpenAiCompatibleClient()
                .transcribe(new byte[]{1, 2}, legacyControl, ""));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void completesWithOutputOnlyAndBoundedGeneration() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"choices\":[{\"message\":{\"content\":\"Final text\"}}]}"));

        String result = new OpenAiCompatibleClient().complete(
                "Safety system", "<transcription>raw</transcription>", settings(baseUrl(), ""));

        assertEquals("Final text", result);
        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("\"max_tokens\":4096"));
        assertTrue(body.contains("Safety system"));
        assertTrue(body.contains("&lt;") || body.contains("<transcription>"));
    }

    @Test
    public void rejectsOversizedTranscriptInsideOtherwiseValidJson() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"text\":\"" + "a".repeat(20_001) + "\"}"));

        Exception error = assertThrows(Exception.class, () -> new OpenAiCompatibleClient()
                .transcribe(new byte[]{1, 2}, settings(baseUrl(), "token"), ""));

        assertTrue(error.getMessage().contains("too long"));
    }

    @Test
    public void rejectsOversizedLlmTextInsideOtherwiseValidJson() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"choices\":[{\"message\":{\"content\":\""
                        + "a".repeat(20_001)
                        + "\"}}]}"));

        Exception error = assertThrows(Exception.class, () -> new OpenAiCompatibleClient()
                .complete("system", "user", settings(baseUrl(), "token")));

        assertTrue(error.getMessage().contains("too long"));
    }

    private String baseUrl() {
        return server.url("/v1").toString();
    }

    private static AppSettings settings(String baseUrl, String key) {
        return new AppSettings(
                RecognitionBackend.OPENAI_COMPATIBLE,
                baseUrl,
                key,
                "whisper-test",
                "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
                "streaming-key",
                "paraformer-realtime-v2",
                "",
                "zh-CN",
                ProcessingMode.AUTO,
                true,
                baseUrl,
                key,
                "llm-test",
                "English",
                "",
                true,
                false,
                false,
                60);
    }
}
