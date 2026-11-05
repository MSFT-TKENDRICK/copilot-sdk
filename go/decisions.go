// Experimental Decisions API: session.Decisions().Create(...).
//
// This mirrors nodejs/src/decisions.ts. The request is sent directly to the provider endpoint
// that the runtime resolves for the session (session.provider.getEndpoint).

package copilot

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/github/copilot-sdk/go/rpc"
)

const (
	decisionsMaxErrorBodyBytes = 64 * 1024
	decisionsMaxResponseBytes  = 8 * 1024 * 1024
	decisionsMinSecretRunes    = 8
	decisionsDefaultTimeout    = 600 * time.Second
	decisionsMaxSafeInteger    = int64(9007199254740991)
	decisionsRedacted          = "[REDACTED]"
	decisionsGateEnv           = "COPILOT_ALLOW_GET_PROVIDER_ENDPOINT"
	decisionsGateHint          = "session.decisions requires the runtime's provider endpoint API. Set COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true in the runtime's environment (for in-process runtimes, in the host process environment before the runtime is loaded)."
)

// Connection pooling follows net/http defaults; active I/O is bounded only by the call
// context, rather than an unrelated dial, TLS-handshake, or response-header timeout.
var decisionsDefaultHTTPTransport = &http.Transport{
	Proxy:                 http.ProxyFromEnvironment,
	DialContext:           (&net.Dialer{KeepAlive: 30 * time.Second}).DialContext,
	ForceAttemptHTTP2:     true,
	MaxIdleConns:          100,
	IdleConnTimeout:       90 * time.Second,
	ExpectContinueTimeout: time.Second,
}

// DecisionsErrorKind classifies a [DecisionsError].
//
// Experimental: DecisionsErrorKind is part of the experimental Decisions API and may change
// or be removed.
type DecisionsErrorKind string

const (
	// DecisionsErrorKindEndpointUnavailable means the provider endpoint could not be resolved.
	DecisionsErrorKindEndpointUnavailable DecisionsErrorKind = "endpointUnavailable"
	// DecisionsErrorKindInvalidEndpoint means the resolved endpoint is malformed.
	DecisionsErrorKindInvalidEndpoint DecisionsErrorKind = "invalidEndpoint"
	// DecisionsErrorKindUnsupportedProvider means the provider type does not support Decisions.
	DecisionsErrorKindUnsupportedProvider DecisionsErrorKind = "unsupportedProvider"
	// DecisionsErrorKindUnsupportedTransport means the provider transport is not HTTP.
	DecisionsErrorKindUnsupportedTransport DecisionsErrorKind = "unsupportedTransport"
	// DecisionsErrorKindModelMismatch means the session token is bound to another model.
	DecisionsErrorKindModelMismatch DecisionsErrorKind = "modelMismatch"
	// DecisionsErrorKindCredentialConflict means two credentials target the same header.
	DecisionsErrorKindCredentialConflict DecisionsErrorKind = "credentialConflict"
	// DecisionsErrorKindHTTP means the API returned a non-success HTTP status.
	DecisionsErrorKindHTTP DecisionsErrorKind = "http"
	// DecisionsErrorKindInvalidResponse means the success response could not be parsed.
	DecisionsErrorKindInvalidResponse DecisionsErrorKind = "invalidResponse"
	// DecisionsErrorKindNetwork means the request or response body failed in transit.
	DecisionsErrorKindNetwork DecisionsErrorKind = "network"
	// DecisionsErrorKindTimeout means the SDK-owned total-call deadline expired.
	DecisionsErrorKindTimeout DecisionsErrorKind = "timeout"
)

// DecisionsError reports endpoint, HTTP, network, response, and SDK timeout failures.
// Known endpoint credentials are redacted, including short credentials and supported encoded
// forms. Other header/query values use an eight-Unicode-scalar heuristic. This is not general
// PII removal or protection against arbitrary transformations of credentials.
// Caller context cancellation and deadlines are returned natively, not wrapped.
//
// Experimental: DecisionsError is part of the experimental Decisions API and may change or be
// removed.
type DecisionsError struct {
	Kind    DecisionsErrorKind
	Message string
	// Status is the HTTP status for [DecisionsErrorKindHTTP], otherwise 0.
	Status     int
	ErrorType  *string
	ErrorCode  *string
	ErrorParam *string
	RequestID  *string
	// Body is the redacted error body, at most 64 KiB.
	Body          string
	BodyTruncated bool
	// RetryAfterMS is advisory HTTP retry guidance in milliseconds, not an automatic retry.
	// Nil means no valid hint was supplied.
	RetryAfterMS *int64
	// Cause is a sanitized *DecisionsNetworkCause for network failures or a fixed safe
	// diagnostic for endpointUnavailable. Original exception objects are never retained.
	// SDK timeout errors have no cause.
	Cause error
}

// Error implements the error interface.
func (e *DecisionsError) Error() string { return e.Message }

// Unwrap returns Cause.
func (e *DecisionsError) Unwrap() error { return e.Cause }

// DecisionsNetworkCause is a sanitized network failure: credentials are redacted from
// all fields and the original error is not retained. Each field is bounded to 64 KiB.
//
// Experimental: DecisionsNetworkCause is part of the experimental Decisions API and may
// change or be removed.
type DecisionsNetworkCause struct {
	// Name is the Go type of the underlying error, for example "*net.OpError".
	Name    string
	Message string
	// Code is the symbolic system error code (for example "ECONNRESET") when a
	// recognized one was present; it is empty otherwise.
	Code string
}

// Error implements the error interface.
func (c *DecisionsNetworkCause) Error() string { return c.Message }

// DecisionsOptions configures one [SessionDecisions.Create] call.
//
// Experimental: DecisionsOptions is part of the experimental Decisions API and may change or
// be removed.
type DecisionsOptions struct {
	// EndpointModelID selects which model's provider endpoint to resolve. Empty resolves the
	// session's current model.
	EndpointModelID string
	// Transport sends the HTTP request. Nil uses the SDK's pooled HTTP transport,
	// whose active I/O is bounded by the call context rather than a separate timeout.
	// Custom transports are trusted to enforce TLS/redirect policy and honor the request
	// context. Their response bodies must support Close concurrently with Read.
	Transport http.RoundTripper
	// Timeout is the total-call deadline, including endpoint resolution, HTTP response
	// bodies, and the single conditional authentication replay. Nil defaults to 600 seconds;
	// a pointer to zero disables the SDK deadline. Negative values are invalid.
	// Caller context cancellation/deadlines take precedence over the SDK deadline.
	Timeout *time.Duration
}

