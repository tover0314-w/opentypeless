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
    private final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();

    public String transcribe(byte[] wav, AppSettings settings) throws Exception {
        String endpoint = EndpointNormalizer.endpoint(settings.sttBaseUrl(), "audio/transcriptions");
        String boundary = "----OpenTypeless" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = open(endpoint, settings.sttApiKey());
        try {
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream output = connection.getOutputStream()) {
                writeField(output, boundary, "model", settings.sttModel());
                if (!settings.language().trim().isEmpty()) {
                    writeField(output, boundary, "language", settings.language().trim());
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
            return text;
        } finally {
            close(connection);
        }
    }

    public String polish(String transcript, AppSettings settings) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", settings.llmModel());
        body.put("temperature", 0.1);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", PolishPrompt.systemPrompt()));
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", "<transcription>" + transcript + "</transcription>"));
        body.put("messages", messages);

        String endpoint = EndpointNormalizer.endpoint(settings.llmBaseUrl(), "chat/completions");
        HttpURLConnection connection = open(endpoint, settings.llmApiKey());
        try {
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            JSONObject response = new JSONObject(readResponse(connection));
            JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            String text = message.optString("content", "").trim();
            if (text.isEmpty()) text = message.optString("reasoning_content", "").trim();
            if (text.isEmpty()) throw new IOException("LLM response did not contain text");
            return text;
        } finally {
            close(connection);
        }
    }

    private HttpURLConnection open(String endpoint, String apiKey) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
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
            String compact = body.replaceAll("\\s+", " ").trim();
            if (compact.length() > 300) compact = compact.substring(0, 300) + "…";
            throw new IOException("Provider returned HTTP " + status
                    + (compact.isEmpty() ? "" : ": " + compact));
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
}
