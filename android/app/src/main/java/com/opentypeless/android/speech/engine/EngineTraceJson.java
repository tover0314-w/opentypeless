package com.opentypeless.android.speech.engine;

import com.opentypeless.android.speech.core.RevisionOrigin;
import com.opentypeless.android.speech.core.RevisionStage;
import com.opentypeless.android.speech.core.SegmentJoin;
import com.opentypeless.android.speech.core.SegmentRevision;
import com.opentypeless.android.speech.core.SessionId;
import com.opentypeless.android.speech.core.TerminalReason;
import com.opentypeless.android.speech.core.TokenEvidence;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded v1 JSON codec for redacted replay fixtures and Voice Lab exports. */
public final class EngineTraceJson {
    private EngineTraceJson() {}

    public static String encode(EngineTrace trace) {
        return encode(trace, EngineTraceLimits.DEFAULT);
    }

    public static String encode(EngineTrace trace, EngineTraceLimits limits) {
        requireTraceBounds(trace, limits);
        StringBuilder json = new StringBuilder();
        json.append("{\"schemaVersion\":").append(trace.schemaVersion());
        json.append(",\"engine\":");
        writeEngine(json, trace.engine());
        json.append(",\"sessionId\":");
        appendQuoted(json, trace.sessionId().value());
        json.append(",\"events\":[");
        for (int index = 0; index < trace.events().size(); index++) {
            if (index > 0) json.append(',');
            writeEvent(json, trace.events().get(index));
        }
        json.append("]}");
        String result = json.toString();
        requireJsonBytes(result, limits);
        return result;
    }

    public static EngineTrace decode(String json) {
        return decode(json, EngineTraceLimits.DEFAULT);
    }

