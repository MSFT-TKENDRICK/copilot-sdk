/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using GitHub.Copilot.Rpc;

namespace GitHub.Copilot;

/// <summary>Implementation of <see cref="CopilotSession.Decisions"/>.</summary>
internal sealed class SessionDecisionsApi(CopilotSession session) : ISessionDecisionsApi
{
    internal const int MaxErrorBodyBytes = 64 * 1024;
    internal const int MaxResponseBytes = 8 * 1024 * 1024;

    private const string GateEnv = "COPILOT_ALLOW_GET_PROVIDER_ENDPOINT";
    private static readonly DecisionsOptions s_defaultOptions = new();

    public async Task<DecisionsResult> CreateAsync(DecisionsRequest request, DecisionsOptions? options = null, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (string.IsNullOrEmpty(request.Model))
        {
            throw new ArgumentException("DecisionsRequest.Model is required.", nameof(request));
        }

        options ??= s_defaultOptions;
        var handler = options.Handler;
        cancellationToken.ThrowIfCancellationRequested();

        var model = request.Model;
        var plan = DecisionsPlanner.Plan(await ResolveAsync(options, cancellationToken).ConfigureAwait(false), model);
        var body = DecisionsWire.SerializeRequest(request);

        if (handler is not null)
        {
            DecisionsHttp.EnsureNoAutoRedirect(options);
        }

        var client = handler is null ? DecisionsHttp.SharedClient : DecisionsHttp.CreateClient(handler);
        try
        {
            var response = await DecisionsHttp.SendAsync(client, plan, body, cancellationToken).ConfigureAwait(false);
            try
            {
                if ((int)response.StatusCode is 401 or 403)
                {
                    DecisionsException original;
                    try
                    {
                        original = await HttpErrorAsync(response, plan, cancellationToken).ConfigureAwait(false);
                    }
                    finally
                    {
                        response.Dispose();
                    }

                    var refreshedEndpoint = await ResolveAsync(options, cancellationToken).ConfigureAwait(false);
                    DecisionsAttemptPlan refreshed;
                    try
                    {
                        refreshed = DecisionsPlanner.Plan(refreshedEndpoint, model);
                    }
                    catch (Exception ex) when (ex is not OperationCanceledException)
                    {
                        throw original;
                    }

                    if (refreshed.Fingerprint == plan.Fingerprint)
                    {
                        throw original;
                    }

                    plan = refreshed;
                    response = await DecisionsHttp.SendAsync(client, plan, body, cancellationToken).ConfigureAwait(false);
                }

                var status = (int)response.StatusCode;
                if (status is < 200 or >= 300)
                {
                    throw await HttpErrorAsync(response, plan, cancellationToken).ConfigureAwait(false);
                }

                byte[] bytes;
                bool exceeded;
                try
                {
                    (bytes, exceeded) = await DecisionsHttp.ReadLimitedAsync(response, MaxResponseBytes, cancellationToken).ConfigureAwait(false);
                }
                catch (Exception ex)
                {
                    RethrowIfCancelled(ex, cancellationToken);
                    throw DecisionsHttp.NetworkError("The Decisions response could not be read.", ex, plan.Secrets);
                }

                if (exceeded)
                {
                    throw new DecisionsException(
                        DecisionsErrorKind.InvalidResponse,
                        $"The Decisions API response exceeded {MaxResponseBytes} bytes.");
                }

                JsonDocument document;
                try
                {
                    document = JsonDocument.Parse(DecisionsText.DecodeUtf8(bytes, bytes.Length));
                }
                catch (JsonException)
                {
                    throw new DecisionsException(
                        DecisionsErrorKind.InvalidResponse,
                        "The Decisions API returned a response that is not valid JSON.");
                }

                using (document)
                {
                    return DecisionsWire.ParseResult(document.RootElement);
                }
            }
            finally
            {
                response.Dispose();
            }
        }
        finally
        {
            if (!ReferenceEquals(client, DecisionsHttp.SharedClient))
            {
                client.Dispose();
            }
        }
    }

