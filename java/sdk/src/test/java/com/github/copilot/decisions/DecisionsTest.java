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
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.util.RawValue;
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
    private static final JsonNode PRODUCTION_VECTORS = fixture("production-vectors.json");
    private static final List<ObjectNode> PASSED_VECTORS = Collections.synchronizedList(new ArrayList<>());
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
        final CountDownLatch closedLatch = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public void close() {
            closed.set(true);
            closedLatch.countDown();
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
        final CountDownLatch finished = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            reading.countDown();
            try {
                release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
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
    void preservesQuestionJsonThroughPublicApi() throws Exception {
        DecisionsQuestion question = new DecisionsQuestion.Predicate(DecisionsContent.<QuestionPart>of(
                new InputJson(fixture("request.full.json").at("/questions/2/instructions/2/data"))), null);
        FakeClient client = client(respond(200, OK_BODY));
        await(decisionsWith(OPENAI).create(minimalRequest().setQuestions(List.of(question)), with(client)));
        assertEquals(fixture("request.full.json").at("/questions/2/instructions/2"),
                json(client.calls.get(0).body()).at("/questions/0/instructions/0"));
        assertEquals(1, client.calls.size());
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

    @Test
    void omitsQuestionCacheBreakpointsButPreservesInputBreakpoints() throws Exception {
        InputText text = new InputText("synthetic instruction", DecisionsCacheBreakpoint.explicit());
        InputImage image = new InputImage("https://example.com/synthetic.png", "auto",
                DecisionsCacheBreakpoint.explicit());
        DecisionsContent<QuestionPart> instructions = DecisionsContent.of(text, image);
        DecisionsRequest request = minimalRequest().setInput(List.of(new UserMessage(DecisionsContent.of(text, image))))
                .setQuestions(List.of(new DecisionsQuestion.Predicate(instructions, "predicate"),
                        new DecisionsQuestion.Choice(instructions, "choice", List.of(new ChoiceOption("yes"))),
                        new DecisionsQuestion.Score(instructions, "score", List.of(new ScoreLevel("low")))));
        FakeClient client = client(respond(200, OK_BODY));
        await(decisionsWith(OPENAI).create(request, with(client)));
        JsonNode sent = json(client.calls.get(0).body());
        assertTrue(sent.at("/input/0/content/0").has("prompt_cache_breakpoint"));
        assertTrue(sent.at("/input/0/content/1").has("prompt_cache_breakpoint"));
        for (JsonNode question : sent.get("questions")) {
            assertEquals(2, question.get("instructions").size());
            for (JsonNode part : question.get("instructions")) {
                assertFalse(part.has("prompt_cache_breakpoint"));
            }
        }
        assertNotNull(text.promptCacheBreakpoint());
        assertNotNull(image.promptCacheBreakpoint());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void rejectsInvalidRolePartCombinationsEvenThroughRawGenerics() throws Exception {
        DecisionsContent assistantText = DecisionsContent.of(new OutputText("synthetic output"));
        DecisionsContent inputText = DecisionsContent.of(new InputText("synthetic input"));
        DecisionsContent image = DecisionsContent.of(new InputImage("https://example.com/synthetic.png"));
        DecisionsContent data = DecisionsContent.of(new InputJson(json("{\"synthetic\":true}")));
        List<DecisionsInputItem> invalid = List.of(new UserMessage(assistantText),
                new DeveloperMessage("developer", image), new AssistantMessage(inputText),
                new FunctionCallOutput("synthetic-call", data));
        FakeClient client = client(respond(200, OK_BODY));
        for (DecisionsInputItem item : invalid) {
            assertInstanceOf(IllegalArgumentException.class,
                    failure(decisionsWith(OPENAI).create(minimalRequest().setInput(List.of(item)), with(client))));
        }
        DecisionsQuestion question = new DecisionsQuestion.Predicate(assistantText, "synthetic");
        assertInstanceOf(IllegalArgumentException.class,
                failure(decisionsWith(OPENAI).create(minimalRequest().setQuestions(List.of(question)), with(client))));
        assertEquals(0, client.calls.size());
        assertThrows(IllegalArgumentException.class, () -> new DeveloperMessage("synthetic-invalid-role", "text"));
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

    @Test
    void enforcesExactSignedIntegerDomainsAcrossAllUsageFields() throws Exception {
        List<String> targets = List.of("usage.input_tokens", "usage.output_tokens", "usage.total_tokens",
                "usage.input_tokens_details.cached_tokens", "usage.input_tokens_details.cache_write_tokens",
                "usage.output_tokens_details.reasoning_tokens", "answers.3.probabilities.0.value");
        List<String> invalid = List.of("null", "true", "\"3\"", "0.25", "3.0000000000000001", "9223372036854775808",
                "-9223372036854775809", "9007199254740992.0", "1e400", "9223372036854775807.0");
        for (String target : targets) {
            for (String raw : invalid) {
                FakeClient client = client(respond(200, responseWithValue(target, raw)));
                DecisionsException error = decisionsFailure(
                        decisionsWith(OPENAI).create(minimalRequest(), with(client)));
                assertEquals(DecisionsErrorKind.INVALID_RESPONSE, error.getKind(), target + " = " + raw);
                assertNull(error.getCause());
            }
        }
        for (String value : List.of("-9223372036854775808", "9223372036854775807", "9007199254740991.0", "-3e0")) {
            FakeClient client = client(respond(200, responseWithValue("answers.3.probabilities.0.value", value)));
            long expected = new java.math.BigDecimal(value).longValueExact();
            DecisionsAnswer.Score score = (DecisionsAnswer.Score) await(
                    decisionsWith(OPENAI).create(minimalRequest(), with(client))).answers().get(3);
            assertEquals(expected, score.probabilities().get(0).value());
        }
    }

    @Test
    void knownFloatingFieldsRejectInvalidAndNonfiniteNumbers() throws Exception {
        for (String target : List.of("answers.0.probability", "answers.1.confidence",
                "answers.1.probabilities.0.probability", "answers.3.score", "answers.3.confidence",
                "answers.3.probabilities.0.probability")) {
            for (String raw : List.of("null", "true", "\"1\"", "1e400", "-1e400")) {
                FakeClient client = client(respond(200, responseWithValue(target, raw)));
                assertEquals(DecisionsErrorKind.INVALID_RESPONSE,
                        decisionsFailure(decisionsWith(OPENAI).create(minimalRequest(), with(client))).getKind(),
                        target + " = " + raw);
            }
        }
        for (String raw : List.of("-0.1", "1.1")) {
            FakeClient client = client(respond(200, responseWithValue("answers.0.probability", raw)));
            assertEquals(DecisionsErrorKind.INVALID_RESPONSE,
                    decisionsFailure(decisionsWith(OPENAI).create(minimalRequest(), with(client))).getKind());
        }
    }

    @Test
    void missingUsageCountersDefaultToZeroAndBomSuccessParses() throws Exception {
        FakeClient client = client(respond(200, "\uFEFF{\"answers\":[],\"model\":\"synthetic\",\"usage\":{}}"));
        DecisionsUsage usage = await(decisionsWith(OPENAI).create(minimalRequest(), with(client))).usage();
        assertEquals(new DecisionsUsage(0, new DecisionsUsage.InputTokensDetails(0, 0), 0,
                new DecisionsUsage.OutputTokensDetails(0), 0), usage);
    }

    // ----------------------------------------------------------------- vectors

    private static Stream<JsonNode> group(String name) {
        return group(VECTORS, name);
    }

    private static Stream<JsonNode> group(JsonNode source, String name) {
        JsonNode array = source.get(name);
        assertNotNull(array, name);
        assertTrue(array.size() > 0, name);
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out.stream();
    }

    private static DynamicTest vector(String file, String group, JsonNode value, Executable assertions) {
        return DynamicTest.dynamicTest(value.get("name").asText(), () -> {
            assertions.execute();
            ObjectNode passed = MAPPER.createObjectNode();
            passed.put("file", file).put("group", group).put("name", value.get("name").asText()).put("status",
                    "passed");
            PASSED_VECTORS.add(passed);
        });
    }

    @AfterAll
    static void writeVectorCensus() throws IOException {
        String destination = System.getenv("COPILOT_DECISIONS_VECTOR_CENSUS");
        if (destination == null || destination.isEmpty()) {
            return;
        }
        Path path = Path.of(destination);
        if (path.toAbsolutePath().getParent() != null) {
            Files.createDirectories(path.toAbsolutePath().getParent());
        }
        ObjectNode census = MAPPER.createObjectNode().put("sdk", "java").put("schemaVersion", 1);
        ArrayNode cases = census.putArray("cases");
        PASSED_VECTORS.forEach(cases::add);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), census);
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
        return group("url").map(v -> vector("vectors.json", "url", v, () -> {
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
        return group("headers").map(v -> vector("vectors.json", "headers", v, () -> {
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
        return group("preflight").map(v -> vector("vectors.json", "preflight", v, () -> {
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
        return group("retry").map(v -> vector("vectors.json", "retry", v, () -> {
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
        return group("redactionLength").map(v -> vector("vectors.json", "redactionLength", v, () -> {
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
            assertEquals(expected, error.getBody());
        }));
    }

    @TestFactory
    Stream<DynamicTest> redactionVectors() {
        return group("redaction").map(v -> vector("vectors.json", "redaction", v, () -> {
            String body = v.get("body").asText();
            if (v.has("bodyRepeat")) {
                body = body.replace("{{repeat}}",
                        v.get("bodyRepeat").get("text").asText().repeat(v.get("bodyRepeat").get("count").asInt()));
            }
            String payload = (v.has("bodyPrefix")
                    ? v.get("bodyPrefix").get("char").asText().repeat(v.get("bodyPrefix").get("count").asInt())
                    : "") + body;
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
                    String.valueOf(error.getErrorParam()), String.valueOf(error.getRequestId()), error.toString(),
                    String.valueOf(error.getCause()));
            for (JsonNode secret : v.get("mustNotContain")) {
                assertFalse(surfaces.contains(secret.asText()), "leaked " + secret.asText().length() + " chars");
            }
            if (!Boolean.TRUE.equals(error.getBodyTruncated())) {
                assertTrue(error.getBody().contains("[REDACTED]"));
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> metadataVectors() {
        return group(PRODUCTION_VECTORS, "metadata").map(v -> vector("production-vectors.json", "metadata", v, () -> {
            FakeClient client = client(
                    respond(v.get("status").asInt(), v.get("body").asText(), stringMap(v.get("headers"))));
            DecisionsException error = decisionsFailure(
                    decisionsWith(openai(v.get("apiKey").asText())).create(minimalRequest(), with(client)));
            assertEquals(DecisionsErrorKind.HTTP, error.getKind());
            assertEquals(v.get("status").asInt(), error.getStatus());
            assertEquals(nullableString(v.get("expectedRequestId")), error.getRequestId());
            if (v.has("expectedErrorType")) {
                assertEquals(nullableString(v.get("expectedErrorType")), error.getErrorType());
            }
            if (v.has("expectedErrorCode")) {
                assertEquals(nullableString(v.get("expectedErrorCode")), error.getErrorCode());
            }
        }));
    }

    private static String nullableString(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private static HttpHeaders httpHeaders(Map<String, String> values) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        values.forEach((key, value) -> headers.put(key, List.of(value)));
        return HttpHeaders.of(headers, (name, value) -> true);
    }

    @TestFactory
    Stream<DynamicTest> retryAfterVectors() {
        return group(PRODUCTION_VECTORS, "retryAfter")
                .map(v -> vector("production-vectors.json", "retryAfter", v, () -> {
                    Map<String, String> headers = stringMap(v.get("headers"));
                    Long expected = v.get("expectedMs").isNull() ? null : v.get("expectedMs").longValue();
                    assertEquals(expected,
                            DecisionsRetryAfter.parse(httpHeaders(headers), v.get("nowEpochMs").longValue()));
                    long before = System.currentTimeMillis();
                    FakeClient client = client(respond(429, "{}", headers));
                    DecisionsException error = decisionsFailure(
                            decisionsWith(OPENAI).create(minimalRequest(), with(client)));
                    Long earlier = DecisionsRetryAfter.parse(httpHeaders(headers), before);
                    Long later = DecisionsRetryAfter.parse(httpHeaders(headers), System.currentTimeMillis());
                    if (earlier == null) {
                        assertNull(error.getRetryAfterMs());
                    } else {
                        assertNotNull(error.getRetryAfterMs());
                        assertTrue(error.getRetryAfterMs() <= earlier);
                        assertTrue(error.getRetryAfterMs() >= later);
                    }
                    assertEquals(DecisionsErrorKind.HTTP, error.getKind());
                    assertEquals(429, error.getStatus());
                    assertEquals(1, client.calls.size());
                }));
    }

    private static String responseWithValue(String target, String rawJson) {
        ObjectNode response = fixture("response.full.json").deepCopy();
        String[] path = target.split("\\.");
        JsonNode parent = response;
        for (int i = 0; i < path.length - 1; i++) {
            parent = parent.isArray() ? parent.get(Integer.parseInt(path[i])) : parent.get(path[i]);
        }
        ((ObjectNode) parent).putRawValue(path[path.length - 1], new RawValue(rawJson));
        return response.toString();
    }

    private static String integerResult(DecisionsResult result, String target) {
        long value = switch (target) {
            case "usage.input_tokens" -> result.usage().inputTokens();
            case "usage.total_tokens" -> result.usage().totalTokens();
            case "usage.input_tokens_details.cached_tokens" -> result.usage().inputTokensDetails().cachedTokens();
            case "answers.3.probabilities.0.value" ->
                ((DecisionsAnswer.Score) result.answers().get(3)).probabilities().get(0).value();
            default -> throw new AssertionError("Unhandled shared integer target: " + target);
        };
        return Long.toString(value);
    }

    @TestFactory
    Stream<DynamicTest> responseNumberVectors() {
        return group(PRODUCTION_VECTORS, "responseNumbers")
                .map(v -> vector("production-vectors.json", "responseNumbers", v, () -> {
                    String target = v.get("target").asText();
                    String body = responseWithValue(target, v.get("jsonValue").asText());
                    FakeClient client = client(respond(200, body));
                    CompletableFuture<DecisionsResult> future = decisionsWith(OPENAI).create(minimalRequest(),
                            with(client));
                    String expectedError = nullableString(v.path("sdkErrors").get("java"));
                    if (expectedError == null) {
                        expectedError = nullableString(v.get("expectedError"));
                    }
                    if (expectedError != null) {
                        assertEquals(expectedError, decisionsFailure(future).getKind().getValue());
                    } else {
                        DecisionsResult result = await(future);
                        if (v.has("expectedInteger")) {
                            assertEquals(v.get("expectedInteger").asText(), integerResult(result, target));
                        } else {
                            assertEquals("answers.3.score", target);
                            assertEquals(v.get("expectedNumber").doubleValue(),
                                    ((DecisionsAnswer.Score) result.answers().get(3)).score());
                        }
                    }
                    assertEquals(1, client.calls.size());
                }));
    }

    @TestFactory
    Stream<DynamicTest> authBodyFailureVectors() {
        return group(PRODUCTION_VECTORS, "authBodyFailure")
                .map(v -> vector("production-vectors.json", "authBodyFailure", v, () -> {
                    List<String> resolutions = new ArrayList<>();
                    FailingStream body = new FailingStream("{\"error\":");
                    FakeClient client = client(respond(v.get("status").asInt(), () -> body, Map.of()),
                            respond(200, OK_BODY));
                    DecisionsException error = decisionsFailure(
                            decisionsWith(List.of(OPENAI, openai("synthetic-refreshed-credential")), resolutions)
                                    .create(minimalRequest(), with(client)));
                    assertEquals(v.get("expectedError").asText(), error.getKind().getValue());
                    assertEquals(v.get("expectedResolves").asInt(), resolutions.size());
                    assertEquals(v.get("expectedSends").asInt(), client.calls.size());
                    assertEquals(v.get("expectedBodyClosed").asBoolean(), body.closed.get());
                }));
    }

    @TestFactory
    Stream<DynamicTest> deadlineVectors() {
        return group(PRODUCTION_VECTORS, "deadline").map(v -> vector("production-vectors.json", "deadline", v, () -> {
            String stage = v.get("stage").asText();
            AtomicInteger resolutions = new AtomicInteger();
            CompletableFuture<SessionProviderGetEndpointResult> pendingEndpoint = new CompletableFuture<>();
            CompletableFuture<HttpResponse<InputStream>> pendingHeaders = new CompletableFuture<>();
            BlockingStream body = new BlockingStream();
            SessionDecisions decisions = new SessionDecisions(model -> {
                int index = resolutions.incrementAndGet();
                return stage.equals("endpoint") || stage.equals("refresh") && index == 2
                        ? pendingEndpoint
                        : CompletableFuture.completedFuture(OPENAI);
            });
            FakeClient client = switch (stage) {
                case "headers" -> client(request -> pendingHeaders);
                case "successBody" -> client(respond(200, () -> body, Map.of()));
                case "errorBody" -> client(respond(v.get("status").asInt(), () -> body, Map.of()));
                case "refresh" -> client(respond(v.get("status").asInt(), "{}"));
                default -> client(respond(200, OK_BODY));
            };
            DecisionsException error = decisionsFailure(decisions.create(minimalRequest(),
                    with(client).setTimeout(Duration.ofMillis(v.get("timeoutMs").asLong()))));
            assertEquals(v.get("expectedError").asText(), error.getKind().getValue());
            assertNull(error.getCause());
            assertEquals(v.get("expectedResolves").asInt(), resolutions.get());
            assertEquals(v.get("expectedSends").asInt(), client.calls.size());
            if (stage.equals("headers")) {
                assertTrue(pendingHeaders.isCancelled());
            }
            if (stage.endsWith("Body")) {
                assertTrue(body.closed.get());
                assertTrue(body.finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            pendingEndpoint.complete(openai("synthetic-late-credential"));
            assertEquals(v.get("expectedSends").asInt(), client.calls.size());
            assertEquals(v.get("expectedResolves").asInt(), resolutions.get());
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
    void redactionGrowthIsCappedAt64KiBWithoutTruncation() throws Exception {
        int cap = SessionDecisions.MAX_ERROR_BODY_BYTES;
        String secret = "sk-test1";
        String body = secret + "x".repeat(cap - secret.length());
        FakeClient client = client(respond(500, body, Map.of()));
        DecisionsException error = decisionsFailure(
                decisionsWith(openai(secret)).create(minimalRequest(), with(client)));
        assertTrue(error.getBody().startsWith(DecisionsRedaction.REDACTED));
        assertTrue(error.getBody().getBytes(StandardCharsets.UTF_8).length <= cap);
        assertEquals(Boolean.FALSE, error.getBodyTruncated());
        assertFalse(error.getBody().contains(secret));
    }

    @Test
    void redactionShrinkCannotRetainAnyOriginalLookahead() throws Exception {
        String secret = "synthetic-long-credential-for-retention";
        String prefix = secret.repeat(2) + "x".repeat(SessionDecisions.MAX_ERROR_BODY_BYTES - secret.length() * 2);
        FakeClient client = client(respond(400, prefix + "OUTSIDE-ORIGINAL-BUDGET"));
        DecisionsException error = decisionsFailure(
                decisionsWith(openai(secret)).create(minimalRequest(), with(client)));
        assertEquals("[REDACTED]".repeat(2) + "x".repeat(SessionDecisions.MAX_ERROR_BODY_BYTES - secret.length() * 2),
                error.getBody());
        assertEquals(Boolean.TRUE, error.getBodyTruncated());
    }

    @Test
    void unionsOriginalOverlapsWithoutReprocessingReplacementMarkers() throws Exception {
        for (String secret : List.of("aaaa", "abab")) {
            String body = secret.equals("aaaa") ? "aaaaaaa" : "ababababab";
            FakeClient client = client(respond(400, body));
            assertEquals("[REDACTED]",
                    decisionsFailure(decisionsWith(openai(secret)).create(minimalRequest(), with(client))).getBody());
        }
        SessionProviderGetEndpointResult ep = endpoint(json("""
                {"type":"openai","baseUrl":"https://example.com","apiKey":"synthetic-key",
                 "headers":{},"sessionToken":{"header":"X-Session","token":"REDACTED"}}
                """));
        FakeClient client = client(respond(400, "synthetic-key REDACTED"));
        assertEquals("[REDACTED] [REDACTED]",
                decisionsFailure(decisionsWith(ep).create(minimalRequest(), with(client))).getBody());
    }

    @Test
    void redactsShortAuthButKeepsNonAuthHeuristicsAndLiteralCase() throws Exception {
        SessionProviderGetEndpointResult ep = endpoint(json("""
                {"type":"openai","baseUrl":"https://example.com?short=%73%68%6Frt&long=synthetic-query",
                 "apiKey":"XYZ","headers":{"Authorization":"bEaReR ABC","api-key":"DEF","X-Other":"short",
                 "X-Session":"OLD"},"sessionToken":{"header":"X-Session","token":"NEW"}}
                """));
        FakeClient client = client(respond(400, "XYZ ABC DEF OLD NEW short %73%68%6Frt synthetic-query xyz"));
        DecisionsException error = decisionsFailure(decisionsWith(ep).create(minimalRequest(), with(client)));
        assertEquals("[REDACTED] [REDACTED] [REDACTED] [REDACTED] [REDACTED] short %73%68%6Frt [REDACTED] xyz",
                error.getBody());
    }

    @Test
    void protectsEarlierCredentialsInReplayHttpAndNetworkDiagnostics() throws Exception {
        String first = "synthetic-old-credential";
        String second = "synthetic-new-credential";
        for (boolean network : List.of(false, true)) {
            List<String> resolutions = new ArrayList<>();
            Function<HttpRequest, CompletableFuture<HttpResponse<InputStream>>> finalAttempt = network
                    ? request -> CompletableFuture
                            .failedFuture(new CredentialFailure(first + " " + second, first + ":" + second))
                    : respond(400,
                            "{\"error\":{\"message\":\"" + first + " " + second + "\",\"code\":\"" + first
                                    + "\",\"type\":\"" + second + "\",\"param\":\"" + first + "\"}}",
                            Map.of("x-request-id", first + ":" + second));
            FakeClient client = client(respond(401, "{}"), finalAttempt);
            DecisionsException error = decisionsFailure(
                    decisionsWith(List.of(openai(first), openai(second)), resolutions).create(minimalRequest(),
                            with(client)));
            String diagnostics = error.toString() + error.getBody() + error.getRequestId() + error.getErrorType()
                    + error.getErrorCode() + error.getErrorParam() + error.getCause();
            if (network) {
                diagnostics += ((DecisionsSanitizedCause) error.getCause()).getCode();
            }
            assertFalse(diagnostics.contains(first));
            assertFalse(diagnostics.contains(second));
            assertEquals(network ? DecisionsErrorKind.NETWORK : DecisionsErrorKind.HTTP, error.getKind());
            assertEquals(2, resolutions.size());
            assertEquals(2, client.calls.size());
            assertEquals("Bearer " + second,
                    client.calls.get(1).request().headers().firstValue("authorization").orElseThrow());
        }
    }

    @Test
    void protectsRawAndDecodedInvalidUtf8QuerySecrets() throws Exception {
        SessionProviderGetEndpointResult ep = endpoint(json("""
                {"type":"openai","baseUrl":"https://example.com?sig=%FFcredential-0001","headers":{}}
                """));
        String raw = "%ffcredential-0001";
        String decoded = "\uFFFDcredential-0001";
        FakeClient client = client(respond(400, raw + " " + decoded, Map.of("x-request-id", raw)));
        DecisionsException error = decisionsFailure(decisionsWith(ep).create(minimalRequest(), with(client)));
        assertEquals("[REDACTED] [REDACTED]", error.getBody());
        assertEquals("[REDACTED]", error.getRequestId());
        client = client(request -> CompletableFuture.failedFuture(new CredentialFailure(raw + " " + decoded, raw)));
        error = decisionsFailure(decisionsWith(ep).create(minimalRequest(), with(client)));
        DecisionsSanitizedCause cause = (DecisionsSanitizedCause) error.getCause();
        assertEquals("[REDACTED] [REDACTED]", cause.getMessage());
        assertEquals("[REDACTED]", cause.getCode());
        client = client(respond(400, "x".repeat(65532) + raw));
        error = decisionsFailure(decisionsWith(ep).create(minimalRequest(), with(client)));
        assertFalse(error.getBody().contains("%ff"));
        assertTrue(error.getBodyTruncated());
        assertTrue(error.getBody().getBytes(StandardCharsets.UTF_8).length <= 65536);
    }

    @Test
    void readsNoMoreThanErrorBudgetLookaheadAndOneOverflowByte() throws Exception {
        EndlessStream stream = new EndlessStream((byte) 'x');
        String secret = "synthetic-bounded-read";
        FakeClient client = client(respond(400, () -> stream, Map.of()));
        DecisionsException error = decisionsFailure(
                decisionsWith(openai(secret)).create(minimalRequest(), with(client)));
        int lookahead = DecisionsRedaction
                .maxSecretBytes(DecisionsEndpointPlanner.plan(openai(secret), "gpt-6-luna").secrets());
        assertEquals(SessionDecisions.MAX_ERROR_BODY_BYTES + lookahead + 1, stream.served.get());
        assertEquals(SessionDecisions.MAX_ERROR_BODY_BYTES, error.getBody().getBytes(StandardCharsets.UTF_8).length);
        assertTrue(stream.closed.get());
    }

    @Test
    void rejectsAllHeaderControlsAndUnpairedSurrogatesBeforeTransport() throws Exception {
        List<String> values = new ArrayList<>(List.of("\uD800", "\uDC00", "valid\uD800tail", "valid\u007Ftail"));
        for (char control = 0; control < 32; control++) {
            if (control != '\t') {
                values.add("synthetic" + control + "value");
            }
        }
        FakeClient client = client(respond(200, OK_BODY));
        for (String value : values) {
            ObjectNode ep = (ObjectNode) json("""
                    {"type":"openai","baseUrl":"https://example.com","apiKey":"synthetic",
                     "headers":{"Authorization":"Bearer synthetic-shadow"}}
                    """);
            ep.put("apiKey", value);
            DecisionsException error = decisionsFailure(
                    decisionsWith(endpoint(ep)).create(minimalRequest(), with(client)));
            assertEquals(DecisionsErrorKind.INVALID_ENDPOINT, error.getKind());
            assertFalse(error.getMessage().contains(value));
            ep.put("apiKey", "synthetic");
            ((ObjectNode) ep.get("headers")).put("X-Unsafe", value);
            assertEquals(DecisionsErrorKind.INVALID_ENDPOINT,
                    decisionsFailure(decisionsWith(endpoint(ep)).create(minimalRequest(), with(client))).getKind());
        }
        assertEquals(0, client.calls.size());
    }

    @Test
    void jdkHeaderEncodingErrorsAreSafeAndCustomTransportsKeepUnicode() throws Exception {
        String secret = "synthetic-\uD83D\uDD11";
        FakeClient client = client(respond(400, secret));
        DecisionsException custom = decisionsFailure(
                decisionsWith(openai(secret)).create(minimalRequest(), with(client)));
        assertEquals("[REDACTED]", custom.getBody());
        assertEquals("Bearer " + secret,
                client.calls.get(0).request().headers().firstValue("authorization").orElseThrow());
        DecisionsException builtin = decisionsFailure(decisionsWith(openai(secret)).create(minimalRequest()));
        assertEquals(DecisionsErrorKind.NETWORK, builtin.getKind());
        assertFalse(builtin.getMessage().contains(secret));
        assertFalse(String.valueOf(builtin.getCause()).contains(secret));
    }

    @Test
    void normalizesUrlsWithoutCollapsingRepeatedSlashesOrRewrappingIpv6() {
        Map<String, String> cases = Map.of("http://[::1]/a//b/../v1", "http://[::1]/a//v1/decisions",
                "http://EXAMPLE.COM:0/v1", "http://example.com:0/v1/decisions",
                "https://example.com/%2e/a/../../v1?x=%2F&x=two", "https://example.com/v1/decisions?x=%2F&x=two");
        cases.forEach(
                (input, expected) -> assertEquals(expected, DecisionsEndpointPlanner.buildUrl(OPENAI.type(), input)));
        for (String invalid : List.of("https://example.com:", "https://example.com:-1", "https://example.com:65536",
                "https://[::1", "https://example.com/%2", "https://example.com?x=%", "http://[fe80::1%zone]")) {
            assertEquals(DecisionsErrorKind.INVALID_ENDPOINT, assertThrows(DecisionsException.class,
                    () -> DecisionsEndpointPlanner.buildUrl(OPENAI.type(), invalid)).getKind());
        }
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

    /**
     * Synthetic failure with credential-bearing diagnostics and discarded state.
     */
    public static final class CredentialFailure extends IOException {
        private static final long serialVersionUID = 1L;
        private final String code;

        CredentialFailure(String message, String code) {
            super(message, new IOException("synthetic-discarded-chain"));
            this.code = code;
            addSuppressed(new IOException("synthetic-discarded-suppressed"));
        }

        /**
         * Returns the synthetic error code.
         *
         * @return the code
         */
        public String getCode() {
            return code;
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
    void protectsCauseNameMessageAndCodeAndDropsRawChains() throws Exception {
        String secret = "CredentialFailure";
        FakeClient client = client(request -> CompletableFuture
                .failedFuture(new CredentialFailure(secret + " " + "x".repeat(70000), "code:" + secret)));
        DecisionsException error = decisionsFailure(
                decisionsWith(openai(secret)).create(minimalRequest(), with(client)));
        DecisionsSanitizedCause cause = assertInstanceOf(DecisionsSanitizedCause.class, error.getCause());
        assertFalse(cause.getName().contains(secret));
        assertFalse(cause.getMessage().contains(secret));
        assertFalse(cause.getCode().contains(secret));
        assertTrue(cause.getMessage().getBytes(StandardCharsets.UTF_8).length <= 65536);
        assertNull(cause.getCause());
        assertEquals(0, cause.getSuppressed().length);
        assertEquals(0, cause.getStackTrace().length);
    }

    @Test
    void protectsEveryHttpDiagnosticAndBoundsLargeRequestIds() throws Exception {
        String secret = "synthetic-diagnostic-secret";
        ObjectNode envelope = MAPPER.createObjectNode();
        ObjectNode fields = envelope.putObject("error");
        for (String field : List.of("type", "code", "param", "message")) {
            fields.put(field, secret);
        }
        FakeClient client = client(
                respond(400, envelope.toString(), Map.of("x-request-id", secret + "x".repeat(70000))));
        DecisionsException error = decisionsFailure(
                decisionsWith(openai(secret)).create(minimalRequest(), with(client)));
        for (String diagnostic : List.of(error.getMessage(), error.getBody(), error.getRequestId(),
                error.getErrorType(), error.getErrorCode(), error.getErrorParam())) {
            assertFalse(diagnostic.contains(secret));
            assertTrue(diagnostic.getBytes(StandardCharsets.UTF_8).length <= 65536);
        }
    }

    @Test
    void endpointRpcFailuresNeverRetainUnknownCredentialsOrRawCauses() throws Exception {
        String unknown = "synthetic-unknown-endpoint-credential";
        for (boolean refresh : List.of(false, true)) {
            AtomicInteger resolutions = new AtomicInteger();
            SessionDecisions decisions = new SessionDecisions(model -> refresh && resolutions.getAndIncrement() == 0
                    ? CompletableFuture.completedFuture(OPENAI)
                    : CompletableFuture.failedFuture(new CredentialFailure(
                            "RPC " + unknown + " COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true", unknown)));
            FakeClient client = client(respond(401, "{}"));
            DecisionsException error = decisionsFailure(decisions.create(minimalRequest(), with(client)));
            assertEquals(DecisionsErrorKind.ENDPOINT_UNAVAILABLE, error.getKind());
            assertFalse(error.toString().contains(unknown));
            DecisionsSanitizedCause cause = assertInstanceOf(DecisionsSanitizedCause.class, error.getCause());
            assertFalse(cause.toString().contains(unknown));
            assertNull(cause.getCause());
            assertEquals(0, cause.getStackTrace().length);
            assertEquals(0, cause.getSuppressed().length);
        }
    }

    @Test
    void modelMismatchUsesFixedDiagnostics() throws Exception {
        SessionProviderGetEndpointResult ep = endpoint(json("""
                {"type":"openai","baseUrl":"https://example.com","headers":{},
                 "sessionToken":{"header":"X-Session","token":"synthetic-token","model":"synthetic-secret-model"}}
                """));
        DecisionsException error = decisionsFailure(
                decisionsWith(ep).create(minimalRequest().setModel("synthetic-request-model")));
        assertEquals(DecisionsErrorKind.MODEL_MISMATCH, error.getKind());
        assertFalse(error.getMessage().contains("synthetic-secret-model"));
        assertFalse(error.getMessage().contains("synthetic-request-model"));
        assertNull(error.getCause());
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
    void rejectsClientsThatFollowRedirectsThroughTheFutureBeforeSending() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        FakeClient client = new FakeClient(List.of(respond(200, OK_BODY)), HttpClient.Redirect.NORMAL,
                Collections.synchronizedList(new ArrayList<>()));
        CompletableFuture<DecisionsResult> future = decisionsWith(List.of(OPENAI), seen).create(minimalRequest(),
                with(client));
        IllegalArgumentException error = assertInstanceOf(IllegalArgumentException.class, failure(future));
        assertTrue(error.getMessage().contains("Redirect.NEVER"));
        assertEquals(1, seen.size());
        assertEquals(0, client.calls.size());
    }

    @Test
    void endpointValidationPrecedesRedirectPolicyCheck() throws Exception {
        FakeClient client = new FakeClient(List.of(respond(200, OK_BODY)), HttpClient.Redirect.NORMAL,
                Collections.synchronizedList(new ArrayList<>()));
        SessionProviderGetEndpointResult bad = endpoint(
                json("{\"type\":\"openai\",\"baseUrl\":\"ftp://api.openai.com/v1\",\"headers\":{}}"));
        DecisionsException error = decisionsFailure(decisionsWith(bad).create(minimalRequest(), with(client)));
        assertEquals(DecisionsErrorKind.INVALID_ENDPOINT, error.getKind());
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
        assertTrue(stream.finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void cancellationStopsReadingWhenAnInterruptedStreamStillReturnsData() throws Exception {
        for (int status : List.of(200, 401, 403)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch readerClosed = new CountDownLatch(1);
            AtomicInteger reads = new AtomicInteger();
            TrackingStream stream = new TrackingStream() {
                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (reads.incrementAndGet() > 1) {
                        throw new IOException("The cancelled body must not be read again.");
                    }
                    entered.countDown();
                    try {
                        release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    bytes[offset] = 'x';
                    return 1;
                }

                @Override
                public void close() {
                    super.close();
                    if (Thread.currentThread().getName().startsWith("copilot-decisions-reader-")) {
                        readerClosed.countDown();
                    }
                }
            };
            List<String> resolutions = new ArrayList<>();
            FakeClient client = client(respond(status, () -> stream, Map.of()));
            CompletableFuture<DecisionsResult> future = decisionsWith(List.of(OPENAI), resolutions)
                    .create(minimalRequest(), with(client).setTimeout(Duration.ZERO));
            try {
                assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertTrue(future.cancel(true));
                assertInstanceOf(CancellationException.class, failure(future));
                assertTrue(readerClosed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertEquals(1, reads.get());
                assertTrue(stream.closed.get());
                assertEquals(1, resolutions.size());
                assertEquals(1, client.calls.size());
            } finally {
                release.countDown();
                future.cancel(true);
            }
        }
    }

    @Test
    void defaultAndDisabledDeadlinesDoNotBecomeHeaderTimeouts() throws Exception {
        assertEquals(Duration.ofSeconds(600), new DecisionsOptions().getTimeout());
        assertEquals(Duration.ofSeconds(600), new DecisionsOptions().setTimeout(null).getTimeout());
        assertThrows(IllegalArgumentException.class, () -> new DecisionsOptions().setTimeout(Duration.ofNanos(-1)));
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofSeconds(Long.MAX_VALUE))) {
            CompletableFuture<HttpResponse<InputStream>> headers = new CompletableFuture<>();
            FakeClient client = client(request -> headers);
            CompletableFuture<DecisionsResult> future = decisionsWith(OPENAI).create(minimalRequest(),
                    with(client).setTimeout(timeout));
            assertFalse(future.isDone());
            assertTrue(client.calls.get(0).request().timeout().isEmpty());
            headers.complete(new FakeResponse(200, client.calls.get(0).request(), httpHeaders(Map.of()),
                    new ByteArrayInputStream(OK_BODY.getBytes(StandardCharsets.UTF_8))));
            assertEquals(6, await(future).answers().size());
        }
    }

    @Test
    void immediatelyExpiredDeadlineDoesNoEndpointOrHttpIo() throws Exception {
        List<String> resolutions = new ArrayList<>();
        FakeClient client = client(respond(200, OK_BODY));
        DecisionsException error = decisionsFailure(decisionsWith(List.of(OPENAI), resolutions).create(minimalRequest(),
                with(client).setTimeout(Duration.ofNanos(1))));
        assertEquals(DecisionsErrorKind.TIMEOUT, error.getKind());
        assertNull(error.getCause());
        assertEquals(0, resolutions.size());
        assertEquals(0, client.calls.size());
    }

    @Test
    void oneDeadlineIncludesInitialResolutionAndRefresh() throws Exception {
        CompletableFuture<SessionProviderGetEndpointResult> initial = new CompletableFuture<>();
        CompletableFuture<SessionProviderGetEndpointResult> refresh = new CompletableFuture<>();
        AtomicInteger resolutions = new AtomicInteger();
        SessionDecisions decisions = new SessionDecisions(
                model -> resolutions.incrementAndGet() == 1 ? initial : refresh);
        FakeClient client = client(respond(401, "{}"));
        CompletableFuture<DecisionsResult> future = decisions.create(minimalRequest(),
                with(client).setTimeout(Duration.ofMillis(1200)));
        Thread.sleep(700);
        assertFalse(future.isDone());
        initial.complete(OPENAI);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(800, TimeUnit.MILLISECONDS));
        DecisionsException error = assertInstanceOf(DecisionsException.class, failure.getCause());
        assertEquals(DecisionsErrorKind.TIMEOUT, error.getKind());
        assertNull(error.getCause());
        assertEquals(2, resolutions.get());
        refresh.complete(openai("synthetic-late-refresh"));
        assertEquals(1, client.calls.size());
    }

    @Test
    void preservesNativeCancellationAtResolverTransportAndBody() throws Exception {
        CancellationException cancellation = new CancellationException("synthetic cancellation " + OPENAI.apiKey());
        cancellation.initCause(new CredentialFailure(OPENAI.apiKey(), OPENAI.apiKey()));
        SessionDecisions decisions = new SessionDecisions(model -> CompletableFuture.failedFuture(cancellation));
        assertSafeCancellation(failure(decisions.create(minimalRequest())));
        FakeClient client = client(request -> CompletableFuture.failedFuture(cancellation));
        assertSafeCancellation(failure(decisionsWith(OPENAI).create(minimalRequest(), with(client))));
        TrackingStream stream = new TrackingStream() {
            @Override
            public int read(byte[] bytes, int offset, int length) {
                throw cancellation;
            }
        };
        client = client(respond(200, () -> stream, Map.of()));
        assertSafeCancellation(failure(decisionsWith(OPENAI).create(minimalRequest(), with(client))));
        assertTrue(stream.closed.get());
    }

    private static void assertSafeCancellation(Throwable error) {
        assertInstanceOf(CancellationException.class, error);
        // JDK 25 get() may wrap a native cancellation in another native cancellation.
        for (Throwable current = error; current != null; current = current.getCause()) {
            assertInstanceOf(CancellationException.class, current);
            assertFalse(String.valueOf(current.getMessage()).contains(OPENAI.apiKey()));
            assertEquals(0, current.getSuppressed().length);
        }
    }

    @Test
    void throwingCloseCannotReplacePrimaryOutcomesOrLeakSuppressedCredentials() throws Exception {
        String secret = "synthetic-close-credential";
        for (String outcome : List.of("success", "http", "invalidResponse", "network", "timeout", "cancel")) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch readerClosed = new CountDownLatch(1);
            AtomicBoolean closed = new AtomicBoolean();
            String payload = outcome.equals("invalidResponse")
                    ? "{"
                    : outcome.equals("http") ? "{\"error\":{\"code\":\"synthetic-primary-code\"}}" : OK_BODY;
            ByteArrayInputStream data = new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8));
            InputStream stream = new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    entered.countDown();
                    if (outcome.equals("network")) {
                        throw new IOException("primary " + secret);
                    }
                    if (outcome.equals("timeout") || outcome.equals("cancel")) {
                        try {
                            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return -1;
                    }
                    return data.read(bytes, offset, length);
                }

                @Override
                public void close() throws IOException {
                    closed.set(true);
                    if (Thread.currentThread().getName().startsWith("copilot-decisions-reader-")) {
                        readerClosed.countDown();
                    }
                    throw new IOException("cleanup " + secret);
                }
            };
            int status = outcome.equals("http") ? 400 : outcome.equals("network") ? 401 : 200;
            List<String> resolutions = new ArrayList<>();
            FakeClient client = client(respond(status, () -> stream, Map.of()));
            CompletableFuture<DecisionsResult> future = decisionsWith(List.of(openai(secret)), resolutions).create(
                    minimalRequest(),
                    with(client).setTimeout(outcome.equals("timeout") ? Duration.ofSeconds(2) : Duration.ZERO));
            try {
                assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                if (outcome.equals("success")) {
                    assertEquals(6, await(future).answers().size());
                } else {
                    if (outcome.equals("cancel")) {
                        assertTrue(future.cancel(true));
                    }
                    Throwable error = failure(future);
                    if (outcome.equals("cancel")) {
                        assertInstanceOf(CancellationException.class, error);
                    } else {
                        DecisionsException typed = assertInstanceOf(DecisionsException.class, error);
                        assertEquals(outcome, typed.getKind().getValue());
                        if (outcome.equals("http")) {
                            assertEquals(400, typed.getStatus());
                            assertEquals("synthetic-primary-code", typed.getErrorCode());
                        } else if (outcome.equals("network")) {
                            assertEquals("primary [REDACTED]", typed.getCause().getMessage());
                        } else if (outcome.equals("timeout")) {
                            assertNull(typed.getCause());
                        }
                    }
                    for (Throwable current = error; current != null; current = current.getCause()) {
                        assertFalse(current.toString().contains(secret));
                        assertEquals(0, current.getSuppressed().length);
                    }
                }
                assertTrue(closed.get());
                assertTrue(readerClosed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertEquals(1, resolutions.size());
                assertEquals(1, client.calls.size());
            } finally {
                release.countDown();
                future.cancel(true);
            }
        }
    }

    @Test
    void earlierInjectedTransportTimeoutRemainsSanitizedNetwork() throws Exception {
        FakeClient client = client(request -> CompletableFuture
                .failedFuture(new HttpTimeoutException("synthetic network timeout " + OPENAI.apiKey())));
        DecisionsException error = decisionsFailure(decisionsWith(OPENAI).create(minimalRequest(), with(client)));
        assertEquals(DecisionsErrorKind.NETWORK, error.getKind());
        assertInstanceOf(DecisionsSanitizedCause.class, error.getCause());
        assertFalse(error.getCause().getMessage().contains(OPENAI.apiKey()));
    }

    @Test
    void callerTimeoutClosesBodyAndRemainsNative() throws Exception {
        BlockingStream stream = new BlockingStream();
        FakeClient client = client(respond(200, () -> stream, Map.of()));
        CompletableFuture<DecisionsResult> future = decisionsWith(OPENAI).create(minimalRequest(),
                with(client).setTimeout(Duration.ZERO));
        assertTrue(stream.reading.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        future.orTimeout(100, TimeUnit.MILLISECONDS);
        assertInstanceOf(TimeoutException.class, failure(future));
        assertTrue(stream.closedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(stream.finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(stream.closed.get());
    }

    @Test
    void callerCancellationWinsBeforeSdkDeadlineAndSkipsLateEndpoint() throws Exception {
        CompletableFuture<SessionProviderGetEndpointResult> endpoint = new CompletableFuture<>();
        FakeClient client = client(respond(200, OK_BODY));
        CompletableFuture<DecisionsResult> future = new SessionDecisions(model -> endpoint).create(minimalRequest(),
                with(client).setTimeout(Duration.ofMillis(150)));
        assertTrue(future.cancel(true));
        Thread.sleep(200);
        endpoint.completeExceptionally(new CredentialFailure("synthetic late RPC failure", "synthetic code"));
        assertInstanceOf(CancellationException.class, failure(future));
        assertEquals(0, client.calls.size());
    }

    /**
     * A transport that observes cancellation but can still deliver a late response.
     */
    static final class LateResponse extends CompletableFuture<HttpResponse<InputStream>> {
        final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled.set(true);
            return false;
        }
    }

    @Test
    void closesLateAcquiredBodiesAfterDeadlineOrCancellation() throws Exception {
        for (boolean cancel : List.of(false, true)) {
            LateResponse pending = new LateResponse();
            FakeClient client = client(request -> pending);
            CompletableFuture<DecisionsResult> future = decisionsWith(OPENAI).create(minimalRequest(),
                    with(client).setTimeout(Duration.ofMillis(150)));
            if (cancel) {
                assertTrue(future.cancel(true));
                assertInstanceOf(CancellationException.class, failure(future));
            } else {
                assertEquals(DecisionsErrorKind.TIMEOUT, decisionsFailure(future).getKind());
            }
            assertTrue(pending.cancelled.get());
            AtomicBoolean closed = new AtomicBoolean();
            ByteArrayInputStream body = new ByteArrayInputStream(OK_BODY.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            };
            pending.complete(new FakeResponse(200, client.calls.get(0).request(), httpHeaders(Map.of()), body));
            assertTrue(closed.get());
            assertEquals(1, client.calls.size());
        }
    }

    @Test
    void closesAuthBodyBeforeStartingCredentialRefresh() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream body = new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        AtomicInteger resolutions = new AtomicInteger();
        SessionDecisions decisions = new SessionDecisions(model -> {
            if (resolutions.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(OPENAI);
            }
            assertTrue(closed.get());
            return CompletableFuture.completedFuture(openai("synthetic-refreshed-key"));
        });
        FakeClient client = client(respond(401, () -> body, Map.of()), respond(200, OK_BODY));
        assertEquals(6, await(decisions.create(minimalRequest(), with(client))).answers().size());
        assertEquals(2, resolutions.get());
    }

    @Test
    void timeoutInterruptsCooperativeRefreshWorkOnTheReader() throws Exception {
        CountDownLatch refreshing = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        SessionDecisions decisions = new SessionDecisions(model -> {
            if (resolutions.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(OPENAI);
            }
            refreshing.countDown();
            try {
                release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return CompletableFuture.completedFuture(openai("synthetic-never-sent"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            } finally {
                finished.countDown();
            }
        });
        FakeClient client = client(respond(401, "{}"));
        try {
            CompletableFuture<DecisionsResult> future = decisions.create(minimalRequest(),
                    with(client).setTimeout(Duration.ofSeconds(1)));
            assertTrue(refreshing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(DecisionsErrorKind.TIMEOUT, decisionsFailure(future).getKind());
            assertTrue(finished.await(1, TimeUnit.SECONDS));
            assertEquals(1, client.calls.size());
        } finally {
            release.countDown();
            if (refreshing.getCount() == 0) {
                assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void leavesNoReaderOrDeadlineThreadsAfterCompletedCalls() throws Exception {
        FakeClient client = client(respond(200, OK_BODY));
        for (int i = 0; i < 10; i++) {
            await(decisionsWith(OPENAI).create(minimalRequest(), with(client)));
        }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (hasDecisionsWorker() && System.nanoTime() < end) {
            Thread.sleep(25);
        }
        assertFalse(hasDecisionsWorker());
    }

    private static boolean hasDecisionsWorker() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && (thread.getName().startsWith("copilot-decisions-reader-")
                        || thread.getName().equals("copilot-decisions-deadline")));
    }

    // ----------------------------------------------------------------- real HTTP

    /** A minimal HTTP/1.1 server that answers one request per connection. */
    static final class MiniServer implements AutoCloseable {
        @FunctionalInterface
        interface Handler {
            void respond(Socket connection, String path, Map<String, String> headers) throws IOException;
        }

        final List<String> hits = Collections.synchronizedList(new ArrayList<>());
        volatile Map<String, String> lastHeaders = Map.of();
        volatile String lastBody;
        private final ServerSocket socket;
        private final Handler handler;
        private final Thread thread;
        private volatile Socket active;

        MiniServer(Function<String, String> handler) throws IOException {
            this((connection, path, headers) -> {
                OutputStream out = connection.getOutputStream();
                out.write(handler.apply(path).getBytes(StandardCharsets.UTF_8));
                out.flush();
            });
        }

        MiniServer(Handler handler) throws IOException {
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
                    active = client;
                    client.setSoTimeout(5000);
                    handle(client);
                } catch (IOException e) {
                    // Closed or client went away.
                } finally {
                    active = null;
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
            handler.respond(client, path, headers);
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
            Socket connection = active;
            if (connection != null) {
                connection.close();
            }
            try {
                thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while stopping the synthetic HTTP server.", e);
            }
            if (thread.isAlive()) {
                throw new IOException("Synthetic HTTP server did not stop.");
            }
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

    @Test
    void slowRealHttpBodiesCloseOnSdkDeadlineAndCallerCancellation() throws Exception {
        for (int status : new int[]{200, 401}) {
            for (boolean cancel : List.of(false, true)) {
                CountDownLatch headersSent = new CountDownLatch(1);
                CountDownLatch peerClosed = new CountDownLatch(1);
                try (MiniServer server = new MiniServer((connection, path, headers) -> {
                    connection.getOutputStream()
                            .write(("HTTP/1.1 " + status
                                    + " Synthetic\r\nContent-Length: 100000\r\nConnection: close\r\n\r\n{")
                                    .getBytes(StandardCharsets.US_ASCII));
                    connection.getOutputStream().flush();
                    headersSent.countDown();
                    try {
                        if (connection.getInputStream().read() == -1) {
                            peerClosed.countDown();
                        }
                    } catch (SocketException e) {
                        peerClosed.countDown();
                    }
                })) {
                    List<String> resolutions = new ArrayList<>();
                    CompletableFuture<DecisionsResult> future = decisionsWith(List.of(local(server.baseUrl(), "{}")),
                            resolutions)
                            .create(minimalRequest(), new DecisionsOptions().setTimeout(Duration.ofSeconds(2)));
                    assertTrue(headersSent.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    if (cancel) {
                        assertTrue(future.cancel(true));
                        assertInstanceOf(CancellationException.class, failure(future));
                    } else {
                        DecisionsException error = decisionsFailure(future);
                        assertEquals(DecisionsErrorKind.TIMEOUT, error.getKind());
                        assertNull(error.getCause());
                    }
                    assertTrue(peerClosed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    assertEquals(1, resolutions.size());
                    assertEquals(List.of("/v1/decisions"), server.hits);
                }
            }
        }
    }

    @Test
    void realHttpRefreshUsesChangedCredentialsExactlyOnce() throws Exception {
        AtomicInteger responses = new AtomicInteger();
        List<String> credentials = Collections.synchronizedList(new ArrayList<>());
        try (MiniServer server = new MiniServer((connection, path, headers) -> {
            credentials.add(headers.get("authorization"));
            boolean first = responses.incrementAndGet() == 1;
            String response = MiniServer.response(first ? 401 : 200, "Synthetic",
                    Map.of("Content-Type", "application/json"), first ? "{}" : OK_BODY);
            connection.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
            connection.getOutputStream().flush();
        })) {
            ObjectNode first = (ObjectNode) json("{\"type\":\"openai\",\"headers\":{}}");
            first.put("baseUrl", server.baseUrl()).put("apiKey", "synthetic-first-key");
            ObjectNode second = first.deepCopy().put("apiKey", "synthetic-second-key");
            List<String> resolutions = new ArrayList<>();
            DecisionsResult result = await(decisionsWith(List.of(endpoint(first), endpoint(second)), resolutions)
                    .create(minimalRequest(), new DecisionsOptions().setTimeout(Duration.ofSeconds(5))));
            assertEquals(6, result.answers().size());
            assertEquals(2, resolutions.size());
            assertEquals(2, server.hits.size());
            assertEquals(List.of("Bearer synthetic-first-key", "Bearer synthetic-second-key"), credentials);
        }
    }

    @Test
    void truncatedRealAuthBodiesDoNotRefreshOrReplay() throws Exception {
        for (int status : new int[]{401, 403}) {
            try (MiniServer server = new MiniServer(path -> "HTTP/1.1 " + status
                    + " Synthetic\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{}")) {
                List<String> resolutions = new ArrayList<>();
                DecisionsException error = decisionsFailure(
                        decisionsWith(List.of(local(server.baseUrl(), "{}")), resolutions).create(minimalRequest(),
                                new DecisionsOptions().setTimeout(Duration.ofSeconds(5))));
                assertEquals(DecisionsErrorKind.NETWORK, error.getKind());
                assertEquals(1, resolutions.size());
                assertEquals(1, server.hits.size());
            }
        }
    }
}
