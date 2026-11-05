/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.util.Objects;

import com.github.copilot.CopilotExperimental;

/**
 * Error raised by {@link SessionDecisions#create}. Provider diagnostics redact
 * known endpoint credentials and supported encodings, including short
 * authentication credentials. Other header and query values use an
 * eight-Unicode-scalar heuristic. This is not general PII removal and does not
 * cover arbitrary transformations unknown to the SDK. Cancellation is reported
 * with the platform's {@link java.util.concurrent.CancellationException}, not
 * this type.
 * <p>
 * For {@link DecisionsErrorKind#NETWORK} errors the cause is a
 * {@link DecisionsSanitizedCause}; for
 * {@link DecisionsErrorKind#ENDPOINT_UNAVAILABLE} it is a fixed safe stand-in,
 * never the original endpoint RPC failure. SDK timeouts have no cause.
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
    private final Long retryAfterMs;

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
        this(kind, message, cause, status, errorType, errorCode, errorParam, requestId, body, bodyTruncated, null);
    }

    DecisionsException(DecisionsErrorKind kind, String message, Throwable cause, Integer status, String errorType,
            String errorCode, String errorParam, String requestId, String body, Boolean bodyTruncated,
            Long retryAfterMs) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.status = status;
        this.errorType = errorType;
        this.errorCode = errorCode;
        this.errorParam = errorParam;
        this.requestId = requestId;
        this.body = body;
        this.bodyTruncated = bodyTruncated;
        this.retryAfterMs = retryAfterMs;
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
     * Returns the bounded, credential-redacted {@code x-request-id} response
     * header.
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
     * Returns whether the original response exceeded 64 KiB, for
     * {@link DecisionsErrorKind#HTTP} errors. Redaction expansion alone does not
     * set this flag.
     *
     * @return whether the body was truncated, or {@code null}
     */
    public Boolean getBodyTruncated() {
        return bodyTruncated;
    }

    /**
     * Returns the provider's advisory retry delay, rounded up to milliseconds. This
     * does not cause an automatic retry.
     *
     * @return the nonnegative delay for an HTTP error, or {@code null}
     */
    public Long getRetryAfterMs() {
        return retryAfterMs;
    }
}
