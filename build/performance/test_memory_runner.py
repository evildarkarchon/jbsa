"""Memory dispatch binds the shipping process and its actual scratch/JFR environment."""

from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import runner


class MemoryRunnerTest(unittest.TestCase):
    """Refuse a replacement candidate before launching external instrumentation."""

    def test_rejects_a_memory_executable_outside_the_shipping_identity(self):
        """A pinned but different executable cannot supply candidate memory evidence."""
        launcher = {"path": sys.executable, "sha256": runner.file_digest(sys.executable)}
        tool = {"launcher": launcher, "inventory": [launcher],
                "memory_launcher": {"path": "different.exe", "sha256": "0" * 64}}
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            runner.memory_request([sys.executable, "--version"], tool, Path(directory), 10)

    def test_rejects_a_launcher_missing_from_the_bound_inventory(self):
        """Binding a launcher digest alone does not prove distribution membership."""
        launcher = {"path": sys.executable, "sha256": runner.file_digest(sys.executable)}
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            runner.memory_request([sys.executable], {"launcher": launcher, "inventory": []}, Path(directory), 10)

    def test_preserves_shipping_argv_and_routes_native_scratch(self):
        """Profiling must not replace semantic args, while TEMP and TMP identify measured scratch."""
        launcher = {"path": sys.executable, "sha256": runner.file_digest(sys.executable)}
        tool = {"launcher": launcher, "inventory": [launcher]}
        with tempfile.TemporaryDirectory() as directory:
            request = runner.memory_request([sys.executable, "a path & literal"], tool, Path(directory), 10)
            self.assertEqual(["a path & literal"], request["arguments"])
            self.assertEqual(str(Path(directory) / "scratch"), request["process_environment"]["TEMP"])
            self.assertEqual(request["scratch_directory"], request["process_environment"]["TMP"])

    def test_rejects_ambient_jvm_tuning_before_starting_the_process(self):
        """An inherited JAVA_TOOL_OPTIONS must not change the bound production runtime."""
        launcher = {"path": sys.executable, "sha256": runner.file_digest(sys.executable)}
        with tempfile.TemporaryDirectory() as directory, patch.dict("os.environ", {"JAVA_TOOL_OPTIONS": "-Xmx1g"}):
            with self.assertRaises(ValueError):
                runner.memory_request([sys.executable], {"launcher": launcher, "inventory": [launcher]}, Path(directory), 10)


if __name__ == "__main__":
    unittest.main()
