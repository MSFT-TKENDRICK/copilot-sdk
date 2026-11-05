import { getEventListeners } from "node:events";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createServer, type RequestListener, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { brotliCompressSync, deflateSync, gzipSync } from "node:zlib";
import { afterAll, afterEach, describe, expect, it, vi } from "vitest";
import {
    CopilotSession,
    DecisionsError,
    type DecisionsAnswer,
    type DecisionsChoiceOption,
    type DecisionsCallOptions,
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
    parseDecisionsResponse,
    parseRetryAfterMs,
    planDecisionsAttempt,
    redactSecrets,
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
const productionVectors = fixture("production-vectors.json");
const passedVectors: { file: string; group: string; name: string; status: "passed" }[] = [];
function passed(file: string, group: string, name: string): void {
    passedVectors.push({ file, group, name, status: "passed" });
}
afterAll(() => {
    const path = process.env.COPILOT_DECISIONS_VECTOR_CENSUS;
    if (path) {
        mkdirSync(dirname(path), { recursive: true });
        writeFileSync(path, JSON.stringify({ sdk: "node", schemaVersion: 1, cases: passedVectors }, null, 2));
    }
});
afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
});

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
                passed("vectors.json", "url", v.name);
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
                passed("vectors.json", "headers", v.name);
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
                passed("vectors.json", "preflight", v.name);
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
                passed("vectors.json", "retry", v.name);
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
                passed("vectors.json", "redactionLength", v.name);
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
                passed("vectors.json", "redaction", v.name);
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
            expect(seenSignal?.aborted).toBe(true);
            expect(seenSignal?.reason).toBe(controller.signal.reason);
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

function responseWithToken(target: string, jsonValue: string): string {
    const document = fixture("response.full.json");
    const path = target.split(".");
    let parent = document;
    for (const key of path.slice(0, -1)) parent = parent[key];
    const marker = "__synthetic_numeric_token__";
    parent[path[path.length - 1]!] = marker;
    const json = JSON.stringify(document);
    expect(json.split(JSON.stringify(marker))).toHaveLength(2);
    return json.replace(JSON.stringify(marker), () => jsonValue);
}

function resultAt(result: unknown, target: string): unknown {
    let value = result;
    for (const key of target.split(".")) {
        value = (value as Record<string, unknown>)[key.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase())];
    }
    return value;
}

function deferred<T>() {
    let resolve!: (value: T) => void;
    let reject!: (error: unknown) => void;
    const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}

function fakeClock(): void {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date", "performance"] });
}

function hangingStage(stage: string, status = 401) {
    const ready = deferred<void>();
    const endpoint = deferred<ProviderEndpoint>();
    const headers = deferred<Response>();
    const cancelled = vi.fn();
    let signal: AbortSignal | null | undefined;
    const resolve = vi.fn(async (): Promise<ProviderEndpoint> => {
        if (stage === "endpoint" || (stage === "refresh" && resolve.mock.calls.length === 2)) {
            ready.resolve();
            return endpoint.promise;
        }
        return { ...openaiEndpoint, apiKey: `synthetic-stage-key-${resolve.mock.calls.length}` };
    });
    const transport = vi.fn(async (_input: string | URL | Request, init?: RequestInit): Promise<Response> => {
        signal = init?.signal;
        if ((stage.startsWith("retry") && transport.mock.calls.length === 1) || stage === "refresh") {
            return new Response("{}", { status });
        }
        if (stage === "headers" || stage === "retryHeaders") {
            ready.resolve();
            return headers.promise;
        }
        if (["successBody", "errorBody", "retrySuccessBody", "retryErrorBody"].includes(stage)) {
            return new Response(new ReadableStream<Uint8Array>({
                pull() { ready.resolve(); },
                cancel: cancelled,
            }), { status: stage.toLowerCase().includes("error") ? status : 200 });
        }
        return new Response(OK_BODY);
    });
    return {
        api: new SessionDecisions(resolve),
        fetch: transport as typeof fetch,
        resolve,
        transport,
        ready,
        cancelled,
        signal: () => signal,
        async finishLate() {
            if (stage === "endpoint" || stage === "refresh") {
                endpoint.reject(new Error("synthetic late RPC rejection"));
            } else if (stage === "headers" || stage === "retryHeaders") {
                const close = vi.fn();
                headers.resolve(new Response(new ReadableStream<Uint8Array>({ cancel: close })));
                for (let i = 0; i < 8; i++) await Promise.resolve();
                expect(close).toHaveBeenCalledOnce();
            }
            for (let i = 0; i < 8; i++) await Promise.resolve();
        },
        releaseEndpoint() { endpoint.resolve(openaiEndpoint); },
    };
}

