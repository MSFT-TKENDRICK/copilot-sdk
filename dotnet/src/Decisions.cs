/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.Net.Http;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;

namespace GitHub.Copilot;

// ============================================================================
// Request types
//
// The Decisions wire format is polymorphic (string-or-array content, discriminated input items,
// questions, answers, and string-or-boolean choice values), so the SDK reads and writes it with
// hand-written Utf8JsonWriter/JsonDocument code instead of reflection-based serialization. The
// [JsonPropertyName] attributes document the wire names.
// ============================================================================

/// <summary>
/// Calls OpenAI's Decisions API (<c>POST {baseUrl}/decisions</c>) directly against the provider
/// endpoint and credentials the session is configured with.
/// </summary>
[Experimental(Diagnostics.Experimental)]
public interface ISessionDecisionsApi
{
    /// <summary>
    /// Calls the Decisions API against the session's provider. Requires the runtime to run with
    /// <c>COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true</c>.
    /// </summary>
    /// <param name="request">The Decisions request. <see cref="DecisionsRequest.Model"/> is required.</param>
    /// <param name="options">Optional per-call options.</param>
    /// <param name="cancellationToken">Cancels the call. Cancellation surfaces as <see cref="OperationCanceledException"/>.</param>
    /// <returns>The parsed Decisions result.</returns>
    /// <exception cref="ArgumentException"><see cref="DecisionsRequest.Model"/> is missing, or the request is malformed.</exception>
    /// <exception cref="DecisionsException">The endpoint could not be resolved or used, or the request failed.</exception>
    Task<DecisionsResult> CreateAsync(DecisionsRequest request, DecisionsOptions? options = null, CancellationToken cancellationToken = default);
}

/// <summary>Per-call options for <see cref="ISessionDecisionsApi.CreateAsync"/>.</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsOptions
{
    /// <summary>
    /// Passed verbatim as <c>modelId</c> to <c>session.provider.getEndpoint</c>. Leave <see langword="null"/>
    /// to use the session's current endpoint. This is never derived from <see cref="DecisionsRequest.Model"/>.
    /// </summary>
    public string? EndpointModelId { get; set; }

    /// <summary>
    /// Total time allowed for endpoint resolution, HTTP attempts, response bodies, and credential refresh.
    /// <see langword="null"/> uses 600 seconds; <see cref="System.Threading.Timeout.InfiniteTimeSpan"/>
    /// disables the SDK deadline. Other values must be positive. Expiry raises
    /// <see cref="DecisionsErrorKind.Timeout"/>; caller cancellation remains native.
    /// </summary>
    public TimeSpan? Timeout { get; set; }

    /// <summary>
    /// Custom HTTP handler (proxying, testing). The handler is not disposed by the SDK. Injected handlers are
    /// trusted to apply their own TLS and redirect policy: an <see cref="HttpClientHandler"/> (or, on .NET,
    /// <c>SocketsHttpHandler</c>) found at the end of the handler chain must have
    /// <c>AllowAutoRedirect = false</c>, otherwise <see cref="ISessionDecisionsApi.CreateAsync"/> throws
    /// <see cref="ArgumentException"/>. If any other handler follows a redirect, the response is rejected with
    /// <see cref="DecisionsErrorKind.InvalidResponse"/>, but the redirected request has already been sent.
    /// When <see langword="null"/>, the SDK uses a shared handler that never follows redirects.
    /// Handlers must honor cancellation; their independent timeouts surface as sanitized network failures.
    /// </summary>
    public HttpMessageHandler? Handler { get; set; }
}

