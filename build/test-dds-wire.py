"""Check the independent DDS scanner against bounded hand-constructed negative inputs."""

import importlib.util
from pathlib import Path
import struct
import unittest
import zlib


def load(name):
    """Load build-only hyphenated Python tools without a product dependency."""
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


scanner = load('validate-dds-wire')
generator = load('generate-dds-fixtures')


class DdsScannerTest(unittest.TestCase):
    """Protect independent boundary calculations and exact framing checks."""

    def test_bc_boundaries(self):
        """Small and odd BC mips occupy full physical blocks."""
        self.assertEqual([32, 8, 8], scanner.mip_sizes(5, 7, 3, 71))
        self.assertEqual([64, 16, 16], scanner.mip_sizes(5, 7, 3, 98))
        self.assertEqual([32, 16, 8, 8], scanner.mip_sizes(16, 1, 4, 71))

    def test_exact_payload_and_array_rejection(self):
        """Reject under/overlong mip chains and shapes that BA2 cannot preserve."""
        raw = generator.texture(5, 7, 3, 71)
        self.assertEqual(48, scanner.inspect_dds(raw)['payload_size'])
        for mutated in (raw[:-1], raw + b'\0'):
            with self.assertRaises(ValueError):
                scanner.inspect_dds(mutated)
        mutated = bytearray(raw)
        struct.pack_into('<I', mutated, 140, 2)
        with self.assertRaises(ValueError):
            scanner.inspect_dds(mutated)

    def test_cubemap_payload(self):
        """Six complete face chains are required for cubemap input."""
        raw = generator.texture(5, 7, 3, 98, True)
        self.assertEqual(576, scanner.inspect_dds(raw)['payload_size'])

    def test_archive_exact_chunk_decode(self):
        """Reject truncated or trailing zlib data even with otherwise valid wire records."""
        name = b'textures/a.dds'
        payload = zlib.compress(bytes(8))
        crc = lambda text: scanner.binascii.crc32(text, 0xffffffff) ^ 0xffffffff
        prefix = struct.pack('<I4sIBBHHHBBBB', crc(b'a'), b'dds\0', crc(b'textures'),
                             0, 1, 24, 1, 1, 1, 71, 0, 0)
        for suffix in (b'', b'\0'):
            stored = payload + suffix
            raw = struct.pack('<4sI4sIq', b'BTDX', 1, b'DX10', 1, 72 + len(stored))
            raw += prefix + struct.pack('<qIIHHI', 72, len(stored), 8, 0, 0, 0xbaadf00d)
            raw += stored + struct.pack('<H', len(name)) + name
            if suffix:
                with self.assertRaises(ValueError):
                    scanner.inspect_archive(raw)
            else:
                self.assertEqual(8, scanner.inspect_archive(raw)['entries'][0]['payload_size'])

    def test_archive_exact_shared_span(self):
        """Exact shared compressed spans are valid while partial overlaps remain invalid."""
        payload = zlib.compress(bytes(8))
        crc = lambda text: scanner.binascii.crc32(text, 0xffffffff) ^ 0xffffffff
        for second_offset in (120, 121):
            records = bytearray()
            for stem, offset in ((b'a', 120), (b'b', second_offset)):
                records.extend(struct.pack('<I4sIBBHHHBBBB', crc(stem), b'dds\0', crc(b'textures'),
                                           0, 1, 24, 1, 1, 1, 71, 0, 0))
                records.extend(struct.pack('<qIIHHI', offset, len(payload), 8, 0, 0, 0xbaadf00d))
            names = b''.join(struct.pack('<H', len(name)) + name for name in
                             (b'textures/a.dds', b'textures/b.dds'))
            raw = struct.pack('<4sI4sIq', b'BTDX', 1, b'DX10', 2, 120 + len(payload))
            raw += records + payload + names
            if second_offset == 120:
                self.assertEqual(2, len(scanner.inspect_archive(raw)['entries']))
            else:
                with self.assertRaises(ValueError):
                    scanner.inspect_archive(raw)


if __name__ == '__main__':
    unittest.main()
