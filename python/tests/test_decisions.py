"""Tests for the experimental ``session.decisions`` API.

Conformance vectors and golden fixtures are loaded from the repository-level
``test/fixtures/decisions`` directory shared by every SDK.
"""

from __future__ import annotations

import asyncio
import json
import threading
from collections.abc import AsyncIterator, Callable
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, cast

import httpx
import pytest

from copilot import (
    CopilotSession,
    DecisionsChoiceAnswer,
    DecisionsError,
    DecisionsPredicateAnswer,
    DecisionsRefusalAnswer,
    DecisionsRequest,
    DecisionsScoreAnswer,
    DecisionsTransportErrorCause,
    DecisionsUnknownAnswer,
    SessionDecisionsApi,
)
from copilot.decisions import (
    MAX_ERROR_BODY_BYTES,
    MAX_RESPONSE_BYTES,
    _build_decisions_headers,
    _build_decisions_url,
    _parse_decisions_result,
    _plan_attempt,
    _serialize_decisions_request,
)
from copilot.generated.rpc import ProviderEndpoint, SessionProviderGetEndpointRequest


def _find_fixture_dir() -> Path:
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "test" / "fixtures" / "decisions"
        if candidate.is_dir():
            return candidate
    raise RuntimeError("Could not locate test/fixtures/decisions.")


FIXTURE_DIR = _find_fixture_dir()


def fixture(name: str) -> Any:
    return json.loads((FIXTURE_DIR / name).read_text(encoding="utf-8"))


VECTORS = fixture("vectors.json")
OK_BODY = json.dumps(fixture("response.full.json"))


def _ids(group: str) -> list[str]:
    return [v["name"] for v in VECTORS[group]]


def endpoint(data: dict[str, Any]) -> ProviderEndpoint:
    return ProviderEndpoint.from_dict(data)


OPENAI_ENDPOINT: dict[str, Any] = {
    "type": "openai",
    "baseUrl": "https://api.openai.com/v1",
    "apiKey": "sk-test-0001",
    "headers": {},
}

MINIMAL_REQUEST: DecisionsRequest = {
    "model": "gpt-6-luna",
    "input": "The export button does nothing when clicked.",
    "questions": [
        {"type": "predicate", "instructions": "Is this ticket reporting a product defect?"}
    ],
}

FULL_REQUEST: DecisionsRequest = {
    "model": "gpt-6-luna",
    "input": [
        {"type": "message", "role": "developer", "content": "You triage incoming support tickets."},
        {
            "type": "message",
            "role": "system",
            "content": [{"type": "input_text", "text": "Answer only from the provided context."}],
        },
        {
            "type": "message",
            "role": "user",
            "content": [
                {
                    "type": "input_text",
                    "text": "Ticket #4821: the export button does nothing.",
                    "prompt_cache_breakpoint": {"mode": "explicit"},
                },
                {
                    "type": "input_image",
                    "image_url": "data:image/png;base64,iVBORw0KGgo=",
                    "detail": "high",
                    "prompt_cache_breakpoint": {"mode": "explicit"},
                },
                {"type": "input_json", "data": {"plan": "enterprise", "seats": 120}},
                {"type": "input_json", "data": [{"id": 1}, {"id": 2}]},
            ],
        },
        {
            "role": "user",
            "content": [
                {"type": "input_text", "text": "Customer replied: still broken after refresh."},
                {
                    "type": "input_image",
                    "image_url": "https://example.com/full.png",
                    "detail": "original",
                },
            ],
        },
        {
            "type": "message",
            "role": "assistant",
            "content": [
                {"type": "output_text", "text": "I will look up the account."},
                {"type": "refusal", "refusal": "I cannot share internal notes."},
            ],
            "phase": "commentary",
        },
        {
            "type": "function_call",
            "call_id": "call_1",
            "name": "lookup_account",
            "arguments": '{"ticket":4821}',
            "id": "fc_1",
            "namespace": "crm",
            "status": "completed",
        },
        {
            "type": "function_call_output",
            "call_id": "call_1",
            "output": '{"tier":"enterprise"}',
            "id": "fco_1",
            "status": "completed",
        },
        {
            "type": "function_call_output",
            "call_id": "call_2",
            "output": [
                {"type": "input_text", "text": "Screenshot attached."},
                {
                    "type": "input_image",
                    "image_url": "https://example.com/shot.png",
                    "detail": "low",
                },
            ],
        },
        {
            "type": "custom_tool_call",
            "call_id": "call_3",
            "name": "grep_logs",
            "input": "export failed",
            "id": "ctc_1",
            "namespace": "ops",
            "status": "incomplete",
        },
        {
            "type": "custom_tool_call_output",
            "call_id": "call_3",
            "output": "3 matches",
            "id": "ctco_1",
            "status": "in_progress",
        },
        {"raw": {"type": "future_item", "payload": {"anything": [1, True, None]}}},
    ],
    "questions": [
        {
            "type": "predicate",
            "name": "is_bug",
            "instructions": "Is this ticket reporting a product defect?",
        },
        {
            "type": "choice",
            "name": "team",
            "instructions": "Which team should own this ticket?",
            "choices": [
                {"value": "frontend", "description": "UI and client code"},
                {"value": "backend"},
            ],
        },
        {
            "type": "choice",
            "instructions": [
                {"type": "input_text", "text": "Should we escalate?"},
                {
                    "type": "input_image",
                    "image_url": "https://example.com/policy.png",
                    "detail": "auto",
                },
                {"type": "input_json", "data": {"sla_hours": 4}},
            ],
            "choices": [{"value": True, "description": "Escalate now"}, {"value": False}],
        },
        {
            "type": "score",
            "name": "severity",
            "instructions": "How severe is the impact?",
            "levels": [
                {"label": "low", "description": "Cosmetic"},
                {"label": "medium"},
                {"label": "high", "description": "Blocks work"},
            ],
        },
    ],
    "prompt_cache_key": "support-triage-v1",
    "prompt_cache_options": {"mode": "explicit", "ttl": "30m"},
    "safety_identifier": "user-7f3a9c",
}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

