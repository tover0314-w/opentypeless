package com.opentypeless.android.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

public final class CompositionStateTest {
    @Test
    public void sealedModelContainsExactlyTheNineDomainStates() {
        assertTrue(CompositionState.class.isSealed());
        assertEquals(
                Set.of(
                        CompositionState.Idle.class,
                        CompositionState.LatinComposing.class,
                        CompositionState.RimeComposing.class,
                        CompositionState.VoicePreparing.class,
                        CompositionState.VoiceListening.class,
                        CompositionState.VoicePartial.class,
                        CompositionState.VoiceFinalizing.class,
                        CompositionState.ActionRunning.class,
                        CompositionState.ActionPreview.class),
                Set.of(CompositionState.class.getPermittedSubclasses()));

        for (Class<?> variant : CompositionState.class.getPermittedSubclasses()) {
            assertTrue(variant.isRecord());
            assertTrue(Modifier.isFinal(variant.getModifiers()));
            assertFalse(Serializable.class.isAssignableFrom(variant));
            assertTrue(Arrays.stream(variant.getAnnotations())
                    .noneMatch(annotation -> annotation.annotationType().getName()
                            .startsWith("kotlinx.serialization")));
        }
    }

    @Test
    public void variantsExposeOnlyTheirExactPrimitiveComponents() {
        Map<Class<?>, List<String>> expectedComponents = Map.of(
                CompositionState.Idle.class, List.of(),
                CompositionState.LatinComposing.class,
                List.of("coordinationGeneration", "revision"),
                CompositionState.RimeComposing.class,
                List.of("coordinationGeneration", "revision"),
                CompositionState.VoicePreparing.class,
                List.of("coordinationGeneration"),
                CompositionState.VoiceListening.class,
                List.of("coordinationGeneration"),
                CompositionState.VoicePartial.class,
                List.of("coordinationGeneration", "revision"),
                CompositionState.VoiceFinalizing.class,
                List.of("coordinationGeneration", "latestRevision"),
                CompositionState.ActionRunning.class,
                List.of("coordinationGeneration"),
                CompositionState.ActionPreview.class,
                List.of("coordinationGeneration"));

        for (Class<?> variant : CompositionState.class.getPermittedSubclasses()) {
            RecordComponent[] components = variant.getRecordComponents();
            assertEquals(
                    expectedComponents.get(variant),
                    Arrays.stream(components)
                            .map(RecordComponent::getName)
                            .collect(Collectors.toList()));
            for (RecordComponent component : components) {
                assertEquals(long.class, component.getType());
            }
        }
    }

    @Test
    public void eachStateFixesItsOnlyOwnerAndGeneration() {
        List<CompositionState> states = List.of(
                new CompositionState.Idle(),
                new CompositionState.LatinComposing(1L, 1L),
                new CompositionState.RimeComposing(2L, 3L),
                new CompositionState.VoicePreparing(3L),
                new CompositionState.VoiceListening(4L),
                new CompositionState.VoicePartial(5L, 7L),
                new CompositionState.VoiceFinalizing(6L, 0L),
                new CompositionState.ActionRunning(7L),
                new CompositionState.ActionPreview(8L));
        List<CompositionOwner> expectedOwners = List.of(
                CompositionOwner.NONE,
                CompositionOwner.LATIN,
                CompositionOwner.RIME,
                CompositionOwner.VOICE,
                CompositionOwner.VOICE,
                CompositionOwner.VOICE,
                CompositionOwner.VOICE,
                CompositionOwner.NONE,
                CompositionOwner.ACTION_PREVIEW);

        for (int index = 0; index < states.size(); index++) {
            CompositionState state = states.get(index);
            assertEquals(expectedOwners.get(index), state.owner());
            assertEquals(index == 0 ? 0L : index, state.coordinationGeneration());
        }
    }