describe("decisions production vectors", () => {
    describe("metadata", () => {
        for (const v of productionVectors.metadata) {
            it(v.name, async () => {
                const { fetch, calls } = fakeFetch([jsonResponse(v.status, v.body, v.headers)]);
                const error = await captureError(
                    decisionsWith([{ ...openaiEndpoint, apiKey: v.apiKey }]).create(minimalRequest, { fetch })
                ) as DecisionsError;
                expect(error).toBeInstanceOf(DecisionsError);
                expect(error.kind).toBe("http");
                expect(error.status).toBe(v.status);
                expect(error.requestId ?? null).toBe(v.expectedRequestId);
                if (v.expectedErrorType) expect(error.errorType).toBe(v.expectedErrorType);
                if (v.expectedErrorCode) expect(error.errorCode).toBe(v.expectedErrorCode);
                expect(calls).toHaveLength(1);
                expect(JSON.stringify(error)).not.toContain(v.apiKey);
                passed("production-vectors.json", "metadata", v.name);
            });
        }
    });

    describe("retryAfter", () => {
        for (const v of productionVectors.retryAfter) {
            it(v.name, async () => {
                expect(parseRetryAfterMs(new Headers(v.headers), v.nowEpochMs) ?? null).toBe(v.expectedMs);
                vi.spyOn(Date, "now").mockReturnValue(v.nowEpochMs);
                const { fetch, calls } = fakeFetch([jsonResponse(429, "{}", v.headers)]);
                const error = await captureError(decisionsWith([openaiEndpoint]).create(minimalRequest, { fetch })) as DecisionsError;
                expect(error).toMatchObject({ kind: "http", status: 429 });
                expect(error.retryAfterMs ?? null).toBe(v.expectedMs);
                expect(calls).toHaveLength(1);
                passed("production-vectors.json", "retryAfter", v.name);
            });
        }
    });

    describe("responseNumbers", () => {
        for (const v of productionVectors.responseNumbers) {
            it(v.name, async () => {
                const body = responseWithToken(v.target, v.jsonValue);
                const { fetch, calls } = fakeFetch([jsonResponse(200, body)]);
                const pending = decisionsWith([openaiEndpoint]).create(minimalRequest, { fetch });
                const expectedError = v.sdkErrors?.node ?? v.expectedError;
                if (expectedError) {
                    await expect(pending).rejects.toMatchObject({ kind: expectedError });
                } else {
                    const value = resultAt(await pending, v.target);
                    expect(typeof value).toBe("number");
                    if (v.expectedInteger !== undefined) {
                        expect(Number.isSafeInteger(value)).toBe(true);
                        expect(String(value)).toBe(v.expectedInteger);
                    } else {
                        expect(value).toBe(v.expectedNumber);
                    }
                }
                expect(calls).toHaveLength(1);
                passed("production-vectors.json", "responseNumbers", v.name);
            });
        }
    });

    describe("authBodyFailure", () => {
        for (const v of productionVectors.authBodyFailure) {
            it(v.name, async () => {
                let reads = 0;
                const stream = new ReadableStream<Uint8Array>({
                    pull(controller) {
                        if (reads++ === 0) controller.enqueue(new TextEncoder().encode('{"error":'));
                        else controller.error(new Error("synthetic unreadable auth body"));
                    },
                });
                const reader = stream.getReader();
                void reader.closed.catch(() => undefined);
                const cancel = vi.spyOn(reader, "cancel");
                vi.spyOn(stream, "getReader").mockReturnValue(reader as never);
                // Response rejects an already-locked stream, so supply its body after construction.
                const response = new Response(null, { status: v.status });
                Object.defineProperty(response, "body", { value: stream });
                const transport = fakeFetch([() => response]);
                const seen: unknown[] = [];
                const error = await captureError(decisionsWith([
                    openaiEndpoint,
                    { ...openaiEndpoint, apiKey: "synthetic-refreshed-key" },
                ], seen).create(minimalRequest, { fetch: transport.fetch }));
                expect(error).toMatchObject({ kind: v.expectedError });
                expect(seen).toHaveLength(v.expectedResolves);
                expect(transport.calls).toHaveLength(v.expectedSends);
                expect(cancel.mock.calls.length > 0 && !stream.locked).toBe(v.expectedBodyClosed);
                passed("production-vectors.json", "authBodyFailure", v.name);
            });
        }
    });

    describe("deadline", () => {
        for (const v of productionVectors.deadline) {
            it(v.name, async () => {
                fakeClock();
                const stage = hangingStage(v.stage, v.status);
                const errorPromise = captureError(stage.api.create(minimalRequest, {
                    fetch: stage.fetch,
                    timeoutMs: v.timeoutMs,
                }));
                await stage.ready.promise;
                await vi.advanceTimersByTimeAsync(v.timeoutMs);
                const error = await errorPromise as DecisionsError;
                expect(error).toBeInstanceOf(DecisionsError);
                expect(error.kind).toBe(v.expectedError);
                expect(error.cause).toBeUndefined();
                expect(stage.resolve).toHaveBeenCalledTimes(v.expectedResolves);
                expect(stage.transport).toHaveBeenCalledTimes(v.expectedSends);
                if (v.stage.endsWith("Body")) expect(stage.cancelled).toHaveBeenCalledOnce();
                await stage.finishLate();
                expect(stage.transport).toHaveBeenCalledTimes(v.expectedSends);
                expect(vi.getTimerCount()).toBe(0);
                passed("production-vectors.json", "deadline", v.name);
            });
        }
    });
});

