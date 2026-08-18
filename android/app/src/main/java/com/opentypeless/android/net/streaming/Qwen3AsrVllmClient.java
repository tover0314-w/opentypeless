package com.opentypeless.android.net.streaming;

import com.opentypeless.android.config.ProviderConfig;
import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.recognition.RecognitionEvent;
import com.opentypeless.android.recognition.RecognitionMetadata;
import com.opentypeless.android.speech.core.SessionId;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.BufferedSource;
import okio.ByteString;

/** Bounded vLLM Realtime transport used only by the reviewed Qwen3-ASR adapter. */
public final class Qwen3AsrVllmClient implements AutoCloseable {
    public static final int MAX_PCM_FRAME_BYTES = 64 * 1_024;
    public static final long MAX_OUTGOING_QUEUE_BYTES = 256L * 1_024L;
    public static final int MAX_JSON_UTF16_UNITS = 524_288;
    public static final int MAX_PROBE_BYTES = 256 * 1_024;
    public static final int MAX_MODELS = 128;

    private static final int MAX_CREDENTIAL_CODE_POINTS = 4_096;
    private static final int MAX_JSON_DEPTH = 16;
    private static final Set<String> SESSION_CREATED_KEYS = Set.of("type", "id", "created");
    private static final Set<String> DELTA_KEYS = Set.of("type", "delta");
    private static final Set<String> DONE_KEYS = Set.of("type", "text", "usage");
    private static final Set<String> ERROR_KEYS = Set.of("type", "error", "code");

    private final OkHttpClient realtimeClient;
    private final OkHttpClient probeClient;

    public Qwen3AsrVllmClient() {
        this(
                new OkHttpClient.Builder()
                        .connectTimeout(10L, TimeUnit.SECONDS)
                        .callTimeout(0L, TimeUnit.MILLISECONDS)
                        .readTimeout(0L, TimeUnit.MILLISECONDS)
                        .pingInterval(15L, TimeUnit.SECONDS)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .retryOnConnectionFailure(false)
                        .build(),
                new OkHttpClient.Builder()
                        .connectTimeout(5L, TimeUnit.SECONDS)
                        .callTimeout(15L, TimeUnit.SECONDS)
                        .readTimeout(10L, TimeUnit.SECONDS)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .retryOnConnectionFailure(false)
                        .build());
    }

    Qwen3AsrVllmClient(OkHttpClient realtimeClient, OkHttpClient probeClient) {
        this.realtimeClient = Objects.requireNonNull(realtimeClient, "realtimeClient");
        this.probeClient = Objects.requireNonNull(probeClient, "probeClient");
    }

    public ProbeResult probe(Config config, char[] credential) {
        Config safeConfig = Objects.requireNonNull(config, "config");
        String token;
        try {
            token = credential(credential);
        } catch (IllegalArgumentException error) {
            return ProbeResult.AUTHENTICATION;
        }
        Request.Builder request = new Request.Builder()
                .url(serviceEndpoint(safeConfig.endpoint(), ServicePath.MODELS))
                .header("Accept", "application/json")
                .header("User-Agent", "OpenTypeless-Android/0.3");
        if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
        try (Response response = probeClient.newCall(request.build()).execute()) {
            ProbeResult statusFailure = probeFailureForStatus(response.code());
            if (statusFailure != null) return statusFailure;
            String body = boundedBody(response.body());
            return containsModel(body, safeConfig.model())
                    ? ProbeResult.AVAILABLE
                    : ProbeResult.MODEL_MISSING;
        } catch (SocketTimeoutException error) {
            return ProbeResult.NETWORK_TIMEOUT;
        } catch (IOException error) {
            return ProbeResult.NETWORK_UNAVAILABLE;
        } catch (IllegalArgumentException | JSONException error) {
            return ProbeResult.PROTOCOL_ERROR;
        } catch (RuntimeException error) {
            return ProbeResult.INTERNAL_ERROR;
        }
    }

