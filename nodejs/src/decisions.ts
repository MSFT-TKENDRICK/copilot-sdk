/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

/**
 * Experimental support for OpenAI's Decisions API (`POST /v1/decisions`), called directly
 * against the provider endpoint and credentials the session is configured with.
 *
 * See `docs/features/decisions.md`.
 *
 * @module decisions
 */

import type { ProviderEndpoint, SessionProviderGetEndpointRequest } from "./generated/rpc.js";

// ============================================================================
// Request types
// ============================================================================

/**
 * Marks the end of an explicit prompt-cache segment.
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export interface DecisionsCacheBreakpoint {
    mode: "explicit";
}

/**
 * Text content part.
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export interface DecisionsInputText {
    type: "input_text";
    text: string;
    promptCacheBreakpoint?: DecisionsCacheBreakpoint | null;
}

/**
 * Image content part. `imageUrl` is a data URL or an `http(s)` URL.
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export interface DecisionsInputImage {
    type: "input_image";
    imageUrl: string;
    detail?: "low" | "high" | "auto" | "original" | null;
    promptCacheBreakpoint?: DecisionsCacheBreakpoint | null;
}

/**
 * Structured JSON content part.
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export interface DecisionsInputJson {
    type: "input_json";
    data: Record<string, unknown> | Record<string, unknown>[];
}

/**
 * Assistant text content part.
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export interface DecisionsOutputText {
    type: "output_text";
    text: string;
}

/**
 * Assistant refusal content part.
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export interface DecisionsRefusalContent {
    type: "refusal";
    refusal: string;
}

/**
 * Status of a conversation item.
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export type DecisionsItemStatus = "in_progress" | "completed" | "incomplete";

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsUserMessage {
    type?: "message";
    role: "user";
    content: string | (DecisionsInputText | DecisionsInputImage | DecisionsInputJson)[];
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsDeveloperMessage {
    type?: "message";
    role: "developer" | "system";
    content: string | DecisionsInputText[];
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsAssistantMessage {
    type?: "message";
    role: "assistant";
    content: string | (DecisionsOutputText | DecisionsRefusalContent)[];
    phase?: "commentary" | "final_answer" | null;
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsFunctionCall {
    type: "function_call";
    callId: string;
    name: string;
    arguments: string;
    id?: string | null;
    namespace?: string | null;
    status?: DecisionsItemStatus | null;
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsFunctionCallOutput {
    type: "function_call_output";
    callId: string;
    output: string | (DecisionsInputText | DecisionsInputImage)[];
    id?: string | null;
    status?: DecisionsItemStatus | null;
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsCustomToolCall {
    type: "custom_tool_call";
    callId: string;
    name: string;
    input: string;
    id?: string | null;
    namespace?: string | null;
    status?: DecisionsItemStatus | null;
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsCustomToolCallOutput {
    type: "custom_tool_call_output";
    callId: string;
    output: string | (DecisionsInputText | DecisionsInputImage)[];
    id?: string | null;
    status?: DecisionsItemStatus | null;
}

/**
 * An input item sent verbatim (already in wire format). Use for item types this SDK
 * version does not model yet.
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export interface DecisionsRawInputItem {
    raw: Record<string, unknown>;
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export type DecisionsInputItem =
    | DecisionsUserMessage
    | DecisionsDeveloperMessage
    | DecisionsAssistantMessage
    | DecisionsFunctionCall
    | DecisionsFunctionCallOutput
    | DecisionsCustomToolCall
    | DecisionsCustomToolCallOutput
    | DecisionsRawInputItem;

/**
 * Content part allowed in question instructions (no cache breakpoints).
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export type DecisionsQuestionPart =
    | { type: "input_text"; text: string }
    | {
          type: "input_image";
          imageUrl: string;
          detail?: "low" | "high" | "auto" | "original" | null;
      }
    | DecisionsInputJson;

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export type DecisionsInstructions = string | DecisionsQuestionPart[];

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsPredicateQuestion {
    type: "predicate";
    instructions: DecisionsInstructions;
    name?: string | null;
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsChoiceOption {
    value: string | boolean;
    description?: string | null;
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsChoiceQuestion {
    type: "choice";
    instructions: DecisionsInstructions;
    name?: string | null;
    choices: DecisionsChoiceOption[];
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsScoreLevel {
    label: string;
    description?: string | null;
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsScoreQuestion {
    type: "score";
    instructions: DecisionsInstructions;
    name?: string | null;
    levels: DecisionsScoreLevel[];
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export type DecisionsQuestion =
    | DecisionsPredicateQuestion
    | DecisionsChoiceQuestion
    | DecisionsScoreQuestion;

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsPromptCacheOptions {
    mode?: "implicit" | "explicit" | null;
    ttl?: "30m" | null;
}

/**
 * A Decisions API request. Optional fields set to `null` are omitted from the wire request.
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export interface DecisionsRequest {
    /** Model to send on the wire (for Azure, the deployment name). Required. */
    model: string;
    input: string | DecisionsInputItem[];
    /** 1 to 400 questions (not validated client-side). */
    questions: DecisionsQuestion[];
    promptCacheKey?: string | null;
    promptCacheOptions?: DecisionsPromptCacheOptions | null;
    /**
     * Opaque, stable identifier for the end user, used for abuse detection. Never send an
     * email address, username, or credential; hash or map it first.
     */
    safetyIdentifier?: string | null;
}

