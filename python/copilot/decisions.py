"""**Experimental.** Support for OpenAI's Decisions API (``POST /v1/decisions``).

Calls the Decisions API directly against the provider endpoint and credentials the
session is configured with. Obtain the API via :attr:`CopilotSession.decisions`.

This API is experimental and may change or be removed in a future release.
See ``docs/features/decisions.md``.
"""

from __future__ import annotations

import asyncio
import json
import math
import re
from collections.abc import Awaitable, Callable, Mapping, Sequence
from dataclasses import dataclass
from enum import Enum
from typing import Any, Literal, NotRequired, TypedDict, cast
from urllib.parse import parse_qsl, quote, urlsplit

import httpx

from .generated.rpc import (
    ProviderEndpoint,
    ProviderTransport,
    ProviderType,
    SessionProviderGetEndpointRequest,
)

__all__ = [
    "DecisionsAnswer",
    "DecisionsAssistantMessage",
    "DecisionsCacheBreakpoint",
    "DecisionsChoiceAnswer",
    "DecisionsChoiceOption",
    "DecisionsChoiceProbability",
    "DecisionsChoiceQuestion",
    "DecisionsCustomToolCall",
    "DecisionsCustomToolCallOutput",
    "DecisionsDeveloperMessage",
    "DecisionsError",
    "DecisionsErrorKind",
    "DecisionsFunctionCall",
    "DecisionsFunctionCallOutput",
    "DecisionsInputImage",
    "DecisionsInputItem",
    "DecisionsInputJson",
    "DecisionsInputText",
    "DecisionsInputTokensDetails",
    "DecisionsInstructions",
    "DecisionsItemStatus",
    "DecisionsOutputText",
    "DecisionsOutputTokensDetails",
    "DecisionsPredicateAnswer",
    "DecisionsPredicateQuestion",
    "DecisionsPromptCacheOptions",
    "DecisionsQuestion",
    "DecisionsQuestionImage",
    "DecisionsQuestionPart",
    "DecisionsQuestionText",
    "DecisionsRawInputItem",
    "DecisionsRefusalAnswer",
    "DecisionsRefusalContent",
    "DecisionsRequest",
    "DecisionsResult",
    "DecisionsScoreAnswer",
    "DecisionsScoreLevel",
    "DecisionsScoreProbability",
    "DecisionsScoreQuestion",
    "DecisionsTransportErrorCause",
    "DecisionsUnknownAnswer",
    "DecisionsUsage",
    "DecisionsUserMessage",
    "SessionDecisionsApi",
]

# ============================================================================
# Request types
# ============================================================================

DecisionsImageDetail = Literal["low", "high", "auto", "original"]

DecisionsItemStatus = Literal["in_progress", "completed", "incomplete"]
"""**Experimental.** Status of a conversation item."""


class DecisionsCacheBreakpoint(TypedDict):
    """**Experimental.** Marks the end of an explicit prompt-cache segment."""

    mode: Literal["explicit"]


class DecisionsInputText(TypedDict):
    """**Experimental.** Text content part."""

    type: Literal["input_text"]
    text: str
    prompt_cache_breakpoint: NotRequired[DecisionsCacheBreakpoint | None]


class DecisionsInputImage(TypedDict):
    """**Experimental.** Image content part. ``image_url`` is a data URL or an http(s) URL."""

    type: Literal["input_image"]
    image_url: str
    detail: NotRequired[DecisionsImageDetail | None]
    prompt_cache_breakpoint: NotRequired[DecisionsCacheBreakpoint | None]


class DecisionsInputJson(TypedDict):
    """**Experimental.** Structured JSON content part."""

    type: Literal["input_json"]
    data: dict[str, Any] | list[dict[str, Any]]


class DecisionsOutputText(TypedDict):
    """**Experimental.** Assistant text content part."""

    type: Literal["output_text"]
    text: str


class DecisionsRefusalContent(TypedDict):
    """**Experimental.** Assistant refusal content part."""

    type: Literal["refusal"]
    refusal: str


class DecisionsUserMessage(TypedDict):
    """**Experimental.** A user message."""

    type: NotRequired[Literal["message"]]
    role: Literal["user"]
    content: str | list[DecisionsInputText | DecisionsInputImage | DecisionsInputJson]


class DecisionsDeveloperMessage(TypedDict):
    """**Experimental.** A developer or system message."""

    type: NotRequired[Literal["message"]]
    role: Literal["developer", "system"]
    content: str | list[DecisionsInputText]


class DecisionsAssistantMessage(TypedDict):
    """**Experimental.** An assistant message."""

    type: NotRequired[Literal["message"]]
    role: Literal["assistant"]
    content: str | list[DecisionsOutputText | DecisionsRefusalContent]
    phase: NotRequired[Literal["commentary", "final_answer"] | None]


class DecisionsFunctionCall(TypedDict):
    """**Experimental.** A function tool call made by the assistant."""

    type: Literal["function_call"]
    call_id: str
    name: str
    arguments: str
    id: NotRequired[str | None]
    namespace: NotRequired[str | None]
    status: NotRequired[DecisionsItemStatus | None]


class DecisionsFunctionCallOutput(TypedDict):
    """**Experimental.** The output of a function tool call."""

    type: Literal["function_call_output"]
    call_id: str
    output: str | list[DecisionsInputText | DecisionsInputImage]
    id: NotRequired[str | None]
    status: NotRequired[DecisionsItemStatus | None]