    public Session open(Config config, char[] credential, Listener listener) {
        Config safeConfig = Objects.requireNonNull(config, "config");
        Listener safeListener = Objects.requireNonNull(listener, "listener");
        String token = credential(credential);
        Request.Builder request = new Request.Builder()
                .url(serviceEndpoint(safeConfig.endpoint(), ServicePath.REALTIME))
                .header("User-Agent", "OpenTypeless-Android/0.3");
        if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
        SessionImpl session = new SessionImpl(safeConfig, safeListener);
        session.start(request.build());
        return session;
    }

    @Override
    public void close() {
        realtimeClient.dispatcher().executorService().shutdownNow();
        realtimeClient.connectionPool().evictAll();
        probeClient.dispatcher().executorService().shutdownNow();
        probeClient.connectionPool().evictAll();
    }

    public record Config(
            ProviderConfig.Endpoint endpoint,
            SessionId sessionId,
            String model) {
        public Config {
            endpoint = Objects.requireNonNull(endpoint, "endpoint");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            model = boundedText(
                    model,
                    "model",
                    ProviderConfig.MAX_MODEL_ID_CODE_POINTS,
                    false);
            serviceEndpoint(endpoint, ServicePath.MODELS);
            serviceEndpoint(endpoint, ServicePath.REALTIME);
        }

        @Override
        public String toString() {
            return "Qwen3AsrVllmConfig{endpoint=<redacted>, session=<redacted>, "
                    + "model=<redacted>}";
        }
    }

    public interface Listener {
        void onOpen();

        void onEvent(RecognitionEvent event);

        void onFailure(Failure failure);
    }

    public interface Session extends AutoCloseable {
        boolean sendPcm(byte[] pcm, int offset, int length);

        boolean finish();

        long queuedBytes();

        void cancel();

        @Override
        void close();
    }

    public enum ProbeResult {
        AVAILABLE,
        MODEL_MISSING,
        AUTHENTICATION,
        RATE_LIMITED,
        SERVER_ERROR,
        NETWORK_TIMEOUT,
        NETWORK_UNAVAILABLE,
        PROTOCOL_ERROR,
        INTERNAL_ERROR
    }

    public enum Failure {
        MODEL_MISSING,
        AUTHENTICATION,
        RATE_LIMITED,
        SERVER_ERROR,
        NETWORK_TIMEOUT,
        NETWORK_UNAVAILABLE,
        PROTOCOL_ERROR,
        INTERNAL_ERROR
    }

    private final class SessionImpl extends WebSocketListener implements Session {
        private final Config config;
        private Listener listener;
        private WebSocket webSocket;
        private StringBuilder transcript = new StringBuilder();
        private long sequence;
        private boolean opened;
        private boolean ready;
        private boolean finishing;
        private boolean cancelled;
        private boolean terminal;

        private SessionImpl(Config config, Listener listener) {
            this.config = config;
            this.listener = listener;
        }

        private void start(Request request) {
            synchronized (this) {
                webSocket = realtimeClient.newWebSocket(request, this);
            }
        }

        @Override
        public void onOpen(WebSocket socket, Response response) {
            Listener target;
            synchronized (this) {
                if (cancelled || terminal || socket != webSocket) {
                    socket.cancel();
                    return;
                }
                opened = true;
                target = listener;
            }
            try {
                if (target != null) target.onOpen();
            } catch (RuntimeException error) {
                fail(Failure.INTERNAL_ERROR);
            }
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            ServerEvent event;
            synchronized (this) {
                if (cancelled || terminal || socket != webSocket) return;
            }
            try {
                event = decodeServerEvent(text);
            } catch (RuntimeException error) {
                fail(Failure.PROTOCOL_ERROR);
                return;
            }
            if (event instanceof SessionCreated) {
                handleSessionCreated(socket);
            } else if (event instanceof TranscriptionDelta delta) {
                handleDelta(delta.text());
            } else if (event instanceof TranscriptionDone done) {
                handleDone(done.text());
            } else {
                fail(((ServerFailure) event).failure());
            }
        }

        @Override
        public void onMessage(WebSocket socket, ByteString bytes) {
            fail(Failure.PROTOCOL_ERROR);
        }

        @Override
        public void onFailure(WebSocket socket, Throwable error, Response response) {
            int status = response == null ? 0 : response.code();
            if (response != null) response.close();
            fail(failureForStatus(status, error));
        }

