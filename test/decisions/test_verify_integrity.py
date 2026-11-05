import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import verify_integrity as integrity


class IntegrityTests(unittest.TestCase):
    def test_generated_names_cover_sdk_conventions(self):
        for name in (
            "src/Generated/Rpc.cs",
            "src/generated/types.ts",
            "copilot/_generated/rpc.py",
            "rpc_generated.go",
            "src/generated.rs",
            "src/rpc.gen.go",
            "src/rpc.generated.ts",
        ):
            with self.subTest(name=name):
                self.assertTrue(integrity.generated_by_name(name))
        for name in ("src/decisions.ts", "src/generator.py", "test/decisions_test.go"):
            self.assertFalse(integrity.generated_by_name(name))

    def test_only_immutable_revisions_or_head_are_allowed(self):
        for revision in ("main", "--help", "", "a" * 41, "abc", "HEAD~1"):
            with self.subTest(revision=revision):
                with self.assertRaises(integrity.IntegrityError):
                    integrity.resolve_revision(Path("."), revision)
        with patch.object(integrity, "git", return_value=("a" * 40).encode()):
            self.assertEqual(integrity.resolve_revision(Path("."), "abcdef0"), "a" * 40)
            self.assertEqual(integrity.resolve_revision(Path("."), "HEAD"), "a" * 40)

    def test_generated_marker_is_checked_in_both_revisions(self):
        names = ["src/old.ts", "src/new.ts", "src/normal.ts"]
        attributes = "".join(
            f"{name}\0linguist-generated\0unspecified\0" for name in names
        ).encode()
        with patch.object(
            integrity,
            "git",
            side_effect=[
                attributes,
                b"a:src/old.ts\0",
                b"// Code generated. DO NOT EDIT.\nexport const value = 1;\n",
                attributes,
                b"b:src/new.ts\0",
                b"/* Auto-generated */\nexport const value = 2;\n",
            ],
        ):
            self.assertEqual(
                integrity.generated_changes(
                    Path("."), "a", "b", names
                ),
                ["src/new.ts", "src/old.ts"],
            )

    def test_generated_attributes_cover_flags_and_explicit_values(self):
        with patch.object(
            integrity,
            "git",
            return_value=(
                b"a\0linguist-generated\0true\0"
                b"b\0linguist-generated\0set\0"
                b"c\0linguist-generated\0false\0"
            ),
        ):
            self.assertEqual(
                integrity.generated_attributes(Path("."), "a" * 40, ["a", "b", "c"]),
                {"a", "b"},
            )

    def test_generated_attributes_fail_closed_on_malformed_output(self):
        for raw in (
            b"",
            b"a\0linguist-generated\0true",
            b"unknown\0linguist-generated\0true\0",
            b"a\0other-attribute\0true\0",
        ):
            with self.subTest(raw=raw):
                with patch.object(integrity, "git", return_value=raw):
                    with self.assertRaises(integrity.IntegrityError):
                        integrity.generated_attributes(Path("."), "a" * 40, ["a"])

    def test_generated_headers_cover_comment_styles(self):
        for source in (
            b"// Copyright\n// Code generated. DO NOT EDIT.\npackage rpc\n",
            b"\xef\xbb\xbf# Copyright\n# Auto-generated\nimport json\n",
            b"/* Copyright */\n/**\n * This file is auto-generated.\n */\nclass Rpc {}\n",
            b'"""Auto-generated RPC definitions."""\nimport json\n',
            b"'''\nDO NOT EDIT\n'''\nimport json\n",
            b"/* Copyright */ /* DO NOT EDIT */ class Rpc {}\n",
        ):
            with self.subTest(source=source):
                self.assertTrue(integrity.generated_header(source))

    def test_marker_literals_and_later_comments_are_not_generated_headers(self):
        for source in (
            b'PATTERN = "DO NOT EDIT|Code generated|Auto-generated"\n',
            b'// A handwritten tool.\nconst marker = "DO NOT EDIT";\n',
            b'"""A handwritten checker."""\nPATTERN = "Code generated"\n',
            b"/* A handwritten checker. */ const marker = 'DO NOT EDIT';\n",
            b"def check():\n    # DO NOT EDIT the input while checking it.\n    pass\n",
        ):
            with self.subTest(source=source):
                self.assertFalse(integrity.generated_header(source))

    def test_real_git_range_detects_removed_and_renamed_headers_not_literals(self):
        with tempfile.TemporaryDirectory(prefix="decisions-integrity-test-") as directory:
            repo = Path(directory)

            def run(*args):
                result = subprocess.run(
                    ["git", "--no-pager", "-C", str(repo), *args],
                    capture_output=True,
                    check=True,
                    timeout=60,
                )
                return result.stdout.decode().strip()

            def write(name, content):
                target = repo / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding="utf-8", newline="\n")

            def commit(message):
                run("add", ".")
                run(
                    "-c", "user.name=Integrity test",
                    "-c", "user.email=integrity@example.invalid",
                    "-c", "commit.gpgsign=false",
                    "commit", "--quiet", "-m",
                    message + "\n\nCo-authored-by: Copilot App "
                    "<223556219+Copilot@users.noreply.github.com>",
                )
                return run("rev-parse", "HEAD")

            run("init", "--quiet", "--template=", "--initial-branch=main")
            run("config", "core.autocrlf", "false")
            run("config", "core.hooksPath", str(repo / "empty-hooks"))
            write(".gitattributes", "src/schema.json linguist-generated=true\n")
            write("src/schema.json", '{"schema": 1}\n')
            write("src/legacy.ts", "// Code generated. DO NOT EDIT.\nexport const n = 1;\n")
            write("src/renamed.ts", "// DO NOT EDIT.\nexport const n = 2;\n")
            write("src/check.py", '"""A handwritten checker."""\nRULE = "DO NOT EDIT"\n')
            base = commit("Baseline fixture")
            write("src/legacy.ts", "export const n = 3;\n")
            (repo / "src/renamed.ts").rename(repo / "src/ordinary.ts")
            write("src/ordinary.ts", "export const n = 2;\n")
            write("src/new.java", "/* Auto-generated */\nclass Rpc {}\n")
            write("src/schema.json", '{"schema": 2}\n')
            write("src/check.py", '"""A handwritten checker."""\nRULE = "Code generated"\n')
            head = commit("Changed fixture")
            with patch.object(
                integrity, "scan_secrets", return_value={"status": "passed", "alerts": []}
            ):
                report = integrity.verify(repo, base, head, "unused-in-this-test")
            self.assertFalse(report["complete"])
            self.assertEqual(
                report["generatedFilesChanged"],
                ["src/legacy.ts", "src/new.java", "src/renamed.ts", "src/schema.json"],
            )
            self.assertIn("src/ordinary.ts", report["changedFiles"])
            self.assertNotIn("src/check.py", report["generatedFilesChanged"])

    def test_alerts_retain_no_matched_text_or_secret(self):
        item = {
            "RuleID": "synthetic-rule",
            "File": "test/example.py",
            "StartLine": 3,
            "Commit": "a" * 40,
            "Secret": "deliberately-synthetic-credential",
            "Match": "deliberately-synthetic-credential",
            "Author": "not needed",
            "Email": "not retained",
        }
        result = integrity.alert_locations(json.dumps([item]).encode())
        self.assertEqual(set(result[0]), {"RuleID", "File", "StartLine", "Commit"})
        self.assertNotIn("deliberately-synthetic-credential", json.dumps(result))

    def test_empty_scanner_result_is_valid(self):
        self.assertEqual(integrity.alert_locations(b"[]"), [])

    def test_malformed_scanner_results_fail_closed(self):
        for value in (
            b"{",
            b'{"findings":[]}',
            b"[true]",
            b'[{"File":"x","File":"y"}]',
            b"[NaN]",
            b"\xff",
            b"[{}]",
        ):
            with self.subTest(value=value):
                with self.assertRaises(integrity.IntegrityError):
                    integrity.alert_locations(value)

    def test_alert_location_types_are_checked(self):
        base = {"RuleID": "test", "File": "test.py", "StartLine": 1, "Commit": "a" * 40}
        for key, value in (
            ("RuleID", ""),
            ("File", []),
            ("StartLine", True),
            ("StartLine", 0),
            ("Commit", "not-a-commit"),
        ):
            with self.subTest(key=key, value=value):
                with self.assertRaises(integrity.IntegrityError):
                    integrity.alert_locations(json.dumps([{**base, key: value}]).encode())

    def test_oversized_scanner_result_is_rejected(self):
        with patch.object(integrity, "MAX_REPORT_BYTES", 3):
            with self.assertRaises(integrity.IntegrityError):
                integrity.alert_locations(b"[]  ")

    def test_missing_scanner_is_not_a_pass(self):
        with patch.object(integrity.subprocess, "run", side_effect=FileNotFoundError):
            with self.assertRaises(integrity.IntegrityError):
                integrity.scan_secrets(Path("."), "a" * 40, "b" * 40, "gitleaks")

    def test_only_exact_committed_fingerprints_are_allowed(self):
        fingerprint = "a" * 40 + ":test/fixture.json:generic-api-key:5"
        raw = ("# A synthetic fixture.\n" + fingerprint + "\n").encode()
        with patch.object(
            integrity, "git", side_effect=[b".gitleaksignore\0", raw]
        ):
            self.assertEqual(
                integrity.committed_ignores(Path("."), "b" * 40), (raw, [fingerprint])
            )

    def test_missing_committed_ignore_file_does_not_read_a_worktree_override(self):
        with patch.object(integrity, "git", return_value=b"") as mocked:
            self.assertEqual(integrity.committed_ignores(Path("."), "a" * 40), (b"", []))
            self.assertEqual(mocked.call_count, 1)

    def test_broad_or_invalid_scanner_exclusions_fail_closed(self):
        valid = "a" * 40 + ":test/fixture.json:generic-api-key:5"
        for value in (
            "*",
            "test/fixture.json",
            "a" * 40 + ":test/*:generic-api-key:5",
            "a" * 40 + ":../fixture.json:generic-api-key:5",
            "a" * 40 + ":/fixture.json:generic-api-key:5",
            "a" * 40 + ":test/fixture.json:generic-api-key:0",
            valid + "\n" + valid,
        ):
            with self.subTest(value=value):
                with patch.object(
                    integrity, "git", side_effect=[b".gitleaksignore\0", value.encode()]
                ):
                    with self.assertRaises(integrity.IntegrityError):
                        integrity.committed_ignores(Path("."), "b" * 40)

    def test_scanner_uses_explicit_default_rules_and_committed_exclusions(self):
        calls = []

        def run(command, **kwargs):
            calls.append(command)
            if command[1] == "version":
                return SimpleNamespace(returncode=0, stdout=b"8.30.1\n")
            config = Path(command[command.index("--config") + 1])
            ignores = Path(command[command.index("--gitleaks-ignore-path") + 1])
            report = Path(command[command.index("--report-path") + 1])
            self.assertEqual(config.read_bytes(), integrity.SCANNER_CONFIG)
            self.assertEqual(ignores.read_bytes(), b"")
            self.assertIn("--ignore-gitleaks-allow", command)
            self.assertIn("--redact=100", command)
            self.assertIn("--log-opts=" + "a" * 40 + ".." + "b" * 40, command)
            report.write_bytes(b"[]")
            return SimpleNamespace(returncode=0, stdout=b"")

        with patch.object(integrity, "git", return_value=b""):
            with patch.object(integrity.subprocess, "run", side_effect=run):
                result = integrity.scan_secrets(Path("."), "a" * 40, "b" * 40, "gitleaks")
        self.assertEqual(len(calls), 2)
        self.assertEqual(result["status"], "passed")
        self.assertEqual(result["policy"]["fingerprints"], [])

    def test_git_errors_do_not_copy_raw_output(self):
        result = type("Result", (), {"returncode": 128, "stdout": b"synthetic-sensitive-text"})()
        with patch.object(integrity.subprocess, "run", return_value=result):
            with self.assertRaises(integrity.IntegrityError) as caught:
                integrity.git(Path("."), "status")
        self.assertNotIn("synthetic-sensitive-text", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