/// <summary>
/// Content that is either plain text or a list of content parts.
/// </summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsContent
{
    /// <summary>Creates text content.</summary>
    public DecisionsContent(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        Text = text;
    }

    /// <summary>Creates content from parts.</summary>
    public DecisionsContent(IList<DecisionsContentPart> parts)
    {
        ArgumentNullException.ThrowIfNull(parts);
        Parts = parts;
    }

    /// <summary>The text content, when this content is plain text.</summary>
    public string? Text { get; }

    /// <summary>The content parts, when this content is a list of parts.</summary>
    public IList<DecisionsContentPart>? Parts { get; }

    /// <summary>Converts text to content.</summary>
    public static implicit operator DecisionsContent(string text) => new(text);

    /// <summary>Converts parts to content.</summary>
    public static implicit operator DecisionsContent(DecisionsContentPart[] parts) => new(parts);

    /// <summary>Converts parts to content.</summary>
    public static implicit operator DecisionsContent(List<DecisionsContentPart> parts) => new(parts);
}

/// <summary>Marks the end of an explicit prompt-cache segment.</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsCacheBreakpoint
{
    /// <summary>The breakpoint mode. Defaults to <c>explicit</c>.</summary>
    [JsonPropertyName("mode")]
    public string Mode { get; set; } = "explicit";
}

/// <summary>Base type for content parts.</summary>
[Experimental(Diagnostics.Experimental)]
public abstract class DecisionsContentPart
{
    private protected DecisionsContentPart()
    {
    }

    /// <summary>The wire <c>type</c> discriminator.</summary>
    [JsonPropertyName("type")]
    public abstract string Type { get; }
}

/// <summary>Text content part (<c>input_text</c>).</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsInputText : DecisionsContentPart
{
    /// <inheritdoc />
    public override string Type => "input_text";

    /// <summary>The text.</summary>
    [JsonPropertyName("text")]
    public string Text { get; set; } = string.Empty;

    /// <summary>Optional explicit prompt-cache breakpoint for input; omitted from question instructions.</summary>
    [JsonPropertyName("prompt_cache_breakpoint")]
    public DecisionsCacheBreakpoint? PromptCacheBreakpoint { get; set; }
}

/// <summary>Image content part (<c>input_image</c>). <see cref="ImageUrl"/> is a data URL or an <c>http(s)</c> URL.</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsInputImage : DecisionsContentPart
{
    /// <inheritdoc />
    public override string Type => "input_image";

    /// <summary>A data URL or an <c>http(s)</c> URL.</summary>
    [JsonPropertyName("image_url")]
    public string ImageUrl { get; set; } = string.Empty;

    /// <summary>Optional detail: <c>low</c>, <c>high</c>, <c>auto</c>, or <c>original</c>.</summary>
    [JsonPropertyName("detail")]
    public string? Detail { get; set; }

    /// <summary>Optional explicit prompt-cache breakpoint for input; omitted from question instructions.</summary>
    [JsonPropertyName("prompt_cache_breakpoint")]
    public DecisionsCacheBreakpoint? PromptCacheBreakpoint { get; set; }
}

/// <summary>Structured JSON content part (<c>input_json</c>).</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsInputJson : DecisionsContentPart
{
    /// <inheritdoc />
    public override string Type => "input_json";

    /// <summary>A JSON object or an array of JSON objects, sent verbatim.</summary>
    [JsonPropertyName("data")]
    public JsonElement Data { get; set; }
}

/// <summary>Assistant text content part (<c>output_text</c>).</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsOutputText : DecisionsContentPart
{
    /// <inheritdoc />
    public override string Type => "output_text";

    /// <summary>The text.</summary>
    [JsonPropertyName("text")]
    public string Text { get; set; } = string.Empty;
}

/// <summary>Assistant refusal content part (<c>refusal</c>).</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsRefusalContent : DecisionsContentPart
{
    /// <inheritdoc />
    public override string Type => "refusal";

    /// <summary>The refusal text.</summary>
    [JsonPropertyName("refusal")]
    public string Refusal { get; set; } = string.Empty;
}

/// <summary>Base type for input items.</summary>
[Experimental(Diagnostics.Experimental)]
public abstract class DecisionsInputItem
{
    private protected DecisionsInputItem()
    {
    }
}