describe("decisions native production regressions", () => {
    it.each(["\ud800", "\udfff", "synthetic\ud800key", "\u0001", "\u001f", "\u007f"])(
        "rejects unsafe header scalars/controls before sending (%j)",
        async (value) => {
            const { fetch, calls } = fakeFetch([jsonResponse(200, OK_BODY)]);
            for (const endpoint of [
                { ...openaiEndpoint, headers: { "X-Synthetic": value } },
                { ...openaiEndpoint, apiKey: value, headers: { authorization: "synthetic-shadow" } },
                { ...openaiEndpoint, sessionToken: { header: "X-Synthetic", token: value } },
            ]) {
                const error = await captureError(decisionsWith([endpoint]).create(minimalRequest, { fetch }));
                expect(error).toMatchObject({ kind: "invalidEndpoint" });
                expect((error as Error).message).toBe("The provider endpoint returned an invalid HTTP header.");
            }
            expect(calls).toHaveLength(0);
        }
    );

    it("preserves scalar Unicode and only trims SP/HTAB for trusted transports", async () => {
        const value = "\u00a0synthetic-\ud83d\udd11\u00a0";
        const { fetch, calls } = fakeFetch([jsonResponse(200, OK_BODY)]);
        await decisionsWith([{ ...openaiEndpoint, headers: { "X-Synthetic": ` \t${value}\t ` } }])
            .create(minimalRequest, { fetch });
        expect((calls[0].init.headers as Record<string, string>)["x-synthetic"]).toBe(value);
    });

    it("sanitizes built-in Unicode header encoding errors", async () => {
        const error = await captureError(decisionsWith([{
            ...openaiEndpoint,
            baseUrl: "http://127.0.0.1:0/v1",
            apiKey: "synthetic-\ud83d\udd11",
        }]).create(minimalRequest)) as DecisionsError;
        expect(error.kind).toBe("network");
        expect(error.cause).toHaveProperty("code", "ERR_INVALID_CHAR");
        expect(String(error.cause)).not.toContain("synthetic-\ud83d\udd11");
    });

    it.each([
        "https://host:/v1", "https:///v1", "https://host:1.2/v1", "https://bad..host/v1",
        "https://-bad.host/v1", "https://[::1]extra/v1", "https://host/v1?x=%a",
        "https://host/v1#bad%", "https://host/\u0000", "https://host/\u007f",
    ])("rejects malformed URLs without reflecting them (%s)", (baseUrl) => {
        expect(() => buildDecisionsUrl({ type: "openai", baseUrl })).toThrow(
            expect.objectContaining({ kind: "invalidEndpoint" })
        );
    });

    it("preserves port zero, encoded path separators, repeated slashes, and query order", () => {
        expect(buildDecisionsUrl({
            type: "openai",
            baseUrl: "HTTP://LOCALHOST:0/a//b/./%2e%2e/c%2Fd?b=2&a=1&a=3#drop",
        })).toBe("http://localhost:0/a//c%2Fd/decisions?b=2&a=1&a=3");
    });

    it.each([null, undefined, 0, "synthetic", [], {}, { type: "unknown" }, { raw: null }])(
        "explicitly rejects runtime-invalid items (%j)",
        (item) => {
            expect(() => serializeDecisionsRequest({
                ...minimalRequest,
                input: [item] as DecisionsInputItem[],
            })).toThrow(TypeError);
        }
    );

    it("rejects sparse items instead of serializing a JSON null", () => {
        expect(() => serializeDecisionsRequest({
            ...minimalRequest, input: new Array<DecisionsInputItem>(1),
        })).toThrow(TypeError);
    });

    it("treats a null message discriminator as absent and retains the explicit raw escape hatch", () => {
        expect(serializeDecisionsRequest({
            ...minimalRequest,
            input: [{ type: null, role: "user", content: "x" }, { raw: { type: "future", extra: true } }],
        }).input).toEqual([{ role: "user", content: "x" }, { type: "future", extra: true }]);
    });

    it.each([
        { type: "message", role: "system", content: [{ type: "input_image", imageUrl: "https://example.com/a" }] },
        { type: "message", role: "developer", content: [{ type: "input_json", data: {} }] },
        { type: "message", role: "user", content: [{ type: "output_text", text: "x" }] },
        { type: "message", role: "assistant", content: [{ type: "input_text", text: "x" }] },
        { type: "message", role: "tool", content: "x" },
        { type: "custom_tool_call_output", callId: "c", output: [{ type: "input_json", data: {} }] },
    ])("rejects part/role combinations outside the typed input contract (%j)", (item) => {
        expect(() => serializeDecisionsRequest({
            ...minimalRequest, input: [item] as DecisionsInputItem[],
        })).toThrow(TypeError);
    });

    it("omits input-only breakpoints from text and image question instructions", () => {
        const part = { type: "input_text" as const, text: "x", promptCacheBreakpoint: { mode: "explicit" as const } };
        const image = { type: "input_image" as const, imageUrl: "https://example.com/a", promptCacheBreakpoint: part.promptCacheBreakpoint };
        const wire = serializeDecisionsRequest({
            ...minimalRequest, questions: [{ type: "predicate", instructions: [part, image] }],
        });
        expect(wire.questions).toEqual([{
            type: "predicate",
            instructions: [{ type: "input_text", text: "x" }, { type: "input_image", image_url: "https://example.com/a" }],
        }]);
    });

    it("rejects unknown/null questions and invalid instruction parts explicitly", () => {
        for (const question of [null, { type: "unknown" }, { type: "predicate", instructions: [null] },
            { type: "predicate", instructions: [{ type: "output_text", text: "x" }] }]) {
            expect(() => serializeDecisionsRequest({
                ...minimalRequest, questions: [question] as DecisionsRequest["questions"],
            })).toThrow(TypeError);
        }
    });

    it("unions overlaps and does not rescan inserted redaction markers", () => {
        expect(redactSecrets("abcdefghijklmn", ["abcdefghij", "efghijklmn"])).toBe("[REDACTED]");
        expect(redactSecrets("abababababab", ["abababab"])).toBe("[REDACTED]");
        expect(redactSecrets("synthetic REDACTED", ["synthetic", "REDACTED"])).toBe("[REDACTED] [REDACTED]");
        expect(redactSecrets("AbCd abcd", ["AbCd"])).toBe("[REDACTED] abcd");
        expect(redactSecrets("AbCd %2f abcd %2F", ["AbCd %2F"])).toBe("[REDACTED] abcd %2F");
    });

    it("never pulls lookahead into the retained original budget after redaction shrinks", async () => {
        const key = "synthetic-long-credential-0001";
        const prefix = key.repeat(2) + "x".repeat(MAX_ERROR_BODY_BYTES - key.length * 2);
        const { fetch } = fakeFetch([jsonResponse(500, prefix + "LOOKAHEAD-MUST-NOT-BE-RETAINED")]);
        const error = await captureError(decisionsWith([{ ...openaiEndpoint, apiKey: key }])
            .create(minimalRequest, { fetch })) as DecisionsError;
        expect(error.body).toBe("[REDACTED]".repeat(2) + "x".repeat(MAX_ERROR_BODY_BYTES - key.length * 2));
        expect(error.bodyTruncated).toBe(true);
        expect(error.body).not.toContain("LOOKAHEAD");
    });

    it("uses lookahead only to redact an original-boundary crossing secret", async () => {
        const key = "synthetic-long-credential-0001";
        const prefix = key.repeat(2) + "x".repeat(MAX_ERROR_BODY_BYTES - key.length * 2 - 5);
        const { fetch } = fakeFetch([jsonResponse(500, prefix + key + "do-not-retain")]);
        const error = await captureError(decisionsWith([{ ...openaiEndpoint, apiKey: key }])
            .create(minimalRequest, { fetch })) as DecisionsError;
        expect(error.body).toBe("[REDACTED]".repeat(2) + prefix.slice(key.length * 2) + "[REDACTED]");
        expect(error.bodyTruncated).toBe(true);
    });

    it("preserves a BOM during credential matching and trims incomplete UTF-8 without credentials", async () => {
        const key = "\ufeffsynthetic-bom-key";
        const echo = fakeFetch([jsonResponse(400, key + " tail")]);
        const redacted = await captureError(decisionsWith([{ ...openaiEndpoint, apiKey: key }])
            .create(minimalRequest, { fetch: echo.fetch })) as DecisionsError;
        expect(redacted.body).toBe("[REDACTED] tail");
        const bytes = fakeFetch([jsonResponse(500, "x".repeat(MAX_ERROR_BODY_BYTES - 1) + "é")]);
        const truncated = await captureError(decisionsWith([{
            type: "openai", baseUrl: "https://example.com", headers: {},
        }]).create(minimalRequest, { fetch: bytes.fetch })) as DecisionsError;
        expect(truncated.body).toBe("x".repeat(MAX_ERROR_BODY_BYTES - 1));
        expect(truncated.bodyTruncated).toBe(true);
    });

    it("protects all short actual credential sources but not short heuristic candidates", async () => {
        const endpoint: ProviderEndpoint = {
            ...openaiEndpoint,
            apiKey: "XYZ",
            headers: { authorization: "Bearer Q", "api-key": "JK", "X-Synthetic-Session": "AB", "X-Trace": "tiny" },
            sessionToken: { header: "x-synthetic-session", token: "CD" },
        };
        const { fetch } = fakeFetch([jsonResponse(500, "XYZ Q JK AB CD tiny")]);
        const error = await captureError(decisionsWith([endpoint]).create(minimalRequest, { fetch })) as DecisionsError;
        expect(error.body).toBe("[REDACTED] [REDACTED] [REDACTED] [REDACTED] [REDACTED] tiny");
    });

    it.each([
        { key: "synthetic-" + "q".repeat(70_000), echo: "synthetic-" + "q".repeat(70_000), truncated: true },
        { key: "synthetic-" + "é".repeat(10_000), echo: "synthetic-" + "\\u00E9".repeat(10_000), truncated: false },
    ])("protects very long literal/escaped credentials without provider-sized regexes ($truncated)", async ({ key, echo, truncated }) => {
        const { fetch } = fakeFetch([jsonResponse(500, "echo:" + echo)]);
        const error = await captureError(decisionsWith([{ ...openaiEndpoint, apiKey: key }])
            .create(minimalRequest, { fetch })) as DecisionsError;
        expect(error.kind).toBe("http");
        expect(error.body).toBe("echo:[REDACTED]");
        expect(error.bodyTruncated).toBe(truncated);
    });

    it("caps short-secret replacement expansion and other error fields independently", async () => {
        const short = "Z";
        const metadata = short.repeat(9000);
        const { fetch } = fakeFetch([jsonResponse(400, JSON.stringify({
            error: { message: metadata, type: metadata, code: metadata, param: metadata },
        }), { "x-request-id": metadata })]);
        const error = await captureError(decisionsWith([{ ...openaiEndpoint, apiKey: short }])
            .create(minimalRequest, { fetch })) as DecisionsError;
        for (const value of [error.body, error.message, error.errorType, error.errorCode, error.errorParam, error.requestId]) {
            expect(value).toBeDefined();
            expect(value).not.toContain(short);
            expect(Buffer.byteLength(value!)).toBeLessThanOrEqual(MAX_ERROR_BODY_BYTES);
        }
        expect(error.bodyTruncated).toBe(false);
    });

    it("bounds and redacts cause name/message/code while dropping arbitrary exception details", async () => {
        const key = "synthetic-cause-key";
        const source = Object.assign(new Error(`message ${key}`), {
            name: `name ${key}`,
            code: `code ${key}`,
            url: `https://example.com/?token=${key}`,
            headers: { authorization: key },
            cause: new Error(key),
            details: { token: key },
        });
        const { fetch } = fakeFetch([() => { throw source; }]);
        const error = await captureError(decisionsWith([{ ...openaiEndpoint, apiKey: key }])
            .create(minimalRequest, { fetch })) as DecisionsError;
        expect(error.kind).toBe("network");
        const cause = error.cause as Error & { code?: string; url?: string; headers?: unknown; details?: unknown };
        expect(cause).not.toBe(source);
        expect(cause.name).toBe("name [REDACTED]");
        expect(cause.message).toBe("message [REDACTED]");
        expect(cause.code).toBe("code [REDACTED]");
        expect([cause.url, cause.headers, cause.details, cause.cause]).toEqual([undefined, undefined, undefined, undefined]);
        expect(cause.stack).toBeUndefined();
    });

    it("bounds very long cause fields without preserving arbitrary throwables", async () => {
        const text = "x".repeat(MAX_ERROR_BODY_BYTES + 20);
        const source = Object.assign(new Error(text), { name: text, code: text });
        const { fetch } = fakeFetch([() => { throw source; }]);
        const error = await captureError(decisionsWith([openaiEndpoint]).create(minimalRequest, { fetch })) as DecisionsError;
        const cause = error.cause as Error & { code: string };
        for (const value of [cause.name, cause.message, cause.code]) {
            expect(Buffer.byteLength(value)).toBe(MAX_ERROR_BODY_BYTES);
        }
    });

    it.each([false, true])("never retains an RPC exception, including on refresh (%s)", async (refresh) => {
        const raw = Object.assign(new Error("synthetic-unknown-endpoint-credential"), { code: "synthetic-unknown-code" });
        let resolved = 0;
        const api = new SessionDecisions(async () => {
            if (refresh && resolved++ === 0) return openaiEndpoint;
            throw raw;
        });
        const { fetch } = fakeFetch([jsonResponse(401, "{}")]);
        const error = await captureError(api.create(minimalRequest, { fetch })) as DecisionsError;
        expect(error.kind).toBe("endpointUnavailable");
        expect(error.cause).not.toBe(raw);
        expect((error.cause as Error).message).toBe("Provider endpoint lookup failed.");
        expect([error.message, (error.cause as Error).message, (error.cause as Error).stack].join("\n")).not.toContain("synthetic-unknown");
    });

    it("uses fixed preflight diagnostics, never provider-controlled models/types/transports", () => {
        const secret = "synthetic-preflight-content";
        for (const [endpoint, model] of [
            [{ ...openaiEndpoint, type: secret }, "m"],
            [{ ...openaiEndpoint, transport: secret }, "m"],
            [{ ...openaiEndpoint, sessionToken: { header: "X-Session", token: "XYZ", model: secret } }, "m"],
            [{ ...openaiEndpoint, sessionToken: { header: "X-Session", token: "XYZ", model: "m" } }, secret],
        ] as [ProviderEndpoint, string][]) {
            try {
                planDecisionsAttempt(endpoint, model);
                expect.fail("Expected a preflight error");
            } catch (error) {
                expect(error).toBeInstanceOf(DecisionsError);
                expect(String(error)).not.toContain(secret);
            }
        }
    });

    it.each(["9007199254740992", "-9007199254740992", "9007199254740991.1", "-9007199254740991.1", "1e-400", "3.0000000000000001", "1e400"])(
        "rejects unsafe or inexact integer tokens without rounding (%s)",
        (token) => {
            expect(() => parseDecisionsResponse(responseWithToken("usage.input_tokens", token)))
                .toThrow(expect.objectContaining({ kind: "invalidResponse" }));
        }
    );

    it.each(["-9007199254740991", "9007199254740991.0", "90071992547409910e-1", "-1e0", "0e-999999"])(
        "accepts exact signed safe integer encodings (%s)",
        (token) => {
            expect(parseDecisionsResponse(responseWithToken("usage.input_tokens", token)).usage.inputTokens).toBe(Number(token));
        }
    );

    it("checks every usage counter and known floating field without constraining unknown answers", () => {
        for (const target of ["usage.input_tokens", "usage.output_tokens", "usage.total_tokens",
            "usage.input_tokens_details.cached_tokens", "usage.input_tokens_details.cache_write_tokens",
            "usage.output_tokens_details.reasoning_tokens", "answers.3.probabilities.0.value"]) {
            for (const token of ["null", "true", '"1"', "1.5", "9007199254740992"]) {
                expect(() => parseDecisionsResponse(responseWithToken(target, token)))
                    .toThrow(expect.objectContaining({ kind: "invalidResponse" }));
            }
        }
        for (const target of ["answers.0.probability", "answers.1.confidence", "answers.1.probabilities.0.probability",
            "answers.3.confidence", "answers.3.probabilities.0.probability"]) {
            for (const token of ["true", "null", '"0.5"', "1e400", "-0.1", "1.1"]) {
                expect(() => parseDecisionsResponse(responseWithToken(target, token)))
                    .toThrow(expect.objectContaining({ kind: "invalidResponse" }));
            }
        }
        expect(parseDecisionsResponse(OK_BODY).answers.at(-1)).toMatchObject({ type: "unknown", wireType: "ranking" });
        const minimal = parseDecisionsResponse('{"answers":[],"model":"m","usage":{}}');
        expect(minimal.usage).toEqual({
            inputTokens: 0, inputTokensDetails: { cachedTokens: 0, cacheWriteTokens: 0 },
            outputTokens: 0, outputTokensDetails: { reasoningTokens: 0 }, totalTokens: 0,
        });
    });

    it("strips a success BOM before parsing", async () => {
        const { fetch } = fakeFetch([jsonResponse(200, "\ufeff" + OK_BODY)]);
        await expect(decisionsWith([openaiEndpoint]).create(minimalRequest, { fetch }))
            .resolves.toMatchObject({ model: "gpt-6-luna-2026-10-01" });
    });

    it.each([".1", "1.", "1e2", "+1", "-0", "1,2", "Infinity", "9007199254740991.01", "9007199254740992"])(
        "rejects malformed/overflow retry delays while allowing a valid fallback (%s)",
        (value) => {
            expect(parseRetryAfterMs(new Headers({ "retry-after-ms": value, "retry-after": "0.001" }))).toBe(1);
            expect(parseRetryAfterMs(new Headers({ "retry-after-ms": value }))).toBeUndefined();
        }
    );

    it("rounds advisory decimal delays exactly at the safe-integer boundary", () => {
        expect(parseRetryAfterMs(new Headers({ "retry-after-ms": "9007199254740990.1" }))).toBe(9007199254740991);
        expect(parseRetryAfterMs(new Headers({ "retry-after": "9007199254740.991" }))).toBe(9007199254740991);
        expect(parseRetryAfterMs(new Headers({ "retry-after": "9007199254740.9911" }))).toBeUndefined();
        expect(parseRetryAfterMs(new Headers({ "retry-after-ms": "0.000000000000000001" }))).toBe(1);
    });
});

