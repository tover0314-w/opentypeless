package com.opentypeless.android.recognition;

import android.speech.SpeechRecognizer;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.net.OpenAiCompatibleClient;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Single content-free mapping boundary from provider/legacy failures to route failures.
 *
 * <p>Raw OEM, transport, provider, and pipeline messages are classification inputs only. They are
 * never returned, logged, or retained by this class.
 */
final class RecognitionFailureMapper {
    enum LocalAvailability {
        READY,
        MODEL_MISSING,
        MODEL_CORRUPT,
        LOW_MEMORY,
        UNSUPPORTED_ABI,
        SYSTEM_UNAVAILABLE
    }

    private RecognitionFailureMapper() {}

    static RecognitionRoute.FailureClass fromAndroidSystem(
            int errorCode,
            String internalMessage) {
        if (errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            return SystemSpeechRecognizer.MICROPHONE_ACCESS_BLOCKED.equals(internalMessage)
                    ? RecognitionRoute.FailureClass.OEM_MIC_BLOCKED
                    : RecognitionRoute.FailureClass.PERMISSION_DENIED;
        }
        if (errorCode == SpeechRecognizer.ERROR_AUDIO) {
            return RecognitionRoute.FailureClass.AUDIO_ERROR;
        }
        if (errorCode == SpeechRecognizer.ERROR_NETWORK) {
            return RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE;
        }
        if (errorCode == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
            return RecognitionRoute.FailureClass.NETWORK_TIMEOUT;
        }
        if (errorCode == SpeechRecognizer.ERROR_NO_MATCH) {
            return RecognitionRoute.FailureClass.NO_MATCH;
        }
        if (errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            return RecognitionRoute.FailureClass.RECOGNIZER_BUSY;
        }
        if (errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            return RecognitionRoute.FailureClass.SPEECH_TIMEOUT;
        }
        if (errorCode == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) {
            return RecognitionRoute.FailureClass.RATE_LIMITED;
        }
        if (errorCode == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
            return RecognitionRoute.FailureClass.UNSUPPORTED_LANGUAGE;
        }
        if (errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
            return RecognitionRoute.FailureClass.MODEL_MISSING;
        }
        if (errorCode == SpeechRecognizer.ERROR_SERVER
                || errorCode == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
            return RecognitionRoute.FailureClass.SERVER_ERROR;
        }
        if (errorCode == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT
                || errorCode == SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS) {
            return RecognitionRoute.FailureClass.UNAVAILABLE;
        }
        return RecognitionRoute.FailureClass.INTERNAL_ERROR;
    }

    static RecognitionRoute.FailureClass fromUpload(Throwable error) {
        if (error instanceof OpenAiCompatibleClient.RequestException requestFailure) {
            return fromUploadFailure(requestFailure.failure());
        }
        if (error instanceof CancellationException) {
            return RecognitionRoute.FailureClass.CANCELLED;
        }
        if (error instanceof SocketTimeoutException) {
            return RecognitionRoute.FailureClass.NETWORK_TIMEOUT;
        }
        if (error instanceof UnknownHostException
                || error instanceof ConnectException
                || error instanceof NoRouteToHostException) {
            return RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE;
        }
        if (error instanceof OpenAiCompatibleUploadProvider.CredentialUnavailableException) {
            return RecognitionRoute.FailureClass.AUTHENTICATION;
        }
        if (error instanceof IllegalArgumentException) {
            return RecognitionRoute.FailureClass.PROTOCOL_ERROR;
        }
        if (error instanceof IOException) {
            return RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE;
        }
        return RecognitionRoute.FailureClass.INTERNAL_ERROR;
    }

