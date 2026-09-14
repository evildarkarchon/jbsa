"""Independently corroborate small ASCII versioned BSAs using the written wire contract.

This build-only scanner does not import the fixture generator, JBSA, or xEdit.
Its bounded scope is complete name tables and unshared contiguous payloads.
"""

import hashlib
import json
from pathlib import Path
import struct
import sys
import zlib


def _rotate_left(value, count):
    """Rotate one unsigned 32-bit word for the independent XXH32 header check."""
    return ((value << count) | (value >> (32 - count))) & 0xffffffff


def xxh32(data, seed=0):
    """Compute XXH32 directly from its published integer algorithm."""
    prime1, prime2, prime3, prime4, prime5 = 2654435761, 2246822519, 3266489917, 668265263, 374761393
    position = 0
    if len(data) >= 16:
        accumulators = [(seed + prime1 + prime2) & 0xffffffff,
                        (seed + prime2) & 0xffffffff, seed & 0xffffffff,
                        (seed - prime1) & 0xffffffff]
        while position <= len(data) - 16:
            for index in range(4):
                word, = struct.unpack_from("<I", data, position)
                position += 4
                accumulators[index] = _rotate_left(
                    (accumulators[index] + word * prime2) & 0xffffffff, 13)
                accumulators[index] = (accumulators[index] * prime1) & 0xffffffff
        value = sum(_rotate_left(accumulators[index], shift)
                    for index, shift in enumerate((1, 7, 12, 18))) & 0xffffffff
    else:
        value = (seed + prime5) & 0xffffffff
    value = (value + len(data)) & 0xffffffff
    while position <= len(data) - 4:
        word, = struct.unpack_from("<I", data, position)
        value = (_rotate_left((value + word * prime3) & 0xffffffff, 17) * prime4) & 0xffffffff
        position += 4
    while position < len(data):
        value = (_rotate_left((value + data[position] * prime5) & 0xffffffff, 11) * prime1) & 0xffffffff
        position += 1
    value ^= value >> 15
    value = (value * prime2) & 0xffffffff
    value ^= value >> 13
    value = (value * prime3) & 0xffffffff
    return (value ^ (value >> 16)) & 0xffffffff


def lz4_block(block, limit):
    """Decode one independent LZ4 block while enforcing the enclosing decoded-size limit."""
    output = bytearray()
    position = 0
    while position < len(block):
        token = block[position]
        position += 1
        literal_length = token >> 4
        if literal_length == 15:
            while True:
                if position >= len(block):
                    raise ValueError("Truncated LZ4 literal length")
                extra = block[position]
                position += 1
                literal_length += extra
                if extra != 255:
                    break
        if literal_length > len(block) - position or len(output) + literal_length > limit:
            raise ValueError("LZ4 literal exceeds bounds")
        output += block[position:position + literal_length]
        position += literal_length
        if position == len(block):
            break
        if position + 2 > len(block):
            raise ValueError("Truncated LZ4 match offset")
        offset, = struct.unpack_from("<H", block, position)
        position += 2
        if offset == 0 or offset > len(output):
            raise ValueError("Invalid LZ4 match offset")
        match_length = (token & 15) + 4
        if (token & 15) == 15:
            while True:
                if position >= len(block):
                    raise ValueError("Truncated LZ4 match length")
                extra = block[position]
                position += 1
                match_length += extra
                if extra != 255:
                    break
        if len(output) + match_length > limit:
            raise ValueError("LZ4 match exceeds bounds")
        for _ in range(match_length):
            output.append(output[-offset])
    return bytes(output)


def lz4_frame(content, decoded_size):
    """Validate and decode the exact BSA-010 frame profile without a provider dependency."""
    if len(content) < 11 or content[:4] != bytes.fromhex("04224d18"):
        raise ValueError("Invalid LZ4 frame magic")
    descriptor = content[4:6]
    # Decode accepts ordinary independent frames from the oracle; canonical JBSA encode separately
    # asserts its required 4 MiB maximum (0x70) at the product boundary.
    if descriptor[0] != 0x60 or descriptor[1] not in (0x40, 0x50, 0x60, 0x70):
        raise ValueError("Unexpected LZ4 frame profile")
    if content[6] != ((xxh32(descriptor) >> 8) & 0xff):
        raise ValueError("Invalid LZ4 frame header checksum")
    position = 7
    decoded = bytearray()
    while True:
        if position + 4 > len(content):
            raise ValueError("Truncated LZ4 frame block")
        block_size, = struct.unpack_from("<I", content, position)
        position += 4
        if block_size == 0:
            break
        stored = bool(block_size & 0x80000000)
        block_size &= 0x7fffffff
        if block_size > 4 * 1024 * 1024 or block_size > len(content) - position:
            raise ValueError("LZ4 frame block exceeds bounds")
        block = content[position:position + block_size]
        position += block_size
        remaining = decoded_size - len(decoded)
        decoded += block if stored else lz4_block(block, remaining)
        if len(decoded) > decoded_size:
            raise ValueError("LZ4 decoded size mismatch")
    if position != len(content) or len(decoded) != decoded_size:
        raise ValueError("LZ4 framing, trailing input or decoded-size mismatch")
    return bytes(decoded)


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
    if magic != b"BSA\0" or version not in (103, 104, 105) or start != 36 or flags & 3 != 3:
        raise ValueError("Unsupported header or absent name tables")
    if folders > 10000 or files > 10000:
        raise ValueError("Independent slice scanner count limit")
    folder_record_size = 24 if version == 105 else 16
    position = 36 + folder_record_size * folders
    total_folder_length = 0
    pending = []
    for ordinal in range(folders):
        record = 36 + folder_record_size * ordinal
        if version == 105:
            folder_hash, count, padding_before, offset, padding_after = struct.unpack_from("<QIIII", raw, record)
            if padding_before or padding_after:
                raise ValueError("Noncanonical 0x69 folder padding")
        else:
            folder_hash, count, offset = struct.unpack_from("<QII", raw, record)
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
        if version >= 104 and flags & 0x100:
            if not content or content[0] > len(content) - 1:
                raise ValueError("Embedded name exceeds record bounds")
            name_size = content[0]
            if content[1:1 + name_size] != folder + b"\\" + basename:
                raise ValueError("Embedded name disagrees with index")
            # Prefix bytes belong to record size, but never to decoded-size or codec input.
            content = content[1 + name_size:]
        if bool(flags & 4) != bool(size_flags & 0x40000000):
            decoded_size, = struct.unpack_from("<I", content)
            if decoded_size > 16 * 1024 * 1024:
                raise ValueError("Independent decoded-size limit")
            if version == 105:
                content = lz4_frame(content[4:], decoded_size)
            else:
                decoder = zlib.decompressobj()
                decoded = decoder.decompress(content[4:], decoded_size + 1)
                if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail or len(decoded) != decoded_size:
                    raise ValueError("Zlib framing, trailing input or decoded-size mismatch")
                content = decoded
        entries.append({"name": (folder + b"\\" + basename).decode("ascii"),
                        "size": len(content), "payload_sha256": hashlib.sha256(content).hexdigest()})
    if position != names_end or payload_position != len(raw):
        raise ValueError("Noncanonical section extent")
    family = {103: "bsa-067", 104: "bsa-068", 105: "bsa-069"}[version]
    return {"family": family, "entries": entries}


if __name__ == "__main__":
    print(json.dumps({"projection": inspect(Path(sys.argv[1]))}, separators=(",", ":")))
