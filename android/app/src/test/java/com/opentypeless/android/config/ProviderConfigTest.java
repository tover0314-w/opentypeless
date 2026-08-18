package com.opentypeless.android.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;

public final class ProviderConfigTest {
    private static final ProviderConfig.Endpoint HTTPS =
            new ProviderConfig.Endpoint("https://api.example.test/v1");
    private static final SecretRef ASR_SECRET =
            new SecretRef(SecretRef.Kind.ASR, "sec_asr0123456789abc");
    private static final SecretRef LLM_SECRET =
            new SecretRef(SecretRef.Kind.LLM, "sec_llm0123456789abc");
    private static final SecretRef CONNECTOR_SECRET =
            new SecretRef(SecretRef.Kind.CONNECTOR, "sec_connector0123456");

    @Test
    public void providerFamilyIsClosedImmutableAndHasExactRecordShapes() {
        assertTrue(ProviderConfig.class.isSealed());
        assertEquals(
                Set.of(
                        ProviderConfig.Asr.class,
                        ProviderConfig.Llm.class,
                        ProviderConfig.Connector.class),
                Set.of(ProviderConfig.class.getPermittedSubclasses()));

        assertComponents(
                ProviderConfig.Asr.class,
                new String[]{"id", "displayName", "endpoint", "modelId", "secretRef", "enabled"},
                new Class<?>[]{
                        String.class,
                        String.class,
                        Optional.class,
                        Optional.class,
                        Optional.class,
                        boolean.class});
        assertComponents(
                ProviderConfig.Llm.class,
                new String[]{"id", "displayName", "endpoint", "modelId", "secretRef", "enabled"},
                new Class<?>[]{
                        String.class,
                        String.class,
                        Optional.class,
                        Optional.class,
                        Optional.class,
                        boolean.class});
        assertComponents(
                ProviderConfig.Connector.class,
                new String[]{"id", "displayName", "endpoint", "secretRef", "enabled"},
                new Class<?>[]{
                        String.class,
                        String.class,
                        Optional.class,
                        Optional.class,
                        boolean.class});
        assertComponents(
                ProviderConfig.Endpoint.class,
                new String[]{"value"},
                new Class<?>[]{String.class});

        for (Class<?> type : new Class<?>[]{
                ProviderConfig.class,
                ProviderConfig.Asr.class,
                ProviderConfig.Llm.class,
                ProviderConfig.Connector.class,
                ProviderConfig.Endpoint.class}) {
            assertFalse(Serializable.class.isAssignableFrom(type));
            if (type != ProviderConfig.class) assertTrue(Modifier.isFinal(type.getModifiers()));
            for (Class<?> implemented : type.getInterfaces()) {
                assertFalse(implemented.getName().startsWith("android."));
            }
        }
    }

    @Test
    public void createsEveryVariantWithoutConflatingSecretOrModelDomains() {
        ProviderConfig.Asr asr = new ProviderConfig.Asr(
                "asr.primary",
                "Primary ASR",
                Optional.of(HTTPS),
                Optional.of("model-a"),
                Optional.of(ASR_SECRET),
                true);
        ProviderConfig.Llm llm = new ProviderConfig.Llm(
                "llm_primary",
                "Primary LLM",
                Optional.of(HTTPS),
                Optional.of("model-b"),
                Optional.of(LLM_SECRET),
                false);
        ProviderConfig.Connector connector = new ProviderConfig.Connector(
                "connector-primary",
                "Primary Connector",
                Optional.of(HTTPS),
                Optional.of(CONNECTOR_SECRET),
                true);
        ProviderConfig.Asr offline = new ProviderConfig.Asr(
                "asr.offline",
                "Offline ASR",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                true);

        assertEquals("asr.primary", asr.id());
        assertEquals(Optional.of("model-a"), asr.modelId());
        assertEquals(Optional.of(ASR_SECRET), asr.secretRef());
        assertTrue(asr.enabled());
        assertEquals("llm_primary", llm.id());
        assertEquals(Optional.of("model-b"), llm.modelId());
        assertFalse(llm.enabled());
        assertEquals(Optional.of(CONNECTOR_SECRET), connector.secretRef());
        assertEquals(Optional.empty(), offline.endpoint());
        assertEquals(Optional.empty(), offline.secretRef());
    }

