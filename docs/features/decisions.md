# Decisions (experimental)

The Decisions API asks a model to answer one or more structured questions about some input and returns calibrated probabilities instead of free-form text. It is an OpenAI API (`POST /v1/decisions`) that currently supports only `gpt-6-luna`. `session.decisions.create(...)` sends a Decisions request to **the session's own model provider**, using the endpoint and credentials the runtime already resolved for the session.

> [!WARNING]
> This API is **experimental** and may change or be removed. It is not part of the generated RPC surface. It is hand-written on top of the `session.provider.getEndpoint` RPC.

## Requirements

* **Runtime opt-in.** The runtime only exposes provider endpoints to SDK clients when the CLI process runs with `COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true`. Without it, calls fail with error kind `endpointUnavailable`.
* **An OpenAI or Azure OpenAI provider** that serves `/decisions`. This is typically a [BYOK](../auth/byok.md) provider with `type: "openai"` or `type: "azure"`. Anthropic providers fail with `unsupportedProvider`. Non-HTTP transports fail with `unsupportedTransport`.
* **`model` is required** and is sent verbatim on the wire. For Azure, it is the deployment name. It is never derived from the session model.

## Question types

| Type | Answer |
|---|---|
| `predicate` | `probability` that the statement is true |
| `choice` | the chosen `choice`, its `confidence`, and per-option `probabilities` (option values are strings or booleans) |
| `score` | the chosen `score` level, its `confidence`, and per-level `probabilities` |

Answers are returned in question order. A question may also produce a `refusal` answer. Answer types the SDK does not recognize are preserved as `unknown` answers with their raw payload. Each question may have a `name`, which is echoed on its answer.

## Example

<details open>
<summary><strong>TypeScript</strong></summary>

<!-- docs-validate: skip -->
```typescript
const result = await session.decisions.create({
    model: "gpt-6-luna",
    input: "The deploy finished and all health checks are green.",
    questions: [
        { type: "predicate", name: "healthy", instructions: "Is the service healthy?" },
    ],
});

const answer = result.answers[0];
if (answer.type === "predicate") {
    console.log(answer.name, answer.probability);
}
```

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
    }
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
| Cancellation | `AbortSignal` (TypeScript), `timeout` (Python), `context.Context` (Go), `CancellationToken` (.NET), future cancellation (Java), or dropping the future (Rust). Cancellation surfaces as the platform's native cancellation error, not a Decisions error. |
| Custom HTTP client | For proxies or custom TLS. The SDK's built-in clients never follow redirects. Injected clients are trusted and **must disable redirects**; otherwise credentials could be forwarded to another origin. (.NET and Java reject injected clients that are configured to follow redirects.) |

## Errors

Failures raise a Decisions error (`DecisionsError`, `DecisionsException`, or the Rust `ErrorKind::Decisions`) with one of these kinds:

| Kind | Meaning |
|---|---|
| `endpointUnavailable` | The runtime could not provide an endpoint, for example because `COPILOT_ALLOW_GET_PROVIDER_ENDPOINT` is not set. |
| `invalidEndpoint` | The endpoint has an invalid base URL or headers. |
| `unsupportedProvider` / `unsupportedTransport` | The provider is not OpenAI or Azure, or the transport is not HTTP. |
| `modelMismatch` | The request `model` conflicts with the endpoint's fixed deployment. |
| `credentialConflict` | The endpoint headers conflict with the SDK's credential header. |
| `http` | A non-2xx response. Includes `status`, OpenAI `errorType`/`errorCode`/`errorParam`, `requestId`, and a redacted `body` (at most 64 KiB, with `bodyTruncated`). |
| `invalidResponse` | The response is not valid Decisions JSON or exceeds 8 MiB. |
| `network` | The request failed in transit. The `cause` is sanitized. |

On a 401 or 403, the SDK refreshes the endpoint once and retries if the effective URL or credentials changed.

**Credentials are redacted** from Decisions HTTP error messages, response bodies, and parsed error fields, and network causes are sanitized. API keys, session tokens, endpoint header values, and URL query values are redacted (including their JSON- and URL-encoded forms). `endpointUnavailable` errors keep the runtime's original `getEndpoint` RPC error as their cause.

## See also

* [BYOK providers](../auth/byok.md)
* [OpenAI Decisions API reference](https://platform.openai.com/docs/api-reference)
