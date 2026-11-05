package copilot_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"reflect"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	copilot "github.com/github/copilot-sdk/go"
	"github.com/github/copilot-sdk/go/rpc"
)

type decisionsProductionVector struct {
	Name               string            `json:"name"`
	APIKey             string            `json:"apiKey"`
	Status             int               `json:"status"`
	Headers            map[string]string `json:"headers"`
	Body               string            `json:"body"`
	ExpectedRequestID  *string           `json:"expectedRequestId"`
	ExpectedErrorType  *string           `json:"expectedErrorType"`
	ExpectedErrorCode  *string           `json:"expectedErrorCode"`
	NowEpochMS         int64             `json:"nowEpochMs"`
	ExpectedMS         *int64            `json:"expectedMs"`
	Target             string            `json:"target"`
	JSONValue          string            `json:"jsonValue"`
	ExpectedInteger    string            `json:"expectedInteger"`
	ExpectedNumber     *float64          `json:"expectedNumber"`
	ExpectedError      string            `json:"expectedError"`
	SDKErrors          map[string]string `json:"sdkErrors"`
	ExpectedResolves   int               `json:"expectedResolves"`
	ExpectedSends      int               `json:"expectedSends"`
	ExpectedBodyClosed bool              `json:"expectedBodyClosed"`
	Stage              string            `json:"stage"`
	TimeoutMS          int64             `json:"timeoutMs"`
}

func loadDecisionsProductionVectors(t *testing.T, group string) []decisionsProductionVector {
	t.Helper()
	var groups map[string][]decisionsProductionVector
	if err := json.Unmarshal(decisionsFixture(t, "production-vectors.json"), &groups); err != nil {
		t.Fatal(err)
	}
	if len(groups[group]) == 0 {
		t.Fatalf("missing production vector group %s", group)
	}
	return groups[group]
}

type decisionsCensusCase struct {
	File   string `json:"file"`
	Group  string `json:"group"`
	Name   string `json:"name"`
	Status string `json:"status"`
}

var decisionsCensus = struct {
	sync.Mutex
	cases map[string]decisionsCensusCase
}{cases: make(map[string]decisionsCensusCase)}

