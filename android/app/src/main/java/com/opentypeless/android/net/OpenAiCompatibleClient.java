package com.opentypeless.android.net;

import com.opentypeless.android.settings.AppSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class OpenAiCompatibleClient {
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_AUDIO_BYTES = 32 * 1024 * 1024;
    private static final int MAX_PROVIDER_TEXT_CODE_POINTS = 20_000;
    private final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();

    public String transcribe(byte[] wav, AppSettings settings, String prompt) throws Exception {
        if (wav == null || wav.length == 0 || wav.length > MAX_AUDIO_BYTES) {
            throw new IllegalArgumentException("Recorded audio has an invalid size");
        }
        String endpoint = EndpointNormalizer.endpoint(settings.sttBaseUrl(), "audio/transcriptions");
        String boundary = "----OpenTypeless" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = open(endpoint, settings.sttApiKey());
        try {
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setChunkedStreamingMode(8_192);
            try (OutputStream output = connection.getOutputStream()) {
                writeField(output, boundary, "model",
                        headerSafe(settings.sttModel(), 200, "STT model"));
                if (!settings.language().trim().isEmpty()) {
                    writeField(output, boundary, "language",
                            headerSafe(settings.language(), 40, "Language"));
                }
                if (prompt != null && !prompt.trim().isEmpty()) {
                    writeField(output, boundary, "prompt", limitText(prompt.trim(), 2_000));
                }
                output.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"recording.wav\"\r\n"
                        + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                output.write(wav);
                output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            JSONObject body = new JSONObject(readResponse(connection));
            String text = body.optString("text", "").trim();
            if (text.isEmpty()) throw new IOException("STT response did not contain text");
            return boundedProviderText(text, "STT transcript");
        } finally {
            close(connection);
        }
    }

    public String complete(String systemPrompt, String userPrompt, AppSettings settings) throws Exception {
        String safeSystemPrompt = boundedPrompt(systemPrompt, 40_000, "System prompt");
        String safeUserPrompt = boundedPrompt(userPrompt, 40_000, "User prompt");
        JSONObject body = new JSONObject();
        body.put("model", headerSafe(settings.llmModel(), 200, "LLM model"));
        body.put("temperature", 0.1);
        body.put("max_tokens", 4096);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", safeSystemPrompt));
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", safeUserPrompt));
        body.put("messages", messages);

        String endpoint = EndpointNormalizer.endpoint(settings.llmBaseUrl(), "chat/completions");
        HttpURLConnection connection = open(endpoint, settings.llmApiKey());
        try {
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] encodedBody = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(encodedBody.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(encodedBody);
            }
            JSONObject response = new JSONObject(readResponse(connection));
            JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            String text = message.optString("content", "").trim();
            if (text.isEmpty()) text = message.optString("reasoning_content", "").trim();
            if (text.isEmpty()) throw new IOException("LLM response did not contain text");
            return boundedProviderText(text, "LLM output");
        } finally {
            close(connection);
        }
    }

    private HttpURLConnection open(String endpoint, String apiKey) throws IOException {
        EndpointNormalizer.requireCredentialSafeTransport(endpoint, apiKey);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            connection.setRequestProperty(
                    "Authorization", "Bearer " + headerSafe(apiKey, 4_096, "API key"));
        }
        activeConnection.set(connection);
        return connection;
    }

    public void cancelActiveRequest() {
        HttpURLConnection connection = activeConnection.getAndSet(null);
        if (connection != null) connection.disconnect();
    }

    private void close(HttpURLConnection connection) {
        activeConnection.compareAndSet(connection, null);
        connection.disconnect();
    }

    private static void writeField(OutputStream output, String boundary, String name, String value)
            throws IOException {
        String field = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        output.write(field.getBytes(StandardCharsets.UTF_8));
    }

    private static String readResponse(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String body = stream == null ? "" : readLimited(stream);
        if (status < 200 || status >= 300) {
            String requestId = connection.getHeaderField("x-request-id");
            String suffix = requestId == null || requestId.isBlank()
                    ? ""
                    : " (request " + limitText(requestId.replaceAll("[^A-Za-z0-9._:-]", ""), 80) + ")";
            if (status >= 300 && status < 400) {
                throw new IOException("Provider redirect was rejected" + suffix);
            }
            throw new IOException("Provider returned HTTP " + status + suffix);
        }
        return body;
    }

    private static String readLimited(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("Provider response is too large");
                bytes.write(buffer, 0, read);
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String limitText(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String headerSafe(String value, int maximumCodePoints, String label) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException(label + " is required");
        if (clean.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " contains unsupported control characters");
        }
        if (clean.codePointCount(0, clean.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(label + " is too long");
        }
        return clean;
    }

    private static String boundedPrompt(String value, int maximumCodePoints, String label) {
        String clean = value == null ? "" : value;
        if (clean.codePointCount(0, clean.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(label + " is too long");
        }
        return clean;
    }

    private static String boundedProviderText(String value, String label) throws IOException {
        if (value.codePointCount(0, value.length()) > MAX_PROVIDER_TEXT_CODE_POINTS) {
            throw new IOException(label + " is too long");
        }
        return value;
    }
}
