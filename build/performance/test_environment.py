"""Exercise the PowerShell environment probe without changing host settings."""
from pathlib import Path
import subprocess
import unittest


class EnvironmentTest(unittest.TestCase):
    """A single-line powercfg response must not break PowerShell strict mode."""

    def test_single_line_power_scheme_is_classified(self):
        """Boolean.Count under strict mode would prevent every normative run."""
        script = Path(__file__).resolve().parents[1] / "performance-environment.ps1"
        command = (". '" + str(script).replace("'", "''") + "'; "
                   "if (-not (Test-HighPerformancePlan 'Power Scheme GUID: 8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c (High performance)')) { exit 1 }; "
                   "if (Test-HighPerformancePlan 'Power Scheme GUID: 381b4222-f694-41f0-9685-ff5bb260df2e (Balanced)') { exit 2 }")
        result = subprocess.run(["pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
