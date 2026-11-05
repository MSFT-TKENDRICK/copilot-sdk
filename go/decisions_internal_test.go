package copilot

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"syscall"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/github/copilot-sdk/go/rpc"
)

type decisionsRoundTripFunc func(*http.Request) (*http.Response, error)

func (f decisionsRoundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) { return f(req) }

func TestSessionDecisionsUsesProviderGetEndpointRPC(t *testing.T) {
	for _, tc := range []struct {
		name    string
		modelID string
	}{
		{"default model", ""},
		{"endpoint model", "endpoint-model"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			session, server := newSetToolsTestSession(t)
			var gotURL, gotAuth string
			transport := decisionsRoundTripFunc(func(req *http.Request) (*http.Response, error) {
				gotURL = req.URL.String()
				gotAuth = req.Header.Get("Authorization")
				body := `{"answers":[{"type":"predicate","probability":0.5}],"model":"m","usage":{}}`
				return &http.Response{StatusCode: 200, Header: http.Header{}, Body: io.NopCloser(strings.NewReader(body)), Request: req}, nil
			})
			type outcome struct {
				result *DecisionsResult
				err    error
			}
			done := make(chan outcome, 1)
			go func() {
				result, err := session.Decisions().Create(context.Background(), DecisionsRequest{
					Model:     "gpt-6-luna",
					Input:     "x",
					Questions: []DecisionsQuestion{DecisionsPredicateQuestion{Instructions: "q"}},
				}, &DecisionsOptions{EndpointModelID: tc.modelID, Transport: transport})
				done <- outcome{result, err}
			}()

			request := server.expectRequest(t, "session.provider.getEndpoint")
			if request.Params["sessionId"] != "session-1" {
				t.Fatalf("params = %v", request.Params)
			}
			modelID, hasModelID := request.Params["modelId"]
			if tc.modelID == "" && hasModelID {
				t.Fatalf("modelId should be omitted, params = %v", request.Params)
			}
			if tc.modelID != "" && modelID != tc.modelID {
				t.Fatalf("modelId = %v", modelID)
			}
			server.respond(t, request.ID, map[string]any{
				"type":    "openai",
				"baseUrl": "https://api.openai.com/v1",
				"apiKey":  "sk-test-0001",
				"headers": map[string]string{},
			})

			got := <-done
			if got.err != nil {
				t.Fatal(got.err)
			}
			if len(got.result.Answers) != 1 || gotURL != "https://api.openai.com/v1/decisions" || gotAuth != "Bearer sk-test-0001" {
				t.Fatalf("result = %+v url = %s auth = %s", got.result, gotURL, gotAuth)
			}
		})
	}
}

func TestSessionDecisionsMapsRPCErrorToEndpointUnavailable(t *testing.T) {
	session, server := newSetToolsTestSession(t)
	done := make(chan error, 1)
	go func() {
		_, err := session.Decisions().Create(context.Background(), DecisionsRequest{Model: "m", Input: "x"}, nil)
		done <- err
	}()
	request := server.expectRequest(t, "session.provider.getEndpoint")
	server.respondError(t, request.ID, -32603, "Provider endpoint access is disabled. To enable this API, set COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true")
	err := <-done
	var de *DecisionsError
	if !errors.As(err, &de) || de.Kind != DecisionsErrorKindEndpointUnavailable || de.Message != decisionsGateHint {
		t.Fatalf("err = %v", err)
	}
}

func TestSessionDecisionsWithoutRPC(t *testing.T) {
	_, err := (&Session{}).Decisions().Create(context.Background(), DecisionsRequest{Model: "m"}, nil)
	var de *DecisionsError
	if !errors.As(err, &de) || de.Kind != DecisionsErrorKindEndpointUnavailable {
		t.Fatalf("err = %v", err)
	}
}