describe("decisions total deadline and resource ownership", () => {
    for (const stageName of ["endpoint", "headers", "successBody", "errorBody", "refresh",
        "retryHeaders", "retrySuccessBody", "retryErrorBody"]) {
        it(`preserves native caller cancellation at ${stageName} and cleans up`, async () => {
            fakeClock();
            const controller = new AbortController();
            const reason = new DOMException("synthetic caller deadline", "TimeoutError");
            const stage = hangingStage(stageName);
            const baselineListeners = getEventListeners(controller.signal, "abort").length;
            const errorPromise = captureError(stage.api.create(minimalRequest, {
                fetch: stage.fetch, signal: controller.signal, timeoutMs: 250,
            }));
            await stage.ready.promise;
            controller.abort(reason);
            expect(await errorPromise).toBe(reason);
            if (stageName.endsWith("Body")) expect(stage.cancelled).toHaveBeenCalledOnce();
            const sends = stage.transport.mock.calls.length;
            await stage.finishLate();
            expect(stage.transport).toHaveBeenCalledTimes(sends);
            expect(getEventListeners(controller.signal, "abort")).toHaveLength(baselineListeners);
            if (stage.signal()) expect(getEventListeners(stage.signal()!, "abort")).toHaveLength(0);
            expect(vi.getTimerCount()).toBe(0);
        });
    }

    for (const stageName of ["retryHeaders", "retrySuccessBody", "retryErrorBody"]) {
        it(`applies the SDK deadline during ${stageName}`, async () => {
            fakeClock();
            const stage = hangingStage(stageName);
            const pending = captureError(stage.api.create(minimalRequest, { fetch: stage.fetch, timeoutMs: 250 }));
            await stage.ready.promise;
            await vi.advanceTimersByTimeAsync(250);
            const error = await pending as DecisionsError;
            expect(error).toMatchObject({ kind: "timeout" });
            expect(error.cause).toBeUndefined();
            expect(Object.hasOwn(error, "cause")).toBe(false);
            expect(stage.resolve).toHaveBeenCalledTimes(2);
            expect(stage.transport).toHaveBeenCalledTimes(2);
            if (stageName.endsWith("Body")) expect(stage.cancelled).toHaveBeenCalledOnce();
            await stage.finishLate();
            expect(vi.getTimerCount()).toBe(0);
        });
    }

    it("does not reset the total budget after credentials are refreshed", async () => {
        fakeClock();
        let resolves = 0;
        const stage = deferred<void>();
        const closed = vi.fn();
        const api = new SessionDecisions(async () => {
            if (++resolves === 2) await new Promise<void>((resolve) => setTimeout(resolve, 100));
            return { ...openaiEndpoint, apiKey: `synthetic-total-key-${resolves}` };
        });
        let sends = 0;
        const transport = (async () => {
            if (++sends === 1) {
                await new Promise<void>((resolve) => setTimeout(resolve, 100));
                return new Response("{}", { status: 401 });
            }
            return new Response(new ReadableStream<Uint8Array>({
                pull() { stage.resolve(); },
                cancel: closed,
            }));
        }) as typeof fetch;
        let settled = false;
        const pending = captureError(api.create(minimalRequest, { fetch: transport, timeoutMs: 250 }))
            .then((error) => { settled = true; return error; });
        await vi.advanceTimersByTimeAsync(200);
        await stage.promise;
        await vi.advanceTimersByTimeAsync(49);
        expect(settled).toBe(false);
        await vi.advanceTimersByTimeAsync(1);
        expect(await pending).toMatchObject({ kind: "timeout" });
        expect([resolves, sends]).toEqual([2, 2]);
        expect(closed).toHaveBeenCalledOnce();
        expect(vi.getTimerCount()).toBe(0);
    });

    it("defaults to exactly 600 seconds including an uninterruptible endpoint lookup", async () => {
        fakeClock();
        const stage = hangingStage("endpoint");
        let settled = false;
        const pending = captureError(stage.api.create(minimalRequest, { fetch: stage.fetch }))
            .then((error) => { settled = true; return error; });
        await stage.ready.promise;
        await vi.advanceTimersByTimeAsync(599_999);
        expect(settled).toBe(false);
        await vi.advanceTimersByTimeAsync(1);
        expect(await pending).toMatchObject({ kind: "timeout" });
        await stage.finishLate();
        expect(stage.transport).not.toHaveBeenCalled();
        expect(vi.getTimerCount()).toBe(0);
    });

    it("allows null to disable the SDK deadline", async () => {
        fakeClock();
        const stage = hangingStage("endpoint");
        let settled = false;
        const pending = stage.api.create(minimalRequest, { fetch: stage.fetch, timeoutMs: null })
            .then((value) => { settled = true; return value; });
        await stage.ready.promise;
        await vi.advanceTimersByTimeAsync(700_000);
        expect(settled).toBe(false);
        expect(vi.getTimerCount()).toBe(0);
        stage.releaseEndpoint();
        await expect(pending).resolves.toMatchObject({ model: "gpt-6-luna-2026-10-01" });
    });

    it("does not overflow Node's native timer range for a large valid timeout", async () => {
        fakeClock();
        const controller = new AbortController();
        const stage = hangingStage("endpoint");
        let settled = false;
        const pending = captureError(stage.api.create(minimalRequest, {
            fetch: stage.fetch, timeoutMs: Number.MAX_SAFE_INTEGER, signal: controller.signal,
        })).then((error) => { settled = true; return error; });
        await stage.ready.promise;
        await vi.advanceTimersByTimeAsync(10_000);
        expect(settled).toBe(false);
        controller.abort();
        expect(await pending).toBe(controller.signal.reason);
        await stage.finishLate();
        expect(vi.getTimerCount()).toBe(0);
    });

    it.each([0, -1, NaN, Infinity, -Infinity, "1", false])(
        "rejects invalid timeout options before endpoint/HTTP I/O (%j)",
        async (timeoutMs) => {
            const resolver = vi.fn(async () => openaiEndpoint);
            const { fetch, calls } = fakeFetch([jsonResponse(200, OK_BODY)]);
            await expect(new SessionDecisions(resolver).create(minimalRequest, {
                fetch, timeoutMs,
            } as DecisionsCallOptions)).rejects.toThrow(TypeError);
            expect(resolver).not.toHaveBeenCalled();
            expect(calls).toHaveLength(0);
        }
    );

    it("keeps missing model validation first, and pre-cancelled callers do no I/O", async () => {
        const controller = new AbortController();
        controller.abort(new Error("synthetic native cancellation"));
        const resolver = vi.fn(async () => openaiEndpoint);
        const api = new SessionDecisions(resolver);
        await expect(api.create({ ...minimalRequest, model: "" }, {
            timeoutMs: 0, signal: controller.signal,
        })).rejects.toThrow("DecisionsRequest.model is required.");
        expect(await captureError(api.create(minimalRequest, { signal: controller.signal })))
            .toBe(controller.signal.reason);
        expect(resolver).not.toHaveBeenCalled();
    });

    it("gives caller cancellation precedence when both abort reasons are observed", async () => {
        fakeClock();
        const caller = new AbortController();
        const native = new DOMException("synthetic caller cancelled", "AbortError");
        const ready = deferred<void>();
        const transport = (async (_input: string | URL | Request, init?: RequestInit) => {
            init!.signal!.addEventListener("abort", () => caller.abort(native), { once: true });
            ready.resolve();
            return new Promise<Response>(() => undefined);
        }) as typeof fetch;
        const pending = captureError(decisionsWith([openaiEndpoint]).create(minimalRequest, {
            fetch: transport, timeoutMs: 250, signal: caller.signal,
        }));
        await ready.promise;
        await vi.advanceTimersByTimeAsync(250);
        expect(await pending).toBe(native);
        expect(vi.getTimerCount()).toBe(0);
    });

    it("treats an injected transport's independent timeout as sanitized network failure", async () => {
        const key = "synthetic-timeout-key";
        const { fetch } = fakeFetch([() => { throw new DOMException(`independent ${key}`, "TimeoutError"); }]);
        const error = await captureError(decisionsWith([{ ...openaiEndpoint, apiKey: key }])
            .create(minimalRequest, { fetch })) as DecisionsError;
        expect(error.kind).toBe("network");
        expect((error.cause as Error).name).toBe("TimeoutError");
        expect((error.cause as Error).message).toBe("independent [REDACTED]");
    });

    it("checks expiry after synchronous serialization before dispatching HTTP", async () => {
        fakeClock();
        const { fetch, calls } = fakeFetch([jsonResponse(200, OK_BODY)]);
        const input = { raw: {
            toJSON() {
                vi.advanceTimersByTime(251);
                return { type: "synthetic-future" };
            },
        } };
        await expect(decisionsWith([openaiEndpoint]).create({
            ...minimalRequest, input: [input],
        }, { fetch, timeoutMs: 250 })).rejects.toMatchObject({ kind: "timeout" });
        expect(calls).toHaveLength(0);
        expect(vi.getTimerCount()).toBe(0);
    });

    it.each(["success", "invalidJson", "invalidShape", "oversize", "readError", "http"])(
        "releases readers, cancels bodies, and removes timers/listeners on %s",
        async (outcome) => {
            fakeClock();
            const controller = new AbortController();
            const stream = new ReadableStream<Uint8Array>({
                start(body) {
                    if (outcome === "readError") {
                        body.error(new Error("synthetic body failure"));
                    } else {
                        const payload = outcome === "oversize" ? " ".repeat(MAX_RESPONSE_BYTES + 1)
                            : outcome === "invalidJson" ? "<html>" : outcome === "invalidShape" ? "{}"
                            : outcome === "http" ? "{}" : OK_BODY;
                        body.enqueue(new TextEncoder().encode(payload));
                        body.close();
                    }
                },
            });
            const reader = stream.getReader();
            void reader.closed.catch(() => undefined);
            const cancel = vi.spyOn(reader, "cancel");
            const release = vi.spyOn(reader, "releaseLock");
            vi.spyOn(stream, "getReader").mockReturnValue(reader as never);
            const response = new Response(null, { status: outcome === "http" ? 500 : 200 });
            Object.defineProperty(response, "body", { value: stream });
            const { fetch } = fakeFetch([() => response]);
            const pending = decisionsWith([openaiEndpoint]).create(minimalRequest, { fetch, signal: controller.signal });
            if (outcome === "success") {
                await expect(pending).resolves.toHaveProperty("answers");
            } else {
                const error = await captureError(pending);
                expect(error).toHaveProperty("kind", outcome === "readError" ? "network"
                    : outcome === "http" ? "http" : "invalidResponse");
            }
            expect(cancel).toHaveBeenCalledOnce();
            expect(release).toHaveBeenCalledOnce();
            expect(stream.locked).toBe(false);
            expect(getEventListeners(controller.signal, "abort")).toHaveLength(0);
            expect(vi.getTimerCount()).toBe(0);
        }
    );

    it("does not read beyond the error retention budget plus lookahead and overflow detection", async () => {
        let reads = 0;
        const cancel = vi.fn();
        const stream = new ReadableStream<Uint8Array>({
            pull(controller) {
                reads++;
                controller.enqueue(new Uint8Array(MAX_ERROR_BODY_BYTES).fill(120));
            },
            cancel,
        }, { highWaterMark: 0 });
        const { fetch } = fakeFetch([() => new Response(stream, { status: 500 })]);
        const error = await captureError(decisionsWith([{
            type: "openai", baseUrl: "https://example.com", headers: {},
        }]).create(minimalRequest, { fetch })) as DecisionsError;
        expect(error.kind).toBe("http");
        expect(error.bodyTruncated).toBe(true);
        expect(reads).toBe(2);
        expect(cancel).toHaveBeenCalledOnce();
    });
});