func recordDecisionsVector(t *testing.T, file, group, name string) {
	t.Helper()
	t.Cleanup(func() {
		path := os.Getenv("COPILOT_DECISIONS_VECTOR_CENSUS")
		if path == "" || t.Failed() || t.Skipped() {
			return
		}
		decisionsCensus.Lock()
		defer decisionsCensus.Unlock()
		key := file + "/" + group + "/" + name
		if _, exists := decisionsCensus.cases[key]; exists {
			t.Fatalf("vector asserted more than once: %s", key)
		}
		decisionsCensus.cases[key] = decisionsCensusCase{file, group, name, "passed"}
		census := struct {
			SDK           string                `json:"sdk"`
			SchemaVersion int                   `json:"schemaVersion"`
			Cases         []decisionsCensusCase `json:"cases"`
		}{SDK: "go", SchemaVersion: 1}
		keys := make([]string, 0, len(decisionsCensus.cases))
		for k := range decisionsCensus.cases {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			census.Cases = append(census.Cases, decisionsCensus.cases[k])
		}
		data, err := json.MarshalIndent(census, "", "  ")
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, append(data, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	})
}

type productionDecisionsBody struct {
	reader io.Reader
	closed bool
}

func (b *productionDecisionsBody) Read(p []byte) (int, error) { return b.reader.Read(p) }
func (b *productionDecisionsBody) Close() error {
	b.closed = true
	return nil
}

func TestDecisionsProductionAuthBodyFailure(t *testing.T) {
	for _, v := range loadDecisionsProductionVectors(t, "authBodyFailure") {
		t.Run(v.Name, func(t *testing.T) {
			recordDecisionsVector(t, "production-vectors.json", "authBodyFailure", v.Name)
			body := &productionDecisionsBody{reader: &failingBody{}}
			next := openaiDecisionsEndpoint()
			next.APIKey = copilot.String("synthetic-refreshed-credential")
			decisions, resolver := decisionsWith(openaiDecisionsEndpoint(), next)
			transport := newFakeDecisionsTransport(
				decisionsResponse(v.Status, body, nil),
				jsonDecisionsResponse(200, okDecisionsBody(t)),
			)
			result, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
			if result != nil {
				t.Error("unreadable authentication response unexpectedly became success")
			}
			var de *copilot.DecisionsError
			if !errors.As(err, &de) || string(de.Kind) != v.ExpectedError {
				t.Errorf("error = %v; want %s", err, v.ExpectedError)
			}
			if len(resolver.seen) != v.ExpectedResolves || len(transport.calls) != v.ExpectedSends || body.closed != v.ExpectedBodyClosed {
				t.Errorf("resolves=%d sends=%d closed=%v; want resolves=%d sends=%d closed=%v",
					len(resolver.seen), len(transport.calls), body.closed, v.ExpectedResolves, v.ExpectedSends, v.ExpectedBodyClosed)
			}
		})
	}
}

func TestDecisionsProductionMetadata(t *testing.T) {
	for _, v := range loadDecisionsProductionVectors(t, "metadata") {
		t.Run(v.Name, func(t *testing.T) {
			recordDecisionsVector(t, "production-vectors.json", "metadata", v.Name)
			endpoint := openaiDecisionsEndpoint()
			endpoint.APIKey = &v.APIKey
			decisions, resolver := decisionsWith(endpoint)
			body := &productionDecisionsBody{reader: strings.NewReader(v.Body)}
			transport := newFakeDecisionsTransport(decisionsResponse(v.Status, body, v.Headers))
			_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
			de := asDecisionsError(t, err)
			if de.Kind != copilot.DecisionsErrorKindHTTP || de.Status != v.Status {
				t.Fatalf("kind=%s status=%d", de.Kind, de.Status)
			}
			if !reflect.DeepEqual(de.RequestID, v.ExpectedRequestID) {
				t.Errorf("request ID was not correctly sanitized or omitted")
			}
			if v.ExpectedErrorType != nil && !reflect.DeepEqual(de.ErrorType, v.ExpectedErrorType) {
				t.Error("error type was not parsed")
			}
			if v.ExpectedErrorCode != nil && !reflect.DeepEqual(de.ErrorCode, v.ExpectedErrorCode) {
				t.Error("error code was not parsed")
			}
			if len(resolver.seen) != 1 || len(transport.calls) != 1 || !body.closed {
				t.Error("metadata error did not close the single response")
			}
		})
	}
}

func TestDecisionsProductionRetryAfter(t *testing.T) {
	for _, v := range loadDecisionsProductionVectors(t, "retryAfter") {
		t.Run(v.Name, func(t *testing.T) {
			recordDecisionsVector(t, "production-vectors.json", "retryAfter", v.Name)
			now := time.UnixMilli(v.NowEpochMS)
			headers := http.Header{}
			for key, value := range v.Headers {
				headers.Set(key, value)
			}
			if parsed := copilot.DecisionsRetryAfterForTest(headers, now); !reflect.DeepEqual(parsed, v.ExpectedMS) {
				t.Fatalf("parser delay=%v; expected=%v", parsed, v.ExpectedMS)
			}
			decisions, resolver := decisionsWith(openaiDecisionsEndpoint())
			copilot.SetDecisionsNowForTest(decisions, now)
			body := &productionDecisionsBody{reader: strings.NewReader(`{"error":{"message":"synthetic rate limit"}}`)}
			transport := newFakeDecisionsTransport(decisionsResponse(429, body, v.Headers))
			_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
			de := asDecisionsError(t, err)
			if de.Kind != copilot.DecisionsErrorKindHTTP || de.Status != 429 || !reflect.DeepEqual(de.RetryAfterMS, v.ExpectedMS) {
				t.Fatalf("public HTTP error has incorrect retry guidance: %+v", de)
			}
			if len(resolver.seen) != 1 || len(transport.calls) != 1 || !body.closed {
				t.Error("advisory retry guidance must not cause a retry or retain the body")
			}
		})
	}
}

// Close is cooperative even when Read does not observe the request context itself.
type blockingProductionDecisionsBody struct {
	closed chan struct{}
	once   sync.Once
}

func (b *blockingProductionDecisionsBody) Read([]byte) (int, error) {
	<-b.closed
	return 0, errors.New("synthetic discarded body failure")
}

func (b *blockingProductionDecisionsBody) Close() error {
	b.once.Do(func() { close(b.closed) })
	return nil
}

func (b *blockingProductionDecisionsBody) isClosed() bool {
	select {
	case <-b.closed:
		return true
	default:
		return false
	}
}

func TestDecisionsProductionDeadline(t *testing.T) {
	for _, v := range loadDecisionsProductionVectors(t, "deadline") {
		t.Run(v.Name, func(t *testing.T) {
			recordDecisionsVector(t, "production-vectors.json", "deadline", v.Name)
			resolves, sends := 0, 0
			var workContexts []context.Context
			decisions := copilot.NewSessionDecisionsForTest(func(ctx context.Context, _ *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error) {
				resolves++
				workContexts = append(workContexts, ctx)
				if v.Stage == "endpoint" || (v.Stage == "refresh" && resolves == 2) {
					<-ctx.Done()
					return nil, errors.New("synthetic discarded RPC credentials")
				}
				endpoint := openaiDecisionsEndpoint()
				return &endpoint, nil
			})
			blocked := &blockingProductionDecisionsBody{closed: make(chan struct{})}
			readable := &productionDecisionsBody{reader: strings.NewReader("{}")}
			transport := newFakeDecisionsTransport(func(req *http.Request) (*http.Response, error) {
				sends++
				workContexts = append(workContexts, req.Context())
				if v.Stage == "headers" {
					<-req.Context().Done()
					return nil, errors.New("synthetic discarded transport credentials")
				}
				status := v.Status
				if status == 0 {
					status = 200
				}
				var body io.ReadCloser = blocked
				if v.Stage == "refresh" {
					body = readable
				}
				return decisionsResponse(status, body, nil)(req)
			})
			timeout := time.Duration(v.TimeoutMS) * time.Millisecond
			start := time.Now()
			result, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport, Timeout: &timeout})
			de := asDecisionsError(t, err)
			if result != nil || string(de.Kind) != v.ExpectedError || de.Cause != nil || de.Body != "" || de.Status != 0 {
				t.Fatalf("timeout did not discard provider diagnostics: %+v", de)
			}
			if resolves != v.ExpectedResolves || sends != v.ExpectedSends {
				t.Fatalf("resolves=%d sends=%d; expected=%d,%d", resolves, sends, v.ExpectedResolves, v.ExpectedSends)
			}
			if (v.Stage == "successBody" || v.Stage == "errorBody") && !blocked.isClosed() {
				t.Fatal("timed-out body was not closed")
			}
			if v.Stage == "refresh" && !readable.closed {
				t.Fatal("original body must close before a timed-out refresh")
			}
			for _, ctx := range workContexts {
				if ctx.Err() != context.DeadlineExceeded {
					t.Fatal("endpoint and HTTP work must share the expired deadline")
				}
			}
			if elapsed := time.Since(start); elapsed > 3*time.Second {
				t.Fatalf("deadline did not stop cooperative work promptly: %v", elapsed)
			}
		})
	}
}