        @Override
        public void onClosed(WebSocket socket, int code, String reason) {
            synchronized (this) {
                if (cancelled || terminal || socket != webSocket) return;
            }
            fail(Failure.NETWORK_UNAVAILABLE);
        }

        private void handleSessionCreated(WebSocket socket) {
            Listener target;
            RecognitionEvent.Preparing preparing;
            RecognitionEvent.Ready readyEvent;
            String update;
            synchronized (this) {
                if (!opened || ready || finishing || cancelled || terminal || socket != webSocket) {
                    fail(Failure.PROTOCOL_ERROR);
                    return;
                }
                update = sessionUpdate(config.model());
                if (!socket.send(update)) {
                    fail(Failure.NETWORK_UNAVAILABLE);
                    return;
                }
                ready = true;
                target = listener;
                preparing = new RecognitionEvent.Preparing(config.sessionId(), nextSequence());
                readyEvent = new RecognitionEvent.Ready(config.sessionId(), nextSequence());
            }
            try {
                if (target != null) {
                    target.onEvent(preparing);
                    target.onEvent(readyEvent);
                }
            } catch (RuntimeException error) {
                fail(Failure.INTERNAL_ERROR);
            }
        }

        private void handleDelta(String delta) {
            Listener target;
            RecognitionEvent.Partial partial;
            synchronized (this) {
                if (!ready || cancelled || terminal || transcript == null) {
                    fail(Failure.PROTOCOL_ERROR);
                    return;
                }
                if (delta.isEmpty()) return;
                int stablePrefix = transcript.length();
                transcript.append(delta);
                String full = transcript.toString();
                if (full.codePointCount(0, full.length()) > RecognitionEvent.MAX_TEXT_CODE_POINTS) {
                    fail(Failure.PROTOCOL_ERROR);
                    return;
                }
                partial = new RecognitionEvent.Partial(
                        config.sessionId(), nextSequence(), full, stablePrefix, null);
                target = listener;
            }
            try {
                if (target != null) target.onEvent(partial);
            } catch (RuntimeException error) {
                fail(Failure.INTERNAL_ERROR);
            }
        }

        private void handleDone(String text) {
            Listener target;
            RecognitionEvent.Endpoint endpoint;
            RecognitionEvent.Final terminalEvent;
            synchronized (this) {
                if (!ready || !finishing || cancelled || terminal) {
                    fail(Failure.PROTOCOL_ERROR);
                    return;
                }
                endpoint = new RecognitionEvent.Endpoint(config.sessionId(), nextSequence());
                terminalEvent = new RecognitionEvent.Final(
                        config.sessionId(),
                        nextSequence(),
                        text,
                        new RecognitionMetadata(null, null, null));
                terminal = true;
                target = listener;
                releaseContentLocked();
            }
            try {
                if (target != null) {
                    target.onEvent(endpoint);
                    target.onEvent(terminalEvent);
                }
            } catch (RuntimeException ignored) {
                // Terminal is committed and callback details stay private.
            }
            webSocket.close(1000, "complete");
        }

        @Override
        public boolean sendPcm(byte[] pcm, int offset, int length) {
            Objects.requireNonNull(pcm, "pcm");
            if (offset < 0
                    || length <= 0
                    || length > MAX_PCM_FRAME_BYTES
                    || (length & 1) != 0
                    || offset > pcm.length - length) {
                throw new IllegalArgumentException("PCM frame is outside its bound");
            }
            String frame = audioAppend(pcm, offset, length);
            boolean sent;
            synchronized (this) {
                if (!ready || finishing || cancelled || terminal || webSocket == null) {
                    return false;
                }
                if (webSocket.queueSize() > MAX_OUTGOING_QUEUE_BYTES - frame.length()) {
                    return false;
                }
                sent = webSocket.send(frame);
            }
            if (!sent) fail(Failure.NETWORK_UNAVAILABLE);
            return sent;
        }

        @Override
        public boolean finish() {
            WebSocket socket;
            synchronized (this) {
                if (!ready || finishing || cancelled || terminal || webSocket == null) {
                    return false;
                }
                finishing = true;
                socket = webSocket;
            }
            boolean sent = socket.send(audioCommit());
            if (!sent) fail(Failure.NETWORK_UNAVAILABLE);
            return sent;
        }

