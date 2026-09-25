"""Tests for a successful Assurance v2 session recorder and its bound identities."""

import importlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from xml.sax.saxutils import escape


ROOT = Path(__file__).resolve().parents[2]
COMMAND = ROOT / "build" / "assurance" / "record.py"


class AssuranceRecordTests(unittest.TestCase):
    """Verify one successful gate produces a complete session-scoped capsule."""

    def test_specification_digest_orders_mixed_case_paths_portably(self) -> None:
        """Use one path order for identical specification bytes on every platform."""
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            specification = repository / "docs/spec"
            specification.mkdir(parents=True)
            (specification / "Z.md").write_text("Z\n", encoding="utf-8", newline="\n")
            (specification / "a.md").write_text("a\n", encoding="utf-8", newline="\n")
            with patch.object(sys, "path", [str(COMMAND.parent), *sys.path]):
                record_tool = importlib.import_module("record")
            digest = record_tool.digest_files(repository, [specification])

        self.assertEqual(
            "sha256:007403c158144a96402779f4d335c8f157e8ebff7217fcf25d3e9da7036a732a",
            digest,
        )

    def test_specification_identity_changes_with_normative_content(self) -> None:
        """Bind the exact normative files even when the declared version is unchanged."""
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            specification = repository / "docs/spec"
            specification.mkdir(parents=True)
            (specification / "requirements.yaml").write_text(
                "specification:\n  version: 0.17.0\n", encoding="utf-8"
            )
            (specification / "README.md").write_text("Normative framework\n", encoding="utf-8")
            behavior = specification / "assurance-v2.md"
            behavior.write_text("First obligation\n", encoding="utf-8")
            (repository / "tests/fixtures").mkdir(parents=True)

            # Direct CLI imports resolve capsule and plan beside the recorder script.
            with patch.object(sys, "path", [str(COMMAND.parent), *sys.path]):
                record_tool = importlib.import_module("record")
            with (
                patch.object(record_tool, "candidate_digest", return_value="candidate"),
                patch.object(record_tool, "command_identity", return_value="java"),
            ):
                first = record_tool.session_identity(repository, repository / "plan.json", "java")
                behavior.write_text("Changed obligation\n", encoding="utf-8")
                second = record_tool.session_identity(repository, repository / "plan.json", "java")

        self.assertRegex(first["specification"], r"^0\.17\.0@sha256:[0-9a-f]{64}$")
        self.assertNotEqual(first["specification"], second["specification"])

    def test_records_every_full_tier_result_once(self) -> None:
        """Create a PASS capsule after the owning Gradle test graph has succeeded."""
        with tempfile.TemporaryDirectory() as temporary:
            reports = Path(temporary) / "reports"
            reports.mkdir()
            plan = json.loads((ROOT / "tests/assurance/plan.json").read_text(encoding="utf-8"))
            selectors = sorted(
                {
                    selector
                    for scenario in plan["scenarios"]
                    for capability_selectors in scenario["test_selectors"].values()
                    for selector in capability_selectors
                }
            )
            testcases = []
            for selector in selectors:
                class_name, method_name = selector.split("#", 1)
                testcases.append(
                    f'<testcase classname="{escape(class_name)}" name="{escape(method_name)}()"/>'
                )
            (reports / "TEST-assurance.xml").write_text(
                '<testsuite tests="{}">{}</testsuite>'.format(
                    len(testcases), "".join(testcases)
                ),
                encoding="utf-8",
            )
            output = Path(temporary) / "capsule.json"
            result = subprocess.run(
                [
                    sys.executable,
                    str(COMMAND),
                    "--repository",
                    str(ROOT),
                    "--plan",
                    str(ROOT / "tests/assurance/plan.json"),
                    "--tier",
                    "full",
                    "--environment",
                    "hosted",
                    "--reports",
                    str(reports),
                    "--output",
                    str(output),
                ],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            capsule = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual("PASS", capsule["outcome"])
        self.assertEqual(
            len(capsule["selected_scenario_ids"]),
            len(capsule["results"]),
        )
        self.assertEqual(
            len(capsule["selected_scenario_ids"]),
            len(set(capsule["selected_scenario_ids"])),
        )
        self.assertTrue(all(result["outcome"] == "PASS" for result in capsule["results"]))
        self.assertRegex(capsule["session_identity"]["candidate"], r"^sha256:[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main()