class DecisionsCustomToolCall(TypedDict):
    """**Experimental.** A custom tool call made by the assistant."""

    type: Literal["custom_tool_call"]
    call_id: str
    name: str
    input: str
    id: NotRequired[str | None]
    namespace: NotRequired[str | None]
    status: NotRequired[DecisionsItemStatus | None]


class DecisionsCustomToolCallOutput(TypedDict):
    """**Experimental.** The output of a custom tool call."""

    type: Literal["custom_tool_call_output"]
    call_id: str
    output: str | list[DecisionsInputText | DecisionsInputImage]
    id: NotRequired[str | None]
    status: NotRequired[DecisionsItemStatus | None]


class DecisionsRawInputItem(TypedDict):
    """**Experimental.** An input item sent verbatim (already in wire format).

    Use for item types this SDK version does not model yet.
    """

    raw: dict[str, Any]


DecisionsInputItem = (
    DecisionsUserMessage
    | DecisionsDeveloperMessage
    | DecisionsAssistantMessage
    | DecisionsFunctionCall
    | DecisionsFunctionCallOutput
    | DecisionsCustomToolCall
    | DecisionsCustomToolCallOutput
    | DecisionsRawInputItem
)
"""**Experimental.** An item of a Decisions request ``input`` list."""


class DecisionsQuestionText(TypedDict):
    """**Experimental.** Text part of question instructions (no cache breakpoint)."""

    type: Literal["input_text"]
    text: str


class DecisionsQuestionImage(TypedDict):
    """**Experimental.** Image part of question instructions (no cache breakpoint)."""

    type: Literal["input_image"]
    image_url: str
    detail: NotRequired[DecisionsImageDetail | None]


DecisionsQuestionPart = DecisionsQuestionText | DecisionsQuestionImage | DecisionsInputJson
"""**Experimental.** Content part allowed in question instructions."""

DecisionsInstructions = str | list[DecisionsQuestionPart]
"""**Experimental.** Question instructions: a string or a list of content parts."""


class DecisionsPredicateQuestion(TypedDict):
    """**Experimental.** A yes/no question answered with a probability."""

    type: Literal["predicate"]
    instructions: DecisionsInstructions
    name: NotRequired[str | None]


class DecisionsChoiceOption(TypedDict):
    """**Experimental.** One option of a choice question (string or boolean value)."""

    value: str | bool
    description: NotRequired[str | None]


class DecisionsChoiceQuestion(TypedDict):
    """**Experimental.** A question answered by picking one of ``choices``."""

    type: Literal["choice"]
    instructions: DecisionsInstructions
    choices: list[DecisionsChoiceOption]
    name: NotRequired[str | None]


class DecisionsScoreLevel(TypedDict):
    """**Experimental.** One level of a score question."""

    label: str
    description: NotRequired[str | None]


class DecisionsScoreQuestion(TypedDict):
    """**Experimental.** A question answered with a score over ordered ``levels``."""

    type: Literal["score"]
    instructions: DecisionsInstructions
    levels: list[DecisionsScoreLevel]
    name: NotRequired[str | None]


DecisionsQuestion = DecisionsPredicateQuestion | DecisionsChoiceQuestion | DecisionsScoreQuestion
"""**Experimental.** A Decisions question."""


class DecisionsPromptCacheOptions(TypedDict, total=False):
    """**Experimental.** Prompt-cache options."""

    mode: Literal["implicit", "explicit"] | None
    ttl: Literal["30m"] | None


class DecisionsRequest(TypedDict):
    """**Experimental.** A Decisions API request.

    Optional fields set to ``None`` are omitted from the wire request.
    """

    model: str
    """Model to send on the wire (for Azure, the deployment name). Required."""
    input: str | list[DecisionsInputItem]
    questions: list[DecisionsQuestion]
    """1 to 400 questions (not validated client-side)."""
    prompt_cache_key: NotRequired[str | None]
    prompt_cache_options: NotRequired[DecisionsPromptCacheOptions | None]
    safety_identifier: NotRequired[str | None]
    """Opaque, stable identifier for the end user, used for abuse detection. Never send an
    email address, username, or credential; hash or map it first."""


# ============================================================================
# Response types
# ============================================================================


@dataclass(frozen=True)
class DecisionsPredicateAnswer:
    """**Experimental.** Answer to a predicate question."""

    name: str | None
    probability: float
    type: Literal["predicate"] = "predicate"


@dataclass(frozen=True)
class DecisionsChoiceProbability:
    """**Experimental.** Probability of one choice value (string vs. boolean preserved)."""

    value: str | bool
    probability: float


@dataclass(frozen=True)
class DecisionsChoiceAnswer:
    """**Experimental.** Answer to a choice question."""

    name: str | None
    choice: str | bool
    confidence: float
    probabilities: list[DecisionsChoiceProbability]
    type: Literal["choice"] = "choice"


@dataclass(frozen=True)
class DecisionsScoreProbability:
    """**Experimental.** Probability of one score level."""

    label: str
    value: int
    """Integer index of the level."""
    probability: float


@dataclass(frozen=True)
class DecisionsScoreAnswer:
    """**Experimental.** Answer to a score question."""

    name: str | None
    score: float
    """Probability-weighted level index (fractional)."""
    confidence: float
    probabilities: list[DecisionsScoreProbability]
    type: Literal["score"] = "score"


@dataclass(frozen=True)
class DecisionsRefusalAnswer:
    """**Experimental.** The model refused to answer the question."""

    name: str | None
    type: Literal["refusal"] = "refusal"


