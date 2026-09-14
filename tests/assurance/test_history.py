"""Black-box tests for the frozen CV1 history verifier."""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
HISTORY = ROOT / "build" / "assurance" / "history.py"


class FrozenHistoryTests(unittest.TestCase):
    """Verify immutable legacy evidence through the public command-line boundary."""

    def setUp(self) -> None:
        """Create a committed temporary repository containing frozen and live paths."""
        self.temporary = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary.name)
        self.run_git("init", "--quiet")
        self.run_git("config", "user.email", "assurance@example.invalid")
        self.run_git("config", "user.name", "Assurance Test")
        self.write("tests/conformance/catalog.json", "{}\n")
        self.write("tests/conformance/objects/base.json", "{}\n")
        self.write("tests/conformance/rebaselines/base.json", "{}\n")
        self.write("docs/reviews/base.md", "frozen\n")
        self.write("docs/spec/conformance-v1.md", "frozen conformance\n")
        self.write("docs/spec/performance-v1.md", "frozen performance\n")
        self.write("tests/performance/catalog.json", "{}\n")
        self.write("tests/performance/requirements.json", "{}\n")
        self.write("tests/performance/protocol.json", "{}\n")
        self.write("tests/performance/baselines.json", "{}\n")
        self.write("tests/conformance/README.md", "live\n")
        self.run_git("add", ".")
        self.run_git("commit", "--quiet", "-m", "baseline")
        self.baseline = self.run_git("rev-parse", "HEAD").stdout.strip()

    def tearDown(self) -> None:
        """Dispose the isolated repository after each test."""
        self.temporary.cleanup()

    def run_git(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        """Run Git in the isolated repository and require success."""
        return subprocess.run(
            ["git", *arguments],
            cwd=self.repository,
            capture_output=True,
            text=True,
            check=True,
        )

    def write(self, relative_path: str, content: str) -> None:
        """Write one temporary repository file, creating its parent directories."""
        path = self.repository / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def run_verifier(
        self,
        baseline: str | None = None,
        repository: Path | None = None,
        index: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        """Run the public frozen-history verification command."""
        command = [
                sys.executable,
                str(HISTORY),
                "verify",
                "--repository",
                str(repository or self.repository),
                f"--baseline={baseline or self.baseline}",
            ]
        if index is not None:
            command.extend(("--index", str(index)))
        return subprocess.run(
            command,
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_clean_history_ignores_changes_outside_frozen_paths(self) -> None:
        """Pass when frozen evidence is unchanged even if adjacent live files change."""
        self.write("tests/conformance/README.md", "live and changed\n")
        self.write("docs/spec/live.md", "not frozen\n")

        result = self.run_verifier()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("frozen CV1 history unchanged\n", result.stdout)
        self.assertEqual("", result.stderr)

    def test_snapshot_is_deterministic_and_verifiable(self) -> None:
        """Create a compact digest index that identifies every frozen evidence root."""
        first = self.repository / "first-index.json"
        second = self.repository / "second-index.json"
        commands = [
            subprocess.run(
                [
                    sys.executable,
                    str(HISTORY),
                    "snapshot",
                    "--repository",
                    str(self.repository),
                    "--output",
                    str(output),
                ],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
            for output in (first, second)
        ]

        self.assertTrue(all(command.returncode == 0 for command in commands), commands)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        index = __import__("json").loads(first.read_text(encoding="utf-8"))
        self.assertEqual("assurance-v2-history-index-v1", index["version"])
        self.assertIn("tests/conformance/catalog.json", index["roots"])
        self.assertIn("tests/performance/catalog.json", index["roots"])
        self.assertIn("docs/spec/conformance-v1.md", index["roots"])
        self.assertIn("docs/spec/performance-v1.md", index["roots"])

        verified = self.run_verifier(index=first)
        self.assertEqual(0, verified.returncode, verified.stderr)

    def test_changed_history_lists_only_frozen_paths_in_sorted_order(self) -> None:
        """Fail with every tracked or new substantive frozen path listed once."""
        self.write("tests/conformance/catalog.json", '{"changed":true}\n')
        self.write("docs/reviews/base.md", "changed\n")
        self.write("tests/conformance/objects/base.json", '{"changed":true}\n')
        self.write("tests/conformance/rebaselines/new.json", "{}\n")

        result = self.run_verifier()

        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual(
            "frozen CV1 history changed:\n"
            "docs/reviews/base.md\n"
            "tests/conformance/catalog.json\n"
            "tests/conformance/objects/base.json\n"
            "tests/conformance/rebaselines/new.json\n",
            result.stdout,
        )
        self.assertEqual("", result.stderr)

    def test_invalid_baseline_and_repository_exit_two(self) -> None:
        """Classify invalid revisions and repository paths as validation errors."""
        cases = (
            self.run_verifier(baseline="not-a-revision"),
            self.run_verifier(baseline="--quiet"),
            self.run_verifier(repository=self.repository / "missing"),
        )

        for result in cases:
            with self.subTest(stderr=result.stderr):
                self.assertEqual(2, result.returncode)
                self.assertEqual("", result.stdout)
                self.assertIn("frozen CV1 history invalid:", result.stderr)


if __name__ == "__main__":
    unittest.main()