    public static EngineTrace decode(String json, EngineTraceLimits limits) {
        if (json == null) {
            throw new IllegalArgumentException("engine trace JSON is missing");
        }
        requireJsonBytes(json, limits);
        try {
            JSONObject root = new JSONObject(json);
            int schemaVersion = root.getInt("schemaVersion");
            if (schemaVersion != EngineTrace.CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported engine trace schema");
            }
            EngineDescriptor engine = readEngine(root.getJSONObject("engine"));
            SessionId sessionId = SessionId.of(root.getString("sessionId"));
            JSONArray eventJson = root.getJSONArray("events");
            if (eventJson.length() > limits.maxEvents()) {
                throw new IllegalArgumentException("engine trace event limit exceeded");
            }
            ArrayList<EngineEvent> events = new ArrayList<>(eventJson.length());
            for (int index = 0; index < eventJson.length(); index++) {
                events.add(readEvent(eventJson.getJSONObject(index), limits));
            }
            EngineTrace trace = new EngineTrace(schemaVersion, engine, sessionId, events);
            requireTraceBounds(trace, limits);
            return trace;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (JSONException | RuntimeException exception) {
            throw new IllegalArgumentException("invalid engine trace JSON", exception);
        }
    }

    private static void writeEngine(StringBuilder json, EngineDescriptor engine) {
        json.append("{\"engineId\":");
        appendQuoted(json, engine.engineId());
        json.append(",\"displayName\":");
        appendQuoted(json, engine.displayName());
        json.append(",\"modelRevision\":");
        appendQuoted(json, engine.modelRevision());
        json.append(",\"processingLocation\":");
        appendQuoted(json, engine.processingLocation().name());
        json.append(",\"capabilities\":[");
        boolean first = true;
        for (EngineCapability capability : EngineCapability.values()) {
            if (engine.capabilities().supports(capability)) {
                if (!first) json.append(',');
                appendQuoted(json, capability.name());
                first = false;
            }
        }
        json.append("]}");
    }

    private static EngineDescriptor readEngine(JSONObject json) throws JSONException {
        JSONArray capabilityJson = json.getJSONArray("capabilities");
        if (capabilityJson.length() > EngineCapability.values().length) {
            throw new IllegalArgumentException("too many engine capabilities");
        }
        EnumSet<EngineCapability> capabilities = EnumSet.noneOf(EngineCapability.class);
        for (int index = 0; index < capabilityJson.length(); index++) {
            capabilities.add(EngineCapability.valueOf(capabilityJson.getString(index)));
        }
        return new EngineDescriptor(
                json.getString("engineId"),
                json.getString("displayName"),
                json.getString("modelRevision"),
                ProcessingLocation.valueOf(json.getString("processingLocation")),
                new EngineCapabilities(capabilities));
    }

    private static void writeEvent(StringBuilder json, EngineEvent event) {
        json.append("{\"type\":");
        appendQuoted(json, typeOf(event));
        json.append(",\"engineId\":");
        appendQuoted(json, event.engineId());
        json.append(",\"eventSequence\":").append(event.eventSequence());
        if (!(event instanceof EngineEvent.Transcript)) {
            json.append(",\"sessionId\":");
            appendQuoted(json, event.sessionId().value());
        }
        if (event instanceof EngineEvent.OpenSegment opened) {
            writeSegment(json, opened.segmentId());
            json.append(",\"joinBefore\":");
            appendQuoted(json, opened.joinBefore().name());
        } else if (event instanceof EngineEvent.SoftBoundary boundary) {
            writeSegment(json, boundary.segmentId());
        } else if (event instanceof EngineEvent.ReopenSegment reopened) {
            writeSegment(json, reopened.segmentId());
        } else if (event instanceof EngineEvent.HardBoundary boundary) {
            writeSegment(json, boundary.segmentId());
        } else if (event instanceof EngineEvent.SealSegment sealed) {
            writeSegment(json, sealed.segmentId());
        } else if (event instanceof EngineEvent.Transcript transcript) {
            json.append(",\"revision\":");
            writeRevision(json, transcript.revision());
        } else if (event instanceof EngineEvent.CaptureEnded ended) {
            json.append(",\"reason\":");
            appendQuoted(json, ended.reason().name());
        } else if (event instanceof EngineEvent.CaptureFailed failed) {
            json.append(",\"reason\":");
            appendQuoted(json, failed.reason().name());
        }
        json.append('}');
    }

    private static EngineEvent readEvent(JSONObject json, EngineTraceLimits limits)
            throws JSONException {
        String type = json.getString("type");
        String engineId = json.getString("engineId");
        long sequence = json.getLong("eventSequence");
        if (type.equals("transcript")) {
            return new EngineEvent.Transcript(
                    engineId, sequence, readRevision(json.getJSONObject("revision"), limits));
        }
        SessionId sessionId = SessionId.of(json.getString("sessionId"));
        return switch (type) {
            case "prepare" -> new EngineEvent.Prepare(sessionId, engineId, sequence);
            case "ready" -> new EngineEvent.Ready(sessionId, engineId, sequence);
            case "stopRequested" -> new EngineEvent.StopRequested(sessionId, engineId, sequence);
            case "openSegment" -> new EngineEvent.OpenSegment(
                    sessionId,
                    engineId,
                    sequence,
                    json.getLong("segmentId"),
                    SegmentJoin.valueOf(json.getString("joinBefore")));
            case "softBoundary" -> new EngineEvent.SoftBoundary(
                    sessionId, engineId, sequence, json.getLong("segmentId"));
            case "reopenSegment" -> new EngineEvent.ReopenSegment(
                    sessionId, engineId, sequence, json.getLong("segmentId"));
            case "hardBoundary" -> new EngineEvent.HardBoundary(
                    sessionId, engineId, sequence, json.getLong("segmentId"));
            case "sealSegment" -> new EngineEvent.SealSegment(
                    sessionId, engineId, sequence, json.getLong("segmentId"));
            case "captureEnded" -> new EngineEvent.CaptureEnded(
                    sessionId,
                    engineId,
                    sequence,
                    TerminalReason.valueOf(json.getString("reason")));
            case "captureFailed" -> new EngineEvent.CaptureFailed(
                    sessionId,
                    engineId,
                    sequence,
                    TerminalReason.valueOf(json.getString("reason")));
            default -> throw new IllegalArgumentException("unknown engine event type: " + type);
        };
    }

    private static void writeRevision(StringBuilder json, SegmentRevision revision) {
        json.append("{\"sessionId\":");
        appendQuoted(json, revision.sessionId().value());
        json.append(",\"segmentId\":").append(revision.segmentId());
        json.append(",\"revisionId\":").append(revision.revisionId());
        json.append(",\"stage\":");
        appendQuoted(json, revision.stage().name());
        json.append(",\"fullText\":");
        appendQuoted(json, revision.fullText());
        json.append(",\"audioStartMs\":").append(revision.audioStartMs());
        json.append(",\"audioEndMs\":").append(revision.audioEndMs());
        json.append(",\"origin\":");
        appendQuoted(json, revision.origin().name());
        json.append(",\"providerFinal\":").append(revision.providerFinal());
        json.append(",\"tokenEvidence\":[");
        for (int index = 0; index < revision.tokenEvidence().size(); index++) {
            if (index > 0) json.append(',');
            TokenEvidence token = revision.tokenEvidence().get(index);
            json.append("{\"text\":");
            appendQuoted(json, token.text());
            json.append(",\"startCodePoint\":").append(token.startCodePoint());
            json.append(",\"endCodePoint\":").append(token.endCodePoint());
            if (token.confidence().isPresent()) {
                json.append(",\"confidence\":").append(token.confidence().getAsDouble());
            }
            if (token.stable().isPresent()) {
                json.append(",\"stable\":").append(token.stable().get());
            }
            if (token.audioStartMs().isPresent()) {
                json.append(",\"audioStartMs\":").append(token.audioStartMs().getAsLong());
                json.append(",\"audioEndMs\":").append(token.audioEndMs().getAsLong());
            }
            json.append('}');
        }
        json.append("]}");
    }

    private static SegmentRevision readRevision(JSONObject json, EngineTraceLimits limits)
            throws JSONException {
        String fullText = json.getString("fullText");
        if (fullText.codePointCount(0, fullText.length()) > limits.maxTextCodePoints()) {
            throw new IllegalArgumentException("transcript text exceeds trace limit");
        }
        JSONArray tokenJson = json.getJSONArray("tokenEvidence");
        if (tokenJson.length() > limits.maxTokensPerRevision()) {
            throw new IllegalArgumentException("token evidence exceeds trace limit");
        }
        ArrayList<TokenEvidence> tokens = new ArrayList<>(tokenJson.length());
        for (int index = 0; index < tokenJson.length(); index++) {
            JSONObject token = tokenJson.getJSONObject(index);
            OptionalDouble confidence = token.has("confidence")
                    ? OptionalDouble.of(token.getDouble("confidence"))
                    : OptionalDouble.empty();
            Optional<Boolean> stable = token.has("stable")
                    ? Optional.of(token.getBoolean("stable"))
                    : Optional.empty();
            OptionalLong audioStart = token.has("audioStartMs")
                    ? OptionalLong.of(token.getLong("audioStartMs"))
                    : OptionalLong.empty();
            OptionalLong audioEnd = token.has("audioEndMs")
                    ? OptionalLong.of(token.getLong("audioEndMs"))
                    : OptionalLong.empty();
            tokens.add(new TokenEvidence(
                    token.getString("text"),
                    token.getInt("startCodePoint"),
                    token.getInt("endCodePoint"),
                    confidence,
                    stable,
                    audioStart,
                    audioEnd));
        }
        return new SegmentRevision(
                SessionId.of(json.getString("sessionId")),
                json.getLong("segmentId"),
                json.getLong("revisionId"),
                RevisionStage.valueOf(json.getString("stage")),
                fullText,
                tokens,
                json.getLong("audioStartMs"),
                json.getLong("audioEndMs"),
                RevisionOrigin.valueOf(json.getString("origin")),
                json.getBoolean("providerFinal"));
    }

    private static String typeOf(EngineEvent event) {
        if (event instanceof EngineEvent.Prepare) return "prepare";
        if (event instanceof EngineEvent.Ready) return "ready";
        if (event instanceof EngineEvent.StopRequested) return "stopRequested";
        if (event instanceof EngineEvent.OpenSegment) return "openSegment";
        if (event instanceof EngineEvent.SoftBoundary) return "softBoundary";
        if (event instanceof EngineEvent.ReopenSegment) return "reopenSegment";
        if (event instanceof EngineEvent.HardBoundary) return "hardBoundary";
        if (event instanceof EngineEvent.Transcript) return "transcript";
        if (event instanceof EngineEvent.SealSegment) return "sealSegment";
        if (event instanceof EngineEvent.CaptureEnded) return "captureEnded";
        if (event instanceof EngineEvent.CaptureFailed) return "captureFailed";
        throw new IllegalArgumentException("unsupported event type: " + event.getClass());
    }

    private static void writeSegment(StringBuilder json, long segmentId) {
        json.append(",\"segmentId\":").append(segmentId);
    }

    private static void appendQuoted(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20
                            || character == '\u2028'
                            || character == '\u2029'
                            || (Character.isSurrogate(character)
                                    && !(Character.isHighSurrogate(character)
                                            && index + 1 < value.length()
                                            && Character.isLowSurrogate(value.charAt(index + 1))))) {
                        appendUnicodeEscape(json, character);
                    } else {
                        json.append(character);
                        if (Character.isHighSurrogate(character)) {
                            json.append(value.charAt(++index));
                        }
                    }
                }
            }
        }
        json.append('"');
    }

    private static void appendUnicodeEscape(StringBuilder json, char character) {
        json.append("\\u");
        String hex = Integer.toHexString(character);
        for (int padding = hex.length(); padding < 4; padding++) json.append('0');
        json.append(hex);
    }

    private static void requireTraceBounds(EngineTrace trace, EngineTraceLimits limits) {
        if (trace == null || limits == null) {
            throw new IllegalArgumentException("trace and limits are required");
        }
        if (trace.events().size() > limits.maxEvents()) {
            throw new IllegalArgumentException("engine trace event limit exceeded");
        }
        for (EngineEvent event : trace.events()) {
            if (event instanceof EngineEvent.Transcript transcript) {
                SegmentRevision revision = transcript.revision();
                if (revision.fullText().codePointCount(0, revision.fullText().length())
                        > limits.maxTextCodePoints()) {
                    throw new IllegalArgumentException("transcript text exceeds trace limit");
                }
                if (revision.tokenEvidence().size() > limits.maxTokensPerRevision()) {
                    throw new IllegalArgumentException("token evidence exceeds trace limit");
                }
            }
        }
    }

    private static void requireJsonBytes(String json, EngineTraceLimits limits) {
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limits.maxJsonBytes()) {
            throw new IllegalArgumentException("engine trace JSON exceeds byte limit");
        }
    }
}