    private static async Task<DecisionsException> HttpErrorAsync(HttpResponseMessage response, DecisionsAttemptPlan plan, CancellationToken cancellationToken)
    {
        try
        {
            return await DecisionsHttp.CreateHttpErrorAsync(response, plan.Secrets, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            RethrowIfCancelled(ex, cancellationToken);
            throw DecisionsHttp.NetworkError("The Decisions error response could not be read.", ex, plan.Secrets);
        }
    }

    private async Task<ProviderEndpoint> ResolveAsync(DecisionsOptions options, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        ProviderEndpoint? endpoint;
        try
        {
            var parameters = options.EndpointModelId is null
                ? null
                : new SessionProviderGetEndpointRequest { ModelId = options.EndpointModelId };
            endpoint = await session.Rpc.Provider.GetEndpointAsync(parameters, cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            cancellationToken.ThrowIfCancellationRequested();
            throw EndpointUnavailable(ex);
        }

        cancellationToken.ThrowIfCancellationRequested();
        return endpoint ?? throw EndpointUnavailable(new InvalidOperationException("The runtime returned no provider endpoint."));
    }

    private static DecisionsException EndpointUnavailable(Exception cause)
    {
        var gated = false;
        for (var current = cause; current is not null; current = current.InnerException)
        {
            if (current.Message.Contains(GateEnv))
            {
                gated = true;
                break;
            }
        }

        var message = gated
            ? $"session.decisions requires the runtime's provider endpoint API. Set {GateEnv}=true in the runtime's environment (for in-process runtimes, in the host process environment before the runtime is loaded)."
            : "Could not resolve the session's provider endpoint for the Decisions API.";
        return new DecisionsException(DecisionsErrorKind.EndpointUnavailable, message, cause);
    }

    private static void RethrowIfCancelled(Exception ex, CancellationToken cancellationToken)
    {
        if (!cancellationToken.IsCancellationRequested)
        {
            return;
        }

        if (ex is OperationCanceledException)
        {
            System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(ex).Throw();
        }

        cancellationToken.ThrowIfCancellationRequested();
    }
}

/// <summary>A validated request plan for one Decisions attempt.</summary>
internal sealed class DecisionsAttemptPlan(string url, IReadOnlyList<KeyValuePair<string, string>> headers, string fingerprint, IReadOnlyList<string> secrets)
{
    public string Url { get; } = url;

    public IReadOnlyList<KeyValuePair<string, string>> Headers { get; } = headers;

    public string Fingerprint { get; } = fingerprint;

    public IReadOnlyList<string> Secrets { get; } = secrets;
}

/// <summary>Endpoint validation, URL/header construction, and credential collection.</summary>
internal static class DecisionsPlanner
{
    private const int MinSecretLength = 8;
    private static readonly char[] s_authorityTerminators = ['/', '?', '#', '\\'];

    public static DecisionsAttemptPlan Plan(ProviderEndpoint endpoint, string model)
    {
        var type = endpoint.Type.Value;
        if (!string.Equals(type, "openai", StringComparison.Ordinal) && !string.Equals(type, "azure", StringComparison.Ordinal))
        {
            throw new DecisionsException(
                DecisionsErrorKind.UnsupportedProvider,
                $"The session's provider type '{type}' does not support the Decisions API.");
        }

        if (endpoint.Transport is { } transport && !string.Equals(transport.Value, "http", StringComparison.Ordinal))
        {
            throw new DecisionsException(
                DecisionsErrorKind.UnsupportedTransport,
                $"The session's provider transport '{transport.Value}' is not supported by the Decisions API.");
        }

        var tokenModel = endpoint.SessionToken?.Model;
        if (tokenModel is not null && !string.Equals(tokenModel, model, StringComparison.Ordinal))
        {
            throw new DecisionsException(
                DecisionsErrorKind.ModelMismatch,
                $"The provider session token is bound to model '{tokenModel}', but the request uses '{model}'. Set endpointModelId or request.model consistently.");
        }

        var url = BuildUrl(type, endpoint.BaseUrl);
        var headers = BuildHeaders(endpoint);
        var tokenHeader = endpoint.SessionToken is { } token ? (token.Header ?? string.Empty).ToLowerInvariant() : null;
        var fingerprint = string.Join(
            "\u0000",
            url,
            Fingerprint(Lookup(headers, "authorization")),
            Fingerprint(Lookup(headers, "api-key")),
            tokenHeader is null ? "\u0001" : Fingerprint(tokenHeader) + "\u0000" + Fingerprint(Lookup(headers, tokenHeader)));
        return new DecisionsAttemptPlan(url, headers, fingerprint, CollectSecrets(endpoint, url));
    }

    private static string Fingerprint(string? value) => value is null ? "\u0001" : "\u0002" + value;

    private static string? Lookup(List<KeyValuePair<string, string>> headers, string name)
    {
        foreach (var header in headers)
        {
            if (string.Equals(header.Key, name, StringComparison.Ordinal))
            {
                return header.Value;
            }
        }

        return null;
    }

    private static DecisionsException InvalidEndpoint(string message) => new(DecisionsErrorKind.InvalidEndpoint, message);

    internal static string BuildUrl(string type, string? baseUrl)
    {
        if (string.IsNullOrEmpty(baseUrl) || !Uri.TryCreate(baseUrl, UriKind.Absolute, out var uri))
        {
            throw InvalidEndpoint("The provider endpoint base URL is not a valid absolute URL.");
        }

        if (!string.Equals(uri.Scheme, "http", StringComparison.Ordinal) && !string.Equals(uri.Scheme, "https", StringComparison.Ordinal))
        {
            throw InvalidEndpoint("The provider endpoint base URL must use http or https.");
        }

        if (!string.IsNullOrEmpty(uri.UserInfo) || RawAuthorityHasUserInfo(baseUrl!))
        {
            throw InvalidEndpoint("The provider endpoint base URL must not contain credentials.");
        }

        var basePath = uri.AbsolutePath.TrimEnd('/');
        var suffix = string.Equals(type, "azure", StringComparison.Ordinal) && !basePath.EndsWith("/openai/v1", StringComparison.OrdinalIgnoreCase)
            ? "/openai/v1/decisions"
            : "/decisions";
        var query = uri.Query;
        if (query == "?")
        {
            query = string.Empty;
        }

        return uri.GetComponents(UriComponents.SchemeAndServer, UriFormat.UriEscaped) + basePath + suffix + query;
    }

    private static bool RawAuthorityHasUserInfo(string url)
    {
        var start = url.IndexOf("://", StringComparison.Ordinal);
        if (start < 0)
        {
            return false;
        }

        start += 3;
        var end = url.IndexOfAny(s_authorityTerminators, start);
        var at = url.IndexOf('@', start);
        return at >= 0 && (end < 0 || at < end);
    }

    private static bool IsTokenChar(char c) =>
        c is (>= 'a' and <= 'z') or (>= 'A' and <= 'Z') or (>= '0' and <= '9')
            or '!' or '#' or '$' or '%' or '&' or '\'' or '*' or '+' or '-' or '.' or '^' or '_' or '`' or '|' or '~';

    private static string CheckHeader(string? name, string value)
    {
        var valid = !string.IsNullOrEmpty(name);
        if (valid)
        {
            foreach (var c in name!)
            {
                if (!IsTokenChar(c))
                {
                    valid = false;
                    break;
                }
            }
        }

        if (!valid || value.IndexOfAny(['\r', '\n', '\0']) >= 0)
        {
            throw InvalidEndpoint("The provider endpoint returned an invalid HTTP header.");
        }

        return TrimHttpWhitespace(value);
    }

    internal static string TrimHttpWhitespace(string value) => value.Trim(' ', '\t');

    private static void Set(List<KeyValuePair<string, string>> headers, string name, string value)
    {
        for (var i = 0; i < headers.Count; i++)
        {
            if (string.Equals(headers[i].Key, name, StringComparison.Ordinal))
            {
                headers[i] = new(name, value);
                return;
            }
        }

        headers.Add(new(name, value));
    }

    internal static List<KeyValuePair<string, string>> BuildHeaders(ProviderEndpoint endpoint)
    {
        var merged = new List<KeyValuePair<string, string>>();
        foreach (var entry in endpoint.Headers)
        {
            if (entry.Value is null)
            {
                continue;
            }

            var value = CheckHeader(entry.Key, entry.Value);
            var lower = entry.Key.ToLowerInvariant();
            if (lower is "content-type" or "accept")
            {
                continue;
            }

            var existing = Lookup(merged, lower);
            if (existing is not null && !string.Equals(existing, value, StringComparison.Ordinal))
            {
                throw InvalidEndpoint("The provider endpoint returned conflicting values for the same HTTP header.");
            }

            Set(merged, lower, value);
        }

        string? apiKeyHeader = null;
        if (!string.IsNullOrEmpty(endpoint.ApiKey) && Lookup(merged, "authorization") is null && Lookup(merged, "api-key") is null)
        {
            if (string.Equals(endpoint.Type.Value, "azure", StringComparison.Ordinal))
            {
                apiKeyHeader = "api-key";
                Set(merged, apiKeyHeader, CheckHeader(apiKeyHeader, endpoint.ApiKey!));
            }
            else
            {
                apiKeyHeader = "authorization";
                Set(merged, apiKeyHeader, "Bearer " + CheckHeader(apiKeyHeader, endpoint.ApiKey!));
            }
        }

        if (endpoint.SessionToken is { } sessionToken)
        {
            var value = CheckHeader(sessionToken.Header, sessionToken.Token ?? string.Empty);
            var lower = sessionToken.Header.ToLowerInvariant();
            if (string.Equals(lower, apiKeyHeader, StringComparison.Ordinal))
            {
                throw new DecisionsException(
                    DecisionsErrorKind.CredentialConflict,
                    "The provider session token uses the same header as the endpoint API key.");
            }

            Set(merged, lower, value);
        }

        Set(merged, "content-type", "application/json");
        Set(merged, "accept", "application/json");
        return merged;
    }

    internal static IReadOnlyList<string> CollectSecrets(ProviderEndpoint endpoint, string url)
    {
        var raw = new List<string>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        void Add(string? value)
        {
            if (value is null)
            {
                return;
            }

            var trimmed = TrimHttpWhitespace(value);
            if (DecisionsText.ScalarLength(trimmed) >= MinSecretLength && seen.Add(trimmed))
            {
                raw.Add(trimmed);
            }
        }

        Add(endpoint.ApiKey);
        Add(endpoint.SessionToken?.Token);
        foreach (var value in endpoint.Headers.Values)
        {
            Add(value);
            if (value is not null)
            {
                Add(StripBearer(value));
            }
        }

        foreach (var value in DecisionsText.QueryValues(url))
        {
            Add(value);
        }

        var variants = new List<string>();
        var variantSet = new HashSet<string>(StringComparer.Ordinal);
        foreach (var secret in raw)
        {
            foreach (var variant in DecisionsText.SecretVariants(secret))
            {
                if (variantSet.Add(variant))
                {
                    variants.Add(variant);
                }
            }
        }

        return variants.OrderByDescending(v => v.Length).ToArray();
    }

    private static string StripBearer(string value)
    {
        var i = 0;
        while (i < value.Length && value[i] is ' ' or '\t')
        {
            i++;
        }

        const string Scheme = "bearer";
        if (value.Length - i < Scheme.Length + 1)
        {
            return value;
        }

        for (var j = 0; j < Scheme.Length; j++)
        {
            var c = value[i + j];
            if (c is >= 'A' and <= 'Z')
            {
                c = (char)(c + 32);
            }

            if (c != Scheme[j])
            {
                return value;
            }
        }

        var k = i + Scheme.Length;
        if (value[k] is not (' ' or '\t'))
        {
            return value;
        }

        while (k < value.Length && value[k] is ' ' or '\t')
        {
            k++;
        }

        return value.Substring(k);
    }
}

/// <summary>Text helpers shared by redaction and decoding.</summary>
internal static class DecisionsText
{
    private static readonly UTF8Encoding s_lossyUtf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: false);

    /// <summary>Decodes UTF-8 like WHATWG <c>TextDecoder</c>: lossy, with a leading BOM removed.</summary>
    public static string DecodeUtf8(byte[] bytes, int count)
    {
        var offset = count >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF ? 3 : 0;
        return s_lossyUtf8.GetString(bytes, offset, count - offset);
    }

    public static byte[] EncodeUtf8(string value) => s_lossyUtf8.GetBytes(value);

    /// <summary>
    /// Returns the largest byte count no greater than <paramref name="limit"/> that ends on a UTF-8
    /// character boundary, so a split trailing character is dropped rather than decoded as U+FFFD.
    /// </summary>
    public static int Utf8BoundaryCut(byte[] bytes, int limit)
    {
        if (bytes.Length <= limit)
        {
            return bytes.Length;
        }

        var cut = limit;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80)
        {
            cut--;
        }

        return cut;
    }

