"""Check the independent General BA2 scanner against committed project vectors."""

import hashlib
import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parent.parent


def load_scanner():
    """Load the build-only hyphenated scanner without importing product code."""
    path = Path(__file__).with_name("validate-ba2-wire.py")
    spec = importlib.util.spec_from_file_location("validate_ba2_wire", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SCANNER = load_scanner()


class GeneralBa2ScannerTest(unittest.TestCase):
    """Protect the v7/v8 envelope, record, naming, and zlib decisions independently."""

    def test_fallout4_versions_seven_and_eight(self):
        """Validate stored/zlib vectors without reading nonexistent Starfield header fields."""
        archives = ROOT / "tests/fixtures/synthetic/artifacts/archives"
        cases = (
            (7, "stored", archives / "fo4-gnrl-v7-stored.ba2", b"jbsa-v7-stored\n"),
            (7, "zlib", archives / "fo4-gnrl-v7-zlib.ba2", b"A" * 32),
            (8, "stored", archives / "fo4-gnrl-v8-stored.hex", b"jbsa-v8-stored\n"),
            (8, "zlib", archives / "fo4-gnrl-v8-zlib.hex", b"B" * 32),
        )
        for version, codec, path, payload in cases:
            raw = bytes.fromhex(path.read_text().strip()) if path.suffix == ".hex" else path.read_bytes()
            temporary = ROOT / "target" / f"validator-fo4-gnrl-v{version}-{codec}.ba2"
            temporary.parent.mkdir(parents=True, exist_ok=True)
            temporary.write_bytes(raw)
            projection = SCANNER.inspect(temporary)
            self.assertEqual(f"fo4-gnrl-v{version}", projection["family"])
            self.assertEqual(hashlib.sha256(payload).hexdigest(),
                             projection["entries"][0]["payload_sha256"])

    def test_rejects_unassigned_intermediate_version(self):
        """Never broaden the explicit version set to the unassigned values four through six."""
        source = ROOT / "tests/fixtures/synthetic/artifacts/archives/fo4-gnrl-v7-stored.ba2"
        raw = bytearray(source.read_bytes())
        raw[4:8] = (4).to_bytes(4, "little")
        temporary = ROOT / "target/validator-unsupported-gnrl-v4.ba2"
        temporary.parent.mkdir(parents=True, exist_ok=True)
        temporary.write_bytes(raw)
        with self.assertRaises(ValueError):
            SCANNER.inspect(temporary)


if __name__ == "__main__":
    unittest.main()
