package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import android.speech.SpeechRecognizer;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.net.OpenAiCompatibleClient;
import com.opentypeless.android.recognition.RecognitionFailureMapper.LocalAvailability;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.EnumSet;
import java.util.concurrent.CancellationException;

public final class RecognitionFailureMapperTest {
    @Test
    public void androidAndOemErrorsMapWithoutDependingOnRawMessages() {
        assertEquals(
                RecognitionRoute.FailureClass.OEM_MIC_BLOCKED,
                RecognitionFailureMapper.fromAndroidSystem(
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                        SystemSpeechRecognizer.MICROPHONE_ACCESS_BLOCKED));
        assertEquals(
                RecognitionRoute.FailureClass.PERMISSION_DENIED,
                RecognitionFailureMapper.fromAndroidSystem(
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                        "provider-secret"));

        Object[][] cases = {
                {SpeechRecognizer.ERROR_AUDIO, RecognitionRoute.FailureClass.AUDIO_ERROR},
                {SpeechRecognizer.ERROR_NETWORK,
                        RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE},
                {SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        RecognitionRoute.FailureClass.NETWORK_TIMEOUT},
                {SpeechRecognizer.ERROR_NO_MATCH, RecognitionRoute.FailureClass.NO_MATCH},
                {SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        RecognitionRoute.FailureClass.RECOGNIZER_BUSY},
                {SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        RecognitionRoute.FailureClass.SPEECH_TIMEOUT},
                {SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
                        RecognitionRoute.FailureClass.RATE_LIMITED},
                {SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        RecognitionRoute.FailureClass.UNSUPPORTED_LANGUAGE},
                {SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                        RecognitionRoute.FailureClass.MODEL_MISSING},
                {SpeechRecognizer.ERROR_SERVER, RecognitionRoute.FailureClass.SERVER_ERROR},
                {SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                        RecognitionRoute.FailureClass.SERVER_ERROR},
                {SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
                        RecognitionRoute.FailureClass.UNAVAILABLE},
                {SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS,
                        RecognitionRoute.FailureClass.UNAVAILABLE},
                {SpeechRecognizer.ERROR_CLIENT, RecognitionRoute.FailureClass.INTERNAL_ERROR},
                {Integer.MIN_VALUE, RecognitionRoute.FailureClass.INTERNAL_ERROR}
        };
        for (Object[] entry : cases) {
            assertEquals(
                    entry[1],
                    RecognitionFailureMapper.fromAndroidSystem((Integer) entry[0], "secret"));
        }
    }

    @Test
    public void uploadFailuresMapByClosedTypeAndNeverByThrowableMessage() {
        Object[][] requestCases = {
                {OpenAiCompatibleClient.RequestFailure.AUTHENTICATION,
                        RecognitionRoute.FailureClass.AUTHENTICATION},
                {OpenAiCompatibleClient.RequestFailure.QUOTA_EXCEEDED,
                        RecognitionRoute.FailureClass.QUOTA_EXCEEDED},
                {OpenAiCompatibleClient.RequestFailure.RATE_LIMITED,
                        RecognitionRoute.FailureClass.RATE_LIMITED},
                {OpenAiCompatibleClient.RequestFailure.NETWORK_TIMEOUT,
                        RecognitionRoute.FailureClass.NETWORK_TIMEOUT},
                {OpenAiCompatibleClient.RequestFailure.REQUEST_TOO_LARGE,
                        RecognitionRoute.FailureClass.AUDIO_ERROR},
                {OpenAiCompatibleClient.RequestFailure.SERVER_ERROR,
                        RecognitionRoute.FailureClass.SERVER_ERROR},
                {OpenAiCompatibleClient.RequestFailure.NO_RESULT,
                        RecognitionRoute.FailureClass.NO_MATCH},
                {OpenAiCompatibleClient.RequestFailure.REDIRECT_REJECTED,
                        RecognitionRoute.FailureClass.PROTOCOL_ERROR},
                {OpenAiCompatibleClient.RequestFailure.RESPONSE_TOO_LARGE,
                        RecognitionRoute.FailureClass.PROTOCOL_ERROR},
                {OpenAiCompatibleClient.RequestFailure.PROTOCOL_ERROR,
                        RecognitionRoute.FailureClass.PROTOCOL_ERROR}
        };
        for (Object[] entry : requestCases) {
            assertEquals(
                    entry[1],
                    RecognitionFailureMapper.fromUploadFailure(
                            (OpenAiCompatibleClient.RequestFailure) entry[0]));
        }

        Object[][] transportCases = {
                {new CancellationException("secret"),
                        RecognitionRoute.FailureClass.CANCELLED},
                {new SocketTimeoutException("secret"),
                        RecognitionRoute.FailureClass.NETWORK_TIMEOUT},
                {new UnknownHostException("secret"),
                        RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE},
                {new ConnectException("secret"),
                        RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE},
                {new NoRouteToHostException("secret"),
                        RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE},
                {new OpenAiCompatibleUploadProvider.CredentialUnavailableException(),
                        RecognitionRoute.FailureClass.AUTHENTICATION},
                {new IllegalArgumentException("secret"),
                        RecognitionRoute.FailureClass.PROTOCOL_ERROR},
                {new IOException("secret"),
                        RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE},
                {new Throwable("secret"), RecognitionRoute.FailureClass.INTERNAL_ERROR}
        };
        for (Object[] entry : transportCases) {
            assertEquals(
                    entry[1],
                    RecognitionFailureMapper.fromUpload((Throwable) entry[0]));
        }
    }

