package com.opentypeless.android.net.streaming;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** JSON messages documented by the DashScope Paraformer realtime WebSocket API. */
public final class ParaformerProtocol {
    private static final int MAX_TRANSCRIPT_CODE_POINTS = 20_000;

    public enum EventType {
        TASK_STARTED,
        RESULT,
        TASK_FINISHED,
        TASK_FAILED,
        IGNORED
    }

    public record Event(
            EventType type,
            long sentenceBeginMs,
            String text,
            boolean sentenceEnd,
            String errorCode,
            String errorMessage) {
        public Event {
            if (type == null) throw new IllegalArgumentException("Event type is required");
            text = transcript(text);
            errorCode = clean(errorCode);
            errorMessage = clean(errorMessage);
        }
    }

    private ParaformerProtocol() {}

    public static String runTask(
            String taskId,
            String model,
            String language,
            String vocabularyId) {
        requireTaskId(taskId);
        String cleanModel = requireValue(model, "Streaming model");
        try {
            JSONObject parameters = new JSONObject()
                    .put("format", "pcm")
                    .put("sample_rate", 16_000)
                    .put("disfluency_removal_enabled", false)
                    .put("semantic_punctuation_enabled", false)
                    .put("max_sentence_silence", 700)
                    .put("multi_threshold_mode_enabled", true)
                    .put("punctuation_prediction_enabled", true)
                    .put("inverse_text_normalization_enabled", true)
                    .put("heartbeat", true);
            String hint = languageHint(language);
            if (!hint.isEmpty()) parameters.put("language_hints", new JSONArray().put(hint));
            String vocabulary = clean(vocabularyId);
            if (!vocabulary.isEmpty()) parameters.put("vocabulary_id", vocabulary);

            JSONObject payload = new JSONObject()
                    .put("task_group", "audio")
                    .put("task", "asr")
                    .put("function", "recognition")
                    .put("model", cleanModel)
                    .put("parameters", parameters)
                    .put("input", new JSONObject());
            return new JSONObject()
                    .put("header", header("run-task", taskId))
                    .put("payload", payload)
                    .toString();
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to build streaming request", error);
        }
    }

    public static String finishTask(String taskId) {
        requireTaskId(taskId);
        try {
            return new JSONObject()
                    .put("header", header("finish-task", taskId))
                    .put("payload", new JSONObject().put("input", new JSONObject()))
                    .toString();
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to build streaming finish request", error);
        }
    }

    public static Event parse(String message, String expectedTaskId) {
        requireTaskId(expectedTaskId);
        try {
            JSONObject root = new JSONObject(message == null ? "" : message);
            JSONObject header = root.getJSONObject("header");
            if (!expectedTaskId.equals(header.optString("task_id"))) {
                throw new IllegalArgumentException("Streaming event used an unexpected task ID");
            }
            String event = header.optString("event");
            return switch (event) {
                case "task-started" -> event(EventType.TASK_STARTED);
                case "task-finished" -> event(EventType.TASK_FINISHED);
                case "task-failed" -> new Event(
                        EventType.TASK_FAILED,
                        -1L,
                        "",
                        false,
                        header.optString("error_code"),
                        limit(header.optString("error_message"), 300));
                case "result-generated" -> parseResult(root);
                default -> event(EventType.IGNORED);
            };
        } catch (JSONException error) {
            throw new IllegalArgumentException("Malformed streaming recognition event", error);
        }
    }

    static String languageHint(String language) {
        String value = clean(language).replace('_', '-');
        if (value.isEmpty()) return "";
        String primary = Locale.forLanguageTag(value).getLanguage().toLowerCase(Locale.ROOT);
        if (primary.equals("cmn")) primary = "zh";
        return switch (primary) {
            case "zh", "en", "ja", "yue", "ko", "de", "fr", "ru" -> primary;
            default -> "";
        };
    }

    private static Event parseResult(JSONObject root) throws JSONException {
        JSONObject sentence = root
                .getJSONObject("payload")
                .getJSONObject("output")
                .getJSONObject("sentence");
        if (sentence.optBoolean("heartbeat", false)) return event(EventType.IGNORED);
        String text = sentence.optString("text");
        if (text.codePointCount(0, text.length()) > MAX_TRANSCRIPT_CODE_POINTS) {
            throw new IllegalArgumentException("Streaming transcript exceeded the safety limit");
        }
        return new Event(
                EventType.RESULT,
                sentence.optLong("begin_time", -1L),
                text,
                sentence.optBoolean("sentence_end", false),
                "",
                "");
    }

    private static JSONObject header(String action, String taskId) throws JSONException {
        return new JSONObject()
                .put("action", action)
                .put("task_id", taskId)
                .put("streaming", "duplex");
    }

    private static Event event(EventType type) {
        return new Event(type, -1L, "", false, "", "");
    }

    private static String requireValue(String value, String label) {
        String clean = clean(value);
        if (clean.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return clean;
    }

    private static void requireTaskId(String taskId) {
        if (clean(taskId).isEmpty()) throw new IllegalArgumentException("Task ID is required");
    }

    private static String limit(String value, int codePoints) {
        String clean = clean(value).replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
        int count = clean.codePointCount(0, clean.length());
        return count <= codePoints
                ? clean
                : clean.substring(0, clean.offsetByCodePoints(0, codePoints));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String transcript(String value) {
        return value == null
                ? ""
                : value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
    }
}
