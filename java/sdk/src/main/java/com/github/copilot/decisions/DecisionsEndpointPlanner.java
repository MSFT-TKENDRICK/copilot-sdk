/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.copilot.generated.rpc.ProviderEndpointTransport;
import com.github.copilot.generated.rpc.ProviderEndpointType;
import com.github.copilot.generated.rpc.ProviderSessionToken;
import com.github.copilot.generated.rpc.SessionProviderGetEndpointResult;

/**
 * Validates a provider endpoint and turns it into an attempt plan: URL,
 * headers, credential fingerprint, and redaction secrets. Check order is
 * normative.
 */
final class DecisionsEndpointPlanner {

    private static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$");
    private static final Pattern BEARER_PREFIX = Pattern.compile("^[ \\t]*bearer[ \\t]+", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DecisionsEndpointPlanner() {
    }

    /**
     * An attempt plan.
     *
     * @param url
     *            the Decisions URL
     * @param headers
     *            lowercase outbound headers
     * @param fingerprint
     *            the credential fingerprint used for the retry decision
     * @param secrets
     *            redaction variants, longest first
     */
    record Plan(String url, Map<String, String> headers, String fingerprint, List<String> secrets) {
    }

    static DecisionsException invalidEndpoint(String message) {
        return new DecisionsException(DecisionsErrorKind.INVALID_ENDPOINT, message);
    }

    static Plan plan(SessionProviderGetEndpointResult endpoint, String model) {
        if (endpoint == null) {
            throw invalidEndpoint("The provider endpoint is missing.");
        }
        ProviderEndpointType type = endpoint.type();
        if (type != ProviderEndpointType.OPENAI && type != ProviderEndpointType.AZURE) {
            throw new DecisionsException(DecisionsErrorKind.UNSUPPORTED_PROVIDER, "The session's provider type '"
                    + (type == null ? "null" : type.getValue()) + "' does not support the Decisions API.");
        }
        ProviderEndpointTransport transport = endpoint.transport();
        if (transport != null && transport != ProviderEndpointTransport.HTTP) {
            throw new DecisionsException(DecisionsErrorKind.UNSUPPORTED_TRANSPORT, "The session's provider transport '"
                    + transport.getValue() + "' is not supported by the Decisions API.");
        }
        ProviderSessionToken token = endpoint.sessionToken();
        String tokenModel = token == null ? null : token.model();
        if (tokenModel != null && !tokenModel.equals(model)) {
            throw new DecisionsException(DecisionsErrorKind.MODEL_MISMATCH,
                    "The provider session token is bound to model '" + tokenModel + "', but the request uses '" + model
                            + "'. Set endpointModelId or request.model consistently.");
        }
        String url = buildUrl(type, endpoint.baseUrl());
        Map<String, String> headers = buildHeaders(endpoint);
        String tokenHeader = token == null || token.header() == null ? null : token.header().toLowerCase(Locale.ROOT);
        ArrayNode fingerprint = MAPPER.createArrayNode();
        fingerprint.add(url);
        fingerprint.add(headers.get("authorization"));
        fingerprint.add(headers.get("api-key"));
        if (tokenHeader == null) {
            fingerprint.addNull();
        } else {
            fingerprint.addArray().add(tokenHeader).add(headers.get(tokenHeader));
        }
        return new Plan(url, headers, fingerprint.toString(), collectSecrets(endpoint, url));
    }

    static String buildUrl(ProviderEndpointType type, String baseUrl) {
        if (baseUrl == null) {
            throw invalidEndpoint("The provider endpoint base URL is not a valid absolute URL.");
        }
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw invalidEndpoint("The provider endpoint base URL is not a valid absolute URL.");
        }
        if (uri.getScheme() == null || uri.isOpaque() || uri.getHost() == null) {
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (scheme != null && !scheme.equals("http") && !scheme.equals("https")) {
                throw invalidEndpoint("The provider endpoint base URL must use http or https.");
            }
            throw invalidEndpoint("The provider endpoint base URL is not a valid absolute URL.");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw invalidEndpoint("The provider endpoint base URL must use http or https.");
        }
        String authority = uri.getRawAuthority();
        if (uri.getRawUserInfo() != null || (authority != null && authority.indexOf('@') >= 0)) {
            throw invalidEndpoint("The provider endpoint base URL must not contain credentials.");
        }
        StringBuilder out = new StringBuilder();
        out.append(scheme).append("://").append(uri.getHost().toLowerCase(Locale.ROOT));
        int port = uri.getPort();
        if (port >= 0 && !(scheme.equals("http") && port == 80) && !(scheme.equals("https") && port == 443)) {
            out.append(':').append(port);
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        String basePath = path.substring(0, end);
        out.append(basePath);
        if (type == ProviderEndpointType.AZURE && !basePath.toLowerCase(Locale.ROOT).endsWith("/openai/v1")) {
            out.append("/openai/v1/decisions");
        } else {
            out.append("/decisions");
        }
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            out.append('?').append(query);
        }
        return out.toString();
    }

