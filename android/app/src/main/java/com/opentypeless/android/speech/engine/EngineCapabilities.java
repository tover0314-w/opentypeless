package com.opentypeless.android.speech.engine;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable set of evidence-backed engine features. */
public record EngineCapabilities(Set<EngineCapability> available) {
    public static final EngineCapabilities NONE = new EngineCapabilities(Set.of());

    public EngineCapabilities {
        Objects.requireNonNull(available, "available");
        available = available.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(available));
    }

    public static EngineCapabilities of(EngineCapability... capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.length == 0) {
            return NONE;
        }
        EnumSet<EngineCapability> values = EnumSet.noneOf(EngineCapability.class);
        Collections.addAll(values, capabilities);
        return new EngineCapabilities(values);
    }

    public boolean supports(EngineCapability capability) {
        return available.contains(Objects.requireNonNull(capability, "capability"));
    }
}
