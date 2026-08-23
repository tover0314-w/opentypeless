package com.opentypeless.android.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable non-secret configuration shared by provider families.
 *
 * <p>This is deliberately a value-only domain contract. It does not resolve secrets, execute
 * network requests, migrate {@code AppSettings}, choose a recognition route, or persist itself.
 */
public sealed interface ProviderConfig
        permits ProviderConfig.Asr, ProviderConfig.Llm, ProviderConfig.Connector {
    int MAX_ID_CODE_POINTS = 128;
    int MAX_DISPLAY_NAME_CODE_POINTS = 80;
    int MAX_MODEL_ID_CODE_POINTS = 256;
    int MAX_ENDPOINT_CODE_POINTS = 2_048;

    String id();

    String displayName();

    Optional<Endpoint> endpoint();

    Optional<SecretRef> secretRef();

    boolean enabled();

    /** ASR provider configuration. */
    record Asr(
            String id,
            String displayName,
            Optional<Endpoint> endpoint,
            Optional<String> modelId,
            Optional<SecretRef> secretRef,
            boolean enabled) implements ProviderConfig {
        public Asr {
            id = requireProviderId(id);
            displayName = requireDisplayName(displayName);
            endpoint = requireOptional(endpoint, "endpoint");
            modelId = requireModelId(modelId);
            secretRef = requireSecretRef(secretRef, SecretRef.Kind.ASR, endpoint);
        }

        @Override
        public String toString() {
            return providerDescription("ASR", enabled, endpoint, secretRef);
        }
    }

    /** LLM provider configuration. */
    record Llm(
            String id,
            String displayName,
            Optional<Endpoint> endpoint,
            Optional<String> modelId,
            Optional<SecretRef> secretRef,
            boolean enabled) implements ProviderConfig {
        public Llm {
            id = requireProviderId(id);
            displayName = requireDisplayName(displayName);
            endpoint = requireOptional(endpoint, "endpoint");
            modelId = requireModelId(modelId);
            secretRef = requireSecretRef(secretRef, SecretRef.Kind.LLM, endpoint);
        }

        @Override
        public String toString() {
            return providerDescription("LLM", enabled, endpoint, secretRef);
        }
    }

    /** Connector provider configuration; protocol and operation policy remain in ACT-001. */
    record Connector(
            String id,
            String displayName,
            Optional<Endpoint> endpoint,
            Optional<SecretRef> secretRef,
            boolean enabled) implements ProviderConfig {
        public Connector {
            id = requireProviderId(id);
            displayName = requireDisplayName(displayName);
            endpoint = requireOptional(endpoint, "endpoint");
            secretRef = requireSecretRef(secretRef, SecretRef.Kind.CONNECTOR, endpoint);
        }

        @Override
        public String toString() {
            return providerDescription("CONNECTOR", enabled, endpoint, secretRef);
        }
    }

    /** Validated endpoint without credentials, query parameters, or fragments. */
    record Endpoint(String value) {
        public Endpoint {
            value = requireEndpoint(value);
        }

        private URI parsed() {
            try {
                return new URI(value);
            } catch (URISyntaxException impossibleAfterConstruction) {
                throw new IllegalStateException("validated endpoint became invalid");
            }
        }

        boolean isCleartext() {
            return "http".equalsIgnoreCase(parsed().getScheme());
        }

        boolean isLoopback() {
            return isLoopbackHost(parsed().getHost());
        }

        @Override
        public String toString() {
            URI uri = parsed();
            String transport = isLoopbackHost(uri.getHost())
                    ? "loopback"
                    : (isLocalHost(uri.getHost()) ? "local" : "remote");
            return "Endpoint{scheme=" + uri.getScheme().toLowerCase(Locale.ROOT)
                    + ", transport=" + transport + ", value=<redacted>}";
        }
    }

    private static String requireProviderId(String value) {
        String safe = Objects.requireNonNull(value, "id");
        if (safe.isEmpty() || safe.length() > MAX_ID_CODE_POINTS) {
            throw new IllegalArgumentException("provider id is outside its bound");
        }
        for (int index = 0; index < safe.length(); index++) {
            char character = safe.charAt(index);
            boolean lower = character >= 'a' && character <= 'z';
            boolean allowed = lower
                    || (index > 0 && character >= '0' && character <= '9')
                    || (index > 0 && (character == '.' || character == '_' || character == '-'));
            if (!allowed) {
                throw new IllegalArgumentException("provider id has an invalid shape");
            }
        }
        return safe;
    }

    private static String requireDisplayName(String value) {
        return requireBoundedText(
                value,
                "displayName",
                MAX_DISPLAY_NAME_CODE_POINTS,
                false);
    }

    private static Optional<String> requireModelId(Optional<String> value) {
        Optional<String> safe = requireOptional(value, "modelId");
        return safe.map(model -> requireBoundedText(
                model,
                "modelId",
                MAX_MODEL_ID_CODE_POINTS,
                false));
    }

    private static Optional<SecretRef> requireSecretRef(
            Optional<SecretRef> value,
            SecretRef.Kind expectedKind,
            Optional<Endpoint> endpoint) {
        Optional<SecretRef> safe = requireOptional(value, "secretRef");
        if (safe.isEmpty()) return safe;
        SecretRef reference = safe.orElseThrow();
        if (reference.kind() != expectedKind) {
            throw new IllegalArgumentException("secret reference kind does not match provider kind");
        }
        Endpoint target = endpoint.orElseThrow(
                () -> new IllegalArgumentException("a secret reference requires an endpoint"));
        if (target.isCleartext() && !target.isLoopback()) {
            throw new IllegalArgumentException(
                    "cleartext credentials are allowed only on loopback endpoints");
        }
        return safe;
    }

    private static <T> Optional<T> requireOptional(Optional<T> value, String name) {
        return Objects.requireNonNull(value, name);
    }

    private static String requireEndpoint(String value) {
        String safe = requireBoundedText(
                value,
                "endpoint",
                MAX_ENDPOINT_CODE_POINTS,
                false);
        try {
            URI uri = new URI(safe);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean http = scheme != null && "http".equalsIgnoreCase(scheme);
            boolean https = scheme != null && "https".equalsIgnoreCase(scheme);
            if (!uri.isAbsolute() || (!http && !https) || host == null || host.isEmpty()) {
                throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URI");
            }
            if (uri.getRawAuthority() == null || uri.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException("endpoint authority has an invalid shape");
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(
                        "endpoint must not contain credentials, query, or fragment");
            }
            if (uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65_535) {
                throw new IllegalArgumentException("endpoint port is outside its bound");
            }
            rejectDotSegments(uri.getRawPath());
            rejectDotSegments(uri.getPath());
            rejectDecodedPathControls(uri.getPath());
            if (http && !isLocalHost(host)) {
                throw new IllegalArgumentException(
                        "plain HTTP is allowed only for loopback or explicit local hosts");
            }
            return safe;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("endpoint URI is invalid", error);
        }
    }

    private static void rejectDotSegments(String path) {
        if (path == null || path.isEmpty()) return;
        for (String segment : path.split("/", -1)) {
            if (".".equalsIgnoreCase(segment)
                    || "..".equalsIgnoreCase(segment)
                    || "%2e".equalsIgnoreCase(segment)
                    || "%2e%2e".equalsIgnoreCase(segment)) {
                throw new IllegalArgumentException("endpoint path must not contain dot segments");
            }
        }
    }

    private static void rejectDecodedPathControls(String path) {
        if (path == null) return;
        for (int offset = 0; offset < path.length(); ) {
            int codePoint = path.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException("endpoint path contains a control character");
            }
            offset += Character.charCount(codePoint);
        }
    }

    private static String requireBoundedText(
            String value,
            String name,
            int maximumCodePoints,
            boolean allowEmpty) {
        String safe = Objects.requireNonNull(value, name);
        if ((!allowEmpty && safe.isEmpty()) || safe.length() > maximumCodePoints * 2) {
            throw new IllegalArgumentException(name + " is outside its bound");
        }
        requireWellFormedUtf16(safe, name);
        if (safe.codePointCount(0, safe.length()) > maximumCodePoints) {
            throw new IllegalArgumentException(name + " is outside its bound");
        }
        if (!safe.equals(safe.strip())) {
            throw new IllegalArgumentException(name + " must not have surrounding whitespace");
        }
        for (int offset = 0; offset < safe.length(); ) {
            int codePoint = safe.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException(name + " contains a control character");
            }
            offset += Character.charCount(codePoint);
        }
        return safe;
    }

    private static void requireWellFormedUtf16(String value, String name) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(name + " contains malformed UTF-16");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(name + " contains malformed UTF-16");
            }
        }
    }

    private static boolean isLocalHost(String host) {
        if (host == null) return false;
        String value = normalizedHost(host);
        if (isLoopbackHost(value)
                || value.endsWith(".local")
                || value.startsWith("fe80:")
                || (value.contains(":") && (value.startsWith("fc") || value.startsWith("fd")))) {
            return true;
        }
        int[] address = ipv4(value);
        if (address == null) return false;
        return address[0] == 10
                || (address[0] == 169 && address[1] == 254)
                || (address[0] == 172 && address[1] >= 16 && address[1] <= 31)
                || (address[0] == 192 && address[1] == 168);
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String value = normalizedHost(host);
        if ("localhost".equals(value) || value.endsWith(".localhost") || "::1".equals(value)) {
            return true;
        }
        int[] address = ipv4(value);
        return address != null && address[0] == 127;
    }

    private static String normalizedHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static int[] ipv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] address = new int[4];
        try {
            for (int index = 0; index < parts.length; index++) {
                if (parts[index].isEmpty()) return null;
                int octet = Integer.parseInt(parts[index]);
                if (octet < 0 || octet > 255) return null;
                address[index] = octet;
            }
            return address;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String providerDescription(
            String kind,
            boolean enabled,
            Optional<Endpoint> endpoint,
            Optional<SecretRef> secretRef) {
        String transport = endpoint.map(value -> value.isCleartext() ? "HTTP" : "HTTPS")
                .orElse("NONE");
        return "ProviderConfig." + kind
                + "{enabled=" + enabled
                + ", endpoint=" + transport
                + ", secretRef=" + (secretRef.isPresent() ? "PRESENT" : "ABSENT")
                + ", details=<redacted>}";
    }
}