/// <summary>
/// A conversation message. <see cref="Role"/> is <c>user</c>, <c>developer</c>, <c>system</c>, or <c>assistant</c>.
/// User content may contain <see cref="DecisionsInputText"/>, <see cref="DecisionsInputImage"/>, and
/// <see cref="DecisionsInputJson"/>; developer/system content <see cref="DecisionsInputText"/>; assistant content
/// <see cref="DecisionsOutputText"/> and <see cref="DecisionsRefusalContent"/>.
/// </summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsMessage : DecisionsInputItem
{
    /// <summary>When <see langword="true"/>, the <c>"type": "message"</c> discriminator is omitted from the wire item.</summary>
    [JsonIgnore]
    public bool OmitType { get; set; }

    /// <summary>The message role.</summary>
    [JsonPropertyName("role")]
    public string Role { get; set; } = "user";

    /// <summary>The message content.</summary>
    [JsonPropertyName("content")]
    public DecisionsContent? Content { get; set; }

    /// <summary>Assistant phase (<c>commentary</c> or <c>final_answer</c>). Sent only for the <c>assistant</c> role.</summary>
    [JsonPropertyName("phase")]
    public string? Phase { get; set; }
}

/// <summary>A function tool call item (<c>function_call</c>).</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsFunctionCall : DecisionsInputItem
{
    /// <summary>The call identifier.</summary>
    [JsonPropertyName("call_id")]
    public string CallId { get; set; } = string.Empty;

    /// <summary>The function name.</summary>
    [JsonPropertyName("name")]
    public string Name { get; set; } = string.Empty;

    /// <summary>The JSON-encoded arguments.</summary>
    [JsonPropertyName("arguments")]
    public string Arguments { get; set; } = string.Empty;

    /// <summary>Optional item identifier.</summary>
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    /// <summary>Optional tool namespace.</summary>
    [JsonPropertyName("namespace")]
    public string? Namespace { get; set; }

    /// <summary>Optional status: <c>in_progress</c>, <c>completed</c>, or <c>incomplete</c>.</summary>
    [JsonPropertyName("status")]
    public string? Status { get; set; }
}

/// <summary>A function tool call output item (<c>function_call_output</c>).</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsFunctionCallOutput : DecisionsInputItem
{
    /// <summary>The call identifier.</summary>
    [JsonPropertyName("call_id")]
    public string CallId { get; set; } = string.Empty;

    /// <summary>The output: text, or <see cref="DecisionsInputText"/>/<see cref="DecisionsInputImage"/> parts.</summary>
    [JsonPropertyName("output")]
    public DecisionsContent? Output { get; set; }

    /// <summary>Optional item identifier.</summary>
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    /// <summary>Optional status: <c>in_progress</c>, <c>completed</c>, or <c>incomplete</c>.</summary>
    [JsonPropertyName("status")]
    public string? Status { get; set; }
}

/// <summary>A custom tool call item (<c>custom_tool_call</c>).</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsCustomToolCall : DecisionsInputItem
{
    /// <summary>The call identifier.</summary>
    [JsonPropertyName("call_id")]
    public string CallId { get; set; } = string.Empty;

    /// <summary>The tool name.</summary>
    [JsonPropertyName("name")]
    public string Name { get; set; } = string.Empty;

    /// <summary>The free-form tool input.</summary>
    [JsonPropertyName("input")]
    public string Input { get; set; } = string.Empty;

    /// <summary>Optional item identifier.</summary>
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    /// <summary>Optional tool namespace.</summary>
    [JsonPropertyName("namespace")]
    public string? Namespace { get; set; }

    /// <summary>Optional status: <c>in_progress</c>, <c>completed</c>, or <c>incomplete</c>.</summary>
    [JsonPropertyName("status")]
    public string? Status { get; set; }
}

