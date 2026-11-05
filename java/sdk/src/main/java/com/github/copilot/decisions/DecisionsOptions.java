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

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(600);

    private String endpointModelId;
    private HttpClient httpClient;
    private Duration timeout = DEFAULT_TIMEOUT;

    /**
     * Creates options with the default 600-second total-call timeout.
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
     * SDK client is used. An injected client remains responsible for its TLS policy
     * and for honoring its reported redirect policy.
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
     * Returns the total-call timeout, including endpoint resolution, response
     * bodies, and the optional authentication replay.
     *
     * @return the timeout; {@link Duration#ZERO} disables the SDK deadline
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Sets the total-call timeout. Expiry is reported as
     * {@link DecisionsErrorKind#TIMEOUT}, cancels in-flight HTTP work, and closes
     * acquired response bodies. The deadline is not reset for authentication
     * replay. Caller cancellation remains a native
     * {@link java.util.concurrent.CancellationException}.
     *
     * @param timeout
     *            a positive timeout, {@link Duration#ZERO} to disable it, or
     *            {@code null} to restore the 600-second default
     * @return these options
     * @throws IllegalArgumentException
     *             if the timeout is negative
     */
    public DecisionsOptions setTimeout(Duration timeout) {
        if (timeout != null && timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative.");
        }
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        return this;
    }
}