Responder = Callable[[httpx.Request], Any]


class FakeHttp:
    """An ``httpx.AsyncClient`` backed by ``MockTransport`` that records requests."""

    def __init__(self, responders: list[Responder]) -> None:
        self.calls: list[httpx.Request] = []
        self.bodies: list[bytes] = []
        self._responders = responders
        self._index = 0
        self.client = httpx.AsyncClient(transport=httpx.MockTransport(self._handle))

    async def _handle(self, request: httpx.Request) -> httpx.Response:
        self.calls.append(request)
        self.bodies.append(await request.aread())
        responder = self._responders[min(self._index, len(self._responders) - 1)]
        self._index += 1
        result = responder(request)
        if asyncio.iscoroutine(result):
            result = await result
        return result


def json_response(status: int, body: str, headers: dict[str, str] | None = None) -> Responder:
    def respond(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            status,
            content=body.encode("utf-8"),
            headers={"content-type": "application/json", **(headers or {})},
        )

    return respond


def text_response(status: int, body: str | bytes, headers: dict[str, str] | None = None):
    content = body.encode("utf-8") if isinstance(body, str) else body

    def respond(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, content=content, headers=headers or {})

    return respond


def decisions_with(
    endpoints: list[dict[str, Any]], seen: list[Any] | None = None
) -> SessionDecisionsApi:
    converted = [endpoint(e) for e in endpoints]
    index = 0

    async def resolve(params: SessionProviderGetEndpointRequest | None) -> ProviderEndpoint:
        nonlocal index
        if seen is not None:
            seen.append(params)
        result = converted[min(index, len(converted) - 1)]
        index += 1
        return result

    return SessionDecisionsApi(resolve)


async def capture_error(awaitable: Any) -> BaseException:
    try:
        await awaitable
    except BaseException as error:  # noqa: BLE001 - tests inspect the raised type
        return error
    raise AssertionError("Expected the call to raise.")


class RecordingStream(httpx.AsyncByteStream):
    def __init__(self, chunks: Callable[[], AsyncIterator[bytes]], events: list[str]) -> None:
        self._chunks = chunks
        self.events = events
        self.closed = False

    async def __aiter__(self) -> AsyncIterator[bytes]:
        async for chunk in self._chunks():
            yield chunk

    async def aclose(self) -> None:
        self.closed = True
        self.events.append("close")


def _chain(error: BaseException) -> list[BaseException]:
    seen: list[BaseException] = []
    current: BaseException | None = error
    while current is not None and current not in seen:
        seen.append(current)
        current = current.__cause__ or current.__context__
    return seen


# ---------------------------------------------------------------------------
# Serialization
# ---------------------------------------------------------------------------


