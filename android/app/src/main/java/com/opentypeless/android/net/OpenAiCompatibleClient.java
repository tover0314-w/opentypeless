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
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class OpenAiCompatibleClient {
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    public static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_AUDIO_BYTES = 32 * 1024 * 1024;
    private static final int MAX_PROVIDER_TEXT_CODE_POINTS = 20_000;
    private final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();
    private final Object requestLock = new Object();

    public String transcribe(byte[] wav, AppSettings settings, String prompt) throws Exception {
        return transcribe(wav, settings, prompt, () -> false);
    }

    public String transcribe(
            byte[] wav,
            AppSettings settings,
            String prompt,
            BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(settings, "settings");
        char[] credential = settings.sttApiKey() == null
                ? new char[0]
                : settings.sttApiKey().toCharArray();
        try {
            return transcribe(
                    wav,
                    settings.sttBaseUrl(),
                    credential,
                    settings.sttModel(),
                    settings.language(),
                    prompt,
                    cancelled);
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    /**
     * Narrow upload seam used by the reviewed recognition adapter.
     *
     * <p>The credential is borrowed only for this synchronous call and is never retained. The
     * caller remains responsible for clearing its array after this method returns.
     */
    public String transcribe(
            byte[] wav,
            String baseUrl,
            char[] apiKey,
            String model,
            String language,
            String prompt,
            BooleanSupplier cancelled) throws Exception {
        if (wav == null || wav.length == 0 || wav.length > MAX_AUDIO_BYTES) {
            throw new IllegalArgumentException("Recorded audio has an invalid size");
        }
        char[] borrowedCredential = Objects.requireNonNull(apiKey, "apiKey");
        String credential = headerSafe(
                new String(borrowedCredential),
                4_096,
                "API key",
                true);
        String safeModel = headerSafe(model, 200, "STT model", false);
        String safeLanguage = optionalHeaderSafe(language, 40, "Language");
        String safePrompt = prompt == null ? "" : prompt.trim();
        // Complete every caller-controlled bound check before open(): invalid work must have
        // observable request count zero, not merely fail while streaming the multipart body.
        if (!safePrompt.isEmpty()) {
            safePrompt = boundedPrompt(safePrompt, 2_000, "STT prompt");
        }
        String endpoint = EndpointNormalizer.endpoint(baseUrl, "audio/transcriptions");
        String boundary = "----OpenTypeless" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = open(endpoint, credential, cancelled);
        try {
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setChunkedStreamingMode(8_192);
            try (OutputStream output = connection.getOutputStream()) {
                writeField(output, boundary, "model", safeModel);
                if (!safeLanguage.isEmpty()) {
                    writeField(output, boundary, "language",
                            safeLanguage);
                }
                if (!safePrompt.isEmpty()) {
                    writeField(output, boundary, "prompt", safePrompt);
                }
                output.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"recording.wav\"\r\n"
                        + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                writeAudio(output, wav, cancelled);
                output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            String response = readResponse(connection, cancelled);
            String text;
            try {
                text = new JSONObject(response).optString("text", "").trim();
            } catch (RuntimeException ignored) {
                throw requestFailure(
                        RequestFailure.PROTOCOL_ERROR,
                        "STT response was invalid");
            }
            if (text.isEmpty()) {
                throw requestFailure(
                        RequestFailure.NO_RESULT,
                        "STT response did not contain text");
            }
            return boundedProviderText(text, "STT transcript");
        } finally {
            close(connection);
        }
    }

    public String complete(String systemPrompt, String userPrompt, AppSettings settings) throws Exception {
        return complete(systemPrompt, userPrompt, settings, () -> false);
    }

    public String complete(
            String systemPrompt,
            String userPrompt,
            AppSettings settings,
            BooleanSupplier cancelled) throws Exception {
        String safeSystemPrompt = boundedPrompt(systemPrompt, 40_000, "System prompt");
        String safeUserPrompt = boundedPrompt(userPrompt, 40_000, "User prompt");
        JSONObject body = new JSONObject();
        body.put("model", headerSafe(settings.llmModel(), 200, "LLM model", false));
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
        HttpURLConnection connection = open(endpoint, settings.llmApiKey(), cancelled);
        try {
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] encodedBody = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(encodedBody.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(encodedBody);
            }
            String responseBody = readResponse(connection, cancelled);
            String text;
            try {
                JSONObject response = new JSONObject(responseBody);
                JSONObject message = response.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message");
                text = message.optString("content", "").trim();
                if (text.isEmpty()) text = message.optString("reasoning_content", "").trim();
            } catch (RuntimeException ignored) {
                throw requestFailure(
                        RequestFailure.PROTOCOL_ERROR,
                        "LLM response was invalid");
            }
            if (text.isEmpty()) {
                throw requestFailure(
                        RequestFailure.NO_RESULT,
                        "LLM response did not contain text");
            }
            return boundedProviderText(text, "LLM output");
        } finally {
            close(connection);
        }
    }

    private HttpURLConnection open(
            String endpoint,
            String apiKey,
            BooleanSupplier cancelled) throws IOException {
        synchronized (requestLock) {
            throwIfCancelled(cancelled);
            EndpointNormalizer.requireCredentialSafeTransport(endpoint, apiKey);
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                connection.setRequestProperty(
                        "Authorization", "Bearer " + headerSafe(apiKey, 4_096, "API key", false));
            }
            throwIfCancelled(cancelled);
            activeConnection.set(connection);
            return connection;
        }
    }

    public void cancelActiveRequest() {
        synchronized (requestLock) {
            HttpURLConnection connection = activeConnection.getAndSet(null);
            if (connection != null) connection.disconnect();
        }
    }

    private void close(HttpURLConnection connection) {
        synchronized (requestLock) {
            activeConnection.compareAndSet(connection, null);
            connection.disconnect();
        }
    }

    private static void throwIfCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted()
                || (cancelled != null && cancelled.getAsBoolean())) {
            throw new CancellationException("Request cancelled");
        }
    }

    private static void writeField(OutputStream output, String boundary, String name, String value)
            throws IOException {
        String field = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        output.write(field.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAudio(
            OutputStream output,
            byte[] wav,
            BooleanSupplier cancelled) throws IOException {
        for (int offset = 0; offset < wav.length; offset += 8_192) {
            throwIfCancelled(cancelled);
            int count = Math.min(8_192, wav.length - offset);
            output.write(wav, offset, count);
        }
        throwIfCancelled(cancelled);
    }

    private static String readResponse(
            HttpURLConnection connection,
            BooleanSupplier cancelled) throws IOException {
        throwIfCancelled(cancelled);
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String body = stream == null ? "" : readLimited(stream, cancelled);
        if (status < 200 || status >= 300) {
            String requestId = connection.getHeaderField("x-request-id");
            String suffix = requestId == null || requestId.isBlank()
                    ? ""
                    : " (request " + limitText(requestId.replaceAll("[^A-Za-z0-9._:-]", ""), 80) + ")";
            if (status >= 300 && status < 400) {
                throw requestFailure(
                        RequestFailure.REDIRECT_REJECTED,
                        "Provider redirect was rejected" + suffix);
            }
            throw requestFailure(
                    failureForStatus(status),
                    "Provider returned HTTP " + status + suffix);
        }
        return body;
    }

    private static String readLimited(
            InputStream input,
            BooleanSupplier cancelled) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                throwIfCancelled(cancelled);
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw requestFailure(
                            RequestFailure.RESPONSE_TOO_LARGE,
                            "Provider response is too large");
                }
                bytes.write(buffer, 0, read);
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String limitText(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String optionalHeaderSafe(
            String value,
            int maximumCodePoints,
            String label) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return "";
        return headerSafe(clean, maximumCodePoints, label, false);
    }

    private static String headerSafe(
            String value,
            int maximumCodePoints,
            String label,
            boolean emptyAllowed) {
        String clean = value == null ? "" : value.trim();
        if (!emptyAllowed && clean.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        requireWellFormedUtf16(clean, label);
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
        requireWellFormedUtf16(clean, label);
        if (clean.codePointCount(0, clean.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(label + " is too long");
        }
        return clean;
    }

    private static String boundedProviderText(String value, String label) throws IOException {
        requireWellFormedUtf16(value, label);
        if (value.codePointCount(0, value.length()) > MAX_PROVIDER_TEXT_CODE_POINTS) {
            throw requestFailure(RequestFailure.PROTOCOL_ERROR, label + " is too long");
        }
        return value;
    }

    private static void requireWellFormedUtf16(String value, String label) {
        for (int index = 0; index < value.length(); ) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(label + " must be well-formed UTF-16");
                }
                index += 2;
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException(label + " must be well-formed UTF-16");
            } else {
                index++;
            }
        }
    }

    private static RequestFailure failureForStatus(int status) {
        if (status == 401 || status == 403) return RequestFailure.AUTHENTICATION;
        if (status == 402) return RequestFailure.QUOTA_EXCEEDED;
        if (status == 408 || status == 504) return RequestFailure.NETWORK_TIMEOUT;
        if (status == 413) return RequestFailure.REQUEST_TOO_LARGE;
        if (status == 429) return RequestFailure.RATE_LIMITED;
        if (status >= 500) return RequestFailure.SERVER_ERROR;
        return RequestFailure.PROTOCOL_ERROR;
    }

    private static RequestException requestFailure(RequestFailure failure, String message) {
        return new RequestException(failure, message);
    }

    public enum RequestFailure {
        AUTHENTICATION,
        QUOTA_EXCEEDED,
        RATE_LIMITED,
        NETWORK_TIMEOUT,
        REQUEST_TOO_LARGE,
        SERVER_ERROR,
        REDIRECT_REJECTED,
        RESPONSE_TOO_LARGE,
        PROTOCOL_ERROR,
        NO_RESULT
    }

    /** Stable content-free failure for callers that must not parse provider messages or bodies. */
    public static final class RequestException extends IOException {
        private final RequestFailure failure;

        private RequestException(RequestFailure failure, String message) {
            super(message);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        public RequestFailure failure() {
            return failure;
        }

        @Override
        public String toString() {
            return "OpenAiCompatibleRequestException{failure=" + failure + "}";
        }
    }
}
