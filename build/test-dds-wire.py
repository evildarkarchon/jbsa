"""Check the independent DDS scanner against bounded hand-constructed negative inputs."""

import importlib.util
import hashlib
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

    def test_starfield_archive_versions_and_codecs(self):
        """Accept v2 zlib and v3 method-3 raw-LZ4 using their larger headers."""
        name = b'textures/a.dds'
        image = bytes.fromhex('630e873656a6ce50')
        crc = lambda text: scanner.binascii.crc32(text, 0xffffffff) ^ 0xffffffff
        prefix = struct.pack('<I4sIBBHHHBBBB', crc(b'a'), b'dds\0', crc(b'textures'),
                             0, 1, 24, 4, 4, 1, 71, 0, 0)
        for version, payload, extra in (
                (2, zlib.compress(image), struct.pack('<Q', 1)),
                (3, bytes([len(image) << 4]) + image, struct.pack('<QI', 1, 3))):
            header_size = 32 if version == 2 else 36
            payload_offset = header_size + 48
            names_offset = payload_offset + len(payload)
            raw = struct.pack('<4sI4sIq', b'BTDX', version, b'DX10', 1, names_offset)
            raw += extra + prefix
            raw += struct.pack('<qIIHHI', payload_offset, len(payload), len(image),
                               0, 0, 0xbaadf00d)
            raw += payload + struct.pack('<H', len(name)) + name
            projection = scanner.inspect_archive(raw)
            suffix = '-m3' if version == 3 else ''
            self.assertEqual(f'sf-dx10-v{version}{suffix}', projection['family'])
            self.assertEqual(hashlib.sha256(image).hexdigest(),
                             projection['entries'][0]['payload_sha256'])

    def test_starfield_rejects_noncanonical_header_and_raw_lz4(self):
        """Reject unknown v3 methods, extra-header drift, and incomplete raw-LZ4 blocks."""
        fixture = bytes.fromhex(
            '425444580300000044583130010000005d00000000000000010000000000000003000000'
            'ce51b53a646473007f66a5c0000118000400040001470000540000000000000009000000'
            '08000000000000000df0adba80630e873656a6ce500e0074657874757265732f612e646473')
        for offset, value in ((24, 2), (32, 2), (76, 8)):
            mutated = bytearray(fixture)
            struct.pack_into('<I', mutated, offset, value)
            with self.assertRaises(ValueError):
                scanner.inspect_archive(mutated)

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
