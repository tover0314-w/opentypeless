package com.opentypeless.android.recognition;

import android.content.Context;
import android.os.SystemClock;

import com.opentypeless.android.config.EffectiveProfile;
import com.opentypeless.android.config.EffectiveProfileResolver;
import com.opentypeless.android.config.GlobalConfig;
import com.opentypeless.android.config.OverrideValue;
import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.config.RecognitionRoute.ConfirmationPolicy;
import com.opentypeless.android.config.RecognitionRoute.FailureClass;
import com.opentypeless.android.config.RecognitionRoute.ProviderCapability;
import com.opentypeless.android.config.RecognitionRoute.RetryPolicy;
import com.opentypeless.android.config.RecognitionRoute.RouteStep;
import com.opentypeless.android.config.RuleOverrides;
import com.opentypeless.android.context.InputContext;
import com.opentypeless.android.ime.DictationRequest;
import com.opentypeless.android.ime.VoiceController;
import com.opentypeless.android.ime.VoicePipelineAdapter;
import com.opentypeless.android.settings.RecognitionBackend;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * STR-010 production migration boundary from {@link VoiceController} to the finite recognition
 * router.
 *
 * <p>Each start resolves one exact effective profile, registers one reviewed descriptor, obtains
 * one opaque router attempt and freezes that binding before the compatibility executor may open a
 * microphone or provider connection. A rejected, stale or terminal route never falls through to
 * the delegate. The wrapper retains no transcript, audio, endpoint, credential or editor
 * capability; the compatibility executor remains available only as the whole-session rollback
 * implementation selected by {@link RecognitionRouterVoiceConfig}.
 */
public final class RecognitionRouterVoiceController implements VoiceController {
    private final Object lifecycleLock = new Object();
    private final VoiceController delegate;
    private final Environment environment;
    private final ProviderCircuitBreaker circuitBreaker;
    private final String fallbackPackageName;
    private final Map<RecognitionBackend, ProviderDescriptor> descriptors;

    private Preparation preparing;
    private ActiveRun active;
    private long generation;

    public RecognitionRouterVoiceController(Context context, VoicePipelineAdapter delegate) {
        Context application = Objects.requireNonNull(context, "context").getApplicationContext();
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        environment = new AndroidEnvironment();
        circuitBreaker = new ProviderCircuitBreaker(SystemClock::elapsedRealtime);
        fallbackPackageName = application.getPackageName();
        descriptors = canonicalDescriptors();
    }

