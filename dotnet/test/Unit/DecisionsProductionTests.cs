/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

#if NET8_0_OR_GREATER
#pragma warning disable GHCP001 // The Decisions API is experimental.

using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.ExceptionServices;
using System.Text;
using System.Text.Json;
using Xunit;

namespace GitHub.Copilot.Test.Unit;

public sealed partial class DecisionsTests
{
    private const string ProductionVectorsFile = "production-vectors.json";

    public static TheoryData<string> MetadataVectors => Fixtures.VectorNames("metadata", ProductionVectorsFile);

    public static TheoryData<string> RetryAfterVectors => Fixtures.VectorNames("retryAfter", ProductionVectorsFile);

    public static TheoryData<string> ResponseNumbersVectors => Fixtures.VectorNames("responseNumbers", ProductionVectorsFile);

    public static TheoryData<string> AuthBodyFailureVectors => Fixtures.VectorNames("authBodyFailure", ProductionVectorsFile);

    public static TheoryData<string> DeadlineVectors => Fixtures.VectorNames("deadline", ProductionVectorsFile);

    [Theory]
    [MemberData(nameof(MetadataVectors))]
    public async Task Metadata_Production_Vector(string name)
    {
        var vector = Fixtures.Vector("metadata", name, ProductionVectorsFile);
        var key = vector.GetProperty("apiKey").GetString()!;
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpointWithKey(key));
        using var http = new ScriptedHandler(_ => Respond(
            vector.GetProperty("status").GetInt32(), vector.GetProperty("body").GetString()!,
            "application/json", ResponseHeaders(vector)));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(DecisionsErrorKind.Http, error.Kind);
        Assert.Equal(vector.GetProperty("status").GetInt32(), error.Status);
        Assert.Equal(NullableString(vector.GetProperty("expectedRequestId")), error.RequestId);
        if (vector.TryGetProperty("expectedErrorType", out var type))
        {
            Assert.Equal(type.GetString(), error.ErrorType);
        }

        if (vector.TryGetProperty("expectedErrorCode", out var code))
        {
            Assert.Equal(code.GetString(), error.ErrorCode);
        }