    private static String checkHeader(String name, String value) {
        if (name == null || value == null || !HEADER_NAME.matcher(name).matches() || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
            throw invalidEndpoint("The provider endpoint returned an invalid HTTP header.");
        }
        return DecisionsRedaction.trimOws(value);
    }

    static Map<String, String> buildHeaders(SessionProviderGetEndpointResult endpoint) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (endpoint.headers() != null) {
            for (Map.Entry<String, String> entry : endpoint.headers().entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                String value = checkHeader(entry.getKey(), entry.getValue());
                String lower = entry.getKey().toLowerCase(Locale.ROOT);
                if (lower.equals("content-type") || lower.equals("accept")) {
                    continue;
                }
                String existing = merged.get(lower);
                if (existing != null && !existing.equals(value)) {
                    throw invalidEndpoint(
                            "The provider endpoint returned conflicting values for the same HTTP header.");
                }
                merged.put(lower, value);
            }
        }

        String apiKeyHeader = null;
        String apiKey = endpoint.apiKey();
        if (apiKey != null && !apiKey.isEmpty() && !merged.containsKey("authorization")
                && !merged.containsKey("api-key")) {
            if (endpoint.type() == ProviderEndpointType.AZURE) {
                apiKeyHeader = "api-key";
                merged.put(apiKeyHeader, checkHeader(apiKeyHeader, apiKey));
            } else {
                apiKeyHeader = "authorization";
                merged.put(apiKeyHeader, "Bearer " + checkHeader(apiKeyHeader, apiKey));
            }
        }

        ProviderSessionToken token = endpoint.sessionToken();
        if (token != null) {
            String value = checkHeader(token.header(), token.token());
            String lower = token.header().toLowerCase(Locale.ROOT);
            if (lower.equals(apiKeyHeader)) {
                throw new DecisionsException(DecisionsErrorKind.CREDENTIAL_CONFLICT,
                        "The provider session token uses the same header as the endpoint API key.");
            }
            merged.put(lower, value);
        }

        merged.put("content-type", "application/json");
        merged.put("accept", "application/json");
        return merged;
    }

    static List<String> collectSecrets(SessionProviderGetEndpointResult endpoint, String url) {
        Set<String> raw = new LinkedHashSet<>();
        DecisionsRedaction.addCandidate(raw, endpoint.apiKey());
        if (endpoint.sessionToken() != null) {
            DecisionsRedaction.addCandidate(raw, endpoint.sessionToken().token());
        }
        if (endpoint.headers() != null) {
            for (String value : endpoint.headers().values()) {
                if (value == null) {
                    continue;
                }
                DecisionsRedaction.addCandidate(raw, value);
                DecisionsRedaction.addCandidate(raw, BEARER_PREFIX.matcher(value).replaceFirst(""));
            }
        }
        if (url != null) {
            int q = url.indexOf('?');
            if (q >= 0) {
                for (String value : searchParamValues(url.substring(q + 1))) {
                    DecisionsRedaction.addCandidate(raw, value);
                }
            }
        }
        return DecisionsRedaction.expand(raw);
    }

    /** Values of {@code URLSearchParams} for a raw query string. */
    static List<String> searchParamValues(String query) {
        List<String> values = new java.util.ArrayList<>();
        for (String pair : query.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            values.add(formDecode(eq < 0 ? "" : pair.substring(eq + 1)));
        }
        return values;
    }

    private static String formDecode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        byte[] input = value.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < input.length; i++) {
            byte b = input[i];
            if (b == '+') {
                bytes.write(' ');
            } else if (b == '%' && i + 2 < input.length && hex(input[i + 1]) >= 0 && hex(input[i + 2]) >= 0) {
                bytes.write(hex(input[i + 1]) * 16 + hex(input[i + 2]));
                i += 2;
            } else {
                bytes.write(b);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int hex(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }
}