class TestSerialization:
    def test_serializes_full_typed_request_to_golden_fixture(self):
        assert _serialize_decisions_request(FULL_REQUEST) == fixture("request.full.json")

    def test_serializes_minimal_request(self):
        assert _serialize_decisions_request(MINIMAL_REQUEST) == fixture("request.minimal.json")

    def test_wire_json_matches_fixture_after_round_trip(self):
        wire = json.dumps(_serialize_decisions_request(FULL_REQUEST), ensure_ascii=False)
        assert json.loads(wire) == fixture("request.full.json")

    def test_preserves_bool_vs_string_choice_values(self):
        serialized = _serialize_decisions_request(FULL_REQUEST)
        values = [c["value"] for c in serialized["questions"][2]["choices"]]
        assert values == [True, False]
        assert all(type(v) is bool for v in values)
        assert serialized["questions"][1]["choices"][0]["value"] == "frontend"

    def test_normalizes_explicit_nulls_to_omission(self):
        with_nulls = cast(
            DecisionsRequest,
            {
                "model": "gpt-6-luna",
                "input": [
                    {
                        "type": "message",
                        "role": "user",
                        "content": [
                            {
                                "type": "input_image",
                                "image_url": "https://example.com/a.png",
                                "detail": None,
                                "prompt_cache_breakpoint": None,
                            }
                        ],
                    },
                    {"type": "message", "role": "assistant", "content": "ok", "phase": None},
                    {
                        "type": "function_call",
                        "call_id": "c",
                        "name": "n",
                        "arguments": "{}",
                        "id": None,
                        "namespace": None,
                        "status": None,
                    },
                ],
                "questions": [
                    {
                        "type": "choice",
                        "name": None,
                        "instructions": "q",
                        "choices": [{"value": "a", "description": None}],
                    },
                    {
                        "type": "score",
                        "instructions": "s",
                        "levels": [{"label": "x", "description": None}],
                    },
                ],
                "prompt_cache_key": None,
                "prompt_cache_options": None,
                "safety_identifier": None,
            },
        )
        assert _serialize_decisions_request(with_nulls) == {
            "model": "gpt-6-luna",
            "input": [
                {
                    "type": "message",
                    "role": "user",
                    "content": [{"type": "input_image", "image_url": "https://example.com/a.png"}],
                },
                {"type": "message", "role": "assistant", "content": "ok"},
                {"type": "function_call", "call_id": "c", "name": "n", "arguments": "{}"},
            ],
            "questions": [
                {"type": "choice", "instructions": "q", "choices": [{"value": "a"}]},
                {"type": "score", "instructions": "s", "levels": [{"label": "x"}]},
            ],
        }

    def test_requires_model(self):
        with pytest.raises(ValueError, match="model is required"):
            _serialize_decisions_request({**MINIMAL_REQUEST, "model": ""})
        with pytest.raises(TypeError, match="model is required"):
            _serialize_decisions_request(cast(DecisionsRequest, {**MINIMAL_REQUEST, "model": None}))

    def test_rejects_unknown_input_item_type(self):
        request = cast(
            DecisionsRequest,
            {**MINIMAL_REQUEST, "input": [{"type": "future_item", "x": 1}]},
        )
        with pytest.raises(TypeError, match="future_item"):
            _serialize_decisions_request(request)


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------


class TestParsing:
    def test_parses_full_response_fixture(self):
        result = _parse_decisions_result(fixture("response.full.json"))
        assert result.model == "gpt-6-luna-2026-10-01"
        usage = result.usage
        assert usage.input_tokens == 812
        assert usage.input_tokens_details.cached_tokens == 512
        assert usage.input_tokens_details.cache_write_tokens == 128
        assert usage.output_tokens == 24
        assert usage.output_tokens_details.reasoning_tokens == 16
        assert usage.total_tokens == 836

        predicate, choice, bool_choice, score, refusal, unknown = result.answers
        assert predicate == DecisionsPredicateAnswer(name="is_bug", probability=0.93)
        assert isinstance(choice, DecisionsChoiceAnswer)
        assert choice.name == "team"
        assert choice.choice == "frontend"
        assert isinstance(bool_choice, DecisionsChoiceAnswer)
        assert bool_choice.name is None
        assert bool_choice.choice is True
        assert [p.value for p in bool_choice.probabilities] == [True, False]
        assert all(type(p.value) is bool for p in bool_choice.probabilities)
        assert isinstance(score, DecisionsScoreAnswer)
        assert score.name == "severity"
        assert score.score == 1.37
        assert [p.value for p in score.probabilities] == [0, 1, 2]
        assert all(type(p.value) is int for p in score.probabilities)
        assert refusal == DecisionsRefusalAnswer(name="pii_check")
        assert unknown == DecisionsUnknownAnswer(
            name="future_kind",
            wire_type="ranking",
            raw={"type": "ranking", "name": "future_kind", "ranked": ["a", "b"]},
        )
        assert unknown.type == "unknown"

    def test_exhaustive_answer_narrowing(self):
        def describe(answer: Any) -> str:
            if answer.type == "predicate":
                return f"p{answer.probability}"
            if answer.type == "choice":
                return f"c{str(answer.choice).lower()}"
            if answer.type == "score":
                return f"s{answer.score}"
            if answer.type == "refusal":
                return "r"
            assert answer.type == "unknown"
            return f"u{answer.wire_type}"

        result = _parse_decisions_result(fixture("response.full.json"))
        assert [describe(a) for a in result.answers] == [
            "p0.93",
            "cfrontend",
            "ctrue",
            "s1.37",
            "r",
            "uranking",
        ]

    @pytest.mark.parametrize(
        "value",
        [
            None,
            [],
            {"answers": [], "model": "m"},
            {"answers": [{"type": "predicate"}], "model": "m", "usage": {}},
            {
                "answers": [{"type": "choice", "choice": 3, "confidence": 1, "probabilities": []}],
                "model": "m",
                "usage": {},
            },
            {
                "answers": [
                    {
                        "type": "score",
                        "score": 1,
                        "confidence": 1,
                        "probabilities": [{"label": "a", "value": 0.5, "probability": 1}],
                    }
                ],
                "model": "m",
                "usage": {},
            },
            {
                "answers": [{"type": "predicate", "probability": True}],
                "model": "m",
                "usage": {},
            },
        ],
    )
    def test_rejects_malformed_responses(self, value: Any):
        with pytest.raises(DecisionsError) as info:
            _parse_decisions_result(value)
        assert info.value.kind == "invalidResponse"

    def test_missing_usage_counts_default_to_zero(self):
        result = _parse_decisions_result({"answers": [], "model": "m", "usage": {}})
        assert result.usage.total_tokens == 0
        assert result.usage.input_tokens_details.cached_tokens == 0