// SessionDecisions issues Decisions API requests through a session's provider endpoint.
//
// Experimental: SessionDecisions is part of the experimental Decisions API and may change or
// be removed.
type SessionDecisions struct {
	resolve func(ctx context.Context, params *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error)
	now     func() time.Time
}

type decisionsAttempt struct {
	url         string
	headers     [][2]string
	fingerprint string
	secrets     []string
}

// Create sends a Decisions request and returns the parsed answers. The provider endpoint is
// resolved on every call; after a readable 401 or 403 it is resolved once more and the
// request retried once if the effective URL or credentials changed. No other retry occurs.
// The default total-call deadline is 600 seconds.
//
// Experimental: Create is part of the experimental Decisions API and may change or be removed.
func (d *SessionDecisions) Create(ctx context.Context, req DecisionsRequest, opts *DecisionsOptions) (result *DecisionsResult, err error) {
	if req.Model == "" {
		return nil, errDecisionsModelRequired
	}
	if opts == nil {
		opts = &DecisionsOptions{}
	}
	timeout := decisionsDefaultTimeout
	if opts.Timeout != nil {
		timeout = *opts.Timeout
	}
	if timeout < 0 {
		return nil, errors.New("DecisionsOptions.Timeout must not be negative")
	}
	callerCtx := ctx
	if err := callerCtx.Err(); err != nil {
		return nil, err
	}
	if timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, timeout)
		defer cancel()
	}
	defer func() {
		if callerErr := callerCtx.Err(); callerErr != nil {
			result, err = nil, callerErr
		} else if ctx.Err() != nil {
			result, err = nil, &DecisionsError{Kind: DecisionsErrorKindTimeout, Message: "The Decisions call exceeded its total-call timeout."}
		}
	}()
	var params *rpc.SessionProviderGetEndpointRequest
	if opts.EndpointModelID != "" {
		id := opts.EndpointModelID
		params = &rpc.SessionProviderGetEndpointRequest{ModelID: &id}
	}

	endpoint, err := d.resolveEndpoint(ctx, params)
	if err != nil {
		return nil, err
	}
	attempt, err := planDecisionsAttempt(endpoint, req.Model)
	if err != nil {
		return nil, err
	}
	body, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}

	transport := opts.Transport
	if transport == nil {
		transport = decisionsDefaultHTTPTransport
	}
	client := &http.Client{
		Transport: decisionsTransport{transport},
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	resp, err := sendDecisions(ctx, client, attempt, body)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		original := decisionsHTTPError(ctx, resp, attempt.secrets, d.currentTime)
		var httpErr *DecisionsError
		if !errors.As(original, &httpErr) || httpErr.Kind != DecisionsErrorKindHTTP {
			return nil, original
		}
		refreshed, err := d.resolveEndpoint(ctx, params)
		if err != nil {
			return nil, err
		}
		next, planErr := planDecisionsAttempt(refreshed, req.Model)
		if planErr != nil || next.fingerprint == attempt.fingerprint {
			return nil, original
		}
		next.secrets = append(next.secrets, attempt.secrets...)
		attempt = next
		resp, err = sendDecisions(ctx, client, attempt, body)
		if err != nil {
			return nil, err
		}
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, decisionsHTTPError(ctx, resp, attempt.secrets, d.currentTime)
	}
	return readDecisionsSuccess(ctx, resp, attempt.secrets)
}

func (d *SessionDecisions) currentTime() time.Time {
	if d.now != nil {
		return d.now()
	}
	return time.Now()
}

func (d *SessionDecisions) resolveEndpoint(ctx context.Context, params *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	endpoint, err := d.resolve(ctx, params)
	if ctxErr := ctx.Err(); ctxErr != nil {
		return nil, ctxErr
	}
	if err != nil {
		message := "Could not resolve the session's provider endpoint for the Decisions API."
		if strings.Contains(err.Error(), decisionsGateEnv) {
			message = decisionsGateHint
		}
		return nil, &DecisionsError{
			Kind: DecisionsErrorKindEndpointUnavailable, Message: message,
			Cause: errors.New("Provider endpoint resolution failed."),
		}
	}
	if endpoint == nil {
		return nil, &DecisionsError{Kind: DecisionsErrorKindEndpointUnavailable, Message: "Could not resolve the session's provider endpoint for the Decisions API."}
	}
	return endpoint, nil
}

type decisionsTransport struct{ http.RoundTripper }

func (t decisionsTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	if err := req.Context().Err(); err != nil {
		return nil, err
	}
	resp, err := t.RoundTripper.RoundTrip(req)
	if ctxErr := req.Context().Err(); ctxErr != nil {
		err = ctxErr
	}
	if err != nil && resp != nil {
		if resp.Body != nil {
			_ = resp.Body.Close()
		}
		return nil, err
	}
	return resp, err
}

func sendDecisions(ctx context.Context, client *http.Client, attempt *decisionsAttempt, body []byte) (*http.Response, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, attempt.url, bytes.NewReader(body))
	if err != nil {
		return nil, &DecisionsError{Kind: DecisionsErrorKindInvalidEndpoint, Message: "The provider endpoint base URL is not a valid absolute URL."}
	}
	for _, h := range attempt.headers {
		httpReq.Header.Set(h[0], h[1])
	}
	resp, err := client.Do(httpReq)
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return nil, ctxErr
		}
		return nil, decisionsNetworkError("The Decisions request could not be sent.", err, attempt.secrets)
	}
	return resp, nil
}

func decisionsNetworkError(message string, err error, secrets []string) *DecisionsError {
	return &DecisionsError{Kind: DecisionsErrorKindNetwork, Message: message, Cause: sanitizeDecisionsCause(err, secrets)}
}

func sanitizeDecisionsCause(err error, secrets []string) *DecisionsNetworkCause {
	inner := err
	for {
		var urlErr *url.Error
		if errors.As(inner, &urlErr) && urlErr.Err != nil {
			inner = urlErr.Err
			continue
		}
		break
	}
	cause := &DecisionsNetworkCause{
		Name:    redactDecisionsText(fmt.Sprintf("%T", inner), secrets),
		Message: redactDecisionsText(inner.Error(), secrets),
	}
	cause.Code = redactDecisionsText(decisionsErrnoCode(err), secrets)
	return cause
}