/// <summary>A custom tool call output item (<c>custom_tool_call_output</c>).</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsCustomToolCallOutput : DecisionsInputItem
{
    /// <summary>The call identifier.</summary>
    [JsonPropertyName("call_id")]
    public string CallId { get; set; } = string.Empty;

    /// <summary>The output: text, or <see cref="DecisionsInputText"/>/<see cref="DecisionsInputImage"/> parts.</summary>
    [JsonPropertyName("output")]
    public DecisionsContent? Output { get; set; }

    /// <summary>Optional item identifier.</summary>
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    /// <summary>Optional status: <c>in_progress</c>, <c>completed</c>, or <c>incomplete</c>.</summary>
    [JsonPropertyName("status")]
    public string? Status { get; set; }
}

/// <summary>
/// An input item sent verbatim (already in wire format). Use for item types this SDK version does not model yet.
/// </summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsRawInputItem : DecisionsInputItem
{
    /// <summary>Creates a raw item from a JSON object.</summary>
    public DecisionsRawInputItem(JsonElement raw)
    {
        Raw = raw;
    }

    /// <summary>The JSON object written verbatim.</summary>
    public JsonElement Raw { get; }
}

/// <summary>The request input: plain text or a list of input items.</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsInput
{
    /// <summary>Creates text input.</summary>
    public DecisionsInput(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        Text = text;
    }

    /// <summary>Creates item input.</summary>
    public DecisionsInput(IList<DecisionsInputItem> items)
    {
        ArgumentNullException.ThrowIfNull(items);
        Items = items;
    }

    /// <summary>The text input, when the input is plain text.</summary>
    public string? Text { get; }

    /// <summary>The input items, when the input is a list of items.</summary>
    public IList<DecisionsInputItem>? Items { get; }

    /// <summary>Converts text to input.</summary>
    public static implicit operator DecisionsInput(string text) => new(text);

    /// <summary>Converts items to input.</summary>
    public static implicit operator DecisionsInput(DecisionsInputItem[] items) => new(items);

    /// <summary>Converts items to input.</summary>
    public static implicit operator DecisionsInput(List<DecisionsInputItem> items) => new(items);
}

/// <summary>A choice value: a string or a boolean. The identity (string vs boolean) is preserved.</summary>
[Experimental(Diagnostics.Experimental)]
public readonly struct DecisionsChoiceValue : IEquatable<DecisionsChoiceValue>
{
    private readonly string? _string;
    private readonly bool _boolean;

    /// <summary>Creates a string choice value.</summary>
    public DecisionsChoiceValue(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        _string = value;
        _boolean = false;
        IsBoolean = false;
    }

    /// <summary>Creates a boolean choice value.</summary>
    public DecisionsChoiceValue(bool value)
    {
        _string = null;
        _boolean = value;
        IsBoolean = true;
    }

    /// <summary>Whether the value is a boolean.</summary>
    public bool IsBoolean { get; }

    /// <summary>Whether the value is a string.</summary>
    public bool IsString => !IsBoolean;

    /// <summary>The string value. Throws when the value is a boolean.</summary>
    public string StringValue => IsBoolean ? throw new InvalidOperationException("The choice value is a boolean.") : _string ?? string.Empty;

    /// <summary>The boolean value. Throws when the value is a string.</summary>
    public bool BooleanValue => IsBoolean ? _boolean : throw new InvalidOperationException("The choice value is a string.");

    /// <summary>Converts a string to a choice value.</summary>
    public static implicit operator DecisionsChoiceValue(string value) => new(value);

    /// <summary>Converts a boolean to a choice value.</summary>
    public static implicit operator DecisionsChoiceValue(bool value) => new(value);

    /// <summary>Compares two choice values.</summary>
    public static bool operator ==(DecisionsChoiceValue left, DecisionsChoiceValue right) => left.Equals(right);

    /// <summary>Compares two choice values.</summary>
    public static bool operator !=(DecisionsChoiceValue left, DecisionsChoiceValue right) => !left.Equals(right);

    /// <inheritdoc />
    public bool Equals(DecisionsChoiceValue other) =>
        IsBoolean == other.IsBoolean && (IsBoolean ? _boolean == other._boolean : string.Equals(_string ?? string.Empty, other._string ?? string.Empty, StringComparison.Ordinal));

    /// <inheritdoc />
    public override bool Equals(object? obj) => obj is DecisionsChoiceValue other && Equals(other);

    /// <inheritdoc />
    public override int GetHashCode() => IsBoolean ? _boolean.GetHashCode() : StringComparer.Ordinal.GetHashCode(_string ?? string.Empty);

    /// <summary>Returns <c>true</c>/<c>false</c> for booleans and the string otherwise.</summary>
    public override string ToString() => IsBoolean ? (_boolean ? "true" : "false") : _string ?? string.Empty;
}

