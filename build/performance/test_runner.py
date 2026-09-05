"""Public runner checks use tiny subprocesses and never award qualification."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import runner


class RunnerTest(unittest.TestCase):
    """Exercise executable identity, argument safety, and missing prerequisite refusal."""

    def test_changed_executable_is_never_started(self):
        """Digest mismatch must fail before invocation, including a valid executable path."""
        with self.assertRaises(ValueError):
            runner.bound_file({"path": sys.executable, "sha256": "0" * 64}, Path.cwd())

    def test_process_preserves_space_and_metacharacter_arguments(self):
        """Shell interpretation or hand-quoting would corrupt this single argument."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            observation = runner.observe_process(
                [sys.executable, "-c", "import sys; print(sys.argv[1])", "a space & ; $value"], root, root / "streams", 10)
            self.assertEqual(0, observation["exit_code"])
            self.assertEqual("a space & ; $value", (root / "streams" / "stdout.txt").read_text().strip())
            self.assertGreater(observation["wall_seconds"], 0)

    def test_missing_registration_yields_invalid_json(self):
        """A foundation catalog must not produce a green report without archive behavior."""
        root = Path(__file__).resolve().parents[2]
        out = root / "target" / ("performance-test-" + next(tempfile._get_candidate_names()))
        result = subprocess.run([sys.executable, str(root / "build/performance/runner.py"), "full", "--output", str(out)], capture_output=True, text=True)
        self.assertEqual(1, result.returncode, result.stderr)
        report = json.loads((out / "results.json").read_text())
        self.assertTrue(report["cases"])
        self.assertEqual({"INVALID"}, {case["outcome"] for case in report["cases"]})

    def test_output_failure_in_earlier_round_cannot_be_hidden(self):
        """Evaluating only the final output would lose the first deterministic failure."""
        def output(size):
            """Represent an independently observed output file."""
            return {"output_parts": [{"path": "archive", "length": size, "sha256": "a" * 64}]}
        records = [{"warmup": False, "observations": {"candidate": output(1_100_000), "oracle": output(1_000_000)}},
                   {"warmup": False, "observations": {"candidate": output(1_000_000), "oracle": output(1_000_000)}}]
        result = runner.evaluate_output_rounds(records, stored=True, dds=False, binary=False, baseline_required=False)
        self.assertEqual("FAIL", result["outcome"])

    def test_released_baseline_cannot_be_omitted(self):
        """A caller cannot opt out of regression gates after a baseline is registered."""
        with self.assertRaises(ValueError):
            runner.validate_baseline_selection({"baseline_required": False}, {"current": {"sha256": "a" * 64}})

    def test_conformance_artifacts_must_belong_to_executed_distribution(self):
        """Repeating an old CV1 artifact list must not qualify a different candidate."""
        old = [{"path": "old.jar", "sha256": "a" * 64}]
        tool = {"conformance_artifacts": old, "inventory": [{"path": "new.jar", "sha256": "b" * 64}]}
        with self.assertRaises(ValueError):
            runner.validate_conformance_artifacts(tool, old)

    def test_split_boundary_requires_multiple_published_parts(self):
        """An unsplit archive cannot satisfy the explicitly required 2 GiB crossing lane."""
        with self.assertRaises(ArithmeticError):
            runner.validate_split_output([{"length": 2_147_483_700}])
        runner.validate_split_output([{"length": 2_000_000_000}, {"length": 147_483_700}])


if __name__ == "__main__":
    unittest.main()
