import { readFileSync } from "node:fs";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";
import {
    CopilotSession,
    DecisionsError,
    type DecisionsAnswer,
    type DecisionsChoiceOption,
    type DecisionsInputItem,
    type DecisionsRequest,
    type SessionDecisionsApi,
} from "../src/index.js";
import {
    buildDecisionsHeaders,
    buildDecisionsUrl,
    MAX_ERROR_BODY_BYTES,
    MAX_RESPONSE_BYTES,
    parseDecisionsResult,
    planDecisionsAttempt,
    serializeDecisionsRequest,
    SessionDecisions,
} from "../src/decisions.js";
import type { ProviderEndpoint } from "../src/generated/rpc.js";

const fixtureDir = join(
    dirname(fileURLToPath(import.meta.url)),
    "..",
    "..",
    "test",
    "fixtures",
    "decisions"
);
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const fixture = (name: string): any => JSON.parse(readFileSync(join(fixtureDir, name), "utf8"));
const vectors = fixture("vectors.json");

const OK_BODY = JSON.stringify(fixture("response.full.json"));
const minimalRequest: DecisionsRequest = {
    model: "gpt-6-luna",
    input: "The export button does nothing when clicked.",
    questions: [{ type: "predicate", instructions: "Is this ticket reporting a product defect?" }],
};

const fullRequest: DecisionsRequest = {
    model: "gpt-6-luna",
    input: [
        { type: "message", role: "developer", content: "You triage incoming support tickets." },
        {
            type: "message",
            role: "system",
            content: [{ type: "input_text", text: "Answer only from the provided context." }],
        },
        {
            type: "message",
            role: "user",
            content: [
                {
                    type: "input_text",
                    text: "Ticket #4821: the export button does nothing.",
                    promptCacheBreakpoint: { mode: "explicit" },
                },
                {
                    type: "input_image",
                    imageUrl: "data:image/png;base64,iVBORw0KGgo=",
                    detail: "high",
                    promptCacheBreakpoint: { mode: "explicit" },
                },
                { type: "input_json", data: { plan: "enterprise", seats: 120 } },
                { type: "input_json", data: [{ id: 1 }, { id: 2 }] },
            ],
        },
        {
            role: "user",
            content: [
                { type: "input_text", text: "Customer replied: still broken after refresh." },
                {
                    type: "input_image",
                    imageUrl: "https://example.com/full.png",
                    detail: "original",
                },
            ],
        },
        {
            type: "message",
            role: "assistant",
            content: [
                { type: "output_text", text: "I will look up the account." },
                { type: "refusal", refusal: "I cannot share internal notes." },
            ],
            phase: "commentary",
        },
        {
            type: "function_call",
            callId: "call_1",
            name: "lookup_account",
            arguments: '{"ticket":4821}',
            id: "fc_1",
            namespace: "crm",
            status: "completed",
        },
        {
            type: "function_call_output",
            callId: "call_1",
            output: '{"tier":"enterprise"}',
            id: "fco_1",
            status: "completed",
        },
        {
            type: "function_call_output",
            callId: "call_2",
            output: [
                { type: "input_text", text: "Screenshot attached." },
                { type: "input_image", imageUrl: "https://example.com/shot.png", detail: "low" },
            ],
        },
        {
            type: "custom_tool_call",
            callId: "call_3",
            name: "grep_logs",
            input: "export failed",
            id: "ctc_1",
            namespace: "ops",
            status: "incomplete",
        },
        {
            type: "custom_tool_call_output",
            callId: "call_3",
            output: "3 matches",
            id: "ctco_1",
            status: "in_progress",
        },
        { raw: { type: "future_item", payload: { anything: [1, true, null] } } },
    ],
    questions: [
        {
            type: "predicate",
            name: "is_bug",
            instructions: "Is this ticket reporting a product defect?",
        },
        {
            type: "choice",
            name: "team",
            instructions: "Which team should own this ticket?",
            choices: [
                { value: "frontend", description: "UI and client code" },
                { value: "backend" },
            ],
        },
        {
            type: "choice",
            instructions: [
                { type: "input_text", text: "Should we escalate?" },
                {
                    type: "input_image",
                    imageUrl: "https://example.com/policy.png",
                    detail: "auto",
                },
                { type: "input_json", data: { sla_hours: 4 } },
            ],
            choices: [{ value: true, description: "Escalate now" }, { value: false }],
        },
        {
            type: "score",
            name: "severity",
            instructions: "How severe is the impact?",
            levels: [
                { label: "low", description: "Cosmetic" },
                { label: "medium" },
                { label: "high", description: "Blocks work" },
            ],
        },
    ],
    promptCacheKey: "support-triage-v1",
    promptCacheOptions: { mode: "explicit", ttl: "30m" },
    safetyIdentifier: "user-7f3a9c",
};