// RawMessage keeps the exact numeric token intact, including int64 endpoints.
func replaceDecisionsJSONPath(t *testing.T, raw json.RawMessage, path []string, value json.RawMessage) json.RawMessage {
	t.Helper()
	if len(path) == 0 {
		return value
	}
	var node any
	if strings.HasPrefix(strings.TrimSpace(string(raw)), "[") {
		var items []json.RawMessage
		if err := json.Unmarshal(raw, &items); err != nil {
			t.Fatal(err)
		}
		i, err := strconv.Atoi(path[0])
		if err != nil || i < 0 || i >= len(items) {
			t.Fatalf("invalid array index %q", path[0])
		}
		items[i] = replaceDecisionsJSONPath(t, items[i], path[1:], value)
		node = items
	} else {
		var obj map[string]json.RawMessage
		if err := json.Unmarshal(raw, &obj); err != nil {
			t.Fatal(err)
		}
		if _, ok := obj[path[0]]; !ok {
			t.Fatalf("missing response path %q", path[0])
		}
		obj[path[0]] = replaceDecisionsJSONPath(t, obj[path[0]], path[1:], value)
		node = obj
	}
	out, err := json.Marshal(node)
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func decisionsResultInteger(t *testing.T, result *copilot.DecisionsResult, target string) string {
	t.Helper()
	var value int64
	switch target {
	case "usage.input_tokens":
		value = result.Usage.InputTokens
	case "usage.total_tokens":
		value = result.Usage.TotalTokens
	case "usage.input_tokens_details.cached_tokens":
		value = result.Usage.InputTokensDetails.CachedTokens
	case "answers.3.probabilities.0.value":
		value = int64(result.Answers[3].(copilot.DecisionsScoreAnswer).Probabilities[0].Value)
	default:
		t.Fatalf("unsupported numeric target %s", target)
	}
	return strconv.FormatInt(value, 10)
}

func TestDecisionsProductionResponseNumbers(t *testing.T) {
	for _, v := range loadDecisionsProductionVectors(t, "responseNumbers") {
		t.Run(v.Name, func(t *testing.T) {
			recordDecisionsVector(t, "production-vectors.json", "responseNumbers", v.Name)
			raw := replaceDecisionsJSONPath(t, decisionsFixture(t, "response.full.json"), strings.Split(v.Target, "."), json.RawMessage(v.JSONValue))
			body := &productionDecisionsBody{reader: strings.NewReader(string(raw))}
			transport := newFakeDecisionsTransport(decisionsResponse(200, body, nil))
			decisions, resolver := decisionsWith(openaiDecisionsEndpoint())
			result, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
			expectedError := v.ExpectedError
			if override, ok := v.SDKErrors["go"]; ok {
				expectedError = override
			}
			if expectedError != "" {
				if de := asDecisionsError(t, err); string(de.Kind) != expectedError {
					t.Fatalf("kind=%s, want %s", de.Kind, expectedError)
				}
			} else {
				if err != nil {
					t.Fatal(err)
				}
				if v.ExpectedInteger != "" {
					if got := decisionsResultInteger(t, result, v.Target); got != v.ExpectedInteger {
						t.Fatalf("integer=%s, want %s", got, v.ExpectedInteger)
					}
				}
				if v.ExpectedNumber != nil && result.Answers[3].(copilot.DecisionsScoreAnswer).Score != *v.ExpectedNumber {
					t.Error("fractional score was not preserved")
				}
			}
			if len(resolver.seen) != 1 || len(transport.calls) != 1 || !body.closed {
				t.Error("numeric response did not close the single response")
			}
		})
	}
}

func TestDecisionsProductionOriginalRedaction(t *testing.T) {
	for _, tc := range []struct {
		name, credential, other, body, expected string
	}{
		{"overlap", "synthetic-abcdefgh", "efgh-tail", "synthetic-abcdefgh-tail", "[REDACTED]"},
		{"self overlap", "abababab", "", "abababababab", "[REDACTED]"},
		{"short credential", "XYZ", "", "before XYZ after", "before [REDACTED] after"},
		{"original budget", "synthetic-secret", "", "synthetic-secret" + strings.Repeat("x", 65536-len("synthetic-secret")) + "LOOKAHEAD-ONLY", "[REDACTED]" + strings.Repeat("x", 65536-len("synthetic-secret"))},
	} {
		t.Run(tc.name, func(t *testing.T) {
			endpoint := openaiDecisionsEndpoint()
			endpoint.APIKey = &tc.credential
			endpoint.Headers = map[string]string{"X-Synthetic": tc.other}
			decisions, _ := decisionsWith(endpoint)
			transport := newFakeDecisionsTransport(jsonDecisionsResponse(400, tc.body))
			_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
			de := asDecisionsError(t, err)
			if de.Body != tc.expected {
				t.Errorf("redaction mismatch: actual %d bytes, expected %d bytes (%s)", len(de.Body), len(tc.expected), tc.name)
			}
			if de.BodyTruncated != (len(tc.body) > 65536) {
				t.Error("bodyTruncated must reflect original body overflow")
			}
		})
	}
}

func TestDecisionsProductionNumericAdapterPreservesLexemes(t *testing.T) {
	for _, value := range []string{"9223372036854775807", "-9223372036854775808", "3.0", "3e0", "1e400"} {
		got := replaceDecisionsJSONPath(t, json.RawMessage(`{"a":[{"b":0}]}`), []string{"a", "0", "b"}, json.RawMessage(value))
		if string(got) != fmt.Sprintf(`{"a":[{"b":%s}]}`, value) {
			t.Fatalf("raw numeric lexeme changed: %s", got)
		}
	}
}

func TestDecisionsProductionTimeoutOptions(t *testing.T) {
	zero, negative, custom := time.Duration(0), -time.Nanosecond, time.Minute
	for _, tc := range []struct {
		name    string
		timeout *time.Duration
		want    time.Duration
	}{
		{"default", nil, 600 * time.Second},
		{"disabled", &zero, 0},
		{"custom", &custom, time.Minute},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var work context.Context
			decisions := copilot.NewSessionDecisionsForTest(func(ctx context.Context, _ *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error) {
				work = ctx
				deadline, has := ctx.Deadline()
				if tc.want == 0 {
					if has {
						t.Error("disabled timeout installed a deadline")
					}
				} else if left := time.Until(deadline); !has || left > tc.want || left < tc.want-time.Second {
					t.Errorf("unexpected default/custom deadline: %v", left)
				}
				endpoint := openaiDecisionsEndpoint()
				return &endpoint, nil
			})
			transport := newFakeDecisionsTransport(jsonDecisionsResponse(200, okDecisionsBody(t)))
			if _, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport, Timeout: tc.timeout}); err != nil {
				t.Fatal(err)
			}
			if tc.want > 0 && work.Err() != context.Canceled {
				t.Error("completed call must release its owned deadline")
			}
		})
	}
	for _, missingModel := range []bool{false, true} {
		decisions, resolver := decisionsWith(openaiDecisionsEndpoint())
		request := decisionsMinimalRequest
		if missingModel {
			request.Model = ""
		}
		_, err := decisions.Create(context.Background(), request, &copilot.DecisionsOptions{Timeout: &negative})
		var de *copilot.DecisionsError
		if err == nil || errors.As(err, &de) || len(resolver.seen) != 0 {
			t.Fatalf("invalid timeout must fail as a native argument error before I/O: %v", err)
		}
		if missingModel && !strings.Contains(err.Error(), "Model is required") {
			t.Error("missing-model validation must come first")
		}
	}
}

