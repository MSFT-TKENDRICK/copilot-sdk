/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.net.http.HttpClient;
import java.time.Duration;

import com.github.copilot.CopilotExperimental;

/**
 * Per-call options for {@link SessionDecisions#create}.
 *
 * @since 1.0.0
 */
@CopilotExperimental
public final class DecisionsOptions {

    private String endpointModelId;
    private HttpClient httpClient;
    private Duration timeout;

    /**
     * Creates empty options.
     */
    public DecisionsOptions() {
    }

    /**
     * Returns the model ID passed to the provider endpoint lookup.
     *
     * @return the model ID, or {@code null}
     */
    public String getEndpointModelId() {
        return endpointModelId;
    }

    /**
     * Sets the model ID whose provider endpoint is resolved. When {@code null}, the
     * session's current model is used. Passed verbatim.
     *
     * @param endpointModelId
     *            the model ID
     * @return these options
     */
    public DecisionsOptions setEndpointModelId(String endpointModelId) {
        this.endpointModelId = endpointModelId;
        return this;
    }

    /**
     * Returns the HTTP client override.
     *
     * @return the client, or {@code null}
     */
    public HttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * Sets the HTTP client used for the Decisions request, for example to configure
     * a proxy or TLS. The client must be built with
     * {@link HttpClient.Redirect#NEVER}: redirects are never followed because they
     * could forward credentials to another origin. A client with any other redirect
     * policy is rejected with {@link IllegalArgumentException}, reported through
     * the returned future, before the request is sent. When {@code null}, a shared
     * SDK client is used.
     *
     * @param httpClient
     *            the client
     * @return these options
     */
    public DecisionsOptions setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
        return this;
    }

    /**
     * Returns the per-attempt timeout.
     *
     * @return the timeout, or {@code null}
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Sets the per-attempt timeout until response headers arrive
     * ({@link java.net.http.HttpRequest.Builder#timeout}). A timeout is reported as
     * a {@link DecisionsErrorKind#NETWORK} error. To bound the whole call, use
     * {@link java.util.concurrent.CompletableFuture#orTimeout} or cancel the
     * returned future.
     *
     * @param timeout
     *            a positive timeout, or {@code null} for none
     * @return these options
     * @throws IllegalArgumentException
     *             if the timeout is zero or negative
     */
    public DecisionsOptions setTimeout(Duration timeout) {
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive.");
        }
        this.timeout = timeout;
        return this;
    }
}
