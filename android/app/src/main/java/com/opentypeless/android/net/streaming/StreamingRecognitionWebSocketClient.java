package com.opentypeless.android.net.streaming;

import com.opentypeless.android.config.ProviderConfig;
import com.opentypeless.android.recognition.RecognitionEvent;
import com.opentypeless.android.speech.core.SessionId;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Narrow WebSocket transport for the STR-001 event contract.
 *
 * <p>The client performs no routing, audio capture, retry, persistence, or editor work. Repository
 * architecture gates restrict its public transport surface to the reviewed STR-002 Provider.
 */
public final class StreamingRecognitionWebSocketClient implements AutoCloseable {
    public static final int MAX_PCM_FRAME_BYTES = 64 * 1_024;
    public static final long MAX_OUTGOING_QUEUE_BYTES = 256L * 1_024L;
    private static final int MAX_CREDENTIAL_CODE_POINTS = 4_096;

    private final OkHttpClient client;

    public StreamingRecognitionWebSocketClient() {
        this(new OkHttpClient.Builder()
                .connectTimeout(10L, TimeUnit.SECONDS)
                .callTimeout(0L, TimeUnit.MILLISECONDS)
                .readTimeout(0L, TimeUnit.MILLISECONDS)
                .pingInterval(15L, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build());
    }

    StreamingRecognitionWebSocketClient(OkHttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public Session open(Config config, char[] credential, Listener listener) {
        Config safeConfig = Objects.requireNonNull(config, "config");
        Listener safeListener = Objects.requireNonNull(listener, "listener");
        String token = credential(credential);
        Request.Builder request = new Request.Builder()
                .url(webSocketEndpoint(safeConfig.endpoint()))
                .header("User-Agent", "OpenTypeless-Android/0.3");
        if (!token.isEmpty()) request.header("Authorization", "Bearer " + token);
        SessionImpl session = new SessionImpl(safeConfig, safeListener);
        session.start(request.build());
        return session;
    }

    @Override
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    public record Config(
            ProviderConfig.Endpoint endpoint,
            SessionId sessionId,
            String model,
            String language) {
        public Config {
            endpoint = Objects.requireNonNull(endpoint, "endpoint");
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            model = bounded(model, "model", ProviderConfig.MAX_MODEL_ID_CODE_POINTS, false);
            language = bounded(language, "language", 63, true);
        }

        @Override
        public String toString() {
            return "StreamingWebSocketConfig{endpoint=<redacted>, session=<redacted>, "
                    + "model=<redacted>, language=<redacted>}";
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

    public enum Failure {
        AUTHENTICATION,
        RATE_LIMITED,
        SERVER_ERROR,
        NETWORK_TIMEOUT,
        NETWORK_UNAVAILABLE,
        PROTOCOL_ERROR,
        INTERNAL_ERROR
    }

    private final class SessionImpl extends WebSocketListener implements Session {
        private final StreamingRecognitionWireEvent.Stream stream;
        private String startFrame;
        private String finishFrame;
        private String cancelFrame;
        private Listener listener;
        private WebSocket webSocket;
        private boolean opened;
        private boolean finishing;
        private boolean cancelled;
        private boolean terminal;

        private SessionImpl(Config config, Listener listener) {
            stream = new StreamingRecognitionWireEvent.Stream(config.sessionId());
            startFrame = controlFrame(config, "start");
            finishFrame = controlFrame(config, "finish");
            cancelFrame = controlFrame(config, "cancel");
            this.listener = listener;
        }

        private void start(Request request) {
            synchronized (this) {
                webSocket = client.newWebSocket(request, this);
            }
        }

        @Override
        public void onOpen(WebSocket socket, Response response) {
            String frame;
            Listener target;
            synchronized (this) {
                if (cancelled || terminal || socket != webSocket) {
                    socket.cancel();
                    return;
                }
                opened = true;
                frame = startFrame;
                startFrame = null;
                target = listener;
            }
            if (frame == null || !socket.send(frame)) {
                fail(Failure.NETWORK_UNAVAILABLE);
                return;
            }
            try {
                if (target != null) target.onOpen();
            } catch (RuntimeException ignored) {
                fail(Failure.INTERNAL_ERROR);
            }
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            StreamingRecognitionWireEvent.Result result;
            synchronized (this) {
                if (cancelled || terminal || socket != webSocket) return;
            }
            try {
                result = stream.accept(text);
            } catch (RuntimeException ignored) {
                fail(Failure.PROTOCOL_ERROR);
                return;
            }
            if (!(result instanceof StreamingRecognitionWireEvent.Accepted accepted)) {
                fail(Failure.PROTOCOL_ERROR);
                return;
            }
            RecognitionEvent event = accepted.event();
            Listener target;
            boolean eventTerminal = event.terminal();
            synchronized (this) {
                if (cancelled || terminal || socket != webSocket) return;
                if (eventTerminal) terminal = true;
                target = listener;
                if (eventTerminal) releaseContentLocked();
            }
            try {
                if (target != null) target.onEvent(event);
            } catch (RuntimeException ignored) {
                synchronized (this) {
                    terminal = true;
                    releaseContentLocked();
                }
                socket.cancel();
                return;
            }
            if (eventTerminal) socket.close(1000, "complete");
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
            ByteString frame = ByteString.of(pcm, offset, length);
            boolean sent;
            synchronized (this) {
                if (!opened || finishing || cancelled || terminal || webSocket == null) {
                    return false;
                }
                if (webSocket.queueSize() > MAX_OUTGOING_QUEUE_BYTES - length) return false;
                sent = webSocket.send(frame);
            }
            if (!sent) fail(Failure.NETWORK_UNAVAILABLE);
            return sent;
        }

        @Override
        public boolean finish() {
            WebSocket socket;
            String frame;
            synchronized (this) {
                if (!opened || finishing || cancelled || terminal || webSocket == null) {
                    return false;
                }
                finishing = true;
                socket = webSocket;
                frame = finishFrame;
                finishFrame = null;
            }
            boolean sent = frame != null && socket.send(frame);
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
            String frame;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                terminal = true;
                socket = webSocket;
                frame = cancelFrame;
                releaseContentLocked();
            }
            if (socket != null) {
                if (opened && frame != null) socket.send(frame);
                socket.cancel();
            }
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
                // Transport authority is already revoked and raw callback details stay private.
            }
        }

        private void releaseContentLocked() {
            listener = null;
            startFrame = null;
            finishFrame = null;
            cancelFrame = null;
        }

        @Override
        public synchronized String toString() {
            return "StreamingWebSocketSession{opened=" + opened
                    + ", finishing=" + finishing
                    + ", terminal=" + terminal
                    + ", content=<redacted>}";
        }
    }

    private static String controlFrame(Config config, String type) {
        try {
            JSONObject frame = new JSONObject()
                    .put("protocol", StreamingRecognitionWireEvent.PROTOCOL)
                    .put("session_id", config.sessionId().value())
                    .put("type", type);
            if (type.equals("start")) {
                frame.put("model", config.model());
                frame.put("language", config.language());
                frame.put("audio_format", "pcm_s16le_16000_mono");
            }
            return frame.toString();
        } catch (JSONException error) {
            throw new IllegalStateException("unable to encode streaming control frame");
        }
    }

    private static Failure failureForStatus(int status, Throwable error) {
        if (status == 401 || status == 403) return Failure.AUTHENTICATION;
        if (status == 408) return Failure.NETWORK_TIMEOUT;
        if (status == 429) return Failure.RATE_LIMITED;
        if (status >= 500 && status <= 599) return Failure.SERVER_ERROR;
        if (status > 0) return Failure.PROTOCOL_ERROR;
        if (error instanceof SocketTimeoutException) return Failure.NETWORK_TIMEOUT;
        return Failure.NETWORK_UNAVAILABLE;
    }

    private static String webSocketEndpoint(ProviderConfig.Endpoint endpoint) {
        URI uri = URI.create(endpoint.value());
        String scheme = uri.getScheme().equalsIgnoreCase("https") ? "wss" : "ws";
        return scheme + endpoint.value().substring(endpoint.value().indexOf(':'));
    }

    private static String credential(char[] value) {
        char[] copy = Arrays.copyOf(Objects.requireNonNull(value, "credential"), value.length);
        try {
            String text = new String(copy);
            if (text.length() > MAX_CREDENTIAL_CODE_POINTS * 2
                    || text.codePointCount(0, text.length()) > MAX_CREDENTIAL_CODE_POINTS
                    || !text.equals(text.strip())) {
                throw new IllegalArgumentException("credential is outside its bound");
            }
            for (int index = 0; index < text.length(); ) {
                char unit = text.charAt(index);
                if (Character.isHighSurrogate(unit)) {
                    if (index + 1 >= text.length()
                            || !Character.isLowSurrogate(text.charAt(index + 1))) {
                        throw new IllegalArgumentException("credential has malformed UTF-16");
                    }
                    index += 2;
                } else if (Character.isLowSurrogate(unit)) {
                    throw new IllegalArgumentException("credential has malformed UTF-16");
                } else {
                    int codePoint = text.codePointAt(index);
                    if (Character.isISOControl(codePoint)) {
                        throw new IllegalArgumentException("credential contains a control character");
                    }
                    index += Character.charCount(codePoint);
                }
            }
            return text;
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    private static String bounded(
            String value, String label, int maximumCodePoints, boolean emptyAllowed) {
        String text = Objects.requireNonNull(value, label);
        if ((!emptyAllowed && text.isEmpty())
                || text.length() > maximumCodePoints * 2
                || text.codePointCount(0, text.length()) > maximumCodePoints
                || !text.equals(text.strip())) {
            throw new IllegalArgumentException(label + " is outside its bound");
        }
        for (int index = 0; index < text.length(); ) {
            char unit = text.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= text.length()
                        || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    throw new IllegalArgumentException(label + " has malformed UTF-16");
                }
                index += 2;
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException(label + " has malformed UTF-16");
            } else {
                int codePoint = text.codePointAt(index);
                if (Character.isISOControl(codePoint)) {
                    throw new IllegalArgumentException(label + " contains a control character");
                }
                index += Character.charCount(codePoint);
            }
        }
        return text;
    }
}
