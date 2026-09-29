"""Independent validator evidence identifies executable tools without source hashes."""

import json
from pathlib import Path
import tempfile
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]


class ValidatorIdentityTests(unittest.TestCase):
    """Inspect each validator wrapper's recorded tool identity."""

    def test_wrappers_do_not_record_python_or_powershell_source_hashes(self) -> None:
        """Keep source script edits from changing the validator's tool record."""
        for wrapper in ("run-ba2-validator.ps1", "run-bsa-validator.ps1", "run-tes3-validator.ps1"):
            with self.subTest(wrapper=wrapper), tempfile.TemporaryDirectory() as temporary:
                work = Path(temporary)
                archive = work / "invalid.archive"
                archive.write_bytes(b"invalid")
                evidence = work / "evidence"
                subprocess.run(
                    [
                        "pwsh", "-NoProfile", "-File", str(ROOT / "build" / wrapper),
                        "-InputPath", str(archive),
                        "-WorkingDirectory", str(work / "run"),
                        "-EvidenceDirectory", str(evidence),
                    ],
                    check=False,
                    capture_output=True,
                )
                observation = json.loads((evidence / "observation.json").read_text(encoding="utf-8"))
                self.assertNotIn("implementation_sha256", observation["validator"])


if __name__ == "__main__":
    unittest.main()
