# Decisions verification

These standard-library Python tools verify evidence for the experimental
[Decisions extension](../../docs/features/decisions.md). They do not replace SDK
test runners, establish live provider access, or certify arbitrary sensitive data
as absent.

## Assertion-backed vector census

Both fixture files are required: `vectors.json` contains 86 cases and
`production-vectors.json` contains 47. Every SDK must execute assertions for all
133 identities. Set `COPILOT_DECISIONS_VECTOR_CENSUS` to a new output path before
running that SDK's Decisions tests.

For example, from the repository root in PowerShell, after installing the Node
SDK's existing development dependencies:

```powershell
$env:COPILOT_DECISIONS_VECTOR_CENSUS = Join-Path $env:TEMP "decisions-node-census.json"
Push-Location nodejs
npx --no-install vitest run test\decisions.test.ts
$testExit = $LASTEXITCODE
Pop-Location
if ($testExit -ne 0) { throw "Decisions tests failed; census is not a passing gate" }
python test\decisions\verify_coverage.py --sdk node $env:COPILOT_DECISIONS_VECTOR_CENSUS
```

Use a separate evidence path for each SDK/run, never a shared stale census.
The verifier checks SDK/schema identity, exact case identities, duplicates,
unknown/missing cases, and malformed JSON, and reports raw fixture-byte hashes. By default
it requires all six SDKs; `--sdk node`, `python`, `go`, `dotnet`, `java`, or `rust`
checks only that SDK. For the full gate, pass the six census files.

A complete census can coexist with an unrelated failed test. Always retain the
runner command, source identity, toolchain, timestamps, exit code, and logs
separately. A generated list of fixture names is not assertion-backed evidence.

## Committed-range integrity

Run against a clean checkout after committing the intended changes. Requires Git
with `check-attr --source` support and an installed Gitleaks binary; the validated
scanner version is 8.30.1.

```powershell
python test\decisions\verify_integrity.py `
    --repo C:\path\to\copilot-sdk `
    --base <immutable-feature-base> `
    --gitleaks C:\path\to\gitleaks.exe `
    --output C:\outside-checkout\new-integrity-result.json
```

The output must be a new file outside the checkout. `--head` defaults to `HEAD`;
an explicit head must be checked out. The tool verifies ancestry and cleanliness
before and after the check.

Generated-file detection applies the repository's `linguist-generated`
attributes, SDK filename conventions, and leading generated-source headers at
both revisions. Removed headers and renames cannot hide a generated-file edit.
Marker strings inside a handwritten checker are not generated-file headers.
Unmarked generated content outside these conventions cannot be inferred
automatically.

Gitleaks scans **every commit in `base..head`**, not only the final diff. It uses
explicit default rules, disables inline `gitleaks:allow` comments, and reads exact
fingerprint exclusions from the checked-out commit's `.gitleaksignore`, not an
untracked file or a repository/environment rule override. The single initial
exclusion is a deterministic dummy credential in the historical
`secret_across_truncation_boundary` test vector. It matches one immutable
commit/file/rule/line finding, not future credentials or an entire test path.

Only alert locations and scanner/policy identities are retained; matching secret
text is not printed or copied to the result. Missing tools, timeouts, incomplete
reports, contradictions, generated edits, or remaining alerts fail the check.
Review exact synthetic-fixture findings individually; do not suppress broad paths,
rules, or whole commits to make a scan pass.

## Test the verifiers

From the repository root:

```powershell
python -B -m unittest discover -s test\decisions -p "test_verify_*.py" -q
```

The integrity tests use isolated temporary Git repositories, including real
attribute/header/rename checks. Mocked scanner tests verify orchestration and
error handling only; they are not evidence that the SDK's actual commit range
passed a secret scan.

## Python package smoke

After building a wheel and sdist into a fresh directory, use the SDK's Python
environment (with its declared dependencies installed):

```powershell
python test\decisions\smoke_python_package.py --source python --dist C:\path\to\fresh-dist
```

The check compares the changed Decisions/JSON-RPC modules in both artifacts with
the checkout, checks packaged documentation, and verifies actual wheel import
origins before exercising deadlines, signed integers, question JSON and the
cancelled-RPC guard. It does not substitute for the SDK suite or live calls.

## Evidence boundaries

The [Decisions CI workflow](../../.github/workflows/decisions.yml) is an offline
conformance workflow: it has no live provider secrets or model calls. It exercises
the Node engine floor and packaged ESM/CommonJS consumers, supported Python minor
versions, the Go version floor and Linux race detector, .NET target builds, Java
25 packaging/style and the same Decisions suite on Java 17, and Rust's full offline
library suite, selected shared-JSON integrations and default-feature compilation.
The Java runtime check verifies fresh test reports identify the intended JVM;
Java 17-compatible bytecode alone is not execution evidence. The workflow also
exercises Python's built wheel. Its complete-census job depends on every SDK job
succeeding. Defining this workflow does not mean its clean-run results have passed.

Offline conformance, real-CLI/local-provider integration, SDK mutation testing,
and live OpenAI/Azure execution are distinct gates. A scoring-harness mutation
campaign is not an SDK mutation campaign. A mutant counts as killed only when a
valid selected SDK assertion fails after a green baseline and successful
compilation; setup failures, timeouts, skipped selections, and zero executed
tests do not count.

Live checks require separately authorized provider configuration. Do not paste
credentials into issues or logs, use real keys in fixtures, retry denied
Copilot-managed entitlements, or label a loopback test provider as live evidence.
