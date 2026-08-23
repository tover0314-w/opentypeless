package com.opentypeless.android.keyboard.switching;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Bounded, capability-free selection state shared by Latin and future Rime wiring. */
public final class KeyboardEngineSelection {
    public enum Engine {
        LATIN,
        RIME
    }

    public sealed interface CycleResult permits Changed, Unavailable {
        KeyboardEngineSelection state();
    }

    public record Changed(KeyboardEngineSelection state) implements CycleResult {
        public Changed {
            Objects.requireNonNull(state, "state");
        }
    }

    public record Unavailable(KeyboardEngineSelection state) implements CycleResult {
        public Unavailable {
            Objects.requireNonNull(state, "state");
        }
    }

    private final Engine active;
    private final Set<Engine> available;
    private final long revision;

    private KeyboardEngineSelection(Engine active, Set<Engine> available, long revision) {
        this.active = Objects.requireNonNull(active, "active");
        Objects.requireNonNull(available, "available");
        if (revision <= 0L || available.isEmpty() || !available.contains(active)) {
            throw new IllegalArgumentException("invalid keyboard engine selection");
        }
        this.available = Set.copyOf(EnumSet.copyOf(available));
        this.revision = revision;
    }

    public static KeyboardEngineSelection latinOnly() {
        return new KeyboardEngineSelection(Engine.LATIN, EnumSet.of(Engine.LATIN), 1L);
    }

    public static KeyboardEngineSelection of(
            Engine active, Set<Engine> available, long revision) {
        return new KeyboardEngineSelection(active, available, revision);
    }

    public Engine active() {
        return active;
    }

    public Set<Engine> available() {
        return available;
    }

    public long revision() {
        return revision;
    }

    public boolean hasAlternative() {
        return available.size() > 1;
    }

    public CycleResult cycle() {
        if (!hasAlternative()) return new Unavailable(this);
        Engine next = active == Engine.LATIN ? Engine.RIME : Engine.LATIN;
        if (!available.contains(next)) return new Unavailable(this);
        return new Changed(new KeyboardEngineSelection(next, available, nextRevision()));
    }

    public KeyboardEngineSelection withAvailability(Set<Engine> nextAvailable) {
        return withAvailabilityAndPreference(nextAvailable, active);
    }

    public KeyboardEngineSelection withAvailabilityAndPreference(
            Set<Engine> nextAvailable, Engine preferred) {
        Objects.requireNonNull(nextAvailable, "nextAvailable");
        Objects.requireNonNull(preferred, "preferred");
        if (nextAvailable.isEmpty()) {
            throw new IllegalArgumentException("at least one engine must remain available");
        }
        Set<Engine> bounded = Set.copyOf(EnumSet.copyOf(nextAvailable));
        if (!bounded.contains(Engine.LATIN)) {
            throw new IllegalArgumentException("Latin must remain as the safe fallback engine");
        }
        Engine nextActive = bounded.contains(preferred)
                ? preferred
                : bounded.contains(active) ? active : Engine.LATIN;
        if (bounded.equals(available) && nextActive == active) return this;
        return new KeyboardEngineSelection(nextActive, bounded, nextRevision());
    }

    private long nextRevision() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("keyboard engine revision exhausted");
        }
        return revision + 1L;
    }

    @Override
    public String toString() {
        return "KeyboardEngineSelection{active=" + active
                + ", availableCount=" + available.size()
                + ", revision=" + revision + "}";
    }
}
