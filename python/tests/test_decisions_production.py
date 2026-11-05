"""Regression tests for the frozen Decisions production contract."""

from __future__ import annotations

import asyncio
import inspect
import json
import time
from collections.abc import AsyncIterator
from typing import Any, cast
from unittest.mock import Mock
from urllib.parse import unquote_plus

import httpx
import pytest
from test_decisions import (
    MINIMAL_REQUEST,
    OK_BODY,
    OPENAI_ENDPOINT,
    FakeHttp,
    RecordingStream,
    _chain,
    capture_error,
    decisions_with,
    endpoint,
    fixture,
    json_response,
    text_response,
)

from copilot import DecisionsError, DecisionsRequest, SessionDecisionsApi
from copilot import decisions as decisions_module
from copilot._jsonrpc import JsonRpcClient
from copilot.decisions import (
    MAX_ERROR_BODY_BYTES,
    _build_decisions_url,
    _parse_decisions_result,
    _parse_retry_after_ms,
    _plan_attempt,
    _serialize_decisions_request,
)
from copilot.generated.rpc import ProviderApi

PRODUCTION_VECTORS = fixture("production-vectors.json")


def _ids(group: str) -> list[str]:
    return [vector["name"] for vector in PRODUCTION_VECTORS[group]]


def _number_body(vector: dict[str, Any]) -> str:
    response = fixture("response.full.json")
    segments = vector["target"].split(".")
    current = response
    for segment in segments[:-1]:
        current = current[int(segment)] if isinstance(current, list) else current[segment]
    current[segments[-1]] = "__synthetic_exact_json_number__"
    return json.dumps(response).replace('"__synthetic_exact_json_number__"', vector["jsonValue"], 1)


def _result_field(result: Any, path: str) -> Any:
    for segment in path.split("."):
        result = result[int(segment)] if isinstance(result, list) else getattr(result, segment)
    return result


