package com.opentypeless.android.speech.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public final class EngineTraceFixtureTest {
    @Test
    public void pinnedProviderShapesDecodeReplayAndCanonicalize() throws IOException {
        List<Fixture> fixtures = List.of(
                new Fixture("paraformer-full-hypothesis-v1.json", "今天开会讨论"),
                new Fixture("android-system-partial-final-v1.json", "OpenTypeless"),
                new Fixture("network-batch-final-v1.json", "final only result"));

        for (Fixture fixture : fixtures) {
            String source = resource("speech-traces/" + fixture.fileName());
            EngineTrace decoded = EngineTraceJson.decode(source);
            String canonical = EngineTraceJson.encode(decoded);
            EngineTrace canonicalDecoded = EngineTraceJson.decode(canonical);
            EngineReplayReport report = new EngineTraceReplayer().replay(canonicalDecoded);

            assertEquals(fixture.fileName(), decoded, canonicalDecoded);
            assertEquals(fixture.fileName(), fixture.expectedText(), report.draft().renderedText());
            assertEquals(fixture.fileName(), 0L, report.rejectedCount());
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = EngineTraceFixtureTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertNotNull("missing fixture " + name, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Fixture(String fileName, String expectedText) {}
}
