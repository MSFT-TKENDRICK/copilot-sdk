package copilot_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
	"unicode/utf8"

	copilot "github.com/github/copilot-sdk/go"
	"github.com/github/copilot-sdk/go/rpc"
)

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

func decisionsFixtureDir(t *testing.T) string {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	for {
		candidate := filepath.Join(dir, "test", "fixtures", "decisions")
		if info, err := os.Stat(candidate); err == nil && info.IsDir() {
			return candidate
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatal("could not find test/fixtures/decisions")
		}
		dir = parent
	}
}

func decisionsFixture(t *testing.T, name string) []byte {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(decisionsFixtureDir(t), name))
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func assertJSONEqual(t *testing.T, got, want []byte) {
	t.Helper()
	var g, w any
	if err := json.Unmarshal(got, &g); err != nil {
		t.Fatalf("invalid JSON %s: %v", got, err)
	}
	if err := json.Unmarshal(want, &w); err != nil {
		t.Fatalf("invalid JSON %s: %v", want, err)
	}
	if !reflect.DeepEqual(g, w) {
		t.Fatalf("JSON mismatch\n got: %s\nwant: %s", got, want)
	}
}

type decisionsVectors struct {
	URL []struct {
		Name     string               `json:"name"`
		Endpoint rpc.ProviderEndpoint `json:"endpoint"`
		Expected string               `json:"expected"`
		Error    string               `json:"error"`
	} `json:"url"`
	Headers []struct {
		Name     string               `json:"name"`
		Endpoint rpc.ProviderEndpoint `json:"endpoint"`
		Expected map[string]string    `json:"expected"`
		Error    string               `json:"error"`
	} `json:"headers"`
	Preflight []struct {
		Name     string               `json:"name"`
		Endpoint rpc.ProviderEndpoint `json:"endpoint"`
		Model    string               `json:"model"`
		Error    string               `json:"error"`
	} `json:"preflight"`
	Retry []struct {
		Name   string               `json:"name"`
		First  rpc.ProviderEndpoint `json:"first"`
		Second rpc.ProviderEndpoint `json:"second"`
		Retry  bool                 `json:"retry"`
	} `json:"retry"`
	Redaction []struct {
		Name       string               `json:"name"`
		Endpoint   rpc.ProviderEndpoint `json:"endpoint"`
		Status     int                  `json:"status"`
		Body       string               `json:"body"`
		BodyPrefix *struct {
			Char  string `json:"char"`
			Count int    `json:"count"`
		} `json:"bodyPrefix"`
		BodyRepeat *struct {
			Text  string `json:"text"`
			Count int    `json:"count"`
		} `json:"bodyRepeat"`
		MustNotContain []string `json:"mustNotContain"`
		ErrorCode      *string  `json:"errorCode"`
		ErrorType      *string  `json:"errorType"`
		BodyTruncated  *bool    `json:"bodyTruncated"`
		MaxBodyBytes   *int     `json:"maxBodyBytes"`
	} `json:"redaction"`
	RedactionLength []struct {
		Name     string `json:"name"`
		Value    string `json:"value"`
		IsSecret bool   `json:"isSecret"`
	} `json:"redactionLength"`
}

func loadDecisionsVectors(t *testing.T) decisionsVectors {
	t.Helper()
	var v decisionsVectors
	if err := json.Unmarshal(decisionsFixture(t, "vectors.json"), &v); err != nil {
		t.Fatal(err)
	}
	if len(v.URL) == 0 || len(v.Headers) == 0 || len(v.Preflight) == 0 || len(v.Retry) == 0 || len(v.Redaction) == 0 || len(v.RedactionLength) == 0 {
		t.Fatal("a vector group is empty or missing")
	}
	return v
}

// ---------------------------------------------------------------------------
// Requests and fakes
// ---------------------------------------------------------------------------

var decisionsMinimalRequest = copilot.DecisionsRequest{
	Model: "gpt-6-luna",
	Input: "The export button does nothing when clicked.",
	Questions: []copilot.DecisionsQuestion{
		copilot.DecisionsPredicateQuestion{Instructions: "Is this ticket reporting a product defect?"},
	},
}

