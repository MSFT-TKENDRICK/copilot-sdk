# Decisions (experimental)

The Decisions API evaluates supplied evidence and returns typed answers instead of a free-form reply: the probability that a statement is true, a choice from a fixed set, or a score against ordered levels. Use it for tasks such as classifying a support ticket, routing a request, or rating the relevance of a document.

`session.decisions.create(...)` calls OpenAI's `POST /v1/decisions` using **the session's provider endpoint and credentials**. The OpenAI reference currently documents `gpt-6-luna`; the SDK requires an explicit model but does not maintain its own model allowlist. Provider access and deployment availability still apply.

> [!WARNING]
> **This fork's Decisions extension is a preview, not production-ready.** Local tests pass, including a real Copilot CLI connected to a local test provider. Live OpenAI, Azure, and Copilot-managed Decisions requests have not been verified in this fork. Review also found unresolved credential-redaction defects on error paths. Keep evaluation on local test providers with synthetic credentials until those fixes are verified. See [Known limitations](#known-limitations).
>
> The API may change or be removed. It is hand-written on top of the existing `session.provider.getEndpoint` RPC, not a new generated runtime API.

## What has been verified

At implementation commit `a8f5d650`, the Decisions suites passed in all six SDKs: Node.js, Python, Go, .NET, Java, and Rust. They exercise serialization, typed results, endpoint/header handling, credential refresh, cancellation, and bounded response reads using fixtures and test providers. Passing those tests does not invalidate the defects subsequently found by adversarial review.

The [Python runtime tests](../../python/tests/test_decisions_runtime.py) also launch a real CLI process and verify both that an opted-in call reaches a local `/v1/decisions` endpoint and that a call without the runtime opt-in fails before reaching it. The local server returns a fixture; this verifies integration, **not a live model's answers or cloud service availability**. The multi-model design and implementation review has not converged, and the SDK mutation campaign is not complete.

| Provider | Implemented behavior | Verification boundary |
|---|---|---|
| OpenAI BYOK (`type: "openai"`) | Append `/decisions` to the base path, normally `/v1` | Local provider and fixture tests; live OpenAI unverified |
| Azure OpenAI (`type: "azure"`) | Append `/openai/v1/decisions`, or just `/decisions` if the base path already ends in `/openai/v1`; send the deployment name as `model` | URL/header tests; live Azure deployment support unverified |
| Azure with an OpenAI-compatible `/openai/v1` base URL (`type: "openai"`) | Append `/decisions` to that base path | Contract tests; live service unverified |
| Copilot-managed provider (CAPI, without BYOK) | Use the runtime-provided endpoint when permitted; no automatic fallback | Unverified; an available Copilot model does not establish Decisions access |
| Other OpenAI-compatible providers | Send the OpenAI Decisions request shape | Best effort only; supporting chat or Responses does not imply supporting Decisions |
| Anthropic or a WebSocket transport | Reject before HTTP with `unsupportedProvider` or `unsupportedTransport` | Negative tests |

## How it works

1. **Resolve the endpoint.** Each call asks the Copilot runtime for `session.provider.getEndpoint`, optionally with an endpoint model ID. The runtime supplies the provider URL, headers, and credentials. The Decisions client does not independently discover API keys from the environment.
2. **Prepare the request.** The SDK checks whether the endpoint can carry an OpenAI Decisions request, builds its URL and headers, and serializes idiomatic SDK fields into OpenAI's wire format.
3. **Call the provider directly.** The SDK's HTTP client sends the request. The runtime resolves credentials but does not run an agent turn for this operation. The request does **not** pass through `CopilotRequestHandler`; use the per-call HTTP injection option for proxies or custom TLS.
4. **Return typed data.** The SDK parses ordered answers, the response model, and token usage, including cache/reasoning details where supplied. Your application decides what to do with those results.

Only the `input` supplied to the call is evaluated. Session conversation history is not automatically included, and tool-call items in `input` are evidence, not instructions for the SDK to execute tools. Use `send` / `sendAndWait` for an agent conversation or tool-use loop instead.

