/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
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
        using var lifetime = new DecisionsCallLifetime(options.Timeout, cancellationToken);
        try
        {
            return await CreateCoreAsync(request, options, lifetime).ConfigureAwait(false);
        }
        catch
        {
            lifetime.Check();
            throw;
        }
    }

    private async Task<DecisionsResult> CreateCoreAsync(DecisionsRequest request, DecisionsOptions options, DecisionsCallLifetime lifetime)
    {
        var handler = options.Handler;
        var cancellationToken = lifetime.Token;
        lifetime.Check();

        var model = request.Model;
        var endpoint = await ResolveAsync(options, cancellationToken).ConfigureAwait(false);
        lifetime.Check();
        var plan = DecisionsPlanner.Plan(endpoint, model);
        var body = DecisionsWire.SerializeRequest(request);
        lifetime.Check();

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

                    lifetime.Check();
                    var refreshedEndpoint = await ResolveAsync(options, cancellationToken).ConfigureAwait(false);
                    lifetime.Check();
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

                    plan = new DecisionsAttemptPlan(refreshed.Url, refreshed.Headers, refreshed.Fingerprint,
                        plan.Secrets.Concat(refreshed.Secrets).Distinct(StringComparer.Ordinal).ToArray());
                    lifetime.Check();
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
                    var result = DecisionsWire.ParseResult(document.RootElement);
                    lifetime.Check();
                    return result;
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
            var pending = session.Rpc.Provider.GetEndpointAsync(parameters, cancellationToken);
            // An in-flight RPC write cannot always be interrupted. Only observe late completion;
            // the expired call must never continue to HTTP dispatch.
            _ = pending.ContinueWith(static completed => { _ = completed.Exception; }, CancellationToken.None,
                TaskContinuationOptions.OnlyOnFaulted | TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
            endpoint = await pending.WaitAsync(Timeout.InfiniteTimeSpan, cancellationToken).ConfigureAwait(false);
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
        return new DecisionsException(DecisionsErrorKind.EndpointUnavailable, message);
    }

    /// <summary>One monotonic deadline and cancellation scope for the entire call.</summary>
    internal sealed class DecisionsCallLifetime : IDisposable
    {
        internal static readonly TimeSpan DefaultTimeout = TimeSpan.FromSeconds(600);

        private readonly object _gate = new();
        private readonly TimeSpan _timeout;
        private readonly CancellationToken _caller;
        private readonly CancellationTokenSource _deadline = new();
        private readonly CancellationTokenSource _linked;
        private readonly Stopwatch _clock = Stopwatch.StartNew();
        private readonly Timer? _timer;
        private bool _disposed;

        public DecisionsCallLifetime(TimeSpan? timeout, CancellationToken caller)
        {
            _timeout = timeout ?? DefaultTimeout;
            if (_timeout != Timeout.InfiniteTimeSpan && _timeout <= TimeSpan.Zero)
            {
                _deadline.Dispose();
                throw new ArgumentOutOfRangeException(nameof(timeout), "The Decisions timeout must be positive or infinite.");
            }

            _caller = caller;
            _linked = CancellationTokenSource.CreateLinkedTokenSource(caller, _deadline.Token);
            if (_timeout != Timeout.InfiniteTimeSpan)
            {
                _timer = new Timer(OnTimer, null, Timeout.Infinite, Timeout.Infinite);
                OnTimer(null);
            }
        }

        public CancellationToken Token => _linked.Token;

        public void Check()
        {
            _caller.ThrowIfCancellationRequested();
            if (_timeout != Timeout.InfiniteTimeSpan && _clock.Elapsed >= _timeout)
            {
                CancelDeadline();
                _caller.ThrowIfCancellationRequested();
                throw new DecisionsException(DecisionsErrorKind.Timeout, "The Decisions call exceeded its total-call deadline.");
            }
        }

        private void OnTimer(object? state)
        {
            lock (_gate)
            {
                if (_disposed)
                {
                    return;
                }

                var remaining = _timeout - _clock.Elapsed;
                if (remaining > TimeSpan.Zero)
                {
                    // Re-arm long deadlines without imposing the timer's platform-specific maximum.
                    _timer!.Change((int)Math.Min(int.MaxValue, Math.Ceiling(remaining.TotalMilliseconds)), Timeout.Infinite);
                }
                else
                {
                    CancelDeadline();
                }
            }
        }

        private void CancelDeadline()
        {
            try
            {
                _deadline.Cancel();
            }
            catch (AggregateException)
            {
                // A custom cancellation callback cannot replace the safe timeout diagnostic.
            }
        }

        public void Dispose()
        {
            lock (_gate)
            {
                _disposed = true;
                _timer?.Dispose();
            }

            _linked.Dispose();
            _deadline.Dispose();
        }
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
    private static readonly char[] s_authorityTerminators = ['/', '?', '#'];

    public static DecisionsAttemptPlan Plan(ProviderEndpoint endpoint, string model)
    {
        var type = endpoint.Type.Value;
        if (!string.Equals(type, "openai", StringComparison.Ordinal) && !string.Equals(type, "azure", StringComparison.Ordinal))
        {
            throw new DecisionsException(
                DecisionsErrorKind.UnsupportedProvider,
                "The session's provider type does not support the Decisions API.");
        }

        if (endpoint.Transport is { } transport && !string.Equals(transport.Value, "http", StringComparison.Ordinal))
        {
            throw new DecisionsException(
                DecisionsErrorKind.UnsupportedTransport,
                "The session's provider transport is not supported by the Decisions API.");
        }

        var tokenModel = endpoint.SessionToken?.Model;
        if (tokenModel is not null && !string.Equals(tokenModel, model, StringComparison.Ordinal))
        {
            throw new DecisionsException(
                DecisionsErrorKind.ModelMismatch,
                "The provider session token is bound to a different model. Set endpointModelId or request.model consistently.");
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
        return new DecisionsAttemptPlan(url, headers, fingerprint, CollectSecrets(endpoint, url, headers));
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
        if (baseUrl is null
            || !(baseUrl.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
                || baseUrl.StartsWith("https://", StringComparison.OrdinalIgnoreCase)))
        {
            throw InvalidEndpoint("The provider endpoint base URL must have an explicit http or https authority.");
        }

        for (var i = 0; i < baseUrl.Length; i++)
        {
            var c = baseUrl[i];
            if (c is <= ' ' or >= '\x7F' or '\\'
                || (c == '%' && (i + 2 >= baseUrl.Length || !Uri.IsHexDigit(baseUrl[i + 1]) || !Uri.IsHexDigit(baseUrl[i + 2]))))
            {
                throw InvalidEndpoint("The provider endpoint base URL contains invalid URI characters.");
            }
        }

        var start = baseUrl.IndexOf("://", StringComparison.Ordinal) + 3;
        var end = baseUrl.IndexOfAny(s_authorityTerminators, start);
        var authority = end < 0 ? baseUrl.Substring(start) : baseUrl.Substring(start, end - start);
        ValidateAuthority(authority);
        if (!Uri.TryCreate(baseUrl, UriKind.Absolute, out var uri) || string.IsNullOrEmpty(uri.Host))
        {
            throw InvalidEndpoint("The provider endpoint base URL is not a valid absolute URL.");
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

    private static void ValidateAuthority(string authority)
    {
        if (authority.Length == 0 || authority.AsSpan().IndexOf('@') >= 0)
        {
            throw InvalidEndpoint("The provider endpoint base URL must have a host and no user information.");
        }

        string? port = null;
        if (authority[0] == '[')
        {
            var close = authority.IndexOf(']');
            if (close < 0 || authority.AsSpan().IndexOf('%') >= 0
#if NET
                || !IPAddress.TryParse(authority.AsSpan(1, close - 1), out var address)
#else
                || !IPAddress.TryParse(authority.Substring(1, close - 1), out var address)
#endif
                || address.AddressFamily != AddressFamily.InterNetworkV6)
            {
                throw InvalidEndpoint("The provider endpoint base URL has an invalid host.");
            }

            if (close + 1 < authority.Length)
            {
                if (authority[close + 1] != ':')
                {
                    throw InvalidEndpoint("The provider endpoint base URL has an invalid authority.");
                }

                port = authority.Substring(close + 2);
            }
        }
        else
        {
            var colon = authority.IndexOf(':');
            var host = colon < 0 ? authority : authority.Substring(0, colon);
            if (colon >= 0)
            {
                port = authority.Substring(colon + 1);
            }

            var labels = (host.Length > 0 && host[host.Length - 1] == '.' ? host.Substring(0, host.Length - 1) : host).Split('.');
            if (host.Length > 254 || labels.Any(label => label.Length is 0 or > 63
                || label[0] == '-' || label[label.Length - 1] == '-'
                || label.Any(c => c is not ((>= 'a' and <= 'z') or (>= 'A' and <= 'Z') or (>= '0' and <= '9') or '-'))))
            {
                throw InvalidEndpoint("The provider endpoint base URL has an invalid host.");
            }

            if (host.All(c => c is (>= '0' and <= '9') or '.')
                && (labels.Length != 4 || labels.Any(label => !byte.TryParse(label, NumberStyles.None, CultureInfo.InvariantCulture, out _))))
            {
                throw InvalidEndpoint("The provider endpoint base URL has an invalid IPv4 host.");
            }
        }

        if (port is not null && (port.Length == 0 || port.Any(c => c is < '0' or > '9')
            || !ushort.TryParse(port, NumberStyles.None, CultureInfo.InvariantCulture, out _)))
        {
            throw InvalidEndpoint("The provider endpoint base URL has an invalid port.");
        }
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

        if (!valid || value.Any(c => (c < ' ' && c != '\t') || c == '\x7F') || DecisionsText.HasLoneSurrogate(value))
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
        var apiKey = endpoint.ApiKey is null ? null : CheckHeader("api-key", endpoint.ApiKey);
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
        if (!string.IsNullOrEmpty(apiKey) && Lookup(merged, "authorization") is null && Lookup(merged, "api-key") is null)
        {
            if (string.Equals(endpoint.Type.Value, "azure", StringComparison.Ordinal))
            {
                apiKeyHeader = "api-key";
                Set(merged, apiKeyHeader, apiKey!);
            }
            else
            {
                apiKeyHeader = "authorization";
                Set(merged, apiKeyHeader, "Bearer " + apiKey);
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

    internal static IReadOnlyList<string> CollectSecrets(ProviderEndpoint endpoint, string url, IReadOnlyList<KeyValuePair<string, string>> headers)
    {
        var raw = new List<string>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        void Add(string? value, bool credential = false)
        {
            if (value is null)
            {
                return;
            }

            var trimmed = TrimHttpWhitespace(value);
            if (trimmed.Length > 0 && (credential || DecisionsText.ScalarLength(trimmed) >= MinSecretLength) && seen.Add(trimmed))
            {
                raw.Add(trimmed);
            }
        }

        Add(endpoint.ApiKey, credential: true);
        Add(endpoint.SessionToken?.Token, credential: true);
        bool IsCredentialHeader(string name) =>
            string.Equals(name, "authorization", StringComparison.OrdinalIgnoreCase)
                || string.Equals(name, "api-key", StringComparison.OrdinalIgnoreCase)
                || string.Equals(name, endpoint.SessionToken?.Header, StringComparison.OrdinalIgnoreCase);

        foreach (var header in endpoint.Headers.Concat(headers.Where(header => IsCredentialHeader(header.Key))))
        {
            var credential = IsCredentialHeader(header.Key);
            Add(header.Value, credential);
            if (header.Value is not null)
            {
                Add(StripBearer(header.Value), credential);
            }
        }

        foreach (var (rawValue, decodedValue) in DecisionsText.QueryValues(endpoint.BaseUrl).Concat(DecisionsText.QueryValues(url)))
        {
            if (DecisionsText.ScalarLength(TrimHttpWhitespace(decodedValue)) >= MinSecretLength)
            {
                Add(decodedValue);
                Add(rawValue);
            }
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

    /// <summary>Raw query values and their WHATWG <c>URLSearchParams</c>-style decoded forms.</summary>
    public static IEnumerable<(string Raw, string Decoded)> QueryValues(string url)
    {
        var queryStart = url.IndexOf('?');
        var fragmentStart = url.IndexOf('#');
        if (queryStart < 0 || (fragmentStart >= 0 && fragmentStart < queryStart))
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
            var raw = equals < 0 ? string.Empty : part.Substring(equals + 1);
            yield return (raw, PercentDecode(raw));
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

    internal static bool HasLoneSurrogate(string value)
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

    /// <summary>Redacts the union of overlapping matches against the original text, including self-overlaps.</summary>
    public static string Redact(string text, IReadOnlyList<string> secrets, int? retainedLength = null, bool includeBoundary = false)
    {
        var limit = retainedLength ?? text.Length;
        var matchLimit = limit + (includeBoundary ? 1 : 0);
        var intervals = new List<(int Start, int End)>();
        foreach (var secret in secrets)
        {
            if (secret.Length == 0)
            {
                continue;
            }

            foreach (Match match in Regex.Matches(text, "(?=" + VariantPattern(secret) + ")", RegexOptions.CultureInvariant))
            {
                if (match.Index >= matchLimit)
                {
                    break;
                }

                intervals.Add((match.Index, match.Index + secret.Length));
            }
        }

        intervals.Sort((a, b) => a.Start != b.Start ? a.Start.CompareTo(b.Start) : a.End.CompareTo(b.End));
        var result = new StringBuilder();
        var cursor = 0;
        for (var i = 0; i < intervals.Count; i++)
        {
            var (start, end) = intervals[i];
            while (i + 1 < intervals.Count && intervals[i + 1].Start < end)
            {
                end = Math.Max(end, intervals[++i].End);
            }

            result.Append(text, cursor, start - cursor);
            result.Append("[REDACTED]");
            cursor = end;
        }

        if (cursor < limit)
        {
            result.Append(text, cursor, limit - cursor);
        }

        return result.ToString();
    }

    public static string CapDiagnostic(string text)
    {
        var bytes = EncodeUtf8(text);
        return bytes.Length <= SessionDecisionsApi.MaxErrorBodyBytes
            ? text
            : DecodeUtf8(bytes, Utf8BoundaryCut(bytes, SessionDecisionsApi.MaxErrorBodyBytes));
    }

    public static string RedactPrefix(byte[] bytes, IReadOnlyList<string> secrets)
    {
        var cut = Utf8BoundaryCut(bytes, SessionDecisionsApi.MaxErrorBodyBytes);
        var retained = DecodeUtf8(bytes, cut).Length;
        var splitCharacter = cut < Math.Min(bytes.Length, SessionDecisionsApi.MaxErrorBodyBytes);
        return CapDiagnostic(Redact(DecodeUtf8(bytes, bytes.Length), secrets, retained, splitCharacter));
    }

    public static string RedactDiagnostic(string text, IReadOnlyList<string> secrets)
    {
        var bytes = EncodeUtf8(text);
        var window = (int)Math.Min(bytes.Length, (long)SessionDecisionsApi.MaxErrorBodyBytes + MaxSecretBytes(secrets));
        var cut = Utf8BoundaryCut(bytes, window);
        if (cut != bytes.Length)
        {
            Array.Resize(ref bytes, cut);
        }

        return RedactPrefix(bytes, secrets);
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
        cancellationToken.ThrowIfCancellationRequested();
        var requestUri = new Uri(plan.Url, UriKind.Absolute);
        using var request = new HttpRequestMessage(HttpMethod.Post, requestUri);
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

        if (cancellationToken.IsCancellationRequested)
        {
            response.Dispose();
            cancellationToken.ThrowIfCancellationRequested();
        }

        var finalUri = response.RequestMessage?.RequestUri;
        if (finalUri is not null && !string.Equals(finalUri.AbsoluteUri, requestUri.AbsoluteUri, StringComparison.Ordinal))
        {
            response.Dispose();
            throw new DecisionsException(
                DecisionsErrorKind.InvalidResponse,
                "The custom HTTP handler followed a redirect. The Decisions API does not follow redirects.");
        }

        return response;
    }

    /// <summary>Reads at most <paramref name="limit"/> bytes; <c>exceeded</c> reports whether more data remained.</summary>
    public static async Task<(byte[] Bytes, bool Exceeded)> ReadLimitedAsync(HttpResponseMessage response, int limit, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        using var registration = cancellationToken.Register(static state => ((HttpResponseMessage)state!).Dispose(), response);
        if (response.Content is null)
        {
            return ([], false);
        }

        using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using var buffer = new MemoryStream();
        var chunk = new byte[16 * 1024];
        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var remaining = limit + 1 - (int)buffer.Length;
            var read = await stream.ReadAsync(chunk.AsMemory(0, Math.Min(chunk.Length, remaining)), cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
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

    public static async Task<DecisionsException> CreateHttpErrorAsync(
        HttpResponseMessage response, IReadOnlyList<string> secrets, CancellationToken cancellationToken, DateTimeOffset? now = null)
    {
        var window = (int)Math.Min(int.MaxValue - 1, (long)SessionDecisionsApi.MaxErrorBodyBytes + DecisionsText.MaxSecretBytes(secrets));
        var (bytes, exceeded) = await ReadLimitedAsync(response, window, cancellationToken).ConfigureAwait(false);
        var raw = DecisionsText.DecodeUtf8(bytes, bytes.Length);
        var body = DecisionsText.RedactPrefix(bytes, secrets);
        var truncated = exceeded || bytes.Length > SessionDecisionsApi.MaxErrorBodyBytes;

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
        var requestId = Header(response, "x-request-id");
        return new DecisionsException(DecisionsErrorKind.Http, DecisionsText.CapDiagnostic(message))
        {
            Status = status,
            ErrorType = errorType,
            ErrorCode = errorCode,
            ErrorParam = errorParam,
            RequestId = requestId is null ? null : DecisionsText.RedactDiagnostic(requestId, secrets),
            RetryAfterMs = ParseRetryAfterMs(Header(response, "retry-after-ms"), Header(response, "retry-after"), now ?? DateTimeOffset.UtcNow),
            Body = body,
            BodyTruncated = truncated,
        };
    }

    private static string? Header(HttpResponseMessage response, string name) =>
        response.Headers.TryGetValues(name, out var values) ? string.Join(", ", values) : null;

    internal static long? ParseRetryAfterMs(string? milliseconds, string? retryAfter, DateTimeOffset now)
    {
        if (ParseDelay(milliseconds, 1) is { } preferred)
        {
            return preferred;
        }

        if (ParseDelay(retryAfter, 1000) is { } seconds)
        {
            return seconds;
        }

        if (retryAfter is null)
        {
            return null;
        }

        var text = DecisionsPlanner.TrimHttpWhitespace(retryAfter);
        const string Format = "ddd, dd MMM yyyy HH:mm:ss 'GMT'";
        if (!DateTimeOffset.TryParseExact(text, Format, CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var date)
            || !string.Equals(date.ToString(Format, CultureInfo.InvariantCulture), text, StringComparison.Ordinal))
        {
            return null;
        }

        var ticks = date.UtcDateTime.Ticks - now.UtcDateTime.Ticks;
        return ticks <= 0 ? 0 : ((ticks - 1) / TimeSpan.TicksPerMillisecond) + 1;
    }

    private static long? ParseDelay(string? value, int multiplier)
    {
        if (value is null)
        {
            return null;
        }

        var text = DecisionsPlanner.TrimHttpWhitespace(value);
        var dot = text.IndexOf('.');
        if (text.Length == 0 || dot == 0 || dot == text.Length - 1
            || text.Where((c, i) => i != dot).Any(c => c is < '0' or > '9'))
        {
            return null;
        }

        var whole = dot < 0 ? text : text.Substring(0, dot);
        if (!long.TryParse(whole, NumberStyles.None, CultureInfo.InvariantCulture, out var integer))
        {
            return null;
        }

        var fraction = dot < 0 ? string.Empty : text.Substring(dot + 1);
        long extra = 0;
        var consumed = multiplier == 1000 ? Math.Min(3, fraction.Length) : 0;
        if (multiplier == 1000)
        {
            for (var i = 0; i < 3; i++)
            {
                extra = (extra * 10) + (i < fraction.Length ? fraction[i] - '0' : 0);
            }
        }

        if (fraction.Skip(consumed).Any(c => c != '0'))
        {
            extra++;
        }

        const long Maximum = 9007199254740991;
        return integer <= (Maximum - extra) / multiplier ? (integer * multiplier) + extra : null;
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
        return text is null ? null : DecisionsText.RedactDiagnostic(text, secrets);
    }

    public static DecisionsException NetworkError(string message, Exception error, IReadOnlyList<string> secrets) =>
        new(DecisionsErrorKind.Network, message, Sanitize(error, secrets));

    /// <summary>
    /// Retains only bounded, redacted type-name, message, and code strings, not the original exception graph.
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
            DecisionsText.RedactDiagnostic(error.GetType().FullName ?? error.GetType().Name, secrets),
            DecisionsText.RedactDiagnostic(error.Message, secrets),
            code is null ? null : DecisionsText.RedactDiagnostic(code, secrets));
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

    private enum ContentContext
    {
        User,
        Instruction,
        Assistant,
        ToolOutput,
        Question,
    }

    private static void WritePart(Utf8JsonWriter writer, DecisionsContentPart part, ContentContext context)
    {
        if (part is null)
        {
            throw new ArgumentException("Decisions content parts must not be null.");
        }

        var allowed = context switch
        {
            ContentContext.User or ContentContext.Question => part is DecisionsInputText or DecisionsInputImage or DecisionsInputJson,
            ContentContext.Instruction => part is DecisionsInputText,
            ContentContext.Assistant => part is DecisionsOutputText or DecisionsRefusalContent,
            _ => part is DecisionsInputText or DecisionsInputImage,
        };
        if (!allowed)
        {
            throw new ArgumentException("The Decisions content part is not supported in this message role or question.");
        }

        if (part is DecisionsInputText { Text: null } or DecisionsInputImage { ImageUrl: null }
            or DecisionsOutputText { Text: null } or DecisionsRefusalContent { Refusal: null })
        {
            throw new ArgumentException("Decisions content part text and image URLs must not be null.");
        }

        writer.WriteStartObject();
        writer.WriteString("type", part.Type);
        switch (part)
        {
            case DecisionsInputText text:
                writer.WriteString("text", text.Text);
                if (context != ContentContext.Question)
                {
                    WriteBreakpoint(writer, text.PromptCacheBreakpoint);
                }

                break;
            case DecisionsInputImage image:
                writer.WriteString("image_url", image.ImageUrl);
                WriteIfPresent(writer, "detail", image.Detail);
                if (context != ContentContext.Question)
                {
                    WriteBreakpoint(writer, image.PromptCacheBreakpoint);
                }

                break;
            case DecisionsInputJson json:
                if (json.Data.ValueKind != JsonValueKind.Object
                    && (json.Data.ValueKind != JsonValueKind.Array || json.Data.EnumerateArray().Any(item => item.ValueKind != JsonValueKind.Object)))
                {
                    throw new ArgumentException("DecisionsInputJson.Data must be an object or an array of objects.");
                }

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

    private static void WriteContent(Utf8JsonWriter writer, string name, DecisionsContent? content, string what, ContentContext context)
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
            WritePart(writer, part, context);
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
                var context = message.Role switch
                {
                    "user" => ContentContext.User,
                    "developer" or "system" => ContentContext.Instruction,
                    "assistant" => ContentContext.Assistant,
                    _ => throw new ArgumentException("DecisionsMessage.Role must be user, developer, system, or assistant."),
                };
                writer.WriteStartObject();
                if (!message.OmitType)
                {
                    writer.WriteString("type", "message");
                }

                writer.WriteString("role", message.Role);
                WriteContent(writer, "content", message.Content, "DecisionsMessage.Content", context);
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
        WriteContent(writer, "output", output, "Tool call output", ContentContext.ToolOutput);
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
        WriteContent(writer, "instructions", question.Instructions, "DecisionsQuestion.Instructions", ContentContext.Question);
        switch (question)
        {
            case DecisionsChoiceQuestion choice:
                writer.WriteStartArray("choices");
                foreach (var option in choice.Choices)
                {
                    if (option is null)
                    {
                        throw new ArgumentException("Decisions choice options must not be null.");
                    }

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
                    if (level is null)
                    {
                        throw new ArgumentException("Decisions score levels must not be null.");
                    }

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
        if (obj is not { } o || !o.TryGetProperty(key, out _))
        {
            return 0;
        }

        return Integer(o, key);
    }

    private static long Integer(JsonElement obj, string key)
    {
        if (obj.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.Number)
        {
            if (value.TryGetInt64(out var integer))
            {
                return integer;
            }

            var raw = value.GetRawText();
            var exponentAt = raw.IndexOfAny(['e', 'E']);
            if (raw.AsSpan().IndexOf('.') >= 0 || exponentAt >= 0)
            {
                var negative = raw[0] == '-';
                var mantissaEnd = exponentAt < 0 ? raw.Length : exponentAt;
                var mantissa = raw.Substring(negative ? 1 : 0, mantissaEnd - (negative ? 1 : 0));
                var point = mantissa.IndexOf('.');
                var digits = mantissa.Replace(".", string.Empty);
                var first = 0;
                while (first < digits.Length && digits[first] == '0')
                {
                    first++;
                }

                if (first == digits.Length)
                {
                    return 0;
                }

                var exponent = 0;
                if (exponentAt >= 0 &&
#if NET
                    !int.TryParse(raw.AsSpan(exponentAt + 1), NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out exponent))
#else
                    !int.TryParse(raw.Substring(exponentAt + 1), NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out exponent))
#endif
                {
                    throw new InvalidResponseException($"Expected integer '{key}'.");
                }

                var last = digits.Length - 1;
                while (digits[last] == '0')
                {
                    last--;
                }

                var decimalPoint = (long)(point < 0 ? digits.Length : point) + exponent;
                if (last < decimalPoint && decimalPoint - first is > 0 and <= 16)
                {
                    var significant = new StringBuilder(16).Append(digits, first, last - first + 1)
                        .Append('0', (int)(decimalPoint - last - 1)).ToString();
                    if (long.TryParse(significant, NumberStyles.None, CultureInfo.InvariantCulture, out integer)
                        && integer <= 9007199254740991)
                    {
                        return negative ? -integer : integer;
                    }
                }
            }
        }

        throw new InvalidResponseException($"Expected signed 64-bit integer '{key}'.");
    }

    private static double Probability(JsonElement obj, string key)
    {
        var number = Num(obj, key);
        if (number is < 0 or > 1)
        {
            throw new InvalidResponseException($"Expected probability '{key}' between zero and one.");
        }

        return number;
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
                return new DecisionsPredicateAnswer(name, Probability(obj, "probability"));
            case "choice":
                {
                    var choice = ChoiceValue(obj, "choice");
                    var confidence = Probability(obj, "confidence");
                    var probabilities = ObjectArray(obj, "probabilities")
                        .Select(p =>
                        {
                            var value = ChoiceValue(p, "value");
                            return new DecisionsChoiceProbability(value, Probability(p, "probability"));
                        })
                        .ToArray();
                    return new DecisionsChoiceAnswer(name, choice, confidence, probabilities);
                }

            case "score":
                {
                    var score = Num(obj, "score");
                    var confidence = Probability(obj, "confidence");
                    var probabilities = ObjectArray(obj, "probabilities")
                        .Select(p =>
                        {
                            var value = Integer(p, "value");
                            var label = Str(p, "label");
                            return new DecisionsScoreProbability(label, value, Probability(p, "probability"));
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