    public static int ByteLength(string value) => s_lossyUtf8.GetByteCount(value);

    /// <summary>Length in Unicode scalar values; a valid surrogate pair counts once.</summary>
    public static int ScalarLength(string value)
    {
        var count = 0;
        for (var i = 0; i < value.Length; i++)
        {
            if (char.IsHighSurrogate(value[i]) && i + 1 < value.Length && char.IsLowSurrogate(value[i + 1]))
            {
                i++;
            }

            count++;
        }

        return count;
    }

    /// <summary>Values of the URL query, decoded like WHATWG <c>URLSearchParams</c>.</summary>
    public static IEnumerable<string> QueryValues(string url)
    {
        var queryStart = url.IndexOf('?');
        if (queryStart < 0)
        {
            yield break;
        }

        var query = url.Substring(queryStart + 1);
        var fragment = query.IndexOf('#');
        if (fragment >= 0)
        {
            query = query.Substring(0, fragment);
        }

        foreach (var part in query.Split('&'))
        {
            if (part.Length == 0)
            {
                continue;
            }

            var equals = part.IndexOf('=');
            yield return equals < 0 ? string.Empty : PercentDecode(part.Substring(equals + 1));
        }
    }

    private static int HexValue(char c) => c switch
    {
        >= '0' and <= '9' => c - '0',
        >= 'a' and <= 'f' => c - 'a' + 10,
        >= 'A' and <= 'F' => c - 'A' + 10,
        _ => -1,
    };

