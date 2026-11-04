/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

package com.github.copilot.decisions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.copilot.decisions.DecisionsContentPart.InputImage;
import com.github.copilot.decisions.DecisionsContentPart.InputJson;
import com.github.copilot.decisions.DecisionsContentPart.InputText;
import com.github.copilot.decisions.DecisionsContentPart.OutputText;
import com.github.copilot.decisions.DecisionsContentPart.QuestionPart;
import com.github.copilot.decisions.DecisionsContentPart.ToolOutputPart;
import com.github.copilot.decisions.DecisionsContentPart.UserPart;
import com.github.copilot.decisions.DecisionsInputItem.AssistantMessage;
import com.github.copilot.decisions.DecisionsInputItem.CustomToolCall;
import com.github.copilot.decisions.DecisionsInputItem.CustomToolCallOutput;
import com.github.copilot.decisions.DecisionsInputItem.DeveloperMessage;
import com.github.copilot.decisions.DecisionsInputItem.FunctionCall;
import com.github.copilot.decisions.DecisionsInputItem.FunctionCallOutput;
import com.github.copilot.decisions.DecisionsInputItem.Raw;
import com.github.copilot.decisions.DecisionsInputItem.UserMessage;
import com.github.copilot.decisions.DecisionsQuestion.ChoiceOption;
import com.github.copilot.decisions.DecisionsQuestion.ScoreLevel;
import com.github.copilot.generated.rpc.SessionProviderGetEndpointResult;

/**
 * Tests for the experimental Decisions API, mirroring the Node reference tests
 * and running every shared vector in {@code test/fixtures/decisions}.
 */
class DecisionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper ENDPOINT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Path FIXTURE_DIR = findFixtureDir();
    private static final JsonNode VECTORS = fixture("vectors.json");
    private static final String OK_BODY = fixture("response.full.json").toString();
    private static final long TIMEOUT_SECONDS = 20;

    // ----------------------------------------------------------------- fixtures

    private static Path findFixtureDir() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("test").resolve("fixtures").resolve("decisions");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("test/fixtures/decisions not found above " + System.getProperty("user.dir"));
    }

    private static JsonNode fixture(String name) {
        try {
            return MAPPER.readTree(Files.readString(FIXTURE_DIR.resolve(name), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SessionProviderGetEndpointResult endpoint(JsonNode node) {
        try {
            return ENDPOINT_MAPPER.treeToValue(node, SessionProviderGetEndpointResult.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SessionProviderGetEndpointResult openai(String apiKey) {
        return endpoint(json("{\"type\":\"openai\",\"baseUrl\":\"https://api.openai.com/v1\",\"apiKey\":\""
                + apiKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"headers\":{}}"));
    }

    private static final SessionProviderGetEndpointResult OPENAI = openai("sk-test-0001");
    private static final SessionProviderGetEndpointResult ANTHROPIC = endpoint(
            json("{\"type\":\"anthropic\",\"baseUrl\":\"https://api.anthropic.com\",\"headers\":{}}"));

    private static DecisionsRequest minimalRequest() {
        return new DecisionsRequest().setModel("gpt-6-luna").setInput("The export button does nothing when clicked.")
                .setQuestions(List.of(new DecisionsQuestion.Predicate("Is this ticket reporting a product defect?")));
    }

    private static DecisionsRequest fullRequest() {
        List<DecisionsInputItem> input = List.of(
                new DeveloperMessage("developer", DecisionsContent.of("You triage incoming support tickets."), true),
                new DeveloperMessage("system",
                        DecisionsContent.of(List.of(new InputText("Answer only from the provided context."))), true),
                new UserMessage(DecisionsContent.<UserPart>of(
                        new InputText("Ticket #4821: the export button does nothing.",
                                DecisionsCacheBreakpoint.explicit()),
                        new InputImage("data:image/png;base64,iVBORw0KGgo=", "high",
                                DecisionsCacheBreakpoint.explicit()),
                        new InputJson(json("{\"plan\":\"enterprise\",\"seats\":120}")),
                        new InputJson(json("[{\"id\":1},{\"id\":2}]"))), true),
                new UserMessage(
                        DecisionsContent.<UserPart>of(new InputText("Customer replied: still broken after refresh."),
                                new InputImage("https://example.com/full.png", "original"))),
                new AssistantMessage(
                        DecisionsContent.of(List.of(new OutputText("I will look up the account."),
                                new DecisionsContentPart.Refusal("I cannot share internal notes."))),
                        "commentary", true),
                new FunctionCall("call_1", "lookup_account", "{\"ticket\":4821}", "fc_1", "crm", "completed"),
                new FunctionCallOutput("call_1", DecisionsContent.of("{\"tier\":\"enterprise\"}"), "fco_1",
                        "completed"),
                new FunctionCallOutput("call_2",
                        DecisionsContent.<ToolOutputPart>of(new InputText("Screenshot attached."),
                                new InputImage("https://example.com/shot.png", "low"))),
                new CustomToolCall("call_3", "grep_logs", "export failed", "ctc_1", "ops", "incomplete"),
                new CustomToolCallOutput("call_3", DecisionsContent.of("3 matches"), "ctco_1", "in_progress"),
                new Raw((ObjectNode) json("{\"type\":\"future_item\",\"payload\":{\"anything\":[1,true,null]}}")));
        List<DecisionsQuestion> questions = List.of(
                new DecisionsQuestion.Predicate("is_bug", "Is this ticket reporting a product defect?"),
                new DecisionsQuestion.Choice("team", "Which team should own this ticket?",
                        List.of(new ChoiceOption("frontend", "UI and client code"), new ChoiceOption("backend"))),
                new DecisionsQuestion.Choice(
                        DecisionsContent.<QuestionPart>of(new InputText("Should we escalate?"),
                                new InputImage("https://example.com/policy.png", "auto"),
                                new InputJson(json("{\"sla_hours\":4}"))),
                        null, List.of(new ChoiceOption(true, "Escalate now"), new ChoiceOption(false))),
                new DecisionsQuestion.Score("severity", "How severe is the impact?",
                        List.of(new ScoreLevel("low", "Cosmetic"), new ScoreLevel("medium"),
                                new ScoreLevel("high", "Blocks work"))));
        return new DecisionsRequest().setModel("gpt-6-luna").setInput(input).setQuestions(questions)
                .setPromptCacheKey("support-triage-v1")
                .setPromptCacheOptions(new DecisionsPromptCacheOptions("explicit", "30m"))
                .setSafetyIdentifier("user-7f3a9c");
    }

    // ----------------------------------------------------------------- fakes

    /** A recorded request with its captured body. */
    record Recorded(HttpRequest request, String body) {
    }

    /** A fake HTTP client returning scripted responses. */
    static final class FakeClient extends HttpClient {
        final List<Recorded> calls = Collections.synchronizedList(new ArrayList<>());
        final List<String> events;
        private final List<Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>>> script;
        private final Redirect redirect;
        private final AtomicInteger index = new AtomicInteger();

        FakeClient(List<Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>>> script) {
            this(script, Redirect.NEVER, Collections.synchronizedList(new ArrayList<>()));
        }

        FakeClient(List<Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>>> script, Redirect redirect,
                List<String> events) {
            this.script = script;
            this.redirect = redirect;
            this.events = events;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return redirect;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            int i = index.getAndIncrement();
            events.add("fetch" + i);
            calls.add(new Recorded(request, captureBody(request)));
            CompletableFuture<HttpResponse<InputStream>> response = script.get(Math.min(i, script.size() - 1))
                    .apply(request);
            @SuppressWarnings("unchecked")
            CompletableFuture<HttpResponse<T>> cast = (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) response;
            return cast;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    private static String captureBody(HttpRequest request) {
        if (request.bodyPublisher().isEmpty()) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<String> done = new CompletableFuture<>();
        request.bodyPublisher().get().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                out.write(bytes, 0, bytes.length);
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(out.toString(StandardCharsets.UTF_8));
            }
        });
        return done.join();
    }

    /** A fake response. */
    record FakeResponse(int statusCode, HttpRequest request, HttpHeaders headers,
            InputStream body) implements HttpResponse<InputStream> {

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>> respond(int status,
            Supplier<InputStream> body, Map<String, String> headers) {
        return request -> {
            Map<String, List<String>> map = new LinkedHashMap<>();
            headers.forEach((k, v) -> map.put(k, List.of(v)));
            return CompletableFuture.completedFuture(
                    new FakeResponse(status, request, HttpHeaders.of(map, (a, b) -> true), body.get()));
        };
    }

    private static Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>> respond(int status,
            String body) {
        return respond(status, body, Map.of("content-type", "application/json"));
    }

    private static Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>> respond(int status, String body,
            Map<String, String> headers) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return respond(status, () -> new ByteArrayInputStream(bytes), headers);
    }

    @SafeVarargs
    private static FakeClient client(Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>>... script) {
        return new FakeClient(List.of(script));
    }

    private static SessionDecisions decisionsWith(List<SessionProviderGetEndpointResult> endpoints, List<String> seen) {
        AtomicInteger index = new AtomicInteger();
        return new SessionDecisions(modelId -> {
            seen.add(modelId);
            return CompletableFuture
                    .completedFuture(endpoints.get(Math.min(index.getAndIncrement(), endpoints.size() - 1)));
        });
    }

    private static SessionDecisions decisionsWith(SessionProviderGetEndpointResult... endpoints) {
        return decisionsWith(List.of(endpoints), Collections.synchronizedList(new ArrayList<>()));
    }

    private static DecisionsOptions with(HttpClient client) {
        return new DecisionsOptions().setHttpClient(client);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("expected success", e.getCause());
        }
    }

    private static Throwable failure(CompletableFuture<?> future) throws Exception {
        try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (CancellationException e) {
            return e;
        } catch (TimeoutException e) {
            throw new AssertionError("future did not complete", e);
        }
        return fail("expected the future to fail");
    }

    private static DecisionsException decisionsFailure(CompletableFuture<?> future) throws Exception {
        return assertInstanceOf(DecisionsException.class, failure(future));
    }

    /** A stream that records reads and closes. */
    static class TrackingStream extends InputStream {
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    /** An endless stream of one byte value. */
    static final class EndlessStream extends TrackingStream {
        private final byte fill;
        final AtomicInteger served = new AtomicInteger();

        EndlessStream(byte fill) {
            this.fill = fill;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed.get()) {
                throw new IOException("closed");
            }
            java.util.Arrays.fill(b, off, off + len, fill);
            served.addAndGet(len);
            return len;
        }
    }

    /** A stream that yields a prefix, then fails like a dropped socket. */
    static final class FailingStream extends TrackingStream {
        private final byte[] prefix;
        private boolean sent;

        FailingStream(String prefix) {
            this.prefix = prefix.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (!sent) {
                sent = true;
                int n = Math.min(len, prefix.length);
                System.arraycopy(prefix, 0, b, off, n);
                return n;
            }
            throw new IOException("socket hang up");
        }
    }

    /** A stream that blocks until closed. */
    static final class BlockingStream extends TrackingStream {
        final CountDownLatch reading = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            reading.countDown();
            try {
                release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("closed");
        }

        @Override
        public void close() {
            super.close();
            release.countDown();
        }
    }

    // -----------------------------------------------------------------
    // serialization

    @Test
    void serializesFullRequestToGoldenFixture() {
        assertEquals(fixture("request.full.json"), DecisionsWire.serialize(fullRequest()));
    }

    @Test
    void serializesMinimalRequest() {
        assertEquals(fixture("request.minimal.json"), DecisionsWire.serialize(minimalRequest()));
    }

    @Test
    void normalizesNullsToOmission() {
        DecisionsRequest withNulls = new DecisionsRequest().setModel("gpt-6-luna").setInput(List.of(
                new UserMessage(DecisionsContent.<UserPart>of(new InputImage("https://example.com/a.png", null, null)),
                        true),
                new AssistantMessage(DecisionsContent.of("ok"), null, true),
                new FunctionCall("c", "n", "{}", null, null, null)))
                .setQuestions(
                        List.of(new DecisionsQuestion.Choice((String) null, "q", List.of(new ChoiceOption("a", null))),
                                new DecisionsQuestion.Score((String) null, "s", List.of(new ScoreLevel("x", null)))))
                .setPromptCacheKey(null).setPromptCacheOptions(null).setSafetyIdentifier(null);
        assertEquals(json("{\"model\":\"gpt-6-luna\",\"input\":["
                + "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_image\",\"image_url\":\"https://example.com/a.png\"}]},"
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":\"ok\"},"
                + "{\"type\":\"function_call\",\"call_id\":\"c\",\"name\":\"n\",\"arguments\":\"{}\"}],"
                + "\"questions\":[{\"type\":\"choice\",\"instructions\":\"q\",\"choices\":[{\"value\":\"a\"}]},"
                + "{\"type\":\"score\",\"instructions\":\"s\",\"levels\":[{\"label\":\"x\"}]}]}"),
                DecisionsWire.serialize(withNulls));
    }

    @Test
    void requiresModel() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DecisionsWire.serialize(minimalRequest().setModel("")));
        assertTrue(error.getMessage().contains("model is required"));
    }

    @Test
    void preservesBooleanAndStringChoiceValues() {
        JsonNode wire = DecisionsWire.serialize(fullRequest());
        assertTrue(wire.at("/questions/2/choices/0/value").isBoolean());
        assertTrue(wire.at("/questions/1/choices/0/value").isTextual());
    }

    // ----------------------------------------------------------------- parsing

    @Test
    void parsesFullResponseFixture() {
        DecisionsResult result = DecisionsWire.parseResult(fixture("response.full.json"));
        assertEquals("gpt-6-luna-2026-10-01", result.model());
        assertEquals(new DecisionsUsage(812, new DecisionsUsage.InputTokensDetails(512, 128), 24,
                new DecisionsUsage.OutputTokensDetails(16), 836), result.usage());
        List<DecisionsAnswer> answers = result.answers();
        assertEquals(6, answers.size());
        assertEquals(new DecisionsAnswer.Predicate("is_bug", 0.93), answers.get(0));

        DecisionsAnswer.Choice choice = assertInstanceOf(DecisionsAnswer.Choice.class, answers.get(1));
        assertEquals("team", choice.name());
        assertEquals(DecisionsChoiceValue.of("frontend"), choice.choice());

        DecisionsAnswer.Choice boolChoice = assertInstanceOf(DecisionsAnswer.Choice.class, answers.get(2));
        assertNull(boolChoice.name());
        assertEquals(DecisionsChoiceValue.of(true), boolChoice.choice());
        assertInstanceOf(DecisionsChoiceValue.BooleanValue.class, boolChoice.choice());
        assertEquals(List.of(DecisionsChoiceValue.of(true), DecisionsChoiceValue.of(false)),
                boolChoice.probabilities().stream().map(DecisionsAnswer.ChoiceProbability::value).toList());

        DecisionsAnswer.Score score = assertInstanceOf(DecisionsAnswer.Score.class, answers.get(3));
        assertEquals("severity", score.name());
        assertEquals(1.37, score.score());
        assertEquals(List.of(0L, 1L, 2L),
                score.probabilities().stream().map(DecisionsAnswer.ScoreProbability::value).toList());

        assertEquals(new DecisionsAnswer.Refusal("pii_check"), answers.get(4));

        DecisionsAnswer.Unknown unknown = assertInstanceOf(DecisionsAnswer.Unknown.class, answers.get(5));
        assertEquals("future_kind", unknown.name());
        assertEquals("ranking", unknown.wireType());
        assertEquals(json("{\"type\":\"ranking\",\"name\":\"future_kind\",\"ranked\":[\"a\",\"b\"]}"), unknown.raw());
    }

    @Test
    void rejectsMalformedResponsesWithInvalidResponse() {
        List<String> bad = List.of("null", "[]", "{\"answers\":[],\"model\":\"m\"}",
                "{\"answers\":[{\"type\":\"predicate\"}],\"model\":\"m\",\"usage\":{}}",
                "{\"answers\":[{\"type\":\"choice\",\"choice\":3,\"confidence\":1,\"probabilities\":[]}],\"model\":\"m\",\"usage\":{}}",
                "{\"answers\":[{\"type\":\"score\",\"score\":1,\"confidence\":1,\"probabilities\":[{\"label\":\"a\",\"value\":0.5,\"probability\":1}]}],\"model\":\"m\",\"usage\":{}}");
        for (String value : bad) {
            DecisionsException error = assertThrows(DecisionsException.class,
                    () -> DecisionsWire.parseResult(json(value)), value);
            assertEquals(DecisionsErrorKind.INVALID_RESPONSE, error.getKind(), value);
        }
    }

    // ----------------------------------------------------------------- vectors

    private static Stream<JsonNode> group(String name) {
        JsonNode array = VECTORS.get(name);
        assertNotNull(array, name);
        assertTrue(array.size() > 0, name);
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out.stream();
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            map.put(e.getKey(), e.getValue().asText());
        }
        return map;
    }

    @TestFactory
    Stream<DynamicTest> urlVectors() {
        return group("url").map(v -> DynamicTest.dynamicTest(v.get("name").asText(), () -> {
            SessionProviderGetEndpointResult ep = endpoint(v.get("endpoint"));
            if (v.hasNonNull("error")) {
                DecisionsException error = assertThrows(DecisionsException.class,
                        () -> DecisionsEndpointPlanner.buildUrl(ep.type(), ep.baseUrl()));
                assertEquals(v.get("error").asText(), error.getKind().getValue());
                assertFalse(error.getMessage().contains("secretpassword"));
            } else {
                assertEquals(v.get("expected").asText(), DecisionsEndpointPlanner.buildUrl(ep.type(), ep.baseUrl()));
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> headerVectors() {
        return group("headers").map(v -> DynamicTest.dynamicTest(v.get("name").asText(), () -> {
            SessionProviderGetEndpointResult ep = endpoint(v.get("endpoint"));
            if (v.hasNonNull("error")) {
                DecisionsException error = assertThrows(DecisionsException.class,
                        () -> DecisionsEndpointPlanner.plan(ep, "gpt-6-luna"));
                assertEquals(v.get("error").asText(), error.getKind().getValue());
            } else {
                Map<String, String> expected = stringMap(v.get("expected"));
                assertEquals(expected, DecisionsEndpointPlanner.plan(ep, "gpt-6-luna").headers());
                assertEquals(expected, DecisionsEndpointPlanner.buildHeaders(ep));
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> preflightVectors() {
        return group("preflight").map(v -> DynamicTest.dynamicTest(v.get("name").asText(), () -> {
            SessionProviderGetEndpointResult ep = endpoint(v.get("endpoint"));
            String model = v.get("model").asText();
            if (v.hasNonNull("error")) {
                DecisionsException error = assertThrows(DecisionsException.class,
                        () -> DecisionsEndpointPlanner.plan(ep, model));
                assertEquals(v.get("error").asText(), error.getKind().getValue());
            } else {
                DecisionsEndpointPlanner.plan(ep, model);
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> retryVectors() {
        return group("retry").map(v -> DynamicTest.dynamicTest(v.get("name").asText(), () -> {
            FakeClient client = client(respond(401, "{\"error\":{\"type\":\"auth\",\"code\":\"expired\"}}"),
                    respond(200, OK_BODY));
            List<String> seen = Collections.synchronizedList(new ArrayList<>());
            SessionProviderGetEndpointResult first = endpoint(v.get("first"));
            SessionProviderGetEndpointResult second = endpoint(v.get("second"));
            CompletableFuture<DecisionsResult> future = decisionsWith(List.of(first, second), seen)
                    .create(minimalRequest(), with(client));
            if (v.get("retry").asBoolean()) {
                assertEquals("gpt-6-luna-2026-10-01", await(future).model());
                assertEquals(2, client.calls.size());
                assertEquals(DecisionsEndpointPlanner.buildUrl(second.type(), second.baseUrl()),
                        client.calls.get(1).request().uri().toString());
            } else {
                DecisionsException error = decisionsFailure(future);
                assertEquals(DecisionsErrorKind.HTTP, error.getKind());
                assertEquals(401, error.getStatus());
                assertEquals("expired", error.getErrorCode());
                assertEquals(1, client.calls.size());
            }
            assertEquals(2, seen.size());
        }));
    }

    @Test
    void retriesAtMostOnce() throws Exception {
        FakeClient client = client(respond(403, "{}"));
        DecisionsException error = decisionsFailure(
                decisionsWith(OPENAI, openai("sk-test-0002"), openai("sk-test-0003")).create(minimalRequest(),
                        with(client)));
        assertEquals(DecisionsErrorKind.HTTP, error.getKind());
        assertEquals(403, error.getStatus());
        assertEquals(2, client.calls.size());
    }

    @TestFactory
    Stream<DynamicTest> redactionLengthVectors() {
        return group("redactionLength").map(v -> DynamicTest.dynamicTest(v.get("name").asText(), () -> {
            String value = v.get("value").asText();
            String echoed = "echo:" + value.strip() + ":end";
            String expected = v.get("isSecret").asBoolean() ? "echo:[REDACTED]:end" : echoed;
            List<String> secrets = DecisionsEndpointPlanner.plan(openai(value), "gpt-6-luna").secrets();
            SessionDecisions.Bounded read = new SessionDecisions.Bounded(echoed.getBytes(StandardCharsets.UTF_8),
                    false);
            assertEquals(expected, SessionDecisions
                    .httpError(400, null, SessionDecisions.redactErrorBody(read, secrets), secrets).getBody());

            FakeClient client = client(respond(400, echoed, Map.of()));
            DecisionsException error = decisionsFailure(
                    decisionsWith(openai(value)).create(minimalRequest(), with(client)));
            if (value.chars().allMatch(c -> c <= 0xFF)) {
                assertEquals(expected, error.getBody());
            } else {
                // java.net.http rejects header values outside Latin-1 before sending.
                assertEquals(DecisionsErrorKind.NETWORK, error.getKind());
                assertEquals(0, client.calls.size());
                assertFalse(String.valueOf(error.getCause()).contains(value.strip()));
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> redactionVectors() {
        return group("redaction").map(v -> DynamicTest.dynamicTest(v.get("name").asText(), () -> {
            String payload = (v.has("bodyPrefix")
                    ? v.get("bodyPrefix").get("char").asText().repeat(v.get("bodyPrefix").get("count").asInt())
                    : "") + v.get("body").asText();
            int status = v.get("status").asInt();
            FakeClient client = client(respond(status, payload, Map.of("x-request-id", "req_123")));
            DecisionsException error = decisionsFailure(
                    decisionsWith(endpoint(v.get("endpoint"))).create(minimalRequest(), with(client)));
            assertEquals(DecisionsErrorKind.HTTP, error.getKind());
            assertEquals(status, error.getStatus());
            assertEquals("req_123", error.getRequestId());
            assertEquals(v.get("errorCode").isNull() ? null : v.get("errorCode").asText(), error.getErrorCode());
            assertEquals(v.get("errorType").isNull() ? null : v.get("errorType").asText(), error.getErrorType());
            if (v.has("bodyTruncated")) {
                assertEquals(v.get("bodyTruncated").asBoolean(), error.getBodyTruncated());
            }
            if (v.has("maxBodyBytes")) {
                assertTrue(error.getBody().getBytes(StandardCharsets.UTF_8).length <= v.get("maxBodyBytes").asInt());
            }
            String surfaces = String.join("\n", error.getMessage(), String.valueOf(error.getBody()),
                    String.valueOf(error.getErrorCode()), String.valueOf(error.getErrorType()),
                    String.valueOf(error.getErrorParam()), error.toString(), String.valueOf(error.getCause()));
            for (JsonNode secret : v.get("mustNotContain")) {
                assertFalse(surfaces.contains(secret.asText()), "leaked " + secret.asText().length() + " chars");
            }
            if (!Boolean.TRUE.equals(error.getBodyTruncated())) {
                assertTrue(error.getBody().contains("[REDACTED]"));
            }
        }));
    }

    // ----------------------------------------------------------------- create

    @Test
    void postsSerializedRequestWithPlannedHeaders() throws Exception {
        FakeClient client = client(respond(200, OK_BODY));
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        DecisionsResult result = await(decisionsWith(List.of(OPENAI), seen).create(fullRequest(), with(client)));
        assertEquals(6, result.answers().size());
        assertEquals(Collections.singletonList(null), seen);
        assertEquals(1, client.calls.size());
        HttpRequest request = client.calls.get(0).request();
        assertEquals("https://api.openai.com/v1/decisions", request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals(Map.of("authorization", List.of("Bearer " + OPENAI.apiKey()), "content-type",
                List.of("application/json"), "accept", List.of("application/json")), request.headers().map());
        assertEquals(fixture("request.full.json"), json(client.calls.get(0).body()));
    }

    @Test
    void passesEndpointModelIdVerbatim() throws Exception {
        FakeClient client = client(respond(200, OK_BODY));
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        await(decisionsWith(List.of(OPENAI), seen).create(minimalRequest(),
                with(client).setEndpointModelId("endpoint-model")));
        assertEquals(List.of("endpoint-model"), seen);
    }

    @Test
    void callsGetEndpointOnEveryCreate() throws Exception {
        FakeClient client = client(respond(200, OK_BODY));
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        SessionDecisions decisions = decisionsWith(List.of(OPENAI), seen);
        await(decisions.create(minimalRequest(), with(client)));
        await(decisions.create(minimalRequest(), with(client)));
        assertEquals(2, seen.size());
    }

    @Test
    void mapsOpenAiErrorEnvelope() throws Exception {
        FakeClient client = client(respond(400, fixture("error.json").toString(),
                Map.of("content-type", "application/json", "x-request-id", "req_9")));
        DecisionsException error = decisionsFailure(decisionsWith(OPENAI).create(minimalRequest(), with(client)));
        assertEquals(DecisionsErrorKind.HTTP, error.getKind());
        assertEquals(400, error.getStatus());
        assertEquals("invalid_request_error", error.getErrorType());
        assertEquals("unsupported_model", error.getErrorCode());
        assertEquals("model", error.getErrorParam());
        assertEquals("req_9", error.getRequestId());
        assertEquals(Boolean.FALSE, error.getBodyTruncated());
        assertTrue(error.getMessage().contains("does not support the Decisions API"));
    }

    @Test
    void boundsLargeErrorBodiesAt64KiB() throws Exception {
        FakeClient client = client(respond(500, "x".repeat(SessionDecisions.MAX_ERROR_BODY_BYTES + 1000), Map.of()));
        DecisionsException error = decisionsFailure(decisionsWith(OPENAI).create(minimalRequest(), with(client)));
        assertEquals(DecisionsErrorKind.HTTP, error.getKind());
        assertEquals(Boolean.TRUE, error.getBodyTruncated());
        assertEquals(SessionDecisions.MAX_ERROR_BODY_BYTES, error.getBody().length());
    }

    @Test
    void truncationBacksOffToUtf8BoundaryWithoutReplacementCharacter() throws Exception {
        int cap = SessionDecisions.MAX_ERROR_BODY_BYTES;
        // A 2-byte character straddling the cap is dropped entirely.
        String twoByte = "x".repeat(cap - 1) + "\u00e9" + "tail";
        // A 4-byte character (surrogate pair) straddling the cap is dropped entirely.
        String fourByte = "x".repeat(cap - 2) + "\uD83D\uDE00" + "tail";
        Map<String, String> cases = Map.of(twoByte, "x".repeat(cap - 1), fourByte, "x".repeat(cap - 2));
        for (Map.Entry<String, String> c : cases.entrySet()) {
            FakeClient client = client(respond(500, c.getKey(), Map.of()));
            DecisionsException error = decisionsFailure(decisionsWith(OPENAI).create(minimalRequest(), with(client)));
            assertEquals(Boolean.TRUE, error.getBodyTruncated());
            assertEquals(c.getValue(), error.getBody());
            assertFalse(error.getBody().contains("\uFFFD"));
            assertTrue(error.getBody().getBytes(StandardCharsets.UTF_8).length <= cap);
        }
    }

    @Test
    void redactionGrowthIsStillCappedAt64KiB() throws Exception {
        int cap = SessionDecisions.MAX_ERROR_BODY_BYTES;
        String secret = "sk-test1";
        String body = secret + "x".repeat(cap - secret.length());
        FakeClient client = client(respond(500, body, Map.of()));
        DecisionsException error = decisionsFailure(
                decisionsWith(openai(secret)).create(minimalRequest(), with(client)));
        assertTrue(error.getBody().startsWith(DecisionsRedaction.REDACTED));
        assertTrue(error.getBody().getBytes(StandardCharsets.UTF_8).length <= cap);
        assertEquals(Boolean.TRUE, error.getBodyTruncated());
        assertFalse(error.getBody().contains(secret));
    }

    @Test
    void reportsNonJsonSuccessBodiesAsInvalidResponse() throws Exception {
        FakeClient client = client(respond(200, "<html>", Map.of()));
        DecisionsException error = decisionsFailure(decisionsWith(OPENAI).create(minimalRequest(), with(client)));
        assertEquals(DecisionsErrorKind.INVALID_RESPONSE, error.getKind());
    }

    /** A transport failure carrying a code and credential-bearing state. */
    public static final class TransportFailure extends IOException {
        private static final long serialVersionUID = 1L;
        /** Credential-bearing state that must not reach the sanitized cause. */
        public final Map<String, String> headers = Map.of("authorization", "Bearer sk-test-0001");

        TransportFailure(String message) {
            super(message);
        }

        /**
         * Returns the error code.
         *
         * @return the code
         */
        public String getCode() {
            return "ECONNRESET";
        }
    }

    @Test
    void wrapsTransportFailuresWithSanitizedCause() throws Exception {
        FakeClient client = client(request -> CompletableFuture.failedFuture(
                new TransportFailure("fetch failed for https://api.openai.com/v1/decisions with Bearer sk-test-0001")));
        DecisionsException error = decisionsFailure(decisionsWith(OPENAI).create(minimalRequest(), with(client)));
        assertEquals(DecisionsErrorKind.NETWORK, error.getKind());
        DecisionsSanitizedCause cause = assertInstanceOf(DecisionsSanitizedCause.class, error.getCause());
        assertEquals(TransportFailure.class.getName(), cause.getName());
        assertEquals("ECONNRESET", cause.getCode());
        assertFalse(cause.getMessage().contains("sk-test-0001"));
        assertFalse(cause.toString().contains("sk-test-0001"));
        assertNull(cause.getCause());
        assertEquals(0, cause.getStackTrace().length);
        assertEquals(0, cause.getSuppressed().length);
    }

    @Test
    void propagatesEndpointUnavailableWhenRefreshFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SessionDecisions decisions = new SessionDecisions(modelId -> calls.getAndIncrement() == 0
                ? CompletableFuture.completedFuture(OPENAI)
                : CompletableFuture.failedFuture(new RuntimeException("runtime went away")));
        FakeClient client = client(respond(401, "{}"));
        DecisionsException error = decisionsFailure(decisions.create(minimalRequest(), with(client)));
        assertEquals(DecisionsErrorKind.ENDPOINT_UNAVAILABLE, error.getKind());
    }

    @Test
    void cancellationDuringRefreshIsNotWrapped() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch refreshing = new CountDownLatch(1);
        CompletableFuture<SessionProviderGetEndpointResult> pending = new CompletableFuture<>();
        SessionDecisions decisions = new SessionDecisions(modelId -> {
            if (calls.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(OPENAI);
            }
            refreshing.countDown();
            return pending;
        });
        FakeClient client = client(respond(401, "{}"));
        CompletableFuture<DecisionsResult> future = decisions.create(minimalRequest(), with(client));
        assertTrue(refreshing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        future.cancel(true);
        pending.complete(openai("sk-test-0002"));
        assertInstanceOf(CancellationException.class, failure(future));
        assertEquals(1, client.calls.size());
    }

    @Test
    void validatesEndpointBeforeSerializingRequest() throws Exception {
        DecisionsRequest malformed = new DecisionsRequest().setModel("gpt-6-luna").setInput("x");
        DecisionsException error = decisionsFailure(decisionsWith(ANTHROPIC).create(malformed));
        assertEquals(DecisionsErrorKind.UNSUPPORTED_PROVIDER, error.getKind());
    }

    @Test
    void reportsIncompleteRequestThroughFutureAfterPlanning() throws Exception {
        DecisionsRequest malformed = new DecisionsRequest().setModel("gpt-6-luna").setInput("x");
        FakeClient client = client(respond(200, OK_BODY));
        Throwable error = failure(decisionsWith(OPENAI).create(malformed, with(client)));
        assertInstanceOf(IllegalArgumentException.class, error);
        assertEquals(0, client.calls.size());
    }

    @Test
    void rejectsMissingModelBeforeGetEndpoint() {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        SessionDecisions decisions = decisionsWith(List.of(OPENAI), seen);
        assertThrows(IllegalArgumentException.class, () -> decisions.create(minimalRequest().setModel(null)));
        assertThrows(NullPointerException.class, () -> decisions.create(null));
        assertEquals(0, seen.size());
    }

    @Test
    void rejectsClientsThatFollowRedirects() {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        FakeClient client = new FakeClient(List.of(respond(200, OK_BODY)), HttpClient.Redirect.NORMAL,
                Collections.synchronizedList(new ArrayList<>()));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> decisionsWith(List.of(OPENAI), seen).create(minimalRequest(), with(client)));
        assertTrue(error.getMessage().contains("Redirect.NEVER"));
        assertEquals(0, seen.size());
        assertEquals(0, client.calls.size());
    }

    @Test
    void drains401BodyBeforeRetrying() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        byte[] empty = "{}".getBytes(StandardCharsets.UTF_8);
        Supplier<InputStream> recording = () -> new ByteArrayInputStream(empty) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                events.add("read");
                return super.read(b, off, len);
            }
        };
        FakeClient client = new FakeClient(List.of(respond(401, recording, Map.of()), respond(200, OK_BODY)),
                HttpClient.Redirect.NEVER, events);
        await(decisionsWith(OPENAI, openai("sk-test-0002")).create(minimalRequest(), with(client)));
        assertTrue(events.indexOf("read") >= 0);
        assertTrue(events.indexOf("read") < events.indexOf("fetch1"), events.toString());
    }

    @Test
    void closesOversizedErrorBodies() throws Exception {
        EndlessStream stream = new EndlessStream((byte) 'x');
        FakeClient client = client(respond(500, () -> stream, Map.of()));
        DecisionsException error = decisionsFailure(decisionsWith(OPENAI).create(minimalRequest(), with(client)));
        assertEquals(Boolean.TRUE, error.getBodyTruncated());
        assertTrue(stream.closed.get());
    }

    @Test
    void mapsMidBodyStreamFailureToNetwork() throws Exception {
        for (int status : new int[]{200, 500}) {
            FailingStream stream = new FailingStream("{\"answers\":");
            FakeClient client = client(respond(status, () -> stream, Map.of()));
            DecisionsException error = decisionsFailure(decisionsWith(OPENAI).create(minimalRequest(), with(client)));
            assertEquals(DecisionsErrorKind.NETWORK, error.getKind(), "status " + status);
            assertEquals("socket hang up", error.getCause().getMessage());
            assertTrue(stream.closed.get());
        }
    }

    @Test
    void capsSuccessBodies() throws Exception {
        EndlessStream stream = new EndlessStream((byte) ' ');
        FakeClient client = client(respond(200, () -> stream, Map.of()));
        DecisionsException error = decisionsFailure(decisionsWith(OPENAI).create(minimalRequest(), with(client)));
        assertEquals(DecisionsErrorKind.INVALID_RESPONSE, error.getKind());
        assertTrue(stream.closed.get());
        assertTrue(stream.served.get() >= SessionDecisions.MAX_RESPONSE_BYTES);
    }

    @Test
    void acceptsSuccessBodyExactlyAtCap() throws Exception {
        byte[] body = OK_BODY.getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[SessionDecisions.MAX_RESPONSE_BYTES];
        System.arraycopy(body, 0, padded, 0, body.length);
        java.util.Arrays.fill(padded, body.length, padded.length, (byte) ' ');
        FakeClient client = client(respond(200, () -> new ByteArrayInputStream(padded), Map.of()));
        assertEquals(6, await(decisionsWith(OPENAI).create(minimalRequest(), with(client))).answers().size());
    }

    @Test
    void surfacesGateHintOnlyWhenRuntimeMentionsIt() throws Exception {
        SessionDecisions gated = new SessionDecisions(modelId -> CompletableFuture.failedFuture(new RuntimeException(
                "Request session.provider.getEndpoint failed with message: Provider endpoint access is disabled. "
                        + "To enable this API, set COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true")));
        DecisionsException gatedError = decisionsFailure(gated.create(minimalRequest()));
        assertEquals(DecisionsErrorKind.ENDPOINT_UNAVAILABLE, gatedError.getKind());
        assertTrue(gatedError.getMessage().contains("COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true"));

        SessionDecisions other = new SessionDecisions(
                modelId -> CompletableFuture.failedFuture(new RuntimeException("Session not found")));
        DecisionsException otherError = decisionsFailure(other.create(minimalRequest()));
        assertEquals(DecisionsErrorKind.ENDPOINT_UNAVAILABLE, otherError.getKind());
        assertFalse(otherError.getMessage().contains("COPILOT_ALLOW_GET_PROVIDER_ENDPOINT"));
        assertNotNull(otherError.getCause());
    }

    @Test
    void resolverThrowingSynchronouslyIsEndpointUnavailable() throws Exception {
        SessionDecisions decisions = new SessionDecisions(modelId -> {
            throw new IllegalStateException("not connected");
        });
        assertEquals(DecisionsErrorKind.ENDPOINT_UNAVAILABLE,
                decisionsFailure(decisions.create(minimalRequest())).getKind());
    }

    @Test
    void rejectsUnsupportedProvidersBeforeSending() throws Exception {
        FakeClient client = client(respond(200, OK_BODY));
        DecisionsException error = decisionsFailure(decisionsWith(ANTHROPIC).create(minimalRequest(), with(client)));
        assertEquals(DecisionsErrorKind.UNSUPPORTED_PROVIDER, error.getKind());
        assertEquals(0, client.calls.size());
    }

    // -----------------------------------------------------------------
    // cancellation

    @Test
    void cancellationDuringGetEndpointSkipsSend() throws Exception {
        CompletableFuture<SessionProviderGetEndpointResult> pending = new CompletableFuture<>();
        FakeClient client = client(respond(200, OK_BODY));
        CompletableFuture<DecisionsResult> future = new SessionDecisions(modelId -> pending).create(minimalRequest(),
                with(client));
        assertTrue(future.cancel(true));
        pending.complete(OPENAI);
        assertInstanceOf(CancellationException.class, failure(future));
        Thread.sleep(100);
        assertEquals(0, client.calls.size());
    }

    @Test
    void cancellationDuringSendAbortsRequest() throws Exception {
        CompletableFuture<HttpResponse<InputStream>> hanging = new CompletableFuture<>();
        CountDownLatch sent = new CountDownLatch(1);
        FakeClient client = client(request -> {
            sent.countDown();
            return hanging;
        });
        CompletableFuture<DecisionsResult> future = decisionsWith(OPENAI).create(minimalRequest(), with(client));
        assertTrue(sent.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        future.cancel(true);
        Throwable error = failure(future);
        assertInstanceOf(CancellationException.class, error);
        assertTrue(hanging.isCancelled());
    }

    @Test
    void cancellationDuringBodyReadClosesStream() throws Exception {
        BlockingStream stream = new BlockingStream();
        FakeClient client = client(respond(200, () -> stream, Map.of()));
        CompletableFuture<DecisionsResult> future = decisionsWith(OPENAI).create(minimalRequest(), with(client));
        assertTrue(stream.reading.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        future.cancel(true);
        assertInstanceOf(CancellationException.class, failure(future));
        assertTrue(stream.closed.get());
    }

    // ----------------------------------------------------------------- real HTTP

    /** A minimal HTTP/1.1 server that answers one request per connection. */
    static final class MiniServer implements AutoCloseable {
        final List<String> hits = Collections.synchronizedList(new ArrayList<>());
        volatile Map<String, String> lastHeaders = Map.of();
        volatile String lastBody;
        private final ServerSocket socket;
        private final Function<String, String> handler;
        private final Thread thread;

        MiniServer(Function<String, String> handler) throws IOException {
            this.handler = handler;
            this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.thread = new Thread(this::serve, "decisions-test-server");
            thread.setDaemon(true);
            thread.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/v1";
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    handle(client);
                } catch (IOException e) {
                    // Closed or client went away.
                }
            }
        }

        private void handle(Socket client) throws IOException {
            InputStream in = client.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            for (String line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            byte[] body = in.readNBytes(length);
            String path = requestLine.split(" ")[1];
            hits.add(path);
            lastHeaders = headers;
            lastBody = new String(body, StandardCharsets.UTF_8);
            String response = handler.apply(path);
            OutputStream out = client.getOutputStream();
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') {
                    String s = line.toString(StandardCharsets.ISO_8859_1);
                    return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
                }
                line.write(c);
            }
            return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
        }

        static String response(int status, String reason, Map<String, String> headers, String body) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
            headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
            sb.append("Content-Length: ").append(bytes.length).append("\r\nConnection: close\r\n\r\n").append(body);
            return sb.toString();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static SessionProviderGetEndpointResult local(String baseUrl, String extraHeaders) {
        return endpoint(json("{\"type\":\"openai\",\"baseUrl\":\"" + baseUrl
                + "\",\"apiKey\":\"sk-test-0001\",\"headers\":" + extraHeaders + "}"));
    }

    @Test
    void roundTripsOverRealHttp() throws Exception {
        try (MiniServer server = new MiniServer(
                path -> MiniServer.response(200, "OK", Map.of("Content-Type", "application/json"), OK_BODY))) {
            DecisionsResult result = await(decisionsWith(local(server.baseUrl(), "{\"X-Extra\":\"1\"}"))
                    .create(minimalRequest(), new DecisionsOptions().setTimeout(Duration.ofSeconds(10))));
            assertInstanceOf(DecisionsAnswer.Predicate.class, result.answers().get(0));
            assertEquals(List.of("/v1/decisions"), server.hits);
            assertEquals("Bearer sk-test-0001", server.lastHeaders.get("authorization"));
            assertEquals("1", server.lastHeaders.get("x-extra"));
            assertEquals("application/json", server.lastHeaders.get("content-type"));
            assertEquals(fixture("request.minimal.json"), json(server.lastBody));
        }
    }

    @Test
    void doesNotFollowRedirectsOverRealHttp() throws Exception {
        try (MiniServer server = new MiniServer(path -> path.endsWith("/decisions")
                ? MiniServer.response(307, "Temporary Redirect", Map.of("Location", "/elsewhere"), "")
                : MiniServer.response(200, "OK", Map.of("Content-Type", "application/json"), OK_BODY))) {
            DecisionsException error = decisionsFailure(
                    decisionsWith(local(server.baseUrl(), "{}")).create(minimalRequest()));
            assertEquals(DecisionsErrorKind.HTTP, error.getKind());
            assertEquals(307, error.getStatus());
            assertTrue(error.getMessage().contains("redirects are not followed"));
            assertEquals(List.of("/v1/decisions"), server.hits);
        }
    }
}
