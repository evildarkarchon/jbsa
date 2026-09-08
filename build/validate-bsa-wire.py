"""Independently corroborate small ASCII TES4 archives using the written wire contract.

This build-only scanner does not import the fixture generator, JBSA, or xEdit.
Its bounded scope is complete name tables and unshared contiguous payloads.
"""

import hashlib
import json
from pathlib import Path
import struct
import sys
import zlib


def name_hash(component, basename):
    """Calculate ASCII component hash independently of product and fixture generator code."""
    parts = component.rsplit(b".", 1) if basename else [component]
    stem = parts[0]
    extension = b"." + parts[1] if len(parts) == 2 else b""
    if not stem or any(value > 127 for value in component):
        raise ValueError("Scanner requires nonempty ASCII stems")
    bottom = int.from_bytes(bytes([stem[-1], stem[-2] if len(stem) > 2 else 0,
                                   len(stem) & 255, stem[0]]), "little")
    for suffix, mask in ((b".nif", 32768), (b".dds", 32896),
                         (b".kf", 128), (b".wav", 2147483648)):
        if extension == suffix:
            bottom |= mask
    accumulator = 0
    extension_accumulator = 0
    for index in range(1, len(stem) - 2):
        accumulator = (accumulator * 65599 + stem[index]) % (1 << 32)
    for value in extension:
        extension_accumulator = (extension_accumulator * 65599 + value) % (1 << 32)
    return (((accumulator + extension_accumulator) % (1 << 32)) << 32) + bottom


def inspect(path):
    """Validate sections, name hashes, framing and exact codec consumption; return semantics."""
    if path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("Independent slice scanner input exceeds 16 MiB")
    raw = path.read_bytes()
    magic, version, start, flags, folders, files, folder_length, names_length, _ = struct.unpack_from("<4s8I", raw)
    if (magic, version, start) != (b"BSA\0", 103, 36) or flags & 3 != 3:
        raise ValueError("Unsupported header or absent name tables")
    if folders > 10000 or files > 10000:
        raise ValueError("Independent slice scanner count limit")
    position = 36 + 16 * folders
    total_folder_length = 0
    pending = []
    for ordinal in range(folders):
        folder_hash, count, offset = struct.unpack_from("<QII", raw, 36 + 16 * ordinal)
        if offset != position + names_length:
            raise ValueError("Folder offset mismatch")
        size = raw[position]
        position += 1
        folder = raw[position:position + size]
        if not folder or folder[-1] != 0 or b"\0" in folder[:-1]:
            raise ValueError("Folder terminator mismatch")
        total_folder_length += size
        position += size
        folder = folder[:-1]
        if name_hash(folder, False) != folder_hash:
            raise ValueError("Folder hash mismatch")
        for _ in range(count):
            file_hash, size_flags, offset = struct.unpack_from("<QII", raw, position)
            pending.append((folder, file_hash, size_flags, offset))
            position += 16
    if len(pending) != files or total_folder_length != folder_length:
        raise ValueError("Header counts or lengths mismatch")
    names_end = position + names_length
    payload_position = names_end
    entries = []
    for folder, expected_hash, size_flags, offset in pending:
        end = raw.index(b"\0", position, names_end)
        basename = raw[position:end]
        position = end + 1
        if name_hash(basename, True) != expected_hash:
            raise ValueError("File hash mismatch")
        size = size_flags & ~0x40000000
        if offset != payload_position or size > len(raw) - offset:
            raise ValueError("Payload span mismatch")
        payload_position += size
        content = raw[offset:offset + size]
        if bool(flags & 4) != bool(size_flags & 0x40000000):
            decoded_size, = struct.unpack_from("<I", content)
            if decoded_size > 16 * 1024 * 1024:
                raise ValueError("Independent decoded-size limit")
            decoder = zlib.decompressobj()
            decoded = decoder.decompress(content[4:], decoded_size + 1)
            if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail or len(decoded) != decoded_size:
                raise ValueError("Zlib framing, trailing input or decoded-size mismatch")
            content = decoded
        entries.append({"name": (folder + b"\\" + basename).decode("ascii"),
                        "size": len(content), "payload_sha256": hashlib.sha256(content).hexdigest()})
    if position != names_end or payload_position != len(raw):
        raise ValueError("Noncanonical section extent")
    return {"family": "bsa-067", "entries": entries}


if __name__ == "__main__":
    print(json.dumps({"projection": inspect(Path(sys.argv[1]))}, separators=(",", ":")))