    private static string PercentDecode(string value)
    {
        var input = EncodeUtf8(value.Replace('+', ' '));
        var output = new List<byte>(input.Length);
        for (var i = 0; i < input.Length; i++)
        {
            if (input[i] == (byte)'%' && i + 2 < input.Length)
            {
                var hi = HexValue((char)input[i + 1]);
                var lo = HexValue((char)input[i + 2]);
                if (hi >= 0 && lo >= 0)
                {
                    output.Add((byte)((hi << 4) | lo));
                    i += 2;
                    continue;
                }
            }

            output.Add(input[i]);
        }

        var bytes = output.ToArray();
        return s_lossyUtf8.GetString(bytes);
    }

    /// <summary>Equivalent of <c>JSON.stringify(value).slice(1, -1)</c>.</summary>
    public static string JsonEscape(string value)
    {
        var sb = new StringBuilder(value.Length + 8);
        for (var i = 0; i < value.Length; i++)
        {
            var c = value[i];
            switch (c)
            {
                case '"': sb.Append("\\\""); break;
                case '\\': sb.Append("\\\\"); break;
                case '\b': sb.Append("\\b"); break;
                case '\f': sb.Append("\\f"); break;
                case '\n': sb.Append("\\n"); break;
                case '\r': sb.Append("\\r"); break;
                case '\t': sb.Append("\\t"); break;
                default:
                    if (c < 0x20)
                    {
                        AppendUnicodeEscape(sb, c);
                    }
                    else if (char.IsHighSurrogate(c) && i + 1 < value.Length && char.IsLowSurrogate(value[i + 1]))
                    {
                        sb.Append(c).Append(value[i + 1]);
                        i++;
                    }
                    else if (char.IsSurrogate(c))
                    {
                        AppendUnicodeEscape(sb, c);
                    }
                    else
                    {
                        sb.Append(c);
                    }

                    break;
            }
        }

        return sb.ToString();
    }

    private static void AppendUnicodeEscape(StringBuilder sb, char c) =>
        sb.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));

    private static string AsciiJsonEscape(string jsonEscaped)
    {
        var sb = new StringBuilder(jsonEscaped.Length + 8);
        foreach (var c in jsonEscaped)
        {
            if (c >= 0x7F)
            {
                AppendUnicodeEscape(sb, c);
            }
            else
            {
                sb.Append(c);
            }
        }

        return sb.ToString();
    }

    private static bool HasLoneSurrogate(string value)
    {
        for (var i = 0; i < value.Length; i++)
        {
            if (char.IsHighSurrogate(value[i]) && i + 1 < value.Length && char.IsLowSurrogate(value[i + 1]))
            {
                i++;
            }
            else if (char.IsSurrogate(value[i]))
            {
                return true;
            }
        }

        return false;
    }

    /// <summary>Equivalent of JavaScript <c>encodeURIComponent</c> for well-formed strings.</summary>
    public static string EncodeUriComponent(string value)
    {
        var sb = new StringBuilder(value.Length * 3);
        foreach (var b in EncodeUtf8(value))
        {
            var c = (char)b;
            if (c is (>= 'A' and <= 'Z') or (>= 'a' and <= 'z') or (>= '0' and <= '9')
                or '-' or '_' or '.' or '!' or '~' or '*' or '\'' or '(' or ')')
            {
                sb.Append(c);
            }
            else
            {
                sb.Append('%').Append(b.ToString("X2", CultureInfo.InvariantCulture));
            }
        }

        return sb.ToString();
    }

    /// <summary>Wire encodings under which a credential may be echoed back in a response body.</summary>
    public static IEnumerable<string> SecretVariants(string secret)
    {
        var jsonEscaped = JsonEscape(secret);
        var asciiJsonEscaped = AsciiJsonEscape(jsonEscaped);
        yield return secret;
        yield return jsonEscaped;
        yield return asciiJsonEscaped;
        yield return jsonEscaped.Replace("/", "\\/");
        yield return asciiJsonEscaped.Replace("/", "\\/");

        // encodeURIComponent throws for lone surrogates; such a value has no URI form to redact.
        if (!HasLoneSurrogate(secret))
        {
            var uriEncoded = EncodeUriComponent(secret);
            yield return uriEncoded;
            yield return uriEncoded.Replace("%20", "+");
        }
    }

    public static int MaxSecretBytes(IReadOnlyList<string> secrets)
    {
        var max = 0;
        foreach (var secret in secrets)
        {
            max = Math.Max(max, ByteLength(secret));
        }

        return max;
    }

    private static bool IsHex(char c) => HexValue(c) >= 0;

    private static void AppendHex(StringBuilder sb, char c)
    {
        if (c is (>= 'a' and <= 'f') or (>= 'A' and <= 'F'))
        {
            sb.Append('[').Append(char.ToLowerInvariant(c)).Append(char.ToUpperInvariant(c)).Append(']');
        }
        else
        {
            sb.Append(c);
        }
    }

    /// <summary>Regex for one secret variant; hex digits in <c>\uXXXX</c> and <c>%XX</c> escapes match case-insensitively.</summary>
    public static string VariantPattern(string variant)
    {
        var sb = new StringBuilder(variant.Length * 2);
        var i = 0;
        while (i < variant.Length)
        {
            if (variant[i] == '\\' && i + 5 < variant.Length && variant[i + 1] == 'u'
                && IsHex(variant[i + 2]) && IsHex(variant[i + 3]) && IsHex(variant[i + 4]) && IsHex(variant[i + 5]))
            {
                sb.Append("\\\\u");
                for (var j = 2; j < 6; j++)
                {
                    AppendHex(sb, variant[i + j]);
                }

                i += 6;
            }
            else if (variant[i] == '%' && i + 2 < variant.Length && IsHex(variant[i + 1]) && IsHex(variant[i + 2]))
            {
                sb.Append('%');
                AppendHex(sb, variant[i + 1]);
                AppendHex(sb, variant[i + 2]);
                i += 3;
            }
            else
            {
                sb.Append(Regex.Escape(variant[i].ToString()));
                i++;
            }
        }

        return sb.ToString();
    }

    /// <summary>Replaces every occurrence of any secret variant with <c>[REDACTED]</c>, longest first.</summary>
    public static string Redact(string text, IReadOnlyList<string> secrets)
    {
        var result = text;
        foreach (var secret in secrets)
        {
            result = Regex.Replace(result, VariantPattern(secret), "[REDACTED]", RegexOptions.CultureInvariant);
        }

        return result;
    }
}