func decisionsFullRequest() copilot.DecisionsRequest {
	return copilot.DecisionsRequest{
		Model: "gpt-6-luna",
		InputItems: []copilot.DecisionsInputItem{
			copilot.DecisionsDeveloperMessage{Role: copilot.DecisionsDeveloperRoleDeveloper, Content: "You triage incoming support tickets.", IncludeType: true},
			copilot.DecisionsDeveloperMessage{
				Role:         copilot.DecisionsDeveloperRoleSystem,
				ContentParts: []copilot.DecisionsInputText{{Text: "Answer only from the provided context."}},
				IncludeType:  true,
			},
			copilot.DecisionsUserMessage{
				IncludeType: true,
				ContentParts: []copilot.DecisionsUserContentPart{
					copilot.DecisionsInputText{Text: "Ticket #4821: the export button does nothing.", PromptCacheBreakpoint: &copilot.DecisionsCacheBreakpoint{}},
					copilot.DecisionsInputImage{
						ImageURL:              "data:image/png;base64,iVBORw0KGgo=",
						Detail:                copilot.DecisionsImageDetailHigh,
						PromptCacheBreakpoint: &copilot.DecisionsCacheBreakpoint{Mode: copilot.DecisionsCacheBreakpointModeExplicit},
					},
					copilot.DecisionsInputJSON{Data: map[string]any{"plan": "enterprise", "seats": 120}},
					copilot.DecisionsInputJSON{Data: []map[string]any{{"id": 1}, {"id": 2}}},
				},
			},
			copilot.DecisionsUserMessage{
				ContentParts: []copilot.DecisionsUserContentPart{
					copilot.DecisionsInputText{Text: "Customer replied: still broken after refresh."},
					copilot.DecisionsInputImage{ImageURL: "https://example.com/full.png", Detail: copilot.DecisionsImageDetailOriginal},
				},
			},
			copilot.DecisionsAssistantMessage{
				IncludeType: true,
				ContentParts: []copilot.DecisionsAssistantContentPart{
					copilot.DecisionsOutputText{Text: "I will look up the account."},
					copilot.DecisionsRefusalContent{Refusal: "I cannot share internal notes."},
				},
				Phase: copilot.DecisionsAssistantPhaseCommentary,
			},
			copilot.DecisionsFunctionCall{
				CallID: "call_1", Name: "lookup_account", Arguments: `{"ticket":4821}`,
				ID: copilot.String("fc_1"), Namespace: copilot.String("crm"), Status: copilot.DecisionsItemStatusCompleted,
			},
			copilot.DecisionsFunctionCallOutput{
				CallID: "call_1", Output: `{"tier":"enterprise"}`, ID: copilot.String("fco_1"), Status: copilot.DecisionsItemStatusCompleted,
			},
			copilot.DecisionsFunctionCallOutput{
				CallID: "call_2",
				OutputParts: []copilot.DecisionsToolOutputPart{
					copilot.DecisionsInputText{Text: "Screenshot attached."},
					copilot.DecisionsInputImage{ImageURL: "https://example.com/shot.png", Detail: copilot.DecisionsImageDetailLow},
				},
			},
			copilot.DecisionsCustomToolCall{
				CallID: "call_3", Name: "grep_logs", Input: "export failed",
				ID: copilot.String("ctc_1"), Namespace: copilot.String("ops"), Status: copilot.DecisionsItemStatusIncomplete,
			},
			copilot.DecisionsCustomToolCallOutput{
				CallID: "call_3", Output: "3 matches", ID: copilot.String("ctco_1"), Status: copilot.DecisionsItemStatusInProgress,
			},
			copilot.DecisionsRawInputItem{Raw: json.RawMessage(`{"type":"future_item","payload":{"anything":[1,true,null]}}`)},
		},
		Questions: []copilot.DecisionsQuestion{
			copilot.DecisionsPredicateQuestion{Name: copilot.String("is_bug"), Instructions: "Is this ticket reporting a product defect?"},
			copilot.DecisionsChoiceQuestion{
				Name:         copilot.String("team"),
				Instructions: "Which team should own this ticket?",
				Choices: []copilot.DecisionsChoiceOption{
					{Value: copilot.DecisionsChoiceString("frontend"), Description: copilot.String("UI and client code")},
					{Value: copilot.DecisionsChoiceString("backend")},
				},
			},
			copilot.DecisionsChoiceQuestion{
				InstructionParts: []copilot.DecisionsQuestionPart{
					copilot.DecisionsQuestionText{Text: "Should we escalate?"},
					copilot.DecisionsQuestionImage{ImageURL: "https://example.com/policy.png", Detail: copilot.DecisionsImageDetailAuto},
					copilot.DecisionsInputJSON{Data: map[string]any{"sla_hours": 4}},
				},
				Choices: []copilot.DecisionsChoiceOption{
					{Value: copilot.DecisionsChoiceBool(true), Description: copilot.String("Escalate now")},
					{Value: copilot.DecisionsChoiceBool(false)},
				},
			},
			copilot.DecisionsScoreQuestion{
				Name:         copilot.String("severity"),
				Instructions: "How severe is the impact?",
				Levels: []copilot.DecisionsScoreLevel{
					{Label: "low", Description: copilot.String("Cosmetic")},
					{Label: "medium"},
					{Label: "high", Description: copilot.String("Blocks work")},
				},
			},
		},
		PromptCacheKey:     copilot.String("support-triage-v1"),
		PromptCacheOptions: &copilot.DecisionsPromptCacheOptions{Mode: copilot.DecisionsPromptCacheModeExplicit, TTL: copilot.DecisionsPromptCacheTTL30m},
		SafetyIdentifier:   copilot.String("user-7f3a9c"),
	}
}

func openaiDecisionsEndpoint() rpc.ProviderEndpoint {
	return rpc.ProviderEndpoint{
		Type:    rpc.ProviderEndpointTypeOpenai,
		BaseURL: "https://api.openai.com/v1",
		APIKey:  copilot.String("sk-test-0001"),
		Headers: map[string]string{},
	}
}

type recordedDecisionsCall struct {
	URL     string
	Method  string
	Headers http.Header
	Body    []byte
}

type fakeDecisionsTransport struct {
	mu        sync.Mutex
	responses []func(*http.Request) (*http.Response, error)
	calls     []recordedDecisionsCall
}

func (f *fakeDecisionsTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	var body []byte
	if req.Body != nil {
		body, _ = io.ReadAll(req.Body)
		_ = req.Body.Close()
	}
	f.mu.Lock()
	index := len(f.calls)
	f.calls = append(f.calls, recordedDecisionsCall{URL: req.URL.String(), Method: req.Method, Headers: req.Header.Clone(), Body: body})
	next := f.responses[min(index, len(f.responses)-1)]
	f.mu.Unlock()
	return next(req)
}

func newFakeDecisionsTransport(responses ...func(*http.Request) (*http.Response, error)) *fakeDecisionsTransport {
	return &fakeDecisionsTransport{responses: responses}
}

func decisionsResponse(status int, body io.ReadCloser, headers map[string]string) func(*http.Request) (*http.Response, error) {
	return func(req *http.Request) (*http.Response, error) {
		h := http.Header{}
		for k, v := range headers {
			h.Set(k, v)
		}
		return &http.Response{StatusCode: status, Header: h, Body: body, Request: req}, nil
	}
}

func jsonDecisionsResponse(status int, body string, headers ...string) func(*http.Request) (*http.Response, error) {
	return func(req *http.Request) (*http.Response, error) {
		h := http.Header{"Content-Type": {"application/json"}}
		for i := 0; i+1 < len(headers); i += 2 {
			h.Set(headers[i], headers[i+1])
		}
		return &http.Response{StatusCode: status, Header: h, Body: io.NopCloser(strings.NewReader(body)), Request: req}, nil
	}
}

type decisionsResolver struct {
	mu        sync.Mutex
	endpoints []rpc.ProviderEndpoint
	seen      []*rpc.SessionProviderGetEndpointRequest
}

func (r *decisionsResolver) decisions() *copilot.SessionDecisions {
	return copilot.NewSessionDecisionsForTest(func(_ context.Context, params *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error) {
		r.mu.Lock()
		defer r.mu.Unlock()
		endpoint := r.endpoints[min(len(r.seen), len(r.endpoints)-1)]
		r.seen = append(r.seen, params)
		return &endpoint, nil
	})
}

func decisionsWith(endpoints ...rpc.ProviderEndpoint) (*copilot.SessionDecisions, *decisionsResolver) {
	r := &decisionsResolver{endpoints: endpoints}
	return r.decisions(), r
}

func asDecisionsError(t *testing.T, err error) *copilot.DecisionsError {
	t.Helper()
	var de *copilot.DecisionsError
	if !errors.As(err, &de) {
		t.Fatalf("expected *DecisionsError, got %T: %v", err, err)
	}
	return de
}

func okDecisionsBody(t *testing.T) string {
	return string(decisionsFixture(t, "response.full.json"))
}

// ---------------------------------------------------------------------------
// Serialization
// ---------------------------------------------------------------------------

func TestDecisionsSerializesFullRequestToGoldenFixture(t *testing.T) {
	got, err := json.Marshal(decisionsFullRequest())
	if err != nil {
		t.Fatal(err)
	}
	assertJSONEqual(t, got, decisionsFixture(t, "request.full.json"))
}