var decisionsErrnoNames = []struct {
	errno syscall.Errno
	name  string
}{
	{syscall.ECONNRESET, "ECONNRESET"},
	{syscall.ECONNREFUSED, "ECONNREFUSED"},
	{syscall.ETIMEDOUT, "ETIMEDOUT"},
	{syscall.EPIPE, "EPIPE"},
	{syscall.ECONNABORTED, "ECONNABORTED"},
	{syscall.EHOSTUNREACH, "EHOSTUNREACH"},
	{syscall.ENETUNREACH, "ENETUNREACH"},
}

func decisionsErrnoCode(err error) string {
	var errno syscall.Errno
	if !errors.As(err, &errno) {
		return ""
	}
	for _, entry := range decisionsErrnoNames {
		if errno == entry.errno {
			return entry.name
		}
	}
	for _, entry := range decisionsPlatformErrnoNames {
		if errno == entry.errno {
			return entry.name
		}
	}
	return ""
}

func decisionsHTTPError(ctx context.Context, resp *http.Response, secrets []string, now func() time.Time) error {
	window := decisionsMaxErrorBodyBytes + maxSecretBytes(secrets)
	raw, err := readDecisionsBody(ctx, resp.Body, int64(window)+1)
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return ctxErr
		}
		return decisionsNetworkError("The Decisions error response could not be read.", err, secrets)
	}
	truncated := len(raw) > decisionsMaxErrorBodyBytes
	text := redactDecisionsText(string(raw), secrets)

	result := &DecisionsError{
		Kind:          DecisionsErrorKindHTTP,
		Status:        resp.StatusCode,
		Body:          text,
		BodyTruncated: truncated,
		RetryAfterMS:  decisionsRetryAfter(resp.Header, now()),
	}
	if values, ok := resp.Header[http.CanonicalHeaderKey("x-request-id")]; ok {
		id := redactDecisionsText(strings.Join(values, ", "), secrets)
		result.RequestID = &id
	}
	var message *string
	if !truncated {
		var envelope map[string]json.RawMessage
		if json.Unmarshal([]byte(decodeDecisionsLossy(raw)), &envelope) == nil {
			var errObj map[string]json.RawMessage
			if json.Unmarshal(envelope["error"], &errObj) == nil && errObj != nil {
				result.ErrorType = redactedStringField(errObj["type"], secrets)
				result.ErrorCode = redactedStringField(errObj["code"], secrets)
				result.ErrorParam = redactedStringField(errObj["param"], secrets)
				message = decisionsStringField(errObj["message"])
			}
		}
	}
	prefix := fmt.Sprintf("Decisions request failed with HTTP %d", resp.StatusCode)
	if resp.StatusCode >= 300 && resp.StatusCode < 400 {
		prefix += " (redirects are not followed)"
	}
	if message != nil && *message != "" {
		result.Message = redactDecisionsText(prefix+": "+*message, secrets)
	} else {
		result.Message = redactDecisionsText(prefix+".", secrets)
	}
	return result
}

func redactedStringField(raw json.RawMessage, secrets []string) *string {
	value := decisionsStringField(raw)
	if value != nil {
		*value = redactDecisionsText(*value, secrets)
	}
	return value
}

func decisionsStringField(raw json.RawMessage) *string {
	if raw == nil {
		return nil
	}
	var value any
	if json.Unmarshal(raw, &value) != nil {
		return nil
	}
	var s string
	switch v := value.(type) {
	case string:
		s = v
	case float64:
		s = jsNumberString(v)
	case bool:
		s = strconv.FormatBool(v)
	default:
		return nil
	}
	return &s
}

func decisionsRetryAfter(headers http.Header, now time.Time) *int64 {
	if value, ok := decisionsRetryDelay(headers.Get("retry-after-ms"), 1); ok {
		return &value
	}
	raw := trimHTTPWhitespace(headers.Get("retry-after"))
	if value, ok := decisionsRetryDelay(raw, 1000); ok {
		return &value
	}
	date, err := time.Parse(http.TimeFormat, raw)
	if err != nil || date.UTC().Format(http.TimeFormat) != raw {
		return nil
	}
	delay := max(int64(0), date.UnixMilli()-now.UnixMilli())
	if delay > decisionsMaxSafeInteger {
		return nil
	}
	return &delay
}

func decisionsRetryDelay(raw string, scale int64) (int64, bool) {
	raw = trimHTTPWhitespace(raw)
	whole, fraction, dot := strings.Cut(raw, ".")
	isDecimal := func(s string) bool {
		return s != "" && strings.IndexFunc(s, func(r rune) bool { return r < '0' || r > '9' }) < 0
	}
	if !isDecimal(whole) || (dot && !isDecimal(fraction)) {
		return 0, false
	}
	whole = strings.TrimLeft(whole, "0")
	if whole == "" {
		whole = "0"
	}
	value, err := strconv.ParseInt(whole, 10, 64)
	if err != nil || value > decisionsMaxSafeInteger/scale {
		return 0, false
	}
	value *= scale
	if scale == 1000 {
		for i := 0; i < 3; i++ {
			if i < len(fraction) {
				value += int64(fraction[i]-'0') * []int64{100, 10, 1}[i]
			}
		}
		fraction = fraction[min(len(fraction), 3):]
	}
	if strings.Trim(fraction, "0") != "" {
		value++
	}
	return value, value <= decisionsMaxSafeInteger
}

// jsNumberString approximates JavaScript's String(number).
func jsNumberString(v float64) string {
	if abs := math.Abs(v); abs == 0 || (abs >= 1e-6 && abs < 1e21) {
		return strconv.FormatFloat(v, 'f', -1, 64)
	}
	s := strconv.FormatFloat(v, 'g', -1, 64)
	if strings.Contains(s, "e") {
		mantissa, exp, _ := strings.Cut(s, "e")
		if exp[0] == '+' || exp[0] == '-' {
			sign := exp[:1]
			exp = strings.TrimLeft(exp[1:], "0")
			return mantissa + "e" + sign + exp
		}
	}
	return s
}

func readDecisionsSuccess(ctx context.Context, resp *http.Response, secrets []string) (*DecisionsResult, error) {
	raw, err := readDecisionsBody(ctx, resp.Body, decisionsMaxResponseBytes+1)
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return nil, ctxErr
		}
		return nil, decisionsNetworkError("The Decisions response could not be read.", err, secrets)
	}
	if len(raw) > decisionsMaxResponseBytes {
		return nil, decisionsInvalidResponse(fmt.Sprintf("The Decisions API response exceeded %d bytes.", decisionsMaxResponseBytes))
	}
	text := decodeDecisionsLossy(raw)
	if !json.Valid([]byte(text)) {
		return nil, decisionsInvalidResponse("The Decisions API returned a response that is not valid JSON.")
	}
	return parseDecisionsResult([]byte(text))
}

