"""Independently corroborate bounded canonical ASCII General BA2 v1 stored/zlib archives.

This build-only scanner imports neither the fixture generator, JBSA, nor xEdit.
It accepts exact shared spans and checks metadata, names and full zlib consumption.
"""

import binascii
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys
import zlib


def inspect(path):
    """Return payload semantics after independently checking the complete canonical wire layout."""
    if path.stat().st_size > 64 * 1024 * 1024:
        raise ValueError("Independent slice scanner input exceeds 64 MiB")
    raw = path.read_bytes()
    magic, version, subtype, count, names_offset = struct.unpack_from("<4sI4sIq", raw)
    if (magic, version, subtype) != (b"BTDX", 1, b"GNRL") or not 0 < count <= 100000:
        raise ValueError("Unsupported header or count")
    table_end = 24 + count * 36
    if not table_end <= names_offset <= len(raw):
        raise ValueError("Filename table outside bounded payload region")
    cursor, entries, spans, identities = names_offset, [], set(), set()
    decoded_total = 0
    for index in range(count):
        basename_hash, extension, directory_hash, mod, chunks, chunk_size, offset, packed, unpacked, sentinel = struct.unpack_from("<I4sIBBHqIII", raw, 24 + index * 36)
        if (mod, chunks, chunk_size, sentinel) != (0, 1, 16, 0xbaadf00d):
            raise ValueError("Noncanonical General record constants")
        length, = struct.unpack_from("<H", raw, cursor)
        cursor += 2
        name = raw[cursor:cursor + length]
        cursor += length
        if len(name) != length or b"\0" in name or b"/" not in name or b"\\" in name:
            raise ValueError("Incomplete or noncanonical name")
        name.decode("ascii")
        normalized = name.lower().replace(b"/", b"\\")
        if normalized in identities:
            raise ValueError("Duplicate normalized name identity")
        identities.add(normalized)
        folder, filename = normalized.rsplit(b"\\", 1)
        parts = filename.rsplit(b".", 1)
        stem, suffix = parts[0], parts[1] if len(parts) == 2 else b""
        # Python's finalized CRC can express the BA2 initial-zero/no-final-XOR variant.
        expected_folder = binascii.crc32(folder, 0xffffffff) ^ 0xffffffff
        expected_stem = binascii.crc32(stem, 0xffffffff) ^ 0xffffffff
        if (directory_hash, basename_hash, extension) != (expected_folder, expected_stem, suffix[:4].ljust(4, b"\0")):
            raise ValueError("Name hash or extension mismatch")
        size = packed or unpacked
        if not table_end <= offset <= names_offset or size > names_offset - offset:
            raise ValueError("Payload overlaps metadata or exceeds payload region")
        spans.add((offset, offset + size))
        content = raw[offset:offset + size]
        decoded_total += unpacked
        if decoded_total > 64 * 1024 * 1024:
            raise ValueError("Independent slice scanner decoded-byte limit")
        if packed:
            decoder = zlib.decompressobj()
            content = decoder.decompress(content, unpacked + 1)
            if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail or len(content) != unpacked:
                raise ValueError("Zlib framing, trailing input or decoded-size mismatch")
        entries.append({"name": name.replace(b"/", b"\\").decode("ascii"),
                        "size": len(content), "payload_sha256": hashlib.sha256(content).hexdigest()})
    if cursor != len(raw):
        raise ValueError("Noncanonical filename-table extent")
    previous_end = table_end
    for start, end in sorted(spans):
        if start != previous_end:
            raise ValueError("Partial overlap or noncanonical payload gap")
        previous_end = end
    if previous_end != names_offset:
        raise ValueError("Unreferenced payload bytes")
    return {"family": "fo4-gnrl-v1", "entries": entries}


if __name__ == "__main__":
    path = Path(sys.argv[1])
    spec = importlib.util.spec_from_file_location("ba2_expectations", Path(__file__).with_name("ba2-cv1-expectations.py"))
    expectations = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(expectations)
    print(json.dumps({"projection": inspect(path),
                      "semantic_projection": expectations.projection(path.read_bytes())}, separators=(",", ":")))
