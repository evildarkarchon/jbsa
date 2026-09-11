"""Behavioral regression checks for independently validated 0x68 framing."""

import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest


def load(name):
    """Load a build-only source file without importing any product code."""
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class EmbeddedFramingTest(unittest.TestCase):
    """Exercise independent wire construction against the independent scanner."""

    def test_version104_stored_zlib_and_embedded_framing(self):
        """Every mode must consume the full name before its optional decoded-size prefix."""
        generator = load("generate-bsa068-fixtures")
        scanner = load("validate-bsa-wire")
        for mode in ("stored", "zlib", "mixed"):
            for embedded in (False, True):
                with self.subTest(mode=mode, embedded=embedded), tempfile.TemporaryDirectory() as temp:
                    wire = bytearray(generator.archive(mode, embedded))
                    path = Path(temp) / "sample.bsa"
                    path.write_bytes(wire)
                    projection = scanner.inspect(path)
                    self.assertEqual("bsa-068", projection["family"])
                    self.assertEqual([1024, 4], [entry["size"] for entry in projection["entries"]])
                    if embedded:
                        first = struct.unpack_from("<I", wire, 72)[0]
                        wire[first + 1] ^= 1
                        path.write_bytes(wire)
                        with self.assertRaisesRegex(ValueError, "Embedded name"):
                            scanner.inspect(path)
                        wire[first + 1] ^= 1
                        wire[first] = 255
                        path.write_bytes(wire)
                        with self.assertRaisesRegex(ValueError, "Embedded name"):
                            scanner.inspect(path)


if __name__ == "__main__":
    unittest.main()