@dataclass(frozen=True)
class DecisionsUnknownAnswer:
    """**Experimental.** An answer whose ``type`` this SDK version does not recognize.

    ``wire_type`` is the original ``type`` and ``raw`` is the original JSON object.
    """

    name: str | None
    wire_type: str
    raw: dict[str, Any]
    type: Literal["unknown"] = "unknown"


DecisionsAnswer = (
    DecisionsPredicateAnswer
    | DecisionsChoiceAnswer
    | DecisionsScoreAnswer
    | DecisionsRefusalAnswer
    | DecisionsUnknownAnswer
)
"""**Experimental.** A Decisions answer."""


@dataclass(frozen=True)
class DecisionsInputTokensDetails:
    """**Experimental.** Input token breakdown."""

    cached_tokens: int
    cache_write_tokens: int


@dataclass(frozen=True)
class DecisionsOutputTokensDetails:
    """**Experimental.** Output token breakdown."""

    reasoning_tokens: int


@dataclass(frozen=True)
class DecisionsUsage:
    """**Experimental.** Token usage of a Decisions call."""

    input_tokens: int
    input_tokens_details: DecisionsInputTokensDetails
    output_tokens: int
    output_tokens_details: DecisionsOutputTokensDetails
    total_tokens: int


@dataclass(frozen=True)
class DecisionsResult:
    """**Experimental.** Result of a Decisions call."""

    answers: list[DecisionsAnswer]
    """One answer per question, in question order."""
    model: str
    usage: DecisionsUsage


# ============================================================================
# Errors
# ============================================================================

DecisionsErrorKind = Literal[
    "endpointUnavailable",
    "invalidEndpoint",
    "unsupportedProvider",
    "unsupportedTransport",
    "modelMismatch",
    "credentialConflict",
    "http",
    "invalidResponse",
    "network",
]
"""**Experimental.** Category of a :class:`DecisionsError`."""


class DecisionsError(Exception):
    """**Experimental.** Error raised by ``session.decisions.create``.

    Messages and ``body`` never contain credentials. Cancellation is reported with
    :class:`asyncio.CancelledError` (or :class:`TimeoutError` for ``timeout``), never
    with this type.

    Attributes:
        kind: Category of the error.
        status: HTTP status for ``http`` errors.
        error_type: OpenAI error ``type``, when the error body was parsable.
        error_code: OpenAI error ``code``, when the error body was parsable.
        error_param: OpenAI error ``param``, when the error body was parsable.
        request_id: Value of the ``x-request-id`` response header.
        body: Response body (at most 64 KiB, credentials redacted).
        body_truncated: Whether ``body`` was truncated at 64 KiB.
    """

    def __init__(
        self,
        kind: DecisionsErrorKind,
        message: str,
        *,
        status: int | None = None,
        error_type: str | None = None,
        error_code: str | None = None,
        error_param: str | None = None,
        request_id: str | None = None,
        body: str | None = None,
        body_truncated: bool | None = None,
    ) -> None:
        super().__init__(message)
        self.kind: DecisionsErrorKind = kind
        self.message = message
        self.status = status
        self.error_type = error_type
        self.error_code = error_code
        self.error_param = error_param
        self.request_id = request_id
        self.body = body
        self.body_truncated = body_truncated


class DecisionsTransportErrorCause(Exception):
    """**Experimental.** Credential-free stand-in for a transport error.

    Set as ``__cause__`` of ``network`` :class:`DecisionsError` instances. Only the
    original exception type name (``name``), a string ``code`` attribute, and the
    redacted message are retained, so request URLs and headers cannot leak.
    """

    def __init__(self, name: str, message: str, code: str | None = None) -> None:
        super().__init__(message)
        self.name = name
        self.message = message
        self.code = code


# ============================================================================
# Internal: endpoint -> request plan
# ============================================================================

_GATE_ENV = "COPILOT_ALLOW_GET_PROVIDER_ENDPOINT"
MAX_ERROR_BODY_BYTES = 64 * 1024
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
_MIN_SECRET_LENGTH = 8
_HEADER_NAME = re.compile(r"[!#$%&'*+\-.^_`|~0-9A-Za-z]+")
_INVALID_HEADER_VALUE = re.compile(r"[\r\n\0]")
_HTTP_WHITESPACE = " \t"
_BEARER_PREFIX = re.compile(r"^[ \t]*bearer[ \t]+", re.IGNORECASE)
_DEFAULT_PORTS = {"http": 80, "https": 443}


@dataclass(frozen=True)
class _AttemptPlan:
    url: str
    headers: dict[str, str]
    """Lowercase header names."""
    fingerprint: tuple[Any, ...]
    secrets: list[str]


def _invalid_endpoint(message: str) -> DecisionsError:
    return DecisionsError("invalidEndpoint", message)


def _enum_value(value: Any) -> Any:
    return value.value if isinstance(value, Enum) else value


