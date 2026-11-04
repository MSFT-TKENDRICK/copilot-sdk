/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.CopilotExperimental;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.rpc.SessionProviderGetEndpointParams;
import com.github.copilot.generated.rpc.SessionProviderGetEndpointResult;

/**
 * The {@code session.decisions} API group: calls OpenAI's Decisions API
 * ({@code POST {baseUrl}/decisions}) directly against the session's provider
 * endpoint. Obtain it with {@link CopilotSession#getDecisions()}.
 * <p>
 * The runtime must run with {@code COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true}.
 * Redirects are never followed. Error messages and bodies never contain
 * credentials. Cancelling the future returned by {@link #create} aborts the
 * in-flight request; the future then reports a {@link CancellationException},
 * never a {@link DecisionsException}.
 *
 * @since 1.0.0
 */
@CopilotExperimental
public final class SessionDecisions {

    static final int MAX_ERROR_BODY_BYTES = 64 * 1024;
    static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    static final String GATE_ENV = "COPILOT_ALLOW_GET_PROVIDER_ENDPOINT";

    private static final int CHUNK_BYTES = 8 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Function<String, CompletableFuture<SessionProviderGetEndpointResult>> resolveEndpoint;

    /**
     * Creates the API for a session. Prefer {@link CopilotSession#getDecisions()}.
     *
     * @param session
     *            the session whose provider endpoint is used
     */
    public SessionDecisions(CopilotSession session) {
        Objects.requireNonNull(session, "session");
        this.resolveEndpoint = modelId -> modelId == null
                ? session.getRpc().provider.getEndpoint()
                : session.getRpc().provider.getEndpoint(new SessionProviderGetEndpointParams(null, modelId));
    }

    SessionDecisions(Function<String, CompletableFuture<SessionProviderGetEndpointResult>> resolveEndpoint) {
        this.resolveEndpoint = Objects.requireNonNull(resolveEndpoint, "resolveEndpoint");
    }

    /**
     * Calls the Decisions API with default options.
     *
     * @param request
     *            the request
     * @return a future completing with the result, or exceptionally with a
     *         {@link DecisionsException}
     * @throws IllegalArgumentException
     *             if {@code request.model} is missing
     * @see #create(DecisionsRequest, DecisionsOptions)
     */
    public CompletableFuture<DecisionsResult> create(DecisionsRequest request) {
        return create(request, null);
    }

