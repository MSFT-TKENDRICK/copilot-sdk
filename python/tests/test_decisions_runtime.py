# --------------------------------------------------------------------------------------------
#  Copyright (c) Microsoft Corporation. All rights reserved.
# --------------------------------------------------------------------------------------------

"""E2E coverage for the experimental ``session.decisions`` surface against a real runtime.

``session.decisions.create`` resolves the session's provider endpoint through the
``session.provider.getEndpoint`` RPC and then calls ``POST {baseUrl}/decisions``
directly from the SDK. These tests point a BYOK OpenAI provider at a local HTTP
server and validate, end to end:
 1. with ``COPILOT_ALLOW_GET_PROVIDER_ENDPOINT=true``, the request reaches the
    provider with the BYOK key and the response is parsed;
 2. without the gate, the call fails with ``endpointUnavailable`` and a hint
    naming the environment variable.

The gate is read by the runtime process, so each case uses its own client.
"""

from __future__ import annotations

import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest
import pytest_asyncio

from copilot import CopilotClient, DecisionsError, DecisionsPredicateAnswer
from copilot.client import RuntimeConnection
from copilot.session import PermissionHandler

pytestmark = [
    pytest.mark.asyncio(loop_scope="module"),
    pytest.mark.skipif(
        not os.environ.get("COPILOT_CLI_PATH"),
        reason="requires COPILOT_CLI_PATH pointing at a Copilot CLI runtime",
    ),
]

GATE_ENV = "COPILOT_ALLOW_GET_PROVIDER_ENDPOINT"
API_KEY = "sk-e2e-decisions-key-0001"
FIXTURES = Path(__file__).resolve().parents[2] / "test" / "fixtures" / "decisions"


class _DecisionsServer:
    """Local stand-in for an OpenAI ``/v1/decisions`` endpoint."""

    def __init__(self) -> None:
        self.requests: list[tuple[str, dict[str, str], dict]] = []
        response_body = (FIXTURES / "response.full.json").read_bytes()
        captured = self.requests

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("content-length", "0"))
                body = json.loads(self.rfile.read(length) or b"{}")
                captured.append((self.path, {k.lower(): v for k, v in self.headers.items()}, body))
                self.send_response(200)
                self.send_header("content-type", "application/json")
                self.send_header("content-length", str(len(response_body)))
                self.end_headers()
                self.wfile.write(response_body)

            def log_message(self, *args) -> None:
                pass

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self._server.server_address[1]}/v1"

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()


@pytest.fixture(scope="module")
def decisions_server():
    server = _DecisionsServer()
    try:
        yield server
    finally:
        server.close()


async def _start_client(home: Path, gate: bool) -> CopilotClient:
    env = {k: v for k, v in os.environ.items() if k != GATE_ENV}
    env["COPILOT_HOME"] = str(home)
    if gate:
        env[GATE_ENV] = "true"
    client = CopilotClient(
        connection=RuntimeConnection.for_stdio(path=os.environ["COPILOT_CLI_PATH"]),
        env=env,
        use_logged_in_user=False,
    )
    await client.start()
    return client


@pytest_asyncio.fixture(loop_scope="module")
async def gated_client(tmp_path_factory):
    client = await _start_client(tmp_path_factory.mktemp("gated-home"), gate=True)
    try:
        yield client
    finally:
        await client.stop()


@pytest_asyncio.fixture(loop_scope="module")
async def ungated_client(tmp_path_factory):
    client = await _start_client(tmp_path_factory.mktemp("ungated-home"), gate=False)
    try:
        yield client
    finally:
        await client.stop()


async def _byok_session(client: CopilotClient, base_url: str):
    return await client.create_session(
        on_permission_request=PermissionHandler.approve_all,
        model="gpt-6-luna",
        provider={"type": "openai", "base_url": base_url, "api_key": API_KEY},
    )


class TestDecisionsE2E:
    async def test_calls_the_session_provider_when_gate_is_enabled(
        self, gated_client, decisions_server
    ):
        session = await _byok_session(gated_client, decisions_server.base_url)
        try:
            result = await session.decisions.create(
                {
                    "model": "gpt-6-luna",
                    "input": [{"role": "user", "content": "Is the sky blue?"}],
                    "questions": [{"type": "predicate", "instructions": "Is it true?"}],
                }
            )
        finally:
            await session.disconnect()

        path, headers, body = decisions_server.requests[-1]
        assert path == "/v1/decisions"
        assert headers["authorization"] == f"Bearer {API_KEY}"
        assert headers["content-type"] == "application/json"
        assert body["model"] == "gpt-6-luna"
        assert body["questions"][0]["type"] == "predicate"
        assert result.model
        assert isinstance(result.answers[0], DecisionsPredicateAnswer)

    async def test_reports_endpoint_unavailable_when_gate_is_disabled(
        self, ungated_client, decisions_server
    ):
        session = await _byok_session(ungated_client, decisions_server.base_url)
        before = len(decisions_server.requests)
        try:
            with pytest.raises(DecisionsError) as raised:
                await session.decisions.create(
                    {
                        "model": "gpt-6-luna",
                        "input": "Is the sky blue?",
                        "questions": [{"type": "predicate", "instructions": "Is it true?"}],
                    }
                )
        finally:
            await session.disconnect()

        assert raised.value.kind == "endpointUnavailable"
        assert GATE_ENV in str(raised.value)
        assert len(decisions_server.requests) == before