interface RecordedCall {
    url: string;
    init: RequestInit;
}

function fakeFetch(responses: (() => Response)[]): {
    fetch: typeof fetch;
    calls: RecordedCall[];
} {
    const calls: RecordedCall[] = [];
    let index = 0;
    const impl = (async (input: string | URL | Request, init?: RequestInit) => {
        calls.push({ url: String(input), init: init ?? {} });
        const next = responses[Math.min(index++, responses.length - 1)];
        return next();
    }) as typeof fetch;
    return { fetch: impl, calls };
}

const jsonResponse =
    (status: number, body: string, headers: Record<string, string> = {}) =>
    () =>
        new Response(body, {
            status,
            headers: { "content-type": "application/json", ...headers },
        });

function decisionsWith(endpoints: ProviderEndpoint[], seen: unknown[] = []): SessionDecisions {
    let index = 0;
    return new SessionDecisions(async (params) => {
        seen.push(params);
        return endpoints[Math.min(index++, endpoints.length - 1)];
    });
}

const openaiEndpoint: ProviderEndpoint = {
    type: "openai",
    baseUrl: "https://api.openai.com/v1",
    apiKey: "sk-test-0001",
    headers: {},
};

async function captureError(promise: Promise<unknown>): Promise<unknown> {
    try {
        await promise;
    } catch (error) {
        return error;
    }
    throw new Error("Expected promise to reject.");
}

describe("decisions serialization", () => {
    it("serializes the full typed request to the golden wire fixture", () => {
        expect(serializeDecisionsRequest(fullRequest)).toEqual(fixture("request.full.json"));
    });

    it("serializes the minimal request", () => {
        expect(serializeDecisionsRequest(minimalRequest)).toEqual(
            fixture("request.minimal.json")
        );
    });

    it("normalizes explicit nulls to omission", () => {
        const withNulls: DecisionsRequest = {
            model: "gpt-6-luna",
            input: [
                {
                    type: "message",
                    role: "user",
                    content: [
                        {
                            type: "input_image",
                            imageUrl: "https://example.com/a.png",
                            detail: null,
                            promptCacheBreakpoint: null,
                        },
                    ],
                },
                { type: "message", role: "assistant", content: "ok", phase: null },
                {
                    type: "function_call",
                    callId: "c",
                    name: "n",
                    arguments: "{}",
                    id: null,
                    namespace: null,
                    status: null,
                },
            ],
            questions: [
                {
                    type: "choice",
                    name: null,
                    instructions: "q",
                    choices: [{ value: "a", description: null }],
                },
                { type: "score", instructions: "s", levels: [{ label: "x", description: null }] },
            ],
            promptCacheKey: null,
            promptCacheOptions: null,
            safetyIdentifier: null,
        };
        expect(serializeDecisionsRequest(withNulls)).toEqual({
            model: "gpt-6-luna",
            input: [
                {
                    type: "message",
                    role: "user",
                    content: [{ type: "input_image", image_url: "https://example.com/a.png" }],
                },
                { type: "message", role: "assistant", content: "ok" },
                { type: "function_call", call_id: "c", name: "n", arguments: "{}" },
            ],
            questions: [
                { type: "choice", instructions: "q", choices: [{ value: "a" }] },
                { type: "score", instructions: "s", levels: [{ label: "x" }] },
            ],
        });
    });

    it("requires model", () => {
        expect(() => serializeDecisionsRequest({ ...minimalRequest, model: "" })).toThrow(
            /model is required/
        );
    });
});

