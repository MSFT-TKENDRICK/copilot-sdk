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

import { request as httpRequest } from "node:http";
import { request as httpsRequest } from "node:https";
import { isIP } from "node:net";
import { pipeline, Readable } from "node:stream";
import { createBrotliDecompress, createGunzip, createInflate } from "node:zlib";
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
    type?: "message" | null;
    role: "user";
    content: string | (DecisionsInputText | DecisionsInputImage | DecisionsInputJson)[];
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsDeveloperMessage {
    type?: "message" | null;
    role: "developer" | "system";
    content: string | DecisionsInputText[];
}

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsAssistantMessage {
    type?: "message" | null;
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
     * Cancels the entire call with the caller's native abort reason. A pending endpoint RPC
     * cannot itself be cancelled, but the SDK stops waiting and observes its eventual outcome.
     */
    signal?: AbortSignal;
    /**
     * Total-call deadline in milliseconds, including endpoint resolution, bodies, and replay.
     * Defaults to 600000 (ten minutes). Set to null to disable; otherwise must be finite and
     * positive. SDK deadline expiry raises DecisionsError with kind "timeout".
     */
    timeoutMs?: number | null;
    /**
     * Passed verbatim as `modelId` to `session.provider.getEndpoint`. Omit to use the session's
     * current endpoint. This is never derived from `request.model`.
     */
    endpointModelId?: string;
    /**
     * Custom fetch implementation (proxying, testing). Injected implementations are trusted and
     * own their redirect and TLS policy; the SDK passes `redirect: "manual"` and an abort signal.
     * An injected transport's independent timeout is a network error, not the SDK deadline.
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
    /** Signed safe-integer index of the level (including provider sentinels). */
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

/**
 * Usage counters are signed JavaScript safe integers; absent counters default to zero.
 * @experimental Part of the experimental Decisions API surface and may change or be removed.
 */
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
    | "network"
    | "timeout";

/** @experimental Part of the experimental Decisions API surface and may change or be removed. */
export interface DecisionsErrorDetails {
    status?: number;
    errorType?: string;
    errorCode?: string;
    errorParam?: string;
    requestId?: string;
    body?: string;
    bodyTruncated?: boolean;
    retryAfterMs?: number;
    cause?: unknown;
}

/**
 * Error raised by `session.decisions.create`. Retained diagnostics redact known endpoint
 * credentials and their supported wire encodings, not arbitrary transformations or general PII.
 * Caller cancellation retains its native reason; only the SDK deadline uses kind "timeout".
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
    /** Bounded, credential-redacted value of the `x-request-id` response header. */
    readonly requestId?: string;
    /** Response body (at most 64 KiB, credentials redacted). */
    readonly body?: string;
    /** Whether the original body exceeded 64 KiB, independent of redaction expansion. */
    readonly bodyTruncated?: boolean;
    /** Advisory delay from retry-after-ms or Retry-After. Does not cause automatic retries. */
    readonly retryAfterMs?: number;

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
        this.retryAfterMs = details.retryAfterMs;
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
const INVALID_HEADER_VALUE = /[\x00-\x08\x0a-\x1f\x7f]/;
const HTTP_WHITESPACE = /^[ \t]+|[ \t]+$/g;
const DEFAULT_TIMEOUT_MS = 600_000;
const MAX_TIMER_MS = 2_147_483_647;

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
    const baseUrl = endpoint.baseUrl;
    if (
        typeof baseUrl !== "string" ||
        !/^https?:\/\//i.test(baseUrl) ||
        /[^\x21-\x7e]|\\/.test(baseUrl) ||
        /%(?![0-9a-f]{2})/i.test(baseUrl)
    ) {
        throw invalidEndpoint("The provider endpoint must be an encoded absolute HTTP(S) URL.");
    }
    const authority = baseUrl.slice(baseUrl.indexOf("://") + 3).split(/[/?#]/, 1)[0]!;
    if (!authority || authority.includes("@")) {
        throw invalidEndpoint("The provider endpoint URL must have a host and no userinfo.");
    }
    const hostPort = authority.startsWith("[")
        ? /^(\[[^\]]+\])(?::([0-9]+))?$/.exec(authority)
        : /^([^:]+)(?::([0-9]+))?$/.exec(authority);
    if (!hostPort || (hostPort[2] !== undefined && Number(hostPort[2]) > 65535)) {
        throw invalidEndpoint("The provider endpoint URL has an invalid host or port.");
    }
    let url: URL;
    try {
        url = new URL(baseUrl);
    } catch {
        throw invalidEndpoint("The provider endpoint base URL is not a valid absolute URL.");
    }
    if (url.protocol !== "http:" && url.protocol !== "https:") {
        throw invalidEndpoint("The provider endpoint base URL must use http or https.");
    }
    if (url.username !== "" || url.password !== "") {
        throw invalidEndpoint("The provider endpoint base URL must not contain credentials.");
    }
    const hostname = url.hostname;
    const validHost = hostname.startsWith("[")
        ? isIP(hostname.slice(1, -1)) === 6
        : hostname.length <= 254 &&
          hostname.replace(/\.$/, "").split(".").every((label) =>
              /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label)
          );
    if (!validHost) throw invalidEndpoint("The provider endpoint URL has an invalid host.");
    const basePath = url.pathname.replace(/\/+$/, "");
    const suffix =
        endpoint.type === "azure" && !basePath.toLowerCase().endsWith("/openai/v1")
            ? "/openai/v1/decisions"
            : "/decisions";
    return `${url.protocol}//${url.host}${basePath}${suffix}${url.search}`;
}

