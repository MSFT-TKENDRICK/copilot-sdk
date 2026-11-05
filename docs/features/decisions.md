# Decisions (experimental)

The Decisions API evaluates supplied evidence and returns typed answers instead of a free-form reply: the probability that a statement is true, a choice from a fixed set, or a score against ordered levels. Use it for tasks such as classifying a support ticket, routing a request, or rating the relevance of a document.

`session.decisions.create(...)` calls OpenAI's `POST /v1/decisions` using **the session's provider endpoint and credentials**. The OpenAI reference currently documents `gpt-6-luna`; the SDK requires an explicit model but does not maintain its own model allowlist. Provider access and deployment availability still apply.

> [!WARNING]
> **This fork's Decisions extension remains a preview, not production-ready.** The hardening pass addresses credential redaction, whole-call deadlines, response validation, and retry behavior, but complete six-SDK verification and mutation evidence are still pending. Live OpenAI and Azure requests have not been verified. Keep evaluation on local test providers with synthetic credentials until the remaining gates pass. See [Known limitations](#known-limitations).
>
> The API may change or be removed. It is hand-written on top of the existing `session.provider.getEndpoint` RPC, not a new generated runtime API.

## What has been verified

At implementation commit `a8f5d650`, the original Decisions suites passed in all six SDKs: Node.js, Python, Go, .NET, Java, and Rust. Adversarial review subsequently exposed gaps in those tests and the implementation. That historical baseline is not production-readiness evidence for the hardened version.

The hardening corpus now has **133 shared cases per SDK**, plus language-specific regressions. Local Decisions suites have passed in Node.js, Python, Go, .NET, and Java. Rust validation, Node package-consumer execution, and the portable CI matrix are still being verified; the native Windows/ARM64 Go toolchain does not support the race detector, so that check runs separately on Linux. The [coverage and integrity tools](../../test/decisions/README.md) distinguish an assertion-backed vector census from runner success and check committed source rather than treating an agent's summary as proof.

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