def _build_decisions_url(endpoint: ProviderEndpoint) -> str:
    """Builds the Decisions URL from the endpoint's base URL."""
    base_url = endpoint.base_url
    invalid = "The provider endpoint base URL is not a valid absolute URL."
    if not isinstance(base_url, str):
        raise _invalid_endpoint(invalid)
    try:
        parts = urlsplit(
            base_url.strip(
                "\x00\x01\x02\x03\x04\x05\x06\x07\x08\t\n\x0b\x0c\r"
                "\x0e\x0f\x10\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1a"
                "\x1b\x1c\x1d\x1e\x1f "
            )
        )
        hostname = parts.hostname
        port = parts.port
    except ValueError:
        raise _invalid_endpoint(invalid) from None
    scheme = parts.scheme.lower()
    if not scheme:
        raise _invalid_endpoint(invalid)
    if scheme not in _DEFAULT_PORTS:
        raise _invalid_endpoint("The provider endpoint base URL must use http or https.")
    if not hostname:
        raise _invalid_endpoint(invalid)
    if "@" in parts.netloc or parts.username is not None or parts.password is not None:
        raise _invalid_endpoint("The provider endpoint base URL must not contain credentials.")
    host = f"[{hostname}]" if ":" in hostname else hostname
    if port is not None and port != _DEFAULT_PORTS[scheme]:
        host = f"{host}:{port}"
    base_path = parts.path.rstrip("/")
    is_azure = _enum_value(endpoint.type) == ProviderType.AZURE.value
    suffix = (
        "/openai/v1/decisions"
        if is_azure and not base_path.lower().endswith("/openai/v1")
        else "/decisions"
    )
    search = f"?{parts.query}" if parts.query else ""
    return f"{scheme}://{host}{base_path}{suffix}{search}"


def _check_header(name: Any, value: Any) -> str:
    if (
        not isinstance(name, str)
        or not isinstance(value, str)
        or not _HEADER_NAME.fullmatch(name)
        or _INVALID_HEADER_VALUE.search(value)
    ):
        raise _invalid_endpoint("The provider endpoint returned an invalid HTTP header.")
    return value.strip(_HTTP_WHITESPACE)


def _build_decisions_headers(endpoint: ProviderEndpoint) -> dict[str, str]:
    """Merges endpoint headers and credentials into the outbound header set."""
    merged: dict[str, str] = {}
    for name, raw_value in (endpoint.headers or {}).items():
        if raw_value is None:
            continue
        value = _check_header(name, raw_value)
        lower = name.lower()
        if lower in ("content-type", "accept"):
            continue
        existing = merged.get(lower)
        if existing is not None and existing != value:
            raise _invalid_endpoint(
                "The provider endpoint returned conflicting values for the same HTTP header."
            )
        merged[lower] = value

    api_key_header: str | None = None
    if endpoint.api_key and "authorization" not in merged and "api-key" not in merged:
        if _enum_value(endpoint.type) == ProviderType.AZURE.value:
            api_key_header = "api-key"
            merged[api_key_header] = _check_header(api_key_header, endpoint.api_key)
        else:
            api_key_header = "authorization"
            merged[api_key_header] = "Bearer " + _check_header(api_key_header, endpoint.api_key)

    token = endpoint.session_token
    if token is not None:
        value = _check_header(token.header, token.token)
        lower = token.header.lower()
        if lower == api_key_header:
            raise DecisionsError(
                "credentialConflict",
                "The provider session token uses the same header as the endpoint API key.",
            )
        merged[lower] = value

    merged["content-type"] = "application/json"
    merged["accept"] = "application/json"
    return merged


def _plan_attempt(endpoint: ProviderEndpoint, model: str) -> _AttemptPlan:
    """Validates the endpoint and builds the request plan (deterministic check order)."""
    provider_type = _enum_value(endpoint.type)
    if provider_type not in (ProviderType.OPENAI.value, ProviderType.AZURE.value):
        raise DecisionsError(
            "unsupportedProvider",
            f"The session's provider type '{provider_type}' does not support the Decisions API.",
        )
    transport = _enum_value(endpoint.transport)
    if transport is not None and transport != ProviderTransport.HTTP.value:
        raise DecisionsError(
            "unsupportedTransport",
            f"The session's provider transport '{transport}' is not supported by the "
            "Decisions API.",
        )
    token_model = endpoint.session_token.model if endpoint.session_token is not None else None
    if token_model is not None and token_model != model:
        raise DecisionsError(
            "modelMismatch",
            f"The provider session token is bound to model '{token_model}', but the request "
            f"uses '{model}'. Set endpoint_model_id or request model consistently.",
        )
    url = _build_decisions_url(endpoint)
    headers = _build_decisions_headers(endpoint)
    token_header = (
        endpoint.session_token.header.lower() if endpoint.session_token is not None else None
    )
    fingerprint = (
        url,
        headers.get("authorization"),
        headers.get("api-key"),
        None if token_header is None else (token_header, headers.get(token_header)),
    )
    return _AttemptPlan(url, headers, fingerprint, _collect_secrets(endpoint, url))


def _utf16_length(value: str) -> int:
    return len(value.encode("utf-16-le", "surrogatepass")) // 2


def _collect_secrets(endpoint: ProviderEndpoint, url: str | None = None) -> list[str]:
    """Credentials that must never surface in errors, expanded into wire encodings.

    Sources: the apiKey, every endpoint header value (and the value without a leading
    bearer scheme), the session token, and URL query values. Each is trimmed of SP/HTAB
    and kept only if at least 8 Unicode scalar values long. Sorted longest first.
    """
    raw: dict[str, None] = {}

    def add(value: Any) -> None:
        if not isinstance(value, str):
            return
        trimmed = value.strip(_HTTP_WHITESPACE)
        if len(trimmed) >= _MIN_SECRET_LENGTH:
            raw[trimmed] = None

    add(endpoint.api_key)
    add(endpoint.session_token.token if endpoint.session_token is not None else None)
    for value in (endpoint.headers or {}).values():
        add(value)
        if isinstance(value, str):
            add(_BEARER_PREFIX.sub("", value, count=1))
    if url:
        for _, value in parse_qsl(urlsplit(url).query, keep_blank_values=True):
            add(value)
    variants: dict[str, None] = {}
    for secret in raw:
        for variant in _secret_variants(secret):
            variants[variant] = None
    return sorted(variants, key=_utf16_length, reverse=True)