    static RecognitionRoute.FailureClass fromUploadFailure(
            OpenAiCompatibleClient.RequestFailure failure) {
        return switch (Objects.requireNonNull(failure, "failure")) {
            case AUTHENTICATION -> RecognitionRoute.FailureClass.AUTHENTICATION;
            case QUOTA_EXCEEDED -> RecognitionRoute.FailureClass.QUOTA_EXCEEDED;
            case RATE_LIMITED -> RecognitionRoute.FailureClass.RATE_LIMITED;
            case NETWORK_TIMEOUT -> RecognitionRoute.FailureClass.NETWORK_TIMEOUT;
            case REQUEST_TOO_LARGE -> RecognitionRoute.FailureClass.AUDIO_ERROR;
            case SERVER_ERROR -> RecognitionRoute.FailureClass.SERVER_ERROR;
            case NO_RESULT -> RecognitionRoute.FailureClass.NO_MATCH;
            case REDIRECT_REJECTED, RESPONSE_TOO_LARGE, PROTOCOL_ERROR ->
                    RecognitionRoute.FailureClass.PROTOCOL_ERROR;
        };
    }

    static RecognitionRoute.FailureClass fromLocalAvailability(LocalAvailability availability) {
        return switch (Objects.requireNonNull(availability, "availability")) {
            case READY -> throw new IllegalArgumentException("ready is not a failure");
            case MODEL_MISSING -> RecognitionRoute.FailureClass.MODEL_MISSING;
            case MODEL_CORRUPT -> RecognitionRoute.FailureClass.PROTOCOL_ERROR;
            case LOW_MEMORY, UNSUPPORTED_ABI, SYSTEM_UNAVAILABLE ->
                    RecognitionRoute.FailureClass.UNAVAILABLE;
        };
    }

    static RecognitionRoute.FailureClass fromLocalRuntime(
            LocalAvailability availability,
            Throwable error) {
        LocalAvailability observed = Objects.requireNonNull(availability, "availability");
        if (error instanceof CancellationException) {
            return RecognitionRoute.FailureClass.CANCELLED;
        }
        if (observed != LocalAvailability.READY) {
            return fromLocalAvailability(observed);
        }
        if (error instanceof IllegalArgumentException) {
            return RecognitionRoute.FailureClass.AUDIO_ERROR;
        }
        return RecognitionRoute.FailureClass.INTERNAL_ERROR;
    }

    static RecognitionRoute.FailureClass fromLegacyAndroidError(int errorCode) {
        return fromAndroidSystem(errorCode, "");
    }

