package com.opentypeless.android.recognition;

import com.opentypeless.android.config.ProviderConfig;
import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.config.SecretRef;
import com.opentypeless.android.net.streaming.Qwen3AsrVllmClient;
import com.opentypeless.android.speech.core.SessionId;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Self-hosted Qwen3-ASR adapter for vLLM's bounded Realtime protocol. */
final class Qwen3AsrVllmProvider
        implements RecognitionProvider<WebSocketStreamingProvider.StartRequest> {
    private final Object probeLock = new Object();
    private final ProviderConfig.Asr config;
    private final ProviderDescriptor descriptor;
    private final Qwen3AsrVllmClient client;
    private final CredentialAccess credentialAccess;
    private final ProbeWorker probeWorker;
    private final WebSocketStreamingProvider delegate;

    private ProviderRegistry.ProbeObservation observation =
            new ProviderRegistry.ObservedUnavailable(
                    RecognitionRoute.FailureClass.UNAVAILABLE);
    private ProbeRequest activeProbe;
    private long probeGeneration;
    private boolean closed;

    static Qwen3AsrVllmProvider create(
            ProviderConfig.Asr config,
            CredentialAccess credentialAccess) {
        Qwen3AsrVllmClient client = new Qwen3AsrVllmClient();
        return new Qwen3AsrVllmProvider(
                config,
                client,
                credentialAccess,
                new SingleProbeWorker());
    }

    Qwen3AsrVllmProvider(
            ProviderConfig.Asr config,
            Qwen3AsrVllmClient client,
            CredentialAccess credentialAccess,
            ProbeWorker probeWorker) {
        this.config = requireRunnableConfig(config);
        this.client = Objects.requireNonNull(client, "client");
        this.credentialAccess = Objects.requireNonNull(credentialAccess, "credentialAccess");
        this.probeWorker = Objects.requireNonNull(probeWorker, "probeWorker");
        descriptor = new ProviderDescriptor(
                this.config.id(),
                this.config.displayName(),
                ProviderCapabilities.qwen3AsrVllm(privacyClass(this.config)));
        delegate = WebSocketStreamingProvider.create(
                this.config,
                descriptor,
                new ClientBackend(this.config, this.client, this.credentialAccess));
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ProviderRegistry.ProbeObservation probe() {
        synchronized (probeLock) {
            return closed
                    ? new ProviderRegistry.ObservedUnavailable(
                            RecognitionRoute.FailureClass.UNAVAILABLE)
                    : observation;
        }
    }

    /** Starts a bounded worker probe; callbacks run on that worker and carry no endpoint details. */
    boolean refreshCapabilities(ProbeListener listener) {
        ProbeListener safeListener = Objects.requireNonNull(listener, "listener");
        ProbeRequest request;
        synchronized (probeLock) {
            if (closed || activeProbe != null) return false;
            if (probeGeneration == Long.MAX_VALUE) {
                observation = new ProviderRegistry.ObservedUnavailable(
                        RecognitionRoute.FailureClass.INTERNAL_ERROR);
                return false;
            }
            request = new ProbeRequest(++probeGeneration, safeListener);
            activeProbe = request;
        }
        try {
            probeWorker.execute(() -> executeProbe(request));
            return true;
        } catch (RuntimeException ignored) {
            completeProbe(
                    request,
                    new ProviderRegistry.ObservedUnavailable(
                            RecognitionRoute.FailureClass.INTERNAL_ERROR));
            return false;
        }
    }

    @Override
    public PreparationResult prepare(WebSocketStreamingProvider.StartRequest request) {
        Objects.requireNonNull(request, "request");
        ProviderRegistry.ProbeObservation current = probe();
        if (current instanceof ProviderRegistry.ObservedUnavailable unavailable) {
            return new NotPrepared(unavailable.failureClass());
        }
        return delegate.prepare(request);
    }

    @Override
    public WebSocketStreamingProvider.StreamingSession start(
            WebSocketStreamingProvider.StartRequest request,
            EventSink sink) {
        WebSocketStreamingProvider.StartRequest safeRequest =
                Objects.requireNonNull(request, "request");
        EventSink safeSink = Objects.requireNonNull(sink, "sink");
        ProviderRegistry.ProbeObservation current = probe();
        if (current instanceof ProviderRegistry.ObservedUnavailable unavailable) {
            return new RejectedSession(
                    safeRequest.sessionId(), safeSink, unavailable.failureClass());
        }
        return delegate.start(safeRequest, safeSink);
    }

    @Override
    public void close() {
        synchronized (probeLock) {
            if (closed) return;
            closed = true;
            activeProbe = null;
            observation = new ProviderRegistry.ObservedUnavailable(
                    RecognitionRoute.FailureClass.UNAVAILABLE);
        }
        probeWorker.close();
        delegate.close();
    }

    private void executeProbe(ProbeRequest request) {
        ProviderRegistry.ProbeObservation result;
        try {
            Qwen3AsrVllmClient.Config clientConfig = clientConfig(request.sessionId);
            Qwen3AsrVllmClient.ProbeResult probeResult = withCredential(
                    credential -> client.probe(clientConfig, credential));
            result = observationForAvailable(probeResult);
        } catch (IllegalArgumentException error) {
            result = new ProviderRegistry.ObservedUnavailable(
                    RecognitionRoute.FailureClass.PROTOCOL_ERROR);
        } catch (Exception error) {
            result = new ProviderRegistry.ObservedUnavailable(
                    RecognitionRoute.FailureClass.AUTHENTICATION);
        }
        completeProbe(request, result);
    }

    private void completeProbe(
            ProbeRequest request,
            ProviderRegistry.ProbeObservation result) {
        ProbeListener target;
        synchronized (probeLock) {
            if (closed || activeProbe != request || request.generation != probeGeneration) return;
            observation = Objects.requireNonNull(result, "result");
            activeProbe = null;
            target = request.listener;
        }
        try {
            target.onResult(result);
        } catch (RuntimeException ignored) {
            // Probe state is already committed and callback details stay private.
        }
    }

    private Qwen3AsrVllmClient.Config clientConfig(SessionId sessionId) {
        return new Qwen3AsrVllmClient.Config(
                config.endpoint().orElseThrow(),
                sessionId,
                config.modelId().orElseThrow());
    }

    private <T> T withCredential(CredentialOperation<T> operation) throws Exception {
        Optional<SecretRef> reference = config.secretRef();
        if (reference.isEmpty()) return operation.apply(new char[0]);
        return credentialAccess.use(reference.orElseThrow(), operation);
    }

    private static ProviderRegistry.ProbeObservation observationFor(
            Qwen3AsrVllmClient.ProbeResult result) {
        return switch (Objects.requireNonNull(result, "result")) {
            case AVAILABLE -> throw new IllegalStateException(
                    "available probe requires provider capabilities");
            case MODEL_MISSING -> unavailable(RecognitionRoute.FailureClass.MODEL_MISSING);
            case AUTHENTICATION -> unavailable(RecognitionRoute.FailureClass.AUTHENTICATION);
            case RATE_LIMITED -> unavailable(RecognitionRoute.FailureClass.RATE_LIMITED);
            case SERVER_ERROR -> unavailable(RecognitionRoute.FailureClass.SERVER_ERROR);
            case NETWORK_TIMEOUT -> unavailable(RecognitionRoute.FailureClass.NETWORK_TIMEOUT);
            case NETWORK_UNAVAILABLE -> unavailable(
                    RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE);
            case PROTOCOL_ERROR -> unavailable(RecognitionRoute.FailureClass.PROTOCOL_ERROR);
            case INTERNAL_ERROR -> unavailable(RecognitionRoute.FailureClass.INTERNAL_ERROR);
        };
    }

    private ProviderRegistry.ProbeObservation observationForAvailable(
            Qwen3AsrVllmClient.ProbeResult result) {
        return result == Qwen3AsrVllmClient.ProbeResult.AVAILABLE
                ? new ProviderRegistry.ObservedAvailable(descriptor.capabilities())
                : observationFor(result);
    }

    private static ProviderRegistry.ObservedUnavailable unavailable(
            RecognitionRoute.FailureClass failureClass) {
        return new ProviderRegistry.ObservedUnavailable(failureClass);
    }

    private static ProviderConfig.Asr requireRunnableConfig(ProviderConfig.Asr value) {
        ProviderConfig.Asr config = Objects.requireNonNull(value, "config");
        if (!config.enabled() || config.endpoint().isEmpty() || config.modelId().isEmpty()) {
            throw new IllegalArgumentException("Qwen3-ASR provider configuration is incomplete");
        }
        new Qwen3AsrVllmClient.Config(
                config.endpoint().orElseThrow(),
                SessionId.of("qwen-probe-validation"),
                config.modelId().orElseThrow());
        return config;
    }

    private static RecognitionRoute.PrivacyClass privacyClass(ProviderConfig.Asr config) {
        try {
            URI endpoint = new URI(config.endpoint().orElseThrow().value());
            return isLocalHost(endpoint.getHost())
                    ? RecognitionRoute.PrivacyClass.LOCAL_NETWORK
                    : RecognitionRoute.PrivacyClass.PUBLIC_NETWORK;
        } catch (URISyntaxException impossibleAfterConfigValidation) {
            throw new IllegalArgumentException("Qwen3-ASR endpoint is invalid");
        }
    }

    private static boolean isLocalHost(String host) {
        if (host == null) return false;
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if ("localhost".equals(value)
                || value.endsWith(".localhost")
                || "::1".equals(value)
                || value.endsWith(".local")
                || value.startsWith("fe80:")
                || (value.contains(":") && (value.startsWith("fc") || value.startsWith("fd")))) {
            return true;
        }
        int[] address = ipv4(value);
        return address != null
                && (address[0] == 10
                        || address[0] == 127
                        || (address[0] == 169 && address[1] == 254)
                        || (address[0] == 172 && address[1] >= 16 && address[1] <= 31)
                        || (address[0] == 192 && address[1] == 168));
    }

    private static int[] ipv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] address = new int[4];
        try {
            for (int index = 0; index < parts.length; index++) {
                if (parts[index].isEmpty()) return null;
                int octet = Integer.parseInt(parts[index]);
                if (octet < 0 || octet > 255) return null;
                address[index] = octet;
            }
            return address;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    @Override
    public String toString() {
        synchronized (probeLock) {
            return "Qwen3AsrVllmProvider{closed=" + closed
                    + ", probeInFlight=" + (activeProbe != null)
                    + ", endpoint=<redacted>, model=<redacted>}";
        }
    }

    @FunctionalInterface
    interface ProbeListener {
        void onResult(ProviderRegistry.ProbeObservation observation);
    }

    interface ProbeWorker {
        void execute(Runnable action);

        void close();
    }

    interface CredentialAccess {
        <T> T use(SecretRef reference, CredentialOperation<T> operation) throws Exception;
    }

    @FunctionalInterface
    interface CredentialOperation<T> {
        T apply(char[] credential) throws Exception;
    }

    private static final class ProbeRequest {
        private final long generation;
        private final SessionId sessionId;
        private final ProbeListener listener;

        private ProbeRequest(long generation, ProbeListener listener) {
            this.generation = generation;
            sessionId = SessionId.of("qwen-probe-" + generation);
            this.listener = listener;
        }
    }

    private static final class SingleProbeWorker implements ProbeWorker {
        private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                action -> new Thread(action, "OpenTypeless-Qwen-Probe"),
                new ThreadPoolExecutor.AbortPolicy());

        @Override
        public void execute(Runnable action) {
            executor.execute(action);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static final class ClientBackend implements WebSocketStreamingProvider.Backend {
        private final ProviderConfig.Asr config;
        private final Qwen3AsrVllmClient client;
        private final CredentialAccess credentialAccess;

        private ClientBackend(
                ProviderConfig.Asr config,
                Qwen3AsrVllmClient client,
                CredentialAccess credentialAccess) {
            this.config = config;
            this.client = client;
            this.credentialAccess = credentialAccess;
        }

        @Override
        public WebSocketStreamingProvider.Connection open(
                SessionId sessionId,
                String language,
                WebSocketStreamingProvider.AttemptListener listener)
                throws WebSocketStreamingProvider.BackendException {
            Qwen3AsrVllmClient.Config clientConfig = new Qwen3AsrVllmClient.Config(
                    config.endpoint().orElseThrow(),
                    sessionId,
                    config.modelId().orElseThrow());
            try {
                Optional<SecretRef> reference = config.secretRef();
                if (reference.isEmpty()) {
                    return open(clientConfig, new char[0], listener);
                }
                return credentialAccess.use(
                        reference.orElseThrow(),
                        credential -> open(clientConfig, credential, listener));
            } catch (WebSocketStreamingProvider.BackendException error) {
                throw error;
            } catch (IllegalArgumentException error) {
                throw new WebSocketStreamingProvider.BackendException(
                        WebSocketStreamingProvider.ClientFailure.PROTOCOL_ERROR);
            } catch (Exception error) {
                throw new WebSocketStreamingProvider.BackendException(
                        WebSocketStreamingProvider.ClientFailure.AUTHENTICATION);
            }
        }

        private WebSocketStreamingProvider.Connection open(
                Qwen3AsrVllmClient.Config clientConfig,
                char[] credential,
                WebSocketStreamingProvider.AttemptListener listener)
                throws WebSocketStreamingProvider.BackendException {
            try {
                Qwen3AsrVllmClient.Session session = client.open(
                        clientConfig,
                        credential,
                        new Qwen3AsrVllmClient.Listener() {
                            @Override
                            public void onOpen() {
                                listener.onOpen();
                            }

                            @Override
                            public void onEvent(RecognitionEvent event) {
                                listener.onEvent(event);
                            }

                            @Override
                            public void onFailure(Qwen3AsrVllmClient.Failure failure) {
                                listener.onFailure(clientFailure(failure));
                            }
                        });
                return new WebSocketStreamingProvider.Connection() {
                    @Override
                    public boolean sendPcm(byte[] pcm, int length) {
                        return session.sendPcm(pcm, 0, length);
                    }

                    @Override
                    public boolean finish() {
                        return session.finish();
                    }

                    @Override
                    public long queuedBytes() {
                        return session.queuedBytes();
                    }

                    @Override
                    public void cancel() {
                        session.cancel();
                    }

                    @Override
                    public void close() {
                        session.close();
                    }
                };
            } catch (IllegalArgumentException error) {
                throw new WebSocketStreamingProvider.BackendException(
                        WebSocketStreamingProvider.ClientFailure.PROTOCOL_ERROR);
            } catch (RuntimeException error) {
                throw new WebSocketStreamingProvider.BackendException(
                        WebSocketStreamingProvider.ClientFailure.INTERNAL_ERROR);
            }
        }

        @Override
        public void close() {
            client.close();
        }

        private static WebSocketStreamingProvider.ClientFailure clientFailure(
                Qwen3AsrVllmClient.Failure failure) {
            return switch (failure) {
                case MODEL_MISSING -> WebSocketStreamingProvider.ClientFailure.MODEL_MISSING;
                case AUTHENTICATION -> WebSocketStreamingProvider.ClientFailure.AUTHENTICATION;
                case RATE_LIMITED -> WebSocketStreamingProvider.ClientFailure.RATE_LIMITED;
                case SERVER_ERROR -> WebSocketStreamingProvider.ClientFailure.SERVER_ERROR;
                case NETWORK_TIMEOUT -> WebSocketStreamingProvider.ClientFailure.NETWORK_TIMEOUT;
                case NETWORK_UNAVAILABLE ->
                        WebSocketStreamingProvider.ClientFailure.NETWORK_UNAVAILABLE;
                case PROTOCOL_ERROR -> WebSocketStreamingProvider.ClientFailure.PROTOCOL_ERROR;
                case INTERNAL_ERROR -> WebSocketStreamingProvider.ClientFailure.INTERNAL_ERROR;
            };
        }
    }

    private final class RejectedSession implements WebSocketStreamingProvider.StreamingSession {
        private final SessionId sessionId;
        private boolean terminal;

        private RejectedSession(
                SessionId sessionId,
                EventSink sink,
                RecognitionRoute.FailureClass failureClass) {
            this.sessionId = sessionId;
            terminal = true;
            try {
                sink.onEvent(new RecognitionEvent.Failure(sessionId, 1L, failureClass));
            } catch (RuntimeException ignored) {
                // Rejection is already terminal.
            }
        }

        @Override
        public SessionId sessionId() {
            return sessionId;
        }

        @Override
        public boolean acceptPcm(byte[] pcm, int length) {
            Objects.requireNonNull(pcm, "pcm");
            return false;
        }

        @Override
        public int acceptedPcmBytes() {
            return 0;
        }

        @Override
        public void stop() {}

        @Override
        public void cancel() {}

        @Override
        public void close() {}

        @Override
        public String toString() {
            return "Qwen3AsrRejectedSession{terminal=" + terminal
                    + ", session=<redacted>}";
        }
    }
}
