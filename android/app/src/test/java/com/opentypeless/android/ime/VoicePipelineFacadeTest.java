package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class VoicePipelineFacadeTest {
    @Test
    public void facadeOwnsOnlyOnePackageConfinedRuntime() throws Exception {
        assertTrue(Modifier.isPublic(VoicePipeline.class.getModifiers()));
        assertTrue(Modifier.isFinal(VoicePipeline.class.getModifiers()));
        assertFalse(Modifier.isPublic(VoicePipelineRuntime.class.getModifiers()));
        assertTrue(Modifier.isFinal(VoicePipelineRuntime.class.getModifiers()));
        Arrays.stream(VoicePipelineRuntime.class.getDeclaredMethods()).forEach(method -> {
            assertFalse(Modifier.isPublic(method.getModifiers()));
            assertFalse(Modifier.isProtected(method.getModifiers()));
        });

        Field[] fields = VoicePipeline.class.getDeclaredFields();
        assertEquals(1, fields.length);
        assertEquals("runtime", fields[0].getName());
        assertEquals(VoicePipelineRuntime.class, fields[0].getType());
        assertTrue(Modifier.isPrivate(fields[0].getModifiers()));
        assertTrue(Modifier.isFinal(fields[0].getModifiers()));
        assertFalse(Modifier.isStatic(fields[0].getModifiers()));

        assertEquals(Context.class, VoicePipeline.class.getConstructor(Context.class)
                .getParameterTypes()[0]);
        assertThrows(NullPointerException.class, () -> new VoicePipeline(null));
    }

    @Test
    public void publicInstanceSurfaceRemainsExactAndLifecycleOnly() {
        Set<String> methods = Arrays.stream(VoicePipeline.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "setRecordingContext",
                "start",
                "prewarmLocalOffline",
                "stopRecording",
                "cancel",
                "discard",
                "hasRecoverableAudio",
                "acknowledgeRecovery",
                "recover",
                "state",
                "shutdown"), methods);
    }

    @Test
    public void compatibilityHelpersRemainPackageStaticAndExact() {
        Set<String> helpers = Arrays.stream(VoicePipeline.class.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "shouldUseSpeechCoreV2",
                "shouldFallbackToLocal",
                "shouldRecoverVisiblePartial",
                "joinTranscriptSegments",
                "reconcileSystemFinal",
                "limitCodePoints",
                "parseSpeechCoreRecoveryId",
                "clearCancelledRun",
                "aiCandidateDisposition"), helpers);
        Arrays.stream(VoicePipeline.class.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .forEach(method -> assertFalse(Modifier.isPublic(method.getModifiers())));
    }
}