# ---------------------------------------------------------------------------
# Conformance vectors
# ---------------------------------------------------------------------------


class TestVectors:
    @pytest.mark.parametrize("vector", VECTORS["url"], ids=_ids("url"))
    def test_url(self, vector: dict[str, Any]):
        ep = endpoint(vector["endpoint"])
        if vector.get("error"):
            with pytest.raises(DecisionsError) as info:
                _build_decisions_url(ep)
            assert info.value.kind == vector["error"]
            assert "secretpassword" not in str(info.value)
        else:
            assert _build_decisions_url(ep) == vector["expected"]

    @pytest.mark.parametrize("vector", VECTORS["headers"], ids=_ids("headers"))
    def test_headers(self, vector: dict[str, Any]):
        ep = endpoint(vector["endpoint"])
        if vector.get("error"):
            with pytest.raises(DecisionsError) as info:
                _plan_attempt(ep, "gpt-6-luna")
            assert info.value.kind == vector["error"]
        else:
            assert _plan_attempt(ep, "gpt-6-luna").headers == vector["expected"]
            assert _build_decisions_headers(ep) == vector["expected"]

    @pytest.mark.parametrize("vector", VECTORS["preflight"], ids=_ids("preflight"))
    def test_preflight(self, vector: dict[str, Any]):
        ep = endpoint(vector["endpoint"])
        if vector.get("error"):
            with pytest.raises(DecisionsError) as info:
                _plan_attempt(ep, vector["model"])
            assert info.value.kind == vector["error"]
        else:
            _plan_attempt(ep, vector["model"])

    @pytest.mark.parametrize("vector", VECTORS["retry"], ids=_ids("retry"))
    async def test_retry(self, vector: dict[str, Any]):
        http = FakeHttp(
            [
                json_response(401, '{"error":{"type":"auth","code":"expired"}}'),
                json_response(200, OK_BODY),
            ]
        )
        seen: list[Any] = []
        api = decisions_with([vector["first"], vector["second"]], seen)
        if vector["retry"]:
            result = await api.create(MINIMAL_REQUEST, http_client=http.client)
            assert result.model == "gpt-6-luna-2026-10-01"
            assert len(http.calls) == 2
            assert str(http.calls[1].url) == _build_decisions_url(endpoint(vector["second"]))
        else:
            error = await capture_error(api.create(MINIMAL_REQUEST, http_client=http.client))
            assert isinstance(error, DecisionsError)
            assert error.kind == "http"
            assert error.status == 401
            assert error.error_code == "expired"
            assert len(http.calls) == 1
        assert len(seen) == 2

    async def test_retries_at_most_once(self):
        http = FakeHttp([json_response(403, "{}")])
        api = decisions_with(
            [
                OPENAI_ENDPOINT,
                {**OPENAI_ENDPOINT, "apiKey": "sk-test-0002"},
                {**OPENAI_ENDPOINT, "apiKey": "sk-test-0003"},
            ]
        )
        error = await capture_error(api.create(MINIMAL_REQUEST, http_client=http.client))
        assert isinstance(error, DecisionsError)
        assert error.kind == "http"
        assert error.status == 403
        assert len(http.calls) == 2

    @pytest.mark.parametrize("vector", VECTORS["redactionLength"], ids=_ids("redactionLength"))
    async def test_redaction_length(self, vector: dict[str, Any]):
        trimmed = vector["value"].strip()
        http = FakeHttp([text_response(400, f"echo:{trimmed}:end")])
        api = decisions_with([{**OPENAI_ENDPOINT, "apiKey": vector["value"]}])
        error = await capture_error(api.create(MINIMAL_REQUEST, http_client=http.client))
        assert isinstance(error, DecisionsError)
        expected = "echo:[REDACTED]:end" if vector["isSecret"] else f"echo:{trimmed}:end"
        assert error.body == expected

    @pytest.mark.parametrize("vector", VECTORS["redaction"], ids=_ids("redaction"))
    async def test_redaction(self, vector: dict[str, Any]):
        prefix = vector.get("bodyPrefix")
        payload = (str(prefix["char"]) * prefix["count"] if prefix else "") + vector["body"]
        http = FakeHttp([text_response(vector["status"], payload, {"x-request-id": "req_123"})])
        error = await capture_error(
            decisions_with([vector["endpoint"]]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "http"
        assert error.status == vector["status"]
        assert error.request_id == "req_123"
        assert error.error_code == vector["errorCode"]
        assert error.error_type == vector["errorType"]
        if "bodyTruncated" in vector:
            assert error.body_truncated is vector["bodyTruncated"]
        if "maxBodyBytes" in vector:
            assert len((error.body or "").encode("utf-8")) <= vector["maxBodyBytes"]
        surfaces = "\n".join(
            [
                error.message,
                error.body or "",
                error.error_code or "",
                error.error_type or "",
                error.error_param or "",
                str(error),
                repr(error),
                json.dumps(vars(error), default=str),
            ]
        )
        for secret in vector["mustNotContain"]:
            assert secret not in surfaces
        if not error.body_truncated:
            assert "[REDACTED]" in (error.body or "")


# ---------------------------------------------------------------------------
# session.decisions.create
# ---------------------------------------------------------------------------


class TestCreate:
    async def test_posts_serialized_request_with_planned_headers(self):
        http = FakeHttp([json_response(200, OK_BODY)])
        seen: list[Any] = []
        result = await decisions_with([OPENAI_ENDPOINT], seen).create(
            FULL_REQUEST, http_client=http.client
        )
        assert len(result.answers) == 6
        assert seen == [None]
        assert len(http.calls) == 1
        call = http.calls[0]
        assert str(call.url) == "https://api.openai.com/v1/decisions"
        assert call.method == "POST"
        assert call.headers["authorization"] == "Bearer sk-test-0001"
        assert call.headers["content-type"] == "application/json"
        assert call.headers["accept"] == "application/json"
        assert json.loads(http.bodies[0]) == fixture("request.full.json")

    async def test_passes_endpoint_model_id_verbatim(self):
        http = FakeHttp([json_response(200, OK_BODY)])
        seen: list[Any] = []
        await decisions_with([OPENAI_ENDPOINT], seen).create(
            MINIMAL_REQUEST, endpoint_model_id="endpoint-model", http_client=http.client
        )
        assert len(seen) == 1
        assert isinstance(seen[0], SessionProviderGetEndpointRequest)
        assert seen[0].model_id == "endpoint-model"

    async def test_calls_get_endpoint_on_every_create(self):
        http = FakeHttp([json_response(200, OK_BODY)])
        seen: list[Any] = []
        api = decisions_with([OPENAI_ENDPOINT], seen)
        await api.create(MINIMAL_REQUEST, http_client=http.client)
        await api.create(MINIMAL_REQUEST, http_client=http.client)
        assert len(seen) == 2

    async def test_maps_openai_error_envelope(self):
        http = FakeHttp(
            [json_response(400, json.dumps(fixture("error.json")), {"x-request-id": "req_9"})]
        )
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "http"
        assert error.status == 400
        assert error.error_type == "invalid_request_error"
        assert error.error_code == "unsupported_model"
        assert error.error_param == "model"
        assert error.request_id == "req_9"
        assert error.body_truncated is False
        assert "does not support the Decisions API" in str(error)

    async def test_bounds_large_error_bodies(self):
        http = FakeHttp([text_response(500, "x" * (MAX_ERROR_BODY_BYTES + 1000))])
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "http"
        assert error.body_truncated is True
        assert error.body is not None
        assert len(error.body) == MAX_ERROR_BODY_BYTES

    async def test_reports_non_json_success_body_as_invalid_response(self):
        http = FakeHttp([text_response(200, "<html>")])
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "invalidResponse"

    async def test_wraps_transport_failures_with_sanitized_cause(self):
        class FetchFailed(Exception):
            def __init__(self) -> None:
                super().__init__(
                    "fetch failed for https://api.openai.com/v1/decisions with Bearer sk-test-0001"
                )
                self.code = "ECONNRESET"
                self.headers = {"authorization": "Bearer sk-test-0001"}

        original: list[BaseException] = []

        def fail(_request: httpx.Request) -> httpx.Response:
            error = FetchFailed()
            original.append(error)
            raise error

        http = FakeHttp([fail])
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "network"
        cause = error.__cause__
        assert isinstance(cause, DecisionsTransportErrorCause)
        assert cause.name == "FetchFailed"
        assert cause.code == "ECONNRESET"
        assert not hasattr(cause, "headers")
        assert "sk-test-0001" not in str(cause)
        assert "[REDACTED]" in str(cause)
        assert error.__suppress_context__ is True
        assert original and original[0] not in _chain(error)

    async def test_propagates_endpoint_unavailable_when_refresh_fails(self):
        calls = 0

        async def resolve(_params: Any) -> ProviderEndpoint:
            nonlocal calls
            calls += 1
            if calls == 1:
                return endpoint(OPENAI_ENDPOINT)
            raise RuntimeError("runtime went away")

        http = FakeHttp([json_response(401, "{}")])
        error = await capture_error(
            SessionDecisionsApi(resolve).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "endpointUnavailable"
        assert len(http.calls) == 1

    async def test_cancellation_during_refresh_propagates(self):
        calls = 0
        started = asyncio.Event()

        async def resolve(_params: Any) -> ProviderEndpoint:
            nonlocal calls
            calls += 1
            if calls == 2:
                started.set()
                await asyncio.Event().wait()
            return endpoint(OPENAI_ENDPOINT)

        http = FakeHttp([json_response(401, "{}")])
        task = asyncio.ensure_future(
            SessionDecisionsApi(resolve).create(MINIMAL_REQUEST, http_client=http.client)
        )
        await started.wait()
        task.cancel()
        error = await capture_error(task)
        assert isinstance(error, asyncio.CancelledError)
        assert not isinstance(error, DecisionsError)

    async def test_validates_endpoint_before_serializing(self):
        malformed = cast(DecisionsRequest, {"model": "gpt-6-luna", "input": "x"})
        error = await capture_error(
            decisions_with(
                [{"type": "anthropic", "baseUrl": "https://api.anthropic.com", "headers": {}}]
            ).create(malformed)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "unsupportedProvider"

    async def test_rejects_missing_model_before_get_endpoint(self):
        seen: list[Any] = []
        api = decisions_with([OPENAI_ENDPOINT], seen)
        missing = cast(DecisionsRequest, {**MINIMAL_REQUEST, "model": None})
        with pytest.raises(TypeError):
            await api.create(missing)
        with pytest.raises(ValueError):
            await api.create({**MINIMAL_REQUEST, "model": ""})
        assert seen == []

    async def test_drains_401_body_before_retrying(self):
        events: list[str] = []
        attempt = 0

        async def body() -> AsyncIterator[bytes]:
            events.append("read")
            yield b"{}"

        def respond(_request: httpx.Request) -> httpx.Response:
            nonlocal attempt
            events.append(f"fetch{attempt}")
            attempt += 1
            if attempt == 1:
                return httpx.Response(401, stream=RecordingStream(body, events))
            return httpx.Response(200, content=OK_BODY.encode())

        http = FakeHttp([respond])
        await decisions_with(
            [OPENAI_ENDPOINT, {**OPENAI_ENDPOINT, "apiKey": "sk-test-0002"}]
        ).create(MINIMAL_REQUEST, http_client=http.client)
        assert events.index("read") < events.index("fetch1")
        assert events.index("close") < events.index("fetch1")

    async def test_closes_oversized_error_bodies(self):
        events: list[str] = []
        pulled = 0

        async def endless() -> AsyncIterator[bytes]:
            nonlocal pulled
            while True:
                pulled += 1
                yield b"x" * (16 * 1024)

        stream = RecordingStream(endless, events)
        http = FakeHttp([lambda _r: httpx.Response(500, stream=stream)])
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.body_truncated is True
        assert stream.closed
        assert pulled < 10

    @pytest.mark.parametrize("status", [200, 500])
    async def test_mid_body_stream_failure_maps_to_network(self, status: int):
        async def failing() -> AsyncIterator[bytes]:
            yield b'{"answers":'
            raise ConnectionResetError("socket hang up")

        stream = RecordingStream(failing, [])
        http = FakeHttp([lambda _r: httpx.Response(status, stream=stream)])
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "network"
        cause = error.__cause__
        assert isinstance(cause, DecisionsTransportErrorCause)
        assert str(cause) == "socket hang up"
        assert cause.name == "ConnectionResetError"
        assert error.__suppress_context__ is True
        assert error.__context__ is None
        assert stream.closed

    async def test_caps_success_bodies(self):
        http = FakeHttp([text_response(200, b" " * (MAX_RESPONSE_BYTES + 1))])
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "invalidResponse"

    async def test_gate_hint_only_when_runtime_mentions_it(self):
        async def gated(_params: Any) -> ProviderEndpoint:
            raise RuntimeError(
                "Request session.provider.getEndpoint failed with message: Provider endpoint "
                "access is disabled. To enable this API, set "
                "COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true"
            )

        gated_error = await capture_error(SessionDecisionsApi(gated).create(MINIMAL_REQUEST))
        assert isinstance(gated_error, DecisionsError)
        assert gated_error.kind == "endpointUnavailable"
        assert "COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true" in str(gated_error)

        async def other(_params: Any) -> ProviderEndpoint:
            raise RuntimeError("Session not found")

        other_error = await capture_error(SessionDecisionsApi(other).create(MINIMAL_REQUEST))
        assert isinstance(other_error, DecisionsError)
        assert other_error.kind == "endpointUnavailable"
        assert "COPILOT_ALLOW_GET_PROVIDER_ENDPOINT" not in str(other_error)
        assert isinstance(other_error.__cause__, RuntimeError)

    async def test_rejects_unsupported_providers_before_sending(self):
        http = FakeHttp([json_response(200, OK_BODY)])
        error = await capture_error(
            decisions_with(
                [{"type": "anthropic", "baseUrl": "https://api.anthropic.com", "headers": {}}]
            ).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "unsupportedProvider"
        assert http.calls == []

    async def test_does_not_close_injected_client(self):
        http = FakeHttp([json_response(200, OK_BODY)])
        await decisions_with([OPENAI_ENDPOINT]).create(MINIMAL_REQUEST, http_client=http.client)
        assert not http.client.is_closed
        await http.client.aclose()


class TestCancellation:
    async def test_cancelled_before_start_does_not_call_get_endpoint(self):
        seen: list[Any] = []
        task = asyncio.ensure_future(
            decisions_with([OPENAI_ENDPOINT], seen).create(MINIMAL_REQUEST)
        )
        task.cancel()
        error = await capture_error(task)
        assert isinstance(error, asyncio.CancelledError)
        assert seen == []

    async def test_cancelled_during_get_endpoint_sends_nothing(self):
        started = asyncio.Event()

        async def resolve(_params: Any) -> ProviderEndpoint:
            started.set()
            await asyncio.Event().wait()
            return endpoint(OPENAI_ENDPOINT)

        http = FakeHttp([json_response(200, OK_BODY)])
        task = asyncio.ensure_future(
            SessionDecisionsApi(resolve).create(MINIMAL_REQUEST, http_client=http.client)
        )
        await started.wait()
        task.cancel()
        error = await capture_error(task)
        assert isinstance(error, asyncio.CancelledError)
        assert http.calls == []

    async def test_cancelled_during_http_propagates_cancelled_error(self):
        sent = asyncio.Event()

        async def hang(_request: httpx.Request) -> httpx.Response:
            sent.set()
            await asyncio.Event().wait()
            raise AssertionError("unreachable")

        http = FakeHttp([hang])
        task = asyncio.ensure_future(
            decisions_with([OPENAI_ENDPOINT]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        await sent.wait()
        task.cancel()
        error = await capture_error(task)
        assert isinstance(error, asyncio.CancelledError)
        assert not isinstance(error, DecisionsError)

    async def test_timeout_raises_timeout_error(self):
        async def hang(_request: httpx.Request) -> httpx.Response:
            await asyncio.Event().wait()
            raise AssertionError("unreachable")

        http = FakeHttp([hang])
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT]).create(
                MINIMAL_REQUEST, timeout=0.05, http_client=http.client
            )
        )
        assert isinstance(error, TimeoutError)
        assert not isinstance(error, DecisionsError)


# ---------------------------------------------------------------------------
# Real HTTP
# ---------------------------------------------------------------------------


class _Server:
    def __init__(self, handler: Callable[[str, dict[str, str], bytes], tuple]) -> None:
        self.hits: list[str] = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("content-length") or 0)
                body = self.rfile.read(length)
                outer.hits.append(self.path)
                headers = {k.lower(): v for k, v in self.headers.items()}
                status, response_headers, payload = handler(self.path, headers, body)
                data = payload.encode("utf-8")
                self.send_response(status)
                for name, value in response_headers.items():
                    self.send_header(name, value)
                self.send_header("content-length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            do_GET = do_POST  # noqa: N815

            def log_message(self, *args: Any) -> None:
                pass

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.httpd.server_address[1]}/v1"

    def close(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=5)


@pytest.fixture
def serve():
    servers: list[_Server] = []

    def start(handler: Callable[[str, dict[str, str], bytes], tuple]) -> _Server:
        server = _Server(handler)
        servers.append(server)
        return server

    yield start
    for server in servers:
        server.close()


class TestRealHttp:
    async def test_round_trips_over_real_http(self, serve):
        seen: dict[str, Any] = {}

        def handler(path: str, headers: dict[str, str], body: bytes) -> tuple:
            seen["headers"] = headers
            seen["body"] = body
            return 200, {"content-type": "application/json"}, OK_BODY

        server = serve(handler)
        result = await decisions_with(
            [
                {
                    "type": "openai",
                    "baseUrl": server.base_url,
                    "apiKey": "sk-test-0001",
                    "headers": {"X-Extra": "1"},
                }
            ]
        ).create(MINIMAL_REQUEST)
        assert isinstance(result.answers[0], DecisionsPredicateAnswer)
        assert seen["headers"]["authorization"] == "Bearer sk-test-0001"
        assert seen["headers"]["x-extra"] == "1"
        assert seen["headers"]["content-type"] == "application/json"
        assert json.loads(seen["body"]) == fixture("request.minimal.json")
        assert server.hits == ["/v1/decisions"]

    async def test_does_not_follow_redirects(self, serve):
        def handler(path: str, _headers: dict[str, str], _body: bytes) -> tuple:
            if path.endswith("/decisions"):
                return 307, {"location": "/elsewhere"}, ""
            return 200, {"content-type": "application/json"}, OK_BODY

        server = serve(handler)
        error = await capture_error(
            decisions_with(
                [
                    {
                        "type": "openai",
                        "baseUrl": server.base_url,
                        "apiKey": "sk-test-0001",
                        "headers": {},
                    }
                ]
            ).create(MINIMAL_REQUEST)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "http"
        assert error.status == 307
        assert "redirects are not followed" in str(error)
        assert server.hits == ["/v1/decisions"]

    async def test_injected_client_redirects_are_not_followed(self, serve):
        def handler(path: str, _headers: dict[str, str], _body: bytes) -> tuple:
            if path.endswith("/decisions"):
                return 307, {"location": "/elsewhere"}, ""
            return 200, {"content-type": "application/json"}, OK_BODY

        server = serve(handler)
        async with httpx.AsyncClient(follow_redirects=True) as client:
            error = await capture_error(
                decisions_with(
                    [{"type": "openai", "baseUrl": server.base_url, "headers": {}}]
                ).create(MINIMAL_REQUEST, http_client=client)
            )
        assert isinstance(error, DecisionsError)
        assert error.status == 307
        assert server.hits == ["/v1/decisions"]


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


class TestPublicApi:
    def test_exported_and_exposed_on_session(self):
        import copilot

        for name in ("DecisionsError", "SessionDecisionsApi", "DecisionsRequest"):
            assert name in copilot.__all__
        prop = CopilotSession.__dict__.get("decisions")
        assert isinstance(prop, property)
        assert prop.__doc__ and prop.__doc__.startswith("**Experimental.**")
        api = decisions_with([OPENAI_ENDPOINT])
        assert callable(api.create)

    def test_session_property_is_lazy_and_uses_provider_rpc(self):
        calls: list[Any] = []

        class FakeProvider:
            async def get_endpoint(self, params: Any) -> ProviderEndpoint:
                calls.append(params)
                return endpoint(OPENAI_ENDPOINT)

        class FakeRpc:
            provider = FakeProvider()

        session = object.__new__(CopilotSession)
        session._decisions = None  # type: ignore[attr-defined]
        session._rpc = FakeRpc()  # type: ignore[attr-defined]
        first = session.decisions
        assert first is session.decisions
        http = FakeHttp([json_response(200, OK_BODY)])
        asyncio.run(first.create(MINIMAL_REQUEST, http_client=http.client))
        assert calls == [None]
