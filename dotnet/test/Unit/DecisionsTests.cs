/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

#if NET8_0_OR_GREATER
#pragma warning disable GHCP001 // The Decisions API is experimental.

using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using Xunit;

namespace GitHub.Copilot.Test.Unit;

public sealed partial class DecisionsTests
{
    private const int MaxErrorBodyBytes = 64 * 1024;
    private const int MaxResponseBytes = 8 * 1024 * 1024;

    private static readonly string OpenAiEndpoint =
        """{"type":"openai","baseUrl":"https://api.openai.com/v1","apiKey":"sk-test-0001","headers":{}}""";

    private static string OpenAiEndpointWithKey(string apiKey) =>
        $$$"""{"type":"openai","baseUrl":"https://api.openai.com/v1","apiKey":{{{JsonString(apiKey)}}},"headers":{}}""";

    private static string OkBody => Fixtures.Text("response.full.json");

    private static DecisionsRequest MinimalRequest() => new()
    {
        Model = "gpt-6-luna",
        Input = "The export button does nothing when clicked.",
        Questions = [new DecisionsPredicateQuestion { Instructions = "Is this ticket reporting a product defect?" }],
    };

    private static DecisionsRequest FullRequest() => new()
    {
        Model = "gpt-6-luna",
        Input = new DecisionsInputItem[]
        {
            new DecisionsMessage { Role = "developer", Content = "You triage incoming support tickets." },
            new DecisionsMessage
            {
                Role = "system",
                Content = new DecisionsContentPart[] { new DecisionsInputText { Text = "Answer only from the provided context." } },
            },
            new DecisionsMessage
            {
                Role = "user",
                Content = new DecisionsContentPart[]
                {
                    new DecisionsInputText
                    {
                        Text = "Ticket #4821: the export button does nothing.",
                        PromptCacheBreakpoint = new DecisionsCacheBreakpoint(),
                    },
                    new DecisionsInputImage
                    {
                        ImageUrl = "data:image/png;base64,iVBORw0KGgo=",
                        Detail = "high",
                        PromptCacheBreakpoint = new DecisionsCacheBreakpoint { Mode = "explicit" },
                    },
                    new DecisionsInputJson { Data = Json("""{"plan":"enterprise","seats":120}""") },
                    new DecisionsInputJson { Data = Json("""[{"id":1},{"id":2}]""") },
                },
            },
            new DecisionsMessage
            {
                OmitType = true,
                Role = "user",
                Content = new DecisionsContentPart[]
                {
                    new DecisionsInputText { Text = "Customer replied: still broken after refresh." },
                    new DecisionsInputImage { ImageUrl = "https://example.com/full.png", Detail = "original" },
                },
            },
            new DecisionsMessage
            {
                Role = "assistant",
                Content = new DecisionsContentPart[]
                {
                    new DecisionsOutputText { Text = "I will look up the account." },
                    new DecisionsRefusalContent { Refusal = "I cannot share internal notes." },
                },
                Phase = "commentary",
            },
            new DecisionsFunctionCall
            {
                CallId = "call_1",
                Name = "lookup_account",
                Arguments = """{"ticket":4821}""",
                Id = "fc_1",
                Namespace = "crm",
                Status = "completed",
            },
            new DecisionsFunctionCallOutput
            {
                CallId = "call_1",
                Output = """{"tier":"enterprise"}""",
                Id = "fco_1",
                Status = "completed",
            },
            new DecisionsFunctionCallOutput
            {
                CallId = "call_2",
                Output = new DecisionsContentPart[]
                {
                    new DecisionsInputText { Text = "Screenshot attached." },
                    new DecisionsInputImage { ImageUrl = "https://example.com/shot.png", Detail = "low" },
                },
            },
            new DecisionsCustomToolCall
            {
                CallId = "call_3",
                Name = "grep_logs",
                Input = "export failed",
                Id = "ctc_1",
                Namespace = "ops",
                Status = "incomplete",
            },
            new DecisionsCustomToolCallOutput
            {
                CallId = "call_3",
                Output = "3 matches",
                Id = "ctco_1",
                Status = "in_progress",
            },
            new DecisionsRawInputItem(Json("""{"type":"future_item","payload":{"anything":[1,true,null]}}""")),
        },
        Questions =
        [
            new DecisionsPredicateQuestion { Name = "is_bug", Instructions = "Is this ticket reporting a product defect?" },
            new DecisionsChoiceQuestion
            {
                Name = "team",
                Instructions = "Which team should own this ticket?",
                Choices =
                [
                    new DecisionsChoiceOption { Value = "frontend", Description = "UI and client code" },
                    new DecisionsChoiceOption { Value = "backend" },
                ],
            },
            new DecisionsChoiceQuestion
            {
                Instructions = new DecisionsContentPart[]
                {
                    new DecisionsInputText { Text = "Should we escalate?" },
                    new DecisionsInputImage { ImageUrl = "https://example.com/policy.png", Detail = "auto" },
                    new DecisionsInputJson { Data = Json("""{"sla_hours":4}""") },
                },
                Choices =
                [
                    new DecisionsChoiceOption { Value = true, Description = "Escalate now" },
                    new DecisionsChoiceOption { Value = false },
                ],
            },
            new DecisionsScoreQuestion
            {
                Name = "severity",
                Instructions = "How severe is the impact?",
                Levels =
                [
                    new DecisionsScoreLevel { Label = "low", Description = "Cosmetic" },
                    new DecisionsScoreLevel { Label = "medium" },
                    new DecisionsScoreLevel { Label = "high", Description = "Blocks work" },
                ],
            },
        ],
        PromptCacheKey = "support-triage-v1",
        PromptCacheOptions = new DecisionsPromptCacheOptions { Mode = "explicit", Ttl = "30m" },
        SafetyIdentifier = "user-7f3a9c",
    };

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    [Fact]
    public async Task Serializes_Full_Request_To_Golden_Fixture_With_Planned_Headers()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var http = new ScriptedHandler(_ => Respond(200, OkBody));

        var result = await runtime.Session.Decisions.CreateAsync(FullRequest(), new DecisionsOptions { Handler = http });

        Assert.Equal(6, result.Answers.Count);
        var call = Assert.Single(http.Calls);
        Assert.Equal("https://api.openai.com/v1/decisions", call.Url);
        Assert.Equal("POST", call.Method);
        AssertHeaders(
            new Dictionary<string, string>
            {
                ["authorization"] = "Bearer sk-test-0001",
                ["content-type"] = "application/json",
                ["accept"] = "application/json",
            },
            call.Headers);
        AssertJsonEqual(Fixtures.Json("request.full.json"), Json(call.Body));