func TestDecisionsProductionCallerContextPrecedence(t *testing.T) {
	for _, stage := range []string{"endpoint", "headers", "successBody", "errorBody", "refresh"} {
		for _, callerDeadline := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/deadline=%v", stage, callerDeadline), func(t *testing.T) {
				caller, cancel := context.WithCancel(context.Background())
				want := context.Canceled
				if callerDeadline {
					cancel()
					caller, cancel = context.WithTimeout(context.Background(), 30*time.Millisecond)
					want = context.DeadlineExceeded
				}
				defer cancel()
				resolves, sends := 0, 0
				var contexts []context.Context
				stopWork := func(ctx context.Context) {
					if !callerDeadline {
						cancel()
					}
					<-ctx.Done()
				}
				decisions := copilot.NewSessionDecisionsForTest(func(ctx context.Context, _ *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error) {
					resolves++
					contexts = append(contexts, ctx)
					if stage == "endpoint" || (stage == "refresh" && resolves == 2) {
						stopWork(ctx)
						return nil, errors.New("synthetic discarded RPC secret")
					}
					endpoint := openaiDecisionsEndpoint()
					return &endpoint, nil
				})
				var bodies []*productionDecisionsBody
				transport := newFakeDecisionsTransport(func(req *http.Request) (*http.Response, error) {
					sends++
					contexts = append(contexts, req.Context())
					if stage == "headers" {
						stopWork(req.Context())
						return nil, errors.New("synthetic discarded transport secret")
					}
					status := 200
					var reader io.Reader = strings.NewReader("{}")
					if stage == "errorBody" || stage == "refresh" {
						status = 401
					}
					if stage != "refresh" {
						reader = productionDecisionsReaderFunc(func([]byte) (int, error) {
							stopWork(req.Context())
							return 0, errors.New("synthetic discarded body secret")
						})
					}
					body := &productionDecisionsBody{reader: reader}
					bodies = append(bodies, body)
					return decisionsResponse(status, body, nil)(req)
				})
				timeout := time.Second
				_, err := decisions.Create(caller, decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport, Timeout: &timeout})
				assertDecisionsCancellation(t, err, want)
				wantResolves, wantSends := 1, 1
				if stage == "endpoint" {
					wantSends = 0
				}
				if stage == "refresh" {
					wantResolves = 2
				}
				if resolves != wantResolves || sends != wantSends {
					t.Fatalf("unexpected replay: resolves=%d sends=%d", resolves, sends)
				}
				for _, body := range bodies {
					if !body.closed {
						t.Error("cancelled body was not closed")
					}
				}
				for _, ctx := range contexts {
					if ctx.Err() != want {
						t.Errorf("cooperative work context=%v, want %v", ctx.Err(), want)
					}
				}
			})
		}
	}
	t.Run("both observed", func(t *testing.T) {
		caller, cancel := context.WithCancel(context.Background())
		defer cancel()
		timeout := 10 * time.Millisecond
		decisions := copilot.NewSessionDecisionsForTest(func(ctx context.Context, _ *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error) {
			<-ctx.Done()
			cancel()
			return nil, errors.New("synthetic discarded secret")
		})
		_, err := decisions.Create(caller, decisionsMinimalRequest, &copilot.DecisionsOptions{Timeout: &timeout})
		assertDecisionsCancellation(t, err, context.Canceled)
	})
}