        @Override
        public synchronized long queuedBytes() {
            return webSocket == null ? 0L : webSocket.queueSize();
        }

        @Override
        public void cancel() {
            WebSocket socket;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                terminal = true;
                socket = webSocket;
                releaseContentLocked();
            }
            if (socket != null) socket.cancel();
        }

        @Override
        public void close() {
            WebSocket socket;
            boolean complete;
            synchronized (this) {
                socket = webSocket;
                complete = terminal && !cancelled;
            }
            if (socket == null) return;
            if (complete) socket.close(1000, "complete");
            else cancel();
        }

        private synchronized long nextSequence() {
            if (sequence == Long.MAX_VALUE) {
                throw new IllegalStateException("recognition sequence exhausted");
            }
            return ++sequence;
        }

        private void fail(Failure failure) {
            Listener target;
            WebSocket socket;
            synchronized (this) {
                if (cancelled || terminal) return;
                terminal = true;
                target = listener;
                socket = webSocket;
                releaseContentLocked();
            }
            if (socket != null) socket.cancel();
            try {
                if (target != null) target.onFailure(failure);
            } catch (RuntimeException ignored) {
                // Transport authority is already revoked.
            }
        }

        private void releaseContentLocked() {
            listener = null;
            if (transcript != null) transcript.setLength(0);
            transcript = null;
        }

