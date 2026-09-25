"""Independent streaming validator for the fixed normative DDS performance corpus.

The source manifest is the external length/hash authority. Archive metadata is
checked independently against its structural recipe, then opaque chunks are
decompressed into bounded hash buffers. No product or Reference Snapshot code
is imported and no pixel decoder is claimed.
"""

import argparse
import binascii
import gzip
import hashlib
import json
from pathlib import Path
import struct
import zlib


def manifest(path):
    """Read a bound full or selected JSON corpus manifest."""
    opener = gzip.open if path.suffix == '.gz' else open
    with opener(path, 'rt', encoding='utf-8') as stream:
        return json.load(stream)


def projection(document):
    """Describe the exact independent input set without archive incidental layout fields."""
    files = sorted(({key: item[key] for key in ('path', 'length', 'sha256')}
                    for item in document['files']), key=lambda item: item['path'])
    encoded = json.dumps(files, sort_keys=True, separators=(',', ':')).encode()
    return {'family': 'fo4-dx10-v1', 'entry_count': len(files),
            'logical_bytes': sum(item['length'] for item in files),
            'files_sha256': hashlib.sha256(encoded).hexdigest()}


def read_exact(stream, count):
    """Reject a truncated metadata or payload span."""
    data = stream.read(count)
    if len(data) != count:
        raise ValueError('Truncated archive')
    return data


