"""Behavioral regression checks for independent 0x69 frame and record validation."""

import importlib.util
from pathlib import Path
import tempfile
import unittest


def load(name):
    """Load one build-only source file without importing product code."""
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Bsa69ValidationTest(unittest.TestCase):
    """Exercise independently constructed 24-byte records and LZ4 frames."""

    def test_stored_lz4_mixed_and_embedded_vectors(self):
        """Every generated mode must expose the same exact semantic payloads."""
        generator = load("generate-bsa069-fixtures")
        scanner = load("validate-bsa-wire")
        for mode in ("stored", "lz4-frame", "mixed"):
            for embedded in (False, True):
                with self.subTest(mode=mode, embedded=embedded), tempfile.TemporaryDirectory() as temp:
                    path = Path(temp) / "sample.bsa"
                    path.write_bytes(generator.archive(mode, embedded))
                    projection = scanner.inspect(path)
                    self.assertEqual("bsa-069", projection["family"])
                    self.assertEqual([1024, 4], [entry["size"] for entry in projection["entries"]])

    def test_frame_profile_and_padding_mutations_are_rejected(self):
        """The scanner must notice profile drift and either nonzero 0x69 padding word."""
        generator = load("generate-bsa069-fixtures")
        scanner = load("validate-bsa-wire")
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "sample.bsa"
            wire = bytearray(generator.archive("lz4-frame", False))
            wire[48] = 1
            path.write_bytes(wire)
            with self.assertRaisesRegex(ValueError, "padding"):
                scanner.inspect(path)
            wire[48] = 0
            first_payload = int.from_bytes(wire[80:84], "little")
            wire[first_payload + 8] ^= 1
            path.write_bytes(wire)
            with self.assertRaisesRegex(ValueError, "profile"):
                scanner.inspect(path)


if __name__ == "__main__":
    unittest.main()