type productionDecisionsReaderFunc func([]byte) (int, error)

func (f productionDecisionsReaderFunc) Read(p []byte) (int, error) { return f(p) }

func TestDecisionsProductionDeadlineNotResetOnReplay(t *testing.T) {
	var deadline time.Time
	resolves, sends := 0, 0
	decisions := copilot.NewSessionDecisionsForTest(func(ctx context.Context, _ *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error) {
		resolves++
		got, ok := ctx.Deadline()
		if resolves == 1 {
			deadline = got
		}
		if !ok || !got.Equal(deadline) {
			t.Error("credential refresh reset the deadline")
		}
		endpoint := openaiDecisionsEndpoint()
		endpoint.APIKey = copilot.String(fmt.Sprintf("synthetic-rotation-%d", resolves))
		return &endpoint, nil
	})
	body := &blockingProductionDecisionsBody{closed: make(chan struct{})}
	transport := newFakeDecisionsTransport(func(req *http.Request) (*http.Response, error) {
		sends++
		got, ok := req.Context().Deadline()
		if !ok || !got.Equal(deadline) {
			t.Error("HTTP attempt reset the deadline")
		}
		if sends == 1 {
			time.Sleep(25 * time.Millisecond)
			return jsonDecisionsResponse(401, "{}")(req)
		}
		return decisionsResponse(200, body, nil)(req)
	})
	timeout := 100 * time.Millisecond
	_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport, Timeout: &timeout})
	if de := asDecisionsError(t, err); de.Kind != copilot.DecisionsErrorKindTimeout || de.Cause != nil {
		t.Fatalf("error=%+v", de)
	}
	if resolves != 2 || sends != 2 || !body.isClosed() {
		t.Fatalf("resolves=%d sends=%d closed=%v", resolves, sends, body.isClosed())
	}
}

