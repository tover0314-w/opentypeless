package com.opentypeless.android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class EndpointNormalizerTest {
    @Test
    public void appendsOpenAiPathWithoutDuplicateSlash() {
        assertEquals(
                "https://api.example.test/v1/audio/transcriptions",
                EndpointNormalizer.endpoint(" https://api.example.test/v1/ ", "/audio/transcriptions"));
    }

    @Test
    public void acceptsLanHttpForExplicitSelfHosting() {
        assertEquals(
                "http://192.168.1.20:8000/v1/chat/completions",
                EndpointNormalizer.endpoint("http://192.168.1.20:8000/v1", "chat/completions"));
        assertEquals(
                "http://localhost:11434/v1/chat/completions",
                EndpointNormalizer.endpoint("http://localhost:11434/v1", "chat/completions"));
    }

    @Test
    public void rejectsEmbeddedCredentialsAndNonHttpSchemes() {
        assertThrows(IllegalArgumentException.class,
                () -> EndpointNormalizer.endpoint("https://user:secret@example.test/v1", "models"));
        assertThrows(IllegalArgumentException.class,
                () -> EndpointNormalizer.endpoint("file:///tmp/provider", "models"));
        assertThrows(IllegalArgumentException.class,
                () -> EndpointNormalizer.endpoint("http://api.example.test/v1", "models"));
    }

    @Test
    public void bearerCredentialsRequireHttpsExceptOnLoopback() {
        EndpointNormalizer.requireCredentialSafeTransport(
                "https://192.168.1.20/v1/audio/transcriptions",
                "secret");
        EndpointNormalizer.requireCredentialSafeTransport(
                "http://127.0.0.1:11434/v1/audio/transcriptions",
                "secret");
        EndpointNormalizer.requireCredentialSafeTransport(
                "http://192.168.1.20:8000/v1/audio/transcriptions",
                "");

        assertThrows(
                IllegalArgumentException.class,
                () -> EndpointNormalizer.requireCredentialSafeTransport(
                        "http://192.168.1.20:8000/v1/audio/transcriptions",
                        "secret"));
    }
}
