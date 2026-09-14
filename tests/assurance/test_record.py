"""Black-box test for a successful Assurance v2 session recorder."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from xml.sax.saxutils import escape


ROOT = Path(__file__).resolve().parents[2]
COMMAND = ROOT / "build" / "assurance" / "record.py"


class AssuranceRecordTests(unittest.TestCase):
    """Verify one successful gate produces a complete session-scoped capsule."""

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
