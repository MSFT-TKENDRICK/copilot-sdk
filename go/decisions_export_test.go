package copilot

import (
	"context"

	"github.com/github/copilot-sdk/go/rpc"
)

// Test hooks for the external decisions tests.

const (
	DecisionsMaxErrorBodyBytesForTest = decisionsMaxErrorBodyBytes
	DecisionsMaxResponseBytesForTest  = decisionsMaxResponseBytes
)

func NewSessionDecisionsForTest(resolve func(ctx context.Context, params *rpc.SessionProviderGetEndpointRequest) (*rpc.ProviderEndpoint, error)) *SessionDecisions {
	return &SessionDecisions{resolve: resolve}
}

func BuildDecisionsURLForTest(endpoint *rpc.ProviderEndpoint) (string, error) {
	return buildDecisionsURL(endpoint.Type, endpoint.BaseURL)
}

// PlanDecisionsAttemptForTest returns the planned URL and outbound headers.
func PlanDecisionsAttemptForTest(endpoint *rpc.ProviderEndpoint, model string) (string, map[string]string, error) {
	attempt, err := planDecisionsAttempt(endpoint, model)
	if err != nil {
		return "", nil, err
	}
	headers := make(map[string]string, len(attempt.headers))
	for _, h := range attempt.headers {
		headers[h[0]] = h[1]
	}
	return attempt.url, headers, nil
}

func ParseDecisionsResultForTest(data []byte) (*DecisionsResult, error) {
	return parseDecisionsResult(data)
}
