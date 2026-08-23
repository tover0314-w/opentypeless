package com.opentypeless.android.net.streaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class ParaformerProtocolTest {
    private static final String TASK = "2bf83b9a-baeb-4fda-8d9a-123456789012";

    @Test
    public void runTaskRequestsLowLatencyPunctuationAndMandarin() throws Exception {
        JSONObject request = new JSONObject(ParaformerProtocol.runTask(
                TASK,
                "paraformer-realtime-v2",
                "cmn-Hans-CN",
                "vocab-1"));
        JSONObject header = request.getJSONObject("header");
        JSONObject parameters = request.getJSONObject("payload").getJSONObject("parameters");

        assertEquals("run-task", header.getString("action"));
        assertEquals("duplex", header.getString("streaming"));
        assertEquals("pcm", parameters.getString("format"));
        assertEquals(16_000, parameters.getInt("sample_rate"));
        assertEquals(700, parameters.getInt("max_sentence_silence"));
        assertTrue(parameters.getBoolean("punctuation_prediction_enabled"));
        assertEquals("zh", parameters.getJSONArray("language_hints").getString(0));
        assertEquals("vocab-1", parameters.getString("vocabulary_id"));
    }

    @Test
    public void parsesPartialFinalHeartbeatAndFailureEvents() {
        ParaformerProtocol.Event partial = ParaformerProtocol.parse(result(false, false, "你好"), TASK);
        ParaformerProtocol.Event complete = ParaformerProtocol.parse(result(true, false, "你好。"), TASK);
        ParaformerProtocol.Event heartbeat = ParaformerProtocol.parse(result(false, true, ""), TASK);
        ParaformerProtocol.Event failure = ParaformerProtocol.parse(
                "{\"header\":{\"task_id\":\"" + TASK
                        + "\",\"event\":\"task-failed\",\"error_code\":\"CLIENT_ERROR\","
                        + "\"error_message\":\"timeout\"},\"payload\":{}}",
                TASK);

        assertEquals(ParaformerProtocol.EventType.RESULT, partial.type());
        assertFalse(partial.sentenceEnd());
        assertTrue(complete.sentenceEnd());
        assertEquals(ParaformerProtocol.EventType.IGNORED, heartbeat.type());
        assertEquals("CLIENT_ERROR", failure.errorCode());
    }

    @Test
    public void rejectsMalformedAndCrossSessionEvents() {
        assertThrows(IllegalArgumentException.class, () -> ParaformerProtocol.parse("{}", TASK));
        assertThrows(
                IllegalArgumentException.class,
                () -> ParaformerProtocol.parse(result(false, false, "text"), "other-task"));
    }

    private static String result(boolean sentenceEnd, boolean heartbeat, String text) {
        return "{\"header\":{\"task_id\":\"" + TASK
                + "\",\"event\":\"result-generated\"},\"payload\":{\"output\":{\"sentence\":{"
                + "\"begin_time\":170,\"text\":" + JSONObject.quote(text)
                + ",\"heartbeat\":" + heartbeat
                + ",\"sentence_end\":" + sentenceEnd + "}}}}";
    }
}