/// <summary>HTTP transport for the Decisions API.</summary>
internal static class DecisionsHttp
{
    private static HttpClient? s_sharedClient;

    public static HttpClient SharedClient
    {
        get
        {
            if (s_sharedClient is { } client)
            {
                return client;
            }

#if NET
            HttpMessageHandler handler = new SocketsHttpHandler
            {
                AllowAutoRedirect = false,
                UseCookies = false,
                PooledConnectionLifetime = TimeSpan.FromMinutes(5),
            };
#else
            HttpMessageHandler handler = new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false };
#endif
            var created = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
            var existing = Interlocked.CompareExchange(ref s_sharedClient, created, null);
            if (existing is not null)
            {
                created.Dispose();
                return existing;
            }

            return created;
        }
    }

    public static HttpClient CreateClient(HttpMessageHandler handler) =>
        new(handler, disposeHandler: false) { Timeout = Timeout.InfiniteTimeSpan };

    /// <summary>Rejects injected handler chains that end in a handler configured to follow redirects.</summary>
    public static void EnsureNoAutoRedirect(DecisionsOptions options)
    {
        for (HttpMessageHandler? current = options.Handler; current is not null; current = (current as DelegatingHandler)?.InnerHandler)
        {
            var followsRedirects = current switch
            {
                HttpClientHandler clientHandler => clientHandler.AllowAutoRedirect,
#if NET
                SocketsHttpHandler socketsHandler => socketsHandler.AllowAutoRedirect,
#endif
                _ => false,
            };
            if (followsRedirects)
            {
                throw new ArgumentException(
                    "DecisionsOptions.Handler must not follow redirects. Set AllowAutoRedirect = false on the handler.",
                    nameof(options));
            }
        }
    }

    public static async Task<HttpResponseMessage> SendAsync(HttpClient client, DecisionsAttemptPlan plan, byte[] body, CancellationToken cancellationToken)
    {
        var requestUri = new Uri(plan.Url, UriKind.Absolute);
        var request = new HttpRequestMessage(HttpMethod.Post, requestUri);
        var content = new ByteArrayContent(body);
        request.Content = content;
        foreach (var header in plan.Headers)
        {
            if (string.Equals(header.Key, "content-type", StringComparison.Ordinal))
            {
                content.Headers.TryAddWithoutValidation(header.Key, header.Value);
            }
            else if (!request.Headers.TryAddWithoutValidation(header.Key, header.Value))
            {
                content.Headers.TryAddWithoutValidation(header.Key, header.Value);
            }
        }

        HttpResponseMessage response;
        try
        {
            response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            request.Dispose();
            if (cancellationToken.IsCancellationRequested)
            {
                if (ex is OperationCanceledException)
                {
                    throw;
                }

                cancellationToken.ThrowIfCancellationRequested();
            }

            throw NetworkError("The Decisions request could not be sent.", ex, plan.Secrets);
        }

        var finalUri = response.RequestMessage?.RequestUri;
        if (finalUri is not null && !string.Equals(finalUri.AbsoluteUri, requestUri.AbsoluteUri, StringComparison.Ordinal))
        {
            response.Dispose();
            request.Dispose();
            throw new DecisionsException(
                DecisionsErrorKind.InvalidResponse,
                "The custom HTTP handler followed a redirect. The Decisions API does not follow redirects.");
        }

        return response;
    }

    /// <summary>Reads at most <paramref name="limit"/> bytes; <c>exceeded</c> reports whether more data remained.</summary>
    public static async Task<(byte[] Bytes, bool Exceeded)> ReadLimitedAsync(HttpResponseMessage response, int limit, CancellationToken cancellationToken)
    {
        if (response.Content is null)
        {
            return ([], false);
        }

        using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using var buffer = new MemoryStream();
        var chunk = new byte[16 * 1024];
        while (true)
        {
            var remaining = limit + 1 - (int)buffer.Length;
            var read = await stream.ReadAsync(chunk.AsMemory(0, Math.Min(chunk.Length, remaining)), cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                return (buffer.ToArray(), false);
            }

            buffer.Write(chunk, 0, read);
            if (buffer.Length > limit)
            {
                var bytes = new byte[limit];
                Array.Copy(buffer.GetBuffer(), bytes, limit);
                return (bytes, true);
            }
        }
    }

    public static async Task<DecisionsException> CreateHttpErrorAsync(HttpResponseMessage response, IReadOnlyList<string> secrets, CancellationToken cancellationToken)
    {
        var window = SessionDecisionsApi.MaxErrorBodyBytes + DecisionsText.MaxSecretBytes(secrets);
        var (bytes, exceeded) = await ReadLimitedAsync(response, window, cancellationToken).ConfigureAwait(false);
        var raw = DecisionsText.DecodeUtf8(bytes, bytes.Length);
        var body = DecisionsText.Redact(raw, secrets);
        var truncated = exceeded || bytes.Length > SessionDecisionsApi.MaxErrorBodyBytes;
        // Cut even when not truncated: replacing short secrets with "[REDACTED]" can grow the text.
        var encoded = DecisionsText.EncodeUtf8(body);
        if (encoded.Length > SessionDecisionsApi.MaxErrorBodyBytes)
        {
            body = DecisionsText.DecodeUtf8(encoded, DecisionsText.Utf8BoundaryCut(encoded, SessionDecisionsApi.MaxErrorBodyBytes));
        }

        string? errorType = null, errorCode = null, errorParam = null, errorMessage = null;
        if (!truncated)
        {
            try
            {
                using var document = JsonDocument.Parse(raw);
                if (document.RootElement.ValueKind == JsonValueKind.Object
                    && document.RootElement.TryGetProperty("error", out var error)
                    && error.ValueKind == JsonValueKind.Object)
                {
                    errorType = RedactField(error, "type", secrets);
                    errorCode = RedactField(error, "code", secrets);
                    errorParam = RedactField(error, "param", secrets);
                    errorMessage = RedactField(error, "message", secrets);
                }
            }
            catch (JsonException)
            {
                // Non-JSON error body.
            }
        }

        var status = (int)response.StatusCode;
        var statusText = status is >= 300 and < 400
            ? $"HTTP {status} (redirects are not followed)"
            : $"HTTP {status}";
        var message = string.IsNullOrEmpty(errorMessage)
            ? $"Decisions request failed with {statusText}."
            : $"Decisions request failed with {statusText}: {errorMessage}";
        return new DecisionsException(DecisionsErrorKind.Http, message)
        {
            Status = status,
            ErrorType = errorType,
            ErrorCode = errorCode,
            ErrorParam = errorParam,
            RequestId = response.Headers.TryGetValues("x-request-id", out var ids) ? string.Join(", ", ids) : null,
            Body = body,
            BodyTruncated = truncated,
        };
    }

    private static string? RedactField(JsonElement error, string name, IReadOnlyList<string> secrets)
    {
        if (!error.TryGetProperty(name, out var value))
        {
            return null;
        }

        var text = value.ValueKind switch
        {
            JsonValueKind.String => value.GetString(),
            JsonValueKind.Number => DecisionsWire.FormatJsNumber(value),
            JsonValueKind.True => "true",
            JsonValueKind.False => "false",
            _ => null,
        };
        return text is null ? null : DecisionsText.Redact(text, secrets);
    }

    public static DecisionsException NetworkError(string message, Exception error, IReadOnlyList<string> secrets) =>
        new(DecisionsErrorKind.Network, message, Sanitize(error, secrets));

    /// <summary>
    /// Builds a credential-free stand-in for a transport exception: only the type name, a string code, and the
    /// redacted message are retained.
    /// </summary>
    private static DecisionsTransportException Sanitize(Exception error, IReadOnlyList<string> secrets)
    {
        string? code = null;
        for (var current = error; current is not null && code is null; current = current.InnerException)
        {
            if (current is SocketException socketException)
            {
                code = socketException.SocketErrorCode.ToString();
            }
        }

#if NET8_0_OR_GREATER
        for (var current = error; current is not null && code is null; current = current.InnerException)
        {
            if (current is HttpRequestException { HttpRequestError: not HttpRequestError.Unknown } httpException)
            {
                code = httpException.HttpRequestError.ToString();
            }
            else if (current is HttpIOException { HttpRequestError: not HttpRequestError.Unknown } ioException)
            {
                code = ioException.HttpRequestError.ToString();
            }
        }
#endif

        return new DecisionsTransportException(
            error.GetType().FullName ?? error.GetType().Name,
            DecisionsText.Redact(error.Message, secrets),
            code);
    }
}