func TestDecisionsSerializesMinimalRequest(t *testing.T) {
	got, err := json.Marshal(decisionsMinimalRequest)
	if err != nil {
		t.Fatal(err)
	}
	assertJSONEqual(t, got, decisionsFixture(t, "request.minimal.json"))
}

func TestDecisionsSerializationOmitsUnsetOptionalFields(t *testing.T) {
	req := copilot.DecisionsRequest{
		Model: "gpt-6-luna",
		InputItems: []copilot.DecisionsInputItem{
			copilot.DecisionsUserMessage{IncludeType: true, ContentParts: []copilot.DecisionsUserContentPart{
				copilot.DecisionsInputImage{ImageURL: "https://example.com/a.png"},
			}},
			copilot.DecisionsAssistantMessage{IncludeType: true, Content: "ok"},
			copilot.DecisionsFunctionCall{CallID: "c", Name: "n", Arguments: "{}"},
		},
		Questions: []copilot.DecisionsQuestion{
			copilot.DecisionsChoiceQuestion{Instructions: "q", Choices: []copilot.DecisionsChoiceOption{{Value: copilot.DecisionsChoiceString("a")}}},
			copilot.DecisionsScoreQuestion{Instructions: "s", Levels: []copilot.DecisionsScoreLevel{{Label: "x"}}},
		},
	}
	got, err := json.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}
	assertJSONEqual(t, got, []byte(`{
		"model": "gpt-6-luna",
		"input": [
			{"type": "message", "role": "user", "content": [{"type": "input_image", "image_url": "https://example.com/a.png"}]},
			{"type": "message", "role": "assistant", "content": "ok"},
			{"type": "function_call", "call_id": "c", "name": "n", "arguments": "{}"}
		],
		"questions": [
			{"type": "choice", "instructions": "q", "choices": [{"value": "a"}]},
			{"type": "score", "instructions": "s", "levels": [{"label": "x"}]}
		]
	}`))
}

func TestDecisionsSerializationRequiresModel(t *testing.T) {
	req := decisionsMinimalRequest
	req.Model = ""
	if _, err := json.Marshal(req); err == nil || !strings.Contains(err.Error(), "Model is required") {
		t.Fatalf("expected model required error, got %v", err)
	}
}