describe("decisions production local HTTP", () => {
    let server: Server | undefined;
    afterEach(async () => {
        if (server) {
            server.closeAllConnections();
            await new Promise<void>((resolve) => server!.close(() => resolve()));
            server = undefined;
        }
    });

    async function serve(handler: RequestListener): Promise<string> {
        const current = createServer(handler);
        server = current;
        await new Promise<void>((resolve) => current.listen(0, "127.0.0.1", resolve));
        return `http://127.0.0.1:${(current.address() as AddressInfo).port}/v1`;
    }

    for (const outcome of ["headers", "successBody", "authBody", "errorBody"]) {
        for (const cancelByCaller of [false, true]) {
            it(`aborts a real slow ${outcome} via ${cancelByCaller ? "caller" : "SDK"} and closes its socket`, async () => {
                const ready = deferred<void>();
                const closed = deferred<void>();
                let requests = 0;
                const baseUrl = await serve((req, res) => {
                    requests++;
                    req.resume();
                    res.on("close", () => closed.resolve());
                    if (outcome !== "headers") {
                        res.writeHead(outcome === "successBody" ? 200 : outcome === "authBody" ? 401 : 500);
                        res.write(" ");
                    }
                    ready.resolve();
                });
                const caller = new AbortController();
                const pending = captureError(decisionsWith([{
                    type: "openai", baseUrl, headers: {},
                }]).create(minimalRequest, {
                    timeoutMs: cancelByCaller ? 10_000 : 300,
                    signal: caller.signal,
                }));
                await ready.promise;
                if (cancelByCaller) caller.abort();
                const error = await pending;
                if (cancelByCaller) expect(error).toBe(caller.signal.reason);
                else expect(error).toMatchObject({ kind: "timeout" });
                await closed.promise;
                expect(requests).toBe(1);
                expect(getEventListeners(caller.signal, "abort")).toHaveLength(0);
            });
        }
    }

    it.each([401, 403])("processes a complete HTTP %s before one changed-credential replay", async (status) => {
        const seen: string[] = [];
        const baseUrl = await serve((req, res) => {
            seen.push(req.headers.authorization ?? "");
            req.resume();
            if (seen.length === 1) {
                res.writeHead(status, { "content-type": "application/json" });
                res.end('{"error":{"code":"expired"}}');
            } else {
                res.writeHead(200, { "content-type": "application/json" });
                res.end(OK_BODY);
            }
        });
        const endpoints: ProviderEndpoint[] = [
            { type: "openai", baseUrl, headers: {}, apiKey: "synthetic-auth-key-a" },
            { type: "openai", baseUrl, headers: {}, apiKey: "synthetic-auth-key-b" },
        ];
        const resolutions: unknown[] = [];
        await expect(decisionsWith(endpoints, resolutions).create(minimalRequest))
            .resolves.toMatchObject({ model: "gpt-6-luna-2026-10-01" });
        expect(seen).toEqual(["Bearer synthetic-auth-key-a", "Bearer synthetic-auth-key-b"]);
        expect(resolutions).toHaveLength(2);
    });

    it.each([
        { encoding: "gzip", compress: gzipSync },
        { encoding: "deflate", compress: deflateSync },
        { encoding: "br", compress: brotliCompressSync },
        { encoding: "gzip, br", compress: (body: string) => brotliCompressSync(gzipSync(body)) },
    ])("preserves built-in response decompression for $encoding", async ({ encoding, compress }) => {
        const baseUrl = await serve((req, res) => {
            req.resume();
            res.writeHead(200, { "content-type": "application/json", "content-encoding": encoding });
            res.end(compress(OK_BODY));
        });
        await expect(decisionsWith([{ type: "openai", baseUrl, headers: {} }])
            .create(minimalRequest)).resolves.toMatchObject({ model: "gpt-6-luna-2026-10-01" });
    });

    it("bounds decompressed success bodies and reports malformed compressed bodies as network failures", async () => {
        let requests = 0;
        const baseUrl = await serve((req, res) => {
            req.resume();
            res.writeHead(200, { "content-encoding": "gzip" });
            res.end(requests++ === 0 ? gzipSync(" ".repeat(MAX_RESPONSE_BYTES + 1)) : "not gzip");
        });
        const api = decisionsWith([{ type: "openai", baseUrl, headers: {} }]);
        await expect(api.create(minimalRequest)).rejects.toMatchObject({ kind: "invalidResponse" });
        await expect(api.create(minimalRequest)).rejects.toMatchObject({ kind: "network" });
    });
});