        Assert.DoesNotContain(key, ErrorSurfaces(error), StringComparison.Ordinal);
        Assert.Single(http.Calls);
        Fixtures.RecordPassed("metadata", name, ProductionVectorsFile);
    }

    [Theory]
    [MemberData(nameof(RetryAfterVectors))]
    public async Task RetryAfter_Production_Vector(string name)
    {
        var vector = Fixtures.Vector("retryAfter", name, ProductionVectorsFile);
        var headers = ResponseHeaders(vector);
        var ms = headers.FirstOrDefault(h => h.Name == "retry-after-ms").Value;
        var after = headers.FirstOrDefault(h => h.Name == "retry-after").Value;
        var now = DateTimeOffset.FromUnixTimeMilliseconds(vector.GetProperty("nowEpochMs").GetInt64());
        var expected = vector.GetProperty("expectedMs").ValueKind == JsonValueKind.Null
            ? (long?)null : vector.GetProperty("expectedMs").GetInt64();
        Assert.Equal(expected, CallInternal<long?>("DecisionsHttp", "ParseRetryAfterMs", ms, after, now));

        using (var response = Respond(429, "{}", "application/json", headers))
        {
            var deterministic = await CallInternal<Task<DecisionsException>>(
                "DecisionsHttp", "CreateHttpErrorAsync", response, Array.Empty<string>(), CancellationToken.None, now);
            Assert.Equal(DecisionsErrorKind.Http, deterministic.Kind);
            Assert.Equal(429, deterministic.Status);
            Assert.Equal(expected, deterministic.RetryAfterMs);
        }

        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var http = new ScriptedHandler(_ => Respond(429, "{}", "application/json", headers));
        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        // Fixture dates are in the past for the live clock; delay syntax is clock-independent.
        var liveExpected = name is "valid_imf_fixdate" or "past_imf_fixdate" ? 0 : expected;
        Assert.Equal(liveExpected, error.RetryAfterMs);
        Assert.Equal(DecisionsErrorKind.Http, error.Kind);
        Assert.Single(http.Calls);
        Assert.Single(runtime.GetEndpointCalls);
        Fixtures.RecordPassed("retryAfter", name, ProductionVectorsFile);
    }

    [Theory]
    [MemberData(nameof(ResponseNumbersVectors))]
    public async Task ResponseNumbers_Production_Vector(string name)
    {
        var vector = Fixtures.Vector("responseNumbers", name, ProductionVectorsFile);
        var target = vector.GetProperty("target").GetString()!;
        var body = ReplaceJsonToken(OkBody, target, vector.GetProperty("jsonValue").GetString()!);
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var http = new ScriptedHandler(_ => Respond(200, body));
        var create = runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http });
        string? expectedError = vector.TryGetProperty("expectedError", out var ordinary) ? ordinary.GetString() : null;
        if (vector.TryGetProperty("sdkErrors", out var overrides) && overrides.TryGetProperty("dotnet", out var sdkError))
        {
            expectedError = sdkError.GetString();
        }

        if (expectedError is not null)
        {
            var error = await Assert.ThrowsAsync<DecisionsException>(() => create);
            Assert.Equal(ParseKind(expectedError), error.Kind);
        }
        else
        {
            var result = await create;
            if (vector.TryGetProperty("expectedInteger", out var integer))
            {
                Assert.Equal(integer.GetString(), ResultInteger(result, target).ToString(CultureInfo.InvariantCulture));
            }
            else
            {
                Assert.Equal(vector.GetProperty("expectedNumber").GetDouble(), Assert.IsType<DecisionsScoreAnswer>(result.Answers[3]).Score);
            }
        }

        Assert.Single(http.Calls);
        Fixtures.RecordPassed("responseNumbers", name, ProductionVectorsFile);
    }

    [Theory]
    [MemberData(nameof(AuthBodyFailureVectors))]
    public async Task AuthBodyFailure_Production_Vector(string name)
    {
        var vector = Fixtures.Vector("authBodyFailure", name, ProductionVectorsFile);
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint, OpenAiEndpointWithKey("synthetic-refreshed-key"));
        var stream = new FailingStream();
        using var http = new ScriptedHandler(
            _ => Respond(vector.GetProperty("status").GetInt32(), new StreamContent(stream)),
            _ => Respond(200, OkBody));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(ParseKind(vector.GetProperty("expectedError").GetString()!), error.Kind);
        Assert.Equal(vector.GetProperty("expectedResolves").GetInt32(), runtime.GetEndpointCalls.Length);
        Assert.Equal(vector.GetProperty("expectedSends").GetInt32(), http.Calls.Length);
        Assert.Equal(vector.GetProperty("expectedBodyClosed").GetBoolean(), stream.Disposed);
        Fixtures.RecordPassed("authBodyFailure", name, ProductionVectorsFile);
    }

    [Theory]
    [MemberData(nameof(DeadlineVectors))]
    public async Task Deadline_Production_Vector(string name)
    {
        var vector = Fixtures.Vector("deadline", name, ProductionVectorsFile);
        var observed = await RunBlockedStageAsync(vector.GetProperty("stage").GetString()!,
            vector.GetProperty("timeoutMs").GetInt32(), callerCancellation: false);

        Assert.Equal(ParseKind(vector.GetProperty("expectedError").GetString()!), observed.Kind);
        Assert.Equal(vector.GetProperty("expectedResolves").GetInt32(), observed.Resolves);
        Assert.Equal(vector.GetProperty("expectedSends").GetInt32(), observed.Sends);
        Fixtures.RecordPassed("deadline", name, ProductionVectorsFile);
    }

    [Theory]
    [InlineData("endpoint")]
    [InlineData("headers")]
    [InlineData("successBody")]
    [InlineData("errorBody")]
    [InlineData("refresh")]
    public async Task Caller_Cancellation_Is_Native_At_Each_Stage(string stage)
    {
        await RunBlockedStageAsync(stage, 250, callerCancellation: true);
    }

    [Fact]
    public async Task Protects_All_Short_Authentication_Candidates_And_Http_Metadata()
    {
        const string Endpoint = """
            {"type":"openai","baseUrl":"https://api.example.com/v1","apiKey":"XY",
             "headers":{"Authorization":" Bearer PQ ","Api-Key":"RS","X-Session":"WX"},
             "sessionToken":{"header":"X-Session","token":"UV"}}
            """;
        await using var runtime = await FakeRuntime.StartAsync(Endpoint);
        using var http = new ScriptedHandler(_ => Respond(400,
            """{"error":{"type":"XY","code":"PQ","param":"UV","message":"XY PQ RS UV WX Bearer PQ"}}""",
            "application/json", ("x-request-id", "RS:WX")));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal("[REDACTED]", error.ErrorType);
        Assert.Equal("[REDACTED]", error.ErrorCode);
        Assert.Equal("[REDACTED]", error.ErrorParam);
        Assert.Equal("[REDACTED]:[REDACTED]", error.RequestId);
        foreach (var secret in new[] { "XY", "PQ", "RS", "UV", "WX" })
        {
            Assert.DoesNotContain(secret, ErrorSurfaces(error), StringComparison.Ordinal);
        }
    }

    [Fact]
    public async Task Bounds_Expanded_Metadata_And_Retains_No_Raw_Exception_Properties()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpointWithKey("XY"));
        using var http = new ScriptedHandler(_ => Respond(400,
            $$$"""{"error":{"type":"XY","code":"XY","param":"XY","message":{{{JsonString(string.Concat(Enumerable.Repeat("XY ", 10000)))}}}}}""",
            "application/json", ("x-request-id", new string('x', MaxErrorBodyBytes) + "XY")));
        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.False(error.BodyTruncated);
        Assert.Equal("[REDACTED]", error.ErrorType);
        foreach (var field in new[] { error.Body, error.Message, error.ErrorType, error.ErrorCode, error.ErrorParam, error.RequestId })
        {
            Assert.NotNull(field);
            Assert.True(Encoding.UTF8.GetByteCount(field!) <= MaxErrorBodyBytes);
            Assert.DoesNotContain("XY", field, StringComparison.Ordinal);
        }
    }

    [Fact]
    public async Task Protects_Transport_Cause_Name_Message_And_Code()
    {
        await using var runtime = await FakeRuntime.StartAsync("""
            {"type":"openai","baseUrl":"https://api.example.com/v1","apiKey":"IOException","headers":{},
             "sessionToken":{"header":"X-Session","token":"Reset"}}
            """);
        var raw = new IOException("IOException Reset " + new string('x', MaxErrorBodyBytes + 100),
            new SocketException((int)SocketError.ConnectionReset));
        raw.Data["unsafe-url"] = "synthetic-private-structured-detail";
        using var http = new ScriptedHandler(_ => throw raw);

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        var cause = Assert.IsType<DecisionsTransportException>(error.InnerException);
        Assert.Equal("System.IO.[REDACTED]", cause.SourceTypeName);
        Assert.Equal("Connection[REDACTED]", cause.Code);
        Assert.True(Encoding.UTF8.GetByteCount(cause.Message) <= MaxErrorBodyBytes);
        Assert.Null(cause.InnerException);
        Assert.Empty(cause.Data);
        Assert.DoesNotContain("synthetic-private-structured-detail", error.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("IOException", ErrorSurfaces(error), StringComparison.Ordinal);
        Assert.DoesNotContain("Reset", ErrorSurfaces(error), StringComparison.Ordinal);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Endpoint_Failures_Never_Retain_Unknown_Credentials(bool refresh)
    {
        const string Unknown = "synthetic-unknown-rpc-credential";
        await using var runtime = await FakeRuntime.StartAsync(call => refresh && call == 1
            ? OpenAiEndpoint : throw new RpcFailure("untrusted URL, token, and detail: " + Unknown));
        using var http = new ScriptedHandler(_ => Respond(401, "{}"));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(DecisionsErrorKind.EndpointUnavailable, error.Kind);
        Assert.Null(error.InnerException);
        Assert.DoesNotContain(Unknown, ErrorSurfaces(error), StringComparison.Ordinal);
        Assert.Equal(refresh ? 1 : 0, http.Calls.Length);
    }

    [Theory]
    [InlineData("type", "unsupportedProvider")]
    [InlineData("transport", "unsupportedTransport")]
    [InlineData("sessionToken.model", "modelMismatch")]
    public async Task Preflight_Uses_Fixed_Diagnostics(string property, string kind)
    {
        const string Secret = "synthetic-private-preflight-value";
        const string Endpoint = """
            {"type":"openai","transport":"http","baseUrl":"https://api.example.com/v1","headers":{},
             "sessionToken":{"header":"X-Session","token":"synthetic-key","model":"gpt-6-luna"}}
            """;
        await using var runtime = await FakeRuntime.StartAsync(ReplaceJsonToken(Endpoint, property, JsonString(Secret)));
        using var http = new ScriptedHandler(_ => Respond(200, OkBody));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.Equal(ParseKind(kind), error.Kind);
        Assert.DoesNotContain(Secret, ErrorSurfaces(error), StringComparison.Ordinal);
        Assert.Null(error.InnerException);
        Assert.Empty(http.Calls);
    }

    [Fact]
    public async Task Shrinking_Redaction_Never_Retains_Original_Lookahead()
    {
        const string Secret = "synthetic-long-key-abcdefghijkl";
        var prefix = Secret + Secret + new string('x', MaxErrorBodyBytes - (Secret.Length * 2));
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpointWithKey(Secret));
        using var http = new ScriptedHandler(_ => Respond(500, prefix + "NEVER-RETAIN-LOOKAHEAD"));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.True(error.BodyTruncated);
        Assert.Equal("[REDACTED][REDACTED]" + new string('x', MaxErrorBodyBytes - (Secret.Length * 2)), error.Body);
        Assert.DoesNotContain("NEVER", error.Body, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Crossing_Unicode_Secret_Is_Replaced_Before_The_Final_Output_Cap()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpointWithKey("🔑"));
        using var http = new ScriptedHandler(_ => Respond(500, new string('x', MaxErrorBodyBytes - 1) + "🔑 trailing"));
        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));
        Assert.True(error.BodyTruncated);
        Assert.Equal(new string('x', MaxErrorBodyBytes - 1) + "[", error.Body);
    }

    [Fact]
    public async Task Short_Nonauthentication_Headers_Keep_The_Scalar_Length_Heuristic()
    {
        const string Short = "🔑🔑🔑🔑abc";
        await using var runtime = await FakeRuntime.StartAsync("""
            {"type":"openai","baseUrl":"https://api.example.com/v1?short=seven77&sig=synthetic-query-secret",
             "headers":{"X-Trace":"🔑🔑🔑🔑abc"}}
            """);
        using var http = new ScriptedHandler(_ => Respond(400, Short + "|seven77|synthetic-query-secret"));
        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));
        Assert.Equal(Short + "|seven77|[REDACTED]", error.Body);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Replay_Diagnostics_Protect_Both_Old_And_New_Credentials(bool network)
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpointWithKey("XY"), OpenAiEndpointWithKey("UV"));
        using var http = new ScriptedHandler(_ => Respond(401, "{}"), _ =>
            network ? throw new HttpRequestException("XY UV") : Respond(400, "XY UV"));
        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));
        Assert.Equal(network ? DecisionsErrorKind.Network : DecisionsErrorKind.Http, error.Kind);
        Assert.Equal("[REDACTED] [REDACTED]",
            network ? Assert.IsType<DecisionsTransportException>(error.InnerException).Message : error.Body);
        Assert.Equal(2, http.Calls.Length);
    }

    [Theory]
    [InlineData("%FFcredential-0001", "%ffcredential-0001", "\ufffdcredential-0001", false)]
    [InlineData("%FFcredential-0001", "%ffcredential-0001", "\ufffdcredential-0001", true)]
    [InlineData("synthetic+%FF+credential", "synthetic+%ff+credential", "synthetic \ufffd credential", false)]
    [InlineData("synthetic+%FF+credential", "synthetic+%ff+credential", "synthetic \ufffd credential", true)]
    [InlineData("%41%42%43%44%45%46%47%48", "%41%42%43%44%45%46%47%48", "ABCDEFGH", false)]
    [InlineData("%41%42%43%44%45%46%47%48", "%41%42%43%44%45%46%47%48", "ABCDEFGH", true)]
    public async Task Protects_Raw_And_Lossy_Decoded_Query_Values(string query, string rawEcho, string decodedEcho, bool network)
    {
        var url = "https://api.example.com/v1?sig=" + query;
        await using var runtime = await FakeRuntime.StartAsync(
            $$$"""{"type":"openai","baseUrl":{{{JsonString(url)}}},"headers":{}}""");
        var text = rawEcho + " | " + decodedEcho;
        using var http = new ScriptedHandler(_ => network ? throw new HttpRequestException(text)
            : Respond(400, text, "text/plain", ("x-request-id", rawEcho)));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));
        Assert.Equal(network ? DecisionsErrorKind.Network : DecisionsErrorKind.Http, error.Kind);
        Assert.Equal("[REDACTED] | [REDACTED]",
            network ? Assert.IsType<DecisionsTransportException>(error.InnerException).Message : error.Body);
        if (!network)
        {
            Assert.Equal("[REDACTED]", error.RequestId);
        }

        Assert.DoesNotContain(rawEcho, ErrorSurfaces(error), StringComparison.Ordinal);
        Assert.DoesNotContain(decodedEcho, ErrorSurfaces(error), StringComparison.Ordinal);
        Assert.Single(http.Calls);
    }

    [Fact]
    public async Task Encoded_Short_Query_Does_Not_Bypass_The_Decoded_Scalar_Heuristic()
    {
        const string Raw = "%41%42%43%44%45%46%47";
        await using var runtime = await FakeRuntime.StartAsync(
            $$$"""{"type":"openai","baseUrl":"https://api.example.com/v1?sig={{{Raw}}}","headers":{}}""");
        using var http = new ScriptedHandler(_ => Respond(400, Raw + "|ABCDEFG"));
        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));
        Assert.Equal(Raw + "|ABCDEFG", error.Body);
    }

    [Fact]
    public async Task Error_Reads_Are_Limited_To_Original_Budget_And_Secret_Lookahead()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpointWithKey("Z"));
        var stream = new EndlessStream();
        using var http = new ScriptedHandler(_ => Respond(500, new StreamContent(stream)));

        var error = await Assert.ThrowsAsync<DecisionsException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }));

        Assert.True(error.BodyTruncated);
        Assert.True(stream.Disposed);
        Assert.Equal(MaxErrorBodyBytes + "Bearer%20Z".Length + 1, stream.BytesRead);
    }

    [Theory]
    [InlineData("aaaaa", "aa", "[REDACTED]")]
    [InlineData("ZZ", "Z", "[REDACTED][REDACTED]")]
    [InlineData("Z unchanged", "Z", "[REDACTED] unchanged")]
    public void Redaction_Only_Matches_Original_Text(string text, string secret, string expected)
    {
        var actual = CallInternal<string>("DecisionsText", "Redact", text, new[] { secret, "REDACTED" }, null, false);
        Assert.Equal(expected, actual);
    }

    [Theory]
    [InlineData(0x0001)]
    [InlineData(0x000b)]
    [InlineData(0x007f)]
    [InlineData(0xd800)]
    [InlineData(0xdfff)]
    public void Rejects_Invalid_Header_Values_Before_Encoding(int codeUnit)
    {
        var value = new string((char)codeUnit, 1);
        var error = Assert.Throws<DecisionsException>(
            () => CallInternal<string>("DecisionsPlanner", "CheckHeader", "X-Test", value));
        Assert.Equal(DecisionsErrorKind.InvalidEndpoint, error.Kind);
    }

    [Fact]
    public void Preserves_Unicode_Header_Scalars_And_Only_Trims_Space_And_Tab()
    {
        Assert.Equal("\u00a0🔑é\tvalue\u00a0",
            CallInternal<string>("DecisionsPlanner", "CheckHeader", "X-Test", " \t\u00a0🔑é\tvalue\u00a0\t "));
    }

    [Theory]
    [InlineData("http://")]
    [InlineData("http:///v1")]
    [InlineData("http://host:")]
    [InlineData("http://host:-1/v1")]
    [InlineData("http://[::1]suffix/v1")]
    [InlineData("http://::1/v1")]
    [InlineData("http://[not-ipv6]/v1")]
    [InlineData("http://bad..host/v1")]
    [InlineData("http://bad../v1")]
    [InlineData("http://-bad.example/v1")]
    [InlineData("http://bad-.example/v1")]
    [InlineData("http://256.0.0.1/v1")]
    [InlineData("http://host/v%")]
    [InlineData("http://host/v%2")]
    [InlineData("http://host/v1?q=%xz")]
    [InlineData("http://host/v1#raw space")]
    public void Rejects_Malformed_Url_Syntax(string url)
    {
        var error = Assert.Throws<DecisionsException>(
            () => CallInternal<string>("DecisionsPlanner", "BuildUrl", "openai", url));
        Assert.Equal(DecisionsErrorKind.InvalidEndpoint, error.Kind);
        Assert.DoesNotContain(url, error.Message, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("http://LOCALHOST:0/v1", "http://localhost:0/v1/decisions")]
    [InlineData("HTTP://EXAMPLE.COM:80/a//b/../v1?x=%C3%A9&x=2", "http://example.com/a//v1/decisions?x=%C3%A9&x=2")]
    [InlineData("https://[::1]:443/%2e/v1?", "https://[::1]/v1/decisions")]
    [InlineData("https://example.com/a/%2e./v1", "https://example.com/v1/decisions")]
    [InlineData("https://example.com/a%2fb/v1", "https://example.com/a%2fb/v1/decisions")]
    public void Normalizes_Urls_Without_Decoding_Slashes(string url, string expected)
    {
        Assert.Equal(expected, CallInternal<string>("DecisionsPlanner", "BuildUrl", "openai", url));
    }

    [Theory]
    [InlineData("usage.input_tokens", "9223372036854775806", "9223372036854775806")]
    [InlineData("usage.input_tokens", "-9223372036854775807", "-9223372036854775807")]
    [InlineData("usage.output_tokens", "-1", "-1")]
    [InlineData("usage.input_tokens_details.cache_write_tokens", "-1", "-1")]
    [InlineData("usage.output_tokens_details.reasoning_tokens", "-1", "-1")]
    [InlineData("answers.3.probabilities.0.value", "9223372036854775807", "9223372036854775807")]
    [InlineData("answers.3.probabilities.0.value", "-9223372036854775808", "-9223372036854775808")]
    [InlineData("answers.3.probabilities.0.value", "3e0", "3")]
    [InlineData("usage.input_tokens", "9007199254740991.0", "9007199254740991")]
    [InlineData("usage.input_tokens", "0e999999999999999", "0")]
    [InlineData("usage.input_tokens", "-123.000e2", "-12300")]
    public void Parses_Signed_Integer_Domains_Without_Double_Rounding(string target, string token, string expected)
    {
        var result = CallInternal<DecisionsResult>("DecisionsWire", "ParseResult", Json(ReplaceJsonToken(OkBody, target, token)));
        Assert.Equal(expected, ResultInteger(result, target).ToString(CultureInfo.InvariantCulture));
    }

    [Theory]
    [InlineData("usage.input_tokens", "9007199254740992.0")]
    [InlineData("usage.input_tokens", "-9007199254740992e0")]
    [InlineData("usage.input_tokens", "1.00000000000000000000000000000000001")]
    [InlineData("usage.input_tokens", "1e-400")]
    [InlineData("usage.input_tokens", "-9223372036854775809")]
    [InlineData("usage.output_tokens", "null")]
    [InlineData("usage.input_tokens_details.cached_tokens", "true")]
    [InlineData("usage.output_tokens_details.reasoning_tokens", "\"1\"")]
    [InlineData("answers.0.probability", "true")]
    [InlineData("answers.0.probability", "-0.1")]
    [InlineData("answers.1.confidence", "1.01")]
    [InlineData("answers.2.probabilities.0.probability", "1e400")]
    [InlineData("answers.3.score", "1e400")]
    [InlineData("answers.3.probabilities.0.value", "null")]
    [InlineData("answers.3.probabilities.0.value", "true")]
    [InlineData("answers.3.probabilities.0.value", "9223372036854775808")]
    public void Invalid_Known_Numbers_Always_Use_InvalidResponse(string target, string token)
    {
        var error = Assert.Throws<DecisionsException>(() =>
            CallInternal<DecisionsResult>("DecisionsWire", "ParseResult", Json(ReplaceJsonToken(OkBody, target, token))));
        Assert.Equal(DecisionsErrorKind.InvalidResponse, error.Kind);
    }

    [Theory]
    [InlineData(".5", null)]
    [InlineData("5.", null)]
    [InlineData("1,000", null)]
    [InlineData("1e2", null)]
    [InlineData("Infinity", null)]
    [InlineData("\n1", null)]
    [InlineData("9007199254740991", 9007199254740991L)]
    [InlineData("9007199254740991.000", 9007199254740991L)]
    [InlineData("9007199254740991.00000000000000000000001", null)]
    [InlineData("0.00000000000000000000001", 1L)]
    public void Retry_Decimals_Are_Exact_And_Strict(string ms, long? expected)
    {
        Assert.Equal(expected, CallInternal<long?>("DecisionsHttp", "ParseRetryAfterMs", ms, null, DateTimeOffset.UtcNow));
    }

    [Fact]
    public void Retry_Seconds_And_Dates_Round_Up_Without_Overflow_Or_Locale()
    {
        var now = new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero).AddTicks(1);
        Assert.Equal(9007199254740991L,
            CallInternal<long?>("DecisionsHttp", "ParseRetryAfterMs", null, "9007199254740.991", now));
        Assert.Null(CallInternal<long?>("DecisionsHttp", "ParseRetryAfterMs", null, "9007199254740.9911", now));
        Assert.Equal(1000L, CallInternal<long?>("DecisionsHttp", "ParseRetryAfterMs", null, "Thu, 01 Jan 2026 00:00:01 GMT", now));
        Assert.Null(CallInternal<long?>("DecisionsHttp", "ParseRetryAfterMs", null, "Fri, 01 Jan 2026 00:00:01 GMT", now));
        Assert.Null(CallInternal<long?>("DecisionsHttp", "ParseRetryAfterMs", null, "Thu, 01 Jan 2026 00:00:01 +0000", now));
    }

    [Fact]
    public void Uses_600_Second_Default_Without_HttpClient_100_Second_Timeout()
    {
        var assembly = typeof(CopilotSession).Assembly;
        var lifetimeType = assembly.GetType("GitHub.Copilot.SessionDecisionsApi+DecisionsCallLifetime", true)!;
        Assert.Equal(TimeSpan.FromSeconds(600),
            lifetimeType.GetField("DefaultTimeout", BindingFlags.Static | BindingFlags.NonPublic)!.GetValue(null));
        using var lifetime = (IDisposable)Activator.CreateInstance(lifetimeType, [null, CancellationToken.None])!;
        Assert.Equal(TimeSpan.FromSeconds(600),
            lifetimeType.GetField("_timeout", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(lifetime));
        var shared = (HttpClient)assembly.GetType("GitHub.Copilot.DecisionsHttp", true)!
            .GetProperty("SharedClient", BindingFlags.Public | BindingFlags.Static)!.GetValue(null)!;
        Assert.Equal(Timeout.InfiniteTimeSpan, shared.Timeout);
        using var handler = new ScriptedHandler(_ => Respond(200, OkBody));
        using var custom = CallInternal<HttpClient>("DecisionsHttp", "CreateClient", handler);
        Assert.Equal(Timeout.InfiniteTimeSpan, custom.Timeout);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-2)]
    [InlineData(-600000)]
    public async Task Invalid_Timeouts_Fail_Before_Any_IO(int milliseconds)
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var http = new ScriptedHandler(_ => Respond(200, OkBody));
        var options = new DecisionsOptions { Handler = http, Timeout = TimeSpan.FromMilliseconds(milliseconds) };

        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(
            () => runtime.Session.Decisions.CreateAsync(MinimalRequest(), options));
        var missingModel = MinimalRequest();
        missingModel.Model = string.Empty;
        var missing = await Assert.ThrowsAsync<ArgumentException>(() => runtime.Session.Decisions.CreateAsync(missingModel, options));
        Assert.Contains("Model is required", missing.Message, StringComparison.Ordinal);
        Assert.Empty(runtime.GetEndpointCalls);
        Assert.Empty(http.Calls);
    }

    [Fact]
    public async Task Accepts_Disabled_And_Large_Positive_Timeouts_Without_Timer_Overflow()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var http = new ScriptedHandler(_ => Respond(200, OkBody));
        foreach (var timeout in new TimeSpan?[] { null, Timeout.InfiniteTimeSpan, TimeSpan.MaxValue })
        {
            var result = await runtime.Session.Decisions.CreateAsync(
                MinimalRequest(), new DecisionsOptions { Handler = http, Timeout = timeout });
            Assert.Equal(6, result.Answers.Count);
        }

        Assert.Equal(3, http.Calls.Length);
    }

    [Fact]
    public async Task Expiry_Before_Endpoint_Resolution_Performs_No_IO()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var http = new ScriptedHandler(_ => Respond(200, OkBody));
        var error = await Assert.ThrowsAsync<DecisionsException>(() => runtime.Session.Decisions.CreateAsync(
            MinimalRequest(), new DecisionsOptions { Handler = http, Timeout = TimeSpan.FromTicks(1) }));
        Assert.Equal(DecisionsErrorKind.Timeout, error.Kind);
        Assert.Empty(runtime.GetEndpointCalls);
        Assert.Empty(http.Calls);
    }

    [Fact]
    public async Task Caller_Cancellation_Takes_Precedence_When_Deadline_Also_Expires()
    {
        using var caller = new CancellationTokenSource();
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var http = new ScriptedHandler(async (_, token) =>
        {
            using var registration = token.Register(caller.Cancel);
            await Task.Delay(Timeout.Infinite, token);
            return Respond(200, OkBody);
        });
        var error = await Record.ExceptionAsync(() => runtime.Session.Decisions.CreateAsync(
            MinimalRequest(), new DecisionsOptions { Handler = http, Timeout = TimeSpan.FromMilliseconds(250) }, caller.Token));
        var cancelled = Assert.IsAssignableFrom<OperationCanceledException>(error);
        Assert.Equal(caller.Token, cancelled.CancellationToken);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Independent_Transport_Timeout_Is_A_Sanitized_Network_Error(bool cancelled)
    {
        const string Secret = "synthetic-transport-secret";
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpointWithKey(Secret));
        using var http = new ScriptedHandler(_ => throw (cancelled
            ? new OperationCanceledException(Secret, new IOException("unsafe-inner"))
            : new TimeoutException(Secret, new IOException("unsafe-inner"))));

        var error = await Assert.ThrowsAsync<DecisionsException>(() => runtime.Session.Decisions.CreateAsync(
            MinimalRequest(), new DecisionsOptions { Handler = http, Timeout = Timeout.InfiniteTimeSpan }));
        Assert.Equal(DecisionsErrorKind.Network, error.Kind);
        var cause = Assert.IsType<DecisionsTransportException>(error.InnerException);
        Assert.Equal("[REDACTED]", cause.Message);
        Assert.Null(cause.InnerException);
        Assert.DoesNotContain(Secret, error.ToString(), StringComparison.Ordinal);
    }

    [Fact]
    public async Task Deadline_Is_Not_Reset_After_Authentication_Replay()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint, OpenAiEndpointWithKey("synthetic-refreshed-key"));
        var attempt = 0;
        using var http = new ScriptedHandler(async (_, token) =>
        {
            await Task.Delay(300, token);
            return Interlocked.Increment(ref attempt) == 1 ? Respond(401, "{}") : Respond(200, OkBody);
        });
        var error = await Assert.ThrowsAsync<DecisionsException>(() => runtime.Session.Decisions.CreateAsync(
            MinimalRequest(), new DecisionsOptions { Handler = http, Timeout = TimeSpan.FromMilliseconds(500) }));
        Assert.Equal(DecisionsErrorKind.Timeout, error.Kind);
        Assert.Null(error.InnerException);
        Assert.Equal(2, http.Calls.Length);
        Assert.Equal(2, runtime.GetEndpointCalls.Length);
    }

    [Fact]
    public async Task Completed_Call_Disposes_Its_Timer_And_Does_Not_Dispose_Injected_Handler()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        CancellationToken observed = default;
        using var http = new ScriptedHandler((_, token) =>
        {
            observed = token;
            return Task.FromResult(Respond(200, OkBody));
        });
        var options = new DecisionsOptions { Handler = http, Timeout = TimeSpan.FromMilliseconds(250) };
        await runtime.Session.Decisions.CreateAsync(MinimalRequest(), options);
        await Task.Delay(350);
        Assert.False(observed.IsCancellationRequested);
        Assert.False(http.Disposed);
        await runtime.Session.Decisions.CreateAsync(MinimalRequest(), options);
        Assert.Equal(2, http.Calls.Length);
    }

    [Fact]
    public async Task Closes_Response_Acquired_During_Caller_Cancellation()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var caller = new CancellationTokenSource();
        var stream = new TrackingStream(Encoding.UTF8.GetBytes(OkBody), () => { });
        using var http = new ScriptedHandler(_ =>
        {
            caller.Cancel();
            return Respond(200, new StreamContent(stream));
        });
        var error = await Record.ExceptionAsync(() =>
            runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http }, caller.Token));
        Assert.IsAssignableFrom<OperationCanceledException>(error);
        Assert.True(stream.Disposed);
    }

    [Theory]
    [InlineData("success")]
    [InlineData("bom")]
    [InlineData("invalid-json")]
    [InlineData("invalid-result")]
    [InlineData("oversized")]
    [InlineData("http-error")]
    public async Task Closes_Bodies_On_All_Processing_Paths(string path)
    {
        var body = path switch
        {
            "bom" => "\ufeff" + OkBody,
            "invalid-json" => "not JSON",
            "invalid-result" => "{}",
            "oversized" => new string('x', MaxResponseBytes + 1),
            "http-error" => "{}",
            _ => OkBody,
        };
        var stream = new TrackingStream(Encoding.UTF8.GetBytes(body), () => { });
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var http = new ScriptedHandler(_ => Respond(path == "http-error" ? 500 : 200, new StreamContent(stream)));
        var create = runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http });
        if (path is "success" or "bom")
        {
            Assert.Equal(6, (await create).Answers.Count);
        }
        else
        {
            var error = await Assert.ThrowsAsync<DecisionsException>(() => create);
            Assert.Equal(path == "http-error" ? DecisionsErrorKind.Http : DecisionsErrorKind.InvalidResponse, error.Kind);
        }

        Assert.True(stream.Disposed);
    }

    [Theory]
    [InlineData(401)]
    [InlineData(403)]
    public async Task Closes_First_Auth_Body_Before_Refresh_And_Replay(int status)
    {
        var firstBody = new TrackingStream("{}"u8.ToArray(), () => { });
        await using var runtime = await FakeRuntime.StartAsync(call =>
        {
            if (call == 2)
            {
                Assert.True(firstBody.Disposed);
            }

            return call == 1 ? OpenAiEndpoint : OpenAiEndpointWithKey("synthetic-refreshed-key");
        });
        using var http = new ScriptedHandler(
            _ => Respond(status, new StreamContent(firstBody)),
            _ =>
            {
                Assert.True(firstBody.Disposed);
                return Respond(200, OkBody);
            });

        Assert.Equal(6, (await runtime.Session.Decisions.CreateAsync(
            MinimalRequest(), new DecisionsOptions { Handler = http })).Answers.Count);
        Assert.Equal(2, http.Calls.Length);
    }

    [Fact]
    public async Task Question_Instructions_Omit_Cache_Breakpoints_Without_Changing_Input()
    {
        var part = new DecisionsInputText { Text = "synthetic question", PromptCacheBreakpoint = new DecisionsCacheBreakpoint() };
        var image = new DecisionsInputImage { ImageUrl = "https://example.com/image.png", PromptCacheBreakpoint = new DecisionsCacheBreakpoint() };
        var request = MinimalRequest();
        request.Input = new DecisionsInputItem[] { new DecisionsMessage { Content = new DecisionsContentPart[] { part, image } } };
        request.Questions[0].Instructions = new DecisionsContentPart[] { part, image };
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var http = new ScriptedHandler(_ => Respond(200, OkBody));
        await runtime.Session.Decisions.CreateAsync(request, new DecisionsOptions { Handler = http });
        var sent = Json(Assert.Single(http.Calls).Body);
        var inputParts = sent.GetProperty("input")[0].GetProperty("content");
        var instructions = sent.GetProperty("questions")[0].GetProperty("instructions");
        for (var index = 0; index < 2; index++)
        {
            Assert.True(inputParts[index].TryGetProperty("prompt_cache_breakpoint", out _));
            Assert.False(instructions[index].TryGetProperty("prompt_cache_breakpoint", out _));
        }
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("[{\"id\":1},{\"id\":2}]")]
    public async Task Question_Instructions_Preserve_Typed_Json(string json)
    {
        var request = MinimalRequest();
        request.Questions[0].Instructions = new DecisionsContentPart[] { new DecisionsInputJson { Data = Json(json) } };
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var http = new ScriptedHandler(_ => Respond(200, OkBody));

        await runtime.Session.Decisions.CreateAsync(request, new DecisionsOptions { Handler = http });

        var instructions = Json(Assert.Single(http.Calls).Body).GetProperty("questions")[0].GetProperty("instructions");
        Assert.Equal(1, instructions.GetArrayLength());
        Assert.Equal("input_json", instructions[0].GetProperty("type").GetString());
        AssertJsonEqual(Json(json), instructions[0].GetProperty("data"));
        Assert.False(instructions[0].TryGetProperty("prompt_cache_breakpoint", out _));
    }

    [Theory]
    [InlineData("question", "invalid-json")]
    [InlineData("question", "output")]
    [InlineData("question", "refusal")]
    [InlineData("question", "null")]
    [InlineData("system", "image")]
    [InlineData("system", "json")]
    [InlineData("developer", "output")]
    [InlineData("developer", "image")]
    [InlineData("developer", "json")]
    [InlineData("user", "output")]
    [InlineData("user", "refusal")]
    [InlineData("assistant", "text")]
    [InlineData("assistant", "image")]
    [InlineData("assistant", "json")]
    [InlineData("invalid-role", "text")]
    [InlineData("tool", "json")]
    [InlineData("tool", "output")]
    [InlineData("tool", "refusal")]
    [InlineData("user", "null-text")]
    [InlineData("user", "null-image")]
    [InlineData("user", "invalid-json")]
    public async Task Rejects_Forbidden_Role_And_Content_Combinations(string role, string kind)
    {
        DecisionsContentPart? part = kind switch
        {
            "text" => new DecisionsInputText { Text = "synthetic" },
            "image" => new DecisionsInputImage { ImageUrl = "https://example.com/image.png" },
            "json" => new DecisionsInputJson { Data = Json("{}") },
            "output" => new DecisionsOutputText { Text = "synthetic" },
            "refusal" => new DecisionsRefusalContent { Refusal = "synthetic" },
            "null-text" => new DecisionsInputText { Text = null! },
            "null-image" => new DecisionsInputImage { ImageUrl = null! },
            "invalid-json" => new DecisionsInputJson { Data = Json("[1]") },
            _ => null,
        };
        var request = MinimalRequest();
        var content = new DecisionsContent(new[] { part! });
        if (role == "question")
        {
            request.Questions[0].Instructions = content;
        }
        else
        {
            request.Input = role == "tool"
                ? new DecisionsInputItem[] { new DecisionsFunctionCallOutput { CallId = "synthetic", Output = content } }
                : new DecisionsInputItem[] { new DecisionsMessage { Role = role, Content = content } };
        }

        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var http = new ScriptedHandler(_ => Respond(200, OkBody));
        await Assert.ThrowsAsync<ArgumentException>(
            () => runtime.Session.Decisions.CreateAsync(request, new DecisionsOptions { Handler = http }));
        Assert.Empty(http.Calls);
    }

    [Fact]
    public async Task Cancellation_Observed_After_Synchronous_Serialization_Prevents_Dispatch()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var caller = new CancellationTokenSource();
        using var http = new ScriptedHandler(_ => Respond(200, OkBody));
        var request = MinimalRequest();
        request.Questions = new CancellingQuestions(caller);
        var error = await Record.ExceptionAsync(() =>
            runtime.Session.Decisions.CreateAsync(request, new DecisionsOptions { Handler = http }, caller.Token));
        Assert.IsAssignableFrom<OperationCanceledException>(error);
        Assert.Single(runtime.GetEndpointCalls);
        Assert.Empty(http.Calls);
    }

    [Fact]
    public async Task Deadline_Observed_After_Synchronous_Serialization_Prevents_Dispatch()
    {
        await using var runtime = await FakeRuntime.StartAsync(OpenAiEndpoint);
        using var http = new ScriptedHandler(_ => Respond(200, OkBody));
        var serialized = false;
        var request = MinimalRequest();
        request.Questions = new ObservedQuestions(() =>
        {
            Thread.Sleep(350);
            serialized = true;
        });
        var error = await Assert.ThrowsAsync<DecisionsException>(() =>
            runtime.Session.Decisions.CreateAsync(request, new DecisionsOptions { Handler = http, Timeout = TimeSpan.FromMilliseconds(250) }));
        Assert.Equal(DecisionsErrorKind.Timeout, error.Kind);
        Assert.True(serialized);
        Assert.Single(runtime.GetEndpointCalls);
        Assert.Empty(http.Calls);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Late_Endpoint_Completions_Are_Observed_Without_Dispatch(bool fail)
    {
        var entered = Signal();
        var release = Signal();
        var completed = Signal();
        await using var runtime = await FakeRuntime.StartAsync(async (call, token) =>
        {
            if (call == 1)
            {
                entered.TrySetResult(true);
                await release.Task.WaitAsync(token);
                completed.TrySetResult(true);
                if (fail)
                {
                    throw new RpcFailure("synthetic-late-rpc-credential");
                }
            }

            return OpenAiEndpoint;
        });
        using var http = new ScriptedHandler(_ => Respond(200, OkBody));
        try
        {
            var error = await Assert.ThrowsAsync<DecisionsException>(() => runtime.Session.Decisions.CreateAsync(
                MinimalRequest(), new DecisionsOptions { Handler = http, Timeout = TimeSpan.FromMilliseconds(250) }));
            Assert.Equal(DecisionsErrorKind.Timeout, error.Kind);
            Assert.Null(error.InnerException);
            Assert.True(entered.Task.IsCompleted);
            Assert.Empty(http.Calls);
        }
        finally
        {
            release.TrySetResult(true);
        }

        await completed.Task.WaitAsync(TimeSpan.FromSeconds(10));
        var next = await runtime.Session.Decisions.CreateAsync(MinimalRequest(), new DecisionsOptions { Handler = http });
        Assert.Equal(6, next.Answers.Count);
        Assert.Single(http.Calls);
        Assert.Equal(2, runtime.GetEndpointCalls.Length);
    }

    [Theory]
    [InlineData("headers", 200, false)]
    [InlineData("body", 200, false)]
    [InlineData("body", 401, false)]
    [InlineData("body", 200, true)]
    public async Task Real_Http_Deadline_And_Cancellation_Close_Connections(string stage, int status, bool callerCancellation)
    {
        await using var server = SlowLoopbackHttpServer.Start(stage, status);
        await using var runtime = await FakeRuntime.StartAsync(
            $$$"""{"type":"openai","baseUrl":{{{JsonString(server.BaseUrl)}}},"apiKey":"synthetic-local-key","headers":{}}""",
            OpenAiEndpointWithKey("synthetic-unused-refresh"));
        using var caller = new CancellationTokenSource();
        var operation = runtime.Session.Decisions.CreateAsync(
            MinimalRequest(), new DecisionsOptions { Timeout = TimeSpan.FromSeconds(callerCancellation ? 30 : 1) }, caller.Token);
        await server.Ready.Task.WaitAsync(TimeSpan.FromSeconds(10));
        if (callerCancellation)
        {
            caller.Cancel();
        }

        var error = await Record.ExceptionAsync(async () => await operation.WaitAsync(TimeSpan.FromSeconds(10)));
        if (callerCancellation)
        {
            Assert.IsAssignableFrom<OperationCanceledException>(error);
        }
        else
        {
            Assert.Equal(DecisionsErrorKind.Timeout, Assert.IsType<DecisionsException>(error).Kind);
        }

        Assert.True(await server.PeerClosed.Task.WaitAsync(TimeSpan.FromSeconds(10)));
        Assert.Single(runtime.GetEndpointCalls);
    }

    [Theory]
    [InlineData(401)]
    [InlineData(403)]
    public async Task Real_Http_Replays_Only_Changed_Credentials(int status)
    {
        var attempt = 0;
        await using var server = LoopbackHttpServer.Start((_, headers, _) =>
        {
            var current = Interlocked.Increment(ref attempt);
            Assert.Equal(current == 1 ? "Bearer synthetic-first-key" : "Bearer synthetic-second-key", headers["authorization"]);
            return (current == 1 ? status : 200, "application/json", (string?)null, current == 1 ? "{}" : OkBody);
        });
        await using var runtime = await FakeRuntime.StartAsync(
            $$$"""{"type":"openai","baseUrl":{{{JsonString(server.BaseUrl)}}},"apiKey":"synthetic-first-key","headers":{}}""",
            $$$"""{"type":"openai","baseUrl":{{{JsonString(server.BaseUrl)}}},"apiKey":"synthetic-second-key","headers":{}}""");

        Assert.Equal(6, (await runtime.Session.Decisions.CreateAsync(MinimalRequest())).Answers.Count);
        Assert.Equal(2, server.Hits.Length);
        Assert.Equal(2, runtime.GetEndpointCalls.Length);
    }

    private static async Task<(DecisionsErrorKind? Kind, int Resolves, int Sends)> RunBlockedStageAsync(
        string stage, int timeoutMs, bool callerCancellation)
    {
        var started = Signal();
        var releaseEndpoint = Signal();
        var endpointFinished = Signal();
        var handlerFinished = Signal();
        using var caller = new CancellationTokenSource();
        await using var runtime = await FakeRuntime.StartAsync(async (call, token) =>
        {
            if (stage == "endpoint" || (stage == "refresh" && call == 2))
            {
                started.TrySetResult(true);
                try
                {
                    await releaseEndpoint.Task.WaitAsync(token);
                }
                finally
                {
                    endpointFinished.TrySetResult(true);
                }
            }

            return call == 1 ? OpenAiEndpoint : OpenAiEndpointWithKey("synthetic-refreshed-key");
        });
        var body = new BlockingStream(started);
        using var http = new ScriptedHandler(async (_, token) =>
        {
            if (stage == "headers")
            {
                started.TrySetResult(true);
                try
                {
                    await Task.Delay(Timeout.Infinite, token);
                }
                finally
                {
                    handlerFinished.TrySetResult(true);
                }
            }

            return stage switch
            {
                "successBody" => Respond(200, new StreamContent(body)),
                "errorBody" => Respond(401, new StreamContent(body)),
                _ => Respond(401, "{}"),
            };
        });
        var clock = Stopwatch.StartNew();
        try
        {
            var operation = runtime.Session.Decisions.CreateAsync(MinimalRequest(),
                new DecisionsOptions
                {
                    Handler = http,
                    Timeout = callerCancellation ? TimeSpan.FromSeconds(30) : TimeSpan.FromMilliseconds(timeoutMs),
                }, caller.Token);
            if (callerCancellation)
            {
                await started.Task.WaitAsync(TimeSpan.FromSeconds(10));
                caller.Cancel();
            }

            var error = await Record.ExceptionAsync(async () => await operation.WaitAsync(TimeSpan.FromSeconds(10)));
            Assert.True(started.Task.IsCompleted, "The intended stage must be reached before cancellation.");
            Assert.True(clock.Elapsed < TimeSpan.FromSeconds(10));
            DecisionsErrorKind? kind = null;
            if (callerCancellation)
            {
                var cancelled = Assert.IsAssignableFrom<OperationCanceledException>(error);
                Assert.Equal(caller.Token, cancelled.CancellationToken);
            }
            else
            {
                var timedOut = Assert.IsType<DecisionsException>(error);
                kind = timedOut.Kind;
                Assert.Equal(DecisionsErrorKind.Timeout, kind);
                Assert.Null(timedOut.InnerException);
                Assert.Null(timedOut.Body);
                Assert.DoesNotContain("synthetic", timedOut.ToString(), StringComparison.Ordinal);
            }

            if (stage is "successBody" or "errorBody")
            {
                Assert.True(body.Disposed);
            }

            if (stage == "headers")
            {
                Assert.True(handlerFinished.Task.IsCompleted);
            }

            return (kind, runtime.GetEndpointCalls.Length, http.Calls.Length);
        }
        finally
        {
            releaseEndpoint.TrySetResult(true);
            if (stage is "endpoint" or "refresh" && started.Task.IsCompleted)
            {
                await endpointFinished.Task.WaitAsync(TimeSpan.FromSeconds(10));
                Assert.Equal(stage == "endpoint" ? 0 : 1, http.Calls.Length);
            }

            body.Dispose();
        }
    }

    private static (string Name, string Value)[] ResponseHeaders(JsonElement vector) =>
        vector.GetProperty("headers").EnumerateObject().Select(h => (h.Name, h.Value.GetString()!)).ToArray();

    private static TaskCompletionSource<bool> Signal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    private static long ResultInteger(DecisionsResult result, string path) => path switch
    {
        "usage.input_tokens" => result.Usage.InputTokens,
        "usage.total_tokens" => result.Usage.TotalTokens,
        "usage.output_tokens" => result.Usage.OutputTokens,
        "usage.input_tokens_details.cached_tokens" => result.Usage.InputTokensDetails.CachedTokens,
        "usage.input_tokens_details.cache_write_tokens" => result.Usage.InputTokensDetails.CacheWriteTokens,
        "usage.output_tokens_details.reasoning_tokens" => result.Usage.OutputTokensDetails.ReasoningTokens,
        "answers.3.probabilities.0.value" => Assert.IsType<DecisionsScoreAnswer>(result.Answers[3]).Probabilities[0].Value,
        _ => throw new ArgumentException("Unexpected response target.", nameof(path)),
    };

    private static T CallInternal<T>(string typeName, string methodName, params object?[] arguments)
    {
        var type = typeof(CopilotSession).Assembly.GetType("GitHub.Copilot." + typeName, throwOnError: true)!;
        var method = type.GetMethod(methodName, BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic)!;
        try
        {
            return (T)method.Invoke(null, arguments)!;
        }
        catch (TargetInvocationException error) when (error.InnerException is not null)
        {
            ExceptionDispatchInfo.Capture(error.InnerException).Throw();
            throw;
        }
    }

    private static string ReplaceJsonToken(string json, string dotPath, string rawReplacement)
    {
        using var document = JsonDocument.Parse(json);
        using var buffer = new MemoryStream();
        var path = dotPath.Split('.');
        using (var writer = new Utf8JsonWriter(buffer))
        {
            Replace(document.RootElement, 0, writer);
        }

        return Encoding.UTF8.GetString(buffer.ToArray());

        void Replace(JsonElement value, int depth, Utf8JsonWriter writer)
        {
            if (depth == path.Length)
            {
                writer.WriteRawValue(rawReplacement);
                return;
            }

            if (value.ValueKind == JsonValueKind.Object)
            {
                Assert.True(value.TryGetProperty(path[depth], out _), "Replacement must address an existing property.");
                writer.WriteStartObject();
                foreach (var property in value.EnumerateObject())
                {
                    writer.WritePropertyName(property.Name);
                    if (property.Name == path[depth])
                    {
                        Replace(property.Value, depth + 1, writer);
                    }
                    else
                    {
                        property.Value.WriteTo(writer);
                    }
                }

                writer.WriteEndObject();
            }
            else
            {
                Assert.Equal(JsonValueKind.Array, value.ValueKind);
                var target = int.Parse(path[depth], CultureInfo.InvariantCulture);
                Assert.InRange(target, 0, value.GetArrayLength() - 1);
                writer.WriteStartArray();
                var index = 0;
                foreach (var item in value.EnumerateArray())
                {
                    if (index++ == target)
                    {
                        Replace(item, depth + 1, writer);
                    }
                    else
                    {
                        item.WriteTo(writer);
                    }
                }

                writer.WriteEndArray();
            }
        }
    }

    private static string ErrorSurfaces(DecisionsException error) =>
        string.Join("\n", error.ToString(), error.Message, error.Body, error.ErrorType, error.ErrorCode, error.ErrorParam,
            error.RequestId, (error.InnerException as DecisionsTransportException)?.SourceTypeName,
            (error.InnerException as DecisionsTransportException)?.Code);

    private sealed class CancellingQuestions(CancellationTokenSource caller) : ObservedQuestions(caller.Cancel);

    private class ObservedQuestions(Action onEnumerate) : List<DecisionsQuestion>, IEnumerable<DecisionsQuestion>
    {
        IEnumerator<DecisionsQuestion> IEnumerable<DecisionsQuestion>.GetEnumerator()
        {
            onEnumerate();
            yield return new DecisionsPredicateQuestion { Instructions = "synthetic question" };
        }
    }

    private sealed class SlowLoopbackHttpServer : IAsyncDisposable
    {
        private readonly TcpListener _listener;
        private readonly CancellationTokenSource _stop = new();
        private readonly Task _server;

        private SlowLoopbackHttpServer(TcpListener listener, string stage, int status)
        {
            _listener = listener;
            _server = RunAsync(stage, status);
        }

        public TaskCompletionSource<bool> Ready { get; } = Signal();

        public TaskCompletionSource<bool> PeerClosed { get; } = Signal();

        public string BaseUrl => $"http://127.0.0.1:{((IPEndPoint)_listener.LocalEndpoint).Port}/v1";

        public static SlowLoopbackHttpServer Start(string stage, int status)
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            return new SlowLoopbackHttpServer(listener, stage, status);
        }

        private async Task RunAsync(string stage, int status)
        {
            using var client = await _listener.AcceptTcpClientAsync(_stop.Token);
            using var stream = client.GetStream();
            try
            {
                var headers = await ReadHeaderAsync(stream, _stop.Token);
                Assert.NotNull(headers);
                var contentLength = headers!.Split("\r\n").Single(h => h.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase));
                await ReadExactlyAsync(stream, int.Parse(contentLength["Content-Length:".Length..].Trim(), CultureInfo.InvariantCulture), _stop.Token);
                if (stage == "body")
                {
                    await stream.WriteAsync(Encoding.ASCII.GetBytes(
                        $"HTTP/1.1 {status} Status\r\nContent-Length: 10000\r\nConnection: close\r\n\r\n{{"), _stop.Token);
                    await stream.FlushAsync(_stop.Token);
                }

                Ready.TrySetResult(true);
                var probe = new byte[1];
                PeerClosed.TrySetResult(await stream.ReadAsync(probe, _stop.Token) == 0);
            }
            catch (IOException) when (!_stop.IsCancellationRequested)
            {
                PeerClosed.TrySetResult(true);
            }
        }

        public async ValueTask DisposeAsync()
        {
            _stop.Cancel();
            _listener.Stop();
            try
            {
                await _server;
            }
            catch (Exception error) when (error is OperationCanceledException or IOException or SocketException)
            {
            }
            finally
            {
                _stop.Dispose();
            }
        }
    }

    private sealed class BlockingStream(TaskCompletionSource<bool> started) : Stream
    {
        public bool Disposed { get; private set; }

        public override bool CanRead => true;

        public override bool CanSeek => false;

        public override bool CanWrite => false;

        public override long Length => throw new NotSupportedException();

        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            started.TrySetResult(true);
            await Task.Delay(Timeout.Infinite, cancellationToken);
            return 0;
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
}

#pragma warning restore GHCP001
#endif