    RecognitionRouterVoiceController(
            VoiceController delegate,
            Environment environment,
            ProviderCircuitBreaker circuitBreaker,
            String fallbackPackageName) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        this.fallbackPackageName = requirePackageName(fallbackPackageName);
        descriptors = canonicalDescriptors();
    }

    @Override
    public boolean start(DictationRequest request, Events events) {
        DictationRequest safeRequest = Objects.requireNonNull(request, "request");
        Events safeEvents = Objects.requireNonNull(events, "events");
        Preparation reservation;
        synchronized (lifecycleLock) {
            if (preparing != null || active != null || delegate.state() != State.IDLE) {
                return false;
            }
            if (generation == Long.MAX_VALUE) {
                reservation = null;
            } else {
                reservation = new Preparation(++generation);
                preparing = reservation;
            }
        }
        if (reservation == null) {
            safeEvents.onError(stableFailure(FailureClass.INTERNAL_ERROR));
            return true;
        }

        PreparationResult prepared = prepareRun(
                reservation.generation,
                safeRequest,
                safeEvents);
        ActiveRun run = prepared.run;
        boolean stale;
        boolean stopAfterStart;
        synchronized (lifecycleLock) {
            stale = preparing != reservation;
            stopAfterStart = reservation.stopRequested;
            if (!stale) {
                preparing = null;
                if (run != null) active = run;
            }
        }
        if (stale) {
            prepared.discard();
            return true;
        }
        if (run == null) {
            safeEvents.onError(stableFailure(prepared.failure));
            return true;
        }

        boolean accepted;
        try {
            accepted = delegate.start(safeRequest, eventsFor(run));
        } catch (RuntimeException ignored) {
            terminalFailure(run, FailureClass.INTERNAL_ERROR, true);
            return true;
        }
        if (accepted) {
            if (stopAfterStart && isCurrent(run)) delegate.stop();
            return true;
        }
        terminalFailure(run, FailureClass.RECOGNIZER_BUSY, false);
        return false;
    }

    @Override
    public void stop() {
        boolean stopDelegate;
        synchronized (lifecycleLock) {
            if (preparing != null) {
                preparing.stopRequested = true;
                return;
            }
            stopDelegate = active != null;
        }
        if (stopDelegate) delegate.stop();
    }

    @Override
    public void cancel() {
        ActiveRun run;
        synchronized (lifecycleLock) {
            if (preparing != null) {
                preparing = null;
                return;
            }
            run = active;
            if (run == null) return;
            active = null;
        }
        run.router.onFailure(run.attempt, FailureClass.CANCELLED);
        delegate.cancel();
    }

    @Override
    public State state() {
        synchronized (lifecycleLock) {
            return active == null ? State.IDLE : delegate.state();
        }
    }

    private PreparationResult prepareRun(
            long runGeneration,
            DictationRequest request,
            Events events) {
        PreparedRoute prepared;
        try {
            prepared = environment.prepare(request, fallbackPackageName);
        } catch (RuntimeException ignored) {
            return PreparationResult.failed(FailureClass.INTERNAL_ERROR);
        }
        RecognitionBackend requestedBackend = request.settings().recognitionBackend();
        if (prepared.backend != requestedBackend
                || !prepared.routeId.equals(routeId(requestedBackend))) {
            return PreparationResult.failed(FailureClass.INTERNAL_ERROR);
        }
        ProviderDescriptor descriptor = descriptors.get(prepared.backend);
        if (prepared.probe instanceof ProviderRegistry.ObservedUnavailable unavailable) {
            return PreparationResult.failed(unavailable.failureClass());
        }
        if (!(prepared.probe instanceof ProviderRegistry.ObservedAvailable available)
                || !available.capabilities().equals(descriptor.capabilities())) {
            return PreparationResult.failed(FailureClass.UNAVAILABLE);
        }

        ProviderRegistry registry = new ProviderRegistry();
        if (registry.register(descriptor, () -> prepared.probe, true)
                != ProviderRegistry.RegistrationResult.REGISTERED) {
            return PreparationResult.failed(FailureClass.INTERNAL_ERROR);
        }
        RecognitionRoute route = directRoute(prepared.routeId, descriptor);
        RecognitionRouter router = new RecognitionRouter(
                route,
                registry,
                prepared.profile,
                RecognitionRouter.PrivacyAuthorization.preauthorized(
                        prepared.profile, descriptor.capabilities().privacyClass()),
                circuitBreaker);
        RecognitionRouter.Decision decision = router.start();
        if (!(decision instanceof RecognitionRouter.AttemptReady ready)
                || ready.attempt().descriptor() != descriptor
                || backendFor(descriptor) != prepared.backend) {
            FailureClass failure = decision instanceof RecognitionRouter.RouteFailed failed
                    ? failed.failureClass()
                    : FailureClass.INTERNAL_ERROR;
            return PreparationResult.failed(failure);
        }
        return PreparationResult.ready(new ActiveRun(
                runGeneration,
                prepared.backend,
                router,
                ready.attempt(),
                events));
    }

    private Events eventsFor(ActiveRun run) {
        return new Events() {
            @Override
            public void onState(State state, String message) {
                if (isCurrent(run)) run.events.onState(state, message);
            }

            @Override
            public void onRoute(com.opentypeless.android.diagnostics.RecognitionRoute route) {
                if (isCurrent(run)) run.events.onRoute(route);
            }

            @Override
            public void onReadyForSpeech() {
                if (isCurrent(run)) run.events.onReadyForSpeech();
            }

            @Override
            public void onBeginningOfSpeech() {
                if (isCurrent(run)) run.events.onBeginningOfSpeech();
            }

            @Override
            public void onTranscript(com.opentypeless.android.ime.TranscriptUpdate update) {
                if (isCurrent(run)) run.events.onTranscript(update);
            }

            @Override
            public void onResult(com.opentypeless.android.ime.DictationResult result) {
                boolean deliver;
                synchronized (lifecycleLock) {
                    deliver = active == run && run.router.isCurrent(run.attempt);
                    if (deliver) {
                        RecognitionRouter.Decision decision = run.router.onSuccess(run.attempt);
                        deliver = decision instanceof RecognitionRouter.Completed;
                        active = null;
                    }
                }
                if (deliver) run.events.onResult(result);
            }

            @Override
            public void onError(String message) {
                FailureClass failure = RecognitionFailureMapper.fromLegacyPipelineMessage(message);
                terminalFailure(run, failure, true);
            }
        };
    }

    private void terminalFailure(ActiveRun run, FailureClass failure, boolean deliver) {
        boolean current;
        synchronized (lifecycleLock) {
            current = active == run;
            if (current) {
                run.router.onFailure(run.attempt, failure);
                active = null;
            }
        }
        if (current && deliver) run.events.onError(stableFailure(failure));
    }

    private boolean isCurrent(ActiveRun run) {
        synchronized (lifecycleLock) {
            return active == run && run.router.isCurrent(run.attempt);
        }
    }

    private static RecognitionRoute directRoute(
            String routeId,
            ProviderDescriptor descriptor) {
        ProviderCapabilities capabilities = descriptor.capabilities();
        return new RecognitionRoute(
                routeId,
                List.of(new RouteStep(
                        descriptor.id(),
                        capabilities.privacyClass(),
                        new RetryPolicy(1, Set.of()),
                        Set.of(),
                        requiredCapabilities(capabilities),
                        ConfirmationPolicy.NOT_REQUIRED)),
                capabilities.privacyClass(),
                false);
    }

    private static Set<ProviderCapability> requiredCapabilities(
            ProviderCapabilities capabilities) {
        EnumSet<ProviderCapability> required = EnumSet.noneOf(ProviderCapability.class);
        if (capabilities.supportsStreaming()) required.add(ProviderCapability.STREAMING);
        if (capabilities.supportsPartialRevision()) {
            required.add(ProviderCapability.PARTIAL_REVISION);
        }
        if (capabilities.supportsEndpointing()) required.add(ProviderCapability.ENDPOINTING);
        if (capabilities.supportsOnDevice()) required.add(ProviderCapability.ON_DEVICE);
        if (capabilities.supportsPrompt()) required.add(ProviderCapability.PROMPT);
        if (capabilities.supportsBiasingTerms()) required.add(ProviderCapability.BIASING_TERMS);
        if (capabilities.supportsDynamicKeyterms()) {
            required.add(ProviderCapability.DYNAMIC_KEYTERMS);
        }
        if (capabilities.supportsLanguageDetection()) {
            required.add(ProviderCapability.LANGUAGE_DETECTION);
        }
        if (capabilities.supportsTimestamps()) required.add(ProviderCapability.TIMESTAMPS);
        if (capabilities.supportsAudioUpload()) required.add(ProviderCapability.AUDIO_UPLOAD);
        return Set.copyOf(required);
    }

    private static Map<RecognitionBackend, ProviderDescriptor> canonicalDescriptors() {
        EnumMap<RecognitionBackend, ProviderDescriptor> canonical =
                new EnumMap<>(RecognitionBackend.class);
        for (RecognitionBackend backend : RecognitionBackend.values()) {
            ProviderDescriptor descriptor = backend == RecognitionBackend.LOCAL_OFFLINE
                    ? new ProviderDescriptor(
                            "builtin.local-two-stage",
                            "Streaming Paraformer + SenseVoice",
                            ProviderCapabilities.localTwoStage())
                    : ProviderDescriptor.declaredForBackend(backend);
            canonical.put(backend, descriptor);
        }
        return Map.copyOf(canonical);
    }

    private static RecognitionBackend backendFor(ProviderDescriptor descriptor) {
        if ("builtin.local-two-stage".equals(descriptor.id())) {
            return RecognitionBackend.LOCAL_OFFLINE;
        }
        for (RecognitionBackend backend : RecognitionBackend.values()) {
            if (ProviderDescriptor.declaredForBackend(backend).id().equals(descriptor.id())) {
                return backend;
            }
        }
        throw new IllegalArgumentException("provider execution binding is unavailable");
    }

    private static String routeId(RecognitionBackend backend) {
        return switch (backend) {
            case OPENAI_COMPATIBLE -> "legacy.openai-compatible";
            case LOCAL_OFFLINE -> "legacy.local-offline";
            case DASHSCOPE_STREAMING -> "legacy.dashscope-streaming";
            case SYSTEM_ON_DEVICE -> "legacy.system-on-device";
            case SYSTEM_DEFAULT -> "legacy.system-default";
        };
    }

    private static String stableFailure(FailureClass failure) {
        return switch (Objects.requireNonNull(failure, "failure")) {
            case PERMISSION_DENIED -> "Voice input is disabled for this field";
            case OEM_MIC_BLOCKED -> "Microphone access is blocked by the device";
            case MODEL_MISSING -> "The selected speech model is not installed";
            case AUTHENTICATION -> "Speech provider authentication failed";
            case QUOTA_EXCEEDED, RATE_LIMITED -> "Speech provider usage is temporarily limited";
            case NETWORK_UNAVAILABLE, NETWORK_TIMEOUT -> "Speech provider network is unavailable";
            case NO_MATCH, SPEECH_TIMEOUT -> "No speech was recognized";
            case CANCELLED -> "Voice input was cancelled";
            case TARGET_CHANGED -> "The input field changed before voice input completed";
            case UNSUPPORTED_LANGUAGE -> "The selected language is not supported";
            case RECOGNIZER_BUSY -> "Another speech recognition session is already active";
            case AUDIO_ERROR -> "Voice audio could not be captured";
            case SERVER_ERROR, PROTOCOL_ERROR, UNAVAILABLE, INTERNAL_ERROR ->
                    "The selected speech route is unavailable";
        };
    }

    private static String requirePackageName(String value) {
        String safe = Objects.requireNonNull(value, "fallbackPackageName");
        if (safe.isBlank() || safe.length() > RuleOverrides.MAX_PACKAGE_NAME_CODE_POINTS) {
            throw new IllegalArgumentException("fallback package name is invalid");
        }
        return safe;
    }

    interface Environment {
        PreparedRoute prepare(DictationRequest request, String fallbackPackageName);
    }

    record PreparedRoute(
            RecognitionBackend backend,
            String routeId,
            EffectiveProfile profile,
            ProviderRegistry.ProbeObservation probe) {
        PreparedRoute {
            backend = Objects.requireNonNull(backend, "backend");
            routeId = Objects.requireNonNull(routeId, "routeId");
            profile = Objects.requireNonNull(profile, "profile");
            probe = Objects.requireNonNull(probe, "probe");
        }

        @Override
        public String toString() {
            return "PreparedRoute{backend=" + backend + ", policy=<redacted>}";
        }
    }

    private record ActiveRun(
            long generation,
            RecognitionBackend backend,
            RecognitionRouter router,
            RecognitionRouter.Attempt attempt,
            Events events) {
        @Override
        public String toString() {
            return "ActiveRun{generation=" + generation
                    + ", backend=" + backend + ", content=<redacted>}";
        }
    }

    private static final class Preparation {
        private final long generation;
        private boolean stopRequested;

        private Preparation(long generation) {
            this.generation = generation;
        }
    }

    private record PreparationResult(ActiveRun run, FailureClass failure) {
        private PreparationResult {
            if ((run == null) == (failure == null)) {
                throw new IllegalArgumentException("preparation result must have one outcome");
            }
        }

        private static PreparationResult ready(ActiveRun run) {
            return new PreparationResult(Objects.requireNonNull(run, "run"), null);
        }

        private static PreparationResult failed(FailureClass failure) {
            return new PreparationResult(null, Objects.requireNonNull(failure, "failure"));
        }

        private void discard() {
            if (run != null) run.router.onFailure(run.attempt, FailureClass.CANCELLED);
        }

        @Override
        public String toString() {
            return "PreparationResult{outcome="
                    + (run == null ? failure : "READY") + ", content=<redacted>}";
        }
    }

    private static final class AndroidEnvironment implements Environment {
        @Override
        public PreparedRoute prepare(DictationRequest request, String fallbackPackageName) {
            RecognitionBackend backend = Objects.requireNonNull(
                    request.settings().recognitionBackend(), "recognitionBackend");
            String expectedRouteId = routeId(backend);
            GlobalConfig global = compatibilityConfig(request, expectedRouteId);
            InputContext input = Objects.requireNonNull(request.inputContext(), "inputContext");
            String packageName = input.packageName().isBlank()
                    ? fallbackPackageName
                    : input.packageName();
            EffectiveProfile profile = EffectiveProfileResolver.resolve(
                    new EffectiveProfileResolver.Request(
                            global,
                            defaults(global),
                            List.of(),
                            List.of(),
                            inheritAll(),
                            packageName,
                            input.fieldKind()));
            ProviderDescriptor descriptor = backend == RecognitionBackend.LOCAL_OFFLINE
                    ? new ProviderDescriptor(
                            "builtin.local-two-stage",
                            "Streaming Paraformer + SenseVoice",
                            ProviderCapabilities.localTwoStage())
                    : ProviderDescriptor.declaredForBackend(backend);
            ProviderRegistry.ProbeObservation probe = request.settings().isReady()
                    ? new ProviderRegistry.ObservedAvailable(descriptor.capabilities())
                    : new ProviderRegistry.ObservedUnavailable(FailureClass.UNAVAILABLE);
            return new PreparedRoute(backend, expectedRouteId, profile, probe);
        }

        private static GlobalConfig compatibilityConfig(
                DictationRequest request,
                String routeId) {
            com.opentypeless.android.settings.AppSettings settings = request.settings();
            return new GlobalConfig(
                    GlobalConfig.FORMAT_VERSION,
                    new GlobalConfig.KeyboardConfig("latin.base"),
                    new GlobalConfig.VoiceConfig(OverrideValue.value(routeId)),
                    new GlobalConfig.ProcessingConfig(OverrideValue.value(
                            processingMode(settings.defaultMode()))),
                    new GlobalConfig.PrivacyConfig(
                            OverrideValue.value(settings.sendContext()),
                            OverrideValue.value(settings.historyEnabled())),
                    new GlobalConfig.AutomationConfig(OverrideValue.disabled()));
        }

        private static com.opentypeless.android.config.ProcessingMode processingMode(
                com.opentypeless.android.settings.ProcessingMode mode) {
            return switch (Objects.requireNonNull(mode, "mode")) {
                case AUTO -> com.opentypeless.android.config.ProcessingMode.AUTO;
                case VERBATIM -> com.opentypeless.android.config.ProcessingMode.EXACT;
                case SMART -> com.opentypeless.android.config.ProcessingMode.SMART;
                case TRANSLATE -> com.opentypeless.android.config.ProcessingMode.TRANSLATE;
            };
        }

        private static EffectiveProfileResolver.ProviderDefaults defaults(GlobalConfig global) {
            return new EffectiveProfileResolver.ProviderDefaults(
                    global.voice().routeId(),
                    global.processing().mode(),
                    global.privacy().sendContext(),
                    global.privacy().historyEnabled(),
                    global.automation().actionSetId());
        }

        private static RuleOverrides inheritAll() {
            return new RuleOverrides(
                    OverrideValue.inherit(),
                    OverrideValue.inherit(),
                    OverrideValue.inherit(),
                    OverrideValue.inherit(),
                    OverrideValue.inherit());
        }
    }
}