def _json_escape(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)[1:-1]


def _ascii_json_escape(escaped: str) -> str:
    out: list[str] = []
    for char in escaped:
        code = ord(char)
        if code < 0x7F:
            out.append(char)
        elif code > 0xFFFF:
            code -= 0x10000
            out.append(f"\\u{0xD800 + (code >> 10):04x}\\u{0xDC00 + (code & 0x3FF):04x}")
        else:
            out.append(f"\\u{code:04x}")
    return "".join(out)


def _secret_variants(secret: str) -> list[str]:
    """Wire encodings under which a credential may be echoed back in a response body."""
    json_escaped = _json_escape(secret)
    ascii_json_escaped = _ascii_json_escape(json_escaped)
    variants = [secret, json_escaped, ascii_json_escaped]
    for escaped in (json_escaped, ascii_json_escaped):
        variants.append(escaped.replace("/", "\\/"))
    try:
        uri_encoded = quote(secret, safe="-_.!~*'()")
    except UnicodeEncodeError:
        return variants
    variants.extend((uri_encoded, uri_encoded.replace("%20", "+")))
    return variants


def _max_secret_bytes(secrets: Sequence[str]) -> int:
    return max((len(s.encode("utf-8", "surrogatepass")) for s in secrets), default=0)


_HEX4 = re.compile(r"[0-9a-fA-F]{4}")
_HEX2 = re.compile(r"[0-9a-fA-F]{2}")


def _hex_class(char: str) -> str:
    return f"[{char.lower()}{char.upper()}]" if char.lower() in "abcdef" else char


def _variant_pattern(variant: str) -> str:
    """Regex for one secret variant; hex digits in ``\\uXXXX``/``%XX`` match any case."""
    out: list[str] = []
    i = 0
    while i < len(variant):
        if variant.startswith("\\u", i) and _HEX4.fullmatch(variant, i + 2, i + 6):
            out.append(r"\\u" + "".join(_hex_class(c) for c in variant[i + 2 : i + 6]))
            i += 6
        elif variant[i] == "%" and _HEX2.fullmatch(variant, i + 1, i + 3):
            out.append("%" + "".join(_hex_class(c) for c in variant[i + 1 : i + 3]))
            i += 3
        else:
            out.append(re.escape(variant[i]))
            i += 1
    return "".join(out)


def _redact_secrets(text: str, secrets: Sequence[str]) -> str:
    """Replaces every occurrence of any secret variant with ``[REDACTED]``, longest first."""
    result = text
    for secret in secrets:
        result = re.sub(_variant_pattern(secret), "[REDACTED]", result)
    return result


# ============================================================================
# Internal: wire serialization / parsing
# ============================================================================


def _set_if_present(target: dict[str, Any], key: str, value: Any) -> None:
    if value is not None:
        target[key] = value


def _serialize_part(part: Mapping[str, Any]) -> dict[str, Any]:
    part_type = part.get("type")
    if part_type == "input_text":
        out: dict[str, Any] = {"type": "input_text", "text": part["text"]}
        _set_if_present(out, "prompt_cache_breakpoint", part.get("prompt_cache_breakpoint"))
        return out
    if part_type == "input_image":
        out = {"type": "input_image", "image_url": part["image_url"]}
        _set_if_present(out, "detail", part.get("detail"))
        _set_if_present(out, "prompt_cache_breakpoint", part.get("prompt_cache_breakpoint"))
        return out
    if part_type == "input_json":
        return {"type": "input_json", "data": part["data"]}
    if part_type == "output_text":
        return {"type": "output_text", "text": part["text"]}
    if part_type == "refusal":
        return {"type": "refusal", "refusal": part["refusal"]}
    raise TypeError(f"Unsupported Decisions content part type '{part_type}'.")


def _serialize_content(content: Any) -> Any:
    if isinstance(content, str):
        return content
    return [_serialize_part(part) for part in content]


def _serialize_input_item(item: Mapping[str, Any]) -> dict[str, Any]:
    if "raw" in item:
        return item["raw"]
    item_type = item.get("type")
    if item_type is None or item_type == "message":
        out: dict[str, Any] = {}
        if item_type == "message":
            out["type"] = "message"
        out["role"] = item["role"]
        out["content"] = _serialize_content(item["content"])
        if item["role"] == "assistant":
            _set_if_present(out, "phase", item.get("phase"))
        return out
    if item_type in ("function_call", "custom_tool_call"):
        payload_key = "arguments" if item_type == "function_call" else "input"
        out = {
            "type": item_type,
            "call_id": item["call_id"],
            "name": item["name"],
            payload_key: item[payload_key],
        }
        _set_if_present(out, "id", item.get("id"))
        _set_if_present(out, "namespace", item.get("namespace"))
        _set_if_present(out, "status", item.get("status"))
        return out
    if item_type in ("function_call_output", "custom_tool_call_output"):
        out = {
            "type": item_type,
            "call_id": item["call_id"],
            "output": _serialize_content(item["output"]),
        }
        _set_if_present(out, "id", item.get("id"))
        _set_if_present(out, "status", item.get("status"))
        return out
    raise TypeError(f"Unsupported Decisions input item type '{item_type}'.")