type decisionsContextReader struct {
	ctx context.Context
	io.Reader
}

func (r decisionsContextReader) Read(p []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	return r.Reader.Read(p)
}

func readDecisionsBody(ctx context.Context, body io.ReadCloser, limit int64) ([]byte, error) {
	closeBody := sync.OnceFunc(func() { _ = body.Close() })
	closed := make(chan struct{})
	stop := context.AfterFunc(ctx, func() {
		closeBody()
		close(closed)
	})
	defer func() {
		if !stop() {
			// Join the cancellation closer; no body reader or detached worker is spawned.
			<-closed
		}
		closeBody()
	}()
	raw, err := io.ReadAll(io.LimitReader(decisionsContextReader{ctx, body}, limit))
	if ctxErr := ctx.Err(); ctxErr != nil {
		return nil, ctxErr
	}
	return raw, err
}

func decisionsInvalidResponse(message string) *DecisionsError {
	return &DecisionsError{Kind: DecisionsErrorKindInvalidResponse, Message: message}
}

// ============================================================================
// Endpoint -> request plan
// ============================================================================

var decisionsHeaderName = regexp.MustCompile("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")

func decisionsInvalidEndpoint(message string) *DecisionsError {
	return &DecisionsError{Kind: DecisionsErrorKindInvalidEndpoint, Message: message}
}

func trimHTTPWhitespace(value string) string {
	return strings.Trim(value, " \t")
}

func buildDecisionsURL(endpointType rpc.ProviderEndpointType, baseURL string) (string, error) {
	schemeEnd := strings.Index(baseURL, "://")
	if schemeEnd < 0 || (!strings.EqualFold(baseURL[:schemeEnd], "http") && !strings.EqualFold(baseURL[:schemeEnd], "https")) {
		return "", decisionsInvalidEndpoint("The provider endpoint base URL must be an absolute http or https URL.")
	}
	for i := 0; i < len(baseURL); i++ {
		c := baseURL[i]
		if c <= 0x20 || c >= 0x7f || c == '\\' || (c == '%' && (i+2 >= len(baseURL) || !isHex(baseURL[i+1]) || !isHex(baseURL[i+2]))) {
			return "", decisionsInvalidEndpoint("The provider endpoint base URL contains invalid URI characters.")
		}
	}
	u, err := url.Parse(baseURL)
	if err != nil || !u.IsAbs() {
		return "", decisionsInvalidEndpoint("The provider endpoint base URL is not a valid absolute URL.")
	}
	scheme := strings.ToLower(u.Scheme)
	if (scheme != "http" && scheme != "https") || u.Host == "" {
		return "", decisionsInvalidEndpoint("The provider endpoint base URL must use http or https.")
	}
	if u.User != nil || strings.Contains(rawDecisionsAuthority(baseURL), "@") {
		return "", decisionsInvalidEndpoint("The provider endpoint base URL must not contain credentials.")
	}
	host := strings.ToLower(u.Hostname())
	authority := rawDecisionsAuthority(baseURL)
	portText := ""
	hasPort := false
	if strings.HasPrefix(authority, "[") {
		end := strings.IndexByte(authority, ']')
		ip, err := netip.ParseAddr(host)
		if end < 0 || err != nil || !ip.Is6() {
			return "", decisionsInvalidEndpoint("The provider endpoint base URL has an invalid host.")
		}
		for _, c := range ip.Zone() {
			if !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && !strings.ContainsRune("-._~", c) {
				return "", decisionsInvalidEndpoint("The provider endpoint base URL has an invalid IPv6 zone.")
			}
		}
		rest := authority[end+1:]
		if rest != "" {
			if !strings.HasPrefix(rest, ":") {
				return "", decisionsInvalidEndpoint("The provider endpoint base URL has an invalid authority.")
			}
			portText, hasPort = rest[1:], true
		}
		host = "[" + strings.ReplaceAll(host, "%", "%25") + "]"
	} else {
		if strings.Count(authority, ":") > 1 || !validDecisionsHostname(host) {
			return "", decisionsInvalidEndpoint("The provider endpoint base URL has an invalid host.")
		}
		_, portText, hasPort = strings.Cut(authority, ":")
	}
	if hasPort {
		if portText == "" || strings.IndexFunc(portText, func(r rune) bool { return r < '0' || r > '9' }) >= 0 {
			return "", decisionsInvalidEndpoint("The provider endpoint base URL has an invalid port.")
		}
		port, err := strconv.ParseUint(portText, 10, 16)
		if err != nil {
			return "", decisionsInvalidEndpoint("The provider endpoint base URL has an invalid port.")
		}
		if !(scheme == "https" && port == 443) && !(scheme == "http" && port == 80) {
			host += ":" + strconv.FormatUint(port, 10)
		}
	}
	basePath := strings.TrimRight(normalizeDecisionsPath(u.EscapedPath()), "/")
	suffix := "/decisions"
	if endpointType == rpc.ProviderEndpointTypeAzure && !strings.HasSuffix(strings.ToLower(basePath), "/openai/v1") {
		suffix = "/openai/v1/decisions"
	}
	result := scheme + "://" + host + basePath + suffix
	if u.RawQuery != "" {
		result += "?" + u.RawQuery
	}
	return result, nil
}

func validDecisionsHostname(host string) bool {
	host = strings.TrimSuffix(host, ".")
	if host == "" || len(host) > 253 {
		return false
	}
	for _, label := range strings.Split(host, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, c := range label {
			if !(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-' {
				return false
			}
		}
	}
	return true
}

func normalizeDecisionsPath(path string) string {
	var segments []string
	for _, segment := range strings.Split(path, "/") {
		dots := strings.ReplaceAll(strings.ReplaceAll(segment, "%2e", "."), "%2E", ".")
		switch dots {
		case ".":
			continue
		case "..":
			if len(segments) > 1 {
				segments = segments[:len(segments)-1]
			}
		default:
			segments = append(segments, segment)
		}
	}
	return strings.Join(segments, "/")
}

func rawDecisionsAuthority(rawURL string) string {
	_, rest, ok := strings.Cut(rawURL, "//")
	if !ok {
		return ""
	}
	if end := strings.IndexAny(rest, "/?#"); end >= 0 {
		rest = rest[:end]
	}
	return rest
}