describe("decisions response parsing", () => {
    it("parses the full response fixture", () => {
        const result = parseDecisionsResult(fixture("response.full.json"));
        expect(result.model).toBe("gpt-6-luna-2026-10-01");
        expect(result.usage).toEqual({
            inputTokens: 812,
            inputTokensDetails: { cachedTokens: 512, cacheWriteTokens: 128 },
            outputTokens: 24,
            outputTokensDetails: { reasoningTokens: 16 },
            totalTokens: 836,
        });
        const [predicate, choice, boolChoice, score, refusal, unknown] = result.answers;
        expect(predicate).toEqual({ type: "predicate", name: "is_bug", probability: 0.93 });
        expect(choice).toMatchObject({ type: "choice", name: "team", choice: "frontend" });
        expect(boolChoice).toMatchObject({ type: "choice", name: null, choice: true });
        if (boolChoice.type !== "choice") throw new Error("expected choice");
        expect(boolChoice.probabilities.map((p) => p.value)).toEqual([true, false]);
        expect(typeof boolChoice.choice).toBe("boolean");
        expect(score).toMatchObject({ type: "score", name: "severity", score: 1.37 });
        if (score.type !== "score") throw new Error("expected score");
        expect(score.probabilities.map((p) => p.value)).toEqual([0, 1, 2]);
        expect(refusal).toEqual({ type: "refusal", name: "pii_check" });
        expect(unknown).toEqual({
            type: "unknown",
            name: "future_kind",
            wireType: "ranking",
            raw: { type: "ranking", name: "future_kind", ranked: ["a", "b"] },
        });
    });

    it("rejects malformed responses with invalidResponse", () => {
        const bad: unknown[] = [
            null,
            [],
            { answers: [], model: "m" },
            { answers: [{ type: "predicate" }], model: "m", usage: {} },
            {
                answers: [{ type: "choice", choice: 3, confidence: 1, probabilities: [] }],
                model: "m",
                usage: {},
            },
            {
                answers: [
                    {
                        type: "score",
                        score: 1,
                        confidence: 1,
                        probabilities: [{ label: "a", value: 0.5, probability: 1 }],
                    },
                ],
                model: "m",
                usage: {},
            },
        ];
        for (const value of bad) {
            expect(() => parseDecisionsResult(value)).toThrow(
                expect.objectContaining({ kind: "invalidResponse" })
            );
        }
    });
});

