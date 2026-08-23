package com.opentypeless.android.net;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class EndpointNormalizer {
    private EndpointNormalizer() {}

    public static String endpoint(String baseUrl, String relativePath) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Base URL is required");
        try {
            URI base = new URI(value);
            String scheme = base.getScheme();
            if ((scheme == null || !(scheme.equals("https") || scheme.equals("http")))
                    || base.getHost() == null
                    || base.getUserInfo() != null
                    || base.getQuery() != null
                    || base.getFragment() != null) {
                throw new IllegalArgumentException("Use an http(s) URL without credentials, query, or fragment");
            }
            if (scheme.equals("http") && !isLocalHost(base.getHost())) {
                throw new IllegalArgumentException("Plain HTTP is allowed only for localhost or private LAN hosts");
            }
            String cleanBase = value.replaceAll("/+$", "");
            String cleanPath = relativePath.replaceAll("^/+", "");
            return cleanBase + "/" + cleanPath;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Base URL is invalid", error);
        }
    }

    /** Never place a bearer credential on a cleartext LAN transport. Loopback stays usable. */
    public static void requireCredentialSafeTransport(String endpoint, String apiKey) {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) return;
        try {
            URI uri = new URI(endpoint == null ? "" : endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !isLoopbackHost(uri.getHost())) {
                throw new IllegalArgumentException(
                        "API keys require HTTPS except on loopback; leave the key empty for plain HTTP LAN self-hosting");
            }
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Base URL is invalid", error);
        }
    }

    /**
     * Validates the official DashScope realtime endpoint before attaching a bearer credential.
     * This deliberately rejects look-alike hosts so a pasted URL cannot exfiltrate the API key.
     */
    public static String dashScopeWebSocket(String value) {
        String endpoint = value == null ? "" : value.trim();
        if (endpoint.isEmpty()) {
            throw new IllegalArgumentException("DashScope WebSocket URL is required");
        }
        try {
            URI uri = new URI(endpoint);
            String host = uri.getHost();
            boolean officialHost = host != null
                    && (host.equalsIgnoreCase("dashscope.aliyuncs.com")
                            || host.toLowerCase(Locale.ROOT)
                                    .matches("[a-z0-9-]+\\.cn-beijing\\.maas\\.aliyuncs\\.com"));
            if (!"wss".equalsIgnoreCase(uri.getScheme())
                    || !officialHost
                    || uri.getPort() != -1
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !"/api-ws/v1/inference".equals(uri.getPath())) {
                throw new IllegalArgumentException(
                        "Use the official DashScope WSS inference URL without credentials, query, or fragment");
            }
            return uri.toASCIIString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("DashScope WebSocket URL is invalid", error);
        }
    }

    private static boolean isLocalHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.equals("localhost") || value.endsWith(".localhost") || value.endsWith(".local")
                || value.equals("::1") || value.startsWith("fe80:")
                || (value.contains(":") && (value.startsWith("fc") || value.startsWith("fd")))) {
            return true;
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) return false;
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            for (String part : parts) {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) return false;
            }
            return first == 10
                    || first == 127
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.equals("localhost") || value.endsWith(".localhost") || value.equals("::1")) {
            return true;
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) return false;
        try {
            if (Integer.parseInt(parts[0]) != 127) return false;
            for (String part : parts) {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) return false;
            }
            return true;
        } catch (NumberFormatException error) {
            return false;
        }
    }
}