/// <summary>Base type for questions.</summary>
[Experimental(Diagnostics.Experimental)]
public abstract class DecisionsQuestion
{
    private protected DecisionsQuestion()
    {
    }

    /// <summary>The wire <c>type</c> discriminator.</summary>
    [JsonPropertyName("type")]
    public abstract string Type { get; }

    /// <summary>Optional question name, echoed on the answer.</summary>
    [JsonPropertyName("name")]
    public string? Name { get; set; }

    /// <summary>
    /// The instructions: text, or <see cref="DecisionsInputText"/>, <see cref="DecisionsInputImage"/>,
    /// and <see cref="DecisionsInputJson"/> parts. Input cache breakpoints are omitted;
    /// assistant and refusal parts are rejected.
    /// </summary>
    [JsonPropertyName("instructions")]
    public DecisionsContent? Instructions { get; set; }
}

/// <summary>A yes/no question answered with a probability.</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsPredicateQuestion : DecisionsQuestion
{
    /// <inheritdoc />
    public override string Type => "predicate";
}

/// <summary>An option of a <see cref="DecisionsChoiceQuestion"/>.</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsChoiceOption
{
    /// <summary>The option value (string or boolean).</summary>
    [JsonPropertyName("value")]
    public DecisionsChoiceValue Value { get; set; }

    /// <summary>Optional description.</summary>
    [JsonPropertyName("description")]
    public string? Description { get; set; }
}

/// <summary>A question answered by choosing one of <see cref="Choices"/>.</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsChoiceQuestion : DecisionsQuestion
{
    /// <inheritdoc />
    public override string Type => "choice";

    /// <summary>The options.</summary>
    [JsonPropertyName("choices")]
    public IList<DecisionsChoiceOption> Choices { get => field ??= []; set; }
}

/// <summary>A level of a <see cref="DecisionsScoreQuestion"/>.</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsScoreLevel
{
    /// <summary>The level label.</summary>
    [JsonPropertyName("label")]
    public string Label { get; set; } = string.Empty;

    /// <summary>Optional description.</summary>
    [JsonPropertyName("description")]
    public string? Description { get; set; }
}

/// <summary>A question answered with a probability-weighted level index.</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsScoreQuestion : DecisionsQuestion
{
    /// <inheritdoc />
    public override string Type => "score";

    /// <summary>The ordered levels.</summary>
    [JsonPropertyName("levels")]
    public IList<DecisionsScoreLevel> Levels { get => field ??= []; set; }
}

/// <summary>Prompt-cache options.</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsPromptCacheOptions
{
    /// <summary>Optional mode: <c>implicit</c> or <c>explicit</c>.</summary>
    [JsonPropertyName("mode")]
    public string? Mode { get; set; }

    /// <summary>Optional TTL. The documented value is <c>30m</c>.</summary>
    [JsonPropertyName("ttl")]
    public string? Ttl { get; set; }
}

