"""Black-box tests for the Assurance v2 legacy coverage comparison."""

import json
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
COMMAND = ROOT / "build" / "assurance" / "compare.py"
LEGACY = ROOT / "tests" / "conformance" / "catalog.json"
PLAN = ROOT / "tests" / "assurance" / "plan.json"


class AssuranceComparisonTests(unittest.TestCase):
    """Verify the comparison report through its command-line boundary."""

    def run_command(
        self, legacy: Path, plan: Path, output: Path
    ) -> subprocess.CompletedProcess[str]:
        """Run the public comparison command with explicit input and output paths."""
        return subprocess.run(
            [
                sys.executable,
                str(COMMAND),
                "--legacy",
                str(legacy),
                "--plan",
                str(plan),
                "--output",
                str(output),
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_repository_comparison_is_deterministic_and_maps_every_qualified_family(
        self,
    ) -> None:
        """Account for every legacy case in the qualified-family migration scope."""
        with tempfile.TemporaryDirectory() as temporary:
            first_path = Path(temporary) / "first.json"
            second_path = Path(temporary) / "second.json"

            first = self.run_command(LEGACY, PLAN, first_path)
            second = self.run_command(LEGACY, PLAN, second_path)

            self.assertEqual(0, first.returncode, first.stderr)
            self.assertEqual(first_path.read_bytes(), second_path.read_bytes())
            report = json.loads(first_path.read_text(encoding="utf-8"))

        self.assertTrue(report["equivalent"])
        self.assertEqual("mapped-equivalent", report["comparison_status"])
        bsa069_retirements = [
            retirement
            for retirement in report["retired_legacy_cases"]
            if retirement["family"] == "bsa-069"
        ]
        self.assertEqual(
            ["bsa-067", "bsa-068", "bsa-069", "tes3"],
            report["scope_families"],
        )
        self.assertEqual(
            {"bsa-067": 32, "bsa-068": 52, "bsa-069": 32, "tes3": 39},
            report["legacy_case_counts"],
        )
        self.assertFalse(
            any(scenario.startswith("bsa-069:") for scenario in report["v2_incomplete_scenario_ids"])
        )
        self.assertEqual(
            {
                "bsa-069:decode-entries",
                "bsa-069:embedded-name-round-trip",
                "bsa-069:lz4-frame-round-trip",
                "bsa-069:malformed-input",
                "bsa-069:oracle-differential",
                "bsa-069:performance-checkpoint",
                "bsa-069:sse-cli-selection",
                "bsa-069:sse-flags",
                "bsa-069:sse-layout-padding",
                "bsa-069:sse-lz4-bounded-resources",
                "bsa-069:sse-lz4-cancellation",
                "bsa-069:sse-sharing-splitting-ordering",
                "bsa-069:stored-mixed-round-trip",
                "bsa-069:unsafe-extraction",
                "bsa-069:unsupported-codec-decode",
                "bsa-069:unsupported-codec-encode",
            },
            {
                scenario
                for scenario in report["v2_executable_scenario_ids"]
                if scenario.startswith("bsa-069:")
            },
        )
        bsa069 = next(
            family for family in report["families"] if family["family"] == "bsa-069"
        )
        self.assertEqual("mapped-equivalent", bsa069["comparison_status"])
        self.assertTrue(bsa069["equivalent"])
        self.assertEqual(32, bsa069["legacy_case_count"])
        self.assertEqual(31, bsa069["mapped_legacy_case_count"])
        self.assertEqual(1, bsa069["retired_legacy_case_count"])
        self.assertEqual(32, bsa069["accounted_legacy_case_count"])
        self.assertEqual(0, bsa069["unmapped_legacy_case_count"])
        self.assertFalse(
            any(gap["family"] == "bsa-069" for gap in report["gaps"])
        )
        self.assertFalse(
            any(case["family"] == "bsa-069" for case in report["unmapped_legacy_cases"])
        )
        self.assertEqual(
            [
                {
                    "case_id": "CV1-bsa-069.decode.malformed-harmless-trailing-bytes.stored.standard-v1",
                    "family": "bsa-069",
                    "reason": "versioned BSA has no archive-level trailing-byte requirement",
                }
            ],
            bsa069_retirements,
        )
        tes3_retirements = [
            retirement
            for retirement in report["retired_legacy_cases"]
            if retirement["family"] == "tes3"
        ]
        self.assertEqual(5, len(tes3_retirements))
        self.assertTrue(
            all(
                retirement["reason"]
                == "TES3 has no decode-time codec selection or compression marker"
                for retirement in tes3_retirements
            )
        )
        self.assertFalse(report["unmapped_legacy_cases"])
        self.assertFalse(report["gaps"])
        self.assertTrue(all(family["equivalent"] for family in report["families"]))

    def test_classifies_supported_archetypes_and_excludes_out_of_scope_cases(self) -> None:
        """Map justified legacy behavior signatures without importing other families."""
        cases = [
            self.legacy_case("decode-accept", "bsa-067", "decode", "base", "zlib", "accept"),
            self.legacy_case("encode-zlib", "bsa-067", "encode", "base", "zlib", "accept"),
            self.legacy_case(
                "malformed",
                "bsa-067",
                "decode",
                "bsa-067-malformed-header-v1",
                "stored",
                "assert-specified-outcome",
            ),
            self.legacy_case(
                "unsupported", "bsa-067", "encode", "base", "raw-lz4", "reject"
            ),
            self.legacy_case(
                "bsa069-lz4", "bsa-069", "encode", "base", "lz4-frame", "accept"
            ),
            self.legacy_case("out-of-scope", "fo4-gnrl-v1", "decode", "base", "zlib", "accept"),
        ]
        catalog = {"schema_version": 1, "contract": "conformance-v1", "cases": cases}

        with tempfile.TemporaryDirectory() as temporary:
            legacy_path = Path(temporary) / "legacy.json"
            plan_path = Path(temporary) / "plan.json"
            output_path = Path(temporary) / "comparison.json"
            legacy_path.write_text(json.dumps(catalog), encoding="utf-8")
            plan = json.loads(PLAN.read_text(encoding="utf-8"))
            plan["legacy_conformance_digest"] = (
                "sha256:" + hashlib.sha256(legacy_path.read_bytes()).hexdigest()
            )
            plan_path.write_text(json.dumps(plan), encoding="utf-8")

            result = self.run_command(legacy_path, plan_path, output_path)

            self.assertEqual(0, result.returncode, result.stderr)
            report = json.loads(output_path.read_text(encoding="utf-8"))

        mappings = {
            item["assurance_scenario_id"]: item for item in report["mapped_scenario_archetypes"]
        }
        self.assertEqual(["decode-accept"], mappings["bsa-067:decode-entries"]["legacy_case_ids"])
        self.assertEqual(["encode-zlib"], mappings["bsa-067:zlib-round-trip"]["legacy_case_ids"])
        self.assertIn("bsa-067:malformed-input", mappings)
        self.assertEqual(["malformed"], mappings["bsa-067:malformed-input"]["legacy_case_ids"])
        self.assertEqual(["bsa069-lz4"], mappings["bsa-069:lz4-frame-round-trip"]["legacy_case_ids"])
        self.assertEqual(
            [{"expected_behavior": "accept", "operation": "encode"}],
            mappings["bsa-067:zlib-round-trip"]["legacy_behavior_signatures"],
        )
        self.assertEqual(
            [
                {
                    "expected_behavior": "reject",
                    "operation": "encode",
                }
            ],
            mappings["bsa-067:unsupported-codec-encode"]["legacy_behavior_signatures"],
        )
        self.assertFalse(report["unmapped_legacy_cases"])
        self.assertFalse(any("out-of-scope" in str(item) for item in report.values()))

    def test_rejects_a_legacy_catalog_outside_the_reviewed_digest(self) -> None:
        """Prevent future Conformance Cases from silently inheriting consolidation rules."""
        with tempfile.TemporaryDirectory() as temporary:
            legacy_path = Path(temporary) / "legacy.json"
            output_path = Path(temporary) / "comparison.json"
            catalog = json.loads(LEGACY.read_text(encoding="utf-8"))
            catalog["cases"].append(
                self.legacy_case(
                    "new-unreviewed-case",
                    "tes3",
                    "scenario",
                    "new-unreviewed-behavior",
                    "stored",
                    "assert-specified-outcome",
                )
            )
            legacy_path.write_text(json.dumps(catalog), encoding="utf-8")

            result = self.run_command(legacy_path, PLAN, output_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("legacy catalog digest does not match reviewed input", result.stderr)
            self.assertFalse(output_path.exists())

    def test_malformed_json_exits_two_without_publishing_output(self) -> None:
        """Reject unreadable comparison inputs without leaving a plausible report."""
        with tempfile.TemporaryDirectory() as temporary:
            legacy_path = Path(temporary) / "legacy.json"
            output_path = Path(temporary) / "comparison.json"
            legacy_path.write_text("{not-json", encoding="utf-8")

            result = self.run_command(legacy_path, PLAN, output_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("assurance comparison invalid:", result.stderr)
            self.assertFalse(output_path.exists())

    def test_structurally_invalid_case_exits_two_without_publishing_output(self) -> None:
        """Reject cases missing behavioral identity fields before comparison."""
        catalog = {
            "schema_version": 1,
            "contract": "conformance-v1",
            "cases": [{"identity": {"case_id": "broken"}, "metadata": {}}],
        }
        with tempfile.TemporaryDirectory() as temporary:
            legacy_path = Path(temporary) / "legacy.json"
            output_path = Path(temporary) / "comparison.json"
            legacy_path.write_text(json.dumps(catalog), encoding="utf-8")

            result = self.run_command(legacy_path, PLAN, output_path)

            self.assertEqual(2, result.returncode)
            self.assertIn("legacy case broken is missing archive_family", result.stderr)
            self.assertFalse(output_path.exists())

    @staticmethod
    def legacy_case(
        case_id: str,
        family: str,
        operation: str,
        fixture: str,
        codec: str,
        expected_behavior: str,
    ) -> dict[str, object]:
        """Build one complete minimal legacy case for black-box input fixtures."""
        return {
            "identity": {
                "case_id": case_id,
                "archive_family": family,
                "operation": operation,
                "fixture": fixture,
                "codec": codec,
            },
            "metadata": {"expected_behavior": expected_behavior},
        }


if __name__ == "__main__":
    unittest.main()