    @Test
    public void everyActiveStateRequiresAFullRangePositiveGeneration() {
        for (long generation : new long[]{1L, Long.MAX_VALUE}) {
            assertEquals(generation,
                    new CompositionState.LatinComposing(generation, 1L)
                            .coordinationGeneration());
            assertEquals(generation,
                    new CompositionState.RimeComposing(generation, 1L)
                            .coordinationGeneration());
            assertEquals(generation,
                    new CompositionState.VoicePreparing(generation).coordinationGeneration());
            assertEquals(generation,
                    new CompositionState.VoiceListening(generation).coordinationGeneration());
            assertEquals(generation,
                    new CompositionState.VoicePartial(generation, 1L)
                            .coordinationGeneration());
            assertEquals(generation,
                    new CompositionState.VoiceFinalizing(generation, 0L)
                            .coordinationGeneration());
            assertEquals(generation,
                    new CompositionState.ActionRunning(generation).coordinationGeneration());
            assertEquals(generation,
                    new CompositionState.ActionPreview(generation).coordinationGeneration());
        }

        for (long invalid : new long[]{0L, -1L, Long.MIN_VALUE}) {
            assertIllegal(() -> new CompositionState.LatinComposing(invalid, 1L));
            assertIllegal(() -> new CompositionState.RimeComposing(invalid, 1L));
            assertIllegal(() -> new CompositionState.VoicePreparing(invalid));
            assertIllegal(() -> new CompositionState.VoiceListening(invalid));
            assertIllegal(() -> new CompositionState.VoicePartial(invalid, 1L));
            assertIllegal(() -> new CompositionState.VoiceFinalizing(invalid, 0L));
            assertIllegal(() -> new CompositionState.ActionRunning(invalid));
            assertIllegal(() -> new CompositionState.ActionPreview(invalid));
        }
    }

    @Test
    public void composingAndPartialRevisionsAreStrictlyPositive() {
        for (long revision : new long[]{1L, Long.MAX_VALUE}) {
            assertEquals(revision,
                    new CompositionState.LatinComposing(1L, revision).revision());
            assertEquals(revision,
                    new CompositionState.RimeComposing(1L, revision).revision());
            assertEquals(revision,
                    new CompositionState.VoicePartial(1L, revision).revision());
        }

        for (long invalid : new long[]{0L, -1L, Long.MIN_VALUE}) {
            assertIllegal(() -> new CompositionState.LatinComposing(1L, invalid));
            assertIllegal(() -> new CompositionState.RimeComposing(1L, invalid));
            assertIllegal(() -> new CompositionState.VoicePartial(1L, invalid));
        }
    }

    @Test
    public void finalizingTracksNoPartialOrTheLatestPositiveRevision() {
        assertEquals(0L, new CompositionState.VoiceFinalizing(1L, 0L).latestRevision());
        assertEquals(1L, new CompositionState.VoiceFinalizing(1L, 1L).latestRevision());
        assertEquals(Long.MAX_VALUE,
                new CompositionState.VoiceFinalizing(1L, Long.MAX_VALUE).latestRevision());

        assertIllegal(() -> new CompositionState.VoiceFinalizing(1L, -1L));
        assertIllegal(() -> new CompositionState.VoiceFinalizing(1L, Long.MIN_VALUE));
    }

    @Test
    public void statesHaveImmutableValueSemanticsWithoutPayloadText() {
        assertEquals(new CompositionState.Idle(), new CompositionState.Idle());
        assertEquals(
                new CompositionState.VoicePartial(3L, 5L),
                new CompositionState.VoicePartial(3L, 5L));
        assertEquals(
                new CompositionState.VoicePartial(3L, 5L).hashCode(),
                new CompositionState.VoicePartial(3L, 5L).hashCode());
        assertNotEquals(
                new CompositionState.VoicePartial(3L, 5L),
                new CompositionState.VoicePartial(4L, 5L));
        assertNotEquals(
                new CompositionState.VoicePartial(3L, 5L),
                new CompositionState.VoicePartial(3L, 6L));
    }

    private static void assertIllegal(Runnable runnable) {
        try {
            runnable.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