describe("decisions diagnostic cross-products", () => {
    const oldKey = "synthetic-old/credential-0001";
    const newKey = "synthetic-new/credential-0002";
    const endpoints: ProviderEndpoint[] = [
        { ...openaiEndpoint, apiKey: oldKey },
        { ...openaiEndpoint, apiKey: newKey },
    ];

    it.each([400, 403])("protects attempt-one credentials in replayed HTTP %s diagnostics", async (status) => {
        const { fetch, calls } = fakeFetch([
            jsonResponse(401, '{"error":{"code":"expired"}}'),
            jsonResponse(status, JSON.stringify({
                error: {
                    message: `old=${oldKey} new=${newKey}`,
                    type: oldKey,
                    code: encodeURIComponent(oldKey).replace("%2F", "%2f"),
                    param: newKey,
                },
            }), { "x-request-id": `old=${oldKey};new=${newKey}` }),
        ]);
        const resolves: unknown[] = [];
        const error = await captureError(decisionsWith(endpoints, resolves)
            .create(minimalRequest, { fetch })) as DecisionsError;
        expect(error).toMatchObject({
            kind: "http",
            status,
            errorType: "[REDACTED]",
            errorCode: "[REDACTED]",
            errorParam: "[REDACTED]",
            requestId: "old=[REDACTED];new=[REDACTED]",
        });
        expect(error.message).toContain("old=[REDACTED] new=[REDACTED]");
        expect(JSON.stringify(error)).not.toContain(oldKey);
        expect(JSON.stringify(error)).not.toContain(newKey);
        expect(calls).toHaveLength(2);
        expect(resolves).toHaveLength(2);
        expect(calls[0].init.headers).toEqual(planDecisionsAttempt(endpoints[0], minimalRequest.model).headers);
        expect(calls[1].init.headers).toEqual(planDecisionsAttempt(endpoints[1], minimalRequest.model).headers);
    });

    it.each(["send", "successBody", "errorBody"])(
        "protects attempt-one credentials in replayed network diagnostics at %s",
        async (stage) => {
            const encoded = encodeURIComponent(oldKey).replace("%2F", "%2f");
            const source = Object.assign(new Error(`old=${oldKey} encoded=${encoded} new=${newKey}`), {
                name: `failure:${oldKey}`,
                code: encoded,
            });
            const { fetch, calls } = fakeFetch([
                jsonResponse(401, '{"error":{"code":"expired"}}'),
                () => {
                    if (stage === "send") throw source;
                    return new Response(new ReadableStream<Uint8Array>({
                        start(controller) { controller.error(source); },
                    }), { status: stage === "successBody" ? 200 : 401 });
                },
            ]);
            const resolves: unknown[] = [];
            const error = await captureError(decisionsWith(endpoints, resolves)
                .create(minimalRequest, { fetch })) as DecisionsError;
            expect(error.kind).toBe("network");
            const cause = error.cause as Error & { code?: string };
            expect(cause).not.toBe(source);
            expect(cause.name).toBe("failure:[REDACTED]");
            expect(cause.message).toBe("old=[REDACTED] encoded=[REDACTED] new=[REDACTED]");
            expect(cause.code).toBe("[REDACTED]");
            expect(calls).toHaveLength(2);
            expect(resolves).toHaveLength(2);
            expect(calls[1].init.headers).toEqual(planDecisionsAttempt(endpoints[1], minimalRequest.model).headers);
        }
    );

    it.each([
        "%FFcredential-0001",
        "%C3%28credential-0001",
        "synthetic+credential%FF",
        "synthetic%20credential%FF",
    ])("protects raw and decoded query representations for %s", async (raw) => {
        const echo = raw.replace(/%[0-9a-fA-F]{2}/g, (escape) => escape.toLowerCase());
        const decoded = new URLSearchParams(`sig=${raw}`).get("sig")!;
        const { fetch, calls } = fakeFetch([jsonResponse(400, JSON.stringify({
            error: {
                message: `raw=${echo};decoded=${decoded}`,
                type: echo,
                code: decoded,
                param: echo,
            },
        }), { "x-request-id": `raw=${echo}` })]);
        const error = await captureError(decisionsWith([{
            type: "openai", baseUrl: `https://example.com/v1?sig=${raw}`, headers: {},
        }]).create(minimalRequest, { fetch })) as DecisionsError;
        expect(error).toMatchObject({
            kind: "http",
            status: 400,
            errorType: "[REDACTED]",
            errorCode: "[REDACTED]",
            errorParam: "[REDACTED]",
            requestId: "raw=[REDACTED]",
        });
        expect(error.message).toContain("raw=[REDACTED];decoded=[REDACTED]");
        expect(error.body).toContain("raw=[REDACTED];decoded=[REDACTED]");
        expect(calls).toHaveLength(1);
        expect(calls[0].url).toBe(`https://example.com/v1/decisions?sig=${raw}`);
    });

    it("protects an old raw query representation in replayed network diagnostics", async () => {
        const raw = "%FFcredential-0001";
        const echo = "%ffcredential-0001";
        const decoded = new URLSearchParams(`sig=${raw}`).get("sig")!;
        const { fetch, calls } = fakeFetch([
            jsonResponse(401, "{}"),
            () => { throw new Error(`raw=${echo};decoded=${decoded}`); },
        ]);
        const resolves: unknown[] = [];
        const error = await captureError(decisionsWith([
            { type: "openai", baseUrl: `https://example.com/v1?sig=${raw}`, headers: {} },
            { type: "openai", baseUrl: "https://example.com/v1?sig=synthetic-new-query", headers: {} },
        ], resolves).create(minimalRequest, { fetch })) as DecisionsError;
        expect(error.kind).toBe("network");
        expect((error.cause as Error).message).toBe("raw=[REDACTED];decoded=[REDACTED]");
        expect(calls).toHaveLength(2);
        expect(resolves).toHaveLength(2);
        expect(calls[1].url).toBe("https://example.com/v1/decisions?sig=synthetic-new-query");
    });

    it.each(["%41%42%43", "%F0%9F%94%91abc"])(
        "does not promote a short decoded query value based on its encoded length (%s)",
        async (raw) => {
            const decoded = new URLSearchParams(`sig=${raw}`).get("sig")!;
            const payload = `raw=${raw};decoded=${decoded}`;
            const { fetch } = fakeFetch([jsonResponse(500, payload)]);
            const error = await captureError(decisionsWith([{
                type: "openai", baseUrl: `https://example.com/v1?sig=${raw}`, headers: {},
            }]).create(minimalRequest, { fetch })) as DecisionsError;
            expect(error.kind).toBe("http");
            expect(error.body).toBe(payload);
        }
    );

    it("redacts a raw invalid-UTF8 query echo across the original body budget", async () => {
        const prefix = "x".repeat(MAX_ERROR_BODY_BYTES - 12);
        const { fetch } = fakeFetch([jsonResponse(500, prefix + "%ffcredential-0001 trailing")]);
        const error = await captureError(decisionsWith([{
            type: "openai", baseUrl: "https://example.com/v1?sig=%FFcredential-0001", headers: {},
        }]).create(minimalRequest, { fetch })) as DecisionsError;
        expect(error.kind).toBe("http");
        expect(error.body).toBe(prefix + "[REDACTED]");
        expect(error.bodyTruncated).toBe(true);
        expect(Buffer.byteLength(error.body!)).toBeLessThanOrEqual(MAX_ERROR_BODY_BYTES);
    });
});
