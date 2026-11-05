"""Check a committed Decisions change set for generated edits and secret alerts.

The scanner examines the complete base..head commit range, not just its final
diff. Only alert locations are retained; matched text and credentials are never
copied to the result or printed. A clean checkout and a working Gitleaks binary
are required. The scanner uses its default rules with only exact committed
fingerprint exclusions; repository/environment rule overrides and inline
allow-comments are not used. This does not establish that arbitrary sensitive
data is absent.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any

MAX_REPORT_BYTES = 8 * 1024 * 1024
GENERATED_MARKER = r"DO NOT EDIT|[Cc]ode generated|[Aa]uto[- ]?generated"
SOURCE_GLOBS = ("*.go", "*.ts", "*.py", "*.cs", "*.java", "*.rs")
SCANNER_CONFIG = b"[extend]\nuseDefault = true\n"


class IntegrityError(ValueError):
    """The requested change set cannot be verified."""


def generated_by_name(name: str) -> bool:
    path = PurePosixPath(name.replace("\\", "/").lower())
    return (
        any(part.strip("_") == "generated" for part in path.parts[:-1])
        or path.stem == "generated"
        or any(marker in path.name for marker in ("_generated.", ".generated.", ".gen."))
    )


def git(repo: Path, *args: str, allowed: tuple[int, ...] = (0,)) -> bytes:
    try:
        result = subprocess.run(
            ["git", "--no-pager", "-C", str(repo), *args],
            capture_output=True,
            timeout=60,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise IntegrityError(f"Git invocation unavailable ({type(error).__name__})") from None
    if result.returncode not in allowed:
        raise IntegrityError("Git could not verify the requested repository or revision")
    return result.stdout


def resolve_revision(repo: Path, revision: str) -> str:
    if revision != "HEAD" and not re.fullmatch(r"[a-fA-F0-9]{7,40}", revision):
        raise IntegrityError("Revisions must be immutable commit IDs (or HEAD)")
    value = git(repo, "rev-parse", "--verify", f"{revision}^{{commit}}").decode().strip()
    if not re.fullmatch(r"[a-f0-9]{40}", value):
        raise IntegrityError("Git returned an invalid commit identity")
    return value


def generated_header(source: bytes) -> bool:
    """Recognize leading generated comments, not marker literals in source code."""
    delimiter: str | None = None
    for line in source.decode("utf-8-sig", errors="replace").splitlines():
        remaining = line.lstrip()
        while remaining:
            if delimiter is not None:
                comment, end, remaining = remaining.partition(delimiter)
                if re.search(GENERATED_MARKER, comment):
                    return True
                if not end:
                    break
                delimiter = None
                remaining = remaining.lstrip()
            elif remaining.startswith(("//", "#")):
                if re.search(GENERATED_MARKER, remaining):
                    return True
                break
            elif remaining.startswith("/*"):
                delimiter, remaining = "*/", remaining[2:]
            elif remaining.startswith(('"""', "'''")):
                delimiter, remaining = remaining[:3], remaining[3:]
            else:
                return False
    return False


def generated_attributes(repo: Path, revision: str, names: list[str]) -> set[str]:
    marked: set[str] = set()
    for start in range(0, len(names), 64):
        batch = names[start : start + 64]
        raw = git(
            repo, "check-attr", f"--source={revision}", "-z", "linguist-generated", "--", *batch
        )
        fields = raw.decode("utf-8").split("\0")
        if fields[-1] != "" or len(fields) != 3 * len(batch) + 1:
            raise IntegrityError("Git returned an incomplete generated-attribute result")
        seen: set[str] = set()
        for offset in range(0, len(fields) - 1, 3):
            name, attribute, value = fields[offset : offset + 3]
            if name not in batch or name in seen or attribute != "linguist-generated":
                raise IntegrityError("Git returned an invalid generated-attribute result")
            seen.add(name)
            if value.lower() in ("true", "set"):
                marked.add(name)
    return marked