func checkDecisionsHeader(name, value string) (string, error) {
	if !decisionsHeaderName.MatchString(name) || !utf8.ValidString(value) {
		return "", decisionsInvalidEndpoint("The provider endpoint returned an invalid HTTP header.")
	}
	for _, c := range value {
		if (c < 0x20 && c != '\t') || c == 0x7f {
			return "", decisionsInvalidEndpoint("The provider endpoint returned an invalid HTTP header.")
		}
	}
	return trimHTTPWhitespace(value), nil
}

type decisionsHeaderSet struct {
	order  []string
	values map[string]string
}

func (h *decisionsHeaderSet) set(name, value string) {
	if _, ok := h.values[name]; !ok {
		h.order = append(h.order, name)
	}
	h.values[name] = value
}

func buildDecisionsHeaders(endpoint *rpc.ProviderEndpoint) (*decisionsHeaderSet, error) {
	if endpoint.APIKey != nil {
		if _, err := checkDecisionsHeader("api-key", *endpoint.APIKey); err != nil {
			return nil, err
		}
	}
	merged := &decisionsHeaderSet{values: map[string]string{}}
	names := make([]string, 0, len(endpoint.Headers))
	for name := range endpoint.Headers {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		value, err := checkDecisionsHeader(name, endpoint.Headers[name])
		if err != nil {
			return nil, err
		}
		lower := strings.ToLower(name)
		if lower == "content-type" || lower == "accept" {
			continue
		}
		if existing, ok := merged.values[lower]; ok && existing != value {
			return nil, decisionsInvalidEndpoint("The provider endpoint returned conflicting values for the same HTTP header.")
		}
		merged.set(lower, value)
	}

	apiKeyHeader := ""
	_, hasAuthorization := merged.values["authorization"]
	_, hasAPIKey := merged.values["api-key"]
	if endpoint.APIKey != nil && *endpoint.APIKey != "" && !hasAuthorization && !hasAPIKey {
		if endpoint.Type == rpc.ProviderEndpointTypeAzure {
			apiKeyHeader = "api-key"
		} else {
			apiKeyHeader = "authorization"
		}
		value, err := checkDecisionsHeader(apiKeyHeader, *endpoint.APIKey)
		if err != nil {
			return nil, err
		}
		if apiKeyHeader == "authorization" {
			value = "Bearer " + value
		}
		merged.set(apiKeyHeader, value)
	}

	if token := endpoint.SessionToken; token != nil {
		value, err := checkDecisionsHeader(token.Header, token.Token)
		if err != nil {
			return nil, err
		}
		lower := strings.ToLower(token.Header)
		if lower == apiKeyHeader {
			return nil, &DecisionsError{
				Kind:    DecisionsErrorKindCredentialConflict,
				Message: "The provider session token uses the same header as the endpoint API key.",
			}
		}
		merged.set(lower, value)
	}

	merged.set("content-type", "application/json")
	merged.set("accept", "application/json")
	return merged, nil
}

func planDecisionsAttempt(endpoint *rpc.ProviderEndpoint, model string) (*decisionsAttempt, error) {
	if endpoint.Type != rpc.ProviderEndpointTypeOpenai && endpoint.Type != rpc.ProviderEndpointTypeAzure {
		return nil, &DecisionsError{
			Kind:    DecisionsErrorKindUnsupportedProvider,
			Message: "The session's provider type does not support the Decisions API.",
		}
	}
	if endpoint.Transport != nil && *endpoint.Transport != rpc.ProviderEndpointTransportHTTP {
		return nil, &DecisionsError{
			Kind:    DecisionsErrorKindUnsupportedTransport,
			Message: "The session's provider transport is not supported by the Decisions API.",
		}
	}
	if endpoint.SessionToken != nil && endpoint.SessionToken.Model != nil && *endpoint.SessionToken.Model != model {
		return nil, &DecisionsError{
			Kind:    DecisionsErrorKindModelMismatch,
			Message: "The provider session token is bound to a different model. Set EndpointModelID and request Model consistently.",
		}
	}
	target, err := buildDecisionsURL(endpoint.Type, endpoint.BaseURL)
	if err != nil {
		return nil, err
	}
	headers, err := buildDecisionsHeaders(endpoint)
	if err != nil {
		return nil, err
	}

	optional := func(name string) any {
		if value, ok := headers.values[name]; ok {
			return value
		}
		return nil
	}
	var tokenPart any
	if endpoint.SessionToken != nil {
		lower := strings.ToLower(endpoint.SessionToken.Header)
		tokenPart = []any{lower, optional(lower)}
	}
	fingerprint, err := marshalDecisionsWire([]any{target, optional("authorization"), optional("api-key"), tokenPart})
	if err != nil {
		return nil, err
	}

	attempt := &decisionsAttempt{
		url:         target,
		fingerprint: string(fingerprint),
		secrets:     collectDecisionsSecrets(endpoint, target),
	}
	for _, name := range headers.order {
		attempt.headers = append(attempt.headers, [2]string{name, headers.values[name]})
	}
	return attempt, nil
}

// ============================================================================
// Secrets and redaction
// ============================================================================

var decisionsBearerPrefix = regexp.MustCompile(`(?i)^[ \t]*bearer[ \t]+`)

func collectDecisionsSecrets(endpoint *rpc.ProviderEndpoint, target string) []string {
	var raw []string
	seen := map[string]bool{}
	add := func(value string, credential bool) {
		trimmed := trimHTTPWhitespace(value)
		if trimmed != "" && (credential || utf8.RuneCountInString(trimmed) >= decisionsMinSecretRunes) && !seen[trimmed] {
			seen[trimmed] = true
			raw = append(raw, trimmed)
		}
	}
	if endpoint.APIKey != nil {
		add(*endpoint.APIKey, true)
		if endpoint.Type == rpc.ProviderEndpointTypeOpenai && trimHTTPWhitespace(*endpoint.APIKey) != "" {
			add("Bearer "+trimHTTPWhitespace(*endpoint.APIKey), true)
		}
	}
	tokenHeader := ""
	if endpoint.SessionToken != nil {
		add(endpoint.SessionToken.Token, true)
		tokenHeader = strings.ToLower(endpoint.SessionToken.Header)
		if tokenHeader == "authorization" {
			add(decisionsBearerPrefix.ReplaceAllString(endpoint.SessionToken.Token, ""), true)
		}
	}
	names := make([]string, 0, len(endpoint.Headers))
	for name := range endpoint.Headers {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		value := endpoint.Headers[name]
		lower := strings.ToLower(name)
		credential := lower == "authorization" || lower == "api-key" || lower == tokenHeader
		add(value, credential)
		add(decisionsBearerPrefix.ReplaceAllString(value, ""), credential)
	}
	if _, query, ok := strings.Cut(target, "?"); ok {
		for _, pair := range strings.Split(query, "&") {
			_, encoded, _ := strings.Cut(pair, "=")
			value := lenientPercentDecode(strings.ReplaceAll(encoded, "+", " "))
			if utf8.RuneCountInString(trimHTTPWhitespace(value)) >= decisionsMinSecretRunes {
				add(value, false)
				add(encoded, false)
			}
		}
	}

	var variants []string
	seenVariant := map[string]bool{}
	for _, secret := range raw {
		for _, variant := range decisionsSecretVariants(secret) {
			if !seenVariant[variant] {
				seenVariant[variant] = true
				variants = append(variants, variant)
			}
		}
	}
	sort.SliceStable(variants, func(i, j int) bool {
		return utf16Len(variants[i]) > utf16Len(variants[j])
	})
	return variants
}