describe("decisions vectors", () => {
    describe("url", () => {
        for (const v of vectors.url) {
            it(v.name, () => {
                if (v.error) {
                    let thrown: unknown;
                    try {
                        buildDecisionsUrl(v.endpoint);
                    } catch (error) {
                        thrown = error;
                    }
                    expect(thrown).toMatchObject({ kind: v.error });
                    expect((thrown as Error).message).not.toContain("secretpassword");
                } else {
                    expect(buildDecisionsUrl(v.endpoint)).toBe(v.expected);
                }
            });
        }
    });

    describe("headers", () => {
        for (const v of vectors.headers) {
            it(v.name, () => {
                if (v.error) {
                    expect(() => planDecisionsAttempt(v.endpoint, "gpt-6-luna")).toThrow(
                        expect.objectContaining({ kind: v.error })
                    );
                } else {
                    expect(planDecisionsAttempt(v.endpoint, "gpt-6-luna").headers).toEqual(
                        v.expected
                    );
                    expect(buildDecisionsHeaders(v.endpoint)).toEqual(v.expected);
                }
            });
        }
    });

    describe("preflight", () => {
        for (const v of vectors.preflight) {
            it(v.name, () => {
                if (v.error) {
                    expect(() => planDecisionsAttempt(v.endpoint, v.model)).toThrow(
                        expect.objectContaining({ kind: v.error })
                    );
                } else {
                    expect(() => planDecisionsAttempt(v.endpoint, v.model)).not.toThrow();
                }
            });
        }
    });

    describe("retry", () => {
        for (const v of vectors.retry) {
            it(v.name, async () => {
                const { fetch, calls } = fakeFetch([
                    jsonResponse(401, '{"error":{"type":"auth","code":"expired"}}'),
                    jsonResponse(200, OK_BODY),
                ]);
                const seen: unknown[] = [];
                const promise = decisionsWith([v.first, v.second], seen).create(minimalRequest, {
                    fetch,
                });
                if (v.retry) {
                    await expect(promise).resolves.toMatchObject({
                        model: "gpt-6-luna-2026-10-01",
                    });
                    expect(calls).toHaveLength(2);
                    expect(calls[1].url).toBe(buildDecisionsUrl(v.second));
                } else {
                    const error = await captureError(promise);
                    expect(error).toBeInstanceOf(DecisionsError);
                    expect(error).toMatchObject({
                        kind: "http",
                        status: 401,
                        errorCode: "expired",
                    });
                    expect(calls).toHaveLength(1);
                }
                expect(seen).toHaveLength(2);
            });
        }

        it("retries at most once", async () => {
            const { fetch, calls } = fakeFetch([jsonResponse(403, "{}")]);
            const error = await captureError(
                decisionsWith([
                    openaiEndpoint,
                    { ...openaiEndpoint, apiKey: "sk-test-0002" },
                    { ...openaiEndpoint, apiKey: "sk-test-0003" },
                ]).create(minimalRequest, { fetch })
            );
            expect(error).toMatchObject({ kind: "http", status: 403 });
            expect(calls).toHaveLength(2);
        });
    });

    describe("redactionLength", () => {
        for (const v of vectors.redactionLength) {
            it(v.name, async () => {
                const { fetch } = fakeFetch([
                    () => new Response(`echo:${v.value.trim()}:end`, { status: 400 }),
                ]);
                const error = (await captureError(
                    decisionsWith([{ ...openaiEndpoint, apiKey: v.value }]).create(minimalRequest, {
                        fetch,
                    })
                )) as DecisionsError;
                expect(error.body).toBe(
                    v.isSecret ? "echo:[REDACTED]:end" : `echo:${v.value.trim()}:end`
                );
            });
        }
    });

    describe("redaction", () => {
        for (const v of vectors.redaction) {
            it(v.name, async () => {
                const payload =
                    (v.bodyPrefix ? String(v.bodyPrefix.char).repeat(v.bodyPrefix.count) : "") +
                    (v.bodyRepeat
                        ? v.body.replaceAll(
                              "{{repeat}}",
                              String(v.bodyRepeat.text).repeat(v.bodyRepeat.count)
                          )
                        : v.body);
                const { fetch } = fakeFetch([
                    () =>
                        new Response(payload, {
                            status: v.status,
                            headers: { "x-request-id": "req_123" },
                        }),
                ]);
                const error = (await captureError(
                    decisionsWith([v.endpoint]).create(minimalRequest, { fetch })
                )) as DecisionsError;
                expect(error).toBeInstanceOf(DecisionsError);
                expect(error.kind).toBe("http");
                expect(error.status).toBe(v.status);
                expect(error.requestId).toBe("req_123");
                expect(error.errorCode ?? null).toBe(v.errorCode);
                expect(error.errorType ?? null).toBe(v.errorType);
                if (v.bodyTruncated !== undefined) {
                    expect(error.bodyTruncated).toBe(v.bodyTruncated);
                }
                if (v.maxBodyBytes !== undefined) {
                    expect(new TextEncoder().encode(error.body ?? "").byteLength).toBeLessThanOrEqual(
                        v.maxBodyBytes
                    );
                }
                const surfaces = [
                    error.message,
                    error.body ?? "",
                    error.errorCode ?? "",
                    error.errorType ?? "",
                    error.errorParam ?? "",
                    String(error),
                    JSON.stringify(error),
                ].join("\n");
                for (const secret of v.mustNotContain) {
                    expect(surfaces).not.toContain(secret);
                }
                if (!error.bodyTruncated) expect(error.body).toContain("[REDACTED]");
            });
        }
    });
});