This placement follows the SDK's session-scoped API groups and reuses an existing runtime primitive. It adds no runtime RPC, generated-code edits, `wireApi: "decisions"` value, or new runtime HTTP dependency. The session's normal `wireApi` setting still concerns its agent-loop calls, not this separate operation.

## Requirements

* **Build from this fork.** The implementation is on [`feature/decisions-api`](https://github.com/MSFT-TKENDRICK/copilot-sdk/tree/feature/decisions-api), proposed in [PR #1](https://github.com/MSFT-TKENDRICK/copilot-sdk/pull/1). Installing a released upstream SDK package does not install this fork's extension.
* **Runtime opt-in.** The runtime only exposes provider endpoints to SDK clients when the CLI process runs with `COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true`. Without it, calls fail with error kind `endpointUnavailable`.
* **An OpenAI or Azure OpenAI provider** that serves `/decisions`. This is typically a [BYOK](../auth/byok.md) provider with `type: "openai"` or `type: "azure"`. Anthropic providers fail with `unsupportedProvider`. Non-HTTP transports fail with `unsupportedTransport`.
* **`model` is required** and is sent verbatim on the wire. For Azure, it is the deployment name. It is never derived from the session model.

For a CLI process started by the SDK, set the opt-in before starting the client, for example in PowerShell:

```powershell
$env:COPILOT_ALLOW_GET_PROVIDER_ENDPOINT = "true"
```

If connecting to an external CLI server, set it in that server's environment before starting it; setting it only in the client process is insufficient. For an in-process runtime, set it in the host process before loading the runtime.

Create a session using the normal [BYOK setup](../auth/byok.md). The `model` in a Decisions request selects the model sent to the provider; it does **not** by itself select a different provider. Use `endpointModelId` (or the language equivalent) only when you also need to ask the runtime for another model's endpoint. Do not assume a chat-compatible endpoint offers `/decisions`.

## Question types

| Type | Answer |
|---|---|
| `predicate` | `probability` that the statement is true |
| `choice` | the chosen `choice`, its `confidence`, and per-option `probabilities` (option values are strings or booleans) |
| `score` | `score`, the probability-weighted average of the **zero-based level indices**, plus `confidence` and per-level `probabilities` |

For three levels named low, medium, and high, their indices are 0, 1, and 2; a score such as `1.37` is valid, not a fourth level. If you need a discrete level, inspect the per-level probabilities rather than treating `score` as an array index.

Answers are returned in question order. Names are optional metadata echoed by the provider, not keys in a result dictionary. A question may produce a `refusal` answer instead of its requested answer type. Unrecognized answer types are preserved as `unknown` answers with their raw payload; extra fields on known answer types are not promised lossless preservation.

Probabilities and confidence values are model judgments, not guaranteed correctness or authorization to act. Evaluate thresholds on representative data before using them to route consequential work, and explicitly handle refusals and unknown answers.

## Inputs, caching, and privacy

`input` is a string or an ordered array of messages, function/custom-tool calls, and their outputs. User messages can include text, images, and JSON; other roles have narrower content types. When passing tool history, associate outputs with calls using `callId` / `call_id`. The SDK does not fetch session history on your behalf.

Optional prompt-cache fields map to `prompt_cache_key` and `prompt_cache_options` on the wire. The current reference documents `ttl: "30m"`. Optional typed request fields set to `null` are omitted. Provider limits, including question counts, remain server-authoritative rather than being enforced as SDK policy.

If using `safetyIdentifier` / `safety_identifier`, supply an opaque identifier appropriate for abuse detection. Do not send a raw email address, username, credential, or other direct identifier; use an appropriate privacy-preserving mapping. Remember that the input itself is sent to the selected provider.

## Example

These are call fragments for an already-created BYOK session using this fork's SDK. They illustrate result handling, not production readiness or proof of live provider access.

<details open>
<summary><strong>TypeScript</strong></summary>

<!-- docs-validate: skip -->
```typescript
const result = await session.decisions.create(
    {
        model: "gpt-6-luna",
        input: "The deploy finished and all health checks are green.",
        questions: [
            { type: "predicate", name: "healthy", instructions: "Is the service healthy?" },
            {
                type: "choice",
                name: "category",
                instructions: "Classify this deployment update.",
                choices: [{ value: "routine" }, { value: "incident" }],
            },
            {
                type: "score",
                name: "urgency",
                instructions: "Rate how urgently this update needs human attention.",
                levels: [{ label: "low" }, { label: "medium" }, { label: "high" }],
            },
        ],
    },
    { signal: AbortSignal.timeout(30_000) },
);

for (const answer of result.answers) {
    switch (answer.type) {
        case "predicate":
            console.log(answer.name, answer.probability);
            break;
        case "choice":
            console.log(answer.name, answer.choice, answer.confidence);
            break;
        case "score":
            console.log(answer.name, answer.score, answer.confidence);
            break;
        case "refusal":
            console.log(answer.name, "No decision: provider refused.");
            break;
        case "unknown":
            console.log(answer.name, "No decision: unsupported answer type.");
            break;
    }
}
```

The signal limits HTTP work, but cannot interrupt a pending runtime endpoint RPC. See the timeout notes below.

</details>
<details>
<summary><strong>Python</strong></summary>

<!-- docs-validate: skip -->
```python
from copilot import DecisionsPredicateAnswer

result = await session.decisions.create(
    {
        "model": "gpt-6-luna",
        "input": "The deploy finished and all health checks are green.",
        "questions": [
            {"type": "predicate", "name": "healthy", "instructions": "Is the service healthy?"},
        ],
    },
    timeout=30.0,
)

answer = result.answers[0]
if isinstance(answer, DecisionsPredicateAnswer):
    print(answer.name, answer.probability)
```

</details>
<details>
<summary><strong>Go</strong></summary>

<!-- docs-validate: skip -->
```go
name := "healthy"
result, err := session.Decisions().Create(ctx, copilot.DecisionsRequest{
	Model: "gpt-6-luna",
	Input: "The deploy finished and all health checks are green.",
	Questions: []copilot.DecisionsQuestion{
		copilot.DecisionsPredicateQuestion{Name: &name, Instructions: "Is the service healthy?"},
	},
}, nil)
if err != nil {
	return err
}
if a, ok := result.Answers[0].(copilot.DecisionsPredicateAnswer); ok {
	fmt.Println(*a.Name, a.Probability)
}
```

</details>
<details>
<summary><strong>.NET</strong></summary>

<!-- docs-validate: skip -->
```csharp
#pragma warning disable GHCP001 // The Decisions API is experimental.
var result = await session.Decisions.CreateAsync(new DecisionsRequest
{
    Model = "gpt-6-luna",
    Input = "The deploy finished and all health checks are green.",
    Questions =
    {
        new DecisionsPredicateQuestion { Name = "healthy", Instructions = "Is the service healthy?" },
    },
});

if (result.Answers[0] is DecisionsPredicateAnswer answer)
{
    Console.WriteLine($"{answer.Name} {answer.Probability}");
}
```

The .NET Decisions types raise the `GHCP001` experimental diagnostic.

</details>
<details>
<summary><strong>Java</strong></summary>

<!-- docs-validate: skip -->
```java
import com.github.copilot.decisions.*;
import java.util.List;

DecisionsResult result = session.getDecisions()
        .create(new DecisionsRequest()
                .setModel("gpt-6-luna")
                .setInput("The deploy finished and all health checks are green.")
                .setQuestions(List.of(new DecisionsQuestion.Predicate("Is the service healthy?"))))
        .get();

if (result.answers().get(0) instanceof DecisionsAnswer.Predicate p) {
    System.out.println(p.probability());
}
```

</details>
<details>
<summary><strong>Rust</strong></summary>

Requires the `network-forwarding` feature.

<!-- docs-validate: skip -->
```rust
use github_copilot_sdk::decisions::*;

let request = DecisionsRequest::new(
    "gpt-6-luna",
    "The deploy finished and all health checks are green.",
    vec![DecisionsQuestion::Predicate(DecisionsPredicateQuestion {
        name: Some("healthy".into()),
        instructions: "Is the service healthy?".into(),
    })],
);

let result = session.decisions().create(request).await?;
if let DecisionsAnswer::Predicate(answer) = &result.answers[0] {
    println!("{:?} {}", answer.name, answer.probability);
}
```

</details>

## Call options

| Option | Meaning |
|---|---|
| Endpoint model id (`endpointModelId`, `endpoint_model_id`, ...) | Passed verbatim as `modelId` to `session.provider.getEndpoint`, to pick a different provider endpoint than the session's current one. Never derived from `model`. |
| Cancellation | `AbortSignal` (TypeScript), task cancellation (Python), `context.Context` (Go), `CancellationToken` (.NET), future cancellation (Java), or dropping the future (Rust). Explicit cancellation uses the platform's native cancellation mechanism, not a Decisions error. |
| Custom HTTP client | For proxies or custom TLS. The SDK's built-in clients never follow redirects. Injected clients are trusted and **must disable redirects**; otherwise credentials could be forwarded to another origin. (.NET and Java reject injected clients that are configured to follow redirects.) |

### Timeouts and retries

**There is no SDK-defined default total-call timeout.** Supply a deadline or cancellation mechanism; HTTP-stack defaults are not a portable whole-call guarantee.

| SDK | Current timeout/deadline behavior |
|---|---|
| TypeScript | Pass `signal`, for example `AbortSignal.timeout(30_000)`. It is checked before and after endpoint resolution and passed to `fetch`; the runtime RPC itself cannot be aborted. |
| Python | `timeout=30.0` bounds the whole call, including resolution and a credential-refresh retry; expiry raises native `TimeoutError`. The default `None` is unbounded. |
| Go | Pass a context created with `context.WithTimeout` / `context.WithDeadline`. |
| .NET | Pass a `CancellationToken` from a timed `CancellationTokenSource` to `CreateAsync`. |
| Java | `DecisionsOptions.setTimeout(Duration)` configures the HTTP request timeout, not a full-call deadline covering endpoint resolution and streaming body reads. Cancel the returned future explicitly; body/deadline hardening remains under review. |
| Rust | Bound the awaited call with the application's async deadline mechanism; dropping the future cancels the operation. |

After a readable HTTP 401 or 403, the SDK resolves the endpoint once more and replays the request only if the effective URL or credentials changed. It does not retry for changes to incidental headers alone. There are no automatic retries for 429, 5xx, or general network failures, and no built-in backoff or `Retry-After` handling. A replay is another provider request and may incur another charge; this is not an exactly-once operation. See the Go error-body limitation below.

## Errors

Failures raise a Decisions error (`DecisionsError`, `DecisionsException`, or the Rust `ErrorKind::Decisions`) with one of these kinds:

| Kind | Meaning |
|---|---|
| `endpointUnavailable` | The runtime could not provide an endpoint, for example because `COPILOT_ALLOW_GET_PROVIDER_ENDPOINT` is not set. |
| `invalidEndpoint` | The endpoint has an invalid base URL or headers. |
| `unsupportedProvider` / `unsupportedTransport` | The provider is not OpenAI or Azure, or the transport is not HTTP. |
| `modelMismatch` | The request `model` conflicts with the endpoint's fixed deployment. |
| `credentialConflict` | The endpoint headers conflict with the SDK's credential header. |
| `http` | A non-2xx response. Includes `status`, OpenAI `errorType`/`errorCode`/`errorParam`, `requestId`, and `body` (at most 64 KiB, with `bodyTruncated`). Redaction has known gaps; do not assume these fields are safe to log. |
| `invalidResponse` | The response is not valid Decisions JSON or exceeds 8 MiB. |
| `network` | The request or response-body read failed in transit. A reduced `cause` is retained, but metadata redaction has known gaps. |

The implementation attempts to redact recognized secrets from HTTP messages, bodies, parsed error fields, and network causes. Its current secret collection uses a minimum of eight Unicode scalar values and includes JSON/URL-encoded forms. This is **not a guarantee that an error is credential-free**. `endpointUnavailable` also preserves the original runtime RPC error as its cause.

Until the redaction fixes land, do not log whole error objects, their bodies/messages, request IDs, or causes. Prefer fixed diagnostic labels and the SDK-defined `kind` and HTTP `status`. This reduces exposure; it does not make the preview production-ready.

## Known limitations

The following remain unresolved in the current implementation; documenting them does not fix them:

* **Error-path credential exposure.** Provider-supplied request IDs and some transport cause metadata bypass redaction. Truncation after earlier replacements can retain a partial secret from the lookahead region, and overlapping secrets are not fully covered. Credentials shorter than the collection threshold are not protected by that redactor.
* **Go interrupted authentication responses.** An unreadable 401/403 body can lead to a credential refresh and replay instead of stopping with a network error.
* **Malformed data and cross-language edge cases.** Examples include Node serializing an unrecognized runtime input-item type as a null array entry and Python exposing `OverflowError` for an extremely large JSON number. Numeric-domain and content-part differences are still being adjudicated.
* **Operational metadata and extensibility.** Success results do not expose the response request ID. Errors do not expose a parsed retry delay. There is a raw input-item escape hatch, but no equivalent raw-question or top-level `extraBody` interface.
* **Incomplete validation.** Live provider calls and SDK mutation coverage remain unverified. The recorded .NET format check did not complete, and Rust all-target Clippy reported a `registered_session_ids` dead-code failure. Neither is counted as a passing check.

## Why not just use the OpenAI or Jev SDK?

If you only need OpenAI Decisions with your own independently managed credentials, the official OpenAI SDK is a reasonable alternative. This extension is useful when an application already has a Copilot session and wants to reuse the provider selection and credential resolution that its runtime owns.

The extension preserves **OpenAI's native contract**, rather than inventing a common wire format for different providers. TypeSafe AI's Jev API is a comparison point, not another backend implemented by this feature:

| Concern | OpenAI Decisions / this extension | TypeSafe AI Jev |
|---|---|---|
| HTTP operation | `POST /v1/decisions` | `POST /v1/systemone` |
| Evidence | `input`: string or ordered input items | `state`, with Jev-specific asset support |
| Questions and answers | Ordered arrays; optional question names | Name-keyed maps |
| Primitives | `predicate`, `choice`, `score`; answers may be refusals | `noul`, `choice`, `score`, `box` |
| Integration here | Typed, session-scoped call using the runtime's endpoint | No Jev endpoint, schema conversion, or `box` support |

Jev/OpenAI SDK conveniences such as richer retry metadata and builders informed the comparison, but are not all implemented here. In particular, this extension does not silently inherit another SDK's retries or timeout defaults. Use the call options and limitations documented above as its actual contract.

## Reproduce the local checks

After setting up the fork's development dependencies, run from `nodejs`:

```powershell
npx --no-install vitest run test\decisions.test.ts
npm run typecheck
```

From `python`, set `COPILOT_CLI_PATH` to a compatible CLI executable and run:

```powershell
$env:COPILOT_CLI_PATH = "C:\path\to\copilot.exe"
uv run --no-sync pytest tests\test_decisions.py tests\test_decisions_runtime.py -q
```

Without `COPILOT_CLI_PATH`, the two runtime tests are skipped. These tests use a local test provider and synthetic credentials, not a live OpenAI account. Fixture coverage and source are in [`test/fixtures/decisions`](../../test/fixtures/decisions), [`nodejs/test/decisions.test.ts`](../../nodejs/test/decisions.test.ts), and [`python/tests/test_decisions.py`](../../python/tests/test_decisions.py).

## See also

* [BYOK providers](../auth/byok.md)
* [OpenAI Decisions guide](https://developers.openai.com/api/docs/guides/decisions)
* [OpenAI create decision reference](https://developers.openai.com/api/reference/resources/decisions/methods/create)
* [TypeSafe AI Jev documentation](https://docs.typesafe.ai/)
