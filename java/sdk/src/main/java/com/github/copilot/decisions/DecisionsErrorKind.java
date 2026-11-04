/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import com.github.copilot.CopilotExperimental;

/**
 * The category of a {@link DecisionsException}.
 *
 * @since 1.0.0
 */
@CopilotExperimental
public enum DecisionsErrorKind {
    /** The session's provider endpoint could not be resolved. */
    ENDPOINT_UNAVAILABLE("endpointUnavailable"),
    /** The provider endpoint has an invalid URL or headers. */
    INVALID_ENDPOINT("invalidEndpoint"),
    /** The provider type does not support the Decisions API. */
    UNSUPPORTED_PROVIDER("unsupportedProvider"),
    /** The provider transport is not supported. */
    UNSUPPORTED_TRANSPORT("unsupportedTransport"),
    /** The provider session token is bound to a different model. */
    MODEL_MISMATCH("modelMismatch"),
    /** The session token and API key use the same header. */
    CREDENTIAL_CONFLICT("credentialConflict"),
    /** The Decisions API returned a non-success HTTP status. */
    HTTP("http"),
    /** The Decisions API returned a response that could not be parsed. */
    INVALID_RESPONSE("invalidResponse"),
    /** The request could not be sent or the response could not be read. */
    NETWORK("network");

    private final String value;

    DecisionsErrorKind(String value) {
        this.value = value;
    }

    /**
     * Returns the cross-SDK name of this kind (for example
     * {@code "endpointUnavailable"}).
     *
     * @return the name
     */
    public String getValue() {
        return value;
    }
}