def header(width, height, mips, fmt, cube):
    """Construct canonical legacy DDS headers for the normative BC corpus formats."""
    fourcc = {71: b'DXT1', 74: b'DXT3', 77: b'DXT5', 80: b'BC4U', 81: b'BC4S',
              83: b'BC5U', 84: b'BC5S'}.get(fmt)
    if fourcc is None or not width or not height or not mips:
        raise ValueError('Format or dimensions outside normative DDS corpus')
    result = bytearray(128)
    result[:4] = b'DDS '
    linear = ((width + 3) // 4) * ((height + 3) // 4) * (8 if fmt in (71, 80, 81) else 16)
    struct.pack_into('<7I', result, 4, 124, 0xa1007, height, width, linear, 1, mips)
    struct.pack_into('<II4s', result, 76, 32, 4, fourcc)
    caps = 0x1000 | (0x400008 if mips > 1 else 0) | (8 if cube else 0)
    struct.pack_into('<II', result, 108, caps, 0xfe00 if cube else 0)
    return result


def hash_chunk(stream, packed, unpacked, digest):
    """Hash exactly one independently framed zlib chunk with at most 1 MiB output buffers."""
    decoder, remaining, decoded = zlib.decompressobj(), packed, 0
    while remaining:
        data = read_exact(stream, min(65536, remaining))
        remaining -= len(data)
        while data:
            output = decoder.decompress(data, min(1048576, unpacked - decoded + 1))
            decoded += len(output)
            if decoded > unpacked:
                raise ValueError('Chunk exceeds declared decoded size')
            digest.update(output)
            if decoder.unused_data:
                raise ValueError('Trailing bytes after zlib stream')
            data = decoder.unconsumed_tail
    if not decoder.eof or decoded != unpacked:
        raise ValueError('Incomplete zlib stream or decoded-size mismatch')


def inspect_archive(path, document):
    """Validate exact DDS metadata, chunk geometry, zlib framing and reconstructed hashes."""
    expected = {entry['path']: entry for entry in document['files']}
    if len(expected) != len(document['files']):
        raise ValueError('Duplicate manifest path')
    total_size = path.stat().st_size
    with path.open('rb') as stream:
        magic, version, kind, count, name_offset = struct.unpack('<4sI4sIq', read_exact(stream, 24))
        if (magic, version, kind, count) != (b'BTDX', 1, b'DX10', len(expected)):
            raise ValueError('Archive selector or entry count mismatch')
        records, spans = [], set()
        for _ in range(count):
            fields = struct.unpack('<I4sIBBHHHBBBB', read_exact(stream, 24))
            _, _, _, mod, chunks, size, h, w, mips, fmt, flags, tile = fields
            if mod or size != 24 or not 1 <= chunks <= 4 or flags & ~1:
                raise ValueError('Noncanonical DDS record constants')
            chunk_records = [struct.unpack('<qIIHHI', read_exact(stream, 24)) for _ in range(chunks)]
            records.append((fields, chunk_records))
            for offset, packed, unpacked, start, end, sentinel in chunk_records:
                if packed == 0 or sentinel != 0xbaadf00d or offset < 0 or offset + packed > name_offset:
                    raise ValueError('Invalid or stored production chunk')
                spans.add((offset, offset + packed))
        table_end = stream.tell()
        if not table_end <= name_offset <= total_size:
            raise ValueError('Invalid filename table offset')
        previous = table_end
        for start, end in sorted(spans):
            if start < previous:
                raise ValueError('Payload overlaps metadata or another partial span')
            previous = end
        # The oracle reserves space for four chunk headers per entry. Physical
        # padding is excluded by CONF-010; referenced spans must still be disjoint.
        stream.seek(name_offset)
        names = []
        for _ in range(count):
            length, = struct.unpack('<H', read_exact(stream, 2))
            name = read_exact(stream, length).decode('ascii').replace('\\', '/')
            if not name or '\0' in name:
                raise ValueError('Invalid archive name')
            names.append(name)
        if stream.tell() != total_size or len(set(names)) != len(names) or set(names) != set(expected):
            raise ValueError('Filename table differs from the input set')
        for name, (fields, chunks) in zip(names, records):
            stem_hash, extension, directory_hash, _, _, _, h, w, mips, fmt, flags, _ = fields
            folder, _, filename = name.lower().replace('/', '\\').rpartition('\\')
            stem, _, suffix = filename.rpartition('.')
            crc = lambda value: binascii.crc32(value.encode('ascii'), 0xffffffff) ^ 0xffffffff
            if (stem_hash, directory_hash, extension) != (crc(stem), crc(folder), suffix.encode()[:4].ljust(4, b'\0')):
                raise ValueError('Name hash mismatch')
            item = expected[name]
            meta = item['structural']
            if (w, h, mips, fmt, bool(flags & 1)) != (meta['width'], meta['height'], meta['mip_count'],
                                                     meta['dxgi_format'], meta['cubemap']):
                raise ValueError('Texture metadata differs from external recipe')
            if len(chunks) != len(meta['chunks']):
                raise ValueError('Chunk partition count mismatch')
            digest = hashlib.sha256(header(w, h, mips, fmt, bool(flags & 1)))
            for record, recipe in zip(chunks, meta['chunks']):
                offset, packed, unpacked, start, end, _ = record
                if (unpacked, start, end) != (recipe['length'], recipe['start_mip'], recipe['end_mip']):
                    raise ValueError('Dimensions-derived mip span mismatch')
                stream.seek(offset)
                hash_chunk(stream, packed, unpacked, digest)
            if 128 + sum(chunk[2] for chunk in chunks) != item['length'] or digest.hexdigest() != item['sha256']:
                raise ValueError('Canonical reconstructed DDS differs from external source identity')
    return projection(document)


def inspect_extraction(path, document):
    """Compare the exact output file tree and bytes to the immutable manifest."""
    expected = {item['path']: item for item in document['files']}
    actual = {item.relative_to(path).as_posix(): item for item in path.rglob('*') if item.is_file()}
    if set(actual) != set(expected):
        raise ValueError('Extracted file set differs from manifest')
    for name, file in actual.items():
        with file.open('rb') as stream:
            sha = hashlib.file_digest(stream, 'sha256').hexdigest()
        if file.stat().st_size != expected[name]['length'] or sha != expected[name]['sha256']:
            raise ValueError('Extracted DDS differs from manifest')
    return projection(document)


def main():
    """Implement the pinned PV1 validator process contract outside timed operations."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--case-id', required=True)
    parser.add_argument('--role', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        source = manifest(args.manifest)
        value = (inspect_archive(args.output / 'archive.bsa', source)
                 if args.case_id.startswith('PV1-pack-') else inspect_extraction(args.output / 'extracted', source))
        result = {'case_id': args.case_id, 'outcome': 'PASS', 'projection': value}
    except (ValueError, OSError, KeyError, zlib.error, struct.error) as error:
        result = {'case_id': args.case_id, 'outcome': 'FAIL', 'reason': str(error)}
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