func utf16Len(s string) int {
	n := 0
	for _, r := range s {
		n += utf16.RuneLen(r)
	}
	return n
}

func lenientPercentDecode(s string) string {
	var out []byte
	for i := 0; i < len(s); i++ {
		if s[i] == '%' && i+2 < len(s) && isHex(s[i+1]) && isHex(s[i+2]) {
			out = append(out, unhex(s[i+1])<<4|unhex(s[i+2]))
			i += 2
			continue
		}
		out = append(out, s[i])
	}
	return decodeDecisionsLossy(out)
}

func isHex(c byte) bool {
	return ('0' <= c && c <= '9') || ('a' <= c && c <= 'f') || ('A' <= c && c <= 'F')
}

func unhex(c byte) byte {
	switch {
	case '0' <= c && c <= '9':
		return c - '0'
	case 'a' <= c && c <= 'f':
		return c - 'a' + 10
	default:
		return c - 'A' + 10
	}
}

func decisionsSecretVariants(secret string) []string {
	jsonEscaped := jsJSONEscape(secret, false)
	asciiEscaped := jsJSONEscape(secret, true)
	uriEncoded := jsEncodeURIComponent(secret)
	return []string{
		secret,
		jsonEscaped,
		asciiEscaped,
		strings.ReplaceAll(jsonEscaped, "/", `\/`),
		strings.ReplaceAll(asciiEscaped, "/", `\/`),
		uriEncoded,
		strings.ReplaceAll(uriEncoded, "%20", "+"),
	}
}

// jsJSONEscape matches JSON.stringify(s).slice(1, -1); ascii additionally escapes every UTF-16
// code unit at or above U+007F as \uXXXX.
func jsJSONEscape(s string, ascii bool) string {
	var b strings.Builder
	for _, r := range s {
		switch r {
		case '"':
			b.WriteString(`\"`)
		case '\\':
			b.WriteString(`\\`)
		case '\b':
			b.WriteString(`\b`)
		case '\f':
			b.WriteString(`\f`)
		case '\n':
			b.WriteString(`\n`)
		case '\r':
			b.WriteString(`\r`)
		case '\t':
			b.WriteString(`\t`)
		default:
			switch {
			case r < 0x20:
				fmt.Fprintf(&b, `\u%04x`, r)
			case ascii && r >= 0x7f:
				if r > 0xffff {
					hi, lo := utf16.EncodeRune(r)
					fmt.Fprintf(&b, `\u%04x\u%04x`, hi, lo)
				} else {
					fmt.Fprintf(&b, `\u%04x`, r)
				}
			default:
				b.WriteRune(r)
			}
		}
	}
	return b.String()
}

// jsEncodeURIComponent matches JavaScript's encodeURIComponent.
func jsEncodeURIComponent(s string) string {
	const unreserved = "-_.!~*'()"
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if ('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z') || ('0' <= c && c <= '9') || strings.IndexByte(unreserved, c) >= 0 {
			b.WriteByte(c)
			continue
		}
		fmt.Fprintf(&b, "%%%02X", c)
	}
	return b.String()
}

func maxSecretBytes(secrets []string) int {
	longest := 0
	for _, secret := range secrets {
		longest = max(longest, len(secret))
	}
	return longest
}

// Folding only escape hex digits preserves byte offsets and ordinary character case.
func foldDecisionsEscapes(text string) (string, []int) {
	folded := []byte(text)
	var positions []int
	for i := 0; i < len(folded); i++ {
		start, end := 0, 0
		if folded[i] == '%' && i+3 <= len(folded) {
			start, end = i+1, i+3
		} else if folded[i] == '\\' && i+6 <= len(folded) && folded[i+1] == 'u' {
			start, end = i+2, i+6
		} else {
			continue
		}
		valid := true
		for _, c := range folded[start:end] {
			valid = valid && isHex(c)
		}
		if valid {
			for j := start; j < end; j++ {
				if folded[j] >= 'A' && folded[j] <= 'F' {
					folded[j] += 'a' - 'A'
				}
				if folded[j] >= 'a' && folded[j] <= 'f' {
					positions = append(positions, j)
				}
			}
			i = end - 1
		}
	}
	return string(folded), positions
}

func matchesDecisionsVariant(text, folded string, positions []int) bool {
	cursor := 0
	for _, p := range positions {
		if text[cursor:p] != folded[cursor:p] || text[p]|0x20 != folded[p] {
			return false
		}
		cursor = p + 1
	}
	return text[cursor:] == folded[cursor:]
}