    /**
     * Calls the Decisions API.
     * <p>
     * The session's provider endpoint is resolved on every call. When the API
     * returns 401 or 403, the endpoint is resolved once more and the request is
     * retried once, only if the effective credentials or URL changed.
     *
     * @param request
     *            the request
     * @param options
     *            per-call options, or {@code null}
     * @return a future completing with the result, or exceptionally with a
     *         {@link DecisionsException}
     * @throws IllegalArgumentException
     *             if {@code request.model} is missing, or the supplied
     *             {@link HttpClient} follows redirects
     */
    public CompletableFuture<DecisionsResult> create(DecisionsRequest request, DecisionsOptions options) {
        Objects.requireNonNull(request, "request");
        if (request.getModel() == null || request.getModel().isEmpty()) {
            throw new IllegalArgumentException("DecisionsRequest.model is required.");
        }
        DecisionsOptions opts = options == null ? new DecisionsOptions() : options;
        HttpClient client = opts.getHttpClient();
        if (client == null) {
            client = DefaultClient.INSTANCE;
        } else if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException(
                    "DecisionsOptions.httpClient must use HttpClient.Redirect.NEVER; the Decisions API never follows redirects.");
        }
        return new Call(request, opts, client).start();
    }

    private static final class DefaultClient {
        static final HttpClient INSTANCE = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static final class Readers {
        private static final AtomicInteger COUNTER = new AtomicInteger();
        static final Executor EXECUTOR = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "copilot-decisions-reader-" + COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Bytes read under a limit.
     *
     * @param bytes
     *            the bytes read (at most the limit)
     * @param exceeded
     *            whether the stream held more data
     */
    record Bounded(byte[] bytes, boolean exceeded) {
    }

    /**
     * Reads at most {@code limit} bytes. {@code exceeded} is true only when the
     * stream held more data than the limit.
     */
    static Bounded readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[CHUNK_BYTES];
        int total = 0;
        boolean exceeded = false;
        for (;;) {
            int n = in.read(buffer);
            if (n < 0) {
                break;
            }
            int remaining = limit - total;
            if (n > remaining) {
                out.write(buffer, 0, remaining);
                exceeded = true;
                break;
            }
            out.write(buffer, 0, n);
            total += n;
        }
        return new Bounded(out.toByteArray(), exceeded);
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static DecisionsException endpointUnavailable(Throwable cause) {
        String text = cause == null || cause.getMessage() == null ? "" : cause.getMessage();
        String message = text.contains(GATE_ENV)
                ? "session.decisions requires the runtime's provider endpoint API. Set " + GATE_ENV
                        + "=true in the runtime's environment (for in-process runtimes, in the host process environment before the runtime is loaded)."
                : "Could not resolve the session's provider endpoint for the Decisions API.";
        return new DecisionsException(DecisionsErrorKind.ENDPOINT_UNAVAILABLE, message, cause);
    }

    static DecisionsException networkError(String message, Throwable error, List<String> secrets) {
        return new DecisionsException(DecisionsErrorKind.NETWORK, message, DecisionsSanitizedCause.of(error, secrets));
    }

    /**
     * A redacted, bounded error body.
     *
     * @param raw
     *            the decoded (unredacted) body, used only for envelope parsing
     * @param body
     *            the redacted body, at most 64 KiB of UTF-8
     * @param truncated
     *            whether the body was truncated
     */
    record ErrorBody(String raw, String body, boolean truncated) {
    }

    /**
     * Redacts before truncating, so a credential straddling the cap is never
     * partially retained.
     */
    static ErrorBody redactErrorBody(Bounded read, List<String> secrets) {
        String raw = new String(read.bytes(), StandardCharsets.UTF_8);
        String redacted = DecisionsRedaction.redact(raw, secrets);
        byte[] encoded = redacted.getBytes(StandardCharsets.UTF_8);
        // Redaction or lossy decoding can grow the text, so the cap is enforced on the
        // redacted encoding as well as the raw read.
        boolean truncated = read.exceeded() || read.bytes().length > MAX_ERROR_BODY_BYTES
                || encoded.length > MAX_ERROR_BODY_BYTES;
        if (!truncated) {
            return new ErrorBody(raw, redacted, false);
        }
        int cut = Math.min(encoded.length, MAX_ERROR_BODY_BYTES);
        if (cut < encoded.length) {
            // Back off to a UTF-8 boundary: a split trailing character is dropped, never
            // emitted as U+FFFD.
            while (cut > 0 && (encoded[cut] & 0xC0) == 0x80) {
                cut--;
            }
        }
        return new ErrorBody(raw, new String(encoded, 0, cut, StandardCharsets.UTF_8), true);
    }

    private static String stringField(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            return Boolean.toString(value.booleanValue());
        }
        if (value.isNumber()) {
            if (value.isIntegralNumber()) {
                return value.asText();
            }
            double d = value.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e21) {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        return null;
    }

    static DecisionsException httpError(int status, String requestId, ErrorBody errorBody, List<String> secrets) {
        String errorType = null;
        String errorCode = null;
        String errorParam = null;
        String errorMessage = null;
        if (!errorBody.truncated()) {
            JsonNode parsed = null;
            try {
                parsed = MAPPER.readTree(errorBody.raw());
            } catch (JsonProcessingException e) {
                // Non-JSON error body.
            }
            if (parsed != null && parsed.isObject() && parsed.get("error") != null && parsed.get("error").isObject()) {
                JsonNode error = parsed.get("error");
                errorType = redactField(error.get("type"), secrets);
                errorCode = redactField(error.get("code"), secrets);
                errorParam = redactField(error.get("param"), secrets);
                errorMessage = redactField(error.get("message"), secrets);
            }
        }
        String statusText = status >= 300 && status < 400
                ? "HTTP " + status + " (redirects are not followed)"
                : "HTTP " + status;
        String message = "Decisions request failed with " + statusText
                + (errorMessage != null && !errorMessage.isEmpty() ? ": " + errorMessage : ".");
        return new DecisionsException(DecisionsErrorKind.HTTP, message, null, status, errorType, errorCode, errorParam,
                requestId, errorBody.body(), errorBody.truncated());
    }

    private static String redactField(JsonNode value, List<String> secrets) {
        String s = stringField(value);
        return s == null ? null : DecisionsRedaction.redact(s, secrets);
    }

    static JsonNode parseJson(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        JsonNode json = null;
        try {
            json = MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            json = null;
        }
        if (json == null || json.isMissingNode()) {
            throw new DecisionsException(DecisionsErrorKind.INVALID_RESPONSE,
                    "The Decisions API returned a response that is not valid JSON.");
        }
        return json;
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        return CompletableFuture.failedFuture(error);
    }

    /** One {@code create} invocation. */
    private final class Call {

        private final DecisionsRequest request;
        private final DecisionsOptions options;
        private final HttpClient client;
        private final CompletableFuture<DecisionsResult> result = new CompletableFuture<>();
        private final Object lock = new Object();
        private CompletableFuture<?> inFlight;
        private InputStream openStream;

        Call(DecisionsRequest request, DecisionsOptions options, HttpClient client) {
            this.request = request;
            this.options = options;
            this.client = client;
        }

        CompletableFuture<DecisionsResult> start() {
            // Any completion of the returned future from outside (cancel,
            // orTimeout, completeExceptionally) aborts in-flight I/O.
            result.whenComplete((r, e) -> abort());
            CompletableFuture<DecisionsResult> pipeline;
            try {
                pipeline = run();
            } catch (RuntimeException e) {
                pipeline = failed(e);
            }
            pipeline.whenComplete((r, e) -> {
                if (e == null) {
                    result.complete(r);
                } else {
                    result.completeExceptionally(unwrap(e));
                }
            });
            return result;
        }

        private boolean aborted() {
            return result.isDone();
        }

        private void abort() {
            CompletableFuture<?> pending;
            InputStream stream;
            synchronized (lock) {
                pending = inFlight;
                stream = openStream;
                inFlight = null;
                openStream = null;
            }
            if (pending != null) {
                pending.cancel(true);
            }
            closeQuietly(stream);
        }

        private void track(CompletableFuture<?> future) {
            synchronized (lock) {
                inFlight = future;
            }
            if (aborted()) {
                future.cancel(true);
            }
        }

        private void track(InputStream stream) {
            synchronized (lock) {
                openStream = stream;
            }
            if (aborted()) {
                closeQuietly(stream);
            }
        }

        private CompletableFuture<DecisionsResult> run() {
            return resolve().thenCompose(endpoint -> {
                DecisionsEndpointPlanner.Plan plan = DecisionsEndpointPlanner.plan(endpoint, request.getModel());
                byte[] body;
                try {
                    body = MAPPER.writeValueAsBytes(DecisionsWire.serialize(request));
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("DecisionsRequest could not be serialized.", e);
                }
                return send(plan, body).thenCompose(response -> {
                    int status = response.statusCode();
                    if (status == 401 || status == 403) {
                        return readHttpError(response, plan).thenCompose(original -> retry(plan, body, original));
                    }
                    return finish(response, plan);
                });
            });
        }

        private CompletableFuture<DecisionsResult> retry(DecisionsEndpointPlanner.Plan plan, byte[] body,
                DecisionsException original) {
            return resolve().thenCompose(endpoint -> {
                DecisionsEndpointPlanner.Plan refreshed;
                try {
                    refreshed = DecisionsEndpointPlanner.plan(endpoint, request.getModel());
                } catch (RuntimeException e) {
                    throw original;
                }
                if (refreshed.fingerprint().equals(plan.fingerprint())) {
                    throw original;
                }
                return send(refreshed, body).thenCompose(response -> finish(response, refreshed));
            });
        }

        private CompletableFuture<DecisionsResult> finish(HttpResponse<InputStream> response,
                DecisionsEndpointPlanner.Plan plan) {
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                return readHttpError(response, plan).thenApply(error -> {
                    throw error;
                });
            }
            return read(response, MAX_RESPONSE_BYTES, "The Decisions response could not be read.", plan)
                    .thenApply(read -> {
                        if (read.exceeded()) {
                            throw new DecisionsException(DecisionsErrorKind.INVALID_RESPONSE,
                                    "The Decisions API response exceeded " + MAX_RESPONSE_BYTES + " bytes.");
                        }
                        return DecisionsWire.parseResult(parseJson(read.bytes()));
                    });
        }

        private CompletableFuture<DecisionsException> readHttpError(HttpResponse<InputStream> response,
                DecisionsEndpointPlanner.Plan plan) {
            int window = MAX_ERROR_BODY_BYTES + DecisionsRedaction.maxSecretBytes(plan.secrets());
            String requestId = response.headers().firstValue("x-request-id").orElse(null);
            return read(response, window, "The Decisions error response could not be read.", plan)
                    .thenApply(read -> httpError(response.statusCode(), requestId,
                            redactErrorBody(read, plan.secrets()), plan.secrets()));
        }

        private CompletableFuture<Bounded> read(HttpResponse<InputStream> response, int limit, String failureMessage,
                DecisionsEndpointPlanner.Plan plan) {
            InputStream stream = response.body();
            if (stream == null) {
                return CompletableFuture.completedFuture(new Bounded(new byte[0], false));
            }
            track(stream);
            CompletableFuture<Bounded> reading = new CompletableFuture<>();
            try {
                Readers.EXECUTOR.execute(() -> {
                    try {
                        reading.complete(readLimited(stream, limit));
                    } catch (IOException | RuntimeException e) {
                        reading.completeExceptionally(e);
                    } finally {
                        closeQuietly(stream);
                    }
                });
            } catch (RuntimeException e) {
                closeQuietly(stream);
                reading.completeExceptionally(e);
            }
            return reading.handle((read, error) -> {
                if (aborted()) {
                    throw new CancellationException();
                }
                if (error != null) {
                    throw networkError(failureMessage, unwrap(error), plan.secrets());
                }
                return read;
            });
        }

        private CompletableFuture<SessionProviderGetEndpointResult> resolve() {
            if (aborted()) {
                return failed(new CancellationException());
            }
            CompletableFuture<SessionProviderGetEndpointResult> pending;
            try {
                pending = resolveEndpoint.apply(options.getEndpointModelId());
                if (pending == null) {
                    throw new IllegalStateException("The provider endpoint resolver returned no result.");
                }
            } catch (RuntimeException e) {
                return failed(endpointUnavailable(e));
            }
            return pending.handleAsync((endpoint, error) -> {
                if (aborted()) {
                    throw new CancellationException();
                }
                if (error != null) {
                    throw endpointUnavailable(unwrap(error));
                }
                return endpoint;
            }, Readers.EXECUTOR);
        }

        private CompletableFuture<HttpResponse<InputStream>> send(DecisionsEndpointPlanner.Plan plan, byte[] body) {
            if (aborted()) {
                return failed(new CancellationException());
            }
            CompletableFuture<HttpResponse<InputStream>> pending;
            HttpRequest httpRequest;
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(plan.url()))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                if (options.getTimeout() != null) {
                    builder.timeout(options.getTimeout());
                }
                plan.headers().forEach(builder::header);
                httpRequest = builder.build();
            } catch (IllegalArgumentException e) {
                // The JDK's message can echo raw header values, which may be credentials too
                // short to redact.
                return failed(networkError("The Decisions request could not be sent.",
                        new IllegalArgumentException("java.net.http rejected the request URL or a header value."),
                        plan.secrets()));
            }
            try {
                pending = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            } catch (RuntimeException e) {
                return failed(networkError("The Decisions request could not be sent.", e, plan.secrets()));
            }
            track(pending);
            return pending.handle((response, error) -> {
                if (aborted()) {
                    if (response != null) {
                        closeQuietly(response.body());
                    }
                    throw new CancellationException();
                }
                if (error != null) {
                    throw networkError("The Decisions request could not be sent.", unwrap(error), plan.secrets());
                }
                return response;
            });
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException | RuntimeException e) {
            // Best effort: the stream is being discarded.
        }
    }
}