    @Test
    public void enforcesProviderIdDisplayNameAndModelBoundsWithoutNormalization() {
        String maximumId = "a" + "b".repeat(ProviderConfig.MAX_ID_CODE_POINTS - 1);
        String emoji = "\uD83D\uDE00";
        String maximumDisplay = emoji.repeat(ProviderConfig.MAX_DISPLAY_NAME_CODE_POINTS);
        String maximumModel = emoji.repeat(ProviderConfig.MAX_MODEL_ID_CODE_POINTS);

        assertEquals(maximumId, asr(maximumId, maximumDisplay, maximumModel).id());
        assertEquals(maximumDisplay, asr(maximumId, maximumDisplay, maximumModel).displayName());
        assertEquals(Optional.of(maximumModel), asr(maximumId, maximumDisplay, maximumModel).modelId());

        for (String invalidId : new String[]{
                "", "1provider", "Provider", "provider/one", "provider one", " provider"}) {
            assertInvalid(() -> asr(invalidId, "Display", "model"));
        }
        assertInvalid(() -> asr(maximumId + "c", "Display", "model"));
        assertInvalid(() -> asr("provider", "", "model"));
        assertInvalid(() -> asr("provider", " Display", "model"));
        assertInvalid(() -> asr("provider", "Display ", "model"));
        assertInvalid(() -> asr("provider", "Bad\nDisplay", "model"));
        assertInvalid(() -> asr("provider", maximumDisplay + emoji, "model"));
        assertInvalid(() -> asr("provider", "Bad\uD800Display", "model"));
        assertInvalid(() -> asr("provider", "Display", ""));
        assertInvalid(() -> asr("provider", "Display", " model"));
        assertInvalid(() -> asr("provider", "Display", maximumModel + emoji));
        assertInvalid(() -> asr("provider", "Display", "bad\uDC00model"));
    }

    @Test
    public void endpointAcceptsHttpsAndExplicitLocalCleartextAtItsExactBound() {
        String prefix = "https://example.test/";
        String maximum = prefix + "a".repeat(ProviderConfig.MAX_ENDPOINT_CODE_POINTS - prefix.length());

        assertEquals(maximum, new ProviderConfig.Endpoint(maximum).value());
        for (String endpoint : new String[]{
                "https://example.test",
                "HTTPS://example.test:443/v1",
                "http://localhost:8080/v1",
                "http://worker.local/v1",
                "http://10.0.0.1/v1",
                "http://172.31.255.254/v1",
                "http://192.168.1.2/v1",
                "http://169.254.1.1/v1",
                "http://127.9.8.7/v1",
                "http://[::1]:8080/v1",
                "http://[fd00::1]/v1",
                "http://[fe80::1]/v1"}) {
            assertEquals(endpoint, new ProviderConfig.Endpoint(endpoint).value());
        }
        assertInvalid(() -> new ProviderConfig.Endpoint(maximum + "a"));
    }

    @Test
    public void endpointRejectsUnsafeAmbiguousOrUnboundedUris() {
        for (String endpoint : new String[]{
                "",
                " https://example.test",
                "https://example.test ",
                "example.test/v1",
                "ftp://example.test/v1",
                "http://example.test/v1",
                "http://8.8.8.8/v1",
                "https://user:pass@example.test/v1",
                "https://example.test/v1?key=value",
                "https://example.test/v1#fragment",
                "https://example.test:0/v1",
                "https://example.test:65536/v1",
                "https://example.test:/v1",
                "https://example.test/./v1",
                "https://example.test/a/../v1",
                "https://example.test/%2e%2e/v1",
                "https://example.test/%0d%0aheader",
                "https://exa mple.test/v1"}) {
            assertInvalid(() -> new ProviderConfig.Endpoint(endpoint));
        }
        assertThrows(NullPointerException.class, () -> new ProviderConfig.Endpoint(null));
    }

