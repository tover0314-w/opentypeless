package com.opentypeless.android.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.Test;

public final class CompositionCoordinatorTest {
    @Test
    public void acquisitionVocabularyIsClosedAndValidatesRevision() {
        assertTrue(CompositionCoordinator.Acquisition.class.isSealed());
        assertEquals(
                Set.of(
                        CompositionCoordinator.Acquisition.Latin.class,
                        CompositionCoordinator.Acquisition.Rime.class,
                        CompositionCoordinator.Acquisition.Voice.class,
                        CompositionCoordinator.Acquisition.Action.class),
                Set.of(CompositionCoordinator.Acquisition.class.getPermittedSubclasses()));
        assertEquals(Long.MAX_VALUE,
                new CompositionCoordinator.Acquisition.Latin(Long.MAX_VALUE).revision());
        for (long invalid : new long[]{0L, -1L, Long.MIN_VALUE}) {
            assertIllegal(() -> new CompositionCoordinator.Acquisition.Latin(invalid));
            assertIllegal(() -> new CompositionCoordinator.Acquisition.Rime(invalid));
        }
    }

    @Test
    public void nullAndSeedBoundariesFailBeforeAnyStateChange() {
        assertIllegal(() -> new CompositionCoordinator(-1L, 0L));
        assertIllegal(() -> new CompositionCoordinator(0L, -1L));

        CompositionCoordinator coordinator = new CompositionCoordinator();
        CompositionCoordinator.Observation idle = coordinator.observe();
        assertNullRejected(() -> coordinator.acquire(
                null, new CompositionCoordinator.Acquisition.Voice()));
        assertNullRejected(() -> coordinator.acquire(idle, null));
        assertNullRejected(() -> coordinator.update(null, 1L));
        assertNullRejected(() -> coordinator.voiceReady(null));
        assertNullRejected(() -> coordinator.voicePartial(null, 1L));
        assertNullRejected(() -> coordinator.beginVoiceFinalizing(null));
        assertNullRejected(() -> coordinator.showActionPreview(null));
        assertNullRejected(() -> coordinator.commit(null, 1L));
        assertNullRejected(() -> coordinator.complete(null));
        assertNullRejected(() -> coordinator.cancel(null));
        assertNullRejected(() -> coordinator.beginPreempt(
                null,
                CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT,
                new CompositionCoordinator.Acquisition.Voice()));
        assertNullRejected(() -> coordinator.beginPreempt(
                idle, null, new CompositionCoordinator.Acquisition.Voice()));
        assertNullRejected(() -> coordinator.beginPreempt(
                idle, CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT, null));
        assertNullRejected(() -> coordinator.finishPreempt(
                null, CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED));
        assertSame(idle, coordinator.observe());
    }

    @Test
    public void observationIsOpaqueAndStaleIdleCannotAcquireAfterAba() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        CompositionCoordinator.Observation originalIdle = coordinator.observe();
        assertEquals(new CompositionState.Idle(), originalIdle.state());
        assertEquals(0L, originalIdle.version());

        CompositionCoordinator.Transition acquired = coordinator.acquire(
                originalIdle, new CompositionCoordinator.Acquisition.Latin(1L));
        assertApplied(acquired, originalIdle, new CompositionState.LatinComposing(1L, 1L));
        CompositionCoordinator.Transition cancelled = coordinator.cancel(acquired.after());
        assertApplied(cancelled, acquired.after(), new CompositionState.Idle());
        assertNotSame(originalIdle, cancelled.after());
        assertEquals(2L, cancelled.after().version());

        assertUnchanged(
                coordinator.acquire(
                        originalIdle, new CompositionCoordinator.Acquisition.Voice()),
                cancelled.after(),
                CompositionCoordinator.Disposition.IGNORED_STALE);

