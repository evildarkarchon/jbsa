"""Tests for a successful Assurance v2 session recorder and its bound identities."""

import importlib
import hashlib
import json
from pathlib import Path
import shutil
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

    def test_oracle_identity_rejects_modified_record_or_probe_script(self) -> None:
        """Invalidate the capsule when a pinned observation or its generator changes."""
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            script = repository / "build/issue50-oracle-probes.py"
            script.parent.mkdir()
            script.write_text("# frozen probe\n", encoding="utf-8")
            record_path = (
                repository
                / "docs/development/evidence/issue50-automated-conformance/oracle-observations.json"
            )
            record_path.parent.mkdir(parents=True)
            observation = {
                "oracle_sha256": "4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2",
                "probe_script_sha256": hashlib.sha256(b"# frozen probe\n").hexdigest(),
                "probes": [{"id": "first", "exit_status": 0}],
            }
            observation["record_sha256"] = hashlib.sha256(
                json.dumps(observation, sort_keys=True, separators=(",", ":")).encode("utf-8")
            ).hexdigest()
            record_path.write_text(json.dumps(observation), encoding="utf-8")
            with patch.object(sys, "path", [str(COMMAND.parent), *sys.path]):
                record_tool = importlib.import_module("record")

            self.assertIn(
                observation["record_sha256"], record_tool.oracle_identity(repository)
            )
            script.write_text("# changed probe\n", encoding="utf-8")
            with self.assertRaises(record_tool.capsule_tool.CapsuleError):
                record_tool.oracle_identity(repository)
            script.write_text("# frozen probe\n", encoding="utf-8")
            observation["probes"][0]["exit_status"] = 1
            record_path.write_text(json.dumps(observation), encoding="utf-8")
            with self.assertRaises(record_tool.capsule_tool.CapsuleError):
                record_tool.oracle_identity(repository)

    def test_deviation_review_rejects_partial_approval_and_changed_code(self) -> None:
        """Require every in-scope row and revalidation after affected implementation changes."""
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            observation_source = (
                ROOT
                / "docs/development/evidence/issue50-automated-conformance/oracle-observations.json"
            )
            observation_path = repository / observation_source.relative_to(ROOT)
            observation_path.parent.mkdir(parents=True)
            shutil.copyfile(observation_source, observation_path)
            source = repository / "jbsa/src/main/java/Archive.java"
            source.parent.mkdir(parents=True)
            source.write_text("class Archive {}\n", encoding="utf-8")
            (repository / "jbsa-cli/src/main/java").mkdir(parents=True)
            codec = repository / "jbsa/src/main/resources/META-INF/jbsa-codec-profile.json"
            codec.parent.mkdir(parents=True)
            codec.write_text('{"profile":"first"}\n', encoding="utf-8")
            profile = repository / "docs/spec/compatibility-profiles.md"
            profile.parent.mkdir(parents=True)
            shutil.copyfile(ROOT / "docs/spec/compatibility-profiles.md", profile)
            with patch.object(sys, "path", [str(COMMAND.parent), *sys.path]):
                record_tool = importlib.import_module("record")
            review = json.loads(
                (ROOT / "tests/assurance/deviation-review.json").read_text(encoding="utf-8")
            )
            review["state"] = "pending"
            review["approved_deviations"] = []
            review["approval_reference"] = None
            review["implementation_sha256"] = record_tool.digest_files(
                repository,
                [source.parent, repository / "jbsa-cli/src/main/java", codec.parent, profile],
            )
            review_path = repository / "tests/assurance/deviation-review.json"
            review_path.parent.mkdir(parents=True)
            review_path.write_text(json.dumps(review), encoding="utf-8")
            self.assertEqual("pending", record_tool.deviation_review_status(repository))
            reports = repository / "reports"
            reports.mkdir()
            (reports / "TEST-example.Test.xml").write_text(
                '<testsuite><testcase classname="example.Test" name="passes()"/></testsuite>',
                encoding="utf-8",
            )
            selection = {
                "assurance_scenarios": [
                    {
                        "assurance_scenario_id": "shared-core:bsarch-v1-cli",
                        "test_selectors": ["example.Test#passes"],
                    }
                ],
                "performance_lanes": [],
            }
            pending = record_tool.results_from_reports(selection, [reports], repository)
            self.assertEqual("INVALID", pending["results"][0]["outcome"])
            self.assertIn("deviation-review:pending", pending["results"][0]["evidence"])

            review["state"] = "approved"
            review["approved_deviations"] = review["required_deviations"].copy()
            review["approval_reference"] = "issue50 maintainer approval"
            review_path.write_text(json.dumps(review), encoding="utf-8")
            self.assertEqual("approved", record_tool.deviation_review_status(repository))
            approved = record_tool.results_from_reports(selection, [reports], repository)
            self.assertEqual("PASS", approved["results"][0]["outcome"])

            review["approved_deviations"].pop()
            review_path.write_text(json.dumps(review), encoding="utf-8")
            self.assertEqual("incomplete", record_tool.deviation_review_status(repository))
            review["approved_deviations"] = review["required_deviations"].copy()
            review_path.write_text(json.dumps(review), encoding="utf-8")
            source.write_text("class Archive { int changed; }\n", encoding="utf-8")
            self.assertEqual("stale", record_tool.deviation_review_status(repository))
            source.write_text("class Archive {}\n", encoding="utf-8")
            codec.write_text('{"profile":"changed"}\n', encoding="utf-8")
            self.assertEqual("stale", record_tool.deviation_review_status(repository))

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
                patch.object(record_tool, "candidate_commit", return_value="a" * 40),
                patch.object(record_tool, "command_identity", return_value="java"),
                patch.object(record_tool, "oracle_identity", return_value="pinned-oracle"),
            ):
                first = record_tool.session_identity(repository, repository / "plan.json", "java")
                behavior.write_text("Changed obligation\n", encoding="utf-8")
                second = record_tool.session_identity(repository, repository / "plan.json", "java")

        self.assertRegex(first["specification"], r"^0\.17\.0@sha256:[0-9a-f]{64}$")
        self.assertNotEqual(first["specification"], second["specification"])

    def test_records_every_full_tier_result_once(self) -> None:
        """Create a PASS capsule after selectors and deviation approval both pass."""
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
        self.assertRegex(capsule["session_identity"]["candidate_commit"], r"^[0-9a-f]{40}$")
        self.assertRegex(
            capsule["session_identity"]["oracle"],
            r"^pinned-sha256:[0-9a-f]{64}@observation-sha256:[0-9a-f]{64}$",
        )
        self.assertRegex(capsule["session_identity"]["validator"], r"^sha256:[0-9a-f]{64}$")
        profile_result = next(
            result
            for result in capsule["results"]
            if result["id"] == "shared-core:bsarch-v1-cli"
        )
        self.assertIn(
            "docs/development/evidence/issue50-automated-conformance/oracle-observations.json",
            profile_result["evidence"],
        )
        self.assertIn("deviation-review:approved", profile_result["evidence"])


if __name__ == "__main__":
    unittest.main()