def generated_changes(repo: Path, base: str, head: str, names: list[str]) -> list[str]:
    changed = set(names)
    marked = {name for name in changed if generated_by_name(name)}
    for revision in (base, head):
        marked.update(generated_attributes(repo, revision, names))
        data = git(
            repo,
            "grep",
            "-I",
            "-l",
            "-z",
            "-E",
            GENERATED_MARKER,
            revision,
            "--",
            *SOURCE_GLOBS,
            allowed=(0, 1),
        )
        for record in data.decode("utf-8").split("\0"):
            if record:
                name = record.removeprefix(revision + ":")
                if name in changed and name not in marked:
                    if generated_header(git(repo, "show", f"{revision}:{name}")):
                        marked.add(name)
    return sorted(marked)


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise IntegrityError("Scanner output has duplicate JSON keys")
        result[key] = value
    return result


def invalid_constant(value: str) -> None:
    raise IntegrityError("Scanner output has a nonstandard JSON number")


def alert_locations(raw: bytes) -> list[dict[str, Any]]:
    if len(raw) > MAX_REPORT_BYTES:
        raise IntegrityError("Scanner report exceeds the size limit")
    try:
        findings = json.loads(
            raw.decode("utf-8-sig"),
            object_pairs_hook=unique_object,
            parse_constant=invalid_constant,
        )
    except (UnicodeError, json.JSONDecodeError, RecursionError):
        raise IntegrityError("Scanner report is not valid JSON") from None
    if not isinstance(findings, list):
        raise IntegrityError("Scanner report must be a list")
    locations = []
    for finding in findings:
        if not isinstance(finding, dict):
            raise IntegrityError("Scanner alert must be an object")
        fields = {key: finding.get(key) for key in ("RuleID", "File", "StartLine", "Commit")}
        if (
            any(not isinstance(fields[key], str) or not fields[key] for key in ("RuleID", "File"))
            or type(fields["StartLine"]) is not int
            or fields["StartLine"] < 1
            or not isinstance(fields["Commit"], str)
            or not re.fullmatch(r"[a-f0-9]{40}", fields["Commit"])
        ):
            raise IntegrityError("Scanner alert lacks a valid location")
        locations.append(fields)
    return locations


def committed_ignores(repo: Path, head: str) -> tuple[bytes, list[str]]:
    paths = git(repo, "ls-tree", "--name-only", "-z", head, "--", ".gitleaksignore")
    if not paths:
        return b"", []
    if paths != b".gitleaksignore\0":
        raise IntegrityError("Git returned an invalid scanner exclusion path")
    raw = git(repo, "show", f"{head}:.gitleaksignore")
    if len(raw) > 64 * 1024:
        raise IntegrityError("Scanner exclusion file exceeds the size limit")
    try:
        lines = raw.decode("utf-8-sig").splitlines()
    except UnicodeError:
        raise IntegrityError("Scanner exclusion file must be UTF-8") from None
    fingerprints = []
    for line in lines:
        value = line.strip()
        if not value or value.startswith("#"):
            continue
        if not re.fullmatch(r"[a-f0-9]{40}:[^:\r\n]+:[\w-]+:[1-9][0-9]*", value):
            raise IntegrityError("Scanner exclusions must be exact commit/file/rule/line fingerprints")
        name = value.split(":")[1]
        if (
            PurePosixPath(name).is_absolute()
            or ".." in PurePosixPath(name).parts
            or any(character in name for character in ("\\", "*", "?", "[", "]"))
            or value in fingerprints
        ):
            raise IntegrityError("Scanner exclusion has a nonliteral path or duplicate fingerprint")
        fingerprints.append(value)
    return raw, fingerprints


