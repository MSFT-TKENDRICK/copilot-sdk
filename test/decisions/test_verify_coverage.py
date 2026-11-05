import contextlib
import copy
import io
import json
import tempfile
import unittest
from pathlib import Path

import verify_coverage as coverage


class CoverageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="decisions-census-tests-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fixtures = self.root / "fixtures"
        self.fixtures.mkdir()
        for filename, groups in coverage.GROUPS.items():
            document = {group: [{"name": f"{group}_case"}] for group in sorted(groups)}
            self.save(self.fixtures / filename, document)
        self.expected, _ = coverage.expected_cases(self.fixtures)

    def save(self, path, document):
        path.write_text(json.dumps(document), encoding="utf-8")
        return path

    def census(self, sdk="node"):
        return {
            "schemaVersion": 1,
            "sdk": sdk,
            "cases": [
                {"file": file, "group": group, "name": name, "status": "passed"}
                for file, group, name in sorted(self.expected)
            ],
        }

    def test_complete_single_census(self):
        self.assertEqual(coverage.validate_census(self.census(), self.expected), ("node", []))

    def test_each_missing_case_is_rejected(self):
        for index in range(len(self.expected)):
            document = self.census()
            del document["cases"][index]
            self.assertTrue(coverage.validate_census(document, self.expected)[1])

    def test_duplicate_case_is_not_additional_coverage(self):
        document = self.census()
        document["cases"].append(copy.deepcopy(document["cases"][0]))
        self.assertTrue(coverage.validate_census(document, self.expected)[1])

    def test_unknown_file_group_or_name_is_rejected(self):
        for field in ("file", "group", "name"):
            with self.subTest(field=field):
                document = self.census()
                document["cases"][0][field] = "invented"
                self.assertTrue(coverage.validate_census(document, self.expected)[1])

    def test_skip_fail_and_enumeration_are_not_passes(self):
        for status in ("skipped", "failed", "enumerated", "PASS", "", True, None):
            with self.subTest(status=status):
                document = self.census()
                document["cases"][0]["status"] = status
                self.assertTrue(coverage.validate_census(document, self.expected)[1])

    def test_malformed_census_shapes(self):
        for document in (None, [], "object", 1, True, {}, {"cases": []}):
            self.assertTrue(coverage.validate_census(document, self.expected)[1])

    def test_schema_version_is_not_a_boolean_or_float(self):
        for version in (True, 1.0, "1", None, 0, 2):
            document = self.census()
            document["schemaVersion"] = version
            self.assertTrue(coverage.validate_census(document, self.expected)[1])

    def test_unknown_top_level_fields_are_rejected(self):
        document = self.census()
        document["passed"] = True
        self.assertTrue(coverage.validate_census(document, self.expected)[1])

    def test_invalid_sdk_shapes(self):
        for sdk in ("unknown", "Node", None, [], {}, True):
            document = self.census()
            document["sdk"] = sdk
            self.assertTrue(coverage.validate_census(document, self.expected)[1])

    def test_invalid_case_shapes(self):
        for case in (None, [], "case", {}, {**self.census()["cases"][0], "extra": 1}):
            document = self.census()
            document["cases"][0] = case
            self.assertTrue(coverage.validate_census(document, self.expected)[1])

    def test_invalid_identity_fields_do_not_crash(self):
        for field in ("file", "group", "name"):
            for value in (None, {}, [], True, 5):
                document = self.census()
                document["cases"][0][field] = value
                self.assertTrue(coverage.validate_census(document, self.expected)[1])

    def test_complete_six_sdk_matrix(self):
        paths = [
            self.save(self.root / f"{sdk}.json", self.census(sdk))
            for sdk in coverage.SDKS
        ]
        report = coverage.verify(paths, self.fixtures, set(coverage.SDKS))
        self.assertTrue(report["complete"])
        self.assertEqual(report["casesPerSdk"], 11)
        self.assertEqual(len(report["censuses"]), 6)
        self.assertIn("separately required", report["scope"])
        self.assertTrue(all(len(value) == 64 for value in report["fixtureSha256"].values()))

    def test_missing_sdk_fails_even_when_other_censuses_pass(self):
        paths = [self.save(self.root / "node.json", self.census())]
        self.assertFalse(coverage.verify(paths, self.fixtures, set(coverage.SDKS))["complete"])

    def test_duplicate_sdk_census_is_rejected(self):
        path = self.save(self.root / "node.json", self.census())
        self.assertFalse(coverage.verify([path, path], self.fixtures, {"node"})["complete"])

    def test_unexpected_sdk_is_rejected(self):
        path = self.save(self.root / "node.json", self.census())
        self.assertFalse(coverage.verify([path], self.fixtures, {"go"})["complete"])

    def test_partial_census_prevents_matrix_completion(self):
        document = self.census()
        document["cases"].pop()
        path = self.save(self.root / "node.json", document)
        self.assertFalse(coverage.verify([path], self.fixtures, {"node"})["complete"])

    def test_missing_file_prevents_completion(self):
        report = coverage.verify([self.root / "absent.json"], self.fixtures, {"node"})
        self.assertFalse(report["complete"])
        self.assertTrue(report["errors"])

    def test_invalid_required_sdk_sets(self):
        for sdks in (set(), {"unknown"}):
            with self.assertRaises(coverage.InvalidEvidence):
                coverage.verify([], self.fixtures, sdks)

    def test_duplicate_json_keys_are_rejected_at_any_depth(self):
        for content in ('{"sdk":"go","sdk":"node"}', '{"cases":[{"name":"a","name":"b"}]}'):
            path = self.root / "duplicate.json"
            path.write_text(content, encoding="utf-8")
            with self.assertRaises(coverage.InvalidEvidence):
                coverage.read_json(path)

    def test_nonstandard_json_constants_are_rejected(self):
        path = self.root / "nonfinite.json"
        for constant in ("NaN", "Infinity", "-Infinity"):
            path.write_text('{"value":' + constant + "}", encoding="utf-8")
            with self.assertRaises(coverage.InvalidEvidence):
                coverage.read_json(path)

    def test_invalid_encoding_and_oversized_evidence_fail(self):
        path = self.root / "invalid.json"
        path.write_bytes(b"\xff")
        with self.assertRaises(coverage.InvalidEvidence):
            coverage.read_json(path)
        path.write_bytes(b" " * (coverage.MAX_JSON_BYTES + 1))
        with self.assertRaises(coverage.InvalidEvidence):
            coverage.read_json(path)

    def test_bom_is_supported_and_raw_bytes_are_hashed(self):
        plain = self.save(self.root / "plain.json", {"safe": True})
        bom = self.root / "bom.json"
        bom.write_bytes(b"\xef\xbb\xbf" + plain.read_bytes())
        plain_doc, plain_hash = coverage.read_json(plain)
        bom_doc, bom_hash = coverage.read_json(bom)
        self.assertEqual(plain_doc, bom_doc)
        self.assertNotEqual(plain_hash, bom_hash)

    def test_missing_empty_and_unknown_fixture_groups_fail(self):
        path = self.fixtures / "vectors.json"
        original = json.loads(path.read_text())
        for mode in ("missing", "empty", "unknown"):
            document = copy.deepcopy(original)
            if mode == "missing":
                del document["url"]
            elif mode == "empty":
                document["url"] = []
            else:
                document["invented"] = [{"name": "not_executed"}]
            self.save(path, document)
            with self.assertRaises(coverage.InvalidEvidence):
                coverage.expected_cases(self.fixtures)

    def test_duplicate_fixture_identity_fails(self):
        path = self.fixtures / "vectors.json"
        document = json.loads(path.read_text())
        document["url"].append(copy.deepcopy(document["url"][0]))
        self.save(path, document)
        with self.assertRaises(coverage.InvalidEvidence):
            coverage.expected_cases(self.fixtures)

    def test_repository_corpus_has_expected_groups(self):
        expected, hashes = coverage.expected_cases(coverage.DEFAULT_FIXTURES)
        self.assertGreaterEqual(len(expected), 133)
        self.assertEqual(set(hashes), set(coverage.GROUPS))

    def test_cli_nonzero_on_missing_sdk_and_writes_failure_report(self):
        path = self.save(self.root / "node.json", self.census())
        output = self.root / "report.json"
        with contextlib.redirect_stderr(io.StringIO()):
            result = coverage.main(
                [str(path), "--fixtures", str(self.fixtures), "--output", str(output)]
            )
        self.assertEqual(result, 1)
        self.assertFalse(json.loads(output.read_text())["complete"])

    def test_cli_single_sdk_success(self):
        path = self.save(self.root / "node.json", self.census())
        with contextlib.redirect_stdout(io.StringIO()):
            result = coverage.main([str(path), "--sdk", "node", "--fixtures", str(self.fixtures)])
        self.assertEqual(result, 0)

    def test_cli_cannot_overwrite_inputs(self):
        path = self.save(self.root / "node.json", self.census())
        for output in (path, self.fixtures / "vectors.json"):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                coverage.main(
                    [str(path), "--fixtures", str(self.fixtures), "--output", str(output)]
                )


if __name__ == "__main__":
    unittest.main()