func redactDecisionsText(text string, secrets []string) string {
	limit := min(len(text), decisionsMaxErrorBodyBytes)
	retainedLimit := limit
	window := min(len(text), decisionsMaxErrorBodyBytes+maxSecretBytes(secrets)+1)
	if !utf8.ValidString(text[:window]) {
		prefix := truncateUTF8(text, limit)
		retainedLimit = len(decodeDecisionsUTF8([]byte(prefix)))
		matchLimit := retainedLimit
		if len(prefix) < limit {
			// A crossing character can begin a secret, but cannot be retained literally.
			matchLimit++
		}
		text = decodeDecisionsUTF8([]byte(text[:window]))
		limit, window = matchLimit, len(text)
	}
	type interval struct{ start, end int }
	var matches []interval
	seen := make(map[string]bool, len(secrets))
	for _, secret := range secrets {
		folded, positions := foldDecisionsEscapes(secret)
		if secret == "" || seen[folded] {
			continue
		}
		seen[folded] = true
		prefix := secret
		if len(positions) > 0 {
			prefix = secret[:positions[0]]
		}
		for offset := 0; offset < limit; {
			index := strings.Index(text[offset:window], prefix)
			if index < 0 || offset+index >= limit {
				break
			}
			start := offset + index
			end := start + len(secret)
			if end <= window && matchesDecisionsVariant(text[start:end], folded, positions) {
				matches = append(matches, interval{start, end})
			}
			offset = start + 1 // Include self-overlapping matches against the original text.
		}
	}
	sort.Slice(matches, func(i, j int) bool {
		if matches[i].start != matches[j].start {
			return matches[i].start < matches[j].start
		}
		return matches[i].end > matches[j].end
	})
	merged := matches[:0]
	for _, match := range matches {
		if n := len(merged); n > 0 && match.start < merged[n-1].end {
			merged[n-1].end = max(merged[n-1].end, match.end)
		} else {
			merged = append(merged, match)
		}
	}
	var out strings.Builder
	cursor := 0
	for _, match := range merged {
		out.WriteString(text[cursor:match.start])
		out.WriteString(decisionsRedacted)
		cursor = match.end
	}
	if cursor < retainedLimit {
		// Lookahead identifies crossing credentials but must never become retained text.
		out.WriteString(truncateUTF8(text[cursor:], retainedLimit-cursor))
	}
	return truncateUTF8(decodeDecisionsLossy([]byte(out.String())), decisionsMaxErrorBodyBytes)
}

// ============================================================================
// Body decoding
// ============================================================================

// decodeDecisionsLossy decodes UTF-8 like the WHATWG TextDecoder: a leading BOM is removed
// and each maximal invalid subsequence becomes U+FFFD.
func decodeDecisionsLossy(data []byte) string {
	return decodeDecisionsUTF8(bytes.TrimPrefix(data, []byte("\xEF\xBB\xBF")))
}

func decodeDecisionsUTF8(data []byte) string {
	if utf8.Valid(data) {
		return string(data)
	}
	var b strings.Builder
	b.Grow(len(data))
	for i := 0; i < len(data); {
		r, size := utf8.DecodeRune(data[i:])
		if r != utf8.RuneError || size > 1 {
			b.WriteRune(r)
			i += size
			continue
		}
		b.WriteRune(utf8.RuneError)
		i += maximalSubpartLen(data[i:])
	}
	return b.String()
}

// maximalSubpartLen returns the length of the maximal subpart of an ill-formed sequence
// starting at data[0] (at least 1), per the Unicode "substitution of maximal subparts" rule.
func maximalSubpartLen(data []byte) int {
	lead := data[0]
	var need int
	lo, hi := byte(0x80), byte(0xBF)
	switch {
	case lead >= 0xC2 && lead <= 0xDF:
		need = 1
	case lead == 0xE0:
		need, lo = 2, 0xA0
	case lead >= 0xE1 && lead <= 0xEC, lead == 0xEE, lead == 0xEF:
		need = 2
	case lead == 0xED:
		need, hi = 2, 0x9F
	case lead == 0xF0:
		need, lo = 3, 0x90
	case lead >= 0xF1 && lead <= 0xF3:
		need = 3
	case lead == 0xF4:
		need, hi = 3, 0x8F
	default:
		return 1
	}
	n := 1
	for k := 0; k < need && n < len(data); k++ {
		c := data[n]
		if c < lo || c > hi {
			break
		}
		lo, hi = 0x80, 0xBF
		n++
	}
	return n
}

// truncateUTF8 cuts s to at most limit bytes, backing off to a UTF-8 character
// boundary; a split trailing character is dropped entirely (no U+FFFD).
func truncateUTF8(s string, limit int) string {
	if len(s) <= limit {
		return s
	}
	cut := limit
	for cut > 0 && !utf8.RuneStart(s[cut]) {
		cut--
	}
	return s[:cut]
}

// ============================================================================
// Response parsing
// ============================================================================

type decisionsParseError string

func (e decisionsParseError) Error() string { return string(e) }

type decisionsObject map[string]json.RawMessage

func asDecisionsObject(raw json.RawMessage) (decisionsObject, bool) {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 || trimmed[0] != '{' {
		return nil, false
	}
	var obj decisionsObject
	if json.Unmarshal(trimmed, &obj) != nil {
		return nil, false
	}
	return obj, true
}

func isJSONNull(raw json.RawMessage) bool {
	return raw == nil || string(bytes.TrimSpace(raw)) == "null"
}

func (o decisionsObject) num(key string) (float64, error) {
	raw := bytes.TrimSpace(o[key])
	var value float64
	if len(raw) == 0 || (raw[0] != '-' && (raw[0] < '0' || raw[0] > '9')) || json.Unmarshal(raw, &value) != nil || math.IsNaN(value) || math.IsInf(value, 0) {
		return 0, decisionsParseError(fmt.Sprintf("Expected number '%s'.", key))
	}
	return value, nil
}

func (o decisionsObject) optionalCount(key string) (int64, error) {
	if o[key] == nil {
		return 0, nil
	}
	return o.integer(key)
}

func (o decisionsObject) integer(key string) (int64, error) {
	raw := strings.TrimSpace(string(o[key]))
	fail := decisionsParseError(fmt.Sprintf("Expected signed integer '%s'.", key))
	if raw == "" || (raw[0] != '-' && (raw[0] < '0' || raw[0] > '9')) {
		return 0, fail
	}
	if !strings.ContainsAny(raw, ".eE") {
		value, err := strconv.ParseInt(raw, 10, 64)
		if err != nil {
			return 0, fail
		}
		return value, nil
	}
	negative := strings.HasPrefix(raw, "-")
	mantissa := strings.TrimPrefix(raw, "-")
	exponentText := ""
	if i := strings.IndexAny(mantissa, "eE"); i >= 0 {
		mantissa, exponentText = mantissa[:i], mantissa[i+1:]
	}
	whole, fraction, _ := strings.Cut(mantissa, ".")
	digits := strings.TrimLeft(whole+fraction, "0")
	if digits == "" {
		return 0, nil
	}
	var exponent int64
	if exponentText != "" {
		var err error
		exponent, err = strconv.ParseInt(exponentText, 10, 64)
		if err != nil || exponent > int64(len(raw))+20 || exponent < -int64(len(raw))-20 {
			return 0, fail
		}
	}
	shift := exponent - int64(len(fraction))
	if shift < 0 {
		if -shift >= int64(len(digits)) || strings.Trim(digits[int64(len(digits))+shift:], "0") != "" {
			return 0, fail
		}
		digits = digits[:int64(len(digits))+shift]
	} else {
		if int64(len(digits))+shift > 16 {
			return 0, fail
		}
		digits += strings.Repeat("0", int(shift))
	}
	value, err := strconv.ParseInt(digits, 10, 64)
	if err != nil || value > decisionsMaxSafeInteger {
		return 0, fail
	}
	if negative {
		value = -value
	}
	return value, nil
}

