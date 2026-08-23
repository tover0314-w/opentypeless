package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.speech.core.SessionId;

import org.junit.Test;

import java.io.Serializable;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;

public final class RecognitionEventTest {
    @Test
    public void sealedVocabularyAndRecordComponentsAreExact() {
        assertEquals(
                Set.of(
                        RecognitionEvent.Preparing.class,
                        RecognitionEvent.Ready.class,
                        RecognitionEvent.SpeechStarted.class,
                        RecognitionEvent.Partial.class,
                        RecognitionEvent.Endpoint.class,
                        RecognitionEvent.Final.class,
                        RecognitionEvent.Failure.class,
                        RecognitionEvent.Cancelled.class),
                Set.of(RecognitionEvent.class.getPermittedSubclasses()));
        assertComponents(RecognitionEvent.Preparing.class, "sessionId", "sequence");
        assertComponents(RecognitionEvent.Ready.class, "sessionId", "sequence");
        assertComponents(RecognitionEvent.SpeechStarted.class, "sessionId", "sequence");
        assertComponents(
                RecognitionEvent.Partial.class,
                "sessionId", "sequence", "text", "stablePrefixLength", "revisionOf");
        assertComponents(RecognitionEvent.Endpoint.class, "sessionId", "sequence");
        assertComponents(
                RecognitionEvent.Final.class,
                "sessionId", "sequence", "text", "metadata");
        assertComponents(
                RecognitionEvent.Failure.class,
                "sessionId", "sequence", "failureClass");
        assertComponents(RecognitionEvent.Cancelled.class, "sessionId", "sequence");
    }

    @Test
    public void everyVariantRequiresOneSessionAndPositiveSequence() {
        SessionId session = SessionId.of("recognition-session");
        assertThrows(
                NullPointerException.class,
                () -> new RecognitionEvent.Preparing(null, 1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionEvent.Ready(session, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionEvent.SpeechStarted(session, -1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionEvent.Endpoint(session, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionEvent.Cancelled(session, 0L));

        assertFalse(new RecognitionEvent.Preparing(session, 1L).terminal());
        assertFalse(new RecognitionEvent.Endpoint(session, Long.MAX_VALUE).terminal());
        assertTrue(new RecognitionEvent.Cancelled(session, Long.MAX_VALUE).terminal());
    }

    @Test
    public void partialTextPrefixAndRevisionAreBoundedAndUtf16Safe() {
        SessionId session = SessionId.of("partial-session");
        RecognitionEvent.Partial empty =
                new RecognitionEvent.Partial(session, 1L, "", 0, null);
        assertEquals("", empty.text());
        RecognitionEvent.Partial emoji =
                new RecognitionEvent.Partial(session, 3L, "A😀B", 3, 2L);
        assertEquals(Integer.valueOf(3), emoji.stablePrefixLength());
        assertEquals(Long.valueOf(2L), emoji.revisionOf());

        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionEvent.Partial(session, 2L, "😀", 1, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionEvent.Partial(session, 2L, "text", 5, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionEvent.Partial(session, 2L, "text", 0, 2L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionEvent.Partial(session, 2L, "text", 0, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionEvent.Partial(session, 2L, "\ud800", null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionEvent.Partial(
                        session,
                        2L,
                        "😀".repeat(RecognitionEvent.MAX_TEXT_CODE_POINTS + 1),
                        null,
                        null));
        assertEquals(
                RecognitionEvent.MAX_TEXT_CODE_POINTS,
                new RecognitionEvent.Partial(
                                session,
                                2L,
                                "😀".repeat(RecognitionEvent.MAX_TEXT_CODE_POINTS),
                                null,
                                null)
                        .text()
                        .codePointCount(0, RecognitionEvent.MAX_TEXT_CODE_POINTS * 2));
    }

    @Test
    public void finalFailureAndMetadataRejectAmbiguousTerminalPayloads() {
        SessionId session = SessionId.of("terminal-session");
        RecognitionMetadata metadata = new RecognitionMetadata("zh-hans-cn", 0.75f, 1_000L);
        RecognitionEvent.Final result =
                new RecognitionEvent.Final(session, 7L, "最终文本", metadata);
        assertEquals("zh-Hans-CN", result.metadata().detectedLanguageTag());
        assertTrue(result.terminal());

        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionEvent.Final(session, 7L, "   ", metadata));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionEvent.Final(session, 7L, "\udc00", metadata));
        assertThrows(
                NullPointerException.class,
                () -> new RecognitionEvent.Final(session, 7L, "text", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionEvent.Failure(
                        session, 8L, RecognitionRoute.FailureClass.CANCELLED));
        assertThrows(
                NullPointerException.class,
                () -> new RecognitionEvent.Failure(session, 8L, null));
        assertTrue(new RecognitionEvent.Failure(
                        session, 8L, RecognitionRoute.FailureClass.NO_MATCH)
                .terminal());
    }

    @Test
    public void metadataBoundsAndAllDiagnosticsAreContentFree() {
        assertEquals(RecognitionMetadata.empty(), new RecognitionMetadata(null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionMetadata("bad_tag", null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionMetadata("a".repeat(64), null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionMetadata(null, Float.NaN, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionMetadata(null, 1.01f, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionMetadata(null, null, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RecognitionMetadata(
                        null, null, ProviderCapabilities.APP_CAPTURE_LIMIT_MS + 1L));

        String secretSession = "secret-session-id";
        String secretText = "secret transcript";
        String secretLanguage = "x-private";
        RecognitionEvent.Final event = new RecognitionEvent.Final(
                SessionId.of(secretSession),
                9L,
                secretText,
                new RecognitionMetadata(secretLanguage, 0.5f, 100L));
        assertFalse(event.toString().contains(secretSession));
        assertFalse(event.toString().contains(secretText));
        assertFalse(event.toString().contains(secretLanguage));
        assertFalse(event.metadata().toString().contains(secretLanguage));
        assertTrue(event.toString().contains("content=<redacted>"));

        assertFalse(Serializable.class.isAssignableFrom(RecognitionEvent.class));
        assertFalse(Serializable.class.isAssignableFrom(RecognitionMetadata.class));
        assertTrue(Arrays.stream(RecognitionEvent.class.getPermittedSubclasses())
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .noneMatch(field -> field.getType().getName().startsWith("android.")));
    }

    private static void assertComponents(Class<?> type, String... expected) {
        assertTrue(type.isRecord());
        assertEquals(
                Arrays.asList(expected),
                Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList());
    }
}