/// <summary>Hand-written wire serialization and parsing (the wire format is polymorphic).</summary>
internal static class DecisionsWire
{
    private static readonly JsonWriterOptions s_writerOptions = new()
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static byte[] SerializeRequest(DecisionsRequest request)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream, s_writerOptions))
        {
            writer.WriteStartObject();
            writer.WriteString("model", request.Model);
            writer.WritePropertyName("input");
            var input = request.Input ?? throw new ArgumentException("DecisionsRequest.Input is required.", nameof(request));
            if (input.Text is not null)
            {
                writer.WriteStringValue(input.Text);
            }
            else
            {
                writer.WriteStartArray();
                foreach (var item in input.Items!)
                {
                    WriteInputItem(writer, item);
                }

                writer.WriteEndArray();
            }

            writer.WritePropertyName("questions");
            writer.WriteStartArray();
            foreach (var question in request.Questions)
            {
                WriteQuestion(writer, question);
            }

            writer.WriteEndArray();
            WriteIfPresent(writer, "prompt_cache_key", request.PromptCacheKey);
            if (request.PromptCacheOptions is { } cacheOptions)
            {
                writer.WriteStartObject("prompt_cache_options");
                WriteIfPresent(writer, "mode", cacheOptions.Mode);
                WriteIfPresent(writer, "ttl", cacheOptions.Ttl);
                writer.WriteEndObject();
            }

            WriteIfPresent(writer, "safety_identifier", request.SafetyIdentifier);
            writer.WriteEndObject();
        }

        return stream.ToArray();
    }

    private static void WriteIfPresent(Utf8JsonWriter writer, string name, string? value)
    {
        if (value is not null)
        {
            writer.WriteString(name, value);
        }
    }

    private static void WriteRaw(Utf8JsonWriter writer, JsonElement value, string what)
    {
        if (value.ValueKind == JsonValueKind.Undefined)
        {
            throw new ArgumentException($"{what} must be a JSON value.");
        }

        value.WriteTo(writer);
    }

    private static void WriteBreakpoint(Utf8JsonWriter writer, DecisionsCacheBreakpoint? breakpoint)
    {
        if (breakpoint is null)
        {
            return;
        }

        writer.WriteStartObject("prompt_cache_breakpoint");
        WriteIfPresent(writer, "mode", breakpoint.Mode);
        writer.WriteEndObject();
    }

    private static void WritePart(Utf8JsonWriter writer, DecisionsContentPart part)
    {
        if (part is null)
        {
            throw new ArgumentException("Decisions content parts must not be null.");
        }

        writer.WriteStartObject();
        writer.WriteString("type", part.Type);
        switch (part)
        {
            case DecisionsInputText text:
                writer.WriteString("text", text.Text);
                WriteBreakpoint(writer, text.PromptCacheBreakpoint);
                break;
            case DecisionsInputImage image:
                writer.WriteString("image_url", image.ImageUrl);
                WriteIfPresent(writer, "detail", image.Detail);
                WriteBreakpoint(writer, image.PromptCacheBreakpoint);
                break;
            case DecisionsInputJson json:
                writer.WritePropertyName("data");
                WriteRaw(writer, json.Data, "DecisionsInputJson.Data");
                break;
            case DecisionsOutputText output:
                writer.WriteString("text", output.Text);
                break;
            case DecisionsRefusalContent refusal:
                writer.WriteString("refusal", refusal.Refusal);
                break;
        }

        writer.WriteEndObject();
    }

    private static void WriteContent(Utf8JsonWriter writer, string name, DecisionsContent? content, string what)
    {
        if (content is null)
        {
            throw new ArgumentException($"{what} is required.");
        }

        writer.WritePropertyName(name);
        if (content.Text is not null)
        {
            writer.WriteStringValue(content.Text);
            return;
        }

        writer.WriteStartArray();
        foreach (var part in content.Parts!)
        {
            WritePart(writer, part);
        }

        writer.WriteEndArray();
    }

    private static void WriteInputItem(Utf8JsonWriter writer, DecisionsInputItem item)
    {
        switch (item)
        {
            case DecisionsRawInputItem raw:
                WriteRaw(writer, raw.Raw, "DecisionsRawInputItem.Raw");
                return;
            case DecisionsMessage message:
                writer.WriteStartObject();
                if (!message.OmitType)
                {
                    writer.WriteString("type", "message");
                }

                writer.WriteString("role", message.Role);
                WriteContent(writer, "content", message.Content, "DecisionsMessage.Content");
                if (string.Equals(message.Role, "assistant", StringComparison.Ordinal))
                {
                    WriteIfPresent(writer, "phase", message.Phase);
                }

                writer.WriteEndObject();
                return;
            case DecisionsFunctionCall call:
                writer.WriteStartObject();
                writer.WriteString("type", "function_call");
                writer.WriteString("call_id", call.CallId);
                writer.WriteString("name", call.Name);
                writer.WriteString("arguments", call.Arguments);
                WriteIfPresent(writer, "id", call.Id);
                WriteIfPresent(writer, "namespace", call.Namespace);
                WriteIfPresent(writer, "status", call.Status);
                writer.WriteEndObject();
                return;
            case DecisionsCustomToolCall call:
                writer.WriteStartObject();
                writer.WriteString("type", "custom_tool_call");
                writer.WriteString("call_id", call.CallId);
                writer.WriteString("name", call.Name);
                writer.WriteString("input", call.Input);
                WriteIfPresent(writer, "id", call.Id);
                WriteIfPresent(writer, "namespace", call.Namespace);
                WriteIfPresent(writer, "status", call.Status);
                writer.WriteEndObject();
                return;
            case DecisionsFunctionCallOutput output:
                WriteCallOutput(writer, "function_call_output", output.CallId, output.Output, output.Id, output.Status);
                return;
            case DecisionsCustomToolCallOutput output:
                WriteCallOutput(writer, "custom_tool_call_output", output.CallId, output.Output, output.Id, output.Status);
                return;
            default:
                throw new ArgumentException("Decisions input items must not be null.");
        }
    }

    private static void WriteCallOutput(Utf8JsonWriter writer, string type, string callId, DecisionsContent? output, string? id, string? status)
    {
        writer.WriteStartObject();
        writer.WriteString("type", type);
        writer.WriteString("call_id", callId);
        WriteContent(writer, "output", output, "Tool call output");
        WriteIfPresent(writer, "id", id);
        WriteIfPresent(writer, "status", status);
        writer.WriteEndObject();
    }

    private static void WriteQuestion(Utf8JsonWriter writer, DecisionsQuestion question)
    {
        if (question is null)
        {
            throw new ArgumentException("Decisions questions must not be null.");
        }

        writer.WriteStartObject();
        writer.WriteString("type", question.Type);
        WriteIfPresent(writer, "name", question.Name);
        WriteContent(writer, "instructions", question.Instructions, "DecisionsQuestion.Instructions");
        switch (question)
        {
            case DecisionsChoiceQuestion choice:
                writer.WriteStartArray("choices");
                foreach (var option in choice.Choices)
                {
                    writer.WriteStartObject();
                    writer.WritePropertyName("value");
                    if (option.Value.IsBoolean)
                    {
                        writer.WriteBooleanValue(option.Value.BooleanValue);
                    }
                    else
                    {
                        writer.WriteStringValue(option.Value.StringValue);
                    }

                    WriteIfPresent(writer, "description", option.Description);
                    writer.WriteEndObject();
                }

                writer.WriteEndArray();
                break;
            case DecisionsScoreQuestion score:
                writer.WriteStartArray("levels");
                foreach (var level in score.Levels)
                {
                    writer.WriteStartObject();
                    writer.WriteString("label", level.Label);
                    WriteIfPresent(writer, "description", level.Description);
                    writer.WriteEndObject();
                }

                writer.WriteEndArray();
                break;
        }

        writer.WriteEndObject();
    }

    private sealed class InvalidResponseException(string message) : Exception(message);

    public static DecisionsResult ParseResult(JsonElement root)
    {
        try
        {
            if (root.ValueKind != JsonValueKind.Object)
            {
                throw new InvalidResponseException("Expected a JSON object.");
            }

            if (!root.TryGetProperty("usage", out var usage) || usage.ValueKind != JsonValueKind.Object)
            {
                throw new InvalidResponseException("Expected object 'usage'.");
            }

            JsonElement? inputDetails = usage.TryGetProperty("input_tokens_details", out var id) && id.ValueKind == JsonValueKind.Object ? id : null;
            JsonElement? outputDetails = usage.TryGetProperty("output_tokens_details", out var od) && od.ValueKind == JsonValueKind.Object ? od : null;
            var answers = ObjectArray(root, "answers").Select(ParseAnswer).ToArray();
            var model = Str(root, "model");
            var inputTokens = OptionalCount(usage, "input_tokens");
            var cachedTokens = OptionalCount(inputDetails, "cached_tokens");
            var cacheWriteTokens = OptionalCount(inputDetails, "cache_write_tokens");
            var outputTokens = OptionalCount(usage, "output_tokens");
            var reasoningTokens = OptionalCount(outputDetails, "reasoning_tokens");
            var totalTokens = OptionalCount(usage, "total_tokens");
            return new DecisionsResult(
                answers,
                model,
                new DecisionsUsage(
                    inputTokens,
                    new DecisionsInputTokensDetails(cachedTokens, cacheWriteTokens),
                    outputTokens,
                    new DecisionsOutputTokensDetails(reasoningTokens),
                    totalTokens));
        }
        catch (InvalidResponseException ex)
        {
            throw new DecisionsException(
                DecisionsErrorKind.InvalidResponse,
                $"The Decisions API returned an unexpected response: {ex.Message}");
        }
    }

    private static double Num(JsonElement obj, string key)
    {
        if (!obj.TryGetProperty(key, out var value) || value.ValueKind != JsonValueKind.Number
            || !value.TryGetDouble(out var number) || double.IsNaN(number) || double.IsInfinity(number))
        {
            throw new InvalidResponseException($"Expected number '{key}'.");
        }

        return number;
    }

    private static long OptionalCount(JsonElement? obj, string key)
    {
        if (obj is not { } o || !o.TryGetProperty(key, out var value) || value.ValueKind == JsonValueKind.Null)
        {
            return 0;
        }

        var number = Num(o, key);
        if (Math.Floor(number) != number || number < long.MinValue || number >= 9.2233720368547758E18)
        {
            throw new InvalidResponseException($"Expected integer '{key}'.");
        }

        return (long)number;
    }

    private static string Str(JsonElement obj, string key)
    {
        if (!obj.TryGetProperty(key, out var value) || value.ValueKind != JsonValueKind.String)
        {
            throw new InvalidResponseException($"Expected string '{key}'.");
        }

        return value.GetString()!;
    }

    private static JsonElement[] ObjectArray(JsonElement obj, string key)
    {
        if (!obj.TryGetProperty(key, out var value) || value.ValueKind != JsonValueKind.Array
            || value.EnumerateArray().Any(item => item.ValueKind != JsonValueKind.Object))
        {
            throw new InvalidResponseException($"Expected array of objects '{key}'.");
        }

        return value.EnumerateArray().ToArray();
    }

    private static DecisionsChoiceValue ChoiceValue(JsonElement obj, string key)
    {
        if (obj.TryGetProperty(key, out var value))
        {
            switch (value.ValueKind)
            {
                case JsonValueKind.String:
                    return new DecisionsChoiceValue(value.GetString()!);
                case JsonValueKind.True:
                    return new DecisionsChoiceValue(true);
                case JsonValueKind.False:
                    return new DecisionsChoiceValue(false);
            }
        }

        throw new InvalidResponseException($"Expected string or boolean '{key}'.");
    }

    private static string? AnswerName(JsonElement obj)
    {
        if (!obj.TryGetProperty("name", out var value) || value.ValueKind == JsonValueKind.Null)
        {
            return null;
        }

        if (value.ValueKind != JsonValueKind.String)
        {
            throw new InvalidResponseException("Expected string 'name'.");
        }

        return value.GetString();
    }

    private static DecisionsAnswer ParseAnswer(JsonElement obj)
    {
        var type = Str(obj, "type");
        var name = AnswerName(obj);
        switch (type)
        {
            case "predicate":
                return new DecisionsPredicateAnswer(name, Num(obj, "probability"));
            case "choice":
                {
                    var choice = ChoiceValue(obj, "choice");
                    var confidence = Num(obj, "confidence");
                    var probabilities = ObjectArray(obj, "probabilities")
                        .Select(p =>
                        {
                            var value = ChoiceValue(p, "value");
                            return new DecisionsChoiceProbability(value, Num(p, "probability"));
                        })
                        .ToArray();
                    return new DecisionsChoiceAnswer(name, choice, confidence, probabilities);
                }

            case "score":
                {
                    var score = Num(obj, "score");
                    var confidence = Num(obj, "confidence");
                    var probabilities = ObjectArray(obj, "probabilities")
                        .Select(p =>
                        {
                            var value = Num(p, "value");
                            if (Math.Floor(value) != value || value < int.MinValue || value > int.MaxValue)
                            {
                                throw new InvalidResponseException("Expected integer level 'value'.");
                            }

                            var label = Str(p, "label");
                            return new DecisionsScoreProbability(label, (int)value, Num(p, "probability"));
                        })
                        .ToArray();
                    return new DecisionsScoreAnswer(name, score, confidence, probabilities);
                }

            case "refusal":
                return new DecisionsRefusalAnswer(name);
            default:
                return new DecisionsUnknownAnswer(name, type, obj.Clone());
        }
    }

    /// <summary>Approximates JavaScript <c>String(number)</c> for a JSON number.</summary>
    internal static string? FormatJsNumber(JsonElement value)
    {
        if (!value.TryGetDouble(out var number) || double.IsNaN(number))
        {
            return null;
        }

        if (double.IsInfinity(number))
        {
            return number > 0 ? "Infinity" : "-Infinity";
        }

        if (number == 0)
        {
            return "0";
        }

        var abs = Math.Abs(number);
        if (Math.Floor(number) == number && abs < 1e21)
        {
            return number.ToString("F0", CultureInfo.InvariantCulture);
        }

        var text = number.ToString("R", CultureInfo.InvariantCulture);
        var e = text.IndexOf('E');
        if (e < 0)
        {
            return text;
        }

        var mantissa = text.Substring(0, e);
#if NET
        var exponent = int.Parse(text.AsSpan(e + 1), NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture);
#else
        var exponent = int.Parse(text.Substring(e + 1), NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture);
#endif
        if (exponent is >= -7 and < 21)
        {
            return number.ToString("0.#############################", CultureInfo.InvariantCulture);
        }

        return mantissa + "e" + (exponent >= 0 ? "+" : "-") + Math.Abs(exponent).ToString(CultureInfo.InvariantCulture);
    }
}