Relevant upstream precedents include [typed structured outputs across six SDKs (#2590)](https://github.com/github/copilot-sdk/pull/2590), [session-scoped GitHub token providers (#2412)](https://github.com/github/copilot-sdk/pull/2412), and [client-executed MCP search over Responses (#2765)](https://github.com/github/copilot-sdk/pull/2765). They inform the typed, session-scoped, compositional approach; they do not constitute upstream approval of this fork.

The [request for user-delegated billing and a simple prompt/response API (#182)](https://github.com/github/copilot-sdk/issues/182) is a related use case, not a capability this extension promises. Reusing a session endpoint does not grant new model entitlements or establish billing-transfer semantics.

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

Question instructions support text, images, and JSON. They do not accept input-only cache breakpoints. Unsupported typed input items and invalid role/content combinations fail explicitly rather than silently becoming null array entries.

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
    { timeoutMs: 30_000 },
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

This sets the SDK-owned deadline for the entire call. Pass an `AbortSignal` separately when the caller also needs native cancellation.

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
| Total-call timeout | Defaults to 600 seconds, including endpoint lookup, both HTTP attempts, response bodies, and credential refresh. See the language-specific options below. |
| Cancellation | `AbortSignal` (TypeScript), task cancellation (Python), `context.Context` (Go), `CancellationToken` (.NET), future cancellation (Java), or dropping the future (Rust). Explicit cancellation uses the platform's native cancellation mechanism, not a Decisions error. |
| Custom HTTP client | For proxies or custom TLS. The SDK's built-in clients never follow redirects. Injected clients are trusted and **must disable redirects**; otherwise credentials could be forwarded to another origin. (.NET and Java reject injected clients that are configured to follow redirects.) |

### Timeouts and retries

**The SDK-defined default total-call deadline is 600 seconds.** It covers endpoint lookup, HTTP headers and bodies, and the optional credential-refresh replay without resetting the budget between phases. SDK-owned expiry becomes error kind `timeout`; explicit caller cancellation remains native.

| SDK | Configure or disable the SDK deadline |
|---|---|
| TypeScript | `timeoutMs: 30_000`; omitted uses the default, `null` disables it. Other values must be finite and positive. |
| Python | `timeout=30.0`; omitted uses the default, `None` disables it. Other values must be finite and positive. |
| Go | `DecisionsOptions.Timeout` is a duration pointer; nil uses the default, a pointer to zero disables it, and negative values are invalid. Caller context deadlines remain independent. |
| .NET | `DecisionsOptions.Timeout` is a nullable `TimeSpan`; null uses the default, `Timeout.InfiniteTimeSpan` disables it, and other values must be positive. |
| Java | `DecisionsOptions.setTimeout(Duration)` now bounds the whole call; `Duration.ZERO` disables it and negative values are invalid. |
| Rust | Existing `create` and `create_with_options` calls use the default. The additive `create_with_timeout(request, options, Option<Duration>)` accepts `None` to disable it; zero expires before I/O. |

An endpoint RPC that cannot be cancelled may finish later, but the SDK stops waiting and must not dispatch HTTP after expiry. Synchronous serialization cannot be preempted; expiry is checked before subsequent I/O. Injected transports remain responsible for their own cancellation/resource behavior, and an earlier caller-configured HTTP-stack timeout is distinct from the SDK deadline.

After a successfully processed bounded HTTP 401 or 403 body, the SDK resolves the endpoint once more and replays the request only if the effective URL or credentials changed. An unreadable authentication body stops with a network, timeout, or cancellation outcome; it does not trigger refresh. Incidental header changes alone do not cause replay. An invalid refreshed plan preserves the original HTTP error; an unsuccessful refresh lookup becomes a safe `endpointUnavailable` error.

There are **no automatic retries for 429, 5xx, or general network failures**, and no built-in backoff. Errors may expose advisory `retryAfterMs` (or the language equivalent): valid `retry-after-ms` takes precedence over numeric or canonical GMT `Retry-After`; fractional delays round up to milliseconds and past dates clamp to zero. Invalid or overflowing hints are ignored. Your application chooses whether and when to retry.

A replay is another provider request and may incur another charge; this is not an exactly-once operation.

## Errors

Failures raise a Decisions error (`DecisionsError`, `DecisionsException`, or the Rust `ErrorKind::Decisions`) with one of these kinds:

| Kind | Meaning |
|---|---|
| `endpointUnavailable` | The runtime could not provide an endpoint, for example because `COPILOT_ALLOW_GET_PROVIDER_ENDPOINT` is not set. |
| `invalidEndpoint` | The endpoint has an invalid base URL or headers. |
| `unsupportedProvider` / `unsupportedTransport` | The provider is not OpenAI or Azure, or the transport is not HTTP. |
| `modelMismatch` | The request `model` conflicts with the endpoint's fixed deployment. |
| `credentialConflict` | The endpoint headers conflict with the SDK's credential header. |
| `http` | A non-2xx response. Includes `status`, OpenAI `errorType`/`errorCode`/`errorParam`, `requestId`, advisory `retryAfterMs`, and bounded/redacted `body` with `bodyTruncated` where available. |
| `invalidResponse` | The response is not valid Decisions JSON or exceeds 8 MiB. |
| `network` | The request or response-body read failed in transit. A reduced, redacted diagnostic cause may be retained, not the raw provider exception. |
| `timeout` | The SDK-owned total-call deadline expired. This is not the caller's native cancellation outcome. |

Known numeric measurements must be finite. Missing usage counts default to zero, but present malformed counts are rejected rather than coerced. Native SDKs preserve signed 64-bit integer tokens; TypeScript rejects integers outside JavaScript's safe range instead of silently rounding them. Integral decimal/exponent forms are accepted only within the portable safe-integer range.

The hardened redactor protects every nonempty configured credential after space/tab trimming, including short credentials. Other header/query candidates retain the eight-Unicode-scalar heuristic. Literal, JSON-escaped, percent/form-encoded, and raw-query representations are tracked across both attempts. Matches are found against the original text and overlapping intervals are merged before replacement.

HTTP diagnostic bodies retain at most the original first 64 KiB of UTF-8, with bounded secret-matching lookahead that is never copied into retained output. Replacement expansion is capped again. `bodyTruncated` describes original overflow, not the size after redaction. Provider-controlled request IDs, error fields, and retained cause strings are also bounded/redacted; endpoint lookup failures do not retain raw runtime exception chains.

This is not general PII removal, arbitrary-encoding detection, or memory erasure. Successful results and unknown raw payloads can contain sensitive provider content; runtime/transport tracing, debugger dumps, and caller-owned logs are outside this Decisions-error guarantee. Prefer fixed labels, `kind`, and `status` over logging whole error objects or response bodies.

## Known limitations

* **Incomplete verification.** The complete hardened six-SDK build/test/format matrix and isolated SDK mutation campaign are not yet accepted as passing. Earlier failed or interrupted runs are not counted as success, even when their shared-vector assertions passed.
* **Live access is unverified.** Local providers and a real CLI do not prove OpenAI/Azure entitlement, deployment compatibility, or live model behavior. Copilot-managed access has an additional runtime entitlement boundary; there is no fallback or bypass.
* **Operational metadata and extensibility.** Success results do not expose the response request ID. There is a raw input-item escape hatch, but no equivalent raw-question or top-level `extraBody` interface. Advisory retry hints do not imply those other capabilities.
* **Transport and diagnostic boundaries.** Injected clients must preserve redirect safety and cancellation/resource behavior. Redaction is limited to recognized credential representations in SDK diagnostics, not arbitrary successful data or application logs.
* **Intentional numeric differences.** TypeScript cannot represent every native signed 64-bit integer exactly and rejects unsafe values. All SDKs preserve fractional scalar scores; callers must not treat them as array indices.

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

Jev/OpenAI SDK conveniences informed the comparison, but are not all implemented here. This extension defines its own explicit whole-call deadline and advisory retry-hint behavior; it does not silently inherit another SDK's automatic retries. Optional conveniences are not wire-conformance requirements merely because another SDK exposes them.

## Reproduce the local checks

After setting up the fork's development dependencies, run from `nodejs`:

```powershell
npx --no-install vitest run test\decisions.test.ts
npm run typecheck
npx --no-install tsc --noEmit -p tsconfig.test.json
```

From `python`, set `COPILOT_CLI_PATH` to a compatible CLI executable and run:

```powershell
$env:COPILOT_CLI_PATH = "C:\path\to\copilot.exe"
uv run --no-sync pytest tests\test_decisions.py tests\test_decisions_production.py tests\test_decisions_runtime.py test_jsonrpc.py test_rpc_timeout.py -q
```

Without `COPILOT_CLI_PATH`, the two runtime tests are skipped; that does not satisfy the runtime-integration gate. They use a local provider and synthetic credentials, not a live OpenAI account. See [evidence-tool instructions](../../test/decisions/README.md) for census capture and committed-range integrity verification. Fixture coverage and source are in [`test/fixtures/decisions`](../../test/fixtures/decisions), [`nodejs/test/decisions.test.ts`](../../nodejs/test/decisions.test.ts), and [`python/tests/test_decisions_production.py`](../../python/tests/test_decisions_production.py).

## See also

* [BYOK providers](../auth/byok.md)
* [OpenAI Decisions guide](https://developers.openai.com/api/docs/guides/decisions)
* [OpenAI create decision reference](https://developers.openai.com/api/reference/resources/decisions/methods/create)
* [TypeSafe AI Jev documentation](https://docs.typesafe.ai/)