/// <summary>A Decisions API request. Optional properties left <see langword="null"/> are omitted from the wire request.</summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsRequest
{
    /// <summary>Model to send on the wire (for Azure, the deployment name). Required.</summary>
    [JsonPropertyName("model")]
    public string Model { get; set; } = string.Empty;

    /// <summary>The input: text or input items.</summary>
    [JsonPropertyName("input")]
    public DecisionsInput? Input { get; set; }

    /// <summary>1 to 400 questions (not validated client-side).</summary>
    [JsonPropertyName("questions")]
    public IList<DecisionsQuestion> Questions { get => field ??= []; set; }

    /// <summary>Optional prompt-cache key.</summary>
    [JsonPropertyName("prompt_cache_key")]
    public string? PromptCacheKey { get; set; }

    /// <summary>Optional prompt-cache options.</summary>
    [JsonPropertyName("prompt_cache_options")]
    public DecisionsPromptCacheOptions? PromptCacheOptions { get; set; }

    /// <summary>
    /// Opaque, stable identifier for the end user, used for abuse detection. Never send an email address,
    /// username, or credential; hash or map it first.
    /// </summary>
    [JsonPropertyName("safety_identifier")]
    public string? SafetyIdentifier { get; set; }
}

// ============================================================================
// Response types
// ============================================================================

/// <summary>Base type for answers. Switch on the derived type; unrecognized wire types map to <see cref="DecisionsUnknownAnswer"/>.</summary>
/// <param name="Name">The question name, or <see langword="null"/>.</param>
[Experimental(Diagnostics.Experimental)]
public abstract record DecisionsAnswer([property: JsonPropertyName("name")] string? Name);

/// <summary>Answer to a <see cref="DecisionsPredicateQuestion"/>.</summary>
/// <param name="Name">The question name, or <see langword="null"/>.</param>
/// <param name="Probability">Probability that the predicate holds.</param>
[Experimental(Diagnostics.Experimental)]
public sealed record DecisionsPredicateAnswer(
    string? Name,
    [property: JsonPropertyName("probability")] double Probability) : DecisionsAnswer(Name);

/// <summary>Probability of one choice option.</summary>
/// <param name="Value">The option value.</param>
/// <param name="Probability">Its probability.</param>
[Experimental(Diagnostics.Experimental)]
public sealed record DecisionsChoiceProbability(
    [property: JsonPropertyName("value")] DecisionsChoiceValue Value,
    [property: JsonPropertyName("probability")] double Probability);

/// <summary>Answer to a <see cref="DecisionsChoiceQuestion"/>.</summary>
/// <param name="Name">The question name, or <see langword="null"/>.</param>
/// <param name="Choice">The chosen value.</param>
/// <param name="Confidence">Confidence in the choice.</param>
/// <param name="Probabilities">Per-option probabilities.</param>
[Experimental(Diagnostics.Experimental)]
public sealed record DecisionsChoiceAnswer(
    string? Name,
    [property: JsonPropertyName("choice")] DecisionsChoiceValue Choice,
    [property: JsonPropertyName("confidence")] double Confidence,
    [property: JsonPropertyName("probabilities")] IReadOnlyList<DecisionsChoiceProbability> Probabilities) : DecisionsAnswer(Name);

/// <summary>Probability of one score level.</summary>
/// <param name="Label">The level label.</param>
/// <param name="Value">Signed 64-bit integer index of the level, including provider sentinel values.</param>
/// <param name="Probability">Its probability.</param>
[Experimental(Diagnostics.Experimental)]
public sealed record DecisionsScoreProbability(
    [property: JsonPropertyName("label")] string Label,
    [property: JsonPropertyName("value")] long Value,
    [property: JsonPropertyName("probability")] double Probability);