def scan_secrets(repo: Path, base: str, head: str, executable: str) -> dict[str, Any]:
    try:
        version = subprocess.run(
            [executable, "version"], capture_output=True, timeout=30, check=False
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise IntegrityError(f"Gitleaks is unavailable ({type(error).__name__})") from None
    release = version.stdout.decode("utf-8", errors="replace").strip()
    if version.returncode != 0 or not re.fullmatch(r"v?\d+\.\d+\.\d+(?:[-+][\w.-]+)?", release):
        raise IntegrityError("Gitleaks did not report a usable version")
    ignore_bytes, fingerprints = committed_ignores(repo, head)
    with tempfile.TemporaryDirectory(prefix="decisions-integrity-") as directory:
        report_path = Path(directory) / "redacted-gitleaks.json"
        config_path = Path(directory) / "gitleaks.toml"
        ignore_path = Path(directory) / ".gitleaksignore"
        config_path.write_bytes(SCANNER_CONFIG)
        ignore_path.write_bytes(ignore_bytes)
        command = [
            executable,
            "git",
            "--config",
            str(config_path),
            "--gitleaks-ignore-path",
            str(ignore_path),
            "--ignore-gitleaks-allow",
            "--redact=100",
            "--no-banner",
            "--log-level=error",
            "--report-format=json",
            "--report-path",
            str(report_path),
            f"--log-opts={base}..{head}",
            str(repo),
        ]
        try:
            result = subprocess.run(command, capture_output=True, timeout=300, check=False)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise IntegrityError(f"Gitleaks did not complete ({type(error).__name__})") from None
        if result.returncode not in (0, 1):
            raise IntegrityError("Gitleaks failed before a valid scan result was available")
        try:
            with report_path.open("rb") as stream:
                raw = stream.read(MAX_REPORT_BYTES + 1)
        except OSError:
            raise IntegrityError("Gitleaks did not produce its required report") from None
        locations = alert_locations(raw)
        if (result.returncode == 0) != (not locations):
            raise IntegrityError("Gitleaks exit status contradicts its alert report")
    return {
        "tool": "gitleaks",
        "version": release,
        "scope": "all commits in base..head",
        "exitCode": result.returncode,
        "status": "passed" if not locations else "failed",
        "alerts": locations,
        "policy": {
            "defaultRules": True,
            "inlineAllowComments": False,
            "configSha256": hashlib.sha256(SCANNER_CONFIG).hexdigest(),
            "ignoreFileSha256": hashlib.sha256(ignore_bytes).hexdigest(),
            "fingerprints": fingerprints,
        },
    }


def verify(repo: Path, base_revision: str, head_revision: str, executable: str) -> dict[str, Any]:
    base = resolve_revision(repo, base_revision)
    head = resolve_revision(repo, head_revision)
    if base == head:
        raise IntegrityError("An empty commit range cannot establish feature integrity")
    git(repo, "merge-base", "--is-ancestor", base, head)
    if resolve_revision(repo, "HEAD") != head:
        raise IntegrityError("The requested head must be checked out")
    if git(repo, "status", "--porcelain=v1", "-z"):
        raise IntegrityError("Commit or isolate pending changes before verifying integrity")
    names = git(
        repo, "diff", "--no-ext-diff", "--no-renames", "--name-only", "-z", base, head, "--"
    )
    changed = [name for name in names.decode("utf-8").split("\0") if name]
    generated = generated_changes(repo, base, head, changed)
    scanner = scan_secrets(repo, base, head, executable)
    patch = git(
        repo,
        "diff",
        "--no-ext-diff",
        "--no-textconv",
        "--no-renames",
        "--no-color",
        "--binary",
        base,
        head,
        "--",
    )
    if resolve_revision(repo, "HEAD") != head or git(repo, "status", "--porcelain=v1", "-z"):
        raise IntegrityError("Checkout changed during integrity verification")
    return {
        "schemaVersion": 1,
        "scope": "repository-integrity",
        "base": base,
        "head": head,
        "complete": not generated and scanner["status"] == "passed",
        "changeSetSha256": hashlib.sha256(patch).hexdigest(),
        "changedFiles": changed,
        "generatedFilesChanged": generated,
        "secretScan": scanner,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--base", required=True, help="Immutable base commit")
    parser.add_argument("--head", default="HEAD", help="Immutable head commit; default HEAD")
    parser.add_argument("--gitleaks", default="gitleaks", help="Installed Gitleaks executable")
    parser.add_argument("--output", type=Path, required=True, help="New evidence file outside checkout")
    args = parser.parse_args(argv)
    if args.output.exists() or args.output.resolve().is_relative_to(args.repo.resolve()):
        parser.error("--output must be a new file outside the checkout")
    try:
        report = verify(args.repo, args.base, args.head, args.gitleaks)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(json.dumps(report, indent=2) + "\n")
    except (IntegrityError, OSError, UnicodeError) as error:
        print(f"Integrity verification failed: {error}", file=sys.stderr)
        return 1
    if not report["complete"]:
        print(
            f"Integrity failed: {len(report['generatedFilesChanged'])} generated-file edits; "
            f"{len(report['secretScan']['alerts'])} secret alerts. See the location-only result."
        )
        return 1
    print("Committed change set passed generated-file and Gitleaks checks.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