func TestDecisionsSerializationRejectsInvalidInputs(t *testing.T) {
	for name, req := range map[string]copilot.DecisionsRequest{
		"missing role": {Model: "m", InputItems: []copilot.DecisionsInputItem{copilot.DecisionsDeveloperMessage{Content: "x"}}},
		"raw array":    {Model: "m", InputItems: []copilot.DecisionsInputItem{copilot.DecisionsRawInputItem{Raw: json.RawMessage(`[1]`)}}},
		"raw invalid":  {Model: "m", InputItems: []copilot.DecisionsInputItem{copilot.DecisionsRawInputItem{Raw: json.RawMessage(`{`)}}},
	} {
		if _, err := json.Marshal(req); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
}

func TestDecisionsChoiceValuePreservesBoolAndString(t *testing.T) {
	for _, tc := range []struct {
		value copilot.DecisionsChoiceValue
		wire  string
	}{
		{copilot.DecisionsChoiceBool(true), `true`},
		{copilot.DecisionsChoiceBool(false), `false`},
		{copilot.DecisionsChoiceString("true"), `"true"`},
		{copilot.DecisionsChoiceString(""), `""`},
	} {
		got, err := json.Marshal(tc.value)
		if err != nil || string(got) != tc.wire {
			t.Fatalf("marshal %v = %s, %v; want %s", tc.value.Value(), got, err, tc.wire)
		}
		var back copilot.DecisionsChoiceValue
		if err := json.Unmarshal(got, &back); err != nil || back != tc.value {
			t.Fatalf("round trip %s = %#v, %v", tc.wire, back, err)
		}
	}
	var bad copilot.DecisionsChoiceValue
	if err := json.Unmarshal([]byte(`3`), &bad); err == nil {
		t.Fatal("expected an error for a numeric choice value")
	}
}

// ---------------------------------------------------------------------------
// Response parsing
// ---------------------------------------------------------------------------

func TestDecisionsParsesFullResponseFixture(t *testing.T) {
	result, err := copilot.ParseDecisionsResultForTest(decisionsFixture(t, "response.full.json"))
	if err != nil {
		t.Fatal(err)
	}
	if result.Model != "gpt-6-luna-2026-10-01" {
		t.Errorf("model = %q", result.Model)
	}
	wantUsage := copilot.DecisionsUsage{
		InputTokens:         812,
		InputTokensDetails:  copilot.DecisionsInputTokensDetails{CachedTokens: 512, CacheWriteTokens: 128},
		OutputTokens:        24,
		OutputTokensDetails: copilot.DecisionsOutputTokensDetails{ReasoningTokens: 16},
		TotalTokens:         836,
	}
	if result.Usage != wantUsage {
		t.Errorf("usage = %+v", result.Usage)
	}
	if len(result.Answers) != 6 {
		t.Fatalf("answers = %d", len(result.Answers))
	}

	predicate := result.Answers[0].(copilot.DecisionsPredicateAnswer)
	if *predicate.Name != "is_bug" || predicate.Probability != 0.93 {
		t.Errorf("predicate = %+v", predicate)
	}
	choice := result.Answers[1].(copilot.DecisionsChoiceAnswer)
	if *choice.Name != "team" || choice.Choice != copilot.DecisionsChoiceString("frontend") || choice.Confidence != 0.81 {
		t.Errorf("choice = %+v", choice)
	}
	boolChoice := result.Answers[2].(copilot.DecisionsChoiceAnswer)
	if boolChoice.Name != nil || !boolChoice.Choice.IsBool() || !boolChoice.Choice.BoolValue() {
		t.Errorf("bool choice = %+v", boolChoice)
	}
	if got := []any{boolChoice.Probabilities[0].Value.Value(), boolChoice.Probabilities[1].Value.Value()}; !reflect.DeepEqual(got, []any{true, false}) {
		t.Errorf("bool probabilities = %v", got)
	}
	score := result.Answers[3].(copilot.DecisionsScoreAnswer)
	if *score.Name != "severity" || score.Score != 1.37 {
		t.Errorf("score = %+v", score)
	}
	var levels []int
	for _, p := range score.Probabilities {
		levels = append(levels, p.Value)
	}
	if !reflect.DeepEqual(levels, []int{0, 1, 2}) || score.Probabilities[1].Label != "medium" {
		t.Errorf("score probabilities = %+v", score.Probabilities)
	}
	refusal := result.Answers[4].(copilot.DecisionsRefusalAnswer)
	if *refusal.Name != "pii_check" || refusal.Type() != copilot.DecisionsAnswerTypeRefusal {
		t.Errorf("refusal = %+v", refusal)
	}
	unknown := result.Answers[5].(copilot.DecisionsUnknownAnswer)
	if unknown.Type() != copilot.DecisionsAnswerTypeUnknown || unknown.WireType != "ranking" || *unknown.Name != "future_kind" {
		t.Errorf("unknown = %+v", unknown)
	}
	assertJSONEqual(t, unknown.Raw, []byte(`{"type":"ranking","name":"future_kind","ranked":["a","b"]}`))
}

func TestDecisionsRejectsMalformedResponses(t *testing.T) {
	for _, body := range []string{
		`null`,
		`[]`,
		`{"answers":[],"model":"m"}`,
		`{"answers":[{"type":"predicate"}],"model":"m","usage":{}}`,
		`{"answers":[{"type":"choice","choice":3,"confidence":1,"probabilities":[]}],"model":"m","usage":{}}`,
		`{"answers":[{"type":"score","score":1,"confidence":1,"probabilities":[{"label":"a","value":0.5,"probability":1}]}],"model":"m","usage":{}}`,
		`{"answers":[1],"model":"m","usage":{}}`,
		`{"answers":[],"usage":{}}`,
		`{"answers":[{"type":"predicate","name":3,"probability":1}],"model":"m","usage":{}}`,
		`{"answers":[{"type":"predicate","probability":"1"}],"model":"m","usage":{}}`,
	} {
		_, err := copilot.ParseDecisionsResultForTest([]byte(body))
		de := asDecisionsError(t, err)
		if de.Kind != copilot.DecisionsErrorKindInvalidResponse || !strings.HasPrefix(de.Message, "The Decisions API returned an unexpected response: ") {
			t.Errorf("%s: got %v (%s)", body, de.Kind, de.Message)
		}
	}
}

func TestDecisionsAnswerTypeSwitchIsExhaustive(t *testing.T) {
	result, err := copilot.ParseDecisionsResultForTest(decisionsFixture(t, "response.full.json"))
	if err != nil {
		t.Fatal(err)
	}
	var described []string
	for _, answer := range result.Answers {
		switch a := answer.(type) {
		case copilot.DecisionsPredicateAnswer:
			described = append(described, "p"+strconv.FormatFloat(a.Probability, 'f', -1, 64))
		case copilot.DecisionsChoiceAnswer:
			described = append(described, fmt.Sprintf("c%v", a.Choice.Value()))
		case copilot.DecisionsScoreAnswer:
			described = append(described, "s"+strconv.FormatFloat(a.Score, 'f', -1, 64))
		case copilot.DecisionsRefusalAnswer:
			described = append(described, "r")
		case copilot.DecisionsUnknownAnswer:
			described = append(described, "u"+a.WireType)
		default:
			t.Fatalf("unexpected answer %T", a)
		}
	}
	want := []string{"p0.93", "cfrontend", "ctrue", "s1.37", "r", "uranking"}
	if !reflect.DeepEqual(described, want) {
		t.Fatalf("described = %v, want %v", described, want)
	}
}

// ---------------------------------------------------------------------------
// Vectors
// ---------------------------------------------------------------------------

func TestDecisionsVectorsURL(t *testing.T) {
	for _, v := range loadDecisionsVectors(t).URL {
		t.Run(v.Name, func(t *testing.T) {
			got, err := copilot.BuildDecisionsURLForTest(&v.Endpoint)
			if v.Error != "" {
				de := asDecisionsError(t, err)
				if string(de.Kind) != v.Error {
					t.Fatalf("kind = %s, want %s", de.Kind, v.Error)
				}
				if strings.Contains(de.Message, "secretpassword") {
					t.Fatalf("message leaks the URL: %s", de.Message)
				}
				return
			}
			if err != nil || got != v.Expected {
				t.Fatalf("got %q, %v; want %q", got, err, v.Expected)
			}
		})
	}
}

func TestDecisionsVectorsHeaders(t *testing.T) {
	for _, v := range loadDecisionsVectors(t).Headers {
		t.Run(v.Name, func(t *testing.T) {
			_, headers, err := copilot.PlanDecisionsAttemptForTest(&v.Endpoint, "gpt-6-luna")
			if v.Error != "" {
				if de := asDecisionsError(t, err); string(de.Kind) != v.Error {
					t.Fatalf("kind = %s, want %s", de.Kind, v.Error)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(headers, v.Expected) {
				t.Fatalf("headers = %v, want %v", headers, v.Expected)
			}
		})
	}
}

func TestDecisionsVectorsPreflight(t *testing.T) {
	for _, v := range loadDecisionsVectors(t).Preflight {
		t.Run(v.Name, func(t *testing.T) {
			_, _, err := copilot.PlanDecisionsAttemptForTest(&v.Endpoint, v.Model)
			if v.Error != "" {
				if de := asDecisionsError(t, err); string(de.Kind) != v.Error {
					t.Fatalf("kind = %s, want %s", de.Kind, v.Error)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
		})
	}
}

func TestDecisionsVectorsRetry(t *testing.T) {
	for _, v := range loadDecisionsVectors(t).Retry {
		t.Run(v.Name, func(t *testing.T) {
			transport := newFakeDecisionsTransport(
				jsonDecisionsResponse(401, `{"error":{"type":"auth","code":"expired"}}`),
				jsonDecisionsResponse(200, okDecisionsBody(t)),
			)
			decisions, resolver := decisionsWith(v.First, v.Second)
			result, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
			if v.Retry {
				if err != nil {
					t.Fatalf("expected success, got %v", err)
				}
				if result.Model != "gpt-6-luna-2026-10-01" {
					t.Fatalf("model = %q", result.Model)
				}
				if len(transport.calls) != 2 {
					t.Fatalf("calls = %d", len(transport.calls))
				}
				wantURL, _ := copilot.BuildDecisionsURLForTest(&v.Second)
				if transport.calls[1].URL != wantURL {
					t.Fatalf("retry URL = %s, want %s", transport.calls[1].URL, wantURL)
				}
			} else {
				de := asDecisionsError(t, err)
				if de.Kind != copilot.DecisionsErrorKindHTTP || de.Status != 401 || de.ErrorCode == nil || *de.ErrorCode != "expired" {
					t.Fatalf("error = %+v", de)
				}
				if len(transport.calls) != 1 {
					t.Fatalf("calls = %d", len(transport.calls))
				}
			}
			if len(resolver.seen) != 2 {
				t.Fatalf("getEndpoint calls = %d", len(resolver.seen))
			}
		})
	}
}

func TestDecisionsRetriesAtMostOnce(t *testing.T) {
	second, third := openaiDecisionsEndpoint(), openaiDecisionsEndpoint()
	second.APIKey = copilot.String("sk-test-0002")
	third.APIKey = copilot.String("sk-test-0003")
	transport := newFakeDecisionsTransport(jsonDecisionsResponse(403, "{}"))
	decisions, _ := decisionsWith(openaiDecisionsEndpoint(), second, third)
	_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
	if de := asDecisionsError(t, err); de.Kind != copilot.DecisionsErrorKindHTTP || de.Status != 403 {
		t.Fatalf("error = %+v", de)
	}
	if len(transport.calls) != 2 {
		t.Fatalf("calls = %d", len(transport.calls))
	}
}

func TestDecisionsVectorsRedactionLength(t *testing.T) {
	for _, v := range loadDecisionsVectors(t).RedactionLength {
		t.Run(v.Name, func(t *testing.T) {
			trimmed := strings.Trim(v.Value, " \t")
			transport := newFakeDecisionsTransport(func(req *http.Request) (*http.Response, error) {
				return &http.Response{StatusCode: 400, Header: http.Header{}, Body: io.NopCloser(strings.NewReader("echo:" + trimmed + ":end")), Request: req}, nil
			})
			endpoint := openaiDecisionsEndpoint()
			endpoint.APIKey = copilot.String(v.Value)
			decisions, _ := decisionsWith(endpoint)
			_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
			de := asDecisionsError(t, err)
			want := "echo:" + trimmed + ":end"
			if v.IsSecret {
				want = "echo:[REDACTED]:end"
			}
			if de.Body != want {
				t.Fatalf("body = %q, want %q", de.Body, want)
			}
		})
	}
}

func TestDecisionsVectorsRedaction(t *testing.T) {
	for _, v := range loadDecisionsVectors(t).Redaction {
		t.Run(v.Name, func(t *testing.T) {
			payload := v.Body
			if v.BodyRepeat != nil {
				payload = strings.ReplaceAll(payload, "{{repeat}}", strings.Repeat(v.BodyRepeat.Text, v.BodyRepeat.Count))
			}
			if v.BodyPrefix != nil {
				payload = strings.Repeat(v.BodyPrefix.Char, v.BodyPrefix.Count) + payload
			}
			transport := newFakeDecisionsTransport(func(req *http.Request) (*http.Response, error) {
				return &http.Response{
					StatusCode: v.Status,
					Header:     http.Header{"X-Request-Id": {"req_123"}},
					Body:       io.NopCloser(strings.NewReader(payload)),
					Request:    req,
				}, nil
			})
			decisions, _ := decisionsWith(v.Endpoint)
			_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
			de := asDecisionsError(t, err)
			if de.Kind != copilot.DecisionsErrorKindHTTP || de.Status != v.Status {
				t.Fatalf("error = %v %d", de.Kind, de.Status)
			}
			if de.RequestID == nil || *de.RequestID != "req_123" {
				t.Fatalf("request id = %v", de.RequestID)
			}
			if !reflect.DeepEqual(de.ErrorCode, v.ErrorCode) {
				t.Fatalf("errorCode = %v, want %v", ptrString(de.ErrorCode), ptrString(v.ErrorCode))
			}
			if !reflect.DeepEqual(de.ErrorType, v.ErrorType) {
				t.Fatalf("errorType = %v, want %v", ptrString(de.ErrorType), ptrString(v.ErrorType))
			}
			if v.BodyTruncated != nil && de.BodyTruncated != *v.BodyTruncated {
				t.Fatalf("bodyTruncated = %v, want %v", de.BodyTruncated, *v.BodyTruncated)
			}
			if v.MaxBodyBytes != nil && len(de.Body) > *v.MaxBodyBytes {
				t.Fatalf("body is %d bytes, max %d", len(de.Body), *v.MaxBodyBytes)
			}
			if !utf8.ValidString(de.Body) {
				t.Fatal("body is not valid UTF-8")
			}
			encoded, _ := json.Marshal(de)
			surfaces := strings.Join([]string{
				de.Message, de.Body, ptrString(de.ErrorCode), ptrString(de.ErrorType), ptrString(de.ErrorParam),
				de.Error(), fmt.Sprintf("%+v", *de), string(encoded),
			}, "\n")
			for _, secret := range v.MustNotContain {
				if strings.Contains(surfaces, secret) {
					t.Fatalf("error surfaces contain %q", secret)
				}
			}
			if !de.BodyTruncated && !strings.Contains(de.Body, "[REDACTED]") {
				t.Fatalf("body was not redacted: %q", de.Body)
			}
		})
	}
}

func ptrString(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

// ---------------------------------------------------------------------------
// Create
// ---------------------------------------------------------------------------

func TestDecisionsCreatePostsSerializedRequestWithPlannedHeaders(t *testing.T) {
	transport := newFakeDecisionsTransport(jsonDecisionsResponse(200, okDecisionsBody(t)))
	decisions, resolver := decisionsWith(openaiDecisionsEndpoint())
	result, err := decisions.Create(context.Background(), decisionsFullRequest(), &copilot.DecisionsOptions{Transport: transport})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Answers) != 6 {
		t.Fatalf("answers = %d", len(result.Answers))
	}
	if len(resolver.seen) != 1 || resolver.seen[0] != nil {
		t.Fatalf("getEndpoint params = %v", resolver.seen)
	}
	if len(transport.calls) != 1 {
		t.Fatalf("calls = %d", len(transport.calls))
	}
	call := transport.calls[0]
	if call.URL != "https://api.openai.com/v1/decisions" || call.Method != http.MethodPost {
		t.Fatalf("call = %s %s", call.Method, call.URL)
	}
	wantHeaders := http.Header{
		"Authorization": {"Bearer sk-test-0001"},
		"Content-Type":  {"application/json"},
		"Accept":        {"application/json"},
	}
	if !reflect.DeepEqual(call.Headers, wantHeaders) {
		t.Fatalf("headers = %v", call.Headers)
	}
	assertJSONEqual(t, call.Body, decisionsFixture(t, "request.full.json"))
}

func TestDecisionsCreatePassesEndpointModelIDVerbatim(t *testing.T) {
	transport := newFakeDecisionsTransport(jsonDecisionsResponse(200, okDecisionsBody(t)))
	decisions, resolver := decisionsWith(openaiDecisionsEndpoint())
	_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport, EndpointModelID: "endpoint-model"})
	if err != nil {
		t.Fatal(err)
	}
	if len(resolver.seen) != 1 || resolver.seen[0] == nil || resolver.seen[0].ModelID == nil || *resolver.seen[0].ModelID != "endpoint-model" {
		t.Fatalf("getEndpoint params = %+v", resolver.seen)
	}
}

func TestDecisionsCreateCallsGetEndpointOnEveryCall(t *testing.T) {
	transport := newFakeDecisionsTransport(jsonDecisionsResponse(200, okDecisionsBody(t)))
	decisions, resolver := decisionsWith(openaiDecisionsEndpoint())
	for range 2 {
		if _, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport}); err != nil {
			t.Fatal(err)
		}
	}
	if len(resolver.seen) != 2 {
		t.Fatalf("getEndpoint calls = %d", len(resolver.seen))
	}
}

func TestDecisionsCreateMapsOpenAIErrorEnvelope(t *testing.T) {
	transport := newFakeDecisionsTransport(jsonDecisionsResponse(400, string(decisionsFixture(t, "error.json")), "X-Request-Id", "req_9"))
	decisions, _ := decisionsWith(openaiDecisionsEndpoint())
	_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
	de := asDecisionsError(t, err)
	if de.Kind != copilot.DecisionsErrorKindHTTP || de.Status != 400 || de.BodyTruncated ||
		ptrString(de.ErrorType) != "invalid_request_error" || ptrString(de.ErrorCode) != "unsupported_model" ||
		ptrString(de.ErrorParam) != "model" || ptrString(de.RequestID) != "req_9" {
		t.Fatalf("error = %+v", de)
	}
	if !strings.Contains(de.Message, "does not support the Decisions API") {
		t.Fatalf("message = %q", de.Message)
	}
}

func TestDecisionsCreateBoundsLargeErrorBodies(t *testing.T) {
	big := strings.Repeat("x", copilot.DecisionsMaxErrorBodyBytesForTest+1000)
	transport := newFakeDecisionsTransport(func(req *http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 500, Header: http.Header{}, Body: io.NopCloser(strings.NewReader(big)), Request: req}, nil
	})
	decisions, _ := decisionsWith(openaiDecisionsEndpoint())
	_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
	de := asDecisionsError(t, err)
	if de.Kind != copilot.DecisionsErrorKindHTTP || !de.BodyTruncated || len(de.Body) != copilot.DecisionsMaxErrorBodyBytesForTest {
		t.Fatalf("kind=%s truncated=%v len=%d", de.Kind, de.BodyTruncated, len(de.Body))
	}
}