type productionDecisionsMarshalBarrier struct{ done <-chan struct{} }

func (b *productionDecisionsMarshalBarrier) MarshalJSON() ([]byte, error) {
	<-b.done
	return []byte("{}"), nil
}

func TestDecisionsProductionDeadlineAfterSerialization(t *testing.T) {
	barrier := &productionDecisionsMarshalBarrier{}
	decisions := copilot.NewSessionDecisionsForTest(func(ctx context.Context, _ *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error) {
		barrier.done = ctx.Done()
		endpoint := openaiDecisionsEndpoint()
		return &endpoint, nil
	})
	request := decisionsMinimalRequest
	request.InputItems = []copilot.DecisionsInputItem{copilot.DecisionsUserMessage{
		ContentParts: []copilot.DecisionsUserContentPart{copilot.DecisionsInputJSON{Data: barrier}},
	}}
	transport := newFakeDecisionsTransport(jsonDecisionsResponse(200, okDecisionsBody(t)))
	timeout := 10 * time.Millisecond
	_, err := decisions.Create(context.Background(), request, &copilot.DecisionsOptions{Transport: transport, Timeout: &timeout})
	if de := asDecisionsError(t, err); de.Kind != copilot.DecisionsErrorKindTimeout || len(transport.calls) != 0 {
		t.Fatalf("serialization expiry must prevent HTTP: error=%v sends=%d", de.Kind, len(transport.calls))
	}
}

func TestDecisionsProductionClosesEveryResponse(t *testing.T) {
	for _, tc := range []struct {
		name, content string
		status        int
		fail          bool
		want          copilot.DecisionsErrorKind
	}{
		{"success", `{"answers":[],"model":"m","usage":{}}`, 200, false, ""},
		{"bad JSON", "{", 200, false, copilot.DecisionsErrorKindInvalidResponse},
		{"bad schema", "{}", 200, false, copilot.DecisionsErrorKindInvalidResponse},
		{"oversized success", strings.Repeat(" ", copilot.DecisionsMaxResponseBytesForTest+1), 200, false, copilot.DecisionsErrorKindInvalidResponse},
		{"oversized error", strings.Repeat("x", copilot.DecisionsMaxErrorBodyBytesForTest+100), 500, false, copilot.DecisionsErrorKindHTTP},
		{"read failure", "", 200, true, copilot.DecisionsErrorKindNetwork},
		{"auth read failure", "", 401, true, copilot.DecisionsErrorKindNetwork},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var reader io.Reader = strings.NewReader(tc.content)
			if tc.fail {
				reader = &failingBody{}
			}
			body := &productionDecisionsBody{reader: reader}
			decisions, _ := decisionsWith(openaiDecisionsEndpoint())
			transport := newFakeDecisionsTransport(decisionsResponse(tc.status, body, nil))
			_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
			if tc.want == "" {
				if err != nil {
					t.Fatal(err)
				}
			} else if de := asDecisionsError(t, err); de.Kind != tc.want {
				t.Errorf("kind=%s; want %s", de.Kind, tc.want)
			}
			if !body.closed || len(transport.calls) != 1 {
				t.Error("single response must close, without generic retries")
			}
		})
	}
	t.Run("transport returns response and error", func(t *testing.T) {
		body := &productionDecisionsBody{reader: strings.NewReader("discarded")}
		transport := newFakeDecisionsTransport(func(req *http.Request) (*http.Response, error) {
			response, _ := decisionsResponse(200, body, nil)(req)
			return response, errors.New("synthetic transport failure")
		})
		decisions, _ := decisionsWith(openaiDecisionsEndpoint())
		_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
		if de := asDecisionsError(t, err); de.Kind != copilot.DecisionsErrorKindNetwork || !body.closed {
			t.Errorf("response returned alongside transport error was not closed: %v", de.Kind)
		}
	})
}