        var getEndpoint = Assert.Single(runtime.GetEndpointCalls);
        Assert.False(getEndpoint.TryGetProperty("modelId", out _));
    }

    [Fact]
    public async Task Serializes_Minimal_Request()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var http = new ScriptedHandler(_ => Respond(200, OkBody));

        await runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http });

        AssertJsonEqual(Fixtures.Json("request.minimal.json"), Json(Assert.Single(http.Calls).Body));
    }

    [Fact]
    public async Task Normalizes_Nulls_To_Omission()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var http = new ScriptedHandler(_ => Respond(200, OkBody));
        var request = new DecisionsRequest
        {
            Model = "gpt-6-luna",
            Input = new DecisionsInputItem[]
            {
                new DecisionsMessage
                {
                    Role = "user",
                    Content = new DecisionsContentPart[]
                    {
                        new DecisionsInputImage { ImageUrl = "https://example.com/a.png", Detail = null, PromptCacheBreakpoint = null },
                    },
                },
                new DecisionsMessage { Role = "assistant", Content = "ok", Phase = null },
                new DecisionsFunctionCall { CallId = "c", Name = "n", Arguments = "{}", Id = null, Namespace = null, Status = null },
            },
            Questions =
            [
                new DecisionsChoiceQuestion
                {
                    Name = null,
                    Instructions = "q",
                    Choices = [new DecisionsChoiceOption { Value = "a", Description = null }],
                },
                new DecisionsScoreQuestion
                {
                    Instructions = "s",
                    Levels = [new DecisionsScoreLevel { Label = "x", Description = null }],
                },
            ],
            PromptCacheKey = null,
            PromptCacheOptions = null,
            SafetyIdentifier = null,
        };

        await runtime.Session.Decisions.CreateAsync(request, new DecisionsOptions { Handler = http });

        AssertJsonEqual(
            Json("""
                {
                  "model": "gpt-6-luna",
                  "input": [
                    { "type": "message", "role": "user", "content": [{ "type": "input_image", "image_url": "https://example.com/a.png" }] },
                    { "type": "message", "role": "assistant", "content": "ok" },
                    { "type": "function_call", "call_id": "c", "name": "n", "arguments": "{}" }
                  ],
                  "questions": [
                    { "type": "choice", "instructions": "q", "choices": [{ "value": "a" }] },
                    { "type": "score", "instructions": "s", "levels": [{ "label": "x" }] }
                  ]
                }
                """),
            Json(Assert.Single(http.Calls).Body));
    }

    [Theory]
    [InlineData("")]
    [InlineData(null)]
    public async Task Requires_Model_Before_Calling_GetEndpoint(string? model)
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var request = MinimalRequest();
        request.Model = model!;

        var error = await Assert.ThrowsAsync<ArgumentException>(() => runtime.Session.Decisions.CreateAsync(request));

        Assert.Contains("Model is required", error.Message, StringComparison.Ordinal);
        Assert.Empty(runtime.GetEndpointCalls);
    }

    [Fact]
    public async Task Validates_Endpoint_Before_Serializing_Request()
    {
        await using var runtime = await FakeRuntime.StartAsync("""{"type":"anthropic","baseUrl":"https://api.anthropic.com","headers":{}}""");
        var malformed = new DecisionsRequest { Model = "gpt-6-luna", Input = null };

        var error = await Assert.ThrowsAsync<DecisionsException>(() => runtime.Session.Decisions.CreateAsync(malformed));

        Assert.Equal(DecisionsErrorKind.UnsupportedProvider, error.Kind);
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    [Fact]
    public async Task Parses_Full_Response_Fixture()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var http = new ScriptedHandler(_ => Respond(200, OkBody));

        var result = await runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http });

        Assert.Equal("gpt-6-luna-2026-10-01", result.Model);
        Assert.Equal(
            new DecisionsUsage(812, new DecisionsInputTokensDetails(512, 128), 24, new DecisionsOutputTokensDetails(16), 836),
            result.Usage);
        Assert.Equal(6, result.Answers.Count);

        Assert.Equal(new DecisionsPredicateAnswer("is_bug", 0.93), result.Answers[0]);

        var choice = Assert.IsType<DecisionsChoiceAnswer>(result.Answers[1]);
        Assert.Equal("team", choice.Name);
        Assert.True(choice.Choice.IsString);
        Assert.Equal("frontend", choice.Choice.StringValue);
        Assert.Equal(0.81, choice.Confidence);
        Assert.Equal(["frontend", "backend"], choice.Probabilities.Select(p => p.Value.StringValue));

        var boolChoice = Assert.IsType<DecisionsChoiceAnswer>(result.Answers[2]);
        Assert.Null(boolChoice.Name);
        Assert.True(boolChoice.Choice.IsBoolean);
        Assert.True(boolChoice.Choice.BooleanValue);
        Assert.Equal(new DecisionsChoiceValue(true), boolChoice.Choice);
        Assert.NotEqual(new DecisionsChoiceValue("true"), boolChoice.Choice);
        Assert.Equal([true, false], boolChoice.Probabilities.Select(p => p.Value.BooleanValue));

        var score = Assert.IsType<DecisionsScoreAnswer>(result.Answers[3]);
        Assert.Equal("severity", score.Name);
        Assert.Equal(1.37, score.Score);
        Assert.Equal([0L, 1L, 2L], score.Probabilities.Select(p => p.Value));
        Assert.Equal(["low", "medium", "high"], score.Probabilities.Select(p => p.Label));

        Assert.Equal(new DecisionsRefusalAnswer("pii_check"), result.Answers[4]);

        var unknown = Assert.IsType<DecisionsUnknownAnswer>(result.Answers[5]);
        Assert.Equal("future_kind", unknown.Name);
        Assert.Equal("ranking", unknown.WireType);
        AssertJsonEqual(Json("""{"type":"ranking","name":"future_kind","ranked":["a","b"]}"""), unknown.Raw);
    }

    public static TheoryData<string> MalformedResponses => new()
    {
        "null",
        "[]",
        """{"answers":[],"model":"m"}""",
        """{"answers":[{"type":"predicate"}],"model":"m","usage":{}}""",
        """{"answers":[{"type":"choice","choice":3,"confidence":1,"probabilities":[]}],"model":"m","usage":{}}""",
        """{"answers":[{"type":"score","score":1,"confidence":1,"probabilities":[{"label":"a","value":0.5,"probability":1}]}],"model":"m","usage":{}}""",
    };

    [Theory]
    [MemberData(nameof(MalformedResponses))]
    public async Task Rejects_Malformed_Responses_As_InvalidResponse(string body)
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var http = new ScriptedHandler(_ => Respond(200, body));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(DecisionsErrorKind.InvalidResponse, error.Kind);
    }

    [Fact]
    public async Task Reports_Non_Json_Success_Body_As_InvalidResponse()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var http = new ScriptedHandler(_ => Respond(200, "<html>", contentType: null));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(DecisionsErrorKind.InvalidResponse, error.Kind);
    }

    // ------------------------------------------------------------------
    // Shared vectors
    // ------------------------------------------------------------------

    public static TheoryData<string> UrlVectors => Fixtures.VectorNames("url");

    public static TheoryData<string> HeaderVectors => Fixtures.VectorNames("headers");

    public static TheoryData<string> PreflightVectors => Fixtures.VectorNames("preflight");

    public static TheoryData<string> RetryVectors => Fixtures.VectorNames("retry");

    public static TheoryData<string> RedactionVectors => Fixtures.VectorNames("redaction");

    public static TheoryData<string> RedactionLengthVectors => Fixtures.VectorNames("redactionLength");

    [Fact]
    public void Vector_Groups_Are_All_Present()
    {
        Assert.Equal(32, UrlVectors.Cast<object>().Count());
        Assert.Equal(21, HeaderVectors.Cast<object>().Count());
        Assert.Equal(7, PreflightVectors.Cast<object>().Count());
        Assert.Equal(10, RetryVectors.Cast<object>().Count());
        Assert.Equal(12, RedactionVectors.Cast<object>().Count());
        Assert.Equal(4, RedactionLengthVectors.Cast<object>().Count());
    }

    [Theory]
    [MemberData(nameof(UrlVectors))]
    public async Task Url_Vector(string name)
    {
        var vector = Fixtures.Vector("url", name);
        await using var runtime = await FakeRuntime.StartAsync(vector.GetProperty("endpoint").GetRawText());
        var http = new ScriptedHandler(_ => Respond(200, OkBody));
        var create = runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http });

        if (vector.TryGetProperty("error", out var expectedError))
        {
            var error = await Assert.ThrowsAsync<DecisionsException>(() => create);
            Assert.Equal(ParseKind(expectedError.GetString()!), error.Kind);
            Assert.DoesNotContain("secretpassword", error.Message, StringComparison.Ordinal);
            Assert.DoesNotContain("secretpassword", error.ToString(), StringComparison.Ordinal);
            Assert.Empty(http.Calls);
        }
        else
        {
            await create;
            Assert.Equal(vector.GetProperty("expected").GetString(), Assert.Single(http.Calls).Url);
        }

        Fixtures.RecordPassed("url", name);
    }

    [Theory]
    [MemberData(nameof(HeaderVectors))]
    public async Task Headers_Vector(string name)
    {
        var vector = Fixtures.Vector("headers", name);
        await using var runtime = await FakeRuntime.StartAsync(vector.GetProperty("endpoint").GetRawText());
        var http = new ScriptedHandler(_ => Respond(200, OkBody));
        var create = runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http });

        if (vector.TryGetProperty("error", out var expectedError))
        {
            var error = await Assert.ThrowsAsync<DecisionsException>(() => create);
            Assert.Equal(ParseKind(expectedError.GetString()!), error.Kind);
            Assert.Empty(http.Calls);
        }
        else
        {
            await create;
            var expected = vector.GetProperty("expected").EnumerateObject().ToDictionary(p => p.Name, p => p.Value.GetString()!);
            AssertHeaders(expected, Assert.Single(http.Calls).Headers);
        }

        Fixtures.RecordPassed("headers", name);
    }

    [Theory]
    [MemberData(nameof(PreflightVectors))]
    public async Task Preflight_Vector(string name)
    {
        var vector = Fixtures.Vector("preflight", name);
        await using var runtime = await FakeRuntime.StartAsync(vector.GetProperty("endpoint").GetRawText());
        var http = new ScriptedHandler(_ => Respond(200, OkBody));
        var request = MinimalRequest();
        request.Model = vector.GetProperty("model").GetString()!;
        var create = runtime.Session.Decisions.CreateAsync(request, new DecisionsOptions { Handler = http });

        var expectedError = vector.GetProperty("error");
        if (expectedError.ValueKind == JsonValueKind.String)
        {
            var error = await Assert.ThrowsAsync<DecisionsException>(() => create);
            Assert.Equal(ParseKind(expectedError.GetString()!), error.Kind);
            Assert.Empty(http.Calls);
        }
        else
        {
            await create;
            Assert.Single(http.Calls);
        }

        Fixtures.RecordPassed("preflight", name);
    }

    [Theory]
    [MemberData(nameof(RetryVectors))]
    public async Task Retry_Vector(string name)
    {
        var vector = Fixtures.Vector("retry", name);
        await using var runtime = await FakeRuntime.StartAsync(
            vector.GetProperty("first").GetRawText(),
            vector.GetProperty("second").GetRawText());
        var http = new ScriptedHandler(
            _ => Respond(401, """{"error":{"type":"auth","code":"expired"}}"""),
            _ => Respond(200, OkBody));
        var create = runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http });

        if (vector.GetProperty("retry").GetBoolean())
        {
            var result = await create;
            Assert.Equal("gpt-6-luna-2026-10-01", result.Model);
            Assert.Equal(2, http.Calls.Length);
            var secondUrl = UrlFor(vector.GetProperty("second"));
            Assert.Equal(secondUrl, http.Calls[1].Url);
        }
        else
        {
            var error = await Assert.ThrowsAsync<DecisionsException>(() => create);
            Assert.Equal(DecisionsErrorKind.Http, error.Kind);
            Assert.Equal(401, error.Status);
            Assert.Equal("expired", error.ErrorCode);
            Assert.Single(http.Calls);
        }

        Assert.Equal(2, runtime.GetEndpointCalls.Length);
        Fixtures.RecordPassed("retry", name);
    }

    [Theory]
    [MemberData(nameof(RedactionVectors))]
    public async Task Redaction_Vector(string name)
    {
        var vector = Fixtures.Vector("redaction", name);
        var payload = vector.GetProperty("body").GetString()!;
        if (vector.TryGetProperty("bodyRepeat", out var repeat))
        {
            payload = payload.Replace(
                "{{repeat}}",
                string.Concat(Enumerable.Repeat(repeat.GetProperty("text").GetString()!, repeat.GetProperty("count").GetInt32())),
                StringComparison.Ordinal);
        }

        if (vector.TryGetProperty("bodyPrefix", out var prefix))
        {
            payload = new string(prefix.GetProperty("char").GetString()![0], prefix.GetProperty("count").GetInt32()) + payload;
        }

        var status = vector.GetProperty("status").GetInt32();
        await using var runtime = await FakeRuntime.StartAsync(vector.GetProperty("endpoint").GetRawText());
        var http = new ScriptedHandler(_ => Respond(status, payload, contentType: null, ("x-request-id", "req_123")));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(DecisionsErrorKind.Http, error.Kind);
        Assert.Equal(status, error.Status);
        Assert.Equal("req_123", error.RequestId);
        Assert.Equal(NullableString(vector.GetProperty("errorCode")), error.ErrorCode);
        Assert.Equal(NullableString(vector.GetProperty("errorType")), error.ErrorType);
        if (vector.TryGetProperty("bodyTruncated", out var bodyTruncated))
        {
            Assert.Equal(bodyTruncated.GetBoolean(), error.BodyTruncated);
        }

        if (vector.TryGetProperty("maxBodyBytes", out var maxBodyBytes))
        {
            Assert.True(Encoding.UTF8.GetByteCount(error.Body ?? string.Empty) <= maxBodyBytes.GetInt32());
        }

        var surfaces = string.Join(
            "\n",
            error.Message,
            error.Body ?? string.Empty,
            error.ErrorCode ?? string.Empty,
            error.ErrorType ?? string.Empty,
            error.ErrorParam ?? string.Empty,
            error.RequestId ?? string.Empty,
            error.ToString());
        foreach (var secret in vector.GetProperty("mustNotContain").EnumerateArray())
        {
            Assert.DoesNotContain(secret.GetString()!, surfaces, StringComparison.Ordinal);
        }

        if (error.BodyTruncated != true)
        {
            Assert.Contains("[REDACTED]", error.Body, StringComparison.Ordinal);
        }
        else
        {
            Assert.True(Encoding.UTF8.GetByteCount(error.Body!) <= MaxErrorBodyBytes);
            Assert.False(error.Body!.EndsWith('\uFFFD'), "Split trailing character must be dropped, not replaced.");
        }

        Assert.True(Encoding.UTF8.GetByteCount(error.Message) <= MaxErrorBodyBytes);
        Fixtures.RecordPassed("redaction", name);
    }

    [Theory]
    [MemberData(nameof(RedactionLengthVectors))]
    public async Task RedactionLength_Vector(string name)
    {
        var vector = Fixtures.Vector("redactionLength", name);
        var value = vector.GetProperty("value").GetString()!;
        var echoed = $"echo:{value.Trim()}:end";
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpointWithKey(value));
        var http = new ScriptedHandler(_ => Respond(400, echoed, contentType: null));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(vector.GetProperty("isSecret").GetBoolean() ? "echo:[REDACTED]:end" : echoed, error.Body);
        Fixtures.RecordPassed("redactionLength", name);
    }

    // ------------------------------------------------------------------
    // CreateAsync behavior
    // ------------------------------------------------------------------

    [Fact]
    public async Task Session_Decisions_Is_Lazily_Created_Once()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);

        Assert.Same(runtime.Session.Decisions, runtime.Session.Decisions);
    }

    [Fact]
    public async Task Retries_At_Most_Once()
    {
        await using var runtime = await FakeRuntime.StartAsync(
            OpenAiEndpoint,
            OpenAiEndpointWithKey("sk-test-0002"),
            OpenAiEndpointWithKey("sk-test-0003"));
        var http = new ScriptedHandler(_ => Respond(403, "{}"));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(DecisionsErrorKind.Http, error.Kind);
        Assert.Equal(403, error.Status);
        Assert.Equal(2, http.Calls.Length);
        Assert.Equal(2, runtime.GetEndpointCalls.Length);
    }

    [Fact]
    public async Task Passes_EndpointModelId_Verbatim()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var http = new ScriptedHandler(_ => Respond(200, OkBody));

        await runtime.Session.Decisions.CreateAsync(
            MinimalRequest(),
            new DecisionsOptions { Handler = http, EndpointModelId = "endpoint-model" });

        var parameters = Assert.Single(runtime.GetEndpointCalls);
        Assert.Equal("endpoint-model", parameters.GetProperty("modelId").GetString());
        Assert.Equal(runtime.Session.SessionId, parameters.GetProperty("sessionId").GetString());
    }

    [Fact]
    public async Task Calls_GetEndpoint_On_Every_Create()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var http = new ScriptedHandler(_ => Respond(200, OkBody));
        var options = new DecisionsOptions { Handler = http };

        await runtime.Session.Decisions.CreateAsync(MinimalRequest(), options);
        await runtime.Session.Decisions.CreateAsync(MinimalRequest(), options);

        Assert.Equal(2, runtime.GetEndpointCalls.Length);
    }

    [Fact]
    public async Task Maps_OpenAI_Error_Envelope()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var http = new ScriptedHandler(_ => Respond(400, Fixtures.Text("error.json"), "application/json", ("x-request-id", "req_9")));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(DecisionsErrorKind.Http, error.Kind);
        Assert.Equal(400, error.Status);
        Assert.Equal("invalid_request_error", error.ErrorType);
        Assert.Equal("unsupported_model", error.ErrorCode);
        Assert.Equal("model", error.ErrorParam);
        Assert.Equal("req_9", error.RequestId);
        Assert.False(error.BodyTruncated);
        Assert.Contains("does not support the Decisions API", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Bounds_Large_Error_Bodies_At_64_KiB()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var http = new ScriptedHandler(_ => Respond(500, new string('x', MaxErrorBodyBytes + 1000), contentType: null));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(DecisionsErrorKind.Http, error.Kind);
        Assert.True(error.BodyTruncated);
        Assert.Equal(MaxErrorBodyBytes, error.Body!.Length);
    }

    [Theory]
    [InlineData("\u20AC", 1)]
    [InlineData("\u00E9", 1)]
    [InlineData("\U0001F600", 1)]
    [InlineData("\U0001F600", 3)]
    public async Task Truncates_Error_Body_On_Utf8_Boundary_Without_Replacement_Char(string multiByte, int prefixShortfall)
    {
        // The multi-byte character straddles the 65,536-byte cut and must be dropped entirely.
        var prefix = new string('x', MaxErrorBodyBytes - prefixShortfall);
        var payload = prefix + multiByte + new string('y', 100);
        Assert.True(Encoding.UTF8.GetByteCount(prefix + multiByte) > MaxErrorBodyBytes);
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var http = new ScriptedHandler(_ => Respond(500, payload, contentType: null));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.True(error.BodyTruncated);
        Assert.Equal(prefix, error.Body);
        Assert.DoesNotContain('\uFFFD', error.Body!);
        Assert.True(Encoding.UTF8.GetByteCount(error.Body!) <= MaxErrorBodyBytes);
    }

    [Fact]
    public async Task Truncation_Keeps_Exact_Boundary_And_Lossy_Decodes_Invalid_Bytes_Elsewhere()
    {
        // 0xFF early in the body is invalid UTF-8 and still decodes to U+FFFD (3 bytes once re-encoded);
        // the 3-byte euro then ends exactly at byte 65,536 of the redacted body and is kept intact.
        var bytes = new List<byte> { (byte)'a', 0xFF, (byte)'b' };
        bytes.AddRange(Enumerable.Repeat((byte)'x', MaxErrorBodyBytes - 5 - 3));
        bytes.AddRange(Encoding.UTF8.GetBytes("\u20AC"));
        bytes.AddRange(Enumerable.Repeat((byte)'z', 500));
        var payload = bytes.ToArray();
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var http = new ScriptedHandler(_ => new HttpResponseMessage((HttpStatusCode)500) { Content = new ByteArrayContent(payload) });

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.True(error.BodyTruncated);
        Assert.StartsWith("a\uFFFDb", error.Body, StringComparison.Ordinal);
        Assert.EndsWith("x\u20AC", error.Body, StringComparison.Ordinal);
        Assert.True(Encoding.UTF8.GetByteCount(error.Body!) <= MaxErrorBodyBytes);
    }

    [Fact]
    public async Task Cancels_Oversized_Error_Bodies()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var stream = new EndlessStream();
        var http = new ScriptedHandler(_ => Respond(500, new StreamContent(stream)));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.True(error.BodyTruncated);
        Assert.True(stream.Disposed);
        Assert.True(stream.BytesRead <= MaxErrorBodyBytes + (64 * 1024), $"Read {stream.BytesRead} bytes.");
    }

    [Fact]
    public async Task Caps_Success_Bodies()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var huge = new byte[MaxResponseBytes + 1];
        huge.AsSpan().Fill((byte)' ');
        var http = new ScriptedHandler(_ => Respond(200, new ByteArrayContent(huge)));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(DecisionsErrorKind.InvalidResponse, error.Kind);
        Assert.Contains(MaxResponseBytes.ToString(CultureInfo.InvariantCulture), error.Message, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(200)]
    [InlineData(500)]
    public async Task Maps_Mid_Body_Stream_Failure_To_Network(int status)
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var http = new ScriptedHandler(_ => Respond(status, new StreamContent(new FailingStream())));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(DecisionsErrorKind.Network, error.Kind);
        var cause = Assert.IsType<DecisionsTransportException>(error.InnerException);
        Assert.Equal("socket hang up", cause.Message);
        Assert.Equal(typeof(IOException).FullName, cause.SourceTypeName);
    }

    [Fact]
    public async Task Wraps_Transport_Failures_With_Sanitized_Cause()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        var http = new ScriptedHandler(_ => throw new HttpRequestException(
            "fetch failed for https://api.openai.com/v1/decisions with Bearer sk-test-0001",
            new SocketException((int)SocketError.ConnectionReset)));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(DecisionsErrorKind.Network, error.Kind);
        var cause = Assert.IsType<DecisionsTransportException>(error.InnerException);
        Assert.Equal(typeof(HttpRequestException).FullName, cause.SourceTypeName);
        Assert.Equal("ConnectionReset", cause.Code);
        Assert.Null(cause.InnerException);
        Assert.DoesNotContain("sk-test-0001", cause.Message, StringComparison.Ordinal);
        Assert.DoesNotContain("sk-test-0001", error.ToString(), StringComparison.Ordinal);
        Assert.Contains("[REDACTED]", cause.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Propagates_EndpointUnavailable_When_Refresh_Fails()
    {
        await using var runtime = await FakeRuntime.StartAsync(call => call == 1
            ? OpenAiEndpoint
            : throw new RpcFailure("runtime went away"));
        var http = new ScriptedHandler(_ => Respond(401, "{}"));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(DecisionsErrorKind.EndpointUnavailable, error.Kind);
        Assert.Null(error.InnerException);
        Assert.Single(http.Calls);
    }

    [Fact]
    public async Task Rethrows_Cancellation_During_Refresh_GetEndpoint()
    {
        using var cts = new CancellationTokenSource();
        await using var runtime = await FakeRuntime.StartAsync(call =>
        {
            if (call == 2)
            {
                cts.Cancel();
            }

            return OpenAiEndpoint;
        });
        var http = new ScriptedHandler(_ => Respond(401, "{}"));

        var error = await Record.ExceptionAsync(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }, cts.Token));

        Assert.IsAssignableFrom<OperationCanceledException>(error);
        Assert.Single(http.Calls);
    }

    [Fact]
    public async Task Drains_401_Body_Before_Retrying()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint, OpenAiEndpointWithKey("sk-test-0002"));
        var events = new List<string>();
        var attempt = 0;
        var http = new ScriptedHandler(_ =>
        {
            lock (events)
            {
                events.Add($"fetch{attempt}");
            }

            return attempt++ == 0
                ? Respond(401, new StreamContent(new TrackingStream("{}"u8.ToArray(), () => { lock (events) { events.Add("read"); } })))
                : Respond(200, OkBody);
        });

        await runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http });

        Assert.True(events.IndexOf("read") >= 0 && events.IndexOf("read") < events.IndexOf("fetch1"), string.Join(",", events));
    }

    [Fact]
    public async Task Surfaces_Gate_Hint_Only_When_Runtime_Mentions_It()
    {
        await using (var gated = await FakeRuntime.StartAsync(_ => throw new RpcFailure(
            "Provider endpoint access is disabled. To enable this API, set COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true")))
        {
            var error = await Assert.ThrowsAsync<DecisionsException>(() => gated.Session.Decisions.CreateAsync(MinimalRequest()));
            Assert.Equal(DecisionsErrorKind.EndpointUnavailable, error.Kind);
            Assert.Contains("COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true", error.Message, StringComparison.Ordinal);
        }

        await using (var other = await FakeRuntime.StartAsync(_ => throw new RpcFailure("Session not found")))
        {
            var error = await Assert.ThrowsAsync<DecisionsException>(() => other.Session.Decisions.CreateAsync(MinimalRequest()));
            Assert.Equal(DecisionsErrorKind.EndpointUnavailable, error.Kind);
            Assert.DoesNotContain("COPILOT_ALLOW_GET_PROVIDER_ENDPOINT", error.Message, StringComparison.Ordinal);
            Assert.Null(error.InnerException);
        }
    }

    [Fact]
    public async Task Rejects_Unsupported_Providers_Before_Sending()
    {
        await using var runtime = await FakeRuntime.StartAsync("""{"type":"anthropic","baseUrl":"https://api.anthropic.com","headers":{}}""");
        var http = new ScriptedHandler(_ => Respond(200, OkBody));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(DecisionsErrorKind.UnsupportedProvider, error.Kind);
        Assert.Empty(http.Calls);
    }

    [Fact]
    public async Task Rejects_Injected_Handler_That_Follows_Redirects()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var follows = new HttpClientHandler { AllowAutoRedirect = true };
        using var socketsFollows = new SocketsHttpHandler { AllowAutoRedirect = true };
        using var wrapped = new PassThroughHandler(new SocketsHttpHandler());

        foreach (var handler in new HttpMessageHandler[] { follows, socketsFollows, wrapped })
        {
            var error = await Assert.ThrowsAsync<ArgumentException>(
                () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = handler }));
            Assert.Contains("redirect", error.Message, StringComparison.OrdinalIgnoreCase);
        }

        Assert.Equal(3, runtime.GetEndpointCalls.Length);
    }

    [Fact]
    public async Task Cancellation_Precedes_Redirect_Handler_Check()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var follows = new HttpClientHandler { AllowAutoRedirect = true };
        using var cts = new CancellationTokenSource();
        cts.Cancel();

        var error = await Record.ExceptionAsync(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = follows }, cts.Token));

        Assert.IsAssignableFrom<OperationCanceledException>(error);
        Assert.Empty(runtime.GetEndpointCalls);
    }

    [Fact]
    public async Task Does_Not_Call_GetEndpoint_When_Already_Cancelled()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var cts = new CancellationTokenSource();
        cts.Cancel();

        var error = await Record.ExceptionAsync(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), cancellationToken: cts.Token));

        Assert.IsAssignableFrom<OperationCanceledException>(error);
        Assert.Empty(runtime.GetEndpointCalls);
    }

    [Fact]
    public async Task Checks_Cancellation_After_GetEndpoint()
    {
        using var cts = new CancellationTokenSource();
        await using var runtime = await FakeRuntime.StartAsync(_ =>
        {
            cts.Cancel();
            return OpenAiEndpoint;
        });
        var http = new ScriptedHandler(_ => Respond(200, OkBody));

        var error = await Record.ExceptionAsync(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }, cts.Token));

        Assert.IsAssignableFrom<OperationCanceledException>(error);
        Assert.Empty(http.Calls);
    }

    [Fact]
    public async Task Passes_Token_To_Handler_And_Rethrows_Cancellation()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var cts = new CancellationTokenSource();
        CancellationToken seenToken = default;
        var http = new ScriptedHandler(async (_, token) =>
        {
            seenToken = token;
            cts.Cancel();
            await Task.Delay(Timeout.Infinite, token);
            throw new InvalidOperationException("unreachable");
        });

        var error = await Record.ExceptionAsync(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }, cts.Token));

        Assert.IsAssignableFrom<OperationCanceledException>(error);
        Assert.True(seenToken.CanBeCanceled);
    }

    // ------------------------------------------------------------------
    // Real HTTP
    // ------------------------------------------------------------------

    [Fact]
    public async Task Round_Trips_Over_Real_Http()
    {
        await using var server = LoopbackHttpServer.Start((_, _, _) => (200, "application/json", null, OkBody));
        await using var runtime = await FakeRuntime.StartAsync(
            $$$"""{"type":"openai","baseUrl":"{{{server.BaseUrl}}}","apiKey":"sk-test-0001","headers":{"X-Extra":"1"}}""");

        var result = await runtime.Session.Decisions.CreateAsync(MinimalRequest());

        Assert.IsType<DecisionsPredicateAnswer>(result.Answers[0]);
        var hit = Assert.Single(server.Hits);
        Assert.Equal("/v1/decisions", hit.Path);
        Assert.Equal("POST", hit.Method);
        Assert.Equal("Bearer sk-test-0001", hit.Headers["authorization"]);
        Assert.Equal("1", hit.Headers["x-extra"]);
        Assert.Equal("application/json", hit.Headers["content-type"]);
        Assert.Equal("application/json", hit.Headers["accept"]);
        AssertJsonEqual(Fixtures.Json("request.minimal.json"), Json(hit.Body));
    }

    [Fact]
    public async Task Does_Not_Follow_Redirects()
    {
        await using var server = LoopbackHttpServer.Start((path, _, _) => path.EndsWith("/decisions", StringComparison.Ordinal)
            ? (307, null, "/elsewhere", string.Empty)
            : (200, "application/json", null, OkBody));
        await using var runtime = await FakeRuntime.StartAsync(
            $$$"""{"type":"openai","baseUrl":"{{{server.BaseUrl}}}","apiKey":"sk-test-0001","headers":{}}""");

        var error = await Assert.ThrowsAsync<DecisionsException>(() => runtime.Session.Decisions.CreateAsync(MinimalRequest()));

        Assert.Equal(DecisionsErrorKind.Http, error.Kind);
        Assert.Equal(307, error.Status);
        Assert.Contains("redirects are not followed", error.Message, StringComparison.Ordinal);
        Assert.Equal(["/v1/decisions"], server.Hits.Select(h => h.Path));
    }

    [Fact]
    public async Task Injected_Handler_Without_Redirects_Does_Not_Follow()
    {
        await using var server = LoopbackHttpServer.Start((path, _, _) => path.EndsWith("/decisions", StringComparison.Ordinal)
            ? (307, null, "/elsewhere", string.Empty)
            : (200, "application/json", null, OkBody));
        await using var runtime = await FakeRuntime.StartAsync(
            $$$"""{"type":"openai","baseUrl":"{{{server.BaseUrl}}}","apiKey":"sk-test-0001","headers":{}}""");
        using var handler = new SocketsHttpHandler { AllowAutoRedirect = false };

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = handler }));

        Assert.Equal(307, error.Status);
        Assert.Equal(["/v1/decisions"], server.Hits.Select(h => h.Path));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static DecisionsErrorKind ParseKind(string kind) =>
        Enum.Parse<DecisionsErrorKind>(char.ToUpperInvariant(kind[0]) + kind[1..]);

    private static string? NullableString(JsonElement element) =>
        element.ValueKind == JsonValueKind.Null ? null : element.GetString();

    private static string UrlFor(JsonElement endpoint)
    {
        // Retry vectors use openai-type base URLs without fragments; the path is appended before any query.
        Assert.Equal("openai", endpoint.GetProperty("type").GetString());
        var baseUrl = endpoint.GetProperty("baseUrl").GetString()!;
        var query = baseUrl.IndexOf('?', StringComparison.Ordinal);
        return query < 0
            ? baseUrl.TrimEnd('/') + "/decisions"
            : baseUrl[..query].TrimEnd('/') + "/decisions" + baseUrl[query..];
    }

    private static JsonElement Json(string text)
    {
        using var document = JsonDocument.Parse(text);
        return document.RootElement.Clone();
    }

    private static JsonElement Json(byte[] utf8)
    {
        using var document = JsonDocument.Parse(utf8);
        return document.RootElement.Clone();
    }

    private static string JsonString(string value) => "\"" + JsonEncodedText.Encode(value, System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping) + "\"";

    private static void AssertJsonEqual(JsonElement expected, JsonElement actual) =>
        Assert.True(JsonElement.DeepEquals(expected, actual), $"Expected:\n{expected.GetRawText()}\nActual:\n{actual.GetRawText()}");

    private static void AssertHeaders(IReadOnlyDictionary<string, string> expected, IReadOnlyDictionary<string, string> actual) =>
        Assert.Equal(
            expected.OrderBy(p => p.Key, StringComparer.Ordinal).ToArray(),
            actual.OrderBy(p => p.Key, StringComparer.Ordinal).ToArray());

    private static HttpResponseMessage Respond(int status, string body, string? contentType = "application/json", params (string Name, string Value)[] headers)
    {
        var content = new ByteArrayContent(Encoding.UTF8.GetBytes(body));
        if (contentType is not null)
        {
            content.Headers.TryAddWithoutValidation("Content-Type", contentType);
        }

        var response = Respond(status, content);
        foreach (var (name, value) in headers)
        {
            response.Headers.TryAddWithoutValidation(name, value);
        }

        return response;
    }

    private static HttpResponseMessage Respond(int status, HttpContent content) =>
        new((HttpStatusCode)status) { Content = content };

    private static class Fixtures
    {
        private static readonly Lazy<string> s_directory = new(FindDirectory);
        private static readonly Lazy<JsonElement> s_vectors = new(() => DecisionsTests.Json(Text("vectors.json")));
        private static readonly Lazy<JsonElement> s_production = new(() => DecisionsTests.Json(Text("production-vectors.json")));
        private static readonly HashSet<(string File, string Group, string Name)> s_passed = [];
        private static readonly object s_censusLock = new();

        public static string Text(string name) => File.ReadAllText(Path.Combine(s_directory.Value, name));

        public static JsonElement Json(string name) => DecisionsTests.Json(Text(name));

        public static TheoryData<string> VectorNames(string group, string file = "vectors.json")
        {
            var data = new TheoryData<string>();
            foreach (var vector in Vectors(file).GetProperty(group).EnumerateArray())
            {
                data.Add(vector.GetProperty("name").GetString()!);
            }

            return data;
        }

        public static JsonElement Vector(string group, string name, string file = "vectors.json") =>
            Vectors(file).GetProperty(group).EnumerateArray().Single(v => v.GetProperty("name").GetString() == name);

        private static JsonElement Vectors(string file) => file switch
        {
            "vectors.json" => s_vectors.Value,
            "production-vectors.json" => s_production.Value,
            _ => throw new ArgumentException("Unknown vector file.", nameof(file)),
        };

        public static void RecordPassed(string group, string name, string file = "vectors.json")
        {
            var path = Environment.GetEnvironmentVariable("COPILOT_DECISIONS_VECTOR_CENSUS");
            if (string.IsNullOrEmpty(path))
            {
                return;
            }

            lock (s_censusLock)
            {
                s_passed.Add((file, group, name));
                using var buffer = new MemoryStream();
                using (var writer = new Utf8JsonWriter(buffer))
                {
                    writer.WriteStartObject();
                    writer.WriteString("sdk", "dotnet");
                    writer.WriteNumber("schemaVersion", 1);
                    writer.WriteStartArray("cases");
                    foreach (var entry in s_passed.OrderBy(v => v.File, StringComparer.Ordinal)
                        .ThenBy(v => v.Group, StringComparer.Ordinal).ThenBy(v => v.Name, StringComparer.Ordinal))
                    {
                        writer.WriteStartObject();
                        writer.WriteString("file", entry.File);
                        writer.WriteString("group", entry.Group);
                        writer.WriteString("name", entry.Name);
                        writer.WriteString("status", "passed");
                        writer.WriteEndObject();
                    }

                    writer.WriteEndArray();
                    writer.WriteEndObject();
                }

                File.WriteAllBytes(path, buffer.ToArray());
            }
        }

        private static string FindDirectory()
        {
            for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
            {
                var candidate = Path.Combine(directory.FullName, "test", "fixtures", "decisions");
                if (File.Exists(Path.Combine(candidate, "vectors.json")))
                {
                    return candidate;
                }
            }

            throw new InvalidOperationException("Could not locate test/fixtures/decisions from " + AppContext.BaseDirectory);
        }
    }

    private sealed record RecordedCall(string Url, string Method, IReadOnlyDictionary<string, string> Headers, byte[] Body);

    private sealed class ScriptedHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>>[] _responses;
        private readonly List<RecordedCall> _calls = [];
        private int _index;

        public bool Disposed { get; private set; }

        public ScriptedHandler(params Func<HttpRequestMessage, HttpResponseMessage>[] responses)
        {
            _responses = responses.Select(r => (Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>>)((request, _) => Task.FromResult(r(request)))).ToArray();
        }

        public ScriptedHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> response)
        {
            _responses = [response];
        }

        public RecordedCall[] Calls
        {
            get
            {
                lock (_calls)
                {
                    return _calls.ToArray();
                }
            }
        }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var headers = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var header in request.Headers.NonValidated)
            {
                headers[header.Key.ToLowerInvariant()] = header.Value.ToString();
            }

            var body = Array.Empty<byte>();
            if (request.Content is not null)
            {
                foreach (var header in request.Content.Headers.NonValidated)
                {
                    if (!string.Equals(header.Key, "Content-Length", StringComparison.OrdinalIgnoreCase))
                    {
                        headers[header.Key.ToLowerInvariant()] = header.Value.ToString();
                    }
                }

                body = await request.Content.ReadAsByteArrayAsync(cancellationToken);
            }

            lock (_calls)
            {
                _calls.Add(new RecordedCall(request.RequestUri!.OriginalString, request.Method.Method, headers, body));
            }

            var next = _responses[Math.Min(Interlocked.Increment(ref _index) - 1, _responses.Length - 1)];
            var response = await next(request, cancellationToken);
            response.RequestMessage = request;
            return response;
        }

        protected override void Dispose(bool disposing)
        {
            Disposed = true;
            base.Dispose(disposing);
        }
    }

    private sealed class PassThroughHandler(HttpMessageHandler inner) : DelegatingHandler(inner);

    private sealed class EndlessStream : Stream
    {
        public long BytesRead { get; private set; }

        public bool Disposed { get; private set; }

        public override bool CanRead => true;

        public override bool CanSeek => false;

        public override bool CanWrite => false;

        public override long Length => throw new NotSupportedException();

        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override int Read(byte[] buffer, int offset, int count)
        {
            var n = Math.Min(count, 16 * 1024);
            buffer.AsSpan(offset, n).Fill((byte)'x');
            BytesRead += n;
            return n;
        }

        public override void Flush()
        {
        }

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

        public override void SetLength(long value) => throw new NotSupportedException();

        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        protected override void Dispose(bool disposing)
        {
            Disposed = true;
            base.Dispose(disposing);
        }
    }

    private sealed class FailingStream : Stream
    {
        private bool _sent;

        public bool Disposed { get; private set; }

        public override bool CanRead => true;

        public override bool CanSeek => false;

        public override bool CanWrite => false;

        public override long Length => throw new NotSupportedException();

        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override int Read(byte[] buffer, int offset, int count)
        {
            if (_sent)
            {
                throw new IOException("socket hang up");
            }

            _sent = true;
            var chunk = """{"answers":"""u8;
            chunk.CopyTo(buffer.AsSpan(offset, count));
            return chunk.Length;
        }

        public override void Flush()
        {
        }

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

        public override void SetLength(long value) => throw new NotSupportedException();

        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        protected override void Dispose(bool disposing)
        {
            Disposed = true;
            base.Dispose(disposing);
        }
    }

    private sealed class TrackingStream(byte[] data, Action onRead) : MemoryStream(data)
    {
        public bool Disposed { get; private set; }

        public override int Read(byte[] buffer, int offset, int count)
        {
            onRead();
            return base.Read(buffer, offset, count);
        }

        public override int Read(Span<byte> buffer)
        {
            onRead();
            return base.Read(buffer);
        }

        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken)
        {
            onRead();
            return base.ReadAsync(buffer, offset, count, cancellationToken);
        }

        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            onRead();
            return base.ReadAsync(buffer, cancellationToken);
        }

        protected override void Dispose(bool disposing)
        {
            Disposed = true;
            base.Dispose(disposing);
        }
    }

    private sealed class RpcFailure(string message) : Exception(message);

    /// <summary>Minimal JSON-RPC runtime that answers session setup and <c>session.provider.getEndpoint</c>.</summary>
    private sealed class FakeRuntime : IAsyncDisposable
    {
        private readonly TcpListener _listener;
        private readonly Func<int, CancellationToken, Task<string>> _getEndpoint;
        private readonly CancellationTokenSource _cts = new();
        private readonly List<JsonElement> _getEndpointCalls = [];
        private readonly Task _serverTask;
        private CopilotClient? _client;
        private CopilotSession? _session;

        private FakeRuntime(TcpListener listener, Func<int, CancellationToken, Task<string>> getEndpoint)
        {
            _listener = listener;
            _getEndpoint = getEndpoint;
            _serverTask = RunAsync();
        }

        public CopilotSession Session => _session!;

        public JsonElement[] GetEndpointCalls
        {
            get
            {
                lock (_getEndpointCalls)
                {
                    return _getEndpointCalls.ToArray();
                }
            }
        }

        public static Task<FakeRuntime> StartAsync(params string[] endpoints) =>
            StartAsync(call => endpoints[Math.Min(call, endpoints.Length) - 1]);

        public static Task<FakeRuntime> StartAsync(Func<int, string> getEndpoint) =>
            StartAsync((call, _) => Task.FromResult(getEndpoint(call)));

        public static async Task<FakeRuntime> StartAsync(Func<int, CancellationToken, Task<string>> getEndpoint)
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            var runtime = new FakeRuntime(listener, getEndpoint);
            var port = ((IPEndPoint)listener.LocalEndpoint).Port;
            runtime._client = new CopilotClient(new CopilotClientOptions { Connection = RuntimeConnection.ForUri($"http://127.0.0.1:{port}") });
            runtime._session = await runtime._client.CreateSessionAsync(new SessionConfig());
            return runtime;
        }

        public async ValueTask DisposeAsync()
        {
            if (_session is not null)
            {
                await _session.DisposeAsync();
            }

            if (_client is not null)
            {
                await _client.DisposeAsync();
            }

            _cts.Cancel();
            _listener.Stop();
            try
            {
                await _serverTask;
            }
            catch (Exception ex) when (ex is OperationCanceledException or ObjectDisposedException or IOException or SocketException)
            {
            }
            finally
            {
                _cts.Dispose();
            }
        }

        private async Task RunAsync()
        {
            using var tcpClient = await _listener.AcceptTcpClientAsync(_cts.Token);
            using var stream = tcpClient.GetStream();
            while (!_cts.Token.IsCancellationRequested)
            {
                using var message = await ReadMessageAsync(stream, _cts.Token);
                if (message is null)
                {
                    return;
                }

                var root = message.RootElement;
                if (!root.TryGetProperty("method", out var methodElement) || !root.TryGetProperty("id", out var id))
                {
                    continue;
                }

                var method = methodElement.GetString();
                var parameters = root.TryGetProperty("params", out var p) ? p.Clone() : Json("{}");
                string result;
                try
                {
                    result = method switch
                    {
                        "connect" => """{"ok":true,"protocolVersion":3,"version":"test"}""",
                        "session.create" => $$"""{"sessionId":{{JsonString(parameters.TryGetProperty("sessionId", out var sid) && sid.ValueKind == JsonValueKind.String ? sid.GetString()! : Guid.NewGuid().ToString())}},"workspacePath":null,"capabilities":null}""",
                        "session.detach" => """{"success":true}""",
                        "session.provider.getEndpoint" => await GetEndpointAsync(parameters, _cts.Token),
                        _ => "{}",
                    };
                }
                catch (RpcFailure failure)
                {
                    await WriteAsync(
                        stream,
                        $$$"""{"jsonrpc":"2.0","id":{{{id.GetRawText()}}},"error":{"code":-32603,"message":{{{JsonString("Request session.provider.getEndpoint failed with message: " + failure.Message)}}}}}""",
                        _cts.Token);
                    continue;
                }

                await WriteAsync(stream, $$"""{"jsonrpc":"2.0","id":{{id.GetRawText()}},"result":{{result}}}""", _cts.Token);
            }
        }

        private Task<string> GetEndpointAsync(JsonElement parameters, CancellationToken cancellationToken)
        {
            int call;
            lock (_getEndpointCalls)
            {
                _getEndpointCalls.Add(parameters);
                call = _getEndpointCalls.Count;
            }

            return _getEndpoint(call, cancellationToken);
        }

        private static async Task WriteAsync(Stream stream, string json, CancellationToken cancellationToken)
        {
            var body = Encoding.UTF8.GetBytes(json);
            var header = Encoding.ASCII.GetBytes($"Content-Length: {body.Length}\r\n\r\n");
            await stream.WriteAsync(header, cancellationToken);
            await stream.WriteAsync(body, cancellationToken);
            await stream.FlushAsync(cancellationToken);
        }

        private static async Task<JsonDocument?> ReadMessageAsync(Stream stream, CancellationToken cancellationToken)
        {
            var headerText = await ReadHeaderAsync(stream, cancellationToken);
            if (headerText is null)
            {
                return null;
            }

            var lengthLine = headerText.Split("\r\n", StringSplitOptions.RemoveEmptyEntries)
                .Single(line => line.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase));
            var length = int.Parse(lengthLine["Content-Length:".Length..].Trim(), CultureInfo.InvariantCulture);
            var body = await ReadExactlyAsync(stream, length, cancellationToken);
            return body is null ? null : JsonDocument.Parse(body);
        }
    }

    private sealed record HttpHit(string Method, string Path, IReadOnlyDictionary<string, string> Headers, byte[] Body);

    /// <summary>One-request-per-connection HTTP/1.1 server on loopback.</summary>
    private sealed class LoopbackHttpServer : IAsyncDisposable
    {
        private readonly TcpListener _listener;
        private readonly Func<string, IReadOnlyDictionary<string, string>, byte[], (int Status, string? ContentType, string? Location, string Body)> _handler;
        private readonly CancellationTokenSource _cts = new();
        private readonly List<HttpHit> _hits = [];
        private readonly Task _serverTask;

        private LoopbackHttpServer(
            TcpListener listener,
            Func<string, IReadOnlyDictionary<string, string>, byte[], (int Status, string? ContentType, string? Location, string Body)> handler)
        {
            _listener = listener;
            _handler = handler;
            _serverTask = RunAsync();
        }

        public string BaseUrl => $"http://127.0.0.1:{((IPEndPoint)_listener.LocalEndpoint).Port}/v1";

        public HttpHit[] Hits
        {
            get
            {
                lock (_hits)
                {
                    return _hits.ToArray();
                }
            }
        }

        public static LoopbackHttpServer Start(
            Func<string, IReadOnlyDictionary<string, string>, byte[], (int Status, string? ContentType, string? Location, string Body)> handler)
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            return new LoopbackHttpServer(listener, handler);
        }

        public async ValueTask DisposeAsync()
        {
            _cts.Cancel();
            _listener.Stop();
            try
            {
                await _serverTask;
            }
            catch (Exception ex) when (ex is OperationCanceledException or ObjectDisposedException or IOException or SocketException)
            {
            }
            finally
            {
                _cts.Dispose();
            }
        }

        private async Task RunAsync()
        {
            while (!_cts.Token.IsCancellationRequested)
            {
                using var client = await _listener.AcceptTcpClientAsync(_cts.Token);
                using var stream = client.GetStream();
                var head = await ReadHeaderAsync(stream, _cts.Token);
                if (head is null)
                {
                    continue;
                }

                var lines = head.Split("\r\n", StringSplitOptions.RemoveEmptyEntries);
                var requestLine = lines[0].Split(' ');
                var headers = new Dictionary<string, string>(StringComparer.Ordinal);
                foreach (var line in lines.Skip(1))
                {
                    var colon = line.IndexOf(':', StringComparison.Ordinal);
                    headers[line[..colon].Trim().ToLowerInvariant()] = line[(colon + 1)..].Trim();
                }

                var length = headers.TryGetValue("content-length", out var lengthText) ? int.Parse(lengthText, CultureInfo.InvariantCulture) : 0;
                var body = await ReadExactlyAsync(stream, length, _cts.Token) ?? [];
                lock (_hits)
                {
                    _hits.Add(new HttpHit(requestLine[0], requestLine[1], headers, body));
                }

                var (status, contentType, location, responseBody) = _handler(requestLine[1], headers, body);
                var payload = Encoding.UTF8.GetBytes(responseBody);
                var response = new StringBuilder()
                    .Append(CultureInfo.InvariantCulture, $"HTTP/1.1 {status} Status\r\n")
                    .Append(CultureInfo.InvariantCulture, $"Content-Length: {payload.Length}\r\n")
                    .Append("Connection: close\r\n");
                if (contentType is not null)
                {
                    response.Append(CultureInfo.InvariantCulture, $"Content-Type: {contentType}\r\n");
                }

                if (location is not null)
                {
                    response.Append(CultureInfo.InvariantCulture, $"Location: {location}\r\n");
                }

                response.Append("\r\n");
                await stream.WriteAsync(Encoding.ASCII.GetBytes(response.ToString()), _cts.Token);
                await stream.WriteAsync(payload, _cts.Token);
                await stream.FlushAsync(_cts.Token);
            }
        }
    }

    private static async Task<string?> ReadHeaderAsync(Stream stream, CancellationToken cancellationToken)
    {
        var header = new List<byte>();
        var buffer = new byte[1];
        while (true)
        {
            var read = await stream.ReadAsync(buffer, cancellationToken);
            if (read == 0)
            {
                return null;
            }

            header.Add(buffer[0]);
            if (header.Count >= 4 && header[^4] == '\r' && header[^3] == '\n' && header[^2] == '\r' && header[^1] == '\n')
            {
                return Encoding.ASCII.GetString(header.ToArray());
            }
        }
    }

    private static async Task<byte[]?> ReadExactlyAsync(Stream stream, int length, CancellationToken cancellationToken)
    {
        var body = new byte[length];
        var offset = 0;
        while (offset < length)
        {
            var read = await stream.ReadAsync(body.AsMemory(offset, length - offset), cancellationToken);
            if (read == 0)
            {
                return null;
            }

            offset += read;
        }

        return body;
    }
}

#pragma warning restore GHCP001
#endif
