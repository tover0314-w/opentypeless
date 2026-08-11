package com.opentypeless.android.speech.engine;

import com.opentypeless.android.speech.core.SessionId;
import java.util.List;
import java.util.Objects;

/** One redacted, deterministic provider/coordinator trace. */
public record EngineTrace(
        int schemaVersion,
        EngineDescriptor engine,
        SessionId sessionId,
        List<EngineEvent> events) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public EngineTrace {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported engine trace schema");
        }
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(sessionId, "sessionId");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        for (EngineEvent event : events) {
            if (!sessionId.equals(event.sessionId())) {
                throw new IllegalArgumentException("trace event belongs to another session");
            }
        }
    }

    public static EngineTrace of(
            EngineDescriptor engine, SessionId sessionId, List<EngineEvent> events) {
        return new EngineTrace(CURRENT_SCHEMA_VERSION, engine, sessionId, events);
    }
}