func TestDecisionsProductionIndependentTransportTimeoutIsNetwork(t *testing.T) {
	original := &url.Error{Op: "POST", URL: "https://synthetic.invalid/secret", Err: syscall.ETIMEDOUT}
	transport := newFakeDecisionsTransport(func(*http.Request) (*http.Response, error) { return nil, original })
	decisions, _ := decisionsWith(openaiDecisionsEndpoint())
	_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
	de := asDecisionsError(t, err)
	if de.Kind != copilot.DecisionsErrorKindNetwork || errors.Is(err, original) {
		t.Fatalf("independent transport timeout must remain sanitized network failure: %+v", de)
	}
}

func TestDecisionsProductionLocalSlowBodies(t *testing.T) {
	for _, status := range []int{200, 401, 403, 500} {
		for _, cancelCaller := range []bool{false, true} {
			t.Run(fmt.Sprintf("%d/caller=%v", status, cancelCaller), func(t *testing.T) {
				var hits atomic.Int32
				aborted := make(chan struct{})
				caller, cancel := context.WithCancel(context.Background())
				defer cancel()
				server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					hits.Add(1)
					_, _ = io.Copy(io.Discard, r.Body)
					w.WriteHeader(status)
					_, _ = io.WriteString(w, "{")
					w.(http.Flusher).Flush()
					if cancelCaller {
						cancel()
					}
					<-r.Context().Done()
					close(aborted)
				}))
				defer server.Close()
				endpoint := openaiDecisionsEndpoint()
				endpoint.BaseURL = server.URL + "/v1"
				decisions, resolver := decisionsWith(endpoint)
				timeout := 100 * time.Millisecond
				_, err := decisions.Create(caller, decisionsMinimalRequest, &copilot.DecisionsOptions{Timeout: &timeout})
				if cancelCaller {
					assertDecisionsCancellation(t, err, context.Canceled)
				} else if de := asDecisionsError(t, err); de.Kind != copilot.DecisionsErrorKindTimeout || de.Cause != nil {
					t.Fatalf("error=%+v", de)
				}
				select {
				case <-aborted:
				case <-time.After(2 * time.Second):
					server.CloseClientConnections()
					t.Fatal("server did not observe response cleanup")
				}
				if hits.Load() != 1 || len(resolver.seen) != 1 {
					t.Fatal("slow authentication body must not refresh or replay")
				}
			})
		}
	}
}

func TestDecisionsProductionLocalAuthRefresh(t *testing.T) {
	var mu sync.Mutex
	var auth []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		mu.Lock()
		defer mu.Unlock()
		auth = append(auth, r.Header.Get("Authorization"))
		if len(auth) == 1 {
			w.WriteHeader(401)
			_, _ = io.WriteString(w, "{}")
			return
		}
		_, _ = io.WriteString(w, `{"answers":[],"model":"m","usage":{}}`)
	}))
	defer server.Close()
	first, second := openaiDecisionsEndpoint(), openaiDecisionsEndpoint()
	first.BaseURL, second.BaseURL = server.URL, server.URL
	first.APIKey, second.APIKey = copilot.String("synthetic-first"), copilot.String("synthetic-second")
	decisions, resolver := decisionsWith(first, second)
	if _, err := decisions.Create(context.Background(), decisionsMinimalRequest, nil); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(resolver.seen) != 2 || !reflect.DeepEqual(auth, []string{"Bearer synthetic-first", "Bearer synthetic-second"}) {
		t.Error("local HTTP did not perform exactly one effective-credential replay")
	}
}

