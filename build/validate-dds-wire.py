"""Independent specification-based DDS and canonical DX10 BA2 validator.

Imports neither JBSA nor Reference Snapshot code. This bounded development scanner
checks opaque texture sizes and chunk metadata; it does not decode pixels and is
not a substitute for the separately required DirectXTex qualification.
"""

import argparse
import binascii
import hashlib
import json
from pathlib import Path
import struct
import zlib


BC8 = {71, 72, 80, 81}
BC16 = {74, 75, 77, 78, 83, 84, 95, 96, 98, 99}
BYTES = {28: 4, 29: 4, 87: 4, 91: 4, 88: 4, 93: 4,
         85: 2, 86: 2, 49: 2, 65: 1, 61: 1}
FOURCC = {b'DXT1': 71, b'DXT3': 74, b'DXT5': 77, b'ATI1': 80,
          b'BC4U': 80, b'BC4S': 81, b'ATI2': 83, b'BC5U': 83, b'BC5S': 84}
LIMIT = 256 * 1024 * 1024


def mip_sizes(width, height, mips, format_id):
    """Compute independent dimension-derived byte lengths for one face's mip chain."""
    if not 0 < width <= 65535 or not 0 < height <= 65535 or not 0 < mips <= 255:
        raise ValueError('Unrepresentable texture dimensions or mip count')
    result = []
    for _ in range(mips):
        if format_id in BC8 | BC16:
            result.append(((width + 3) // 4) * ((height + 3) // 4) *
                          (8 if format_id in BC8 else 16))
        elif format_id in BYTES:
            result.append(width * height * BYTES[format_id])
        else:
            raise ValueError('Format outside initial writable allow-list')
        width, height = max(1, width // 2), max(1, height // 2)
    return result


def inspect_dds(raw):
    """Validate supported 2D DDS envelopes and exact opaque payload bounds."""
    if len(raw) < 128 or raw[:4] != b'DDS ':
        raise ValueError('Missing or truncated DDS header')
    field = lambda offset: struct.unpack_from('<I', raw, offset)[0]
    if field(4) != 124 or field(76) != 32:
        raise ValueError('Invalid DDS structure sizes')
    width, height, mips = field(16), field(12), max(1, field(28))
    if field(24) not in (0, 1) or field(112) & 0x200000:
        raise ValueError('Volume DDS unsupported')
    cube = bool(field(112) & 0x200)
    fourcc = raw[84:88]
    envelope, tile, target = 128, 0, 'PC'
    if fourcc in (b'DX10', b'XBOX'):
        envelope = 164 if fourcc == b'XBOX' else 148
        if len(raw) < envelope:
            raise ValueError('Truncated extended DDS envelope')
        fmt, dimension, misc, array_size, _ = struct.unpack_from('<5I', raw, 128)
        if dimension != 3 or array_size != 1:
            raise ValueError('Only a single 2D resource is supported')
        cube = bool(misc & 4)
        if envelope == 164:
            target, tile = 'XBOX', field(148)
            if tile > 255:
                raise ValueError('Tile mode exceeds BA2 field')
    elif fourcc in FOURCC and field(80) & 4:
        fmt = FOURCC[fourcc]
    else:
        flags, bits, red, green, blue, alpha = struct.unpack_from('<6I', raw, 80)[0], field(88), field(92), field(96), field(100), field(104)
        if bits == 32 and flags & 0x40:
            fmt = 88 if not flags & 1 else 28 if red == 255 else 87
        elif bits == 16 and (red, green, blue, alpha) == (0xf800, 0x7e0, 0x1f, 0):
            fmt = 85
        elif bits == 16 and (red, green, blue, alpha) == (0x7c00, 0x3e0, 0x1f, 0x8000):
            fmt = 86
        elif bits == 16 and flags & 0x20000:
            fmt = 49
        elif bits == 8 and flags & 2:
            fmt = 65
        elif bits == 8 and flags & 0x20000:
            fmt = 61
        else:
            raise ValueError('Unsupported legacy DDS format')
    sizes = mip_sizes(width, height, mips, fmt)
    required = sum(sizes) * (6 if cube else 1)
    if len(raw) != envelope + required:
        raise ValueError('DDS payload does not match dimensions-derived mip chains')
    return {'width': width, 'height': height, 'mip_count': mips, 'format': fmt,
            'cubemap': cube, 'tile_mode': tile, 'target': target,
            'payload_size': required, 'payload_sha256': hashlib.sha256(raw[envelope:]).hexdigest()}


def decode_raw_lz4(block, expected_size):
    """Decode one raw-LZ4 block while requiring complete input and exact bounded output."""
    source, output = 0, bytearray()

    def extended_length(base):
        """Consume one bounded LZ4 literal or match extension chain."""
        nonlocal source
        length = base
        if base == 15:
            while True:
                if source >= len(block):
                    raise ValueError('Truncated raw-LZ4 length')
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
            raise ValueError('Raw-LZ4 literal exceeds bounded input or output')
        output.extend(block[source:literal_end])
        source = literal_end
        if source == len(block):
            break
        if source + 2 > len(block):
            raise ValueError('Truncated raw-LZ4 match offset')
        distance = block[source] | block[source + 1] << 8
        source += 2
        if distance == 0 or distance > len(output):
            raise ValueError('Invalid raw-LZ4 match offset')
        match_length = extended_length(token & 0x0f) + 4
        if len(output) + match_length > expected_size:
            raise ValueError('Raw-LZ4 match exceeds bounded output')
        for _ in range(match_length):
            output.append(output[-distance])
    if source != len(block) or len(output) != expected_size:
        raise ValueError('Raw-LZ4 trailing input or decoded-size mismatch')
    return bytes(output)


def inspect_archive(raw):
    """Validate canonical DX10 structure, hashes, independent codec frames and mip spans."""
    if len(raw) < 24:
        raise ValueError('Truncated BA2 header')
    magic, version, kind, count, names_offset = struct.unpack_from('<4sI4sIq', raw)
    if magic != b'BTDX' or kind != b'DX10' or version not in (1, 2, 3) or count > 100000:
        raise ValueError('Unsupported archive envelope')
    header_size = {1: 24, 2: 32, 3: 36}[version]
    if len(raw) < header_size:
        raise ValueError('Truncated Starfield extra header')
    method = None
    if version >= 2:
        extra, = struct.unpack_from('<Q', raw, 24)
        if extra != 1:
            raise ValueError('Noncanonical Starfield extra header')
    if version == 3:
        method, = struct.unpack_from('<I', raw, 32)
        if method != 3:
            raise ValueError('Unsupported Starfield compression method')
    cursor, records = header_size, []
    for _ in range(count):
        if cursor + 24 > len(raw):
            raise ValueError('Truncated texture record')
        record = struct.unpack_from('<I4sIBBHHHBBBB', raw, cursor)
        stem, extension, directory, mod, chunks, header, height, width, mips, fmt, flags, tile = record
        if mod != 0 or header != 24 or not 1 <= chunks <= 4 or flags & ~1:
            raise ValueError('Noncanonical texture metadata')
        sizes = mip_sizes(width, height, mips, fmt)
        cube = bool(flags & 1)
        expected_count, w, h = 1, width, height
        while expected_count < min(4, mips) and w >= 512 and h >= 512 and not cube:
            expected_count += 1
            w, h = w // 2, h // 2
        if chunks != expected_count:
            raise ValueError('Noncanonical mip partition count')
        cursor += 24
        chunk_records = []
        for chunk in range(chunks):
            if cursor + 24 > len(raw):
                raise ValueError('Truncated chunk record')
            offset, packed, unpacked, start, end, sentinel = struct.unpack_from('<qIIHHI', raw, cursor)
            expected_end = mips - 1 if chunk == chunks - 1 else chunk
            expected_size = sum(sizes[chunk:expected_end + 1]) * (6 if cube else 1)
            if (start, end, sentinel, unpacked) != (chunk, expected_end, 0xbaadf00d, expected_size) or not packed:
                raise ValueError('Noncanonical chunk range, size, sentinel or compression')
            chunk_records.append((offset, packed, unpacked, start, end))
            cursor += 24
        records.append((stem, extension, directory, width, height, mips, fmt, cube, tile, chunk_records))
    table_end = cursor
    if not table_end <= names_offset <= len(raw):
        raise ValueError('Invalid name table offset')
    cursor, spans, entries, identities, total = names_offset, set(), [], set(), 0
    for stem, extension, directory, width, height, mips, fmt, cube, tile, chunks in records:
        if cursor + 2 > len(raw):
            raise ValueError('Truncated name length')
        length, = struct.unpack_from('<H', raw, cursor)
        cursor += 2
        name = raw[cursor:cursor + length]
        cursor += length
        if len(name) != length or b'\0' in name:
            raise ValueError('Invalid name bytes')
        normalized = name.decode('ascii').lower().replace('/', '\\')
        if normalized in identities:
            raise ValueError('Duplicate names')
        identities.add(normalized)
        folder, _, leaf = normalized.rpartition('\\')
        basename, _, suffix = leaf.rpartition('.')
        crc = lambda value: binascii.crc32(value.encode('ascii'), 0xffffffff) ^ 0xffffffff
        if (stem, directory, extension) != (crc(basename), crc(folder), suffix.encode()[:4].ljust(4, b'\0')):
            raise ValueError('Name hash mismatch')
        digest, chunk_projection = hashlib.sha256(), []
        for offset, packed, unpacked, start, end in chunks:
            if offset < table_end or packed > names_offset - offset:
                raise ValueError('Chunk span outside payload region')
            total += unpacked
            if total > LIMIT:
                raise ValueError('Scanner decoded byte limit exceeded')
            content = raw[offset:offset + packed]
            if method == 3:
                decoded = decode_raw_lz4(content, unpacked)
            else:
                decoder = zlib.decompressobj()
                decoded = decoder.decompress(content, unpacked + 1)
                if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail or len(decoded) != unpacked:
                    raise ValueError('Invalid zlib chunk framing or decoded size')
            digest.update(decoded)
            spans.add((offset, offset + packed))
            chunk_projection.append({'start_mip': start, 'end_mip': end, 'unpacked_size': unpacked})
        entries.append({'name': normalized, 'width': width, 'height': height,
                        'mip_count': mips, 'format': fmt, 'cubemap': cube, 'tile_mode': tile,
                        'payload_size': sum(c[2] for c in chunks), 'payload_sha256': digest.hexdigest(),
                        'chunks': chunk_projection})
    if cursor != len(raw):
        raise ValueError('Trailing filename table bytes')
    previous = table_end
    for start, end in sorted(spans):
        if start != previous:
            raise ValueError('Payload gap or partial overlap')
        previous = end
    if previous != names_offset:
        raise ValueError('Unreferenced payload bytes')
    family = 'fo4-dx10-v1' if version == 1 else f'sf-dx10-v{version}' + ('-m3' if method == 3 else '')
    projection = {'family': family, 'entries': entries}
    if version >= 2:
        projection.update({'version': version, 'compression_method': method})
    return projection


def main():
    """Print a normalized projection for one bounded DDS or BA2 input."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    args = parser.parse_args()
    if args.input.stat().st_size > LIMIT:
        raise ValueError('Scanner input exceeds 256 MiB')
    raw = args.input.read_bytes()
    projection = inspect_dds(raw) if raw[:4] == b'DDS ' else inspect_archive(raw)
    print(json.dumps({'projection': projection}, sort_keys=True))


if __name__ == '__main__':
    main()