/**
 * Per-call options for {@link SessionDecisionsApi.create}.
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export interface DecisionsCallOptions {
    /**
     * Cancels the call. The runtime `getEndpoint` RPC itself cannot be aborted; the signal is
     * checked before and after it and is passed to `fetch`.
     */
    signal?: AbortSignal;
    /**
     * Passed verbatim as `modelId` to `session.provider.getEndpoint`. Omit to use the session's
     * current endpoint. This is never derived from `request.model`.
     */
    endpointModelId?: string;
    /**
     * Custom fetch implementation (proxying, testing). Injected implementations are trusted and
     * must provide their own redirect protection; the SDK passes `redirect: "manual"`.
     */
    fetch?: typeof fetch;
}

// ============================================================================
// Response types
// ============================================================================

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsPredicateAnswer {
    type: "predicate";
    name: string | null;
    probability: number;
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsChoiceProbability {
    value: string | boolean;
    probability: number;
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsChoiceAnswer {
    type: "choice";
    name: string | null;
    choice: string | boolean;
    confidence: number;
    probabilities: DecisionsChoiceProbability[];
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsScoreProbability {
    label: string;
    /** Integer index of the level. */
    value: number;
    probability: number;
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsScoreAnswer {
    type: "score";
    name: string | null;
    /** Probability-weighted level index (fractional). */
    score: number;
    confidence: number;
    probabilities: DecisionsScoreProbability[];
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsRefusalAnswer {
    type: "refusal";
    name: string | null;
}

/**
 * An answer whose `type` this SDK version does not recognize. `wireType` is the original `type`
 * and `raw` is the original JSON object.
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export interface DecisionsUnknownAnswer {
    type: "unknown";
    name: string | null;
    wireType: string;
    raw: Record<string, unknown>;
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export type DecisionsAnswer =
    | DecisionsPredicateAnswer
    | DecisionsChoiceAnswer
    | DecisionsScoreAnswer
    | DecisionsRefusalAnswer
    | DecisionsUnknownAnswer;

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsUsage {
    inputTokens: number;
    inputTokensDetails: { cachedTokens: number; cacheWriteTokens: number };
    outputTokens: number;
    outputTokensDetails: { reasoningTokens: number };
    totalTokens: number;
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsResult {
    /** One answer per question, in question order. */
    answers: DecisionsAnswer[];
    model: string;
    usage: DecisionsUsage;
}

// ============================================================================
// Errors
// ============================================================================

/**
 * Category of a {@link DecisionsError}.
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export type DecisionsErrorKind =
    | "endpointUnavailable"
    | "invalidEndpoint"
    | "unsupportedProvider"
    | "unsupportedTransport"
    | "modelMismatch"
    | "credentialConflict"
    | "http"
    | "invalidResponse"
    | "network";

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsErrorDetails {
    status?: number;
    errorType?: string;
    errorCode?: string;
    errorParam?: string;
    requestId?: string;
    body?: string;
    bodyTruncated?: boolean;
    cause?: unknown;
}

/**
 * Error raised by `session.decisions.create`. Messages and `body` never contain credentials.
 * Cancellation is reported with the platform's native abort error, not this type.
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export class DecisionsError extends Error {
    readonly kind: DecisionsErrorKind;
    /** HTTP status for `http` errors. */
    readonly status?: number;
    /** OpenAI error `type`, when the error body was parsable. */
    readonly errorType?: string;
    /** OpenAI error `code`, when the error body was parsable. */
    readonly errorCode?: string;
    /** OpenAI error `param`, when the error body was parsable. */
    readonly errorParam?: string;
    /** Value of the `x-request-id` response header. */
    readonly requestId?: string;
    /** Response body (at most 64 KiB, credentials redacted). */
    readonly body?: string;
    /** Whether `body` was truncated at 64 KiB. */
    readonly bodyTruncated?: boolean;

    constructor(kind: DecisionsErrorKind, message: string, details: DecisionsErrorDetails = {}) {
        super(message, details.cause === undefined ? undefined : { cause: details.cause });
        this.name = "DecisionsError";
        this.kind = kind;
        this.status = details.status;
        this.errorType = details.errorType;
        this.errorCode = details.errorCode;
        this.errorParam = details.errorParam;
        this.requestId = details.requestId;
        this.body = details.body;
        this.bodyTruncated = details.bodyTruncated;
    }
}

/**
 * The `session.decisions` API group.
 *
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
export interface SessionDecisionsApi {
    /**
     * Calls the Decisions API (`POST {baseUrl}/decisions`) against the session's provider.
     * Requires the runtime to run with `COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true`.
     *
     * @experimental Part of the experimental Decisions API surface and may change or be removed.
     */
    create(request: DecisionsRequest, options?: DecisionsCallOptions): Promise<DecisionsResult>;
}

// ============================================================================
// Internal: endpoint -> request plan
// ============================================================================

const GATE_ENV = "COPILOT_ALLOW_GET_PROVIDER_ENDPOINT";
/** @internal */
export const MAX_ERROR_BODY_BYTES = 64 * 1024;
/** @internal */
export const MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
const MIN_SECRET_LENGTH = 8;
const HEADER_NAME = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/;
const INVALID_HEADER_VALUE = /[\r\n\0]/;
const HTTP_WHITESPACE = /^[ \t]+|[ \t]+$/g;

/** @internal */
export interface DecisionsAttemptPlan {
    url: string;
    /** Lowercase header names. */
    headers: Record<string, string>;
    fingerprint: string;
    secrets: string[];
}

function invalidEndpoint(message: string): DecisionsError {
    return new DecisionsError("invalidEndpoint", message);
}

/** @internal Builds the Decisions URL from the endpoint's base URL. */
export function buildDecisionsUrl(endpoint: Pick<ProviderEndpoint, "type" | "baseUrl">): string {
    let url: URL;
    try {
        url = new URL(endpoint.baseUrl);
    } catch {
        throw invalidEndpoint("The provider endpoint base URL is not a valid absolute URL.");
    }
    if (url.protocol !== "http:" && url.protocol !== "https:") {
        throw invalidEndpoint("The provider endpoint base URL must use http or https.");
    }
    if (url.username !== "" || url.password !== "") {
        throw invalidEndpoint("The provider endpoint base URL must not contain credentials.");
    }
    const basePath = url.pathname.replace(/\/+$/, "");
    const suffix =
        endpoint.type === "azure" && !basePath.toLowerCase().endsWith("/openai/v1")
            ? "/openai/v1/decisions"
            : "/decisions";
    return `${url.protocol}//${url.host}${basePath}${suffix}${url.search}`;
}

function checkHeader(name: string, value: string): string {
    if (!HEADER_NAME.test(name) || INVALID_HEADER_VALUE.test(value)) {
        throw invalidEndpoint("The provider endpoint returned an invalid HTTP header.");
    }
    return value.replace(HTTP_WHITESPACE, "");
}

/** @internal Merges endpoint headers and credentials into the outbound header set. */
export function buildDecisionsHeaders(endpoint: ProviderEndpoint): Record<string, string> {
    const merged = new Map<string, string>();
    for (const [name, rawValue] of Object.entries(endpoint.headers ?? {})) {
        if (rawValue === undefined || rawValue === null) continue;
        const value = checkHeader(name, rawValue);
        const lower = name.toLowerCase();
        if (lower === "content-type" || lower === "accept") continue;
        const existing = merged.get(lower);
        if (existing !== undefined && existing !== value) {
            throw invalidEndpoint(
                "The provider endpoint returned conflicting values for the same HTTP header."
            );
        }
        merged.set(lower, value);
    }

    let apiKeyHeader: string | undefined;
    if (endpoint.apiKey && !merged.has("authorization") && !merged.has("api-key")) {
        if (endpoint.type === "azure") {
            apiKeyHeader = "api-key";
            merged.set(apiKeyHeader, checkHeader(apiKeyHeader, endpoint.apiKey));
        } else {
            apiKeyHeader = "authorization";
            merged.set(apiKeyHeader, `Bearer ${checkHeader(apiKeyHeader, endpoint.apiKey)}`);
        }
    }

    if (endpoint.sessionToken) {
        const { header, token } = endpoint.sessionToken;
        const value = checkHeader(header, token);
        const lower = header.toLowerCase();
        if (lower === apiKeyHeader) {
            throw new DecisionsError(
                "credentialConflict",
                "The provider session token uses the same header as the endpoint API key."
            );
        }
        merged.set(lower, value);
    }

    merged.set("content-type", "application/json");
    merged.set("accept", "application/json");
    return Object.fromEntries(merged);
}

/** @internal Validates the endpoint and builds the request plan (deterministic check order). */
export function planDecisionsAttempt(
    endpoint: ProviderEndpoint,
    model: string
): DecisionsAttemptPlan {
    if (endpoint.type !== "openai" && endpoint.type !== "azure") {
        throw new DecisionsError(
            "unsupportedProvider",
            `The session's provider type '${String(endpoint.type)}' does not support the Decisions API.`
        );
    }
    if (endpoint.transport !== undefined && endpoint.transport !== "http") {
        throw new DecisionsError(
            "unsupportedTransport",
            `The session's provider transport '${String(endpoint.transport)}' is not supported by the Decisions API.`
        );
    }
    const tokenModel = endpoint.sessionToken?.model;
    if (tokenModel !== undefined && tokenModel !== null && tokenModel !== model) {
        throw new DecisionsError(
            "modelMismatch",
            `The provider session token is bound to model '${tokenModel}', but the request uses '${model}'. Set endpointModelId or request.model consistently.`
        );
    }
    const url = buildDecisionsUrl(endpoint);
    const headers = buildDecisionsHeaders(endpoint);
    const sessionTokenHeader = endpoint.sessionToken?.header.toLowerCase();
    const fingerprint = JSON.stringify([
        url,
        headers["authorization"] ?? null,
        headers["api-key"] ?? null,
        sessionTokenHeader === undefined
            ? null
            : [sessionTokenHeader, headers[sessionTokenHeader] ?? null],
    ]);
    return { url, headers, fingerprint, secrets: collectSecrets(endpoint, url) };
}

/** Length in Unicode scalar values, so the minimum is identical across SDK languages. */
const scalarLength = (value: string): number => [...value].length;

/**
 * @internal Credentials that must never surface in errors: the apiKey, every endpoint header value,
 * the session token, a bearer token without its scheme, and URL query values. Each is trimmed of
 * SP/HTAB, kept only if at least {@link MIN_SECRET_LENGTH} scalar values long, then expanded into its
 * wire encodings. Sorted longest first.
 */
export function collectSecrets(
    endpoint: Pick<ProviderEndpoint, "apiKey" | "sessionToken" | "headers">,
    url?: string
): string[] {
    const raw = new Set<string>();
    const add = (value: string | undefined | null) => {
        if (typeof value !== "string") return;
        const trimmed = value.replace(HTTP_WHITESPACE, "");
        if (scalarLength(trimmed) >= MIN_SECRET_LENGTH) raw.add(trimmed);
    };
    add(endpoint.apiKey);
    add(endpoint.sessionToken?.token);
    for (const value of Object.values(endpoint.headers ?? {})) {
        add(value);
        if (typeof value === "string") add(value.replace(/^[ \t]*bearer[ \t]+/i, ""));
    }
    if (url) {
        for (const value of new URL(url).searchParams.values()) add(value);
    }
    const variants = new Set<string>();
    for (const secret of raw) {
        for (const variant of secretVariants(secret)) variants.add(variant);
    }
    return [...variants].sort((a, b) => b.length - a.length);
}

/** Wire encodings under which a credential may be echoed back in a response body. */
function secretVariants(secret: string): string[] {
    const jsonEscaped = JSON.stringify(secret).slice(1, -1);
    const asciiJsonEscaped = jsonEscaped.replace(
        /[\u007f-\uffff]/g,
        (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, "0")}`
    );
    const variants = [secret, jsonEscaped, asciiJsonEscaped];
    for (const escaped of [jsonEscaped, asciiJsonEscaped]) {
        variants.push(escaped.replace(/\//g, "\\/"));
    }
    const uriEncoded = encodeURIComponent(secret);
    variants.push(uriEncoded, uriEncoded.replace(/%20/g, "+"));
    return variants;
}

/** Byte length of the longest secret, used as the redaction lookahead window. */
function maxSecretBytes(secrets: readonly string[]): number {
    const encoder = new TextEncoder();
    let max = 0;
    for (const secret of secrets) max = Math.max(max, encoder.encode(secret).byteLength);
    return max;
}

const escapeRegExp = (value: string): string => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * Regex source for one secret variant. Hex digits inside `\uXXXX` and `%XX` escapes match
 * case-insensitively because both casings are valid encodings of the same character.
 */
function variantPattern(variant: string): string {
    let out = "";
    let i = 0;
    const hex = (c: string) => (/[a-f]/i.test(c) ? `[${c.toLowerCase()}${c.toUpperCase()}]` : c);
    while (i < variant.length) {
        if (variant.startsWith("\\u", i) && /^[0-9a-f]{4}$/i.test(variant.slice(i + 2, i + 6))) {
            out += "\\\\u" + [...variant.slice(i + 2, i + 6)].map(hex).join("");
            i += 6;
        } else if (variant[i] === "%" && /^[0-9a-f]{2}$/i.test(variant.slice(i + 1, i + 3))) {
            out += "%" + [...variant.slice(i + 1, i + 3)].map(hex).join("");
            i += 3;
        } else {
            out += escapeRegExp(variant[i]!);
            i += 1;
        }
    }
    return out;
}

/** @internal Replaces every occurrence of any secret variant with `[REDACTED]`, longest first. */
export function redactSecrets(text: string, secrets: readonly string[]): string {
    let result = text;
    for (const secret of secrets) {
        result = result.replace(new RegExp(variantPattern(secret), "g"), "[REDACTED]");
    }
    return result;
}

// ============================================================================
// Internal: wire serialization / parsing
// ============================================================================

type JsonObject = Record<string, unknown>;

function setIfPresent(target: JsonObject, key: string, value: unknown): void {
    if (value !== undefined && value !== null) target[key] = value;
}

function serializePart(part: {
    type: string;
    [key: string]: unknown;
}): JsonObject {
    switch (part.type) {
        case "input_text": {
            const p = part as unknown as DecisionsInputText;
            const out: JsonObject = { type: "input_text", text: p.text };
            setIfPresent(out, "prompt_cache_breakpoint", p.promptCacheBreakpoint);
            return out;
        }
        case "input_image": {
            const p = part as unknown as DecisionsInputImage;
            const out: JsonObject = { type: "input_image", image_url: p.imageUrl };
            setIfPresent(out, "detail", p.detail);
            setIfPresent(out, "prompt_cache_breakpoint", p.promptCacheBreakpoint);
            return out;
        }
        case "input_json":
            return { type: "input_json", data: (part as unknown as DecisionsInputJson).data };
        case "output_text":
            return { type: "output_text", text: (part as unknown as DecisionsOutputText).text };
        case "refusal":
            return {
                type: "refusal",
                refusal: (part as unknown as DecisionsRefusalContent).refusal,
            };
        default:
            throw new TypeError(`Unsupported Decisions content part type '${part.type}'.`);
    }
}

function serializeContent(content: string | { type: string }[]): unknown {
    return typeof content === "string"
        ? content
        : content.map((part) => serializePart(part as { type: string }));
}

function serializeInputItem(item: DecisionsInputItem): JsonObject {
    if ("raw" in item) return item.raw;
    if (!("type" in item) || item.type === undefined || item.type === "message") {
        const message = item as
            | DecisionsUserMessage
            | DecisionsDeveloperMessage
            | DecisionsAssistantMessage;
        const out: JsonObject = {};
        if (message.type === "message") out.type = "message";
        out.role = message.role;
        out.content = serializeContent(message.content as string | { type: string }[]);
        if (message.role === "assistant") setIfPresent(out, "phase", message.phase);
        return out;
    }
    switch (item.type) {
        case "function_call": {
            const out: JsonObject = {
                type: item.type,
                call_id: item.callId,
                name: item.name,
                arguments: item.arguments,
            };
            setIfPresent(out, "id", item.id);
            setIfPresent(out, "namespace", item.namespace);
            setIfPresent(out, "status", item.status);
            return out;
        }
        case "custom_tool_call": {
            const out: JsonObject = {
                type: item.type,
                call_id: item.callId,
                name: item.name,
                input: item.input,
            };
            setIfPresent(out, "id", item.id);
            setIfPresent(out, "namespace", item.namespace);
            setIfPresent(out, "status", item.status);
            return out;
        }
        case "function_call_output":
        case "custom_tool_call_output": {
            const out: JsonObject = {
                type: item.type,
                call_id: item.callId,
                output: serializeContent(item.output),
            };
            setIfPresent(out, "id", item.id);
            setIfPresent(out, "status", item.status);
            return out;
        }
    }
}

function serializeQuestion(question: DecisionsQuestion): JsonObject {
    const out: JsonObject = { type: question.type };
    setIfPresent(out, "name", question.name);
    out.instructions = serializeContent(question.instructions as string | { type: string }[]);
    if (question.type === "choice") {
        out.choices = question.choices.map((choice) => {
            const c: JsonObject = { value: choice.value };
            setIfPresent(c, "description", choice.description);
            return c;
        });
    } else if (question.type === "score") {
        out.levels = question.levels.map((level) => {
            const l: JsonObject = { label: level.label };
            setIfPresent(l, "description", level.description);
            return l;
        });
    }
    return out;
}

/** @internal Converts a typed request into its wire JSON object. */
export function serializeDecisionsRequest(request: DecisionsRequest): JsonObject {
    if (typeof request?.model !== "string" || request.model === "") {
        throw new TypeError("DecisionsRequest.model is required.");
    }
    const out: JsonObject = {
        model: request.model,
        input:
            typeof request.input === "string"
                ? request.input
                : request.input.map((item) => serializeInputItem(item)),
        questions: request.questions.map((q) => serializeQuestion(q)),
    };
    setIfPresent(out, "prompt_cache_key", request.promptCacheKey);
    if (request.promptCacheOptions !== undefined && request.promptCacheOptions !== null) {
        const options: JsonObject = {};
        setIfPresent(options, "mode", request.promptCacheOptions.mode);
        setIfPresent(options, "ttl", request.promptCacheOptions.ttl);
        out.prompt_cache_options = options;
    }
    setIfPresent(out, "safety_identifier", request.safetyIdentifier);
    return out;
}

class InvalidResponse extends Error {}

function isObject(value: unknown): value is JsonObject {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function num(obj: JsonObject, key: string): number {
    const value = obj[key];
    if (typeof value !== "number" || !Number.isFinite(value)) {
        throw new InvalidResponse(`Expected number '${key}'.`);
    }
    return value;
}

function optionalNum(obj: JsonObject | undefined, key: string): number {
    if (!obj || obj[key] === undefined || obj[key] === null) return 0;
    return num(obj, key);
}

function str(obj: JsonObject, key: string): string {
    const value = obj[key];
    if (typeof value !== "string") throw new InvalidResponse(`Expected string '${key}'.`);
    return value;
}

function arr(obj: JsonObject, key: string): JsonObject[] {
    const value = obj[key];
    if (!Array.isArray(value) || !value.every(isObject)) {
        throw new InvalidResponse(`Expected array of objects '${key}'.`);
    }
    return value as JsonObject[];
}

function choiceValue(obj: JsonObject, key: string): string | boolean {
    const value = obj[key];
    if (typeof value !== "string" && typeof value !== "boolean") {
        throw new InvalidResponse(`Expected string or boolean '${key}'.`);
    }
    return value;
}

function answerName(obj: JsonObject): string | null {
    const value = obj.name;
    if (value === undefined || value === null) return null;
    if (typeof value !== "string") throw new InvalidResponse("Expected string 'name'.");
    return value;
}

function parseAnswer(obj: JsonObject): DecisionsAnswer {
    const type = str(obj, "type");
    const name = answerName(obj);
    switch (type) {
        case "predicate":
            return { type, name, probability: num(obj, "probability") };
        case "choice":
            return {
                type,
                name,
                choice: choiceValue(obj, "choice"),
                confidence: num(obj, "confidence"),
                probabilities: arr(obj, "probabilities").map((p) => ({
                    value: choiceValue(p, "value"),
                    probability: num(p, "probability"),
                })),
            };
        case "score":
            return {
                type,
                name,
                score: num(obj, "score"),
                confidence: num(obj, "confidence"),
                probabilities: arr(obj, "probabilities").map((p) => {
                    const value = num(p, "value");
                    if (!Number.isInteger(value)) {
                        throw new InvalidResponse("Expected integer level 'value'.");
                    }
                    return { label: str(p, "label"), value, probability: num(p, "probability") };
                }),
            };
        case "refusal":
            return { type, name };
        default:
            return { type: "unknown", name, wireType: type, raw: obj };
    }
}

/** @internal Parses a wire response into a typed result. Throws `DecisionsError` (invalidResponse). */
export function parseDecisionsResult(json: unknown): DecisionsResult {
    try {
        if (!isObject(json)) throw new InvalidResponse("Expected a JSON object.");
        const usage = json.usage;
        if (!isObject(usage)) throw new InvalidResponse("Expected object 'usage'.");
        const inputDetails = isObject(usage.input_tokens_details)
            ? usage.input_tokens_details
            : undefined;
        const outputDetails = isObject(usage.output_tokens_details)
            ? usage.output_tokens_details
            : undefined;
        return {
            answers: arr(json, "answers").map(parseAnswer),
            model: str(json, "model"),
            usage: {
                inputTokens: optionalNum(usage, "input_tokens"),
                inputTokensDetails: {
                    cachedTokens: optionalNum(inputDetails, "cached_tokens"),
                    cacheWriteTokens: optionalNum(inputDetails, "cache_write_tokens"),
                },
                outputTokens: optionalNum(usage, "output_tokens"),
                outputTokensDetails: {
                    reasoningTokens: optionalNum(outputDetails, "reasoning_tokens"),
                },
                totalTokens: optionalNum(usage, "total_tokens"),
            },
        };
    } catch (error) {
        if (error instanceof InvalidResponse) {
            throw new DecisionsError(
                "invalidResponse",
                `The Decisions API returned an unexpected response: ${error.message}`
            );
        }
        throw error;
    }
}

// ============================================================================
// Internal: HTTP
// ============================================================================

class BodyReadError extends Error {}

/**
 * Reads at most `limit` bytes. `exceeded` reports whether the stream held more data; the rest of
 * the stream is cancelled so the connection can be released.
 */
async function readLimited(
    response: Response,
    limit: number
): Promise<{ bytes: Uint8Array; exceeded: boolean }> {
    if (!response.body) return { bytes: new Uint8Array(0), exceeded: false };
    const reader = response.body.getReader();
    const chunks: Uint8Array[] = [];
    let total = 0;
    let exceeded = false;
    try {
        for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            const remaining = limit - total;
            if (value.byteLength > remaining) {
                chunks.push(value.subarray(0, remaining));
                total = limit;
                exceeded = true;
                await reader.cancel().catch(() => undefined);
                break;
            }
            chunks.push(value);
            total += value.byteLength;
        }
    } catch (error) {
        throw new BodyReadError("Failed to read the response body.", { cause: error });
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
        bytes.set(chunk, offset);
        offset += chunk.byteLength;
    }
    return { bytes, exceeded };
}

const decodeUtf8 = (bytes: Uint8Array): string =>
    new TextDecoder("utf-8", { fatal: false }).decode(bytes);

/**
 * Reads an error body capped at {@link MAX_ERROR_BODY_BYTES}. A lookahead window as long as the
 * longest secret is read past the cap and redaction runs before truncation, so a credential that
 * straddles the cap is never partially retained.
 */
async function readRedactedErrorBody(
    response: Response,
    secrets: readonly string[]
): Promise<{ raw: string; body: string; truncated: boolean }> {
    const window = MAX_ERROR_BODY_BYTES + maxSecretBytes(secrets);
    const { bytes, exceeded } = await readLimited(response, window);
    const raw = decodeUtf8(bytes);
    const redacted = redactSecrets(raw, secrets);
    const truncated = exceeded || bytes.byteLength > MAX_ERROR_BODY_BYTES;
    if (!truncated) return { raw, body: redacted, truncated };
    const encoded = new TextEncoder().encode(redacted);
    let cut = MAX_ERROR_BODY_BYTES;
    while (cut > 0 && (encoded[cut]! & 0xc0) === 0x80) cut--;
    return {
        raw,
        body: decodeUtf8(encoded.subarray(0, cut)),
        truncated,
    };
}

function stringField(value: unknown): string | undefined {
    if (value === undefined || value === null) return undefined;
    if (typeof value === "string") return value;
    if (typeof value === "number" || typeof value === "boolean") return String(value);
    return undefined;
}

/** @internal Maps a non-2xx response to a `DecisionsError` (bounded, redacted). */
export async function decisionsHttpError(
    response: Response,
    secrets: readonly string[]
): Promise<DecisionsError> {
    const { raw, body, truncated } = await readRedactedErrorBody(response, secrets);
    let errorType: string | undefined;
    let errorCode: string | undefined;
    let errorParam: string | undefined;
    let errorMessage: string | undefined;
    if (!truncated) {
        try {
            const parsed: unknown = JSON.parse(raw);
            if (isObject(parsed) && isObject(parsed.error)) {
                const redact = (value: unknown) => {
                    const s = stringField(value);
                    return s === undefined ? undefined : redactSecrets(s, secrets);
                };
                errorType = redact(parsed.error.type);
                errorCode = redact(parsed.error.code);
                errorParam = redact(parsed.error.param);
                errorMessage = redact(parsed.error.message);
            }
        } catch {
            // Non-JSON error body.
        }
    }
    const status = response.status;
    const statusText =
        status >= 300 && status < 400
            ? `HTTP ${status} (redirects are not followed)`
            : `HTTP ${status}`;
    return new DecisionsError(
        "http",
        `Decisions request failed with ${statusText}${errorMessage ? `: ${errorMessage}` : "."}`,
        {
            status,
            errorType,
            errorCode,
            errorParam,
            requestId: response.headers.get("x-request-id") ?? undefined,
            body,
            bodyTruncated: truncated,
        }
    );
}

/**
 * Builds a credential-free stand-in for a transport error: only the error name, a string `code`,
 * and the redacted message are retained, so request URLs and headers cannot leak through `cause`.
 */
function sanitizeCause(error: unknown, secrets: readonly string[]): Error {
    const source = error instanceof BodyReadError ? error.cause : error;
    const name = source instanceof Error ? source.name : "Error";
    const message = redactSecrets(
        source instanceof Error ? source.message : String(source),
        secrets
    );
    const sanitized = new Error(message);
    sanitized.name = name;
    const code = isObject(source) ? source.code : undefined;
    if (typeof code === "string") (sanitized as Error & { code?: string }).code = code;
    return sanitized;
}

function networkError(message: string, error: unknown, secrets: readonly string[]): DecisionsError {
    return new DecisionsError("network", message, { cause: sanitizeCause(error, secrets) });
}

function endpointUnavailable(cause: unknown): DecisionsError {
    const text = cause instanceof Error ? cause.message : String(cause);
    const message = text.includes(GATE_ENV)
        ? `session.decisions requires the runtime's provider endpoint API. Set ${GATE_ENV}=true in the runtime's environment (for in-process runtimes, in the host process environment before the runtime is loaded).`
        : "Could not resolve the session's provider endpoint for the Decisions API.";
    return new DecisionsError("endpointUnavailable", message, { cause });
}

/** @internal Resolves the session's current provider endpoint. */
export type DecisionsEndpointResolver = (
    params?: SessionProviderGetEndpointRequest
) => Promise<ProviderEndpoint>;

/** @internal Implementation of `session.decisions`. */
export class SessionDecisions implements SessionDecisionsApi {
    constructor(private readonly resolveEndpoint: DecisionsEndpointResolver) {}

    async create(
        request: DecisionsRequest,
        options: DecisionsCallOptions = {}
    ): Promise<DecisionsResult> {
        if (typeof request?.model !== "string" || request.model === "") {
            throw new TypeError("DecisionsRequest.model is required.");
        }
        const { signal } = options;
        const fetchImpl = options.fetch ?? fetch;

        let plan = planDecisionsAttempt(await this.resolve(options), request.model);
        const body = JSON.stringify(serializeDecisionsRequest(request));
        let response = await this.send(fetchImpl, plan, body, signal);

        if (response.status === 401 || response.status === 403) {
            const original = await this.httpError(response, plan, signal);
            const refreshedEndpoint = await this.resolve(options);
            let refreshed: DecisionsAttemptPlan;
            try {
                refreshed = planDecisionsAttempt(refreshedEndpoint, request.model);
            } catch {
                throw original;
            }
            if (refreshed.fingerprint === plan.fingerprint) throw original;
            plan = refreshed;
            response = await this.send(fetchImpl, plan, body, signal);
        }

        if (response.status < 200 || response.status >= 300) {
            throw await this.httpError(response, plan, signal);
        }

        let bytes: Uint8Array;
        let exceeded: boolean;
        try {
            ({ bytes, exceeded } = await readLimited(response, MAX_RESPONSE_BYTES));
        } catch (error) {
            if (signal?.aborted) throw signal.reason;
            throw networkError("The Decisions response could not be read.", error, plan.secrets);
        }
        if (exceeded) {
            throw new DecisionsError(
                "invalidResponse",
                `The Decisions API response exceeded ${MAX_RESPONSE_BYTES} bytes.`
            );
        }
        let json: unknown;
        try {
            json = JSON.parse(decodeUtf8(bytes));
        } catch {
            throw new DecisionsError(
                "invalidResponse",
                "The Decisions API returned a response that is not valid JSON."
            );
        }
        return parseDecisionsResult(json);
    }

    private async httpError(
        response: Response,
        plan: DecisionsAttemptPlan,
        signal: AbortSignal | undefined
    ): Promise<DecisionsError> {
        try {
            return await decisionsHttpError(response, plan.secrets);
        } catch (error) {
            if (signal?.aborted) throw signal.reason;
            throw networkError("The Decisions error response could not be read.", error, plan.secrets);
        }
    }

    private async resolve(options: DecisionsCallOptions): Promise<ProviderEndpoint> {
        options.signal?.throwIfAborted();
        let endpoint: ProviderEndpoint;
        try {
            endpoint = await this.resolveEndpoint(
                options.endpointModelId === undefined
                    ? undefined
                    : { modelId: options.endpointModelId }
            );
        } catch (error) {
            options.signal?.throwIfAborted();
            throw endpointUnavailable(error);
        }
        options.signal?.throwIfAborted();
        return endpoint;
    }

    private async send(
        fetchImpl: typeof fetch,
        plan: DecisionsAttemptPlan,
        body: string,
        signal: AbortSignal | undefined
    ): Promise<Response> {
        try {
            return await fetchImpl(plan.url, {
                method: "POST",
                headers: plan.headers,
                body,
                redirect: "manual",
                signal,
            });
        } catch (error) {
            if (signal?.aborted) throw error;
            throw networkError("The Decisions request could not be sent.", error, plan.secrets);
        }
    }
}
