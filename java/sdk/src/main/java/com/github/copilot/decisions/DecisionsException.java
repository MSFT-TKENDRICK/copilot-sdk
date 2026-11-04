/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.util.Objects;

import com.github.copilot.CopilotExperimental;

/**
 * Error raised by {@link SessionDecisions#create}. Messages,
 * {@link #getBody()}, and the error fields never contain credentials.
 * Cancellation is reported with the platform's
 * {@link java.util.concurrent.CancellationException}, not this type.
 * <p>
 * For {@link DecisionsErrorKind#NETWORK} errors the cause is a
 * {@link DecisionsSanitizedCause}; for
 * {@link DecisionsErrorKind#ENDPOINT_UNAVAILABLE} it is the endpoint resolution
 * failure.
 *
 * @since 1.0.0
 */
@CopilotExperimental
public final class DecisionsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final DecisionsErrorKind kind;
    private final Integer status;
    private final String errorType;
    private final String errorCode;
    private final String errorParam;
    private final String requestId;
    private final String body;
    private final Boolean bodyTruncated;

    /**
     * Creates an exception with only a kind and message.
     *
     * @param kind
     *            the error kind
     * @param message
     *            the message
     */
    public DecisionsException(DecisionsErrorKind kind, String message) {
        this(kind, message, null, null, null, null, null, null, null, null);
    }

    DecisionsException(DecisionsErrorKind kind, String message, Throwable cause) {
        this(kind, message, cause, null, null, null, null, null, null, null);
    }

    DecisionsException(DecisionsErrorKind kind, String message, Throwable cause, Integer status, String errorType,
            String errorCode, String errorParam, String requestId, String body, Boolean bodyTruncated) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.status = status;
        this.errorType = errorType;
        this.errorCode = errorCode;
        this.errorParam = errorParam;
        this.requestId = requestId;
        this.body = body;
        this.bodyTruncated = bodyTruncated;
    }

    /**
     * Returns the error kind.
     *
     * @return the kind
     */
    public DecisionsErrorKind getKind() {
        return kind;
    }

    /**
     * Returns the HTTP status for {@link DecisionsErrorKind#HTTP} errors.
     *
     * @return the status, or {@code null}
     */
    public Integer getStatus() {
        return status;
    }

    /**
     * Returns the OpenAI error {@code type}, when the error body was parsable.
     *
     * @return the error type, or {@code null}
     */
    public String getErrorType() {
        return errorType;
    }

    /**
     * Returns the OpenAI error {@code code}, when the error body was parsable.
     *
     * @return the error code, or {@code null}
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Returns the OpenAI error {@code param}, when the error body was parsable.
     *
     * @return the error param, or {@code null}
     */
    public String getErrorParam() {
        return errorParam;
    }

    /**
     * Returns the {@code x-request-id} response header.
     *
     * @return the request ID, or {@code null}
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Returns the response body (at most 64 KiB of UTF-8, credentials redacted) for
     * {@link DecisionsErrorKind#HTTP} errors.
     *
     * @return the body, or {@code null}
     */
    public String getBody() {
        return body;
    }

    /**
     * Returns whether {@link #getBody()} was truncated at 64 KiB, for
     * {@link DecisionsErrorKind#HTTP} errors.
     *
     * @return whether the body was truncated, or {@code null}
     */
    public Boolean getBodyTruncated() {
        return bodyTruncated;
    }
}