func TestDecisionsCreateTruncatesErrorBodyAtUTF8Boundary(t *testing.T) {
	limit := copilot.DecisionsMaxErrorBodyBytesForTest
	for _, tc := range []struct {
		name   string
		raw    string
		prefix string
	}{
		// A 4-byte emoji starts 2 bytes before the limit: it is dropped whole.
		{"split emoji", strings.Repeat("x", limit-2) + "🔑" + strings.Repeat("y", 100), strings.Repeat("x", limit-2)},
		// A 3-byte character ends exactly at the limit: it is kept.
		{"exact fit", strings.Repeat("x", limit-3) + "€" + strings.Repeat("y", 100), strings.Repeat("x", limit-3) + "€"},
		// Invalid UTF-8 earlier is decoded lossily (1 raw byte -> 3-byte U+FFFD) before the cut.
		{"lossy prefix", "\xFF" + strings.Repeat("x", limit-5) + "🔑" + strings.Repeat("y", 100), "\uFFFD" + strings.Repeat("x", limit-5)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			transport := newFakeDecisionsTransport(func(req *http.Request) (*http.Response, error) {
				return &http.Response{StatusCode: 500, Header: http.Header{}, Body: io.NopCloser(strings.NewReader(tc.raw)), Request: req}, nil
			})
			decisions, _ := decisionsWith(openaiDecisionsEndpoint())
			_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
			de := asDecisionsError(t, err)
			if !de.BodyTruncated || len(de.Body) > limit || !utf8.ValidString(de.Body) {
				t.Fatalf("truncated=%v len=%d valid=%v", de.BodyTruncated, len(de.Body), utf8.ValidString(de.Body))
			}
			if de.Body != tc.prefix {
				t.Fatalf("body len=%d tail=%q, want len=%d", len(de.Body), de.Body[max(0, len(de.Body)-8):], len(tc.prefix))
			}
			if tc.name == "split emoji" && strings.HasSuffix(de.Body, "\uFFFD") {
				t.Fatal("split trailing character must be dropped, not replaced with U+FFFD")
			}
		})
	}
}