    static RecognitionRoute.FailureClass fromLegacyPipelineMessage(String rawMessage) {
        String lower = rawMessage == null ? "" : rawMessage.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "cancel")) {
            return RecognitionRoute.FailureClass.CANCELLED;
        }
        if (containsAny(lower, "target changed", "input field changed", "editor changed")) {
            return RecognitionRoute.FailureClass.TARGET_CHANGED;
        }
        if (containsAny(lower, "microphone access is blocked", "oem mic blocked")) {
            return RecognitionRoute.FailureClass.OEM_MIC_BLOCKED;
        }
        if (containsAny(lower, "permission", "not allowed")) {
            return RecognitionRoute.FailureClass.PERMISSION_DENIED;
        }
        if (containsAny(lower, "unsupported language", "language not supported")) {
            return RecognitionRoute.FailureClass.UNSUPPORTED_LANGUAGE;
        }
        if (containsAny(lower, "model missing", "model is missing", "model not installed",
                "language unavailable")) {
            return RecognitionRoute.FailureClass.MODEL_MISSING;
        }
        if (containsAny(lower, "authentication", "unauthorized", "api key", "credential")) {
            return RecognitionRoute.FailureClass.AUTHENTICATION;
        }
        if (containsAny(lower, "quota")) {
            return RecognitionRoute.FailureClass.QUOTA_EXCEEDED;
        }
        if (containsAny(lower, "rate limit", "too many requests")) {
            return RecognitionRoute.FailureClass.RATE_LIMITED;
        }
        if (containsAny(lower, "speech timeout", "no speech was detected")) {
            return RecognitionRoute.FailureClass.SPEECH_TIMEOUT;
        }
        if (containsAny(lower, "network timeout", "timed out", "timeout")) {
            return RecognitionRoute.FailureClass.NETWORK_TIMEOUT;
        }
        if (containsAny(lower, "network", "dns", "unable to connect", "connection failed")) {
            return RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE;
        }
        if (containsAny(lower, "recognizer is busy", "recognition session is already active",
                "another speech recognition session")) {
            return RecognitionRoute.FailureClass.RECOGNIZER_BUSY;
        }
        if (containsAny(lower, "no match", "no speech", "too short", "no text")) {
            return RecognitionRoute.FailureClass.NO_MATCH;
        }
        if (containsAny(lower, "microphone", "audio", "recording")) {
            return RecognitionRoute.FailureClass.AUDIO_ERROR;
        }
        if (containsAny(lower, "redirect", "protocol", "invalid response",
                "unexpected provider response", "malformed", "response too large")) {
            return RecognitionRoute.FailureClass.PROTOCOL_ERROR;
        }
        if (containsAny(lower, "server", "service error")) {
            return RecognitionRoute.FailureClass.SERVER_ERROR;
        }
        if (containsAny(lower, "unavailable", "not available", "unsupported backend",
                "endpoint is not configured")) {
            return RecognitionRoute.FailureClass.UNAVAILABLE;
        }
        return RecognitionRoute.FailureClass.INTERNAL_ERROR;
    }

    static int toAndroidErrorCode(RecognitionRoute.FailureClass failureClass) {
        return switch (Objects.requireNonNull(failureClass, "failureClass")) {
            case PERMISSION_DENIED, OEM_MIC_BLOCKED ->
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
            case AUDIO_ERROR -> SpeechRecognizer.ERROR_AUDIO;
            case NETWORK_UNAVAILABLE -> SpeechRecognizer.ERROR_NETWORK;
            case NETWORK_TIMEOUT -> SpeechRecognizer.ERROR_NETWORK_TIMEOUT;
            case RATE_LIMITED -> SpeechRecognizer.ERROR_TOO_MANY_REQUESTS;
            case RECOGNIZER_BUSY -> SpeechRecognizer.ERROR_RECOGNIZER_BUSY;
            case NO_MATCH -> SpeechRecognizer.ERROR_NO_MATCH;
            case SPEECH_TIMEOUT -> SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            case UNSUPPORTED_LANGUAGE -> SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED;
            case MODEL_MISSING -> SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE;
            case AUTHENTICATION, QUOTA_EXCEEDED, SERVER_ERROR -> SpeechRecognizer.ERROR_SERVER;
            case UNAVAILABLE, PROTOCOL_ERROR, CANCELLED, TARGET_CHANGED, INTERNAL_ERROR ->
                    SpeechRecognizer.ERROR_CLIENT;
        };
    }

    static String stableMessage(RecognitionRoute.FailureClass failureClass) {
        return switch (Objects.requireNonNull(failureClass, "failureClass")) {
            case UNAVAILABLE -> "Speech recognition is unavailable";
            case MODEL_MISSING -> "The requested speech model is unavailable";
            case PERMISSION_DENIED -> "Microphone permission is required";
            case OEM_MIC_BLOCKED -> "Microphone access is blocked by the device";
            case AUDIO_ERROR -> "Speech audio could not be processed";
            case NETWORK_UNAVAILABLE -> "The speech service could not be reached";
            case NETWORK_TIMEOUT -> "The speech service timed out";
            case AUTHENTICATION -> "Speech service authentication failed";
            case QUOTA_EXCEEDED -> "The speech service quota was exceeded";
            case RATE_LIMITED -> "The speech service is temporarily rate limited";
            case SERVER_ERROR -> "The speech service failed";
            case PROTOCOL_ERROR -> "The speech service returned an invalid response";
            case RECOGNIZER_BUSY -> "Another speech recognition session is already active";
            case NO_MATCH -> "Speech recognition returned no text";
            case SPEECH_TIMEOUT -> "No speech was detected";
            case UNSUPPORTED_LANGUAGE -> "The requested language is not supported";
            case CANCELLED -> "Speech recognition was cancelled";
            case TARGET_CHANGED -> "The target input field changed";
            case INTERNAL_ERROR -> "Speech recognition failed";
        };
    }

    private static boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) return true;
        }
        return false;
    }
}