    @Test
    public void localAvailabilityAndRuntimeFailuresUseOneSharedMapping() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RecognitionFailureMapper.fromLocalAvailability(LocalAvailability.READY));
        assertEquals(
                RecognitionRoute.FailureClass.MODEL_MISSING,
                RecognitionFailureMapper.fromLocalAvailability(LocalAvailability.MODEL_MISSING));
        assertEquals(
                RecognitionRoute.FailureClass.PROTOCOL_ERROR,
                RecognitionFailureMapper.fromLocalAvailability(LocalAvailability.MODEL_CORRUPT));
        for (LocalAvailability unavailable : new LocalAvailability[]{
                LocalAvailability.LOW_MEMORY,
                LocalAvailability.UNSUPPORTED_ABI,
                LocalAvailability.SYSTEM_UNAVAILABLE}) {
            assertEquals(
                    RecognitionRoute.FailureClass.UNAVAILABLE,
                    RecognitionFailureMapper.fromLocalAvailability(unavailable));
        }
        assertEquals(
                RecognitionRoute.FailureClass.AUDIO_ERROR,
                RecognitionFailureMapper.fromLocalRuntime(
                        LocalAvailability.READY,
                        new IllegalArgumentException("secret")));
        assertEquals(
                RecognitionRoute.FailureClass.CANCELLED,
                RecognitionFailureMapper.fromLocalRuntime(
                        LocalAvailability.MODEL_CORRUPT,
                        new CancellationException("secret")));
        assertEquals(
                RecognitionRoute.FailureClass.MODEL_MISSING,
                RecognitionFailureMapper.fromLocalRuntime(
                        LocalAvailability.MODEL_MISSING,
                        new IllegalStateException("secret")));
        assertEquals(
                RecognitionRoute.FailureClass.INTERNAL_ERROR,
                RecognitionFailureMapper.fromLocalRuntime(
                        LocalAvailability.READY,
                        new IllegalStateException("secret")));
    }

    @Test
    public void legacyMessagesCoverTheExactStableVocabularyWithoutRetainingInput() {
        Object[][] cases = {
                {"backend unavailable", RecognitionRoute.FailureClass.UNAVAILABLE},
                {"model missing", RecognitionRoute.FailureClass.MODEL_MISSING},
                {"permission denied", RecognitionRoute.FailureClass.PERMISSION_DENIED},
                {"OEM mic blocked", RecognitionRoute.FailureClass.OEM_MIC_BLOCKED},
                {"audio capture failed", RecognitionRoute.FailureClass.AUDIO_ERROR},
                {"network connection failed",
                        RecognitionRoute.FailureClass.NETWORK_UNAVAILABLE},
                {"network timeout", RecognitionRoute.FailureClass.NETWORK_TIMEOUT},
                {"authentication failed", RecognitionRoute.FailureClass.AUTHENTICATION},
                {"quota exhausted", RecognitionRoute.FailureClass.QUOTA_EXCEEDED},
                {"rate limit", RecognitionRoute.FailureClass.RATE_LIMITED},
                {"server failed", RecognitionRoute.FailureClass.SERVER_ERROR},
                {"invalid response", RecognitionRoute.FailureClass.PROTOCOL_ERROR},
                {"recognizer is busy", RecognitionRoute.FailureClass.RECOGNIZER_BUSY},
                {"no match", RecognitionRoute.FailureClass.NO_MATCH},
                {"speech timeout", RecognitionRoute.FailureClass.SPEECH_TIMEOUT},
                {"unsupported language", RecognitionRoute.FailureClass.UNSUPPORTED_LANGUAGE},
                {"cancelled", RecognitionRoute.FailureClass.CANCELLED},
                {"target changed", RecognitionRoute.FailureClass.TARGET_CHANGED},
                {"opaque provider-secret", RecognitionRoute.FailureClass.INTERNAL_ERROR}
        };
        EnumSet<RecognitionRoute.FailureClass> observed =
                EnumSet.noneOf(RecognitionRoute.FailureClass.class);
        for (Object[] entry : cases) {
            RecognitionRoute.FailureClass expected =
                    (RecognitionRoute.FailureClass) entry[1];
            assertEquals(
                    expected,
                    RecognitionFailureMapper.fromLegacyPipelineMessage((String) entry[0]));
            observed.add(expected);
        }
        assertEquals(EnumSet.allOf(RecognitionRoute.FailureClass.class), observed);

        RecognitionFailure failure = RecognitionErrors.fromPipelineMessage(
                "Provider redirect was rejected: provider-secret");
        assertEquals(RecognitionRoute.FailureClass.PROTOCOL_ERROR, failure.failureClass());
        assertEquals(SpeechRecognizer.ERROR_CLIENT, failure.errorCode());
        assertFalse(failure.message().contains("provider-secret"));
        assertFalse(failure.toString().contains(failure.message()));
    }

    @Test
    public void legacyFailureMessagesAreBoundedWellFormedAndContentRedacted() {
        RecognitionFailure oversized = new RecognitionFailure(
                RecognitionRoute.FailureClass.SERVER_ERROR,
                "secret".repeat(100));
        assertEquals(
                RecognitionFailureMapper.stableMessage(
                        RecognitionRoute.FailureClass.SERVER_ERROR),
                oversized.message());

        RecognitionFailure malformed = new RecognitionFailure(
                RecognitionRoute.FailureClass.INTERNAL_ERROR,
                "\uD800secret");
        assertEquals(
                RecognitionFailureMapper.stableMessage(
                        RecognitionRoute.FailureClass.INTERNAL_ERROR),
                malformed.message());
        assertFalse(malformed.toString().contains("secret"));
    }
}