// endlessBody yields 'x' forever and records Close.
type endlessBody struct {
	mu     sync.Mutex
	closed bool
}

func (b *endlessBody) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = 'x'
	}
	return len(p), nil
}

func (b *endlessBody) Close() error {
	b.mu.Lock()
	b.closed = true
	b.mu.Unlock()
	return nil
}

func TestDecisionsCreateClosesOversizedErrorBodies(t *testing.T) {
	body := &endlessBody{}
	transport := newFakeDecisionsTransport(decisionsResponse(500, body, nil))
	decisions, _ := decisionsWith(openaiDecisionsEndpoint())
	_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
	de := asDecisionsError(t, err)
	if !de.BodyTruncated {
		t.Fatal("expected a truncated body")
	}
	body.mu.Lock()
	defer body.mu.Unlock()
	if !body.closed {
		t.Fatal("expected the body to be closed")
	}
}

func TestDecisionsCreateReportsNonJSONSuccessAsInvalidResponse(t *testing.T) {
	transport := newFakeDecisionsTransport(jsonDecisionsResponse(200, "<html>"))
	decisions, _ := decisionsWith(openaiDecisionsEndpoint())
	_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
	if de := asDecisionsError(t, err); de.Kind != copilot.DecisionsErrorKindInvalidResponse {
		t.Fatalf("kind = %s", de.Kind)
	}
}

func TestDecisionsCreateCapsSuccessBodies(t *testing.T) {
	huge := bytes.Repeat([]byte(" "), copilot.DecisionsMaxResponseBytesForTest+1)
	transport := newFakeDecisionsTransport(decisionsResponse(200, io.NopCloser(bytes.NewReader(huge)), nil))
	decisions, _ := decisionsWith(openaiDecisionsEndpoint())
	_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
	de := asDecisionsError(t, err)
	if de.Kind != copilot.DecisionsErrorKindInvalidResponse || !strings.Contains(de.Message, "exceeded 8388608 bytes") {
		t.Fatalf("error = %v: %s", de.Kind, de.Message)
	}
}

func TestDecisionsCreateWrapsTransportFailuresWithSanitizedCause(t *testing.T) {
	original := &net.OpError{
		Op:  "read",
		Net: "tcp",
		Err: fmt.Errorf("fetch failed for https://api.openai.com/v1/decisions with Bearer sk-test-0001: %w", syscall.ECONNRESET),
	}
	transport := newFakeDecisionsTransport(func(*http.Request) (*http.Response, error) { return nil, original })
	decisions, _ := decisionsWith(openaiDecisionsEndpoint())
	_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
	de := asDecisionsError(t, err)
	if de.Kind != copilot.DecisionsErrorKindNetwork || de.Message != "The Decisions request could not be sent." {
		t.Fatalf("error = %v: %s", de.Kind, de.Message)
	}
	var cause *copilot.DecisionsNetworkCause
	if !errors.As(err, &cause) {
		t.Fatalf("cause = %T", de.Cause)
	}
	if cause.Name != "*net.OpError" {
		t.Errorf("cause name = %q", cause.Name)
	}
	if cause.Code != "ECONNRESET" {
		t.Errorf("cause code = %q", cause.Code)
	}
	if strings.Contains(cause.Message, "sk-test-0001") || !strings.Contains(cause.Message, "[REDACTED]") {
		t.Errorf("cause message = %q", cause.Message)
	}
	if errors.Is(err, syscall.ECONNRESET) {
		t.Error("the original error must not be reachable through Unwrap")
	}
	var opErr *net.OpError
	if errors.As(err, &opErr) {
		t.Error("the original *net.OpError must not be reachable through Unwrap")
	}
}

