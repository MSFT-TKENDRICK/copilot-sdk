"""Assertion-backed Decisions vector census, including pytest-xdist runs."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import cast

import pytest

_PASSED_VECTORS: dict[tuple[str, str, str], dict[str, str]] = {}


@pytest.fixture
def record_vector(request: pytest.FixtureRequest):
    def record(file: str, group: str, name: str) -> None:
        request.node.user_properties.append(("decisions_vector", (file, group, name)))

    return record


def pytest_runtest_logreport(report: pytest.TestReport) -> None:
    if report.when != "call" or not report.passed:
        return
    for key, value in report.user_properties:
        if key == "decisions_vector":
            file, group, name = cast(tuple[str, str, str], value)
            _PASSED_VECTORS[(file, group, name)] = {
                "file": file,
                "group": group,
                "name": name,
                "status": "passed",
            }


def pytest_sessionfinish(session: pytest.Session) -> None:
    path = os.environ.get("COPILOT_DECISIONS_VECTOR_CENSUS")
    if path and not hasattr(session.config, "workerinput"):
        output = Path(path)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(
            json.dumps(
                {
                    "sdk": "python",
                    "schemaVersion": 1,
                    "cases": [_PASSED_VECTORS[key] for key in sorted(_PASSED_VECTORS)],
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