describe("session.decisions.create", () => {
    it("posts the serialized request with the planned headers", async () => {
        const { fetch, calls } = fakeFetch([jsonResponse(200, OK_BODY)]);
        const seen: unknown[] = [];
        const result = await decisionsWith([openaiEndpoint], seen).create(fullRequest, { fetch });
        expect(result.answers).toHaveLength(6);
        expect(seen).toEqual([undefined]);
        expect(calls).toHaveLength(1);
        expect(calls[0].url).toBe("https://api.openai.com/v1/decisions");
        expect(calls[0].init.method).toBe("POST");
        expect(calls[0].init.redirect).toBe("manual");
        expect(calls[0].init.headers).toEqual({
            authorization: "Bearer sk-test-0001",
            "content-type": "application/json",
            accept: "application/json",
        });
        expect(JSON.parse(String(calls[0].init.body))).toEqual(fixture("request.full.json"));
    });

    it("passes endpointModelId verbatim and never derives it from request.model", async () => {
        const { fetch } = fakeFetch([jsonResponse(200, OK_BODY)]);
        const seen: unknown[] = [];
        await decisionsWith([openaiEndpoint], seen).create(minimalRequest, {
            fetch,
            endpointModelId: "endpoint-model",
        });
        expect(seen).toEqual([{ modelId: "endpoint-model" }]);
    });

    it("calls getEndpoint on every create", async () => {
        const { fetch } = fakeFetch([jsonResponse(200, OK_BODY)]);
        const seen: unknown[] = [];
        const decisions = decisionsWith([openaiEndpoint], seen);
        await decisions.create(minimalRequest, { fetch });
        await decisions.create(minimalRequest, { fetch });
        expect(seen).toHaveLength(2);
    });

    it("maps the OpenAI error envelope", async () => {
        const { fetch } = fakeFetch([
            jsonResponse(400, JSON.stringify(fixture("error.json")), { "x-request-id": "req_9" }),
        ]);
        const error = (await captureError(
            decisionsWith([openaiEndpoint]).create(minimalRequest, { fetch })
        )) as DecisionsError;
        expect(error).toMatchObject({
            kind: "http",
            status: 400,
            errorType: "invalid_request_error",
            errorCode: "unsupported_model",
            errorParam: "model",
            requestId: "req_9",
            bodyTruncated: false,
        });
        expect(error.message).toContain("does not support the Decisions API");
    });

    it("bounds large error bodies at 64 KiB", async () => {
        const big = "x".repeat(MAX_ERROR_BODY_BYTES + 1000);
        const { fetch } = fakeFetch([() => new Response(big, { status: 500 })]);
        const error = (await captureError(
            decisionsWith([openaiEndpoint]).create(minimalRequest, { fetch })
        )) as DecisionsError;
        expect(error.kind).toBe("http");
        expect(error.bodyTruncated).toBe(true);
        expect(error.body).toHaveLength(MAX_ERROR_BODY_BYTES);
    });

    it("reports non-JSON success bodies as invalidResponse", async () => {
        const { fetch } = fakeFetch([() => new Response("<html>", { status: 200 })]);
        await expect(
            decisionsWith([openaiEndpoint]).create(minimalRequest, { fetch })
        ).rejects.toMatchObject({ kind: "invalidResponse" });
    });

    it("wraps transport failures as network errors with a sanitized cause", async () => {
        const failing = (async () => {
            const error = new TypeError(
                "fetch failed for https://api.openai.com/v1/decisions with Bearer sk-test-0001"
            ) as TypeError & { code: string; headers: unknown };
            error.code = "ECONNRESET";
            error.headers = { authorization: "Bearer sk-test-0001" };
            throw error;
        }) as typeof fetch;
        const error = (await captureError(
            decisionsWith([openaiEndpoint]).create(minimalRequest, { fetch: failing })
        )) as DecisionsError;
        expect(error.kind).toBe("network");
        const cause = error.cause as Error & { code?: string; headers?: unknown };
        expect(cause.name).toBe("TypeError");
        expect(cause.code).toBe("ECONNRESET");
        expect(cause.headers).toBeUndefined();
        expect(cause.message).not.toContain("sk-test-0001");
    });

    it("propagates endpointUnavailable when the refresh getEndpoint fails", async () => {
        let calls = 0;
        const decisions = new SessionDecisions(async () => {
            if (calls++ === 0) return openaiEndpoint;
            throw new Error("runtime went away");
        });
        const { fetch } = fakeFetch([jsonResponse(401, "{}")]);
        await expect(decisions.create(minimalRequest, { fetch })).rejects.toMatchObject({
            kind: "endpointUnavailable",
        });
    });

    it("rethrows native abort when cancelled during the refresh getEndpoint", async () => {
        const controller = new AbortController();
        let calls = 0;
        const decisions = new SessionDecisions(async () => {
            if (calls++ === 1) controller.abort();
            return openaiEndpoint;
        });
        const { fetch } = fakeFetch([jsonResponse(401, "{}")]);
        const error = await captureError(
            decisions.create(minimalRequest, { fetch, signal: controller.signal })
        );
        expect(error).not.toBeInstanceOf(DecisionsError);
        expect((error as Error).name).toBe("AbortError");
    });

    it("validates the endpoint before serializing the request", async () => {
        const malformed = { model: "gpt-6-luna", input: "x" } as unknown as DecisionsRequest;
        await expect(
            decisionsWith([
                { type: "anthropic", baseUrl: "https://api.anthropic.com", headers: {} },
            ]).create(malformed)
        ).rejects.toMatchObject({ kind: "unsupportedProvider" });
    });

    it("rejects a missing model before calling getEndpoint", async () => {
        const seen: unknown[] = [];
        await expect(
            decisionsWith([openaiEndpoint], seen).create({
                ...minimalRequest,
                model: undefined as unknown as string,
            })
        ).rejects.toThrow(TypeError);
        expect(seen).toHaveLength(0);
    });

    it("drains the 401 body before retrying", async () => {
        const events: string[] = [];
        let attempt = 0;
        const fetchImpl = (async () => {
            events.push(`fetch${attempt}`);
            if (attempt++ === 0) {
                const stream = new ReadableStream<Uint8Array>({
                    pull(controller) {
                        events.push("read");
                        controller.enqueue(new TextEncoder().encode("{}"));
                        controller.close();
                    },
                });
                return new Response(stream, { status: 401 });
            }
            return new Response(OK_BODY, { status: 200 });
        }) as typeof fetch;
        await decisionsWith([openaiEndpoint, { ...openaiEndpoint, apiKey: "sk-test-0002" }]).create(
            minimalRequest,
            { fetch: fetchImpl }
        );
        expect(events.indexOf("read")).toBeLessThan(events.indexOf("fetch1"));
    });

    it("truncates error bodies on a UTF-8 character boundary", async () => {
        const payload = "x".repeat(MAX_ERROR_BODY_BYTES - 1) + "é".repeat(10);
        const { fetch } = fakeFetch([() => new Response(payload, { status: 500 })]);
        const error = (await captureError(
            decisionsWith([openaiEndpoint]).create(minimalRequest, { fetch })
        )) as DecisionsError;
        expect(error.bodyTruncated).toBe(true);
        expect(new TextEncoder().encode(error.body ?? "").byteLength).toBe(MAX_ERROR_BODY_BYTES - 1);
        expect(error.body).not.toContain("\uFFFD");
    });

    it("cancels oversized error bodies", async () => {
        let cancelled = false;
        const stream = new ReadableStream<Uint8Array>({
            pull(controller) {
                controller.enqueue(new Uint8Array(16 * 1024).fill(120));
            },
            cancel() {
                cancelled = true;
            },
        });
        const { fetch } = fakeFetch([() => new Response(stream, { status: 500 })]);
        const error = (await captureError(
            decisionsWith([openaiEndpoint]).create(minimalRequest, { fetch })
        )) as DecisionsError;
        expect(error.bodyTruncated).toBe(true);
        expect(cancelled).toBe(true);
    });

    for (const status of [200, 500]) {
        it(`maps a mid-body stream failure on HTTP ${status} to network`, async () => {
            let sent = false;
            const stream = new ReadableStream<Uint8Array>({
                pull(controller) {
                    if (!sent) {
                        sent = true;
                        controller.enqueue(new TextEncoder().encode('{"answers":'));
                    } else {
                        controller.error(new Error("socket hang up"));
                    }
                },
            });
            const { fetch } = fakeFetch([() => new Response(stream, { status })]);
            const error = (await captureError(
                decisionsWith([openaiEndpoint]).create(minimalRequest, { fetch })
            )) as DecisionsError;
            expect(error).toBeInstanceOf(DecisionsError);
            expect(error.kind).toBe("network");
            expect((error.cause as Error).message).toBe("socket hang up");
        });
    }

    it("caps success bodies", async () => {
        const huge = new Uint8Array(MAX_RESPONSE_BYTES + 1).fill(32);
        const { fetch } = fakeFetch([() => new Response(huge, { status: 200 })]);
        await expect(
            decisionsWith([openaiEndpoint]).create(minimalRequest, { fetch })
        ).rejects.toMatchObject({ kind: "invalidResponse" });
    });

    it("surfaces the gate hint only when the runtime mentions it", async () => {
        const gated = new SessionDecisions(async () => {
            throw new Error(
                "Request session.provider.getEndpoint failed with message: Provider endpoint access is disabled. To enable this API, set COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true"
            );
        });
        const gatedError = (await captureError(gated.create(minimalRequest))) as DecisionsError;
        expect(gatedError.kind).toBe("endpointUnavailable");
        expect(gatedError.message).toContain("COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true");

        const other = new SessionDecisions(async () => {
            throw new Error("Session not found");
        });
        const otherError = (await captureError(other.create(minimalRequest))) as DecisionsError;
        expect(otherError.kind).toBe("endpointUnavailable");
        expect(otherError.message).not.toContain("COPILOT_ALLOW_GET_PROVIDER_ENDPOINT");
        expect(otherError.cause).toBeInstanceOf(Error);
    });

    it("rejects unsupported providers before sending", async () => {
        const { fetch, calls } = fakeFetch([jsonResponse(200, OK_BODY)]);
        await expect(
            decisionsWith([
                { type: "anthropic", baseUrl: "https://api.anthropic.com", headers: {} },
            ]).create(minimalRequest, { fetch })
        ).rejects.toMatchObject({ kind: "unsupportedProvider" });
        expect(calls).toHaveLength(0);
    });

    describe("cancellation", () => {
        it("does not call getEndpoint when already aborted", async () => {
            const controller = new AbortController();
            controller.abort();
            const seen: unknown[] = [];
            const error = await captureError(
                decisionsWith([openaiEndpoint], seen).create(minimalRequest, {
                    signal: controller.signal,
                })
            );
            expect(error).not.toBeInstanceOf(DecisionsError);
            expect((error as Error).name).toBe("AbortError");
            expect(seen).toHaveLength(0);
        });

        it("checks the signal after getEndpoint", async () => {
            const controller = new AbortController();
            const decisions = new SessionDecisions(async () => {
                controller.abort();
                return openaiEndpoint;
            });
            const { fetch, calls } = fakeFetch([jsonResponse(200, OK_BODY)]);
            const error = await captureError(
                decisions.create(minimalRequest, { fetch, signal: controller.signal })
            );
            expect(error).not.toBeInstanceOf(DecisionsError);
            expect(calls).toHaveLength(0);
        });

        it("passes the signal to fetch and rethrows the native abort error", async () => {
            const controller = new AbortController();
            let seenSignal: AbortSignal | null | undefined;
            const hanging = ((_input: string | URL | Request, init?: RequestInit) =>
                new Promise<Response>((_resolve, reject) => {
                    seenSignal = init?.signal;
                    init?.signal?.addEventListener("abort", () => reject(init.signal!.reason));
                    controller.abort();
                })) as typeof fetch;
            const error = await captureError(
                decisionsWith([openaiEndpoint]).create(minimalRequest, {
                    fetch: hanging,
                    signal: controller.signal,
                })
            );
            expect(seenSignal).toBe(controller.signal);
            expect(error).not.toBeInstanceOf(DecisionsError);
            expect((error as Error).name).toBe("AbortError");
        });
    });
});

