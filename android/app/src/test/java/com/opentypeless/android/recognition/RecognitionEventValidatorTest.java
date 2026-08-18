package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.speech.core.SessionId;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class RecognitionEventValidatorTest {
    @Test
    public void monotonicLifecycleAcceptsOneTerminalAndDropsEverythingAfterIt() {
        SessionId session = SessionId.of("lifecycle");
        RecognitionEventValidator validator = new RecognitionEventValidator(session);
        List<RecognitionEvent> accepted = List.of(
                new RecognitionEvent.Preparing(session, 2L),
                new RecognitionEvent.Ready(session, 3L),
                new RecognitionEvent.SpeechStarted(session, 5L),
                new RecognitionEvent.Partial(session, 8L, "part", 0, null),
                new RecognitionEvent.Endpoint(session, 13L),
                new RecognitionEvent.Final(
                        session, 21L, "final", RecognitionMetadata.empty()));
        for (RecognitionEvent event : accepted) {
            assertEquals(
                    RecognitionEventValidator.Disposition.ACCEPTED,
                    validator.accept(event));
        }
        assertEquals(
                RecognitionEventValidator.Disposition.DROPPED_AFTER_TERMINAL,
                validator.accept(new RecognitionEvent.Partial(
                        session, 22L, "late", null, 8L)));
        assertEquals(
                RecognitionEventValidator.Disposition.DROPPED_AFTER_TERMINAL,
                validator.accept(new RecognitionEvent.Cancelled(session, 23L)));
        assertFalse(validator.toString().contains(session.value()));
        assertTrue(validator.toString().contains("terminal=true"));
    }

    @Test
    public void foreignAndNonMonotonicEventsDoNotPoisonTheExpectedSession() {
        SessionId session = SessionId.of("expected");
        RecognitionEventValidator validator = new RecognitionEventValidator(session);
        assertEquals(
                RecognitionEventValidator.Disposition.REJECTED_SESSION,
                validator.accept(new RecognitionEvent.Preparing(SessionId.of("foreign"), 100L)));
        assertEquals(
                RecognitionEventValidator.Disposition.ACCEPTED,
                validator.accept(new RecognitionEvent.Preparing(session, 10L)));
        assertEquals(
                RecognitionEventValidator.Disposition.REJECTED_SEQUENCE,
                validator.accept(new RecognitionEvent.Ready(session, 10L)));
        assertEquals(
                RecognitionEventValidator.Disposition.REJECTED_SEQUENCE,
                validator.accept(new RecognitionEvent.Ready(session, 9L)));
        assertEquals(
                RecognitionEventValidator.Disposition.ACCEPTED,
                validator.accept(new RecognitionEvent.Ready(session, 11L)));
    }

    @Test
    public void partialRevisionMustReferenceTheLastAcceptedPartial() {
        SessionId session = SessionId.of("revisions");
        RecognitionEventValidator validator = new RecognitionEventValidator(session);
        assertEquals(
                RecognitionEventValidator.Disposition.REJECTED_REVISION,
                validator.accept(new RecognitionEvent.Partial(session, 3L, "bad", null, 2L)));
        assertEquals(
                RecognitionEventValidator.Disposition.ACCEPTED,
                validator.accept(new RecognitionEvent.Partial(session, 4L, "one", 0, null)));
        assertEquals(
                RecognitionEventValidator.Disposition.REJECTED_REVISION,
                validator.accept(new RecognitionEvent.Partial(session, 7L, "two", 1, 3L)));
        assertEquals(
                RecognitionEventValidator.Disposition.ACCEPTED,
                validator.accept(new RecognitionEvent.Partial(session, 8L, "two", 1, 4L)));
        assertEquals(
                RecognitionEventValidator.Disposition.ACCEPTED,
                validator.accept(new RecognitionEvent.Endpoint(session, 9L)));
        assertEquals(
                RecognitionEventValidator.Disposition.ACCEPTED,
                validator.accept(new RecognitionEvent.Partial(session, 10L, "three", 2, 8L)));
    }

    @Test
    public void everyTerminalVariantClosesTheStreamWithoutSequenceOverflow() {
        SessionId finalSession = SessionId.of("final-max");
        RecognitionEventValidator finalValidator = new RecognitionEventValidator(finalSession);
        assertEquals(
                RecognitionEventValidator.Disposition.ACCEPTED,
                finalValidator.accept(new RecognitionEvent.Final(
                        finalSession,
                        Long.MAX_VALUE,
                        "final",
                        RecognitionMetadata.empty())));
        assertEquals(
                RecognitionEventValidator.Disposition.DROPPED_AFTER_TERMINAL,
                finalValidator.accept(new RecognitionEvent.Final(
                        finalSession,
                        Long.MAX_VALUE,
                        "again",
                        RecognitionMetadata.empty())));

        SessionId failureSession = SessionId.of("failure");
        RecognitionEventValidator failureValidator = new RecognitionEventValidator(failureSession);
        assertEquals(
                RecognitionEventValidator.Disposition.ACCEPTED,
                failureValidator.accept(new RecognitionEvent.Failure(
                        failureSession, 1L, RecognitionRoute.FailureClass.NETWORK_TIMEOUT)));
        assertEquals(
                RecognitionEventValidator.Disposition.DROPPED_AFTER_TERMINAL,
                failureValidator.accept(new RecognitionEvent.Ready(failureSession, 2L)));

        SessionId cancelledSession = SessionId.of("cancelled");
        RecognitionEventValidator cancelledValidator =
                new RecognitionEventValidator(cancelledSession);
        assertEquals(
                RecognitionEventValidator.Disposition.ACCEPTED,
                cancelledValidator.accept(new RecognitionEvent.Cancelled(cancelledSession, 1L)));
        assertEquals(
                RecognitionEventValidator.Disposition.DROPPED_AFTER_TERMINAL,
                cancelledValidator.accept(new RecognitionEvent.Ready(cancelledSession, 2L)));
    }

    @Test
    public void concurrentDuplicateSequenceHasExactlyOneLinearizedWinner() throws Exception {
        SessionId session = SessionId.of("concurrent");
        RecognitionEventValidator validator = new RecognitionEventValidator(session);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Runnable submit = () -> {
            ready.countDown();
            try {
                start.await();
                RecognitionEventValidator.Disposition result =
                        validator.accept(new RecognitionEvent.Ready(session, 1L));
                if (result == RecognitionEventValidator.Disposition.ACCEPTED) {
                    accepted.incrementAndGet();
                } else if (result == RecognitionEventValidator.Disposition.REJECTED_SEQUENCE) {
                    rejected.incrementAndGet();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
        };
        Thread first = new Thread(submit, "recognition-event-first");
        Thread second = new Thread(submit, "recognition-event-second");
        first.start();
        second.start();
        ready.await();
        start.countDown();
        first.join();
        second.join();
        assertEquals(1, accepted.get());
        assertEquals(1, rejected.get());
    }
}