function checkHeader(name: string, value: string): string {
    if (
        typeof name !== "string" ||
        typeof value !== "string" ||
        !HEADER_NAME.test(name) ||
        INVALID_HEADER_VALUE.test(value) ||
        hasUnpairedSurrogate(value)
    ) {
        throw invalidEndpoint("The provider endpoint returned an invalid HTTP header.");
    }
    return value.replace(HTTP_WHITESPACE, "");
}

function hasUnpairedSurrogate(value: string): boolean {
    for (let i = 0; i < value.length; i++) {
        const code = value.charCodeAt(i);
        if (code >= 0xd800 && code <= 0xdbff) {
            const next = value.charCodeAt(++i);
            if (!(next >= 0xdc00 && next <= 0xdfff)) return true;
        } else if (code >= 0xdc00 && code <= 0xdfff) {
            return true;
        }
    }
    return false;
}

/** @internal Merges endpoint headers and credentials into the outbound header set. */
export function buildDecisionsHeaders(endpoint: ProviderEndpoint): Record<string, string> {
    if (endpoint.headers != null && !isObject(endpoint.headers)) {
        throw invalidEndpoint("The provider endpoint returned invalid HTTP headers.");
    }
    if (endpoint.apiKey != null) checkHeader("api-key", endpoint.apiKey);
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
    if (!isObject(endpoint)) throw invalidEndpoint("The provider endpoint is invalid.");
    if (endpoint.type !== "openai" && endpoint.type !== "azure") {
        throw new DecisionsError(
            "unsupportedProvider",
            "The session's provider type does not support the Decisions API."
        );
    }
    if (endpoint.transport !== undefined && endpoint.transport !== "http") {
        throw new DecisionsError(
            "unsupportedTransport",
            "The session's provider transport is not supported by the Decisions API."
        );
    }
    const tokenModel = endpoint.sessionToken?.model;
    if (tokenModel !== undefined && tokenModel !== null && tokenModel !== model) {
        throw new DecisionsError(
            "modelMismatch",
            "The provider session token is bound to a different model. Set endpointModelId or request.model consistently."
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
    return { url, headers, fingerprint, secrets: collectSecrets(endpoint, url, headers) };
}

/** Length in Unicode scalar values, so the minimum is identical across SDK languages. */
const scalarLength = (value: string): number => [...value].length;

/**
 * @internal Protect every nonempty SP/HTAB-trimmed actual credential, regardless of length.
 * Other header/query values use the eight-Unicode-scalar heuristic. Qualifying decoded query
 * values also retain their raw percent/form representation, including lossy UTF-8 decoding.
 * Supported literal, JSON, escaped-slash, percent and form encodings are protected, not general PII.
 */
export function collectSecrets(
    endpoint: Pick<ProviderEndpoint, "apiKey" | "sessionToken" | "headers">,
    url?: string,
    effectiveHeaders?: Record<string, string>
): string[] {
    const raw = new Set<string>();
    const add = (value: string | undefined | null, credential = false) => {
        if (typeof value !== "string") return false;
        const trimmed = value.replace(HTTP_WHITESPACE, "");
        if (!trimmed || (!credential && scalarLength(trimmed) < MIN_SECRET_LENGTH)) return false;
        raw.add(trimmed);
        return true;
    };
    add(endpoint.apiKey, true);
    add(endpoint.sessionToken?.token, true);
    const tokenHeader = endpoint.sessionToken?.header.toLowerCase();
    if (tokenHeader === "authorization") {
        add(endpoint.sessionToken?.token.replace(/^[ \t]*bearer[ \t]+/i, ""), true);
    }
    for (const [name, value] of [
        ...Object.entries(endpoint.headers ?? {}),
        ...Object.entries(effectiveHeaders ?? {}),
    ]) {
        const lower = name.toLowerCase();
        const credential = lower === "authorization" || lower === "api-key" || lower === tokenHeader;
        add(value, credential);
        if (typeof value === "string") {
            add(value.replace(/^[ \t]*bearer[ \t]+/i, ""), credential);
        }
    }
    if (url) {
        for (const parameter of new URL(url).search.slice(1).split("&")) {
            const equals = parameter.indexOf("=");
            if (equals < 0) continue;
            const decoded = new URLSearchParams(parameter).values().next().value;
            // Encoded length must not promote a short logical value. Preserve the original
            // spelling once it qualifies: re-encoding U+FFFD cannot recover a raw %FF echo.
            if (add(decoded)) add(parameter.slice(equals + 1));
        }
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

const foldHex = (value: string): string => value.replace(/[A-F]/g, (c) => c.toLowerCase());

/** Literal spans outside case-insensitive escape digits, preserving original character offsets. */
function literalSpans(variant: string): { offset: number; text: string }[] {
    const spans: { offset: number; text: string }[] = [];
    let start = 0;
    for (const match of variant.matchAll(/\\u[0-9a-fA-F]{4}|%[0-9a-fA-F]{2}/g)) {
        for (let i = match.index!; i < match.index! + match[0].length; i++) {
            if (/[a-fA-F]/.test(variant[i]!)) {
                if (i > start) spans.push({ offset: start, text: variant.slice(start, i) });
                start = i + 1;
            }
        }
    }
    if (start < variant.length) spans.push({ offset: start, text: variant.slice(start) });
    return spans;
}

/** Match original text, including self-overlap, before doing any replacement. */
function secretIntervals(text: string, secrets: readonly string[]): [number, number][] {
    const matches: [number, number][] = [];
    let folded: string | undefined;
    for (const secret of secrets) {
        if (!secret) continue;
        const spans = literalSpans(secret);
        const exact = spans.length === 1 && spans[0]!.text === secret;
        // Avoid constructing a provider-sized regex. Fold ASCII hex for candidate search only,
        // then verify every ordinary character against the unmodified original text.
        const haystack = exact ? text : (folded ??= foldHex(text));
        const needle = exact ? secret : foldHex(secret);
        let from = 0;
        for (;;) {
            const index = haystack.indexOf(needle, from);
            if (index < 0) break;
            if (exact || spans.every((span) => text.startsWith(span.text, index + span.offset))) {
                matches.push([index, index + secret.length]);
            }
            from = index + 1;
        }
    }
    matches.sort((a, b) => a[0] - b[0] || b[1] - a[1]);
    const unions: [number, number][] = [];
    for (const match of matches) {
        const previous = unions[unions.length - 1];
        if (previous && match[0] < previous[1]) {
            previous[1] = Math.max(previous[1], match[1]);
        } else {
            unions.push([...match]);
        }
    }
    return unions;
}

/**
 * @internal Unions all overlapping original matches. `end` bounds ORIGINAL retained text;
 * matches crossing it are replaced whole, and lookahead never becomes retained content.
 */
export function redactSecrets(
    text: string,
    secrets: readonly string[],
    end = text.length
): string {
    const parts: string[] = [];
    let start = 0;
    for (const [from, to] of secretIntervals(text, secrets)) {
        if (from >= end) break;
        parts.push(text.slice(start, from), "[REDACTED]");
        start = to;
    }
    if (start < end) parts.push(text.slice(start, end));
    return parts.join("");
}

// ============================================================================
// Internal: wire serialization / parsing
// ============================================================================

type JsonObject = Record<string, unknown>;

function setIfPresent(target: JsonObject, key: string, value: unknown): void {
    if (value !== undefined && value !== null) target[key] = value;
}

function requiredText(value: unknown): string {
    if (typeof value !== "string") throw new TypeError("Expected a Decisions text value.");
    return value;
}

function serializePart(
    part: unknown,
    allowed: readonly string[],
    cacheBreakpoints: boolean
): JsonObject {
    if (!isObject(part) || typeof part.type !== "string" || !allowed.includes(part.type)) {
        throw new TypeError("Unsupported Decisions content part for this context.");
    }
    const breakpoint = cacheBreakpoints ? part.promptCacheBreakpoint : undefined;
    if (breakpoint != null && (!isObject(breakpoint) || breakpoint.mode !== "explicit")) {
        throw new TypeError("Invalid Decisions cache breakpoint.");
    }
    switch (part.type) {
        case "input_text": {
            const out: JsonObject = { type: "input_text", text: requiredText(part.text) };
            setIfPresent(out, "prompt_cache_breakpoint", breakpoint);
            return out;
        }
        case "input_image": {
            if (part.detail != null && (
                typeof part.detail !== "string" || !["low", "high", "auto", "original"].includes(part.detail)
            )) {
                throw new TypeError("Invalid Decisions image detail.");
            }
            const out: JsonObject = { type: "input_image", image_url: requiredText(part.imageUrl) };
            setIfPresent(out, "detail", part.detail);
            setIfPresent(out, "prompt_cache_breakpoint", breakpoint);
            return out;
        }
        case "input_json": {
            if (!isObject(part.data) && !(Array.isArray(part.data) && part.data.every(isObject))) {
                throw new TypeError("Expected an object or object array for Decisions JSON content.");
            }
            return { type: "input_json", data: part.data };
        }
        case "output_text":
            return { type: "output_text", text: requiredText(part.text) };
        case "refusal":
            return { type: "refusal", refusal: requiredText(part.refusal) };
        default:
            throw new TypeError("Unsupported Decisions content part.");
    }
}

function serializeContent(
    content: unknown,
    allowed: readonly string[],
    cacheBreakpoints = true
): unknown {
    if (typeof content === "string") return content;
    if (!Array.isArray(content)) throw new TypeError("Expected Decisions content text or parts.");
    return Array.from(content, (part) => serializePart(part, allowed, cacheBreakpoints));
}

function serializeInputItem(item: DecisionsInputItem): JsonObject {
    if (!isObject(item)) throw new TypeError("Expected a Decisions input item.");
    if ("raw" in item) {
        if (!isObject(item.raw)) throw new TypeError("Expected a raw Decisions input object.");
        return item.raw;
    }
    if (!("type" in item) || item.type == null || item.type === "message") {
        const message = item as
            | DecisionsUserMessage
            | DecisionsDeveloperMessage
            | DecisionsAssistantMessage;
        const out: JsonObject = {};
        if (message.type === "message") out.type = "message";
        out.role = message.role;
        let allowed: readonly string[];
        switch (message.role) {
            case "user":
                allowed = ["input_text", "input_image", "input_json"];
                break;
            case "developer":
            case "system":
                allowed = ["input_text"];
                break;
            case "assistant":
                allowed = ["output_text", "refusal"];
                break;
            default:
                throw new TypeError("Unsupported Decisions message role.");
        }
        out.content = serializeContent(message.content, allowed);
        if (message.role === "assistant") setIfPresent(out, "phase", message.phase);
        return out;
    }
    switch (item.type) {
        case "function_call": {
            const out: JsonObject = {
                type: item.type,
                call_id: requiredText(item.callId),
                name: requiredText(item.name),
                arguments: requiredText(item.arguments),
            };
            setIfPresent(out, "id", item.id);
            setIfPresent(out, "namespace", item.namespace);
            setIfPresent(out, "status", item.status);
            return out;
        }
        case "custom_tool_call": {
            const out: JsonObject = {
                type: item.type,
                call_id: requiredText(item.callId),
                name: requiredText(item.name),
                input: requiredText(item.input),
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
                call_id: requiredText(item.callId),
                output: serializeContent(item.output, ["input_text", "input_image"]),
            };
            setIfPresent(out, "id", item.id);
            setIfPresent(out, "status", item.status);
            return out;
        }
        default:
            throw new TypeError("Unsupported Decisions input item. Use an explicit raw wrapper.");
    }
}

function serializeQuestion(question: DecisionsQuestion): JsonObject {
    if (!isObject(question) || !["predicate", "choice", "score"].includes(question.type)) {
        throw new TypeError("Unsupported Decisions question.");
    }
    const out: JsonObject = { type: question.type };
    setIfPresent(out, "name", question.name);
    out.instructions = serializeContent(
        question.instructions,
        ["input_text", "input_image", "input_json"],
        false
    );
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
    if (typeof request.input !== "string" && !Array.isArray(request.input)) {
        throw new TypeError("Expected Decisions input text or an array of input items.");
    }
    if (!Array.isArray(request.questions)) throw new TypeError("Expected Decisions questions.");
    const out: JsonObject = {
        model: request.model,
        input:
            typeof request.input === "string"
                ? request.input
                : Array.from(request.input, serializeInputItem),
        questions: Array.from(request.questions, serializeQuestion),
    };
    setIfPresent(out, "prompt_cache_key", request.promptCacheKey);
    if (request.promptCacheOptions !== undefined && request.promptCacheOptions !== null) {
        if (request.promptCacheOptions.ttl != null && request.promptCacheOptions.ttl !== "30m") {
            throw new TypeError("Unsupported Decisions cache TTL; only 30m is supported.");
        }
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

function integer(obj: JsonObject, key: string, source?: JsonObject): number {
    const value = num(obj, key);
    if (!Number.isSafeInteger(value) || (source && !exactInteger(source[key], value))) {
        throw new InvalidResponse(`Expected signed safe integer '${key}'.`);
    }
    return value;
}

function exactInteger(token: unknown, value: number): boolean {
    if (typeof token !== "string") return false;
    const match = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(token);
    if (!match) return false;
    const fraction = match[3] ?? "";
    const digits = (match[2]! + fraction).replace(/^0+/, "");
    if (!digits) return value === 0;
    const significant = digits.replace(/0+$/, "");
    const scale = Number(match[4] ?? 0) - fraction.length + digits.length - significant.length;
    if (!Number.isFinite(scale) || scale < 0 || significant.length + scale > 16) return false;
    return match[1] + significant + "0".repeat(scale) === String(value);
}

function optionalInteger(obj: JsonObject | undefined, key: string, source?: JsonObject): number {
    if (!obj || !Object.hasOwn(obj, key)) return 0;
    return integer(obj, key, source);
}

function details(obj: JsonObject, key: string): JsonObject | undefined {
    if (!Object.hasOwn(obj, key)) return undefined;
    if (!isObject(obj[key])) throw new InvalidResponse(`Expected object '${key}'.`);
    return obj[key];
}

function probability(obj: JsonObject, key: string): number {
    const value = num(obj, key);
    if (value < 0 || value > 1) throw new InvalidResponse(`Expected probability '${key}'.`);
    return value;
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

function parseAnswer(obj: JsonObject, source?: JsonObject): DecisionsAnswer {
    const type = str(obj, "type");
    const name = answerName(obj);
    switch (type) {
        case "predicate":
            return { type, name, probability: probability(obj, "probability") };
        case "choice":
            return {
                type,
                name,
                choice: choiceValue(obj, "choice"),
                confidence: probability(obj, "confidence"),
                probabilities: arr(obj, "probabilities").map((p) => ({
                    value: choiceValue(p, "value"),
                    probability: probability(p, "probability"),
                })),
            };
        case "score":
            return {
                type,
                name,
                score: num(obj, "score"),
                confidence: probability(obj, "confidence"),
                probabilities: arr(obj, "probabilities").map((p, i) => {
                    const sources = source?.probabilities as JsonObject[] | undefined;
                    const value = integer(p, "value", sources?.[i]);
                    return { label: str(p, "label"), value, probability: probability(p, "probability") };
                }),
            };
        case "refusal":
            return { type, name };
        default:
            return { type: "unknown", name, wireType: type, raw: obj };
    }
}

/** @internal Parses a wire response into a typed result. Throws `DecisionsError` (invalidResponse). */
export function parseDecisionsResult(json: unknown, numberSources?: unknown): DecisionsResult {
    try {
        if (!isObject(json)) throw new InvalidResponse("Expected a JSON object.");
        const usage = json.usage;
        if (!isObject(usage)) throw new InvalidResponse("Expected object 'usage'.");
        const inputDetails = details(usage, "input_tokens_details");
        const outputDetails = details(usage, "output_tokens_details");
        const source = numberSources as JsonObject | undefined;
        const usageSource = source?.usage as JsonObject | undefined;
        const answerSources = source?.answers as JsonObject[] | undefined;
        return {
            answers: arr(json, "answers").map((answer, i) => parseAnswer(answer, answerSources?.[i])),
            model: str(json, "model"),
            usage: {
                inputTokens: optionalInteger(usage, "input_tokens", usageSource),
                inputTokensDetails: {
                    cachedTokens: optionalInteger(inputDetails, "cached_tokens", usageSource?.input_tokens_details as JsonObject | undefined),
                    cacheWriteTokens: optionalInteger(inputDetails, "cache_write_tokens", usageSource?.input_tokens_details as JsonObject | undefined),
                },
                outputTokens: optionalInteger(usage, "output_tokens", usageSource),
                outputTokensDetails: {
                    reasoningTokens: optionalInteger(outputDetails, "reasoning_tokens", usageSource?.output_tokens_details as JsonObject | undefined),
                },
                totalTokens: optionalInteger(usage, "total_tokens", usageSource),
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

/** @internal Parses wire JSON without losing the original integer-token precision. */
export function parseDecisionsResponse(text: string): DecisionsResult {
    let json: unknown;
    let numberSources: unknown;
    try {
        const raw = text.replace(/^\ufeff/, "");
        json = JSON.parse(raw);
        // Node 20 does not support JSON.parse reviver source context. Keep numeric lexemes in
        // a parallel tree; strings are skipped intact and unknown payloads retain normal JSON.
        numberSources = JSON.parse(raw.replace(
            /"(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g,
            (token) => token.startsWith('"') ? token : JSON.stringify(token)
        ));
    } catch {
        throw new DecisionsError("invalidResponse", "The Decisions API returned a response that is not valid JSON.");
    }
    return parseDecisionsResult(json, numberSources);
}

// ============================================================================
// Internal: HTTP
// ============================================================================

class CallScope {
    private readonly controller = new AbortController();
    readonly signal = this.controller.signal;
    private readonly deadline: number | undefined;
    private timer?: ReturnType<typeof setTimeout>;
    private readonly callerAborted = () => this.controller.abort(this.caller?.reason);

    constructor(timeoutMs: number | null, private readonly caller?: AbortSignal) {
        caller?.throwIfAborted();
        this.deadline = timeoutMs === null ? undefined : performance.now() + timeoutMs;
        caller?.addEventListener("abort", this.callerAborted, { once: true });
        this.schedule();
    }

    private schedule(): void {
        if (this.deadline === undefined) return;
        const remaining = this.deadline - performance.now();
        if (remaining <= 0) {
            this.expire();
        } else {
            this.timer = setTimeout(() => this.schedule(), Math.ceil(Math.min(remaining, MAX_TIMER_MS)));
        }
    }

    private expire(): void {
        if (!this.signal.aborted) {
            this.controller.abort(new DecisionsError("timeout", "The Decisions call exceeded its SDK deadline."));
        }
    }

    check(): void {
        this.caller?.throwIfAborted();
        if (this.deadline !== undefined && performance.now() >= this.deadline) this.expire();
        this.signal.throwIfAborted();
    }

    private reason(): unknown {
        try {
            this.check();
        } catch (error) {
            return error;
        }
        return this.signal.reason;
    }

    wait<T>(start: () => Promise<T>, discard?: (value: T) => void): Promise<T> {
        this.check();
        return new Promise<T>((resolve, reject) => {
            let settled = false;
            const finish = (action: () => void) => {
                if (settled) return;
                settled = true;
                this.signal.removeEventListener("abort", onAbort);
                action();
            };
            const onAbort = () => finish(() => reject(this.reason()));
            this.signal.addEventListener("abort", onAbort, { once: true });
            let pending: Promise<T>;
            try {
                this.check();
                pending = start();
            } catch (error) {
                finish(() => reject(error));
                return;
            }
            // Both handlers stay attached to non-cancellable RPCs/transports after expiry.
            // A late response is closed; a late rejection is observed, never rethrown.
            void Promise.resolve(pending).then(
                (value) => {
                    if (settled) {
                        discard?.(value);
                        return;
                    }
                    finish(() => {
                        try {
                            this.check();
                            resolve(value);
                        } catch (error) {
                            discard?.(value);
                            reject(error);
                        }
                    });
                },
                (error: unknown) => finish(() => {
                    reject(this.signal.aborted ? this.reason() : error);
                })
            );
        });
    }

    dispose(): void {
        if (this.timer !== undefined) clearTimeout(this.timer);
        this.caller?.removeEventListener("abort", this.callerAborted);
    }
}

function closeResponse(response: Response | undefined): void {
    if (!response) return;
    try {
        void response.body?.cancel().catch(() => undefined);
    } catch {
        // Cleanup must not replace the call's result with a discarded transport exception.
    }
}

/** Native HTTP has no independent header/body deadline and never follows redirects. */
function fetchDecisionsHttp(url: string, init: RequestInit): Promise<Response> {
    return new Promise((resolve, reject) => {
        const target = new URL(url);
        const request = (target.protocol === "https:" ? httpsRequest : httpRequest)(
            target,
            {
                method: init.method,
                headers: init.headers as Record<string, string>,
                signal: init.signal ?? undefined,
                agent: false,
            },
            (message) => {
                try {
                    const headers = new Headers();
                    for (const [name, value] of Object.entries(message.headers)) {
                        if (value !== undefined) headers.set(name, Array.isArray(value) ? value.join(", ") : value);
                    }
                    const status = message.statusCode ?? 500;
                    const noBody = status === 204 || status === 205 || status === 304;
                    if (noBody) message.destroy();
                    let decoded: Readable = message;
                    const encodings = (headers.get("content-encoding") ?? "")
                        .split(",").map((value) => value.trim().toLowerCase()).filter(Boolean);
                    if (!noBody && encodings.length && encodings.every((value) =>
                        ["gzip", "x-gzip", "deflate", "br"].includes(value)
                    )) {
                        const decoders = encodings.reverse().map((value) =>
                            value === "br" ? createBrotliDecompress()
                                : value === "deflate" ? createInflate() : createGunzip()
                        );
                        decoded = decoders[decoders.length - 1]!;
                        // Pipeline propagates read failure/cancellation to every decoder and socket.
                        pipeline([message, ...decoders], () => undefined);
                    }
                    const body = noBody ? null : Readable.toWeb(decoded, {
                        strategy: { highWaterMark: 0, size: (chunk: Uint8Array) => chunk.byteLength },
                    }) as ReadableStream<Uint8Array>;
                    resolve(new Response(body, { status, headers }));
                } catch (error) {
                    message.destroy();
                    reject(error);
                }
            }
        );
        request.on("error", reject);
        try {
            request.end(init.body as string);
        } catch (error) {
            request.destroy();
            reject(error);
        }
    });
}

class BodyReadError extends Error {}

/**
 * Reads at most `limit` bytes. `exceeded` reports whether the stream held more data; the rest of
 * the stream is cancelled so the connection can be released.
 */
async function readLimited(
    response: Response,
    limit: number,
    scope?: CallScope
): Promise<{ bytes: Uint8Array; exceeded: boolean }> {
    if (!response.body) return { bytes: new Uint8Array(0), exceeded: false };
    const reader = response.body.getReader();
    const chunks: Uint8Array[] = [];
    let total = 0;
    let exceeded = false;
    try {
        for (;;) {
            const { done, value } = await (scope ? scope.wait(() => reader.read()) : reader.read());
            if (done) break;
            const remaining = limit - total;
            if (value.byteLength > remaining) {
                chunks.push(value.subarray(0, remaining));
                total = limit;
                exceeded = true;
                break;
            }
            chunks.push(value);
            total += value.byteLength;
        }
    } catch (error) {
        scope?.check();
        throw new BodyReadError("Failed to read the response body.", { cause: error });
    } finally {
        // cancel() settles pending reads immediately even if an injected cancel hook stalls.
        // Observe its completion without letting that hook extend the SDK deadline.
        void reader.cancel().catch(() => undefined);
        reader.releaseLock();
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
        bytes.set(chunk, offset);
        offset += chunk.byteLength;
    }
    return { bytes, exceeded };
}

const decodeUtf8 = (bytes: Uint8Array, partial = false): string =>
    new TextDecoder("utf-8", { fatal: false, ignoreBOM: true }).decode(bytes, { stream: partial });

function utf8Prefix(bytes: Uint8Array, limit: number): Uint8Array {
    if (bytes.byteLength <= limit) return bytes;
    let cut = limit;
    while (cut > 0 && (bytes[cut]! & 0xc0) === 0x80) cut--;
    return bytes.subarray(0, cut);
}

function capDiagnostic(text: string): string {
    return decodeUtf8(utf8Prefix(new TextEncoder().encode(text), MAX_ERROR_BODY_BYTES));
}

function boundedDiagnostic(text: string, secrets: readonly string[]): string {
    const bytes = new TextEncoder().encode(text);
    const raw = decodeUtf8(utf8Prefix(bytes, MAX_ERROR_BODY_BYTES + maxSecretBytes(secrets)));
    const retained = decodeUtf8(utf8Prefix(bytes, MAX_ERROR_BODY_BYTES));
    return capDiagnostic(redactSecrets(raw, secrets, retained.length));
}

/**
 * Reads an error body capped at {@link MAX_ERROR_BODY_BYTES}. A lookahead window as long as the
 * longest secret is read past the cap and redaction runs before truncation, so a credential that
 * straddles the cap is never partially retained.
 */
async function readRedactedErrorBody(
    response: Response,
    secrets: readonly string[],
    scope?: CallScope
): Promise<{ raw: string; body: string; truncated: boolean }> {
    const window = MAX_ERROR_BODY_BYTES + maxSecretBytes(secrets);
    const { bytes, exceeded } = await readLimited(response, window, scope);
    const raw = decodeUtf8(bytes, exceeded);
    const retained = decodeUtf8(
        utf8Prefix(bytes, MAX_ERROR_BODY_BYTES),
        exceeded && bytes.byteLength <= MAX_ERROR_BODY_BYTES
    );
    const redacted = redactSecrets(raw, secrets, retained.length);
    const truncated = exceeded || bytes.byteLength > MAX_ERROR_BODY_BYTES;
    return { raw, body: capDiagnostic(redacted), truncated };
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
    secrets: readonly string[],
    scope?: CallScope
): Promise<DecisionsError> {
    const { raw, body, truncated } = await readRedactedErrorBody(response, secrets, scope);
    let errorType: string | undefined;
    let errorCode: string | undefined;
    let errorParam: string | undefined;
    let errorMessage: string | undefined;
    if (!truncated) {
        try {
            const parsed: unknown = JSON.parse(raw.replace(/^\ufeff/, ""));
            if (isObject(parsed) && isObject(parsed.error)) {
                const redact = (value: unknown) => {
                    const s = stringField(value);
                    return s === undefined ? undefined : boundedDiagnostic(s, secrets);
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
        capDiagnostic(`Decisions request failed with ${statusText}${errorMessage ? `: ${errorMessage}` : "."}`),
        {
            status,
            errorType,
            errorCode,
            errorParam,
            requestId: response.headers.has("x-request-id")
                ? boundedDiagnostic(response.headers.get("x-request-id")!, secrets)
                : undefined,
            body,
            bodyTruncated: truncated,
            retryAfterMs: parseRetryAfterMs(response.headers),
        }
    );
}

function decimalDelay(value: string, scale: number): number | undefined {
    if (!/^\d+(?:\.\d+)?$/.test(value)) return undefined;
    const [whole, fraction = ""] = value.split(".");
    const digits = (whole! + fraction.slice(0, scale).padEnd(scale, "0")).replace(/^0+/, "") || "0";
    if (digits.length > 16) return undefined;
    const delay = BigInt(digits) + (/[1-9]/.test(fraction.slice(scale)) ? 1n : 0n);
    return delay <= 9007199254740991n ? Number(delay) : undefined;
}

/** @internal Deterministic advisory retry parser; never schedules a retry. */
export function parseRetryAfterMs(headers: Pick<Headers, "get">, nowEpochMs = Date.now()): number | undefined {
    const milliseconds = headers.get("retry-after-ms")?.replace(HTTP_WHITESPACE, "");
    if (milliseconds !== undefined) {
        const delay = decimalDelay(milliseconds, 0);
        if (delay !== undefined) return delay;
    }
    const retryAfter = headers.get("retry-after")?.replace(HTTP_WHITESPACE, "");
    if (retryAfter === undefined) return undefined;
    const seconds = decimalDelay(retryAfter, 3);
    if (seconds !== undefined) return seconds;
    if (!/^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \d{2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{4} \d{2}:\d{2}:\d{2} GMT$/.test(retryAfter)) {
        return undefined;
    }
    const date = Date.parse(retryAfter);
    if (!Number.isFinite(date) || new Date(date).toUTCString() !== retryAfter) return undefined;
    const delay = Math.max(0, Math.ceil(date - nowEpochMs));
    return Number.isSafeInteger(delay) ? delay : undefined;
}

/**
 * Builds a bounded stand-in retaining only the redacted name/message/code, not raw exception
 * objects, stacks, chains, URL/header attributes, or arbitrary structured details.
 */
function sanitizeCause(error: unknown, secrets: readonly string[]): Error {
    const source = error instanceof BodyReadError ? error.cause : error;
    try {
        const message = isObject(source) && typeof source.message === "string"
            ? source.message : typeof source === "string" ? source : "Transport operation failed.";
        const sanitized = new Error(boundedDiagnostic(message, secrets));
        sanitized.name = boundedDiagnostic(
            isObject(source) && typeof source.name === "string" ? source.name : "Error",
            secrets
        );
        const code = isObject(source) ? source.code : undefined;
        if (typeof code === "string") {
            (sanitized as Error & { code?: string }).code = boundedDiagnostic(code, secrets);
        }
        delete sanitized.stack;
        return sanitized;
    } catch {
        return new Error("Transport operation failed.");
    }
}

function networkError(message: string, error: unknown, secrets: readonly string[]): DecisionsError {
    return new DecisionsError("network", message, { cause: sanitizeCause(error, secrets) });
}

function endpointUnavailable(cause: unknown): DecisionsError {
    let gated = false;
    try {
        gated = cause instanceof Error && typeof cause.message === "string" && cause.message.includes(GATE_ENV);
    } catch {
        // Even reading an arbitrary RPC exception must not expose it.
    }
    const message = gated
        ? `session.decisions requires the runtime's provider endpoint API. Set ${GATE_ENV}=true in the runtime's environment (for in-process runtimes, in the host process environment before the runtime is loaded).`
        : "Could not resolve the session's provider endpoint for the Decisions API.";
    return new DecisionsError("endpointUnavailable", message, {
        cause: new Error("Provider endpoint lookup failed."),
    });
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
        const timeoutMs = options.timeoutMs === undefined ? DEFAULT_TIMEOUT_MS : options.timeoutMs;
        if (timeoutMs !== null && (typeof timeoutMs !== "number" || !Number.isFinite(timeoutMs) || timeoutMs <= 0)) {
            throw new TypeError("Decisions timeoutMs must be null or a finite positive number.");
        }
        const scope = new CallScope(timeoutMs, options.signal);
        const fetchImpl = options.fetch ?? fetchDecisionsHttp;
        let response: Response | undefined;
        try {
            let plan = planDecisionsAttempt(await this.resolve(options, scope), request.model);
            const body = JSON.stringify(serializeDecisionsRequest(request));
            response = await this.send(fetchImpl, plan, body, scope);
            if (response.status === 401 || response.status === 403) {
                const original = await this.httpError(response, plan, scope);
                closeResponse(response);
                response = undefined;
                const refreshedEndpoint = await this.resolve(options, scope);
                let refreshed: DecisionsAttemptPlan;
                try {
                    refreshed = planDecisionsAttempt(refreshedEndpoint, request.model);
                } catch {
                    scope.check();
                    throw original;
                }
                if (refreshed.fingerprint === plan.fingerprint) throw original;
                refreshed.secrets = [...new Set([...plan.secrets, ...refreshed.secrets])];
                plan = refreshed;
                response = await this.send(fetchImpl, plan, body, scope);
            }

            if (response.status < 200 || response.status >= 300) {
                throw await this.httpError(response, plan, scope);
            }
            let bytes: Uint8Array;
            let exceeded: boolean;
            try {
                ({ bytes, exceeded } = await readLimited(response, MAX_RESPONSE_BYTES, scope));
            } catch (error) {
                scope.check();
                throw networkError("The Decisions response could not be read.", error, plan.secrets);
            }
            scope.check();
            if (exceeded) {
                throw new DecisionsError("invalidResponse", `The Decisions API response exceeded ${MAX_RESPONSE_BYTES} bytes.`);
            }
            const result = parseDecisionsResponse(decodeUtf8(bytes));
            scope.check();
            return result;
        } catch (error) {
            scope.check();
            throw error;
        } finally {
            closeResponse(response);
            scope.dispose();
        }
    }

    private async httpError(
        response: Response,
        plan: DecisionsAttemptPlan,
        scope: CallScope
    ): Promise<DecisionsError> {
        try {
            const error = await decisionsHttpError(response, plan.secrets, scope);
            scope.check();
            return error;
        } catch (error) {
            scope.check();
            throw networkError("The Decisions error response could not be read.", error, plan.secrets);
        }
    }

    private async resolve(options: DecisionsCallOptions, scope: CallScope): Promise<ProviderEndpoint> {
        try {
            return await scope.wait(() => this.resolveEndpoint(
                options.endpointModelId === undefined
                    ? undefined
                    : { modelId: options.endpointModelId }
            ));
        } catch (error) {
            scope.check();
            throw endpointUnavailable(error);
        }
    }

    private async send(
        fetchImpl: (url: string, init: RequestInit) => Promise<Response>,
        plan: DecisionsAttemptPlan,
        body: string,
        scope: CallScope
    ): Promise<Response> {
        try {
            return await scope.wait(() => fetchImpl(plan.url, {
                method: "POST",
                headers: plan.headers,
                body,
                redirect: "manual",
                signal: scope.signal,
            }), closeResponse);
        } catch (error) {
            scope.check();
            throw networkError("The Decisions request could not be sent.", error, plan.secrets);
        }
    }
}