        @Override
        public synchronized String toString() {
            return "Qwen3AsrVllmSession{ready=" + ready
                    + ", finishing=" + finishing
                    + ", terminal=" + terminal
                    + ", content=<redacted>}";
        }
    }

    private sealed interface ServerEvent
            permits SessionCreated, TranscriptionDelta, TranscriptionDone, ServerFailure {}

    private record SessionCreated() implements ServerEvent {}

    private record TranscriptionDelta(String text) implements ServerEvent {}

    private record TranscriptionDone(String text) implements ServerEvent {}

    private record ServerFailure(Failure failure) implements ServerEvent {}

    private enum ServicePath {
        MODELS,
        REALTIME
    }

    private static ServerEvent decodeServerEvent(String json) {
        requireJsonBound(json);
        requireJsonDepth(json);
        try {
            JSONTokener tokener = new JSONTokener(json);
            Object decoded = tokener.nextValue();
            if (!(decoded instanceof JSONObject root) || tokener.nextClean() != 0) {
                throw invalidProtocol();
            }
            String type = requiredString(root, "type", 128, false);
            return switch (type) {
                case "session.created" -> {
                    requireExactKeys(root, SESSION_CREATED_KEYS);
                    requiredString(root, "id", 256, false);
                    requiredNonNegativeLong(root, "created");
                    yield new SessionCreated();
                }
                case "transcription.delta" -> {
                    requireExactKeys(root, DELTA_KEYS);
                    yield new TranscriptionDelta(requiredRecognitionText(root, "delta", true));
                }
                case "transcription.done" -> {
                    requireAllowedAndRequiredKeys(
                            root, DONE_KEYS, Set.of("type", "text"));
                    if (root.has("usage")
                            && !root.isNull("usage")
                            && !(root.get("usage") instanceof JSONObject)) {
                        throw invalidProtocol();
                    }
                    yield new TranscriptionDone(requiredRecognitionText(root, "text", false));
                }
                case "error" -> {
                    requireAllowedAndRequiredKeys(
                            root, ERROR_KEYS, Set.of("type", "error"));
                    requiredString(root, "error", 2_048, false);
                    String code = root.has("code") && !root.isNull("code")
                            ? requiredString(root, "code", 128, false)
                            : "";
                    yield new ServerFailure(failureForCode(code));
                }
                default -> throw invalidProtocol();
            };
        } catch (JSONException | RuntimeException error) {
            throw invalidProtocol();
        }
    }

    private static String audioAppend(byte[] pcm, int offset, int length) {
        try {
            return new JSONObject()
                    .put("type", "input_audio_buffer.append")
                    .put("audio", ByteString.of(pcm, offset, length).base64())
                    .toString();
        } catch (JSONException error) {
            throw new IllegalStateException("unable to encode audio frame");
        }
    }

    private static String audioCommit() {
        try {
            return new JSONObject()
                    .put("type", "input_audio_buffer.commit")
                    .put("final", true)
                    .toString();
        } catch (JSONException error) {
            throw new IllegalStateException("unable to encode commit frame");
        }
    }

    private static String sessionUpdate(String model) {
        try {
            return new JSONObject()
                    .put("type", "session.update")
                    .put("model", model)
                    .toString();
        } catch (JSONException error) {
            throw new IllegalStateException("unable to encode session update");
        }
    }

    private static String serviceEndpoint(
            ProviderConfig.Endpoint endpoint,
            ServicePath servicePath) {
        HttpUrl base = HttpUrl.get(Objects.requireNonNull(endpoint, "endpoint").value());
        String path = base.encodedPath();
        String prefix;
        if (path.isEmpty() || "/".equals(path)) prefix = "/v1";
        else if ("/v1".equals(path) || "/v1/".equals(path)) prefix = "/v1";
        else throw new IllegalArgumentException("vLLM endpoint must be a root or /v1 base");
        HttpUrl target = base.newBuilder()
                .encodedPath(prefix + (servicePath == ServicePath.MODELS
                        ? "/models"
                        : "/realtime"))
                .build();
        if (servicePath == ServicePath.MODELS) return target.toString();
        String transport = "https".equals(base.scheme()) ? "wss" : "ws";
        return transport + target.toString().substring(target.scheme().length());
    }

    private static ProbeResult probeFailureForStatus(int status) {
        if (status >= 200 && status < 300) return null;
        if (status == 401 || status == 403) return ProbeResult.AUTHENTICATION;
        if (status == 404) return ProbeResult.PROTOCOL_ERROR;
        if (status == 408) return ProbeResult.NETWORK_TIMEOUT;
        if (status == 429) return ProbeResult.RATE_LIMITED;
        if (status >= 500) return ProbeResult.SERVER_ERROR;
        return ProbeResult.PROTOCOL_ERROR;
    }

    private static Failure failureForStatus(int status, Throwable error) {
        if (status == 401 || status == 403) return Failure.AUTHENTICATION;
        if (status == 408) return Failure.NETWORK_TIMEOUT;
        if (status == 429) return Failure.RATE_LIMITED;
        if (status >= 500) return Failure.SERVER_ERROR;
        if (status >= 300) return Failure.PROTOCOL_ERROR;
        if (error instanceof SocketTimeoutException) return Failure.NETWORK_TIMEOUT;
        if (error instanceof IOException) return Failure.NETWORK_UNAVAILABLE;
        return Failure.INTERNAL_ERROR;
    }

    private static Failure failureForCode(String code) {
        return switch (code) {
            case "model_not_found" -> Failure.MODEL_MISSING;
            case "invalid_event" -> Failure.PROTOCOL_ERROR;
            case "processing_error" -> Failure.SERVER_ERROR;
            default -> Failure.SERVER_ERROR;
        };
    }

    private static String boundedBody(ResponseBody body) throws IOException {
        if (body == null || body.contentLength() > MAX_PROBE_BYTES) {
            throw invalidProtocol();
        }
        BufferedSource source = body.source();
        if (source.request(MAX_PROBE_BYTES + 1L)) throw invalidProtocol();
        String text = source.readString(StandardCharsets.UTF_8);
        if (text.length() > MAX_PROBE_BYTES) throw invalidProtocol();
        requireJsonDepth(text);
        return text;
    }

    private static boolean containsModel(String json, String expected)
            throws JSONException {
        JSONTokener tokener = new JSONTokener(json);
        Object decoded = tokener.nextValue();
        if (!(decoded instanceof JSONObject root) || tokener.nextClean() != 0) {
            throw invalidProtocol();
        }
        Object rawData = root.get("data");
        if (!(rawData instanceof JSONArray data) || data.length() > MAX_MODELS) {
            throw invalidProtocol();
        }
        for (int index = 0; index < data.length(); index++) {
            Object rawModel = data.get(index);
            if (!(rawModel instanceof JSONObject model)) throw invalidProtocol();
            String id = requiredString(
                    model,
                    "id",
                    ProviderConfig.MAX_MODEL_ID_CODE_POINTS,
                    false);
            if (expected.equals(id)) return true;
        }
        return false;
    }

    private static void requireJsonBound(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_JSON_UTF16_UNITS) {
            throw invalidProtocol();
        }
    }

    private static void requireJsonDepth(String value) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (unit == '\\') escaped = true;
                else if (unit == '"') quoted = false;
                continue;
            }
            if (unit == '"') quoted = true;
            else if (unit == '{' || unit == '[') {
                if (++depth > MAX_JSON_DEPTH) throw invalidProtocol();
            } else if (unit == '}' || unit == ']') {
                if (--depth < 0) throw invalidProtocol();
            }
        }
        if (quoted || escaped || depth != 0) throw invalidProtocol();
    }

    private static String requiredRecognitionText(
            JSONObject object,
            String key,
            boolean allowEmpty) throws JSONException {
        return boundedText(
                requiredString(object, key, RecognitionEvent.MAX_TEXT_CODE_POINTS, allowEmpty),
                key,
                RecognitionEvent.MAX_TEXT_CODE_POINTS,
                allowEmpty);
    }

    private static String requiredString(
            JSONObject object,
            String key,
            int maximumCodePoints,
            boolean allowEmpty) throws JSONException {
        Object raw = object.get(key);
        if (!(raw instanceof String text)) throw invalidProtocol();
        return boundedText(text, key, maximumCodePoints, allowEmpty);
    }

    private static String boundedText(
            String value,
            String name,
            int maximumCodePoints,
            boolean allowEmpty) {
        String text = Objects.requireNonNull(value, name);
        if ((!allowEmpty && text.isEmpty())
                || text.length() > maximumCodePoints * 2
                || text.codePointCount(0, text.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(name + " is outside its bound");
        }
        for (int index = 0; index < text.length(); ) {
            char unit = text.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= text.length()
                        || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    throw new IllegalArgumentException(name + " must be well-formed UTF-16");
                }
                index += 2;
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException(name + " must be well-formed UTF-16");
            } else {
                index++;
            }
        }
        return text;
    }

    private static long requiredNonNegativeLong(JSONObject object, String key)
            throws JSONException {
        Object raw = object.get(key);
        if (!(raw instanceof Number number)
                || raw instanceof Double
                || raw instanceof Float) {
            throw invalidProtocol();
        }
        long value;
        try {
            value = Long.parseLong(number.toString());
        } catch (NumberFormatException error) {
            throw invalidProtocol();
        }
        if (value < 0L) throw invalidProtocol();
        return value;
    }

    private static void requireExactKeys(JSONObject object, Set<String> expected)
            throws JSONException {
        requireAllowedAndRequiredKeys(object, expected, expected);
    }

    private static void requireAllowedAndRequiredKeys(
            JSONObject object,
            Set<String> allowed,
            Set<String> required) throws JSONException {
        for (String key : required) {
            if (!object.has(key) || object.isNull(key)) throw invalidProtocol();
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            if (!allowed.contains(keys.next())) throw invalidProtocol();
        }
    }

    private static String credential(char[] value) {
        Objects.requireNonNull(value, "credential");
        if (value.length > MAX_CREDENTIAL_CODE_POINTS * 2) {
            throw new IllegalArgumentException("credential is outside its bound");
        }
        String token = new String(value);
        if (token.codePointCount(0, token.length()) > MAX_CREDENTIAL_CODE_POINTS
                || !token.equals(token.strip())) {
            throw new IllegalArgumentException("credential is outside its bound");
        }
        for (int index = 0; index < token.length(); index++) {
            char unit = token.charAt(index);
            if (Character.isISOControl(unit)
                    || (Character.isHighSurrogate(unit)
                            && (index + 1 >= token.length()
                                    || !Character.isLowSurrogate(token.charAt(index + 1))))
                    || Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("credential is invalid");
            }
            if (Character.isHighSurrogate(unit)) index++;
        }
        return token;
    }

    private static IllegalArgumentException invalidProtocol() {
        return new IllegalArgumentException("vLLM response is invalid");
    }
}