func TestDecisionsProductionKeepsOldCredentialsAfterRefresh(t *testing.T) {
	for _, network := range []bool{false, true} {
		t.Run(fmt.Sprintf("network=%v", network), func(t *testing.T) {
			first, second := openaiDecisionsEndpoint(), openaiDecisionsEndpoint()
			first.APIKey, second.APIKey = copilot.String("synthetic-old-credential"), copilot.String("synthetic-new-credential")
			decisions, resolver := decisionsWith(first, second)
			transport := newFakeDecisionsTransport(
				jsonDecisionsResponse(401, "{}"),
				func(req *http.Request) (*http.Response, error) {
					if network {
						return nil, errors.New("synthetic-old-credential synthetic-new-credential")
					}
					return jsonDecisionsResponse(403,
						`{"error":{"message":"synthetic-old-credential synthetic-new-credential","type":"synthetic-old-credential","code":"synthetic-new-credential","param":"synthetic-old-credential"}}`,
						"x-request-id", "synthetic-old-credential")(req)
				},
			)
			_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
			de := asDecisionsError(t, err)
			wantKind := copilot.DecisionsErrorKindHTTP
			if network {
				wantKind = copilot.DecisionsErrorKindNetwork
			}
			encoded, marshalErr := json.Marshal(de)
			if marshalErr != nil || de.Kind != wantKind || strings.Contains(string(encoded), "synthetic-old-credential") || strings.Contains(string(encoded), "synthetic-new-credential") {
				t.Fatal("a refreshed attempt did not protect all known credentials")
			}
			if len(transport.calls) != 2 || len(resolver.seen) != 2 {
				t.Fatal("credential accumulation changed the at-most-once replay policy")
			}
		})
	}
}

func TestDecisionsProductionRawQueryCredential(t *testing.T) {
	for _, network := range []bool{false, true} {
		t.Run(fmt.Sprintf("network=%v", network), func(t *testing.T) {
			endpoint := openaiDecisionsEndpoint()
			endpoint.BaseURL += "?sig=%FFcredential-0001"
			decisions, resolver := decisionsWith(endpoint)
			transport := newFakeDecisionsTransport(func(req *http.Request) (*http.Response, error) {
				if !strings.HasSuffix(req.URL.RawQuery, "%FFcredential-0001") {
					t.Fatal("redaction changed the query sent to the provider")
				}
				text := "%ffcredential-0001 \ufffdcredential-0001"
				if network {
					return nil, errors.New(text)
				}
				return jsonDecisionsResponse(400, text, "x-request-id", text)(req)
			})
			_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
			de := asDecisionsError(t, err)
			encoded, _ := json.Marshal(de)
			if strings.Contains(string(encoded), "credential-0001") || !strings.Contains(string(encoded), "[REDACTED]") {
				t.Fatal("raw/decoded invalid-UTF-8 query representations were not both protected")
			}
			if len(transport.calls) != 1 || len(resolver.seen) != 1 {
				t.Fatal("query redaction introduced a retry")
			}
		})
	}
}

func TestDecisionsProductionNilTypedItemsFail(t *testing.T) {
	for name, value := range map[string]any{
		"nil input":           copilot.DecisionsRequest{Model: "m", InputItems: []copilot.DecisionsInputItem{nil}},
		"nil input pointer":   copilot.DecisionsRequest{Model: "m", InputItems: []copilot.DecisionsInputItem{(*copilot.DecisionsUserMessage)(nil)}},
		"nil question":        copilot.DecisionsRequest{Model: "m", Questions: []copilot.DecisionsQuestion{nil}},
		"nil question ptr":    copilot.DecisionsRequest{Model: "m", Questions: []copilot.DecisionsQuestion{(*copilot.DecisionsScoreQuestion)(nil)}},
		"nil user part":       copilot.DecisionsUserMessage{ContentParts: []copilot.DecisionsUserContentPart{nil}},
		"nil user part ptr":   copilot.DecisionsUserMessage{ContentParts: []copilot.DecisionsUserContentPart{(*copilot.DecisionsInputImage)(nil)}},
		"nil assistant part":  copilot.DecisionsAssistantMessage{ContentParts: []copilot.DecisionsAssistantContentPart{nil}},
		"nil tool part":       copilot.DecisionsFunctionCallOutput{OutputParts: []copilot.DecisionsToolOutputPart{nil}},
		"nil predicate part":  copilot.DecisionsPredicateQuestion{InstructionParts: []copilot.DecisionsQuestionPart{nil}},
		"nil choice part":     copilot.DecisionsChoiceQuestion{InstructionParts: []copilot.DecisionsQuestionPart{nil}},
		"nil score part":      copilot.DecisionsScoreQuestion{InstructionParts: []copilot.DecisionsQuestionPart{(*copilot.DecisionsQuestionText)(nil)}},
		"invalid system role": copilot.DecisionsDeveloperMessage{Role: "user"},
	} {
		t.Run(name, func(t *testing.T) {
			_, err := json.Marshal(value)
			var de *copilot.DecisionsError
			if err == nil || errors.As(err, &de) {
				t.Fatal("invalid typed part must fail explicitly as a native argument error")
			}
		})
	}
}