def _serialize_question(question: Mapping[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {"type": question["type"]}
    _set_if_present(out, "name", question.get("name"))
    out["instructions"] = _serialize_content(question["instructions"])
    if question["type"] == "choice":
        choices = []
        for choice in question["choices"]:
            c: dict[str, Any] = {"value": choice["value"]}
            _set_if_present(c, "description", choice.get("description"))
            choices.append(c)
        out["choices"] = choices
    elif question["type"] == "score":
        levels = []
        for level in question["levels"]:
            entry: dict[str, Any] = {"label": level["label"]}
            _set_if_present(entry, "description", level.get("description"))
            levels.append(entry)
        out["levels"] = levels
    return out


def _require_model(request: Any) -> str:
    if not isinstance(request, Mapping):
        raise TypeError("DecisionsRequest must be a mapping.")
    model = request.get("model")
    if not isinstance(model, str):
        raise TypeError("DecisionsRequest model is required.")
    if model == "":
        raise ValueError("DecisionsRequest model is required.")
    return model


def _serialize_decisions_request(request: DecisionsRequest) -> dict[str, Any]:
    """Converts a typed request into its wire JSON object."""
    model = _require_model(request)
    req = cast(Mapping[str, Any], request)
    input_value = req["input"]
    out: dict[str, Any] = {
        "model": model,
        "input": input_value
        if isinstance(input_value, str)
        else [_serialize_input_item(item) for item in input_value],
        "questions": [_serialize_question(q) for q in req["questions"]],
    }
    _set_if_present(out, "prompt_cache_key", req.get("prompt_cache_key"))
    cache_options = req.get("prompt_cache_options")
    if cache_options is not None:
        options: dict[str, Any] = {}
        _set_if_present(options, "mode", cache_options.get("mode"))
        _set_if_present(options, "ttl", cache_options.get("ttl"))
        out["prompt_cache_options"] = options
    _set_if_present(out, "safety_identifier", req.get("safety_identifier"))
    return out


class _InvalidResponse(Exception):
    pass


def _reject_constant(value: str) -> Any:
    raise ValueError(f"Invalid JSON constant {value}")


def _loads_strict(text: str) -> tuple[bool, Any]:
    """Parses JSON like ``JSON.parse`` (NaN/Infinity rejected). Returns (ok, value)."""
    try:
        return True, json.loads(text, parse_constant=_reject_constant)
    except (ValueError, RecursionError):
        return False, None


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def _num(obj: Mapping[str, Any], key: str) -> float:
    value: Any = obj.get(key)
    if not _is_number(value):
        raise _InvalidResponse(f"Expected number '{key}'.")
    return float(value)


def _int_value(value: Any, message: str) -> int:
    if not _is_number(value) or (isinstance(value, float) and not value.is_integer()):
        raise _InvalidResponse(message)
    return int(value)


def _optional_tokens(obj: Mapping[str, Any] | None, key: str) -> int:
    if obj is None or obj.get(key) is None:
        return 0
    return _int_value(obj.get(key), f"Expected integer '{key}'.")


def _str(obj: Mapping[str, Any], key: str) -> str:
    value = obj.get(key)
    if not isinstance(value, str):
        raise _InvalidResponse(f"Expected string '{key}'.")
    return value


def _arr(obj: Mapping[str, Any], key: str) -> list[dict[str, Any]]:
    value = obj.get(key)
    if not isinstance(value, list) or not all(isinstance(v, dict) for v in value):
        raise _InvalidResponse(f"Expected array of objects '{key}'.")
    return value


def _choice_value(obj: Mapping[str, Any], key: str) -> str | bool:
    value = obj.get(key)
    if not isinstance(value, (str, bool)):
        raise _InvalidResponse(f"Expected string or boolean '{key}'.")
    return value


def _answer_name(obj: Mapping[str, Any]) -> str | None:
    value = obj.get("name")
    if value is None:
        return None
    if not isinstance(value, str):
        raise _InvalidResponse("Expected string 'name'.")
    return value


def _parse_answer(obj: dict[str, Any]) -> DecisionsAnswer:
    answer_type = _str(obj, "type")
    name = _answer_name(obj)
    if answer_type == "predicate":
        return DecisionsPredicateAnswer(name=name, probability=_num(obj, "probability"))
    if answer_type == "choice":
        return DecisionsChoiceAnswer(
            name=name,
            choice=_choice_value(obj, "choice"),
            confidence=_num(obj, "confidence"),
            probabilities=[
                DecisionsChoiceProbability(
                    value=_choice_value(p, "value"), probability=_num(p, "probability")
                )
                for p in _arr(obj, "probabilities")
            ],
        )
    if answer_type == "score":
        return DecisionsScoreAnswer(
            name=name,
            score=_num(obj, "score"),
            confidence=_num(obj, "confidence"),
            probabilities=[
                DecisionsScoreProbability(
                    label=_str(p, "label"),
                    value=_int_value(p.get("value"), "Expected integer level 'value'."),
                    probability=_num(p, "probability"),
                )
                for p in _arr(obj, "probabilities")
            ],
        )
    if answer_type == "refusal":
        return DecisionsRefusalAnswer(name=name)
    return DecisionsUnknownAnswer(name=name, wire_type=answer_type, raw=obj)


def _parse_decisions_result(value: Any) -> DecisionsResult:
    """Parses a wire response into a typed result. Raises ``DecisionsError`` (invalidResponse)."""
    failure: DecisionsError | None = None
    try:
        if not isinstance(value, dict):
            raise _InvalidResponse("Expected a JSON object.")
        usage = value.get("usage")
        if not isinstance(usage, dict):
            raise _InvalidResponse("Expected object 'usage'.")
        input_details = usage.get("input_tokens_details")
        output_details = usage.get("output_tokens_details")
        input_details = input_details if isinstance(input_details, dict) else None
        output_details = output_details if isinstance(output_details, dict) else None
        answers = [_parse_answer(a) for a in _arr(value, "answers")]
        return DecisionsResult(
            answers=answers,
            model=_str(value, "model"),
            usage=DecisionsUsage(
                input_tokens=_optional_tokens(usage, "input_tokens"),
                input_tokens_details=DecisionsInputTokensDetails(
                    cached_tokens=_optional_tokens(input_details, "cached_tokens"),
                    cache_write_tokens=_optional_tokens(input_details, "cache_write_tokens"),
                ),
                output_tokens=_optional_tokens(usage, "output_tokens"),
                output_tokens_details=DecisionsOutputTokensDetails(
                    reasoning_tokens=_optional_tokens(output_details, "reasoning_tokens"),
                ),
                total_tokens=_optional_tokens(usage, "total_tokens"),
            ),
        )
    except _InvalidResponse as error:
        failure = DecisionsError(
            "invalidResponse", f"The Decisions API returned an unexpected response: {error}"
        )
    raise failure


# ============================================================================
# Internal: HTTP
# ============================================================================


class _BodyReadError(Exception):
    def __init__(self, error: Exception) -> None:
        super().__init__("Failed to read the response body.")
        self.error = error


async def _read_limited(response: httpx.Response, limit: int) -> tuple[bytes, bool]:
    """Reads at most ``limit`` bytes; reports whether the stream held more data."""
    chunks: list[bytes] = []
    total = 0
    exceeded = False
    try:
        async for chunk in response.aiter_bytes():
            remaining = limit - total
            if len(chunk) > remaining:
                chunks.append(chunk[:remaining])
                total = limit
                exceeded = True
                break
            chunks.append(chunk)
            total += len(chunk)
    except Exception as error:
        raise _BodyReadError(error) from None
    return b"".join(chunks), exceeded


def _decode_utf8(data: bytes) -> str:
    return data.decode("utf-8", errors="replace")


async def _read_redacted_error_body(
    response: httpx.Response, secrets: Sequence[str]
) -> tuple[str, str, bool]:
    """Reads an error body capped at 64 KiB, redacting before truncation.

    A lookahead window as long as the longest secret is read past the cap so a credential
    that straddles the cap is never partially retained.
    """
    window = MAX_ERROR_BODY_BYTES + _max_secret_bytes(secrets)
    data, exceeded = await _read_limited(response, window)
    raw = _decode_utf8(data)
    redacted = _redact_secrets(raw, secrets)
    truncated = exceeded or len(data) > MAX_ERROR_BODY_BYTES
    # Always cap after redaction: replacing short secrets with "[REDACTED]" can grow the
    # text past the cap even when the raw body fit. Drop a code point split by the cut.
    body = redacted.encode("utf-8", "surrogatepass")[:MAX_ERROR_BODY_BYTES]
    return raw, body.decode("utf-8", errors="ignore"), truncated


def _string_field(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return str(int(value)) if value.is_integer() and abs(value) < 1e21 else repr(value)
    return None


async def _decisions_http_error(response: httpx.Response, secrets: Sequence[str]) -> DecisionsError:
    """Maps a non-2xx response to a ``DecisionsError`` (bounded, redacted)."""
    raw, body, truncated = await _read_redacted_error_body(response, secrets)
    error_type = error_code = error_param = error_message = None
    if not truncated:
        ok, parsed = _loads_strict(raw)
        if ok and isinstance(parsed, dict) and isinstance(parsed.get("error"), dict):
            error = parsed["error"]

            def redact(value: Any) -> str | None:
                text = _string_field(value)
                return None if text is None else _redact_secrets(text, secrets)

            error_type = redact(error.get("type"))
            error_code = redact(error.get("code"))
            error_param = redact(error.get("param"))
            error_message = redact(error.get("message"))
    status = response.status_code
    status_text = (
        f"HTTP {status} (redirects are not followed)" if 300 <= status < 400 else f"HTTP {status}"
    )
    suffix = f": {error_message}" if error_message else "."
    return DecisionsError(
        "http",
        f"Decisions request failed with {status_text}{suffix}",
        status=status,
        error_type=error_type,
        error_code=error_code,
        error_param=error_param,
        request_id=response.headers.get("x-request-id"),
        body=body,
        body_truncated=truncated,
    )


def _sanitize_cause(error: BaseException, secrets: Sequence[str]) -> DecisionsTransportErrorCause:
    source: BaseException = error.error if isinstance(error, _BodyReadError) else error
    code = getattr(source, "code", None)
    return DecisionsTransportErrorCause(
        type(source).__name__,
        _redact_secrets(str(source), secrets),
        code if isinstance(code, str) else None,
    )


def _network_error(message: str, error: BaseException, secrets: Sequence[str]) -> DecisionsError:
    result = DecisionsError("network", message)
    # Setting __cause__ also sets __suppress_context__; callers raise outside the
    # except block so the original transport error is never chained.
    result.__cause__ = _sanitize_cause(error, secrets)
    return result


def _endpoint_unavailable(cause: BaseException) -> DecisionsError:
    if _GATE_ENV in str(cause):
        message = (
            "session.decisions requires the runtime's provider endpoint API. Set "
            f"{_GATE_ENV}=true in the runtime's environment (for in-process runtimes, in the "
            "host process environment before the runtime is loaded)."
        )
    else:
        message = "Could not resolve the session's provider endpoint for the Decisions API."
    return DecisionsError("endpointUnavailable", message)


async def _close_quietly(response: httpx.Response) -> None:
    try:
        await response.aclose()
    except Exception:
        pass


DecisionsEndpointResolver = Callable[
    [SessionProviderGetEndpointRequest | None], Awaitable[ProviderEndpoint]
]


class SessionDecisionsApi:
    """**Experimental.** The ``session.decisions`` API group.

    Calls OpenAI's Decisions API against the session's provider. Obtained via
    :attr:`CopilotSession.decisions`. This API is experimental and may change or be
    removed in a future release.
    """

    def __init__(self, resolve_endpoint: DecisionsEndpointResolver) -> None:
        self._resolve_endpoint = resolve_endpoint

    async def create(
        self,
        request: DecisionsRequest,
        *,
        endpoint_model_id: str | None = None,
        timeout: float | None = None,
        http_client: httpx.AsyncClient | None = None,
    ) -> DecisionsResult:
        """**Experimental.** Calls the Decisions API (``POST {base_url}/decisions``).

        Requires the runtime to run with ``COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true``.
        The provider endpoint is resolved via ``session.provider.getEndpoint`` on every
        call. On HTTP 401/403 the endpoint is resolved once more and the request is
        replayed once (and re-billed) only if the credentials or URL changed.

        Args:
            request: The Decisions request. ``model`` is the wire model (for Azure, the
                deployment name).
            endpoint_model_id: Passed verbatim as ``modelId`` to ``getEndpoint``. Omit to
                use the session's current endpoint. Never derived from ``request["model"]``.
            timeout: Overall deadline in seconds for the whole call. On expiry
                :class:`TimeoutError` is raised.
            http_client: Custom :class:`httpx.AsyncClient` (proxying, testing). It is
                trusted, never closed by the SDK, and requests are sent with
                ``follow_redirects=False``.

        Returns:
            The typed result.

        Raises:
            DecisionsError: On endpoint, protocol, HTTP, or network failures.
            TypeError: If ``request`` has no ``model``.
            ValueError: If ``request["model"]`` is empty.
            asyncio.CancelledError: If the call is cancelled (never wrapped).
            TimeoutError: If ``timeout`` elapses.
        """
        model = _require_model(request)
        async with asyncio.timeout(timeout):
            if http_client is not None:
                return await self._create(request, model, endpoint_model_id, http_client)
            async with httpx.AsyncClient(timeout=None, follow_redirects=False) as client:
                return await self._create(request, model, endpoint_model_id, client)

    async def _create(
        self,
        request: DecisionsRequest,
        model: str,
        endpoint_model_id: str | None,
        client: httpx.AsyncClient,
    ) -> DecisionsResult:
        params = (
            None
            if endpoint_model_id is None
            else SessionProviderGetEndpointRequest(model_id=endpoint_model_id)
        )
        plan = _plan_attempt(await self._resolve(params), model)
        body = json.dumps(
            _serialize_decisions_request(request),
            ensure_ascii=False,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
        response = await self._send(client, plan, body)

        if response.status_code in (401, 403):
            original = await self._http_error(response, plan)
            refreshed_endpoint = await self._resolve(params)
            refreshed: _AttemptPlan | None
            try:
                refreshed = _plan_attempt(refreshed_endpoint, model)
            except Exception:
                refreshed = None
            if refreshed is None or refreshed.fingerprint == plan.fingerprint:
                raise original
            plan = refreshed
            response = await self._send(client, plan, body)

        if not 200 <= response.status_code < 300:
            raise await self._http_error(response, plan)

        failure: DecisionsError | None = None
        try:
            data, exceeded = await _read_limited(response, MAX_RESPONSE_BYTES)
        except _BodyReadError as error:
            failure = _network_error(
                "The Decisions response could not be read.", error, plan.secrets
            )
        finally:
            await _close_quietly(response)
        if failure is not None:
            raise failure
        if exceeded:
            raise DecisionsError(
                "invalidResponse",
                f"The Decisions API response exceeded {MAX_RESPONSE_BYTES} bytes.",
            )
        ok, value = _loads_strict(_decode_utf8(data))
        if not ok:
            raise DecisionsError(
                "invalidResponse",
                "The Decisions API returned a response that is not valid JSON.",
            )
        return _parse_decisions_result(value)

    async def _http_error(self, response: httpx.Response, plan: _AttemptPlan) -> DecisionsError:
        failure: DecisionsError | None = None
        try:
            return await _decisions_http_error(response, plan.secrets)
        except _BodyReadError as error:
            failure = _network_error(
                "The Decisions error response could not be read.", error, plan.secrets
            )
        finally:
            await _close_quietly(response)
        raise failure

    async def _resolve(self, params: SessionProviderGetEndpointRequest | None) -> ProviderEndpoint:
        try:
            return await self._resolve_endpoint(params)
        except Exception as error:
            raise _endpoint_unavailable(error) from error

    async def _send(
        self, client: httpx.AsyncClient, plan: _AttemptPlan, body: bytes
    ) -> httpx.Response:
        failure: DecisionsError | None = None
        try:
            request = client.build_request(
                "POST",
                plan.url,
                headers=[
                    (name.encode("ascii"), value.encode("utf-8"))
                    for name, value in plan.headers.items()
                ],
                content=body,
            )
            return await client.send(request, stream=True, follow_redirects=False)
        except Exception as error:
            failure = _network_error(
                "The Decisions request could not be sent.", error, plan.secrets
            )
        raise failure