func (o decisionsObject) str(key string) (string, error) {
	raw := bytes.TrimSpace(o[key])
	var value string
	if len(raw) == 0 || raw[0] != '"' || json.Unmarshal(raw, &value) != nil {
		return "", decisionsParseError(fmt.Sprintf("Expected string '%s'.", key))
	}
	return value, nil
}

func (o decisionsObject) arr(key string) ([]decisionsObject, []json.RawMessage, error) {
	fail := decisionsParseError(fmt.Sprintf("Expected array of objects '%s'.", key))
	raw := bytes.TrimSpace(o[key])
	var items []json.RawMessage
	if len(raw) == 0 || raw[0] != '[' || json.Unmarshal(raw, &items) != nil {
		return nil, nil, fail
	}
	objects := make([]decisionsObject, len(items))
	for i, item := range items {
		obj, ok := asDecisionsObject(item)
		if !ok {
			return nil, nil, fail
		}
		objects[i] = obj
	}
	return objects, items, nil
}

func (o decisionsObject) choiceValue(key string) (DecisionsChoiceValue, error) {
	var value DecisionsChoiceValue
	if o[key] == nil || value.UnmarshalJSON(o[key]) != nil {
		return value, decisionsParseError(fmt.Sprintf("Expected string or boolean '%s'.", key))
	}
	return value, nil
}

func (o decisionsObject) name() (*string, error) {
	if isJSONNull(o["name"]) {
		return nil, nil
	}
	value, err := o.str("name")
	if err != nil {
		return nil, err
	}
	return &value, nil
}

func parseDecisionsAnswer(obj decisionsObject, raw json.RawMessage) (DecisionsAnswer, error) {
	answerType, err := obj.str("type")
	if err != nil {
		return nil, err
	}
	name, err := obj.name()
	if err != nil {
		return nil, err
	}
	switch answerType {
	case "predicate":
		probability, err := obj.num("probability")
		if err != nil {
			return nil, err
		}
		return DecisionsPredicateAnswer{Name: name, Probability: probability}, nil
	case "choice":
		choice, err := obj.choiceValue("choice")
		if err != nil {
			return nil, err
		}
		confidence, err := obj.num("confidence")
		if err != nil {
			return nil, err
		}
		entries, _, err := obj.arr("probabilities")
		if err != nil {
			return nil, err
		}
		probabilities := make([]DecisionsChoiceProbability, 0, len(entries))
		for _, entry := range entries {
			value, err := entry.choiceValue("value")
			if err != nil {
				return nil, err
			}
			probability, err := entry.num("probability")
			if err != nil {
				return nil, err
			}
			probabilities = append(probabilities, DecisionsChoiceProbability{Value: value, Probability: probability})
		}
		return DecisionsChoiceAnswer{Name: name, Choice: choice, Confidence: confidence, Probabilities: probabilities}, nil
	case "score":
		score, err := obj.num("score")
		if err != nil {
			return nil, err
		}
		confidence, err := obj.num("confidence")
		if err != nil {
			return nil, err
		}
		entries, _, err := obj.arr("probabilities")
		if err != nil {
			return nil, err
		}
		probabilities := make([]DecisionsScoreProbability, 0, len(entries))
		for _, entry := range entries {
			value, err := entry.integer("value")
			if err != nil {
				return nil, err
			}
			label, err := entry.str("label")
			if err != nil {
				return nil, err
			}
			probability, err := entry.num("probability")
			if err != nil {
				return nil, err
			}
			probabilities = append(probabilities, DecisionsScoreProbability{Label: label, Value: value, Probability: probability})
		}
		return DecisionsScoreAnswer{Name: name, Score: score, Confidence: confidence, Probabilities: probabilities}, nil
	case "refusal":
		return DecisionsRefusalAnswer{Name: name}, nil
	default:
		return DecisionsUnknownAnswer{Name: name, WireType: answerType, Raw: append(json.RawMessage(nil), bytes.TrimSpace(raw)...)}, nil
	}
}

func parseDecisionsResult(data []byte) (*DecisionsResult, error) {
	data = bytes.TrimPrefix(data, []byte("\xEF\xBB\xBF"))
	result, err := parseDecisionsResultInner(data)
	if err != nil {
		var parseErr decisionsParseError
		if errors.As(err, &parseErr) {
			return nil, decisionsInvalidResponse("The Decisions API returned an unexpected response: " + parseErr.Error())
		}
		return nil, err
	}
	return result, nil
}

func parseDecisionsResultInner(data []byte) (*DecisionsResult, error) {
	root, ok := asDecisionsObject(data)
	if !ok {
		return nil, decisionsParseError("Expected a JSON object.")
	}
	usage, ok := asDecisionsObject(root["usage"])
	if !ok {
		return nil, decisionsParseError("Expected object 'usage'.")
	}
	inputDetails, _ := asDecisionsObject(usage["input_tokens_details"])
	outputDetails, _ := asDecisionsObject(usage["output_tokens_details"])

	entries, raws, err := root.arr("answers")
	if err != nil {
		return nil, err
	}
	answers := make([]DecisionsAnswer, 0, len(entries))
	for i, entry := range entries {
		answer, err := parseDecisionsAnswer(entry, raws[i])
		if err != nil {
			return nil, err
		}
		answers = append(answers, answer)
	}
	model, err := root.str("model")
	if err != nil {
		return nil, err
	}

	result := &DecisionsResult{Answers: answers, Model: model}
	counts := []struct {
		obj    decisionsObject
		key    string
		target *int64
	}{
		{usage, "input_tokens", &result.Usage.InputTokens},
		{inputDetails, "cached_tokens", &result.Usage.InputTokensDetails.CachedTokens},
		{inputDetails, "cache_write_tokens", &result.Usage.InputTokensDetails.CacheWriteTokens},
		{usage, "output_tokens", &result.Usage.OutputTokens},
		{outputDetails, "reasoning_tokens", &result.Usage.OutputTokensDetails.ReasoningTokens},
		{usage, "total_tokens", &result.Usage.TotalTokens},
	}
	for _, c := range counts {
		value, err := c.obj.optionalCount(c.key)
		if err != nil {
			return nil, err
		}
		*c.target = value
	}
	return result, nil
}
