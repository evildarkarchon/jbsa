"""Independent DDS PV1 validation rejects incorrect bytes, metadata and zlib framing."""

import binascii
import hashlib
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

import dds_validator


class DdsValidatorTest(unittest.TestCase):
    """Exercise bounded exact-byte validation against a manually constructed one-block texture."""

    def test_exact_frame_consumption(self):
        """Trailing compressed bytes and incorrect decoded lengths cannot pass the validator."""
        compressed = zlib.compress(bytes(8))
        for data, length in ((compressed + b'\0', 8), (compressed, 7), (compressed[:-1], 8)):
            with self.assertRaises(ValueError):
                dds_validator.hash_chunk(io.BytesIO(data), len(data), length, hashlib.sha256())
        digest = hashlib.sha256()
        dds_validator.hash_chunk(io.BytesIO(compressed), len(compressed), 8, digest)
        self.assertEqual(hashlib.sha256(bytes(8)).hexdigest(), digest.hexdigest())

    def test_streaming_archive_matches_external_canonical_dds(self):
        """The complete reconstructed hash and declared structure must match external evidence."""
        dds = bytearray(136)
        dds[:4] = b'DDS '
        for offset, value in {4: 124, 8: 0xa1007, 12: 1, 16: 1, 20: 8, 24: 1, 28: 1,
                              76: 32, 80: 4, 84: 0x31545844, 108: 0x1000}.items():
            struct.pack_into('<I', dds, offset, value)
        name = b'textures/a.dds'
        compressed = zlib.compress(bytes(8))
        crc = lambda data: binascii.crc32(data, 0xffffffff) ^ 0xffffffff
        raw = struct.pack('<4sI4sIq', b'BTDX', 1, b'DX10', 1, 72 + len(compressed))
        raw += struct.pack('<I4sIBBHHHBBBB', crc(b'a'), b'dds\0', crc(b'textures'), 0, 1, 24,
                           1, 1, 1, 71, 0, 8)
        raw += struct.pack('<qIIHHI', 72, len(compressed), 8, 0, 0, 0xbaadf00d)
        raw += compressed + struct.pack('<H', len(name)) + name
        document = {'files': [{'path': name.decode(), 'length': 136,
                    'sha256': hashlib.sha256(dds).hexdigest(), 'structural': {
                        'width': 1, 'height': 1, 'mip_count': 1, 'dxgi_format': 71, 'cubemap': False,
                        'chunks': [{'length': 8, 'start_mip': 0, 'end_mip': 0}]}}]}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'a.ba2'
            path.write_bytes(raw)
            self.assertEqual(136, dds_validator.inspect_archive(path, document)['logical_bytes'])
            padded = bytearray(raw[:72] + bytes(8) + raw[72:])
            struct.pack_into('<q', padded, 16, 80 + len(compressed))
            struct.pack_into('<q', padded, 48, 80)
            path.write_bytes(padded)
            self.assertEqual(136, dds_validator.inspect_archive(path, document)['logical_bytes'])
            document['files'][0]['sha256'] = '0' * 64
            with self.assertRaises(ValueError):
                dds_validator.inspect_archive(path, document)


if __name__ == '__main__':
    unittest.main()