// failingBody yields a prefix and then fails.
type failingBody struct {
	sent bool
}

func (b *failingBody) Read(p []byte) (int, error) {
	if !b.sent {
		b.sent = true
		return copy(p, `{"answers":`), nil
	}
	return 0, errors.New("socket hang up")
}

func (b *failingBody) Close() error { return nil }

func TestDecisionsCreateMapsMidStreamFailureToNetwork(t *testing.T) {
	for _, status := range []int{200, 500} {
		t.Run(strconv.Itoa(status), func(t *testing.T) {
			transport := newFakeDecisionsTransport(decisionsResponse(status, &failingBody{}, nil))
			decisions, _ := decisionsWith(openaiDecisionsEndpoint())
			_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
			de := asDecisionsError(t, err)
			if de.Kind != copilot.DecisionsErrorKindNetwork {
				t.Fatalf("kind = %s", de.Kind)
			}
			var cause *copilot.DecisionsNetworkCause
			if !errors.As(err, &cause) || cause.Message != "socket hang up" {
				t.Fatalf("cause = %#v", de.Cause)
			}
		})
	}
}

func TestDecisionsCreatePropagatesEndpointUnavailableOnRefreshFailure(t *testing.T) {
	calls := 0
	decisions := copilot.NewSessionDecisionsForTest(func(context.Context, *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error) {
		calls++
		if calls == 1 {
			endpoint := openaiDecisionsEndpoint()
			return &endpoint, nil
		}
		return nil, errors.New("runtime went away")
	})
	transport := newFakeDecisionsTransport(jsonDecisionsResponse(401, "{}"))
	_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
	if de := asDecisionsError(t, err); de.Kind != copilot.DecisionsErrorKindEndpointUnavailable {
		t.Fatalf("kind = %s", de.Kind)
	}
}

func TestDecisionsCreateReturnsCancellationDuringRefresh(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	calls := 0
	decisions := copilot.NewSessionDecisionsForTest(func(context.Context, *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error) {
		calls++
		if calls == 2 {
			cancel()
		}
		endpoint := openaiDecisionsEndpoint()
		return &endpoint, nil
	})
	transport := newFakeDecisionsTransport(jsonDecisionsResponse(401, "{}"))
	_, err := decisions.Create(ctx, decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
	assertDecisionsCancellation(t, err, context.Canceled)
}

func assertDecisionsCancellation(t *testing.T, err, target error) {
	t.Helper()
	if !errors.Is(err, target) {
		t.Fatalf("expected %v, got %T: %v", target, err, err)
	}
	var de *copilot.DecisionsError
	if errors.As(err, &de) {
		t.Fatalf("cancellation must not be wrapped in DecisionsError: %v", err)
	}
}

func TestDecisionsCreateValidatesEndpointBeforeSerializing(t *testing.T) {
	malformed := copilot.DecisionsRequest{
		Model:      "gpt-6-luna",
		InputItems: []copilot.DecisionsInputItem{copilot.DecisionsRawInputItem{Raw: json.RawMessage(`[`)}},
	}
	decisions, _ := decisionsWith(rpc.ProviderEndpoint{Type: rpc.ProviderEndpointTypeAnthropic, BaseURL: "https://api.anthropic.com", Headers: map[string]string{}})
	_, err := decisions.Create(context.Background(), malformed, nil)
	if de := asDecisionsError(t, err); de.Kind != copilot.DecisionsErrorKindUnsupportedProvider {
		t.Fatalf("kind = %s", de.Kind)
	}
}

func TestDecisionsCreateRejectsMissingModelBeforeGetEndpoint(t *testing.T) {
	req := decisionsMinimalRequest
	req.Model = ""
	decisions, resolver := decisionsWith(openaiDecisionsEndpoint())
	_, err := decisions.Create(context.Background(), req, nil)
	if err == nil || !strings.Contains(err.Error(), "Model is required") {
		t.Fatalf("err = %v", err)
	}
	var de *copilot.DecisionsError
	if errors.As(err, &de) {
		t.Fatal("a missing model is a usage error, not a DecisionsError")
	}
	if len(resolver.seen) != 0 {
		t.Fatalf("getEndpoint calls = %d", len(resolver.seen))
	}
}

// eventBody records reads and closes into a shared event log.
type eventBody struct {
	events *[]string
	data   *strings.Reader
}

func (b *eventBody) Read(p []byte) (int, error) {
	*b.events = append(*b.events, "read")
	return b.data.Read(p)
}

func (b *eventBody) Close() error {
	*b.events = append(*b.events, "close")
	return nil
}

func TestDecisionsCreateDrainsUnauthorizedBodyBeforeRetrying(t *testing.T) {
	var events []string
	attempt := 0
	ok := okDecisionsBody(t)
	transport := newFakeDecisionsTransport(func(req *http.Request) (*http.Response, error) {
		events = append(events, fmt.Sprintf("fetch%d", attempt))
		attempt++
		if attempt == 1 {
			return &http.Response{StatusCode: 401, Header: http.Header{}, Body: &eventBody{events: &events, data: strings.NewReader("{}")}, Request: req}, nil
		}
		return &http.Response{StatusCode: 200, Header: http.Header{}, Body: io.NopCloser(strings.NewReader(ok)), Request: req}, nil
	})
	second := openaiDecisionsEndpoint()
	second.APIKey = copilot.String("sk-test-0002")
	decisions, _ := decisionsWith(openaiDecisionsEndpoint(), second)
	if _, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport}); err != nil {
		t.Fatal(err)
	}
	index := func(event string) int {
		for i, e := range events {
			if e == event {
				return i
			}
		}
		return -1
	}
	if index("read") < 0 || index("close") < 0 || index("read") > index("fetch1") || index("close") > index("fetch1") {
		t.Fatalf("events = %v", events)
	}
}