class TestProductionVectors:
    @pytest.mark.parametrize("vector", PRODUCTION_VECTORS["metadata"], ids=_ids("metadata"))
    async def test_metadata(self, vector: dict[str, Any], record_vector):
        http = FakeHttp([text_response(vector["status"], vector["body"], vector["headers"])])
        error = await capture_error(
            decisions_with([{**OPENAI_ENDPOINT, "apiKey": vector["apiKey"]}]).create(
                MINIMAL_REQUEST, http_client=http.client
            )
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "http"
        assert error.status == vector["status"]
        assert error.request_id == vector["expectedRequestId"]
        if "expectedErrorType" in vector:
            assert error.error_type == vector["expectedErrorType"]
        if "expectedErrorCode" in vector:
            assert error.error_code == vector["expectedErrorCode"]
        record_vector("production-vectors.json", "metadata", vector["name"])

    @pytest.mark.parametrize("vector", PRODUCTION_VECTORS["retryAfter"], ids=_ids("retryAfter"))
    async def test_retry_after(self, vector: dict[str, Any], record_vector, monkeypatch):
        assert (
            _parse_retry_after_ms(vector["headers"], vector["nowEpochMs"]) == vector["expectedMs"]
        )
        monkeypatch.setattr(decisions_module, "_now_epoch_ms", lambda: vector["nowEpochMs"])
        http = FakeHttp([text_response(429, "{}", vector["headers"])])
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "http"
        assert error.status == 429
        assert error.retry_after_ms == vector["expectedMs"]
        assert len(http.calls) == 1
        record_vector("production-vectors.json", "retryAfter", vector["name"])

    @pytest.mark.parametrize(
        "vector", PRODUCTION_VECTORS["responseNumbers"], ids=_ids("responseNumbers")
    )
    async def test_response_numbers(self, vector: dict[str, Any], record_vector):
        http = FakeHttp([text_response(200, _number_body(vector))])
        api = decisions_with([OPENAI_ENDPOINT])
        expected_error = vector.get("sdkErrors", {}).get("python", vector.get("expectedError"))
        if expected_error:
            error = await capture_error(api.create(MINIMAL_REQUEST, http_client=http.client))
            assert isinstance(error, DecisionsError)
            assert error.kind == expected_error
        else:
            result = await api.create(MINIMAL_REQUEST, http_client=http.client)
            actual = _result_field(result, vector["target"])
            if "expectedInteger" in vector:
                assert type(actual) is int
                assert str(actual) == vector["expectedInteger"]
            else:
                assert type(actual) is float
                assert actual == vector["expectedNumber"]
        assert len(http.calls) == 1
        record_vector("production-vectors.json", "responseNumbers", vector["name"])

    @pytest.mark.parametrize(
        "vector", PRODUCTION_VECTORS["authBodyFailure"], ids=_ids("authBodyFailure")
    )
    async def test_unreadable_auth_body(self, vector: dict[str, Any], record_vector):
        async def unreadable() -> AsyncIterator[bytes]:
            raise ConnectionResetError("synthetic unreadable auth response")
            yield b""  # pragma: no cover

        stream = RecordingStream(unreadable, [])
        http = FakeHttp([lambda _request: httpx.Response(vector["status"], stream=stream)])
        seen: list[Any] = []
        error = await capture_error(
            decisions_with(
                [OPENAI_ENDPOINT, {**OPENAI_ENDPOINT, "apiKey": "synthetic-refreshed-key"}], seen
            ).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == vector["expectedError"]
        assert len(seen) == vector["expectedResolves"]
        assert len(http.calls) == vector["expectedSends"]
        assert stream.closed is vector["expectedBodyClosed"]
        record_vector("production-vectors.json", "authBodyFailure", vector["name"])

    @pytest.mark.parametrize("vector", PRODUCTION_VECTORS["deadline"], ids=_ids("deadline"))
    async def test_deadline(self, vector: dict[str, Any], record_vector):
        resolves = 0
        reached = asyncio.Event()
        cancelled = asyncio.Event()
        streams: list[RecordingStream] = []
        stage = vector["stage"]

        async def block():
            reached.set()
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()

        async def resolve(_params):
            nonlocal resolves
            resolves += 1
            if stage == "endpoint" or (stage == "refresh" and resolves == 2):
                await block()
            return endpoint(OPENAI_ENDPOINT)

        async def slow_body() -> AsyncIterator[bytes]:
            yield b"{"
            await block()

        async def respond(_request: httpx.Request) -> httpx.Response:
            if stage == "headers":
                await block()
            if stage in ("successBody", "errorBody"):
                stream = RecordingStream(slow_body, [])
                streams.append(stream)
                return httpx.Response(vector.get("status", 200), stream=stream)
            return httpx.Response(vector.get("status", 200), content=b"{}")

        http = FakeHttp([respond])
        error = await capture_error(
            SessionDecisionsApi(resolve).create(
                MINIMAL_REQUEST, timeout=vector["timeoutMs"] / 1000, http_client=http.client
            )
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == vector["expectedError"]
        assert error.__cause__ is None
        assert error.__context__ is None
        assert resolves == vector["expectedResolves"]
        assert len(http.calls) == vector["expectedSends"]
        assert reached.is_set()
        assert cancelled.is_set()
        assert all(stream.closed for stream in streams)
        record_vector("production-vectors.json", "deadline", vector["name"])


class TestProductionRegressions:
    async def test_short_actual_credential_redacts_request_id_and_body(self):
        http = FakeHttp([text_response(400, "echo:XYZ", {"x-request-id": "req:XYZ"})])
        error = await capture_error(
            decisions_with([{**OPENAI_ENDPOINT, "apiKey": "XYZ"}]).create(
                MINIMAL_REQUEST, http_client=http.client
            )
        )
        assert isinstance(error, DecisionsError)
        assert error.request_id == "req:[REDACTED]"
        assert error.body == "echo:[REDACTED]"

    @pytest.mark.parametrize(
        ("key", "token", "body"),
        [
            ("abcdefghij", "efghijklmn", "abcdefghijklmn"),
            ("abababab", "synthetic-unrelated-token", "abababababab"),
        ],
    )
    async def test_unions_original_overlapping_secret_intervals(
        self, key: str, token: str, body: str
    ):
        ep = {
            **OPENAI_ENDPOINT,
            "apiKey": key,
            "sessionToken": {"header": "X-Synthetic-Session", "token": token},
        }
        http = FakeHttp([text_response(400, body)])
        error = await capture_error(
            decisions_with([ep]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.body == "[REDACTED]"

    async def test_redaction_shrink_does_not_retain_original_lookahead(self):
        key = "synthetic-long-credential-0001"
        prefix = key + "x" * (MAX_ERROR_BODY_BYTES - len(key))
        http = FakeHttp([text_response(400, prefix + "LOOKAHEAD_MUST_NOT_BE_RETAINED")])
        error = await capture_error(
            decisions_with([{**OPENAI_ENDPOINT, "apiKey": key}]).create(
                MINIMAL_REQUEST, http_client=http.client
            )
        )
        assert isinstance(error, DecisionsError)
        assert error.body == "[REDACTED]" + "x" * (MAX_ERROR_BODY_BYTES - len(key))
        assert error.body_truncated is True

    @pytest.mark.parametrize("value", [None, 2**63, -(2**63) - 1, 10**400, 2**53 + 0.0])
    def test_rejects_invalid_present_integer_counts(self, value: Any):
        response = fixture("response.full.json")
        response["usage"]["input_tokens"] = value
        with pytest.raises(DecisionsError) as raised:
            _parse_decisions_result(response)
        assert raised.value.kind == "invalidResponse"

    def test_default_deadline_is_ten_minutes(self):
        assert inspect.signature(SessionDecisionsApi.create).parameters["timeout"].default == 600.0

    async def test_own_deadline_is_safe_decisions_timeout(self):
        async def hang(_request: httpx.Request) -> httpx.Response:
            await asyncio.Event().wait()
            raise AssertionError("unreachable")

        http = FakeHttp([hang])
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT]).create(
                MINIMAL_REQUEST, http_client=http.client, timeout=0.01
            )
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "timeout"
        assert error.__cause__ is None
        assert error.__context__ is None

    async def test_endpoint_failure_drops_raw_exception_chain(self):
        async def resolve(_params):
            error = RuntimeError("synthetic-unknown-endpoint-credential")
            setattr(error, "headers", {"Authorization": "synthetic-unknown-endpoint-credential"})
            raise error

        error = await capture_error(SessionDecisionsApi(resolve).create(MINIMAL_REQUEST))
        assert isinstance(error, DecisionsError)
        assert error.kind == "endpointUnavailable"
        assert "synthetic-unknown-endpoint-credential" not in str(error.__cause__)
        assert error.__context__ is None
        assert "synthetic-unknown-endpoint-credential" not in json.dumps(vars(error), default=str)

    async def test_sanitizes_all_transport_cause_fields_and_drops_raw_chain(self):
        key = "synthetic-cause-key-0001"
        failure_type = type(f"Synthetic_{key}_Failure", (RuntimeError,), {})
        original = failure_type(f"message:{key}")
        setattr(original, "code", f"code:{key}")
        setattr(original, "headers", {"X-Synthetic": key})
        setattr(original, "url", f"https://example.com/?secret={key}")
        original.__cause__ = RuntimeError(f"nested:{key}")

        def fail(_request):
            raise original

        http = FakeHttp([fail])
        error = await capture_error(
            decisions_with([{**OPENAI_ENDPOINT, "apiKey": key}]).create(
                MINIMAL_REQUEST, http_client=http.client
            )
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "network"
        cause = error.__cause__
        assert cause is not None
        assert vars(cause) == {
            "name": "Synthetic_[REDACTED]_Failure",
            "message": "message:[REDACTED]",
            "code": "code:[REDACTED]",
        }
        assert cause.__cause__ is None
        assert cause.__context__ is None
        assert original not in _chain(error)
        assert error.__context__ is None
        assert key not in json.dumps([vars(item) for item in _chain(error)], default=str)

    async def test_bounds_every_retained_diagnostic_string(self):
        key = "XYZ"
        repeated = (key + " ") * 8000
        payload = json.dumps(
            {"error": {"message": repeated, "type": key, "code": key, "param": key}}
        )
        http = FakeHttp([text_response(400, payload, {"x-request-id": repeated * 3})])
        error = await capture_error(
            decisions_with([{**OPENAI_ENDPOINT, "apiKey": key}]).create(
                MINIMAL_REQUEST, http_client=http.client
            )
        )
        assert isinstance(error, DecisionsError)
        assert error.body_truncated is False
        assert error.error_type == error.error_code == error.error_param == "[REDACTED]"
        for value in vars(error).values():
            if isinstance(value, str):
                assert len(value.encode("utf-8")) <= MAX_ERROR_BODY_BYTES
                assert key not in value

        failure_type = type("XYZ" * 30000, (RuntimeError,), {})
        original = failure_type(repeated * 3)
        setattr(original, "code", repeated * 3)

        def fail(_request):
            raise original

        error = await capture_error(
            decisions_with([{**OPENAI_ENDPOINT, "apiKey": key}]).create(
                MINIMAL_REQUEST, http_client=FakeHttp([fail]).client
            )
        )
        assert isinstance(error, DecisionsError)
        assert error.__cause__ is not None
        for value in vars(error.__cause__).values():
            assert len(value.encode("utf-8")) <= MAX_ERROR_BODY_BYTES
            assert key not in value

    @pytest.mark.parametrize(
        ("values", "body", "expected"),
        [
            (
                {"headers": {"Authorization": "Bearer XYZ"}},
                "Bearer XYZ:XYZ",
                "[REDACTED]:[REDACTED]",
            ),
            ({"headers": {"api-key": " XYZ "}}, "XYZ", "[REDACTED]"),
            (
                {
                    "apiKey": None,
                    "sessionToken": {"header": "Authorization", "token": "Bearer XYZ"},
                },
                "Bearer XYZ:XYZ",
                "[REDACTED]:[REDACTED]",
            ),
            (
                {
                    "headers": {"X-Session": "OLD"},
                    "sessionToken": {"header": "X-Session", "token": "NEW"},
                },
                "OLD:NEW",
                "[REDACTED]:[REDACTED]",
            ),
            (
                {
                    "apiKey": "",
                    "headers": {"X-Trace": "visible"},
                    "baseUrl": "https://example.com/v1?trace=visible",
                },
                "visible",
                "visible",
            ),
        ],
    )
    async def test_short_credential_sources_and_non_auth_heuristic(
        self, values, body: str, expected: str
    ):
        http = FakeHttp([text_response(400, body)])
        error = await capture_error(
            decisions_with([{**OPENAI_ENDPOINT, **values}]).create(
                MINIMAL_REQUEST, http_client=http.client
            )
        )
        assert isinstance(error, DecisionsError)
        assert error.body == expected

    async def test_bounds_utf8_at_original_boundary_without_partial_code_point(self):
        body = "x" * (MAX_ERROR_BODY_BYTES - 1) + "é" + "lookahead"
        http = FakeHttp([text_response(400, body)])
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.body == "x" * (MAX_ERROR_BODY_BYTES - 1)
        assert error.body_truncated is True

    async def test_read_budget_includes_only_secret_lookahead_and_overflow_byte(self, monkeypatch):
        monkeypatch.setattr(decisions_module, "MAX_ERROR_BODY_BYTES", 32)
        ep = {**OPENAI_ENDPOINT, "apiKey": "synthetic-boundary-key"}
        plan = _plan_attempt(endpoint(ep), MINIMAL_REQUEST["model"])
        pulled = 0
        payload = b"x" * 29 + b"synthetic-boundary-key" + b"z" * 100

        async def chunks() -> AsyncIterator[bytes]:
            nonlocal pulled
            for byte in payload:
                pulled += 1
                yield bytes([byte])

        stream = RecordingStream(chunks, [])
        http = FakeHttp([lambda _request: httpx.Response(400, stream=stream)])
        error = await capture_error(
            decisions_with([ep]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.body_truncated is True
        assert "syn" not in (error.body or "")
        assert pulled == 32 + decisions_module._max_secret_bytes(plan.secrets) + 1
        assert stream.closed

    @pytest.mark.parametrize(
        ("raw_value", "echo"),
        [
            ("%FFcredential-0001", "%ffcredential-0001"),
            ("%E2%28%A1credential-0001", "%e2%28%a1credential-0001"),
            ("synthetic+query+credential", "synthetic+query+credential"),
        ],
    )
    async def test_query_candidates_protect_raw_and_decoded_representations(self, raw_value, echo):
        decoded = unquote_plus(raw_value)
        payload = json.dumps(
            {
                "error": {
                    "message": f"raw:{echo}|decoded:{decoded}",
                    "type": echo,
                    "code": decoded,
                    "param": echo,
                }
            }
        )
        http = FakeHttp([text_response(400, payload, {"x-request-id": f"req:{echo}"})])
        ep = {**OPENAI_ENDPOINT, "baseUrl": f"https://example.com/v1?sig={raw_value}"}
        error = await capture_error(
            decisions_with([ep]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "http"
        assert error.request_id == "req:[REDACTED]"
        assert error.error_type == error.error_code == error.error_param == "[REDACTED]"
        assert "raw:[REDACTED]|decoded:[REDACTED]" in error.message
        assert echo not in (error.body or "")
        assert decoded not in error.message
        assert len(http.calls) == 1


class TestStrictEndpointValidation:
    @pytest.mark.parametrize(
        "url",
        [
            "https:example.com/v1",
            "https:///v1",
            "https://example.com:/v1",
            "https://example.com:-1/v1",
            "https://[::1]extra/v1",
            "https://[:::1]/v1",
            "https://::1/v1",
            "https://bad..host/v1",
            "https://-bad.example/v1",
            "https://bad-.example/v1",
            "https://127.0.0.256/v1",
            "https://example.com/v1%",
            "https://example.com/v1%2",
            "https://example.com/v1?x=%xz",
            "https://example.com/v1#bad%",
            "https://example.com/v1 ",
            "https://example.com/v1\n",
            "https://éxample.com/v1",
            "https://example.com/v1?tag=é",
            "https://example.com\\v1",
        ],
    )
    def test_rejects_ambiguous_or_malformed_uris(self, url: str):
        with pytest.raises(DecisionsError) as raised:
            _build_decisions_url(endpoint({**OPENAI_ENDPOINT, "baseUrl": url}))
        assert raised.value.kind == "invalidEndpoint"
        assert url not in str(raised.value)
        assert raised.value.__context__ is None

    @pytest.mark.parametrize(
        ("url", "expected"),
        [
            ("HTTP://LOCALHOST:0/v1", "http://localhost:0/v1/decisions"),
            ("https://[2001:DB8::1]:443/v1", "https://[2001:db8::1]/v1/decisions"),
            (
                "https://EXAMPLE.com/a//b/../v1?x=a%2Fb&x=2#fragment",
                "https://example.com/a//v1/decisions?x=a%2Fb&x=2",
            ),
            ("https://example.com/a/.%2E/v1", "https://example.com/v1/decisions"),
            ("https://example.com/a/%2e./v1", "https://example.com/v1/decisions"),
        ],
    )
    def test_preserves_supported_uri_forms(self, url: str, expected: str):
        assert _build_decisions_url(endpoint({**OPENAI_ENDPOINT, "baseUrl": url})) == expected

    @pytest.mark.parametrize("codepoint", [*range(9), *range(10, 32), 127, 0xD800, 0xDFFF])
    @pytest.mark.parametrize("source", ["header", "unusedApiKey", "sessionToken"])
    def test_rejects_controls_and_surrogates_before_secret_encoding(self, codepoint, source):
        value = f"synthetic-{chr(codepoint)}-credential"
        ep = {**OPENAI_ENDPOINT, "headers": {"Authorization": "Bearer synthetic-override"}}
        if source == "header":
            ep["headers"]["X-Synthetic"] = value
        elif source == "unusedApiKey":
            ep["apiKey"] = value
        else:
            ep["sessionToken"] = {"header": "X-Session", "token": value}
        with pytest.raises(DecisionsError) as raised:
            _plan_attempt(endpoint(ep), MINIMAL_REQUEST["model"])
        assert raised.value.kind == "invalidEndpoint"
        assert value not in str(raised.value)

    def test_accepts_unicode_scalar_headers_for_custom_transports(self):
        value = " \té🔑\ttext\t "
        plan = _plan_attempt(
            endpoint({**OPENAI_ENDPOINT, "headers": {"X-Synthetic": value}}),
            MINIMAL_REQUEST["model"],
        )
        assert plan.headers["x-synthetic"] == "é🔑\ttext"

    @pytest.mark.parametrize(
        ("updates", "model", "kind"),
        [
            ({"type": "synthetic-provider-secret"}, "m", "unsupportedProvider"),
            ({"transport": "synthetic-transport-secret"}, "m", "unsupportedTransport"),
            (
                {
                    "sessionToken": {
                        "token": "synthetic-token",
                        "header": "X-Session",
                        "model": "synthetic-token-model-secret",
                    }
                },
                "synthetic-request-model-secret",
                "modelMismatch",
            ),
        ],
    )
    def test_preflight_errors_do_not_echo_provider_or_model_values(self, updates, model, kind):
        ep = endpoint(
            {
                **OPENAI_ENDPOINT,
                **{
                    key: value for key, value in updates.items() if key not in ("type", "transport")
                },
            }
        )
        for key in ("type", "transport"):
            if key in updates:
                setattr(ep, key, updates[key])
        with pytest.raises(DecisionsError) as raised:
            _plan_attempt(ep, model)
        assert raised.value.kind == kind
        assert "synthetic" not in str(raised.value)


class TestRequestHygiene:
    @pytest.mark.parametrize(
        "item",
        [
            None,
            1,
            "message",
            [],
            {"raw": None},
            {"type": "future_item"},
            {"role": "tool", "content": ""},
        ],
    )
    def test_invalid_typed_input_items_are_native_argument_errors(self, item):
        with pytest.raises(TypeError):
            _serialize_decisions_request({**MINIMAL_REQUEST, "input": [item]})

    def test_null_optional_message_discriminator_is_omitted(self):
        request = cast(
            DecisionsRequest,
            {**MINIMAL_REQUEST, "input": [{"type": None, "role": "user", "content": "hello"}]},
        )
        assert _serialize_decisions_request(request)["input"] == [
            {"role": "user", "content": "hello"}
        ]

    @pytest.mark.parametrize(
        ("role", "part"),
        [
            ("developer", {"type": "input_image", "image_url": "https://example.com/a.png"}),
            ("system", {"type": "input_json", "data": {}}),
            ("assistant", {"type": "input_text", "text": "no"}),
            ("user", {"type": "output_text", "text": "no"}),
        ],
    )
    def test_forbidden_role_part_combinations_are_rejected(self, role, part):
        request = {**MINIMAL_REQUEST, "input": [{"role": role, "content": [part]}]}
        with pytest.raises(TypeError, match="content part"):
            _serialize_decisions_request(cast(DecisionsRequest, request))

    @pytest.mark.parametrize("item_type", ["function_call_output", "custom_tool_call_output"])
    def test_tool_output_json_parts_are_rejected(self, item_type):
        item = {"type": item_type, "call_id": "c", "output": [{"type": "input_json", "data": {}}]}
        with pytest.raises(TypeError, match="content part"):
            _serialize_decisions_request(
                cast(DecisionsRequest, {**MINIMAL_REQUEST, "input": [item]})
            )

    @pytest.mark.parametrize("data", [{"sla_hours": 4}, [{"id": 1}, {"id": 2}]])
    def test_question_parts_omit_input_only_breakpoints(self, data):
        parts = [
            {
                "type": "input_text",
                "text": "question",
                "prompt_cache_breakpoint": {"mode": "explicit"},
            },
            {
                "type": "input_image",
                "image_url": "https://example.com/a.png",
                "detail": "high",
                "prompt_cache_breakpoint": {"mode": "explicit"},
            },
            {
                "type": "input_json",
                "data": data,
                "prompt_cache_breakpoint": {"mode": "explicit"},
            },
        ]
        request = {
            **MINIMAL_REQUEST,
            "questions": [{"type": "predicate", "instructions": parts}],
        }
        wire = _serialize_decisions_request(cast(DecisionsRequest, request))
        assert wire["questions"][0]["instructions"] == [
            {"type": "input_text", "text": "question"},
            {"type": "input_image", "image_url": "https://example.com/a.png", "detail": "high"},
            {"type": "input_json", "data": data},
        ]

    @pytest.mark.parametrize("part", [None, {"type": "output_text", "text": "unsupported"}])
    def test_unsupported_question_and_null_parts_are_rejected(self, part):
        request = {
            **MINIMAL_REQUEST,
            "questions": [{"type": "predicate", "instructions": [part]}],
        }
        with pytest.raises(TypeError, match="content part"):
            _serialize_decisions_request(cast(DecisionsRequest, request))

    def test_raw_input_remains_explicit_unknown_wire_escape_hatch(self):
        raw = {"type": "synthetic_future_type", "payload": {"nullable": None, "value": [1, True]}}
        wire = _serialize_decisions_request({**MINIMAL_REQUEST, "input": [{"raw": raw}]})
        assert wire["input"] == [raw]


class TestExactResponseNumbers:
    @pytest.mark.parametrize(
        ("token", "expected"),
        [
            ("9007199254740991.0", 9007199254740991),
            ("-9007199254740991e0", -9007199254740991),
            ("3.00000000000000000000001", None),
            ("9007199254740991.00001", None),
            ("9223372036854775807.0", None),
            ("-9223372036854775808e0", None),
            ("1e999999999999999999999999", None),
        ],
    )
    async def test_integral_decimal_tokens_do_not_silently_round(self, token, expected):
        body = _number_body({"target": "usage.input_tokens", "jsonValue": token})
        http = FakeHttp([text_response(200, body)])
        api = decisions_with([OPENAI_ENDPOINT])
        if expected is None:
            error = await capture_error(api.create(MINIMAL_REQUEST, http_client=http.client))
            assert isinstance(error, DecisionsError)
            assert error.kind == "invalidResponse"
        else:
            result = await api.create(MINIMAL_REQUEST, http_client=http.client)
            assert type(result.usage.input_tokens) is int
            assert result.usage.input_tokens == expected

    @pytest.mark.parametrize(
        "path",
        [
            "answers.0.probability",
            "answers.1.confidence",
            "answers.1.probabilities.0.probability",
            "answers.3.score",
            "answers.3.confidence",
            "answers.3.probabilities.0.probability",
        ],
    )
    @pytest.mark.parametrize("token", ["true", "null", '"1"', "1e400", "NaN", str(10**400)])
    async def test_known_float_fields_reject_invalid_and_huge_numbers(self, path, token):
        http = FakeHttp([text_response(200, _number_body({"target": path, "jsonValue": token}))])
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "invalidResponse"

    @pytest.mark.parametrize("token", ["-9223372036854775808", "9223372036854775807"])
    async def test_score_indices_preserve_full_signed_int64_domain(self, token):
        path = "answers.3.probabilities.0.value"
        http = FakeHttp([text_response(200, _number_body({"target": path, "jsonValue": token}))])
        result = await decisions_with([OPENAI_ENDPOINT]).create(
            MINIMAL_REQUEST, http_client=http.client
        )
        assert type(_result_field(result, path)) is int
        assert str(_result_field(result, path)) == token


class TestTotalCallDeadline:
    async def test_default_deadline_is_enforced_without_waiting_ten_minutes(self, monkeypatch):
        original_timeout = asyncio.timeout
        durations: list[float | None] = []

        def shortened_timeout(duration):
            durations.append(duration)
            return original_timeout(0.01)

        async def resolve(_params):
            await asyncio.Event().wait()
            raise AssertionError("unreachable")

        monkeypatch.setattr(decisions_module.asyncio, "timeout", shortened_timeout)
        http = FakeHttp([json_response(200, OK_BODY)])
        error = await capture_error(
            SessionDecisionsApi(resolve).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert durations == [600.0]
        assert isinstance(error, DecisionsError)
        assert error.kind == "timeout"
        assert http.calls == []

    async def test_none_disables_deadline_and_builtin_http_timeout(self, monkeypatch):
        original_timeout = asyncio.timeout
        durations = []
        options = []

        def observed_timeout(duration):
            durations.append(duration)
            return original_timeout(duration)

        http = FakeHttp([json_response(200, OK_BODY)])

        def client_factory(**kwargs):
            options.append(kwargs)
            return http.client

        async def resolve(_params):
            await asyncio.sleep(0.02)
            return endpoint(OPENAI_ENDPOINT)

        monkeypatch.setattr(decisions_module.asyncio, "timeout", observed_timeout)
        monkeypatch.setattr(decisions_module.httpx, "AsyncClient", client_factory)
        result = await SessionDecisionsApi(resolve).create(MINIMAL_REQUEST, timeout=None)
        assert result.model == "gpt-6-luna-2026-10-01"
        assert durations == [None]
        assert options == [{"timeout": None, "follow_redirects": False}]
        assert http.client.is_closed

    @pytest.mark.parametrize(
        "timeout", [0, -1, float("nan"), float("inf"), -float("inf"), True, False, "1", 10**400]
    )
    async def test_invalid_deadlines_fail_before_any_io(self, timeout):
        seen: list[Any] = []
        http = FakeHttp([json_response(200, OK_BODY)])
        api = decisions_with([OPENAI_ENDPOINT], seen)
        with pytest.raises((TypeError, ValueError)):
            await api.create(MINIMAL_REQUEST, timeout=timeout, http_client=http.client)
        with pytest.raises(TypeError, match="model is required"):
            await api.create(
                cast(DecisionsRequest, {**MINIMAL_REQUEST, "model": None}),
                timeout=timeout,
                http_client=http.client,
            )
        assert seen == []
        assert http.calls == []

    async def test_deadline_is_not_reset_between_resolution_and_auth_replay(self, monkeypatch):
        original_timeout = asyncio.timeout
        scopes: list[asyncio.Timeout] = []
        observed_deadlines = []
        resolves = 0

        def observed_timeout(duration):
            scope = original_timeout(duration)
            scopes.append(scope)
            return scope

        async def resolve(_params):
            nonlocal resolves
            resolves += 1
            observed_deadlines.append(scopes[0].when())
            return endpoint({**OPENAI_ENDPOINT, "apiKey": f"synthetic-key-{resolves}"})

        async def respond(_request):
            observed_deadlines.append(scopes[0].when())
            if resolves == 1:
                return httpx.Response(401, content=b"{}")
            return httpx.Response(200, content=OK_BODY.encode())

        monkeypatch.setattr(decisions_module.asyncio, "timeout", observed_timeout)
        http = FakeHttp([respond])
        result = await SessionDecisionsApi(resolve).create(
            MINIMAL_REQUEST, timeout=1, http_client=http.client
        )
        assert result.model
        assert len(scopes) == 1
        assert len(observed_deadlines) == 4
        assert observed_deadlines[0] is not None
        assert all(value == observed_deadlines[0] for value in observed_deadlines)
        assert len(http.calls) == resolves == 2

    async def test_checks_deadline_after_synchronous_serialization_before_send(self, monkeypatch):
        serialize = decisions_module._serialize_decisions_request

        def slow_serialization(request):
            time.sleep(0.03)
            return serialize(request)

        monkeypatch.setattr(decisions_module, "_serialize_decisions_request", slow_serialization)
        http = FakeHttp([json_response(200, OK_BODY)])
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT]).create(
                MINIMAL_REQUEST, timeout=0.01, http_client=http.client
            )
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "timeout"
        assert error.__context__ is None
        assert http.calls == []

    @pytest.mark.parametrize("late_failure", [False, True])
    async def test_cancel_suppressing_resolver_never_dispatches_after_expiry(self, late_failure):
        async def resolve(_params):
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                pass
            if late_failure:
                raise RuntimeError("synthetic-late-rpc-credential")
            return endpoint(OPENAI_ENDPOINT)

        http = FakeHttp([json_response(200, OK_BODY)])
        error = await capture_error(
            SessionDecisionsApi(resolve).create(
                MINIMAL_REQUEST, timeout=0.01, http_client=http.client
            )
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "timeout"
        assert error.__cause__ is None
        assert error.__context__ is None
        assert http.calls == []

    @pytest.mark.parametrize("caller", [False, True])
    @pytest.mark.parametrize("late_error", [False, True])
    async def test_native_rpc_waiter_is_retired_and_late_reply_is_observed(
        self, caller, late_error, monkeypatch
    ):
        loop = asyncio.get_running_loop()
        process = Mock()
        process.poll.return_value = None
        rpc = JsonRpcClient(process)
        rpc._loop = loop
        messages = []
        started = asyncio.Event()
        contexts = []

        async def send(message):
            messages.append(message)
            started.set()

        monkeypatch.setattr(rpc, "_send_message", send)
        previous_handler = loop.get_exception_handler()
        loop.set_exception_handler(lambda _loop, context: contexts.append(context))
        http = FakeHttp([json_response(200, OK_BODY)])
        api = SessionDecisionsApi(ProviderApi(rpc, "synthetic-session").get_endpoint)
        task = asyncio.create_task(
            api.create(MINIMAL_REQUEST, timeout=0.03, http_client=http.client)
        )
        try:
            await asyncio.wait_for(started.wait(), 1)
            if caller:
                task.cancel("synthetic caller cancellation")
            error = await capture_error(task)
            if caller:
                assert isinstance(error, asyncio.CancelledError)
            else:
                assert isinstance(error, DecisionsError)
                assert error.kind == "timeout"
            assert rpc.pending_requests == {}
            assert len(messages) == 1
            assert messages[0]["method"] == "session.provider.getEndpoint"
            late = (
                {"error": {"code": -32000, "message": "synthetic-late-rpc-credential"}}
                if late_error
                else {"result": OPENAI_ENDPOINT}
            )
            rpc._handle_message({"jsonrpc": "2.0", "id": messages[0]["id"], **late})
            await asyncio.sleep(0)
            assert contexts == []
            assert http.calls == []
        finally:
            loop.set_exception_handler(previous_handler)
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)

    @pytest.mark.parametrize("caller", [False, True])
    @pytest.mark.parametrize("reply", ["result", "error", "invalid"])
    async def test_queued_native_rpc_completion_is_safe_after_cancellation(
        self, caller, reply, monkeypatch
    ):
        loop = asyncio.get_running_loop()
        process = Mock()
        process.poll.return_value = None
        rpc = JsonRpcClient(process)
        rpc._loop = loop
        messages = []
        started = asyncio.Event()
        queued = []
        contexts = []

        async def send(message):
            messages.append(message)
            started.set()

        monkeypatch.setattr(rpc, "_send_message", send)
        previous_handler = loop.get_exception_handler()
        loop.set_exception_handler(lambda _loop, context: contexts.append(context))
        http = FakeHttp([json_response(200, OK_BODY)])
        api = SessionDecisionsApi(ProviderApi(rpc, "synthetic-session").get_endpoint)
        task = asyncio.create_task(
            api.create(MINIMAL_REQUEST, timeout=0.03, http_client=http.client)
        )
        try:
            await asyncio.wait_for(started.wait(), 1)
            payload = {
                "result": {"result": OPENAI_ENDPOINT},
                "error": {"error": {"code": -32000, "message": "synthetic-queued-rpc-key"}},
                "invalid": {},
            }[reply]
            with monkeypatch.context() as scope:
                scope.setattr(
                    loop,
                    "call_soon_threadsafe",
                    lambda callback, *args: queued.append((callback, args)),
                )
                rpc._handle_message({"jsonrpc": "2.0", "id": messages[0]["id"], **payload})
            assert len(queued) == 1
            if caller:
                task.cancel()
            error = await capture_error(task)
            if caller:
                assert isinstance(error, asyncio.CancelledError)
            else:
                assert isinstance(error, DecisionsError)
                assert error.kind == "timeout"
            for callback, args in queued:
                loop.call_soon(callback, *args)
            await asyncio.sleep(0)
            assert contexts == []
            assert rpc.pending_requests == {}
            assert http.calls == []
        finally:
            loop.set_exception_handler(previous_handler)
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)

    @pytest.mark.parametrize(
        "stage", ["endpoint", "headers", "successBody", "errorBody", "refresh"]
    )
    async def test_native_caller_cancellation_at_every_stage(self, stage):
        started = asyncio.Event()
        stopped = asyncio.Event()
        streams: list[RecordingStream] = []
        resolves = 0

        async def block():
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                stopped.set()

        async def resolve(_params):
            nonlocal resolves
            resolves += 1
            if stage == "endpoint" or (stage == "refresh" and resolves == 2):
                await block()
            return endpoint(OPENAI_ENDPOINT)

        async def body() -> AsyncIterator[bytes]:
            yield b"{"
            await block()

        async def respond(_request):
            if stage == "headers":
                await block()
            if stage in ("successBody", "errorBody"):
                stream = RecordingStream(body, [])
                streams.append(stream)
                return httpx.Response(200 if stage == "successBody" else 401, stream=stream)
            return httpx.Response(401, content=b"{}")

        http = FakeHttp([respond])
        task = asyncio.create_task(
            SessionDecisionsApi(resolve).create(
                MINIMAL_REQUEST, timeout=None, http_client=http.client
            )
        )
        try:
            await asyncio.wait_for(started.wait(), 1)
            task.cancel("synthetic caller cancellation")
            error = await capture_error(task)
            assert isinstance(error, asyncio.CancelledError)
            assert error.args == ("synthetic caller cancellation",)
            assert stopped.is_set()
            assert all(stream.closed for stream in streams)
        finally:
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)

    async def test_caller_cancelled_before_create_dispatches_no_io(self):
        seen = []
        http = FakeHttp([json_response(200, OK_BODY)])

        async def run():
            task = asyncio.current_task()
            assert task is not None
            task.cancel("synthetic caller cancellation")
            await decisions_with([OPENAI_ENDPOINT], seen).create(
                MINIMAL_REQUEST, http_client=http.client
            )

        error = await capture_error(asyncio.create_task(run()))
        assert isinstance(error, asyncio.CancelledError)
        assert seen == http.calls == []

    async def test_caller_cancellation_wins_when_sdk_expiry_is_also_observed(self, monkeypatch):
        original_timeout = asyncio.timeout
        scopes: list[asyncio.Timeout] = []

        def controlled_timeout(_duration):
            scope = original_timeout(None)
            scopes.append(scope)
            return scope

        async def respond(_request):
            scopes[0].reschedule(asyncio.get_running_loop().time())
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                task = asyncio.current_task()
                assert task is not None
                task.cancel("synthetic caller cancellation")
                raise

        monkeypatch.setattr(decisions_module.asyncio, "timeout", controlled_timeout)
        http = FakeHttp([respond])
        error = await capture_error(
            asyncio.create_task(
                decisions_with([OPENAI_ENDPOINT]).create(
                    MINIMAL_REQUEST, timeout=0.01, http_client=http.client
                )
            )
        )
        assert isinstance(error, asyncio.CancelledError)

    async def test_external_caller_deadline_remains_native(self):
        async def resolve(_params):
            await asyncio.Event().wait()

        async def run():
            async with asyncio.timeout(0.01):
                await SessionDecisionsApi(resolve).create(MINIMAL_REQUEST, timeout=None)

        error = await capture_error(asyncio.create_task(run()))
        assert isinstance(error, TimeoutError)
        assert not isinstance(error, DecisionsError)

    @pytest.mark.parametrize("caller", [False, True])
    async def test_late_http_response_is_closed_after_cancel_suppression(self, caller):
        started = asyncio.Event()

        async def body() -> AsyncIterator[bytes]:
            yield OK_BODY.encode()

        stream = RecordingStream(body, [])

        async def respond(_request):
            started.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                pass
            return httpx.Response(200, stream=stream)

        http = FakeHttp([respond])
        task = asyncio.create_task(
            decisions_with([OPENAI_ENDPOINT]).create(
                MINIMAL_REQUEST, timeout=0.03, http_client=http.client
            )
        )
        await asyncio.wait_for(started.wait(), 1)
        if caller:
            task.cancel()
        error = await capture_error(task)
        if caller:
            assert isinstance(error, asyncio.CancelledError)
        else:
            assert isinstance(error, DecisionsError)
            assert error.kind == "timeout"
            assert error.__cause__ is None
            assert error.__context__ is None
        assert stream.closed

    async def test_independent_transport_timeout_is_sanitized_network_failure(self):
        def fail(_request):
            raise httpx.ReadTimeout("synthetic-transport-key timeout")

        http = FakeHttp([fail])
        error = await capture_error(
            decisions_with([{**OPENAI_ENDPOINT, "apiKey": "synthetic-transport-key"}]).create(
                MINIMAL_REQUEST, timeout=1, http_client=http.client
            )
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "network"
        assert str(error.__cause__) == "[REDACTED] timeout"
        assert error.__context__ is None


class TestBodyOwnership:
    @pytest.mark.parametrize("status", [401, 403])
    async def test_partially_read_auth_failure_does_not_refresh_or_replay(self, status):
        async def broken() -> AsyncIterator[bytes]:
            yield b'{"error":'
            raise ConnectionResetError("synthetic-partial-auth-key")

        stream = RecordingStream(broken, [])
        http = FakeHttp([lambda _request: httpx.Response(status, stream=stream)])
        seen = []
        error = await capture_error(
            decisions_with(
                [
                    {**OPENAI_ENDPOINT, "apiKey": "synthetic-partial-auth-key"},
                    {**OPENAI_ENDPOINT, "apiKey": "synthetic-refreshed-key"},
                ],
                seen,
            ).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "network"
        assert str(error.__cause__) == "[REDACTED]"
        assert error.__context__ is None
        assert stream.closed
        assert len(seen) == len(http.calls) == 1

    @pytest.mark.parametrize(
        ("payload", "expected"),
        [(b"\xef\xbb\xbf" + OK_BODY.encode(), "success"), (b"{", "invalidResponse")],
    )
    async def test_closes_success_bom_and_parse_failure_bodies(self, payload, expected):
        async def chunks() -> AsyncIterator[bytes]:
            yield payload

        stream = RecordingStream(chunks, [])
        http = FakeHttp([lambda _request: httpx.Response(200, stream=stream)])
        api = decisions_with([OPENAI_ENDPOINT])
        if expected == "success":
            result = await api.create(MINIMAL_REQUEST, http_client=http.client)
            assert result.model == "gpt-6-luna-2026-10-01"
        else:
            error = await capture_error(api.create(MINIMAL_REQUEST, http_client=http.client))
            assert isinstance(error, DecisionsError)
            assert error.kind == expected
        assert stream.closed

    async def test_oversized_success_stops_reading_and_closes_body(self, monkeypatch):
        monkeypatch.setattr(decisions_module, "MAX_RESPONSE_BYTES", 64)
        pulled = 0

        async def endless() -> AsyncIterator[bytes]:
            nonlocal pulled
            while True:
                pulled += 1
                yield b"x"

        stream = RecordingStream(endless, [])
        http = FakeHttp([lambda _request: httpx.Response(200, stream=stream)])
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "invalidResponse"
        assert pulled == 65
        assert stream.closed

    async def test_refresh_rpc_failure_has_safe_chain_and_closed_original_body(self):
        async def body() -> AsyncIterator[bytes]:
            yield b"{}"

        stream = RecordingStream(body, [])
        resolves = 0
        original = RuntimeError("synthetic-refresh-rpc-secret")

        async def resolve(_params):
            nonlocal resolves
            resolves += 1
            if resolves == 2:
                raise original
            return endpoint(OPENAI_ENDPOINT)

        http = FakeHttp([lambda _request: httpx.Response(401, stream=stream)])
        error = await capture_error(
            SessionDecisionsApi(resolve).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "endpointUnavailable"
        assert original not in _chain(error)
        assert "synthetic" not in str(error.__cause__)
        assert error.__context__ is None
        assert stream.closed
        assert resolves == 2
        assert len(http.calls) == 1

    async def test_replayed_error_protects_both_old_and_new_credentials(self):
        first = {**OPENAI_ENDPOINT, "apiKey": "synthetic-old-key"}
        second = {**OPENAI_ENDPOINT, "apiKey": "synthetic-new-key"}
        http = FakeHttp(
            [
                text_response(401, "{}"),
                text_response(403, "synthetic-old-key:synthetic-new-key"),
            ]
        )
        error = await capture_error(
            decisions_with([first, second]).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.body == "[REDACTED]:[REDACTED]"
        assert len(http.calls) == 2

    async def test_replayed_network_error_protects_previous_credentials_and_raw_query(self):
        first = {
            **OPENAI_ENDPOINT,
            "apiKey": "synthetic-old-key",
            "baseUrl": "https://example.com/v1?sig=%FFcredential-0001",
        }
        second = {
            **OPENAI_ENDPOINT,
            "apiKey": "synthetic-new-key",
            "baseUrl": "https://example.com/v1",
        }

        def fail(_request):
            error = RuntimeError("synthetic-old-key:synthetic-new-key:%ffcredential-0001")
            setattr(error, "code", "synthetic-old-key:%ffcredential-0001")
            raise error

        seen = []
        http = FakeHttp([text_response(401, "{}"), fail])
        error = await capture_error(
            decisions_with([first, second], seen).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "network"
        cause = error.__cause__
        assert cause is not None
        assert str(cause) == "[REDACTED]:[REDACTED]:[REDACTED]"
        assert getattr(cause, "code") == "[REDACTED]:[REDACTED]"
        assert error.__context__ is None
        assert len(seen) == len(http.calls) == 2
        assert http.calls[1].headers["authorization"] == "Bearer synthetic-new-key"
        assert str(http.calls[1].url) == "https://example.com/v1/decisions"

    @pytest.mark.parametrize("status", [408, 429, 500, 503])
    async def test_retry_hint_does_not_enable_network_or_backoff_retries(self, status):
        seen = []
        http = FakeHttp([text_response(status, "{}", {"retry-after": "0"})])
        error = await capture_error(
            decisions_with([OPENAI_ENDPOINT], seen).create(MINIMAL_REQUEST, http_client=http.client)
        )
        assert isinstance(error, DecisionsError)
        assert error.kind == "http"
        assert error.retry_after_ms == 0
        assert len(seen) == len(http.calls) == 1


class TestRetryHintStrictness:
    @pytest.mark.parametrize(
        "value", [".5", "1.", "1e3", "+1", "-1", "1,2", "NaN", "Infinity", "\u00a01", "1\n"]
    )
    def test_rejects_non_decimal_delay_syntax_without_echoing_it(self, value):
        assert _parse_retry_after_ms({"retry-after-ms": value}, 0) is None
        assert _parse_retry_after_ms({"retry-after": value}, 0) is None

    @pytest.mark.parametrize(
        ("headers", "expected"),
        [
            ({"retry-after-ms": "9007199254740991.0"}, 9007199254740991),
            ({"retry-after-ms": "9007199254740991.000000000000000000001"}, None),
            ({"retry-after-ms": "9007199254740990.000000000000000000001"}, 9007199254740991),
            ({"retry-after": "9007199254740.990000000000000000001"}, 9007199254740991),
            ({"retry-after-ms": "0" * 6000 + "2"}, 2),
            ({"retry-after-ms": "9" * 6000, "retry-after": "2"}, 2000),
            ({"retry-after": "Thu, 21 Oct 2015 07:28:00 GMT"}, None),
            ({"retry-after": "Wed, 1 Oct 2015 07:28:00 GMT"}, None),
        ],
    )
    def test_exact_rounding_overflow_and_canonical_dates(self, headers, expected):
        assert _parse_retry_after_ms(headers, 1445412420000) == expected


@pytest.fixture
async def local_http():
    servers: list[asyncio.Server] = []
    writers: list[asyncio.StreamWriter] = []
    tasks: list[asyncio.Task] = []
    failures: list[BaseException] = []

    async def start(handler):
        requests = []

        async def connected(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
            task = asyncio.current_task()
            assert task is not None
            tasks.append(task)
            writers.append(writer)
            try:
                raw_headers = (await reader.readuntil(b"\r\n\r\n")).decode("ascii")
                request_line, *lines = raw_headers.split("\r\n")
                headers = {
                    name.lower(): value.strip()
                    for line in lines
                    if ":" in line
                    for name, value in [line.split(":", 1)]
                }
                body = await reader.readexactly(int(headers.get("content-length", "0")))
                request = {"line": request_line, "headers": headers, "body": body}
                requests.append(request)
                await handler(reader, writer, request)
            except (ConnectionResetError, BrokenPipeError):
                pass
            except Exception as error:
                failures.append(error)
            finally:
                writer.close()
                try:
                    await writer.wait_closed()
                except (ConnectionResetError, BrokenPipeError):
                    pass

        server = await asyncio.start_server(connected, "127.0.0.1", 0)
        servers.append(server)
        port = server.sockets[0].getsockname()[1]
        return f"http://127.0.0.1:{port}/v1", requests

    try:
        yield start
    finally:
        for server in servers:
            server.close()
            await server.wait_closed()
        for writer in writers:
            writer.close()
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        assert failures == []


class TestRealLocalHttpCleanup:
    @pytest.mark.parametrize("status", [200, 400, 401, 403])
    @pytest.mark.parametrize("caller", [False, True])
    async def test_slow_body_socket_is_closed_on_deadline_or_caller_cancel(
        self, status, caller, local_http
    ):
        started = asyncio.Event()
        disconnected = asyncio.Event()

        async def handler(reader, writer, _request):
            writer.write(
                f"HTTP/1.1 {status} Synthetic\r\nContent-Length: 10000\r\n"
                "Content-Type: application/json\r\n\r\n{".encode()
            )
            await writer.drain()
            started.set()
            assert await reader.read(1) == b""
            disconnected.set()

        url, requests = await local_http(handler)
        seen = []
        async with httpx.AsyncClient(timeout=None, trust_env=False) as client:
            task = asyncio.create_task(
                decisions_with([{**OPENAI_ENDPOINT, "baseUrl": url}], seen).create(
                    MINIMAL_REQUEST, timeout=None if caller else 0.5, http_client=client
                )
            )
            try:
                await asyncio.wait_for(started.wait(), 3)
                if caller:
                    task.cancel("synthetic caller cancellation")
                error = await capture_error(task)
                if caller:
                    assert isinstance(error, asyncio.CancelledError)
                else:
                    assert isinstance(error, DecisionsError)
                    assert error.kind == "timeout"
                    assert error.__cause__ is None
                    assert error.__context__ is None
                await asyncio.wait_for(disconnected.wait(), 3)
                assert len(requests) == len(seen) == 1
            finally:
                if not task.done():
                    task.cancel()
                    await asyncio.gather(task, return_exceptions=True)

    @pytest.mark.parametrize("status", [401, 403])
    async def test_incomplete_auth_body_socket_close_does_not_replay(self, status, local_http):
        async def handler(_reader, writer, _request):
            writer.write(f"HTTP/1.1 {status} Synthetic\r\nContent-Length: 10000\r\n\r\n{{".encode())
            await writer.drain()

        url, requests = await local_http(handler)
        seen = []
        async with httpx.AsyncClient(timeout=None, trust_env=False) as client:
            error = await capture_error(
                decisions_with(
                    [
                        {**OPENAI_ENDPOINT, "baseUrl": url},
                        {
                            **OPENAI_ENDPOINT,
                            "baseUrl": url,
                            "apiKey": "synthetic-refreshed-local-key",
                        },
                    ],
                    seen,
                ).create(MINIMAL_REQUEST, timeout=2, http_client=client)
            )
        assert isinstance(error, DecisionsError)
        assert error.kind == "network"
        assert len(requests) == len(seen) == 1
        assert error.__context__ is None

    async def test_complete_auth_body_allows_one_real_http_refresh(self, local_http):
        async def handler(_reader, writer, request):
            if request["headers"]["authorization"] == "Bearer synthetic-local-first":
                status, body = 401, b'{"error":{"code":"expired"}}'
            else:
                status, body = 200, OK_BODY.encode()
            writer.write(
                f"HTTP/1.1 {status} Synthetic\r\nContent-Length: {len(body)}\r\n"
                "Connection: close\r\nContent-Type: application/json\r\n\r\n".encode()
                + body
            )
            await writer.drain()

        url, requests = await local_http(handler)
        seen = []
        async with httpx.AsyncClient(timeout=None, trust_env=False) as client:
            result = await decisions_with(
                [
                    {**OPENAI_ENDPOINT, "baseUrl": url, "apiKey": "synthetic-local-first"},
                    {**OPENAI_ENDPOINT, "baseUrl": url, "apiKey": "synthetic-local-second"},
                ],
                seen,
            ).create(MINIMAL_REQUEST, timeout=2, http_client=client)
        assert result.model == "gpt-6-luna-2026-10-01"
        assert len(requests) == len(seen) == 2
        assert all(request["line"] == "POST /v1/decisions HTTP/1.1" for request in requests)