        CompositionCoordinator foreign = new CompositionCoordinator();
        assertUnchanged(
                coordinator.acquire(
                        foreign.observe(), new CompositionCoordinator.Acquisition.Voice()),
                cancelled.after(),
                CompositionCoordinator.Disposition.REJECTED_OBSERVATION);
    }

    @Test
    public void acquireMapsAllRequestsAndActiveAcquireIsConflict() {
        List<AcquireCase> cases = List.of(
                new AcquireCase(
                        new CompositionCoordinator.Acquisition.Latin(3L),
                        new CompositionState.LatinComposing(1L, 3L)),
                new AcquireCase(
                        new CompositionCoordinator.Acquisition.Rime(4L),
                        new CompositionState.RimeComposing(1L, 4L)),
                new AcquireCase(
                        new CompositionCoordinator.Acquisition.Voice(),
                        new CompositionState.VoicePreparing(1L)),
                new AcquireCase(
                        new CompositionCoordinator.Acquisition.Action(),
                        new CompositionState.ActionRunning(1L)));
        for (AcquireCase entry : cases) {
            CompositionCoordinator coordinator = new CompositionCoordinator();
            CompositionCoordinator.Transition transition = coordinator.acquire(
                    coordinator.observe(), entry.acquisition());
            assertApplied(transition, transition.before(), entry.expected());
            assertSame(transition.after(), coordinator.observe());
            assertUnchanged(
                    coordinator.acquire(
                            transition.after(),
                            new CompositionCoordinator.Acquisition.Voice()),
                    transition.after(),
                    CompositionCoordinator.Disposition.REJECTED_CONFLICT);
        }
    }

    @Test
    public void latinAndRimeRevisionAndCommitAreExact() {
        for (CompositionCoordinator.Acquisition acquisition : List.of(
                new CompositionCoordinator.Acquisition.Latin(3L),
                new CompositionCoordinator.Acquisition.Rime(3L))) {
            CompositionCoordinator coordinator = new CompositionCoordinator();
            CompositionCoordinator.Observation revisionThree = coordinator.acquire(
                    coordinator.observe(), acquisition).after();
            assertUnchanged(
                    coordinator.update(revisionThree, 3L),
                    revisionThree,
                    CompositionCoordinator.Disposition.IGNORED_DUPLICATE);
            assertUnchanged(
                    coordinator.update(revisionThree, 2L),
                    revisionThree,
                    CompositionCoordinator.Disposition.IGNORED_STALE);
            assertUnchanged(
                    coordinator.update(revisionThree, 0L),
                    revisionThree,
                    CompositionCoordinator.Disposition.REJECTED_REVISION);

            CompositionCoordinator.Observation maximum = coordinator.update(
                    revisionThree, Long.MAX_VALUE).after();
            assertEquals(Long.MAX_VALUE, revisionOf(maximum.state()));
            assertUnchanged(
                    coordinator.commit(maximum, 3L),
                    maximum,
                    CompositionCoordinator.Disposition.REJECTED_REVISION);
            assertApplied(
                    coordinator.commit(maximum, Long.MAX_VALUE),
                    maximum,
                    new CompositionState.Idle());
        }
    }

    @Test
    public void voiceGraphHandlesNoPartialNewerPartialAndLateEvents() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        CompositionCoordinator.Observation preparing = coordinator.acquire(
                coordinator.observe(), new CompositionCoordinator.Acquisition.Voice()).after();
        assertUnchanged(
                coordinator.voicePartial(preparing, 1L),
                preparing,
                CompositionCoordinator.Disposition.REJECTED_STATE);

        CompositionCoordinator.Observation listening = coordinator.voiceReady(preparing).after();
        assertEquals(new CompositionState.VoiceListening(1L), listening.state());
        assertUnchanged(
                coordinator.voiceReady(listening),
                listening,
                CompositionCoordinator.Disposition.IGNORED_DUPLICATE);
        CompositionCoordinator.Observation partial = coordinator.voicePartial(listening, 1L).after();
        assertUnchanged(
                coordinator.voicePartial(partial, 1L),
                partial,
                CompositionCoordinator.Disposition.IGNORED_DUPLICATE);
        assertUnchanged(
                coordinator.voicePartial(partial, 0L),
                partial,
                CompositionCoordinator.Disposition.REJECTED_REVISION);
        CompositionCoordinator.Observation second = coordinator.voicePartial(partial, 2L).after();
        assertUnchanged(
                coordinator.voicePartial(second, 1L),
                second,
                CompositionCoordinator.Disposition.IGNORED_STALE);
        CompositionCoordinator.Observation finalizing =
                coordinator.beginVoiceFinalizing(second).after();
        assertEquals(new CompositionState.VoiceFinalizing(1L, 2L), finalizing.state());
        assertUnchanged(
                coordinator.voicePartial(finalizing, 3L),
                finalizing,
                CompositionCoordinator.Disposition.IGNORED_STALE);
        CompositionCoordinator.Observation idle = coordinator.complete(finalizing).after();
        assertEquals(new CompositionState.Idle(), idle.state());
        assertUnchanged(
                coordinator.complete(finalizing),
                idle,
                CompositionCoordinator.Disposition.IGNORED_STALE);

        CompositionCoordinator noPartial = new CompositionCoordinator();
        CompositionCoordinator.Observation noPartialPreparing = noPartial.acquire(
                noPartial.observe(), new CompositionCoordinator.Acquisition.Voice()).after();
        CompositionCoordinator.Observation noPartialListening =
                noPartial.voiceReady(noPartialPreparing).after();
        assertEquals(
                new CompositionState.VoiceFinalizing(1L, 0L),
                noPartial.beginVoiceFinalizing(noPartialListening).after().state());
    }

    @Test
    public void actionRunningHasNoOwnerAndOnlyPreviewOwnsComposition() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        CompositionCoordinator.Observation running = coordinator.acquire(
                coordinator.observe(), new CompositionCoordinator.Acquisition.Action()).after();
        assertEquals(CompositionOwner.NONE, running.state().owner());
        CompositionCoordinator.Observation preview =
                coordinator.showActionPreview(running).after();
        assertEquals(new CompositionState.ActionPreview(1L), preview.state());
        assertEquals(CompositionOwner.ACTION_PREVIEW, preview.state().owner());
        assertUnchanged(
                coordinator.showActionPreview(preview),
                preview,
                CompositionCoordinator.Disposition.IGNORED_DUPLICATE);
        assertEquals(new CompositionState.Idle(), coordinator.complete(preview).after().state());
    }

    @Test
    public void cancelCoversAllEightActiveVariantsAndIdleIsDuplicate() {
        CompositionCoordinator idleCoordinator = new CompositionCoordinator();
        CompositionCoordinator.Observation idle = idleCoordinator.observe();
        assertUnchanged(
                idleCoordinator.cancel(idle),
                idle,
                CompositionCoordinator.Disposition.IGNORED_DUPLICATE);

        for (StateSetup setup : activeSetups()) {
            CompositionCoordinator coordinator = new CompositionCoordinator();
            CompositionCoordinator.Observation active = setup.create(coordinator);
            CompositionCoordinator.Transition cancelled = coordinator.cancel(active);
            assertApplied(cancelled, active, new CompositionState.Idle());
        }
    }

    @Test
    public void generationAndVersionExhaustionFailWithoutChangingObservation() {
        CompositionCoordinator generationExhausted =
                new CompositionCoordinator(Long.MAX_VALUE, 0L);
        CompositionCoordinator.Observation idle = generationExhausted.observe();
        assertUnchanged(
                generationExhausted.acquire(
                        idle, new CompositionCoordinator.Acquisition.Voice()),
                idle,
                CompositionCoordinator.Disposition.GENERATION_EXHAUSTED);

        CompositionCoordinator reachesGenerationMaximum =
                new CompositionCoordinator(Long.MAX_VALUE - 1L, 0L);
        CompositionCoordinator.Observation maximumGeneration =
                reachesGenerationMaximum.acquire(
                        reachesGenerationMaximum.observe(),
                        new CompositionCoordinator.Acquisition.Rime(1L)).after();
        CompositionCoordinator.PreemptRejected generationRejected = rejected(
                reachesGenerationMaximum.beginPreempt(
                        maximumGeneration,
                        CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT,
                        new CompositionCoordinator.Acquisition.Action()));
        assertUnchanged(
                generationRejected.transition(),
                maximumGeneration,
                CompositionCoordinator.Disposition.GENERATION_EXHAUSTED);

        CompositionCoordinator versionExhausted =
                new CompositionCoordinator(0L, Long.MAX_VALUE);
        CompositionCoordinator.Observation versionMax = versionExhausted.observe();
        assertUnchanged(
                versionExhausted.acquire(
                        versionMax, new CompositionCoordinator.Acquisition.Voice()),
                versionMax,
                CompositionCoordinator.Disposition.VERSION_EXHAUSTED);

        CompositionCoordinator preemptVersion =
                new CompositionCoordinator(0L, Long.MAX_VALUE - 2L);
        CompositionCoordinator.Observation active = preemptVersion.acquire(
                preemptVersion.observe(),
                new CompositionCoordinator.Acquisition.Latin(1L)).after();
        CompositionCoordinator.PreemptRejected rejected = rejected(preemptVersion.beginPreempt(
                active,
                CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT,
                new CompositionCoordinator.Acquisition.Voice()));
        assertUnchanged(
                rejected.transition(),
                active,
                CompositionCoordinator.Disposition.VERSION_EXHAUSTED);
    }

    @Test
    public void preemptDirectiveAllowlistIsExactForEveryActivePhase() {
        for (StateSetup setup : activeSetups()) {
            for (CompositionCoordinator.ReleaseDirective directive
                    : CompositionCoordinator.ReleaseDirective.values()) {
                CompositionCoordinator coordinator = new CompositionCoordinator();
                CompositionCoordinator.Observation active = setup.create(coordinator);
                CompositionCoordinator.PreemptStart result = coordinator.beginPreempt(
                        active,
                        directive,
                        new CompositionCoordinator.Acquisition.Voice());
                if (directive == CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT
                        || setup.commitAllowed()) {
                    assertTrue(setup.name(),
                            result instanceof CompositionCoordinator.PreemptPrepared);
                } else {
                    assertEquals(
                            setup.name(),
                            CompositionCoordinator.Disposition.REJECTED_RELEASE_DIRECTIVE,
                            rejected(result).transition().disposition());
                    assertSame(active, coordinator.observe());
                }
            }
        }
    }

    @Test
    public void preemptSuccessPublishesOnlyAfterReleaseProof() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        CompositionCoordinator.Observation active = coordinator.acquire(
                coordinator.observe(), new CompositionCoordinator.Acquisition.Rime(4L)).after();
        CompositionCoordinator.PreemptPrepared prepared = prepared(coordinator.beginPreempt(
                active,
                CompositionCoordinator.ReleaseDirective.COMMIT_CURRENT,
                new CompositionCoordinator.Acquisition.Action()));

        assertEquals(active.state(), prepared.observation().state());
        assertEquals(active.version() + 1L, prepared.observation().version());
        assertEquals(
                CompositionCoordinator.ObservationPhase.PREEMPT_PENDING,
                prepared.observation().phase());
        assertSame(prepared.observation(), coordinator.observe());
        assertUnchanged(
                coordinator.cancel(prepared.observation()),
                prepared.observation(),
                CompositionCoordinator.Disposition.REJECTED_PREEMPTION_PENDING);

        CompositionCoordinator.Transition finished = coordinator.finishPreempt(
                prepared.ticket(),
                CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED);
        assertEquals(CompositionCoordinator.Disposition.APPLIED, finished.disposition());
        assertEquals(new CompositionState.ActionRunning(2L), finished.after().state());
        assertEquals(active.version() + 2L, finished.after().version());
        assertEquals(
                CompositionCoordinator.ObservationPhase.STABLE,
                finished.after().phase());
        assertSame(finished.after(), coordinator.observe());
    }

    @Test
    public void provenUnchangedRestoresOldStateWithoutConsumingGeneration() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        CompositionCoordinator.Observation active = coordinator.acquire(
                coordinator.observe(), new CompositionCoordinator.Acquisition.Latin(1L)).after();
        CompositionCoordinator.PreemptPrepared prepared = prepared(coordinator.beginPreempt(
                active,
                CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT,
                new CompositionCoordinator.Acquisition.Voice()));
        CompositionCoordinator.Transition restored = coordinator.finishPreempt(
                prepared.ticket(),
                CompositionCoordinator.ReleaseResolution.PROVEN_UNCHANGED);
        assertEquals(
                CompositionCoordinator.Disposition.RELEASE_PROVEN_UNCHANGED,
                restored.disposition());
        assertEquals(active.state(), restored.after().state());
        assertEquals(active.version() + 2L, restored.after().version());
        assertNotSame(active, restored.after());

        CompositionCoordinator.Observation idle = coordinator.cancel(restored.after()).after();
        CompositionCoordinator.Observation next = coordinator.acquire(
                idle, new CompositionCoordinator.Acquisition.Voice()).after();
        assertEquals(2L, next.state().coordinationGeneration());
    }

    @Test
    public void uncertainReleaseRemainsPendingUntilSameTicketGetsProof() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        CompositionCoordinator.Observation active = coordinator.acquire(
                coordinator.observe(), new CompositionCoordinator.Acquisition.Voice()).after();
        CompositionCoordinator.PreemptPrepared prepared = prepared(coordinator.beginPreempt(
                active,
                CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT,
                new CompositionCoordinator.Acquisition.Rime(1L)));
        CompositionCoordinator.Transition uncertain = coordinator.finishPreempt(
                prepared.ticket(), CompositionCoordinator.ReleaseResolution.UNCERTAIN);
        assertUnchanged(
                uncertain,
                prepared.observation(),
                CompositionCoordinator.Disposition.RELEASE_UNCERTAIN);
        assertSame(prepared.observation(), coordinator.observe());

        CompositionCoordinator foreign = new CompositionCoordinator();
        CompositionCoordinator.Observation foreignActive = foreign.acquire(
                foreign.observe(), new CompositionCoordinator.Acquisition.Voice()).after();
        CompositionCoordinator.PreemptPrepared foreignPrepared = prepared(foreign.beginPreempt(
                foreignActive,
                CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT,
                new CompositionCoordinator.Acquisition.Action()));
        assertUnchanged(
                coordinator.finishPreempt(
                        foreignPrepared.ticket(),
                        CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED),
                prepared.observation(),
                CompositionCoordinator.Disposition.REJECTED_PREEMPT_TICKET);
        assertSame(prepared.observation(), coordinator.observe());

        CompositionCoordinator.Transition resolved = coordinator.finishPreempt(
                prepared.ticket(),
                CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED);
        assertEquals(CompositionCoordinator.Disposition.APPLIED, resolved.disposition());
        assertEquals(new CompositionState.RimeComposing(2L, 1L), resolved.after().state());
        assertUnchanged(
                coordinator.finishPreempt(
                        prepared.ticket(),
                        CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED),
                resolved.after(),
                CompositionCoordinator.Disposition.REJECTED_PREEMPT_TICKET);
    }

    @Test
    public void pendingRejectsEveryOrdinaryMutationAndAnotherPreemption() {
        CompositionCoordinator coordinator = new CompositionCoordinator();
        CompositionCoordinator.Observation active = coordinator.acquire(
                coordinator.observe(), new CompositionCoordinator.Acquisition.Latin(1L)).after();
        CompositionCoordinator.PreemptPrepared prepared = prepared(coordinator.beginPreempt(
                active,
                CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT,
                new CompositionCoordinator.Acquisition.Voice()));
        CompositionCoordinator.Observation pending = prepared.observation();

        List<CompositionCoordinator.Transition> rejected = List.of(
                coordinator.acquire(
                        pending, new CompositionCoordinator.Acquisition.Action()),
                coordinator.update(pending, 2L),
                coordinator.voiceReady(pending),
                coordinator.voicePartial(pending, 1L),
                coordinator.beginVoiceFinalizing(pending),
                coordinator.showActionPreview(pending),
                coordinator.commit(pending, 1L),
                coordinator.complete(pending),
                coordinator.cancel(pending));
        for (CompositionCoordinator.Transition transition : rejected) {
            assertUnchanged(
                    transition,
                    pending,
                    CompositionCoordinator.Disposition.REJECTED_PREEMPTION_PENDING);
        }
        assertEquals(
                CompositionCoordinator.Disposition.REJECTED_PREEMPTION_PENDING,
                rejected(coordinator.beginPreempt(
                        pending,
                        CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT,
                        new CompositionCoordinator.Acquisition.Action()))
                        .transition()
                        .disposition());
        assertSame(pending, coordinator.observe());
    }

    @Test
    public void publicTokensCannotBeConstructedAndDiagnosticsAreContentFree() {
        assertEquals(
                Set.of(
                        CompositionCoordinator.PreemptPrepared.class,
                        CompositionCoordinator.PreemptRejected.class),
                Set.of(CompositionCoordinator.PreemptStart.class.getPermittedSubclasses()));
        assertEquals(
                Set.of(
                        CompositionCoordinator.ReleaseDirective.COMMIT_CURRENT,
                        CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT),
                Set.of(CompositionCoordinator.ReleaseDirective.values()));
        assertEquals(
                Set.of(
                        CompositionCoordinator.ReleaseResolution.PROVEN_RELEASED,
                        CompositionCoordinator.ReleaseResolution.PROVEN_UNCHANGED,
                        CompositionCoordinator.ReleaseResolution.UNCERTAIN),
                Set.of(CompositionCoordinator.ReleaseResolution.values()));
        for (Class<?> type : List.of(
                CompositionCoordinator.Observation.class,
                CompositionCoordinator.Transition.class,
                CompositionCoordinator.PreemptPrepared.class,
                CompositionCoordinator.PreemptRejected.class,
                CompositionCoordinator.PreemptTicket.class)) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertFalse(type.getName(), Modifier.isPublic(constructor.getModifiers()));
            }
        }
        CompositionCoordinator coordinator = new CompositionCoordinator();
        CompositionCoordinator.Observation active = coordinator.acquire(
                coordinator.observe(), new CompositionCoordinator.Acquisition.Latin(7L)).after();
        CompositionCoordinator.PreemptPrepared prepared = prepared(coordinator.beginPreempt(
                active,
                CompositionCoordinator.ReleaseDirective.CANCEL_CURRENT,
                new CompositionCoordinator.Acquisition.Action()));
        String diagnostics = active + " " + prepared + " " + prepared.ticket();
        assertFalse(diagnostics.contains("InputConnection"));
        assertFalse(diagnostics.contains("android."));
        assertTrue(diagnostics.contains("ticket=<redacted>"));
    }

    @Test
    public void allPublicCoordinatorEntrypointsAreSynchronizedAndModelsAreNotSerializable() {
        Set<String> expected = Set.of(
                "observe",
                "acquire",
                "update",
                "voiceReady",
                "voicePartial",
                "beginVoiceFinalizing",
                "showActionPreview",
                "commit",
                "complete",
                "cancel",
                "beginPreempt",
                "finishPreempt");
        Set<String> actual = Arrays.stream(CompositionCoordinator.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .peek(method -> assertTrue(
                        method.getName(),
                        Modifier.isSynchronized(method.getModifiers())))
                .map(method -> method.getName())
                .collect(Collectors.toSet());
        assertEquals(expected, actual);

        for (Class<?> type : List.of(
                CompositionCoordinator.class,
                CompositionCoordinator.Observation.class,
                CompositionCoordinator.Transition.class,
                CompositionCoordinator.PreemptTicket.class,
                CompositionCoordinator.PreemptPrepared.class,
                CompositionCoordinator.PreemptRejected.class)) {
            assertFalse(type.getName(), java.io.Serializable.class.isAssignableFrom(type));
        }
    }

    @Test
    public void concurrentExactAcquireHasOneWinnerAndNoGenerationGap() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 24; iteration++) {
                CompositionCoordinator coordinator = new CompositionCoordinator();
                CompositionCoordinator.Observation idle = coordinator.observe();
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<CompositionCoordinator.Transition> latin = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return coordinator.acquire(
                            idle, new CompositionCoordinator.Acquisition.Latin(1L));
                });
                Future<CompositionCoordinator.Transition> voice = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return coordinator.acquire(
                            idle, new CompositionCoordinator.Acquisition.Voice());
                });
                assertTrue(ready.await(5L, TimeUnit.SECONDS));
                start.countDown();
                List<CompositionCoordinator.Transition> results =
                        List.of(latin.get(5L, TimeUnit.SECONDS), voice.get(5L, TimeUnit.SECONDS));
                assertEquals(1L, results.stream()
                        .filter(result -> result.disposition()
                                == CompositionCoordinator.Disposition.APPLIED)
                        .count());
                assertEquals(1L, results.stream()
                        .filter(result -> result.disposition()
                                == CompositionCoordinator.Disposition.IGNORED_STALE)
                        .count());
                assertEquals(1L, coordinator.observe().state().coordinationGeneration());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
        }
    }

    private static List<StateSetup> activeSetups() {
        return List.of(
                new StateSetup("Latin", true, coordinator -> coordinator.acquire(
                        coordinator.observe(),
                        new CompositionCoordinator.Acquisition.Latin(1L)).after()),
                new StateSetup("Rime", true, coordinator -> coordinator.acquire(
                        coordinator.observe(),
                        new CompositionCoordinator.Acquisition.Rime(1L)).after()),
                new StateSetup("VoicePreparing", false, coordinator -> coordinator.acquire(
                        coordinator.observe(),
                        new CompositionCoordinator.Acquisition.Voice()).after()),
                new StateSetup("VoiceListening", false, coordinator -> {
                    CompositionCoordinator.Observation preparing = coordinator.acquire(
                            coordinator.observe(),
                            new CompositionCoordinator.Acquisition.Voice()).after();
                    return coordinator.voiceReady(preparing).after();
                }),
                new StateSetup("VoicePartial", true, coordinator -> {
                    CompositionCoordinator.Observation preparing = coordinator.acquire(
                            coordinator.observe(),
                            new CompositionCoordinator.Acquisition.Voice()).after();
                    CompositionCoordinator.Observation listening =
                            coordinator.voiceReady(preparing).after();
                    return coordinator.voicePartial(listening, 1L).after();
                }),
                new StateSetup("VoiceFinalizing0", false, coordinator -> {
                    CompositionCoordinator.Observation preparing = coordinator.acquire(
                            coordinator.observe(),
                            new CompositionCoordinator.Acquisition.Voice()).after();
                    CompositionCoordinator.Observation listening =
                            coordinator.voiceReady(preparing).after();
                    return coordinator.beginVoiceFinalizing(listening).after();
                }),
                new StateSetup("ActionRunning", false, coordinator -> coordinator.acquire(
                        coordinator.observe(),
                        new CompositionCoordinator.Acquisition.Action()).after()),
                new StateSetup("ActionPreview", true, coordinator -> {
                    CompositionCoordinator.Observation running = coordinator.acquire(
                            coordinator.observe(),
                            new CompositionCoordinator.Acquisition.Action()).after();
                    return coordinator.showActionPreview(running).after();
                }));
    }

    private static long revisionOf(CompositionState state) {
        if (state instanceof CompositionState.LatinComposing latin) return latin.revision();
        return ((CompositionState.RimeComposing) state).revision();
    }

    private static CompositionCoordinator.PreemptPrepared prepared(
            CompositionCoordinator.PreemptStart result) {
        assertTrue(result.toString(), result instanceof CompositionCoordinator.PreemptPrepared);
        return (CompositionCoordinator.PreemptPrepared) result;
    }

    private static CompositionCoordinator.PreemptRejected rejected(
            CompositionCoordinator.PreemptStart result) {
        assertTrue(result.toString(), result instanceof CompositionCoordinator.PreemptRejected);
        return (CompositionCoordinator.PreemptRejected) result;
    }

    private static void assertApplied(
            CompositionCoordinator.Transition transition,
            CompositionCoordinator.Observation before,
            CompositionState afterState) {
        assertSame(before, transition.before());
        assertEquals(CompositionCoordinator.Disposition.APPLIED, transition.disposition());
        assertEquals(afterState, transition.after().state());
        assertEquals(before.version() + 1L, transition.after().version());
        assertNotSame(before, transition.after());
    }

    private static void assertUnchanged(
            CompositionCoordinator.Transition transition,
            CompositionCoordinator.Observation expected,
            CompositionCoordinator.Disposition disposition) {
        assertSame(expected, transition.before());
        assertSame(expected, transition.after());
        assertEquals(disposition, transition.disposition());
    }

    private static void assertIllegal(Runnable runnable) {
        try {
            runnable.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertNullRejected(Runnable runnable) {
        try {
            runnable.run();
            fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // Expected.
        }
    }

    private record AcquireCase(
            CompositionCoordinator.Acquisition acquisition,
            CompositionState expected) {}

    private record StateSetup(
            String name,
            boolean commitAllowed,
            StateFactory factory) {
        CompositionCoordinator.Observation create(CompositionCoordinator coordinator) {
            return factory.create(coordinator);
        }
    }

    @FunctionalInterface
    private interface StateFactory {
        CompositionCoordinator.Observation create(CompositionCoordinator coordinator);
    }
}