    @Test
    public void secretReferenceMustMatchVariantAndUseCredentialSafeTransport() {
        ProviderConfig.Endpoint loopback =
                new ProviderConfig.Endpoint("http://localhost:8080/v1");
        ProviderConfig.Endpoint privateLan =
                new ProviderConfig.Endpoint("http://192.168.1.10/v1");

        assertEquals(Optional.of(ASR_SECRET), new ProviderConfig.Asr(
                "asr.loopback",
                "Loopback ASR",
                Optional.of(loopback),
                Optional.empty(),
                Optional.of(ASR_SECRET),
                true).secretRef());
        assertEquals(Optional.empty(), new ProviderConfig.Connector(
                "connector.lan",
                "LAN Connector",
                Optional.of(privateLan),
                Optional.empty(),
                true).secretRef());

        assertInvalid(() -> new ProviderConfig.Asr(
                "asr.no-endpoint", "ASR", Optional.empty(), Optional.empty(),
                Optional.of(ASR_SECRET), true));
        assertInvalid(() -> new ProviderConfig.Asr(
                "asr.wrong-kind", "ASR", Optional.of(HTTPS), Optional.empty(),
                Optional.of(LLM_SECRET), true));
        assertInvalid(() -> new ProviderConfig.Llm(
                "llm.wrong-kind", "LLM", Optional.of(HTTPS), Optional.empty(),
                Optional.of(ASR_SECRET), true));
        assertInvalid(() -> new ProviderConfig.Connector(
                "connector.wrong-kind", "Connector", Optional.of(HTTPS),
                Optional.of(ASR_SECRET), true));
        assertInvalid(() -> new ProviderConfig.Asr(
                "asr.lan-secret", "ASR", Optional.of(privateLan), Optional.empty(),
                Optional.of(ASR_SECRET), true));
    }

    @Test
    public void rejectsNullOptionalContainersInsteadOfTreatingThemAsAbsence() {
        assertThrows(NullPointerException.class, () -> new ProviderConfig.Asr(
                "asr.null-endpoint", "ASR", null, Optional.empty(), Optional.empty(), true));
        assertThrows(NullPointerException.class, () -> new ProviderConfig.Asr(
                "asr.null-model", "ASR", Optional.empty(), null, Optional.empty(), true));
        assertThrows(NullPointerException.class, () -> new ProviderConfig.Asr(
                "asr.null-secret", "ASR", Optional.empty(), Optional.empty(), null, true));
        assertThrows(NullPointerException.class, () -> new ProviderConfig.Connector(
                "connector.null", "Connector", Optional.empty(), null, true));
    }

    @Test
    public void everyStringRepresentationRedactsIdentityMetadataEndpointAndSecret() {
        String endpoint = "https://private.example.test/hidden/path";
        String opaqueId = "sec_asr0123456789abc";
        ProviderConfig.Asr config = new ProviderConfig.Asr(
                "asr.private",
                "Private Display",
                Optional.of(new ProviderConfig.Endpoint(endpoint)),
                Optional.of("private-model"),
                Optional.of(new SecretRef(SecretRef.Kind.ASR, opaqueId)),
                true);

        String rendered = config.toString();
        assertEquals(
                "ProviderConfig.ASR{enabled=true, endpoint=HTTPS, secretRef=PRESENT, details=<redacted>}",
                rendered);
        for (String secret : new String[]{
                "asr.private", "Private Display", "private-model", endpoint,
                "private.example.test", opaqueId}) {
            assertFalse(rendered.contains(secret));
        }

        String renderedEndpoint = config.endpoint().orElseThrow().toString();
        assertEquals(
                "Endpoint{scheme=https, transport=remote, value=<redacted>}",
                renderedEndpoint);
        assertFalse(renderedEndpoint.contains("private.example.test"));
        assertFalse(renderedEndpoint.contains("hidden"));
    }

    private static ProviderConfig.Asr asr(String id, String display, String model) {
        return new ProviderConfig.Asr(
                id,
                display,
                Optional.of(HTTPS),
                Optional.of(model),
                Optional.empty(),
                true);
    }

    private static void assertComponents(
            Class<?> type,
            String[] expectedNames,
            Class<?>[] expectedTypes) {
        assertTrue(type.isRecord());
        RecordComponent[] components = type.getRecordComponents();
        assertEquals(expectedNames.length, components.length);
        for (int index = 0; index < components.length; index++) {
            assertEquals(expectedNames[index], components[index].getName());
            assertEquals(expectedTypes[index], components[index].getType());
        }
    }

    private static void assertInvalid(Runnable action) {
        assertThrows(IllegalArgumentException.class, action::run);
    }
}