/// <summary>Answer to a <see cref="DecisionsScoreQuestion"/>.</summary>
/// <param name="Name">The question name, or <see langword="null"/>.</param>
/// <param name="Score">Probability-weighted level index (fractional).</param>
/// <param name="Confidence">Confidence in the score.</param>
/// <param name="Probabilities">Per-level probabilities.</param>
[Experimental(Diagnostics.Experimental)]
public sealed record DecisionsScoreAnswer(
    string? Name,
    [property: JsonPropertyName("score")] double Score,
    [property: JsonPropertyName("confidence")] double Confidence,
    [property: JsonPropertyName("probabilities")] IReadOnlyList<DecisionsScoreProbability> Probabilities) : DecisionsAnswer(Name);

/// <summary>The model declined to answer the question.</summary>
/// <param name="Name">The question name, or <see langword="null"/>.</param>
[Experimental(Diagnostics.Experimental)]
public sealed record DecisionsRefusalAnswer(string? Name) : DecisionsAnswer(Name);

/// <summary>An answer whose <c>type</c> this SDK version does not recognize.</summary>
/// <param name="Name">The question name, or <see langword="null"/>.</param>
/// <param name="WireType">The original <c>type</c>.</param>
/// <param name="Raw">The original JSON object.</param>
[Experimental(Diagnostics.Experimental)]
public sealed record DecisionsUnknownAnswer(string? Name, string WireType, JsonElement Raw) : DecisionsAnswer(Name);

/// <summary>Input token details.</summary>
/// <param name="CachedTokens">Tokens read from the prompt cache.</param>
/// <param name="CacheWriteTokens">Tokens written to the prompt cache.</param>
[Experimental(Diagnostics.Experimental)]
public sealed record DecisionsInputTokensDetails(
    [property: JsonPropertyName("cached_tokens")] long CachedTokens,
    [property: JsonPropertyName("cache_write_tokens")] long CacheWriteTokens);

/// <summary>Output token details.</summary>
/// <param name="ReasoningTokens">Reasoning tokens.</param>
[Experimental(Diagnostics.Experimental)]
public sealed record DecisionsOutputTokensDetails(
    [property: JsonPropertyName("reasoning_tokens")] long ReasoningTokens);

/// <summary>Token usage. Counts are signed 64-bit integers; missing counts are reported as 0.</summary>
/// <param name="InputTokens">Input tokens.</param>
/// <param name="InputTokensDetails">Input token details.</param>
/// <param name="OutputTokens">Output tokens.</param>
/// <param name="OutputTokensDetails">Output token details.</param>
/// <param name="TotalTokens">Total tokens.</param>
[Experimental(Diagnostics.Experimental)]
public sealed record DecisionsUsage(
    [property: JsonPropertyName("input_tokens")] long InputTokens,
    [property: JsonPropertyName("input_tokens_details")] DecisionsInputTokensDetails InputTokensDetails,
    [property: JsonPropertyName("output_tokens")] long OutputTokens,
    [property: JsonPropertyName("output_tokens_details")] DecisionsOutputTokensDetails OutputTokensDetails,
    [property: JsonPropertyName("total_tokens")] long TotalTokens);

/// <summary>A Decisions API result.</summary>
/// <param name="Answers">One answer per question, in question order.</param>
/// <param name="Model">The model that answered.</param>
/// <param name="Usage">Token usage.</param>
[Experimental(Diagnostics.Experimental)]
public sealed record DecisionsResult(
    [property: JsonPropertyName("answers")] IReadOnlyList<DecisionsAnswer> Answers,
    [property: JsonPropertyName("model")] string Model,
    [property: JsonPropertyName("usage")] DecisionsUsage Usage);

// ============================================================================
// Errors
// ============================================================================

