/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
 * The built-in HTTP client never follows redirects. Known credentials and their
 * supported encodings are redacted from bounded provider diagnostics; this is
 * not general PII removal. Cancelling the future returned by {@link #create}
 * aborts the in-flight request; the future then reports a
 * {@link CancellationException}, never a {@link DecisionsException}.
 *
 * @since 1.0.0
 */
@CopilotExperimental
public final class SessionDecisions {

    static final int MAX_ERROR_BODY_BYTES = 64 * 1024;
    static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    static final String GATE_ENV = "COPILOT_ALLOW_GET_PROVIDER_ENDPOINT";

    private static final int CHUNK_BYTES = 8 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

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
     *         {@link DecisionsException}, or with an
     *         {@link IllegalArgumentException} if the supplied {@link HttpClient}
     *         follows redirects
     * @throws IllegalArgumentException
     *             if {@code request.model} is missing
     */
    public CompletableFuture<DecisionsResult> create(DecisionsRequest request, DecisionsOptions options) {
        Objects.requireNonNull(request, "request");
        if (request.getModel() == null || request.getModel().isEmpty()) {
            throw new IllegalArgumentException("DecisionsRequest.model is required.");
        }
        long startedNanos = System.nanoTime();
        DecisionsOptions opts = options == null ? new DecisionsOptions() : options;
        Duration timeout = opts.getTimeout();
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative.");
        }
        HttpClient client = opts.getHttpClient();
        if (client == null) {
            client = DefaultClient.INSTANCE;
        }
        return new Call(request, opts.getEndpointModelId(), timeout, startedNanos, client, opts.getHttpClient() != null)
                .start();
    }

    private static final class DefaultClient {
        static final HttpClient INSTANCE = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static final class Readers {
        private static final AtomicInteger COUNTER = new AtomicInteger();
    }

    private static final class Deadlines {
        static final ScheduledThreadPoolExecutor EXECUTOR = createExecutor();

        private static ScheduledThreadPoolExecutor createExecutor() {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
                Thread thread = new Thread(r, "copilot-decisions-deadline");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            executor.setKeepAliveTime(1, TimeUnit.SECONDS);
            executor.allowCoreThreadTimeOut(true);
            return executor;
        }
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
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, (long) limit - total + 1));
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
        String text;
        try {
            text = cause == null || cause.getMessage() == null ? "" : cause.getMessage();
        } catch (RuntimeException e) {
            text = "";
        }
        String message = text.contains(GATE_ENV)
                ? "session.decisions requires the runtime's provider endpoint API. Set " + GATE_ENV
                        + "=true in the runtime's environment (for in-process runtimes, in the host process environment before the runtime is loaded)."
                : "Could not resolve the session's provider endpoint for the Decisions API.";
        return new DecisionsException(DecisionsErrorKind.ENDPOINT_UNAVAILABLE, message, new DecisionsSanitizedCause(
                "ProviderEndpointResolutionFailure", "Provider endpoint resolution failed.", null));
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
     * Uses lookahead only to recognize secrets crossing the original byte cap.
     */
    static ErrorBody redactErrorBody(Bounded read, List<String> secrets) {
        String raw = new String(read.bytes(), StandardCharsets.UTF_8);
        int retained = new String(read.bytes(), 0, DecisionsRedaction.utf8Cut(read.bytes(), MAX_ERROR_BODY_BYTES),
                StandardCharsets.UTF_8).length();
        String redacted = DecisionsRedaction.redactPrefix(raw, retained, secrets);
        boolean truncated = read.exceeded() || read.bytes().length > MAX_ERROR_BODY_BYTES;
        return new ErrorBody(raw, DecisionsRedaction.capUtf8(redacted, MAX_ERROR_BODY_BYTES), truncated);
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
            return value.asText();
        }
        return null;
    }

    static DecisionsException httpError(int status, String requestId, ErrorBody errorBody, List<String> secrets) {
        return httpError(status, requestId, errorBody, secrets, null);
    }

    private static DecisionsException httpError(int status, String requestId, ErrorBody errorBody, List<String> secrets,
            Long retryAfterMs) {
        String errorType = null;
        String errorCode = null;
        String errorParam = null;
        String errorMessage = null;
        if (!errorBody.truncated()) {
            JsonNode parsed = null;
            try {
                parsed = MAPPER.readTree(stripBom(errorBody.raw()));
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
        return new DecisionsException(DecisionsErrorKind.HTTP,
                DecisionsRedaction.capUtf8(message, MAX_ERROR_BODY_BYTES), null, status, errorType, errorCode,
                errorParam, DecisionsRedaction.sanitize(requestId, secrets), errorBody.body(), errorBody.truncated(),
                retryAfterMs);
    }

    private static String redactField(JsonNode value, List<String> secrets) {
        String s = stringField(value);
        return DecisionsRedaction.sanitize(s, secrets);
    }

    static JsonNode parseJson(byte[] bytes) {
        String text = stripBom(new String(bytes, StandardCharsets.UTF_8));
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

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        return CompletableFuture.failedFuture(error);
    }

    private static CancellationException cancelled() {
        return new CancellationException("The Decisions call was cancelled.");
    }

    /**
     * Keeps valid Unicode header values representable for caller-owned transports.
     */
    private static final class CustomRequest extends HttpRequest {
        private final URI uri;
        private final HttpHeaders headers;
        private final byte[] body;

        CustomRequest(DecisionsEndpointPlanner.Plan plan, byte[] body) {
            this.uri = URI.create(plan.url());
            Map<String, List<String>> values = new LinkedHashMap<>();
            plan.headers().forEach((name, value) -> values.put(name, List.of(value)));
            this.headers = HttpHeaders.of(values, (name, value) -> true);
            this.body = body;
        }

        @Override
        public Optional<BodyPublisher> bodyPublisher() {
            return Optional.of(BodyPublishers.ofByteArray(body));
        }

        @Override
        public String method() {
            return "POST";
        }

        @Override
        public Optional<Duration> timeout() {
            return Optional.empty();
        }

        @Override
        public boolean expectContinue() {
            return false;
        }

        @Override
        public URI uri() {
            return uri;
        }

        @Override
        public Optional<HttpClient.Version> version() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }
    }

    /** One {@code create} invocation. */
    private final class Call {

        private final DecisionsRequest request;
        private final String endpointModelId;
        private final long timeoutNanos;
        private final long startedNanos;
        private final HttpClient client;
        private final boolean customClient;
        private final CompletableFuture<DecisionsResult> result = new CompletableFuture<>();
        private final Object lock = new Object();
        private final Set<String> knownSecrets = new LinkedHashSet<>();
        private CompletableFuture<?> inFlight;
        private InputStream openStream;
        private Thread reader;
        private CompletableFuture<Bounded> bodyRead;
        private ScheduledFuture<?> timer;
        private boolean stopped;

        Call(DecisionsRequest request, String endpointModelId, Duration timeout, long startedNanos, HttpClient client,
                boolean customClient) {
            this.request = request;
            this.endpointModelId = endpointModelId;
            this.startedNanos = startedNanos;
            this.client = client;
            this.customClient = customClient;
            long nanos;
            try {
                nanos = timeout.toNanos();
            } catch (ArithmeticException e) {
                nanos = Long.MAX_VALUE;
            }
            this.timeoutNanos = nanos;
        }

        CompletableFuture<DecisionsResult> start() {
            // Any completion of the returned future from outside (cancel,
            // orTimeout, completeExceptionally) aborts in-flight I/O.
            result.whenComplete((r, e) -> abort());
            if (timeoutNanos != 0) {
                synchronized (lock) {
                    timer = Deadlines.EXECUTOR.schedule(() -> complete(null, timeoutError()),
                            Math.max(0, timeoutNanos - (System.nanoTime() - startedNanos)), TimeUnit.NANOSECONDS);
                }
            }
            CompletableFuture<DecisionsResult> pipeline;
            try {
                pipeline = run();
            } catch (RuntimeException e) {
                pipeline = failed(e);
            }
            pipeline.whenComplete((r, e) -> {
                Throwable error = e == null ? null : unwrap(e);
                complete(r, !(error instanceof CancellationException) && expired() ? timeoutError() : error);
            });
            return result;
        }

        private boolean expired() {
            return timeoutNanos != 0 && System.nanoTime() - startedNanos >= timeoutNanos;
        }

        private DecisionsException timeoutError() {
            return new DecisionsException(DecisionsErrorKind.TIMEOUT, "The Decisions call exceeded its SDK deadline.");
        }

        private void requireActive() {
            synchronized (lock) {
                if (stopped || result.isDone()) {
                    throw new CancellationException();
                }
            }
            if (expired()) {
                complete(null, timeoutError());
                throw new CancellationException();
            }
        }

        private void complete(DecisionsResult value, Throwable error) {
            synchronized (lock) {
                if (stopped || result.isDone()) {
                    return;
                }
                stopped = true;
            }
            abort();
            if (error == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(error);
            }
        }

        private void abort() {
            CompletableFuture<?> pending;
            InputStream stream;
            Thread readingThread;
            CompletableFuture<Bounded> reading;
            ScheduledFuture<?> deadline;
            synchronized (lock) {
                stopped = true;
                pending = inFlight;
                stream = openStream;
                readingThread = reader;
                reading = bodyRead;
                deadline = timer;
                inFlight = null;
                openStream = null;
                reader = null;
                bodyRead = null;
                timer = null;
            }
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (pending != null) {
                pending.cancel(true);
            }
            if (readingThread != null && readingThread != Thread.currentThread()) {
                readingThread.interrupt();
            }
            closeQuietly(stream);
            if (reading != null) {
                reading.cancel(false);
            }
        }

        private void track(CompletableFuture<?> future) {
            boolean cancel;
            synchronized (lock) {
                cancel = stopped || result.isDone();
                if (!cancel) {
                    inFlight = future;
                }
            }
            if (cancel) {
                future.cancel(true);
            }
        }

        private void track(InputStream stream) {
            boolean close;
            synchronized (lock) {
                close = stopped || result.isDone();
                if (!close) {
                    openStream = stream;
                }
            }
            if (close) {
                closeQuietly(stream);
            }
        }

        private CompletableFuture<DecisionsResult> run() {
            return resolve().thenCompose(endpoint -> {
                requireActive();
                DecisionsEndpointPlanner.Plan plan = remember(
                        DecisionsEndpointPlanner.plan(endpoint, request.getModel()));
                byte[] body;
                try {
                    body = MAPPER.writeValueAsBytes(DecisionsWire.serialize(request));
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("DecisionsRequest could not be serialized.", e);
                }
                if (client.followRedirects() != HttpClient.Redirect.NEVER) {
                    throw new IllegalArgumentException(
                            "DecisionsOptions.httpClient must use HttpClient.Redirect.NEVER; the Decisions API never follows redirects.");
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

        private DecisionsEndpointPlanner.Plan remember(DecisionsEndpointPlanner.Plan plan) {
            knownSecrets.addAll(plan.secrets());
            return new DecisionsEndpointPlanner.Plan(plan.url(), plan.headers(), plan.fingerprint(),
                    List.copyOf(knownSecrets));
        }

        private CompletableFuture<DecisionsResult> retry(DecisionsEndpointPlanner.Plan plan, byte[] body,
                DecisionsException original) {
            return resolve().thenCompose(endpoint -> {
                requireActive();
                DecisionsEndpointPlanner.Plan refreshed;
                try {
                    refreshed = remember(DecisionsEndpointPlanner.plan(endpoint, request.getModel()));
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
                        requireActive();
                        if (read.exceeded()) {
                            throw new DecisionsException(DecisionsErrorKind.INVALID_RESPONSE,
                                    "The Decisions API response exceeded " + MAX_RESPONSE_BYTES + " bytes.");
                        }
                        return DecisionsWire.parseResult(parseJson(read.bytes()));
                    });
        }

        private CompletableFuture<DecisionsException> readHttpError(HttpResponse<InputStream> response,
                DecisionsEndpointPlanner.Plan plan) {
            int window = (int) Math.min(Integer.MAX_VALUE - 1L,
                    (long) MAX_ERROR_BODY_BYTES + DecisionsRedaction.maxSecretBytes(plan.secrets()));
            String requestId = response.headers().firstValue("x-request-id").orElse(null);
            return read(response, window, "The Decisions error response could not be read.", plan).thenApply(
                    read -> httpError(response.statusCode(), requestId, redactErrorBody(read, plan.secrets()),
                            plan.secrets(), DecisionsRetryAfter.parse(response.headers(), System.currentTimeMillis())));
        }

        private CompletableFuture<Bounded> read(HttpResponse<InputStream> response, int limit, String failureMessage,
                DecisionsEndpointPlanner.Plan plan) {
            InputStream stream = response.body();
            requireActive();
            if (stream == null) {
                return CompletableFuture.completedFuture(new Bounded(new byte[0], false));
            }
            CompletableFuture<Bounded> reading = new CompletableFuture<>();
            Thread thread = new Thread(() -> {
                Bounded value = null;
                Throwable failure = null;
                try {
                    requireActive();
                    value = readLimited(stream, limit);
                } catch (IOException | RuntimeException e) {
                    failure = e;
                } finally {
                    closeQuietly(stream);
                    synchronized (lock) {
                        if (openStream == stream) {
                            openStream = null;
                        }
                    }
                }
                // Close before completing: continuations may immediately refresh or replay.
                try {
                    if (failure == null) {
                        reading.complete(value);
                    } else {
                        reading.completeExceptionally(failure);
                    }
                } finally {
                    synchronized (lock) {
                        if (reader == Thread.currentThread()) {
                            reader = null;
                            bodyRead = null;
                        }
                    }
                }
            }, "copilot-decisions-reader-" + Readers.COUNTER.incrementAndGet());
            thread.setDaemon(true);
            try {
                synchronized (lock) {
                    if (stopped || result.isDone()) {
                        closeQuietly(stream);
                        reading.cancel(false);
                    } else {
                        reader = thread;
                        bodyRead = reading;
                        thread.start();
                    }
                }
            } catch (RuntimeException e) {
                closeQuietly(stream);
                reading.completeExceptionally(e);
            }
            return reading.handle((read, error) -> {
                Throwable cause = error == null ? null : unwrap(error);
                if (cause instanceof CancellationException) {
                    throw cancelled();
                }
                requireActive();
                if (cause != null) {
                    throw networkError(failureMessage, cause, plan.secrets());
                }
                return read;
            });
        }

        private CompletableFuture<SessionProviderGetEndpointResult> resolve() {
            requireActive();
            CompletableFuture<SessionProviderGetEndpointResult> pending;
            try {
                pending = resolveEndpoint.apply(endpointModelId);
                if (pending == null) {
                    throw new IllegalStateException("The provider endpoint resolver returned no result.");
                }
            } catch (RuntimeException e) {
                return failed(e instanceof CancellationException ? cancelled() : endpointUnavailable(e));
            }
            // The RPC protocol cannot cancel an endpoint lookup. Observe its eventual
            // result, but never dispatch HTTP after cancellation or deadline expiry.
            return pending.handle((endpoint, error) -> {
                Throwable cause = error == null ? null : unwrap(error);
                if (cause instanceof CancellationException) {
                    throw cancelled();
                }
                requireActive();
                if (cause != null) {
                    throw endpointUnavailable(cause);
                }
                return endpoint;
            });
        }

        private CompletableFuture<HttpResponse<InputStream>> send(DecisionsEndpointPlanner.Plan plan, byte[] body) {
            requireActive();
            CompletableFuture<HttpResponse<InputStream>> pending;
            HttpRequest httpRequest;
            try {
                if (customClient) {
                    httpRequest = new CustomRequest(plan, body);
                } else {
                    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(plan.url()))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                    plan.headers().forEach(builder::header);
                    httpRequest = builder.build();
                }
            } catch (IllegalArgumentException e) {
                // The JDK's message can echo raw header values, which may be credentials too
                // short to redact.
                return failed(networkError("The Decisions request could not be sent.",
                        new IllegalArgumentException("java.net.http rejected the request URL or a header value."),
                        plan.secrets()));
            }
            try {
                requireActive();
                pending = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (pending == null) {
                    throw new IllegalStateException("The HTTP client returned no future.");
                }
            } catch (RuntimeException e) {
                return failed(e instanceof CancellationException
                        ? cancelled()
                        : networkError("The Decisions request could not be sent.", e, plan.secrets()));
            }
            track(pending);
            return pending.handle((response, error) -> {
                if (response != null) {
                    track(response.body());
                }
                Throwable cause = error == null ? null : unwrap(error);
                if (cause instanceof CancellationException) {
                    throw cancelled();
                }
                requireActive();
                if (cause != null) {
                    throw networkError("The Decisions request could not be sent.", cause, plan.secrets());
                }
                if (response == null) {
                    throw networkError("The Decisions request could not be sent.",
                            new IOException("The HTTP client returned no response."), plan.secrets());
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