func TestDecisionsEncodeURIComponent(t *testing.T) {
	for in, want := range map[string]string{
		"a b/é":                 "a%20b%2F%C3%A9",
		"AZaz09-_.!~*'()":       "AZaz09-_.!~*'()",
		"🔑":                     "%F0%9F%94%91",
		"k=v&x?#[]@$,;:+\"<>\\": "k%3Dv%26x%3F%23%5B%5D%40%24%2C%3B%3A%2B%22%3C%3E%5C",
	} {
		if got := jsEncodeURIComponent(in); got != want {
			t.Errorf("jsEncodeURIComponent(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestDecisionsJSONEscape(t *testing.T) {
	in := "a\"b\\c/\b\f\n\r\t\x01é\u007f🔑"
	if got, want := jsJSONEscape(in, false), `a\"b\\c/\b\f\n\r\t\u0001é`+"\u007f🔑"; got != want {
		t.Errorf("json = %q, want %q", got, want)
	}
	if got, want := jsJSONEscape(in, true), `a\"b\\c/\b\f\n\r\t\u0001\u00e9\u007f\ud83d\udd11`; got != want {
		t.Errorf("ascii = %q, want %q", got, want)
	}
}

func TestDecisionsRedactionMatchesEscapesCaseInsensitively(t *testing.T) {
	secrets := collectDecisionsSecrets(&rpc.ProviderEndpoint{APIKey: String("sécret/🔑 key")}, "https://h/")
	for _, encoded := range []string{
		`s\u00e9cret\/\ud83d\udd11 key`,
		`s\u00E9cret\/\uD83D\uDD11 key`,
		`s%C3%A9cret%2F%F0%9F%94%91%20key`,
		`s%c3%a9cret%2f%f0%9f%94%91+key`,
	} {
		if got := redactDecisionsText("x"+encoded+"y", secrets); got != "x[REDACTED]y" {
			t.Errorf("redact(%q) = %q", encoded, got)
		}
	}
}

func TestDecisionsSecretsIncludeQueryValues(t *testing.T) {
	secrets := collectDecisionsSecrets(&rpc.ProviderEndpoint{}, "https://h/decisions?api-version=2025&sig=abc%2Bdef+ghi%zz")
	found := false
	for _, s := range secrets {
		if s == "abc+def ghi%zz" {
			found = true
		}
		if s == "2025" {
			t.Error("short query values must not be secrets")
		}
	}
	if !found {
		t.Fatalf("secrets = %q", secrets)
	}
}

func TestDecisionsLossyDecode(t *testing.T) {
	for in, want := range map[string]string{
		"\xEF\xBB\xBFok":         "ok",
		"\xE2\x82A":              "\uFFFDA",
		"a\xFFb":                 "a\uFFFDb",
		"\xF0\x9F\x94":           "\uFFFD",
		"\xED\xA0\x80":           "\uFFFD\uFFFD\uFFFD",
		"\xC0\xAF":               "\uFFFD\uFFFD",
		"valid \xF0\x9F\x94\x91": "valid 🔑",
	} {
		if got := decodeDecisionsLossy([]byte(in)); got != want {
			t.Errorf("decode(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestDecisionsTruncateUTF8(t *testing.T) {
	if got := truncateUTF8("ab🔑", 4); got != "ab" {
		t.Errorf("got %q", got)
	}
	if got := truncateUTF8("ab🔑", 6); got != "ab🔑" {
		t.Errorf("got %q", got)
	}
	if got := truncateUTF8("abc", 2); got != "ab" {
		t.Errorf("got %q", got)
	}
}

func TestDecisionsRejectsNonIntegralUsage(t *testing.T) {
	_, err := parseDecisionsResult([]byte(`{"answers":[],"model":"m","usage":{"input_tokens":1.5}}`))
	var de *DecisionsError
	if !errors.As(err, &de) || de.Kind != DecisionsErrorKindInvalidResponse {
		t.Fatalf("err = %v", err)
	}
}

func TestDecisionsErrnoCode(t *testing.T) {
	cases := []struct {
		err  error
		want string
	}{
		{syscall.ECONNRESET, "ECONNRESET"},
		{syscall.ECONNREFUSED, "ECONNREFUSED"},
		{syscall.ETIMEDOUT, "ETIMEDOUT"},
		{syscall.EPIPE, "EPIPE"},
		{syscall.ECONNABORTED, "ECONNABORTED"},
		{syscall.EHOSTUNREACH, "EHOSTUNREACH"},
		{syscall.ENETUNREACH, "ENETUNREACH"},
		{errors.Join(errors.New("wrapped"), syscall.ECONNREFUSED), "ECONNREFUSED"},
		{syscall.Errno(1), ""},
		{errors.New("plain"), ""},
	}
	for _, entry := range decisionsPlatformErrnoNames {
		cases = append(cases, struct {
			err  error
			want string
		}{entry.errno, entry.name})
	}
	for _, tc := range cases {
		if got := decisionsErrnoCode(tc.err); got != tc.want {
			t.Errorf("decisionsErrnoCode(%v) = %q, want %q", tc.err, got, tc.want)
		}
	}
}

func TestDecisionsProductionLongCredentialBoundary(t *testing.T) {
	secret := strings.Repeat("s", decisionsMaxErrorBodyBytes+4000) + "-synthetic"
	body := &productionCountingBody{Reader: strings.NewReader(secret + " LOOKAHEAD")}
	err := decisionsHTTPError(context.Background(), &http.Response{StatusCode: 400, Body: body, Header: http.Header{}}, decisionsSecretVariants(secret), time.Now)
	var de *DecisionsError
	if !errors.As(err, &de) || !de.BodyTruncated || de.Body != decisionsRedacted || body.closed != 1 {
		t.Fatal("a credential longer than the retention budget was not protected as a whole")
	}
	shortQuery := "%F0%9F%94%91"
	secrets := collectDecisionsSecrets(&rpc.ProviderEndpoint{}, "https://h?short="+shortQuery)
	if got := redactDecisionsText(shortQuery, secrets); got != shortQuery {
		t.Fatal("raw query protection must retain the decoded Unicode-scalar heuristic")
	}
}

func TestDecisionsProductionRetryClockAfterBodyRead(t *testing.T) {
	now := time.UnixMilli(1445412420000)
	body := &productionClockBody{Reader: strings.NewReader("{}"), now: &now}
	headers := http.Header{"Retry-After": {"Wed, 21 Oct 2015 07:28:00 GMT"}}
	err := decisionsHTTPError(context.Background(), &http.Response{StatusCode: 429, Body: body, Header: headers}, nil, func() time.Time { return now })
	var de *DecisionsError
	if !errors.As(err, &de) || de.RetryAfterMS == nil || *de.RetryAfterMS != 59500 {
		t.Fatal("HTTP-date guidance did not use the parsing clock after reading the body")
	}
}

type productionClockBody struct {
	io.Reader
	now  *time.Time
	once bool
}

func (b *productionClockBody) Read(p []byte) (int, error) {
	if !b.once {
		b.once = true
		*b.now = b.now.Add(500 * time.Millisecond)
	}
	return b.Reader.Read(p)
}

func (b *productionClockBody) Close() error { return nil }

func TestDecisionsProductionDefaultTransportDeadlinePolicy(t *testing.T) {
	transport := decisionsDefaultHTTPTransport
	if transport.DialContext == nil || transport.TLSHandshakeTimeout != 0 || transport.ResponseHeaderTimeout != 0 {
		t.Fatal("built-in active I/O must use the total-call context, not earlier phase timeouts")
	}
	if transport.TLSClientConfig != nil && transport.TLSClientConfig.InsecureSkipVerify {
		t.Fatal("deadline hardening must preserve certificate verification")
	}
}

func TestDecisionsProductionRPCContextCancellation(t *testing.T) {
	for _, sdkDeadline := range []bool{false, true} {
		t.Run(fmt.Sprintf("sdk=%v", sdkDeadline), func(t *testing.T) {
			session, server := newSetToolsTestSession(t)
			caller, cancel := context.WithCancel(context.Background())
			defer cancel()
			timeout := time.Second
			if sdkDeadline {
				timeout = 50 * time.Millisecond
			}
			sends := 0
			transport := decisionsRoundTripFunc(func(req *http.Request) (*http.Response, error) {
				sends++
				return &http.Response{
					StatusCode: 200, Header: http.Header{}, Request: req,
					Body: io.NopCloser(strings.NewReader(`{"answers":[],"model":"m","usage":{}}`)),
				}, nil
			})
			done := make(chan error, 1)
			go func() {
				_, err := session.Decisions().Create(caller, DecisionsRequest{Model: "m"}, &DecisionsOptions{Timeout: &timeout, Transport: transport})
				done <- err
			}()
			request := server.expectRequest(t, "session.provider.getEndpoint")
			if !sdkDeadline {
				cancel()
			}
			select {
			case err := <-done:
				if sdkDeadline {
					var de *DecisionsError
					if !errors.As(err, &de) || de.Kind != DecisionsErrorKindTimeout || de.Cause != nil {
						t.Fatalf("SDK deadline=%v", err)
					}
				} else if err != context.Canceled {
					t.Fatalf("caller cancellation=%v", err)
				}
			case <-time.After(2 * time.Second):
				t.Fatal("the runtime RPC did not honor the work context")
			}
			if sends != 0 {
				t.Fatal("an expired endpoint lookup dispatched HTTP")
			}
			server.respondError(t, request.ID, -32603, "synthetic credentials discovered after cancellation")
			go func() {
				_, err := session.Decisions().Create(context.Background(), DecisionsRequest{Model: "m"}, &DecisionsOptions{Transport: transport})
				done <- err
			}()
			next := server.expectRequest(t, "session.provider.getEndpoint")
			server.respond(t, next.ID, map[string]any{"type": "openai", "baseUrl": "https://synthetic.example/v1", "headers": map[string]string{}})
			select {
			case err := <-done:
				if err != nil || sends != 1 {
					t.Fatalf("late RPC completion interfered with a later call: sends=%d error=%v", sends, err)
				}
			case <-time.After(2 * time.Second):
				t.Fatal("the runtime RPC did not recover after cancellation")
			}
		})
	}
}

type productionDiagnosticError struct {
	Message string
	RawURL  string
	Headers map[string]string
}

func (e *productionDiagnosticError) Error() string { return e.Message }
func (e *productionDiagnosticError) Unwrap() error { return syscall.ECONNRESET }

func TestDecisionsProductionSanitizesAllCauseFields(t *testing.T) {
	endpoint := &rpc.ProviderEndpoint{
		Type: rpc.ProviderEndpointTypeOpenai, BaseURL: "https://synthetic.example",
		APIKey: String("Diagnostic"), Headers: map[string]string{"api-key": "RESET", "X-Test": "synthetic-trace-secret"},
	}
	original := &productionDiagnosticError{
		Message: "Diagnostic RESET synthetic-trace-secret",
		RawURL:  "synthetic-unsafe-raw-attribute",
		Headers: map[string]string{"X-Raw": "synthetic-unsafe-raw-attribute"},
	}
	secrets := collectDecisionsSecrets(endpoint, endpoint.BaseURL)
	err := decisionsNetworkError("network failure", original, secrets)
	var cause *DecisionsNetworkCause
	if !errors.As(err, &cause) || cause.Name != "*copilot.production[REDACTED]Error" || cause.Code != "ECONN[REDACTED]" {
		t.Fatalf("cause fields not redacted: %#v", cause)
	}
	if errors.Is(err, original) || errors.Is(err, syscall.ECONNRESET) {
		t.Fatal("raw exception chain retained")
	}
	encoded, marshalErr := json.Marshal(err)
	if marshalErr != nil {
		t.Fatal(marshalErr)
	}
	for _, secret := range []string{"Diagnostic", "RESET", "synthetic-trace-secret", "synthetic-unsafe-raw-attribute"} {
		if strings.Contains(string(encoded), secret) || strings.Contains(fmt.Sprintf("%+v", err.Cause), secret) {
			t.Fatal("provider exception metadata was retained unsafely")
		}
	}
}

func TestDecisionsProductionSafeEndpointDiagnostics(t *testing.T) {
	for _, gated := range []bool{false, true} {
		message := "synthetic-unknown-endpoint-credential"
		if gated {
			message += decisionsGateEnv
		}
		original := errors.New(message)
		d := &SessionDecisions{resolve: func(context.Context, *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error) {
			return nil, original
		}}
		_, err := d.Create(context.Background(), DecisionsRequest{Model: "m"}, nil)
		var de *DecisionsError
		if !errors.As(err, &de) || de.Kind != DecisionsErrorKindEndpointUnavailable || errors.Is(err, original) {
			t.Fatalf("error=%v", err)
		}
		data, _ := json.Marshal(de)
		if strings.Contains(string(data), "synthetic-unknown-endpoint-credential") {
			t.Fatal("unknown endpoint credentials leaked through RPC diagnostics")
		}
	}
	for _, wire := range []string{
		`{"type":"synthetic-secret-provider","baseUrl":"https://h"}`,
		`{"type":"openai","transport":"synthetic-secret-transport","baseUrl":"https://h"}`,
		`{"type":"openai","baseUrl":"https://h","sessionToken":{"header":"X-Token","token":"T","model":"synthetic-secret-model"}}`,
	} {
		var endpoint rpc.ProviderEndpoint
		if err := json.Unmarshal([]byte(wire), &endpoint); err != nil {
			t.Fatal(err)
		}
		_, err := planDecisionsAttempt(&endpoint, "synthetic-secret-request-model")
		data, _ := json.Marshal(err)
		if err == nil || strings.Contains(string(data), "synthetic-secret") {
			t.Fatal("preflight must use fixed diagnostics")
		}
	}
}

func TestDecisionsProductionStrongAndHeuristicSecrets(t *testing.T) {
	for _, wire := range []string{
		`{"type":"openai","baseUrl":"https://h","apiKey":" X "}`,
		`{"type":"openai","baseUrl":"https://h","headers":{"Authorization":"Bearer X"}}`,
		`{"type":"openai","baseUrl":"https://h","headers":{"api-key":"X"}}`,
		`{"type":"openai","baseUrl":"https://h","sessionToken":{"header":"X-Token","token":"X"}}`,
		`{"type":"openai","baseUrl":"https://h","headers":{"x-token":"X"},"sessionToken":{"header":"X-Token","token":"synthetic-new"}}`,
		`{"type":"openai","baseUrl":"https://h","sessionToken":{"header":"Authorization","token":"Bearer X"}}`,
	} {
		var endpoint rpc.ProviderEndpoint
		if err := json.Unmarshal([]byte(wire), &endpoint); err != nil {
			t.Fatal(err)
		}
		secrets := collectDecisionsSecrets(&endpoint, endpoint.BaseURL)
		if got := redactDecisionsText("prefix:X:suffix", secrets); got != "prefix:[REDACTED]:suffix" {
			t.Errorf("short actual credential not protected: %q", got)
		}
	}
	endpoint := &rpc.ProviderEndpoint{Headers: map[string]string{"X-Other": "1234567", "X-Eight": "12345678", "X-Unicode": "🔑🔑🔑🔑abc"}}
	secrets := collectDecisionsSecrets(endpoint, "https://h?short=abcdefg&long=abcdefgh")
	if got := redactDecisionsText("1234567 12345678 abcdefg abcdefgh 🔑🔑🔑🔑abc", secrets); got != "1234567 [REDACTED] abcdefg [REDACTED] 🔑🔑🔑🔑abc" {
		t.Fatalf("non-auth heuristic changed: %q", got)
	}
}

func TestDecisionsProductionExactRedactionCase(t *testing.T) {
	for _, tc := range []struct {
		text, secret, want string
	}{
		{"%2F", "F", "%2[REDACTED]"},
		{"%2F%2f", "F%2F", "%2[REDACTED]"},
		{"AB%2f cd", "AB%2F", "[REDACTED] cd"},
		{"ab%2f cd", "AB%2F", "ab%2f cd"},
		{`x\u00E9x`, `\u00e9`, "x[REDACTED]x"},
		{"[REDACTED] XYZ", "XYZ", "[REDACTED] [REDACTED]"},
		{"REDACTED", "REDACTED", "[REDACTED]"},
	} {
		if got := redactDecisionsText(tc.text, []string{tc.secret}); got != tc.want {
			t.Errorf("redact(%q,%q)=%q; want %q", tc.text, tc.secret, got, tc.want)
		}
		secrets := collectDecisionsSecrets(&rpc.ProviderEndpoint{}, "https://h?sig=%FFcredential-0001")
		if got := redactDecisionsText("echo:\xffcredential-0001", secrets); got != "echo:[REDACTED]" {
			t.Fatal("lossy UTF-8 decoding exposed a known decoded query credential")
		}
		secret := "\ufffdcredential-0001"
		raw := secret + strings.Repeat("x", decisionsMaxErrorBodyBytes-len(secret)-1) + "\xffcredential-0001 LOOKAHEAD"
		if got := redactDecisionsText(raw, []string{secret}); strings.Contains(got, "credential") || strings.Contains(got, "LOOKAHEAD") {
			t.Fatal("invalid-UTF-8 lookahead escaped the original retention budget")
		}
	}
}

type productionCountingBody struct {
	io.Reader
	read, closed int
}

func (b *productionCountingBody) Read(p []byte) (int, error) {
	n, err := b.Reader.Read(p)
	b.read += n
	return n, err
}

func (b *productionCountingBody) Close() error { b.closed++; return nil }

func TestDecisionsProductionErrorRetentionBudget(t *testing.T) {
	secret := "synthetic-long-secret"
	secrets := decisionsSecretVariants(secret)
	for _, prefix := range []string{"", "\ufeff", secret, secret + secret, "é🔑" + secret} {
		t.Run(fmt.Sprintf("prefix_bytes=%d", len(prefix)), func(t *testing.T) {
			filler := strings.Repeat("x", decisionsMaxErrorBodyBytes-len(prefix)-3)
			raw := prefix + filler + secret + "LOOKAHEAD-MUST-NOT-BE-RETAINED"
			body := &productionCountingBody{Reader: strings.NewReader(raw)}
			err := decisionsHTTPError(context.Background(), &http.Response{StatusCode: 500, Body: body, Header: http.Header{}}, secrets, time.Now)
			var de *DecisionsError
			if !errors.As(err, &de) || !de.BodyTruncated || !utf8.ValidString(de.Body) || len(de.Body) > decisionsMaxErrorBodyBytes {
				t.Fatalf("invalid retained body: error=%v", err)
			}
			if strings.Contains(de.Body, "synthetic") || strings.Contains(de.Body, "LOOKAHEAD") {
				t.Fatal("redaction used lookahead as retained content")
			}
			if body.read > decisionsMaxErrorBodyBytes+maxSecretBytes(secrets)+1 || body.closed != 1 {
				t.Fatalf("read=%d close=%d", body.read, body.closed)
			}
		})
	}
}

func TestDecisionsProductionDiagnosticBudgets(t *testing.T) {
	message := strings.Repeat("Z", 20000)
	raw, _ := json.Marshal(map[string]any{"error": map[string]any{
		"type": "Z", "code": "Z", "param": "Z", "message": message,
	}})
	headers := http.Header{"X-Request-Id": {strings.Repeat("Z", 70000)}}
	err := decisionsHTTPError(context.Background(), &http.Response{StatusCode: 400, Body: io.NopCloser(strings.NewReader(string(raw))), Header: headers}, []string{"Z"}, time.Now)
	var de *DecisionsError
	if !errors.As(err, &de) || de.BodyTruncated {
		t.Fatal("redaction expansion must not mark original body truncated")
	}
	for _, text := range []string{de.Body, de.Message, *de.ErrorType, *de.ErrorCode, *de.ErrorParam, *de.RequestID} {
		if len(text) > decisionsMaxErrorBodyBytes || strings.Contains(text, "Z") || !utf8.ValidString(text) {
			t.Fatal("unsafe diagnostic output budget")
		}
	}
	cause := sanitizeDecisionsCause(errors.New(strings.Repeat("x", 70000)), nil)
	if len(cause.Message) != decisionsMaxErrorBodyBytes {
		t.Fatal("network diagnostic not bounded")
	}
}

func TestDecisionsProductionIntegerDomains(t *testing.T) {
	for _, raw := range []string{
		"-9223372036854775809", "9223372036854775808", "1e400", "1e-400", "true", "null", `"1"`,
		"1.00000000000000000000001", "0.99999999999999999999999",
		"9007199254740992.0", "-9007199254740992e0", "1e999999999999999999999",
	} {
		t.Run(raw, func(t *testing.T) {
			for _, key := range []string{"input_tokens", "output_tokens", "total_tokens"} {
				_, err := parseDecisionsResult([]byte(fmt.Sprintf(`{"answers":[],"model":"m","usage":{"%s":%s}}`, key, raw)))
				var de *DecisionsError
				if !errors.As(err, &de) || de.Kind != DecisionsErrorKindInvalidResponse {
					t.Errorf("%s=%s did not fail as invalidResponse", key, raw)
				}
			}
		})
	}
	for raw, want := range map[string]int64{
		"-9223372036854775808": -9223372036854775808,
		"9223372036854775807":  9223372036854775807,
		"9007199254740991.0":   9007199254740991,
		"-9007199254740991e0":  -9007199254740991,
		"-3.000":               -3, "300e-2": 3, "0e400": 0,
	} {
		for _, key := range []string{"cached_tokens", "cache_write_tokens", "reasoning_tokens", "value"} {
			obj := decisionsObject{key: json.RawMessage(raw)}
			if got, err := obj.integer(key); err != nil || got != want {
				t.Errorf("%s=%s got=%d err=%v", key, raw, got, err)
			}
		}
	}
	result, err := parseDecisionsResult([]byte("\ufeff" + `{"answers":[],"model":"m","usage":{}}`))
	if err != nil || result.Usage != (DecisionsUsage{}) {
		t.Fatal("BOM or missing count defaults changed")
	}
}

func TestDecisionsProductionURLGuards(t *testing.T) {
	for _, raw := range []string{
		"http:/h", "http:///v1", "https://", "https://h:", "https://h:-1", "https://h:+1",
		"https://h:65536", "https://[::1", "https://[not-ip]/", "https://::1/v1",
		"https://h..example/", "https://-h/", "https://h-/", "https://h/%", "https://h?x=%z0",
		"https://h#%0", "https://h/\x7f", "https://h/\x01", "https://h/a b", "https://h/é",
		"https://h\\@other/", "https://user@h/", "https://@h/", "https://h\t/v1",
	} {
		if _, err := buildDecisionsURL(rpc.ProviderEndpointTypeOpenai, raw); err == nil {
			t.Errorf("accepted invalid URI %q", raw)
		}
	}
	for raw, want := range map[string]string{
		"HTTP://LOCALHOST:0/v1":                   "http://localhost:0/v1/decisions",
		"https://h:65535/v1":                      "https://h:65535/v1/decisions",
		"https://h:00443/v1?":                     "https://h/v1/decisions",
		"http://[2001:DB8::1]:80/v1":              "http://[2001:db8::1]/v1/decisions",
		"https://h/a//b/%2e/../v1?x=%2F&x=2#gone": "https://h/a//v1/decisions?x=%2F&x=2",
		"https://xn--bcher-kva.example/v1":        "https://xn--bcher-kva.example/v1/decisions",
	} {
		if got, err := buildDecisionsURL(rpc.ProviderEndpointTypeOpenai, raw); err != nil || got != want {
			t.Errorf("URL=%q err=%v, want %q", got, err, want)
		}
	}
}

func TestDecisionsProductionHeaderGuards(t *testing.T) {
	for c := 0; c <= 0x7f; c++ {
		if c >= 0x20 && c != 0x7f || c == '\t' {
			continue
		}
		if _, err := checkDecisionsHeader("X-Synthetic", "a"+string(rune(c))+"b"); err == nil {
			t.Errorf("accepted control U+%04X", c)
		}
	}
	for _, value := range []string{"\xed\xa0\x80", "\xff", "\xc0\xaf"} {
		if _, err := checkDecisionsHeader("X-Synthetic", value); err == nil {
			t.Error("accepted invalid UTF-8/surrogate header")
		}
	}
	if got, err := checkDecisionsHeader("X-Synthetic", " \té🔑\u00a0\t "); err != nil || got != "é🔑\u00a0" {
		t.Errorf("valid scalar header or HTTP-only trim changed: %q, %v", got, err)
	}
	endpoint := &rpc.ProviderEndpoint{Type: rpc.ProviderEndpointTypeOpenai, BaseURL: "https://h", APIKey: String("\xed\xa0\x80"), Headers: map[string]string{"Authorization": "synthetic-header"}}
	if _, err := planDecisionsAttempt(endpoint, "m"); err == nil {
		t.Fatal("shadowed malformed credential reached secret encoding")
	}
}

func TestDecisionsProductionRetryHintEdges(t *testing.T) {
	now := time.Unix(1445412420, 1)
	for raw, want := range map[string]int64{
		"9007199254740991":             decisionsMaxSafeInteger,
		"9007199254740990.000000001":   decisionsMaxSafeInteger,
		"000000000000000000000001.001": 2,
	} {
		if got := decisionsRetryAfter(http.Header{"Retry-After-Ms": {raw}}, now); got == nil || *got != want {
			t.Errorf("milliseconds %s got=%v; want %d", raw, got, want)
		}
	}
	for _, raw := range []string{"1.", ".1", "1,2", "+1", "-0", "1e0", "Infinity", "9007199254740991.1", "1\n", "\u00a01"} {
		if got := decisionsRetryAfter(http.Header{"Retry-After-Ms": {raw}}, now); got != nil {
			t.Errorf("accepted invalid hint %q", raw)
		}
	}
	for raw, want := range map[string]int64{
		"9007199254740.991":             decisionsMaxSafeInteger,
		"Wed, 21 Oct 2015 07:28:00 GMT": 60000,
	} {
		if got := decisionsRetryAfter(http.Header{"Retry-After": {raw}}, now); got == nil || *got != want {
			t.Errorf("seconds/date %s got=%v; want %d", raw, got, want)
		}
	}
	if got := decisionsRetryAfter(http.Header{"Retry-After": {"Thu, 21 Oct 2015 07:28:00 GMT"}}, now); got != nil {
		t.Fatal("noncanonical weekday accepted")
	}
}
