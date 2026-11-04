package copilot

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"

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