func TestDecisionsCreateSurfacesGateHintOnlyWhenMentioned(t *testing.T) {
	gated := copilot.NewSessionDecisionsForTest(func(context.Context, *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error) {
		return nil, errors.New("Request session.provider.getEndpoint failed with message: Provider endpoint access is disabled. To enable this API, set COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true")
	})
	_, err := gated.Create(context.Background(), decisionsMinimalRequest, nil)
	gatedErr := asDecisionsError(t, err)
	if gatedErr.Kind != copilot.DecisionsErrorKindEndpointUnavailable || !strings.Contains(gatedErr.Message, "COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true") {
		t.Fatalf("gated error = %v: %s", gatedErr.Kind, gatedErr.Message)
	}

	runtimeErr := errors.New("Session not found")
	other := copilot.NewSessionDecisionsForTest(func(context.Context, *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error) {
		return nil, runtimeErr
	})
	_, err = other.Create(context.Background(), decisionsMinimalRequest, nil)
	otherErr := asDecisionsError(t, err)
	if otherErr.Kind != copilot.DecisionsErrorKindEndpointUnavailable || strings.Contains(otherErr.Message, "COPILOT_ALLOW_GET_PROVIDER_ENDPOINT") {
		t.Fatalf("other error = %v: %s", otherErr.Kind, otherErr.Message)
	}
	if !errors.Is(err, runtimeErr) {
		t.Fatal("endpointUnavailable should unwrap to the resolution error")
	}
}

func TestDecisionsCreateRejectsUnsupportedProvidersBeforeSending(t *testing.T) {
	transport := newFakeDecisionsTransport(jsonDecisionsResponse(200, okDecisionsBody(t)))
	decisions, _ := decisionsWith(rpc.ProviderEndpoint{Type: rpc.ProviderEndpointTypeAnthropic, BaseURL: "https://api.anthropic.com", Headers: map[string]string{}})
	_, err := decisions.Create(context.Background(), decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
	if de := asDecisionsError(t, err); de.Kind != copilot.DecisionsErrorKindUnsupportedProvider {
		t.Fatalf("kind = %s", de.Kind)
	}
	if len(transport.calls) != 0 {
		t.Fatalf("calls = %d", len(transport.calls))
	}
}

// ---------------------------------------------------------------------------
// Cancellation
// ---------------------------------------------------------------------------

func TestDecisionsCreateDoesNotCallGetEndpointWhenAlreadyCancelled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	decisions, resolver := decisionsWith(openaiDecisionsEndpoint())
	_, err := decisions.Create(ctx, decisionsMinimalRequest, nil)
	assertDecisionsCancellation(t, err, context.Canceled)
	if len(resolver.seen) != 0 {
		t.Fatalf("getEndpoint calls = %d", len(resolver.seen))
	}
}

func TestDecisionsCreateChecksContextAfterGetEndpoint(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	decisions := copilot.NewSessionDecisionsForTest(func(context.Context, *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error) {
		cancel()
		endpoint := openaiDecisionsEndpoint()
		return &endpoint, nil
	})
	transport := newFakeDecisionsTransport(jsonDecisionsResponse(200, okDecisionsBody(t)))
	_, err := decisions.Create(ctx, decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: transport})
	assertDecisionsCancellation(t, err, context.Canceled)
	if len(transport.calls) != 0 {
		t.Fatalf("calls = %d", len(transport.calls))
	}
}

func hangingDecisionsTransport(onStart func()) *fakeDecisionsTransport {
	return newFakeDecisionsTransport(func(req *http.Request) (*http.Response, error) {
		if onStart != nil {
			onStart()
		}
		<-req.Context().Done()
		return nil, req.Context().Err()
	})
}

func TestDecisionsCreateReturnsCancellationDuringTransport(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	decisions, _ := decisionsWith(openaiDecisionsEndpoint())
	_, err := decisions.Create(ctx, decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: hangingDecisionsTransport(cancel)})
	assertDecisionsCancellation(t, err, context.Canceled)
}

func TestDecisionsCreateReturnsDeadlineExceeded(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	decisions, _ := decisionsWith(openaiDecisionsEndpoint())
	_, err := decisions.Create(ctx, decisionsMinimalRequest, &copilot.DecisionsOptions{Transport: hangingDecisionsTransport(nil)})
	assertDecisionsCancellation(t, err, context.DeadlineExceeded)
}

// ---------------------------------------------------------------------------
// Real HTTP
// ---------------------------------------------------------------------------

func TestDecisionsCreateRoundTripsOverHTTP(t *testing.T) {
	var seenHeaders http.Header
	var seenBody []byte
	ok := okDecisionsBody(t)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seenHeaders = r.Header.Clone()
		seenBody, _ = io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, ok)
	}))
	defer server.Close()

	decisions, _ := decisionsWith(rpc.ProviderEndpoint{
		Type:    rpc.ProviderEndpointTypeOpenai,
		BaseURL: server.URL + "/v1",
		APIKey:  copilot.String("sk-test-0001"),
		Headers: map[string]string{"X-Extra": "1"},
	})
	result, err := decisions.Create(context.Background(), decisionsMinimalRequest, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, isPredicate := result.Answers[0].(copilot.DecisionsPredicateAnswer); !isPredicate {
		t.Fatalf("first answer = %T", result.Answers[0])
	}
	if seenHeaders.Get("Authorization") != "Bearer sk-test-0001" || seenHeaders.Get("X-Extra") != "1" || seenHeaders.Get("Content-Type") != "application/json" {
		t.Fatalf("headers = %v", seenHeaders)
	}
	assertJSONEqual(t, seenBody, decisionsFixture(t, "request.minimal.json"))
}

func TestDecisionsCreateDoesNotFollowRedirects(t *testing.T) {
	var mu sync.Mutex
	var hits []string
	ok := okDecisionsBody(t)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		hits = append(hits, r.URL.Path)
		mu.Unlock()
		if strings.HasSuffix(r.URL.Path, "/decisions") {
			w.Header().Set("Location", "/elsewhere")
			w.WriteHeader(http.StatusTemporaryRedirect)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, ok)
	}))
	defer server.Close()

	decisions, _ := decisionsWith(rpc.ProviderEndpoint{
		Type:    rpc.ProviderEndpointTypeOpenai,
		BaseURL: server.URL + "/v1",
		APIKey:  copilot.String("sk-test-0001"),
		Headers: map[string]string{},
	})
	_, err := decisions.Create(context.Background(), decisionsMinimalRequest, nil)
	de := asDecisionsError(t, err)
	if de.Kind != copilot.DecisionsErrorKindHTTP || de.Status != http.StatusTemporaryRedirect || !strings.Contains(de.Message, "redirects are not followed") {
		t.Fatalf("error = %v %d: %s", de.Kind, de.Status, de.Message)
	}
	mu.Lock()
	defer mu.Unlock()
	if !reflect.DeepEqual(hits, []string{"/v1/decisions"}) {
		t.Fatalf("hits = %v", hits)
	}
}