describe("session.decisions over real HTTP", () => {
    let server: Server | undefined;

    afterEach(async () => {
        const current = server;
        server = undefined;
        if (current) await new Promise<void>((resolve) => current.close(() => resolve()));
    });

    async function listen(
        handler: (
            path: string,
            headers: Record<string, unknown>,
            body: string
        ) => [number, Record<string, string>, string]
    ): Promise<{ baseUrl: string; hits: string[] }> {
        const hits: string[] = [];
        const created = createServer((req, res) => {
            let body = "";
            req.on("data", (chunk) => (body += chunk));
            req.on("end", () => {
                hits.push(req.url ?? "");
                const [status, headers, payload] = handler(req.url ?? "", req.headers, body);
                res.writeHead(status, headers);
                res.end(payload);
            });
        });
        server = created;
        await new Promise<void>((resolve) => created.listen(0, "127.0.0.1", resolve));
        const { port } = created.address() as AddressInfo;
        return { baseUrl: `http://127.0.0.1:${port}/v1`, hits };
    }

    it("round-trips through global fetch", async () => {
        let seenHeaders: Record<string, unknown> = {};
        let seenBody = "";
        const { baseUrl } = await listen((_path, headers, body) => {
            seenHeaders = headers;
            seenBody = body;
            return [200, { "content-type": "application/json" }, OK_BODY];
        });
        const result = await decisionsWith([
            { type: "openai", baseUrl, apiKey: "sk-test-0001", headers: { "X-Extra": "1" } },
        ]).create(minimalRequest);
        expect(result.answers[0]).toMatchObject({ type: "predicate" });
        expect(seenHeaders.authorization).toBe("Bearer sk-test-0001");
        expect(seenHeaders["x-extra"]).toBe("1");
        expect(seenHeaders["content-type"]).toBe("application/json");
        expect(JSON.parse(seenBody)).toEqual(fixture("request.minimal.json"));
    });

    it("does not follow redirects", async () => {
        const { baseUrl, hits } = await listen((path) =>
            path.endsWith("/decisions")
                ? [307, { location: "/elsewhere" }, ""]
                : [200, { "content-type": "application/json" }, OK_BODY]
        );
        const error = (await captureError(
            decisionsWith([
                { type: "openai", baseUrl, apiKey: "sk-test-0001", headers: {} },
            ]).create(minimalRequest)
        )) as DecisionsError;
        expect(error).toBeInstanceOf(DecisionsError);
        expect(error.kind).toBe("http");
        expect(error.status).toBe(307);
        expect(error.message).toContain("redirects are not followed");
        expect(hits).toEqual(["/v1/decisions"]);
    });
});

