"""Independently corroborate bounded canonical ASCII General BA2 archives.

This build-only scanner imports neither the fixture generator, JBSA, nor xEdit.
It accepts exact shared spans and checks metadata, names, and complete zlib/raw-LZ4 input.
"""

import binascii
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys
import zlib


def decode_raw_lz4(block, expected_size):
    """Decode one raw LZ4 block while requiring complete input and exact bounded output."""
    source = 0
    output = bytearray()

    def extended_length(base):
        """Consume an LZ4 extension chain for a literal or match length."""
        nonlocal source
        length = base
        if base == 15:
            while True:
                if source >= len(block):
                    raise ValueError("Truncated raw-LZ4 length")
                value = block[source]
                source += 1
                length += value
                if value != 255:
                    break
        return length

    while source < len(block):
        token = block[source]
        source += 1
        literal_length = extended_length(token >> 4)
        literal_end = source + literal_length
        if literal_end > len(block) or len(output) + literal_length > expected_size:
            raise ValueError("Raw-LZ4 literal exceeds bounded input or output")
        output.extend(block[source:literal_end])
        source = literal_end
        if source == len(block):
            break
        if source + 2 > len(block):
            raise ValueError("Truncated raw-LZ4 match offset")
        distance = block[source] | block[source + 1] << 8
        source += 2
        if distance == 0 or distance > len(output):
            raise ValueError("Invalid raw-LZ4 match offset")
        match_length = extended_length(token & 0x0f) + 4
        if len(output) + match_length > expected_size:
            raise ValueError("Raw-LZ4 match exceeds bounded output")
        for _ in range(match_length):
            output.append(output[-distance])
    if source != len(block) or len(output) != expected_size:
        raise ValueError("Raw-LZ4 trailing input or decoded-size mismatch")
    return bytes(output)


def inspect(path):
    """Return payload semantics after independently checking the complete canonical wire layout."""
    if path.stat().st_size > 64 * 1024 * 1024:
        raise ValueError("Independent slice scanner input exceeds 64 MiB")
    raw = path.read_bytes()
    if len(raw) < 24:
        raise ValueError("Truncated General BA2 header")
    magic, version, subtype, count, names_offset = struct.unpack_from("<4sI4sIq", raw)
    if (magic != b"BTDX" or subtype != b"GNRL"
            or version not in (1, 2, 3, 7, 8) or not 0 < count <= 100000):
        raise ValueError("Unsupported header or count")
    header_size = {1: 24, 2: 32, 3: 36, 7: 24, 8: 24}[version]
    if len(raw) < header_size:
        raise ValueError("Truncated General BA2 extra header")
    method = None
    if version in (2, 3):
        unknown_value_at_24, = struct.unpack_from("<Q", raw, 24)
        if unknown_value_at_24 != 1:
            raise ValueError("Noncanonical Starfield extra header")
    if version == 3:
        method, = struct.unpack_from("<I", raw, 32)
        if method != 3:
            raise ValueError("Unsupported Starfield compression method")
    table_end = header_size + count * 36
    if not table_end <= names_offset <= len(raw):
        raise ValueError("Filename table outside bounded payload region")
    cursor, entries, spans, identities = names_offset, [], set(), set()
    decoded_total = 0
    for index in range(count):
        basename_hash, extension, directory_hash, mod, chunks, chunk_size, offset, packed, unpacked, sentinel = struct.unpack_from("<I4sIBBHqIII", raw, header_size + index * 36)
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
        if packed and method == 3:
            content = decode_raw_lz4(content, unpacked)
        elif packed:
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
    family = (f"fo4-gnrl-v{version}" if version in (1, 7, 8)
              else f"sf-gnrl-v{version}" + ("-m3" if method == 3 else ""))
    projection = {"family": family, "entries": entries}
    if version != 1:
        projection.update({"version": version, "compression_method": method})
    return projection


if __name__ == "__main__":
    path = Path(sys.argv[1])
    spec = importlib.util.spec_from_file_location("ba2_expectations", Path(__file__).with_name("ba2-cv1-expectations.py"))
    expectations = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(expectations)
    projection = inspect(path)
    result = {"projection": projection}
    if projection["family"] == "fo4-gnrl-v1":
        result["semantic_projection"] = expectations.projection(path.read_bytes())
    print(json.dumps(result, separators=(",", ":")))
