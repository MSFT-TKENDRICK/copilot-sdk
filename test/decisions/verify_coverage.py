"""Check assertion-backed Decisions vector censuses against the shared corpus.

A census proves neither that tests ran nor that a whole suite passed. Run this
after a successful test command; retain that command's logs and exit status.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any

SDKS = ("node", "python", "go", "dotnet", "java", "rust")
GROUPS = {
    "vectors.json": {"url", "headers", "retry", "preflight", "redaction", "redactionLength"},
    "production-vectors.json": {
        "metadata", "retryAfter", "responseNumbers", "authBodyFailure", "deadline",
    },
}
DEFAULT_FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "decisions"
MAX_JSON_BYTES = 2 * 1024 * 1024
Identity = tuple[str, str, str]


class InvalidEvidence(ValueError):
    """A fixture or census cannot establish complete vector coverage."""


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise InvalidEvidence(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def _invalid_constant(value: str) -> None:
    raise InvalidEvidence(f"Nonstandard JSON numeric constant: {value}")


def read_json(path: Path) -> tuple[Any, str]:
    try:
        with path.open("rb") as stream:
            data = stream.read(MAX_JSON_BYTES + 1)
        if len(data) > MAX_JSON_BYTES:
            raise InvalidEvidence(f"{path.name}: exceeds the evidence size limit")
        document = json.loads(
            data.decode("utf-8-sig"),
            object_pairs_hook=_unique_object,
            parse_constant=_invalid_constant,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise InvalidEvidence(f"{path.name}: unreadable or malformed JSON ({type(error).__name__})")
    return document, hashlib.sha256(data).hexdigest()


def expected_cases(fixtures: Path) -> tuple[set[Identity], dict[str, str]]:
    identities: set[Identity] = set()
    hashes = {}
    for filename, required_groups in GROUPS.items():
        document, hashes[filename] = read_json(fixtures / filename)
        if not isinstance(document, dict):
            raise InvalidEvidence(f"{filename}: expected an object")
        actual_groups = {key for key in document if not key.startswith("$")}
        if actual_groups != required_groups:
            raise InvalidEvidence(f"{filename}: missing or unknown vector groups")
        for group in sorted(required_groups):
            cases = document[group]
            if not isinstance(cases, list) or not cases:
                raise InvalidEvidence(f"{filename}/{group}: expected a nonempty case list")
            for case in cases:
                if not isinstance(case, dict):
                    raise InvalidEvidence(f"{filename}/{group}: expected a case object")
                name = case.get("name")
                if not isinstance(name, str) or not name.strip():
                    raise InvalidEvidence(f"{filename}/{group}: missing case name")
                identity = filename, group, name
                if identity in identities:
                    raise InvalidEvidence(f"{filename}/{group}: duplicate case {name}")
                identities.add(identity)
    return identities, hashes


def validate_census(document: Any, expected: set[Identity]) -> tuple[str, list[str]]:
    if not isinstance(document, dict):
        return "", ["census must be a JSON object"]
    errors = []
    if set(document) != {"schemaVersion", "sdk", "cases"}:
        errors.append("census must contain exactly schemaVersion, sdk, and cases")
    if type(document.get("schemaVersion")) is not int or document["schemaVersion"] != 1:
        errors.append("schemaVersion must be integer 1")
    sdk = document.get("sdk")
    if not isinstance(sdk, str) or sdk not in SDKS:
        errors.append("sdk is not a supported SDK identity")
        sdk = ""
    cases = document.get("cases")
    if not isinstance(cases, list):
        return sdk, errors + ["cases must be a list"]
    seen: set[Identity] = set()
    for index, case in enumerate(cases):
        if not isinstance(case, dict) or set(case) != {"file", "group", "name", "status"}:
            errors.append(f"case[{index}]: expected exactly file, group, name, and status")
            continue
        if any(not isinstance(case[key], str) or not case[key] for key in case):
            errors.append(f"case[{index}]: all fields must be nonempty strings")
            continue
        identity = case["file"], case["group"], case["name"]
        if identity in seen:
            errors.append(f"case[{index}]: duplicate vector identity {identity}")
        seen.add(identity)
        if identity not in expected:
            errors.append(f"case[{index}]: unknown vector identity {identity}")
        if case["status"] != "passed":
            errors.append(f"case[{index}]: not passed")
    missing = sorted(expected - seen)
    if missing:
        errors.append(f"missing {len(missing)} vector(s): {missing[:5]}")
    return sdk, errors


def verify(paths: list[Path], fixtures: Path, required_sdks: set[str]) -> dict[str, Any]:
    if not required_sdks or not required_sdks <= set(SDKS):
        raise InvalidEvidence("At least one known SDK is required")
    expected, fixture_hashes = expected_cases(fixtures)
    errors = []
    reports: dict[str, Any] = {}
    for path in paths:
        try:
            document, digest = read_json(path)
        except InvalidEvidence as error:
            errors.append(str(error))
            continue
        sdk, problems = validate_census(document, expected)
        errors.extend(f"{path.name}: {problem}" for problem in problems)
        if not sdk:
            continue
        if sdk in reports:
            errors.append(f"Duplicate census for SDK {sdk}; repeats are not independent coverage")
            continue
        if sdk not in required_sdks:
            errors.append(f"Unexpected SDK {sdk}")
        reports[sdk] = {"censusFile": path.name, "sha256": digest, "valid": not problems}
    missing_sdks = required_sdks - set(reports)
    if missing_sdks:
        errors.append("Missing SDKs: " + ", ".join(sorted(missing_sdks)))
    return {
        "schemaVersion": 1,
        "scope": "shared-vector-census-only; successful runner exit is separately required",
        "complete": not errors,
        "requiredSdks": sorted(required_sdks),
        "casesPerSdk": len(expected),
        "groups": dict(sorted(Counter(f"{file}/{group}" for file, group, _ in expected).items())),
        "fixtureSha256": fixture_hashes,
        "censuses": reports,
        "errors": errors,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("census", type=Path, nargs="+")
    parser.add_argument("--sdk", choices=SDKS, help="Check one SDK; default requires all six")
    parser.add_argument("--fixtures", type=Path, default=DEFAULT_FIXTURES)
    parser.add_argument("--output", type=Path, help="Optional machine-readable coverage report")
    args = parser.parse_args(argv)
    required = {args.sdk} if args.sdk else set(SDKS)
    if args.output:
        protected = args.census + [args.fixtures / name for name in GROUPS]
        if args.output.resolve() in {path.resolve() for path in protected}:
            parser.error("--output must not overwrite a census or fixture")
    try:
        report = verify(args.census, args.fixtures, required)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except (InvalidEvidence, OSError) as error:
        print(f"Coverage verification failed: {error}", file=sys.stderr)
        return 1
    if report["errors"]:
        for error in report["errors"]:
            print(f"Coverage verification failed: {error}", file=sys.stderr)
        return 1
    print(
        f"Complete census: {report['casesPerSdk']} vectors per SDK; "
        f"SDKs: {', '.join(sorted(required))}. Runner success is a separate prerequisite."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