/// <summary>Category of a <see cref="DecisionsException"/>.</summary>
[Experimental(Diagnostics.Experimental)]
public enum DecisionsErrorKind
{
    /// <summary>The runtime could not provide the session's provider endpoint.</summary>
    EndpointUnavailable,
    /// <summary>The provider endpoint has an invalid base URL or headers.</summary>
    InvalidEndpoint,
    /// <summary>The provider type does not support the Decisions API.</summary>
    UnsupportedProvider,
    /// <summary>The provider transport is not HTTP.</summary>
    UnsupportedTransport,
    /// <summary>The provider session token is bound to a different model.</summary>
    ModelMismatch,
    /// <summary>The session token and the API key use the same header.</summary>
    CredentialConflict,
    /// <summary>The Decisions API returned a non-2xx status.</summary>
    Http,
    /// <summary>The Decisions API returned an unexpected response.</summary>
    InvalidResponse,
    /// <summary>The request could not be sent or the response could not be read.</summary>
    Network,
    /// <summary>The SDK-owned total-call deadline expired. Caller cancellation remains native.</summary>
    Timeout,
}

/// <summary>
/// Error raised by <see cref="ISessionDecisionsApi.CreateAsync"/>. Retained provider diagnostics redact
/// known endpoint credentials, including short credentials, and recognized literal/escaped encodings.
/// Other header/query values use an eight-Unicode-scalar heuristic. This is not general PII removal
/// or protection against arbitrary transformations. Caller cancellation is reported with
/// <see cref="OperationCanceledException"/>, not this type.
/// </summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsException : Exception
{
    internal DecisionsException(DecisionsErrorKind kind, string message, Exception? innerException = null)
        : base(message, innerException)
    {
        Kind = kind;
    }

    /// <summary>The error category.</summary>
    public DecisionsErrorKind Kind { get; }

    /// <summary>HTTP status for <see cref="DecisionsErrorKind.Http"/> errors.</summary>
    public int? Status { get; internal init; }

    /// <summary>OpenAI error <c>type</c>, when the error body was parsable.</summary>
    public string? ErrorType { get; internal init; }

    /// <summary>OpenAI error <c>code</c>, when the error body was parsable.</summary>
    public string? ErrorCode { get; internal init; }

    /// <summary>OpenAI error <c>param</c>, when the error body was parsable.</summary>
    public string? ErrorParam { get; internal init; }

    /// <summary>Value of the <c>x-request-id</c> response header.</summary>
    public string? RequestId { get; internal init; }

    /// <summary>
    /// Advisory retry delay in milliseconds from <c>retry-after-ms</c> or <c>Retry-After</c>.
    /// No retry is scheduled. Invalid or absent hints yield <see langword="null"/>.
    /// </summary>
    public long? RetryAfterMs { get; internal init; }

    /// <summary>Redacted original response-body prefix, at most 64 KiB of UTF-8.</summary>
    public string? Body { get; internal init; }

    /// <summary>
    /// Whether the original body exceeded 65,536 UTF-8 bytes. Lookahead is used only to recognize secrets
    /// crossing that boundary, not retained as additional content. A final UTF-8-safe output cap also
    /// limits redaction expansion without changing this flag.
    /// </summary>
    public bool? BodyTruncated { get; internal init; }
}

/// <summary>
/// Sanitized stand-in for a transport exception, used as <see cref="Exception.InnerException"/> of
/// <see cref="DecisionsErrorKind.Network"/> errors. Only bounded, redacted type name, message, and code
/// strings are retained; arbitrary exception properties and chains are discarded.
/// </summary>
[Experimental(Diagnostics.Experimental)]
public sealed class DecisionsTransportException : Exception
{
    internal DecisionsTransportException(string sourceTypeName, string message, string? code)
        : base(message)
    {
        SourceTypeName = sourceTypeName;
        Code = code;
    }

    /// <summary>Bounded, redacted full name of the original exception type.</summary>
    public string SourceTypeName { get; }

    /// <summary>
    /// Error code derived from the original exception (for example a <c>SocketError</c> or
    /// <c>HttpRequestError</c> name), or <see langword="null"/>.
    /// </summary>
    public string? Code { get; }
}
