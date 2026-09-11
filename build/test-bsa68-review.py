"""Check independently authored version-104 review projections and selector rejection."""

import importlib.util
from pathlib import Path
import struct
import json
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("expectations", ROOT / "build/bsa-cv1-expectations.py")
expectations = importlib.util.module_from_spec(spec)
spec.loader.exec_module(expectations)


class Bsa68ReviewTests(unittest.TestCase):
    """Catch scanner rejection or mislabeling of the shared version-104 wire layout."""

    def test_version104_stored_projection(self):
        """A hand-patched version and flags keep literal sizes and payload identities."""
        raw = bytearray.fromhex((ROOT / "tests/fixtures/bsa067/artifacts/bsa-067-stored.hex").read_text())
        struct.pack_into("<I", raw, 4, 104)
        struct.pack_into("<I", raw, 12, 0x83)
        actual = expectations.projection(raw)
        self.assertEqual("bsa-068", actual.get("archive_family"))
        self.assertEqual(104, actual["wire_version"])
        self.assertEqual([1024, 4], [entry["decoded_size"] for entry in actual["entries"]])

    def test_version103_projection_remains_supported(self):
        """The original fixture keeps its existing family, flags and payload sizes."""
        raw = bytes.fromhex((ROOT / "tests/fixtures/bsa067/artifacts/bsa-067-stored.hex").read_text())
        actual = expectations.projection(raw)
        self.assertEqual("bsa-067", actual["archive_family"])
        self.assertEqual(0x683, actual["archive_flags"])

    def test_generator_version104_recipes(self):
        """Explicit generation must write 104 fixtures with valid automatic version-specific flags."""
        with tempfile.TemporaryDirectory(dir=ROOT / "target") as directory:
            generated = subprocess.run(["python", str(ROOT / "build/generate-bsa68-cv1-fixtures.py"),
                "--output", directory], capture_output=True, text=True)
            self.assertEqual(0, generated.returncode, generated.stderr)
            manifest = json.loads((Path(directory) / "manifest.json").read_text())
            fixture = next(value for value in manifest["fixtures"] if value["id"] == "bsa-068-stored")
            raw = bytes.fromhex((Path(directory) / fixture["output"]["path"]).read_text())
            actual = expectations.projection(raw)
            self.assertEqual("bsa-068", actual["archive_family"])
            self.assertEqual(0x83, actual["archive_flags"])

    def test_embedded_prefix_is_metadata_not_payload(self):
        """Embedded wire bytes are retained separately and excluded from decoded byte counts."""
        raw = bytes.fromhex((ROOT / "tests/fixtures/bsa068/fallout3-stored-embedded.hex").read_text())
        actual = expectations.projection(raw)
        self.assertEqual([1024, 4], [entry["decoded_size"] for entry in actual["entries"]])
        self.assertEqual("6d65736865735c612e6e6966", actual["entries"][0]["embedded_name"])
        self.assertEqual(["bsa.nontexture-with-embedded-name"] * 2,
                         [item["identifier"] for item in actual["diagnostics"]])
        self.assertEqual("CONFORMING", actual["disposition"])


if __name__ == "__main__":
    unittest.main()
