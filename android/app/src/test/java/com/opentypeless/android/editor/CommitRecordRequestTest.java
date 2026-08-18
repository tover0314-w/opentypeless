package com.opentypeless.android.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Set;
import org.junit.Test;

public final class CommitRecordRequestTest {
    @Test
    public void sealedRequestLetsProducerSupplyOnlyExplicitRawPresence() {
        assertTrue(CommitRecordRequest.class.isSealed());
        assertEquals(
                Set.of(
                        CommitRecordRequest.None.class,
                        CommitRecordRequest.Requested.class),
                Set.of(CommitRecordRequest.class.getPermittedSubclasses()));

        CommitRecordRequest.None none = new CommitRecordRequest.None();
        assertEquals(0, none.getClass().getRecordComponents().length);

        CommitRecord.RawTranscript.Absent absent =
                new CommitRecord.RawTranscript.Absent();
        CommitRecordRequest.Requested withoutRaw =
                new CommitRecordRequest.Requested(absent);
        assertEquals(absent, withoutRaw.rawTranscript());

        CommitRecord.RawTranscript.Present present =
                new CommitRecord.RawTranscript.Present("private raw");
        CommitRecordRequest.Requested withRaw =
                new CommitRecordRequest.Requested(present);
        assertEquals(present, withRaw.rawTranscript());

        RecordComponent[] components =
                CommitRecordRequest.Requested.class.getRecordComponents();
        assertEquals(1, components.length);
        assertEquals("rawTranscript", components[0].getName());
        assertEquals(CommitRecord.RawTranscript.class, components[0].getType());
    }

    @Test
    public void requestVariantsAreFinalImmutableNonSerializableAndNullSafe() {
        for (Class<?> variant : CommitRecordRequest.class.getPermittedSubclasses()) {
            assertTrue(variant.isRecord());
            assertTrue(Modifier.isFinal(variant.getModifiers()));
            assertFalse(Serializable.class.isAssignableFrom(variant));
            for (RecordComponent component : variant.getRecordComponents()) {
                assertFalse(component.getType() == String.class);
                assertFalse(component.getType() == OperationSource.class);
                assertFalse(component.getType() == EditorSessionSnapshot.class);
                assertFalse(component.getName().toLowerCase().contains("id"));
                assertFalse(component.getName().toLowerCase().contains("insert"));
            }
        }
        assertFalse(Serializable.class.isAssignableFrom(CommitRecordRequest.class));
        assertNullRejected(() -> new CommitRecordRequest.Requested(null));
    }

    @Test
    public void requestedDiagnosticDoesNotExposeRawText() {
        String privateRaw = "request-private-raw";
        CommitRecordRequest.Requested request = new CommitRecordRequest.Requested(
                new CommitRecord.RawTranscript.Present(privateRaw));
        assertFalse(request.toString().contains(privateRaw));
        assertTrue(request.toString().contains("PRESENT"));
    }

    private static void assertNullRejected(Runnable action) {
        try {
            action.run();
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // Expected.
        }
    }
}
