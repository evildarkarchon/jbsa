"""Black-box tests for canonical Assurance v2 Evidence Capsules."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
COMMAND = ROOT / "build" / "assurance" / "capsule.py"


class EvidenceCapsuleTests(unittest.TestCase):
    """Verify session identity and result completeness through the public CLI."""

    def run_capsule(
        self,
        directory: Path,
        results: list[dict[str, object]],
    ) -> tuple[subprocess.CompletedProcess[str], Path]:
        """Run capsule creation with one complete selected Assurance Scenario."""
        selection = {
            "requested_tier": "full",
            "selected_tier": "full",
            "selection_reason": "full",
            "plan_digest": "sha256:" + "1" * 64,
            "assurance_scenarios": [
                {
                    "assurance_scenario_id": "tes3:decode-entries",
                    "semantic_expectation_id": "sha256:" + "2" * 64,
                    "test_selectors": ["example.Test#passes"],
                }
            ],
            "performance_lanes": [],
        }
        session = {
            "candidate": "sha256:" + "3" * 64,
            "runtime": "windows-x64",
            "jvm": "temurin-25.0.4.1+1",
            "profile": "sha256:" + "4" * 64,
            "corpus": "sha256:" + "5" * 64,
            "protocol": "sha256:" + "6" * 64,
            "specification": "0.17.0",
            "toolchain": "gradle-9.7.1",
            "platform": "windows-2025",
            "provider": "jdk-zlib+lwjgl-lz4",
            "generator": "sha256:" + "7" * 64,
        }
        selection_path = directory / "selection.json"
        session_path = directory / "session.json"
        results_path = directory / "results.json"
        output_path = directory / "capsule.json"
        selection_path.write_text(json.dumps(selection), encoding="utf-8")
        session_path.write_text(json.dumps(session), encoding="utf-8")
        results_path.write_text(json.dumps({"results": results}), encoding="utf-8")
        process = subprocess.run(
            [
                sys.executable,
                str(COMMAND),
                "--selection",
                str(selection_path),
                "--session",
                str(session_path),
                "--results",
                str(results_path),
                "--output",
                str(output_path),
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        return process, output_path

    def test_writes_deterministic_pass_capsule_with_one_session_identity(self) -> None:
        """Bind selected results to one validated session and content address the capsule."""
        result = {
            "id": "tes3:decode-entries",
            "outcome": "PASS",
            "evidence": ["target/test-results/tes3.xml"],
        }
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            first, first_path = self.run_capsule(directory, [result])
            first_bytes = first_path.read_bytes()
            second, second_path = self.run_capsule(directory, [result])
            second_bytes = second_path.read_bytes()
            capsule = json.loads(second_bytes)

        self.assertEqual(0, first.returncode, first.stderr)
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual(first_bytes, second_bytes)
        self.assertEqual("PASS", capsule["outcome"])
        self.assertEqual("assurance-v2-evidence-capsule-v1", capsule["version"])
        self.assertRegex(capsule["capsule_digest"], r"^sha256:[0-9a-f]{64}$")
        self.assertEqual("temurin-25.0.4.1+1", capsule["session_identity"]["jvm"])

    def test_rejects_missing_duplicate_and_foreign_results(self) -> None:
        """Reject result sets that cannot account exactly once for every selection."""
        invalid_results = (
            [],
            [
                {"id": "tes3:decode-entries", "outcome": "PASS", "evidence": ["a"]},
                {"id": "tes3:decode-entries", "outcome": "PASS", "evidence": ["b"]},
            ],
            [{"id": "foreign:scenario", "outcome": "PASS", "evidence": ["a"]}],
        )
        for results in invalid_results:
            with self.subTest(results=results), tempfile.TemporaryDirectory() as temporary:
                process, output = self.run_capsule(Path(temporary), results)
                self.assertEqual(2, process.returncode)
                self.assertIn("evidence capsule invalid:", process.stderr)
                self.assertFalse(output.exists())

    def test_fail_or_invalid_result_publishes_nonpassing_capsule(self) -> None:
        """Retain diagnosable nonpassing evidence without reporting a successful tier."""
        for outcome in ("FAIL", "INVALID"):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as temporary:
                process, output = self.run_capsule(
                    Path(temporary),
                    [{"id": "tes3:decode-entries", "outcome": outcome, "evidence": ["a"]}],
                )
                capsule = json.loads(output.read_text(encoding="utf-8"))
                self.assertEqual(1, process.returncode)
                self.assertEqual(outcome, capsule["outcome"])

    def test_retained_issue45_local_capsule_is_content_addressed_and_complete(self) -> None:
        """Retain the accepted local Starfield oracle/performance session as canonical evidence."""
        self.assert_retained_capsule("issue45-local-capsule.json", 30)

    def test_retained_issue46_local_capsule_is_content_addressed_and_complete(self) -> None:
        """Retain the Starfield DDS oracle and targeted performance session as evidence."""
        self.assert_retained_capsule("issue46-local-capsule.json", 62)

    def test_retained_issue47_local_capsule_is_content_addressed_and_complete(self) -> None:
        """Retain the qualified v8 General and v7/v8 DDS decode-only session as evidence."""
        self.assert_retained_capsule("issue47-local-capsule.json", 40)

    def assert_retained_capsule(self, name: str, scenario_count: int) -> None:
        """Verify one committed local capsule's digest, outcomes, scope, and runtime identity."""
        path = ROOT / "tests" / "assurance" / name
        capsule = json.loads(path.read_text(encoding="utf-8"))
        digest = capsule.pop("capsule_digest")
        encoded = json.dumps(
            capsule, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        self.assertEqual("sha256:" + hashlib.sha256(encoded).hexdigest(), digest)
        self.assertEqual("PASS", capsule["outcome"])
        self.assertEqual("local", capsule["environment"])
        self.assertEqual("affected", capsule["selected_tier"])
        self.assertEqual(scenario_count, len(capsule["selected_scenario_ids"]))
        self.assertTrue(
            all(result["outcome"] == "PASS" for result in capsule["results"])
        )
        self.assertEqual(
            'openjdk version "25.0.4.1" 2026-08-18 LTS',
            capsule["session_identity"]["jvm"],
        )


if __name__ == "__main__":
    unittest.main()