describe("public API", () => {
    it("is exported from the package root and exposed on CopilotSession", () => {
        expect(DecisionsError).toBeTypeOf("function");
        const descriptor = Object.getOwnPropertyDescriptor(CopilotSession.prototype, "decisions");
        expect(descriptor?.get).toBeTypeOf("function");
        const api: SessionDecisionsApi = new SessionDecisions(async () => openaiEndpoint);
        expect(api.create).toBeTypeOf("function");
    });

    it("type-checks the documented request shapes and exhaustive answer narrowing", () => {
        const items: DecisionsInputItem[] = [
            { role: "user", content: "untyped message" },
            { type: "message", role: "user", content: "typed message" },
            { raw: { type: "future_item" } },
        ];
        const options: DecisionsChoiceOption[] = [{ value: true }, { value: "a" }];
        const describeAnswer = (answer: DecisionsAnswer): string => {
            switch (answer.type) {
                case "predicate":
                    return `p${answer.probability}`;
                case "choice":
                    return `c${String(answer.choice)}`;
                case "score":
                    return `s${answer.score}`;
                case "refusal":
                    return "r";
                case "unknown":
                    return `u${answer.wireType}`;
                default: {
                    const exhaustive: never = answer;
                    return exhaustive;
                }
            }
        };
        expect(items).toHaveLength(3);
        expect(options).toHaveLength(2);
        expect(
            parseDecisionsResult(fixture("response.full.json")).answers.map(describeAnswer)
        ).toEqual(["p0.93", "cfrontend", "ctrue", "s1.37", "r", "uranking"]);
    });
});
