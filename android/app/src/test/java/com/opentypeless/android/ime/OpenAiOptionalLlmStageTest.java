package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.context.FieldKind;
import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.data.PersonalizationSnapshot;
import com.opentypeless.android.net.OpenAiCompatibleClient;
import com.opentypeless.android.personalization.PromptComposer;
import com.opentypeless.android.settings.AppSettings;
import com.opentypeless.android.settings.ProcessingMode;
import com.opentypeless.android.settings.RecognitionBackend;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.concurrent.CancellationException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

public final class OpenAiOptionalLlmStageTest {
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
    public void composesExistingPromptsAndCallsTheSharedClientExactlyOnce() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"choices\":[{\"message\":{\"content\":\"candidate\"}}]}"));
        AppSettings settings = settings("secret-token");
        InputContext context = new InputContext(
                "example.package",
                FieldKind.GENERAL,
                "selected text",
                "preceding context",
                true);
        TextProcessingPipeline.LlmRequest request = new TextProcessingPipeline.LlmRequest(
                ProcessingMode.SMART,
                context,
                PersonalizationSnapshot.empty(),
                settings,
                "deterministic transcript");

        String result = new OpenAiOptionalLlmStage(new OpenAiCompatibleClient())
                .apply(request, () -> false);

        assertEquals("candidate", result);
        assertEquals(1, server.getRequestCount());
        RecordedRequest recorded = server.takeRequest();
        assertEquals("/v1/chat/completions", recorded.getPath());
        assertEquals("Bearer secret-token", recorded.getHeader("Authorization"));
        JSONArray messages = new JSONObject(recorded.getBody().readUtf8())
                .getJSONArray("messages");
        assertEquals(
                PromptComposer.systemPrompt(
                        request.mode(),
                        request.inputContext(),
                        request.personalization(),
                        settings.targetLanguage(),
                        settings.customInstructions()),
                messages.getJSONObject(0).getString("content"));
        assertEquals(
                PromptComposer.userPrompt(
                        request.deterministicText(), context, settings.sendContext()),
                messages.getJSONObject(1).getString("content"));
    }

    @Test
    public void preservesCancellationAndProviderFailureForThePipelineToClassify() {
        OpenAiOptionalLlmStage stage = new OpenAiOptionalLlmStage(
                new OpenAiCompatibleClient());
        TextProcessingPipeline.LlmRequest request = request(settings("secret-token"));

        assertThrows(CancellationException.class, () -> stage.apply(request, () -> true));
        assertEquals(0, server.getRequestCount());

        server.enqueue(new MockResponse()
                .setResponseCode(503)
                .setBody("private transcript and secret-token"));
        Exception error = assertThrows(Exception.class, () -> stage.apply(request, () -> false));
        assertTrue(error.getMessage().contains("HTTP 503"));
        assertFalse(error.getMessage().contains("private transcript"));
        assertFalse(error.getMessage().contains("secret-token"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void stageIsPackageConfinedFinalAndRejectsNullCapabilities() {
        assertTrue(Modifier.isFinal(OpenAiOptionalLlmStage.class.getModifiers()));
        assertFalse(Modifier.isPublic(OpenAiOptionalLlmStage.class.getModifiers()));
        assertEquals(1, OpenAiOptionalLlmStage.class.getDeclaredFields().length);
        assertEquals(OpenAiCompatibleClient.class,
                OpenAiOptionalLlmStage.class.getDeclaredFields()[0].getType());

        assertThrows(NullPointerException.class, () -> new OpenAiOptionalLlmStage(null));
        OpenAiOptionalLlmStage stage = new OpenAiOptionalLlmStage(
                new OpenAiCompatibleClient());
        assertThrows(NullPointerException.class, () -> stage.apply(null, () -> false));
        assertThrows(NullPointerException.class, () -> stage.apply(request(settings("")), null));
    }

    private TextProcessingPipeline.LlmRequest request(AppSettings settings) {
        return new TextProcessingPipeline.LlmRequest(
                ProcessingMode.SMART,
                new InputContext("pkg", FieldKind.GENERAL, "", "", true),
                PersonalizationSnapshot.empty(),
                settings,
                "transcript");
    }

    private AppSettings settings(String key) {
        return new AppSettings(
                RecognitionBackend.LOCAL_OFFLINE,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "zh",
                ProcessingMode.SMART,
                true,
                server.url("/v1").toString(),
                key,
                "llm-test",
                "English",
                "keep the tone",
                true,
                true,
                true,
                60);
    }
}
