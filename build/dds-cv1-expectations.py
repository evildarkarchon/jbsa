"""Independent DDS BA2 expectations for a reviewable, unapproved CV1 proposal.

The wire parser and canonical DDS table follow docs/spec/formats/dds-ba2.md and
DDS-008..012 directly. Neither candidate code/output nor oracle code is imported.
A fixture's bytes are the sole input authority; generated expectations require
separate review before becoming accepted conformance goldens.
"""

import binascii
import hashlib
import json
from pathlib import Path
import struct
import sys
import zlib

LIMIT = 256 * 1024 * 1024
BC8 = {71, 72, 80, 81}
BC16 = {74, 75, 77, 78, 83, 84, 95, 96, 98, 99}
PIXELS = {28: 4, 29: 4, 87: 4, 91: 4, 88: 4, 93: 4,
          85: 2, 86: 2, 49: 2, 65: 1, 61: 1}
LEGACY_FOURCC = {71: b'DXT1', 74: b'DXT3', 77: b'DXT5', 80: b'BC4U',
                 81: b'BC4S', 83: b'BC5U', 84: b'BC5S'}
# DDS-009 literal rows: pixel flags, bit count, red, green, blue, alpha masks.
LEGACY_PIXELS = {
    28: (0x41, 32, 0xff, 0xff00, 0xff0000, 0xff000000),
    87: (0x41, 32, 0xff0000, 0xff00, 0xff, 0xff000000),
    88: (0x40, 32, 0xff0000, 0xff00, 0xff, 0),
    85: (0x40, 16, 0xf800, 0x7e0, 0x1f, 0),
    86: (0x41, 16, 0x7c00, 0x3e0, 0x1f, 0x8000),
    49: (0x20001, 16, 0xff, 0, 0, 0xff00),
    65: (2, 8, 0, 0, 0, 0xff),
    61: (0x20000, 8, 0xff, 0, 0, 0),
}


def diagnostic(identifier, ordinal=None, name=None, field=None, offset=None, length=None,
               values=None, severity='WARNING', operation='OPEN'):
    """Project stable semantic evidence without machine-dependent absolute input paths."""
    return {'identifier': identifier, 'severity': severity, 'operation': operation,
            'affected': {'entry_ordinal': ordinal, 'entry_name': name, 'field': field,
                         'byte_span': None if offset is None else {'offset': offset, 'length': length}},
            'values': values or {}}


def failure(identifier, kind='FORMAT', operation='OPEN', ordinal=None, name=None, values=None):
    """Describe an independent expected rejection at the operation boundary that discovers it."""
    assessment = None
    if kind == 'FORMAT':
        extent = {'kind': 'payloads', 'ordinals': [ordinal]} if operation == 'READ_CONTENT' else {'kind': 'structure'}
        assessment = {'disposition': 'REJECTED', 'extent': extent}
    if operation == 'EXTRACT':
        assessment = {'disposition': 'CONFORMING', 'extent': {'kind': 'structure'}}
    return {'failure_kind': kind, 'assessment': assessment,
            'diagnostics': [diagnostic(identifier, ordinal, name, values=values,
                                       severity='ERROR', operation=operation)]}


def identity(name):
    """Return safe complete name identity with ASCII folding and separator normalization."""
    name = name.replace('/', '\\')
    if ':' in name or any(not part or part in ('.', '..') or part.endswith(('.', ' '))
                           for part in name.split('\\')):
        return None
    return ''.join(chr(ord(c) + 32) if 'A' <= c <= 'Z' else c for c in name)


def canonical_header(width, height, mips, fmt, cube=False, tile=0, target='PC'):
    """Render the specification's canonical envelope from independent literal DDS field tables."""
    if target not in ('PC', 'XBOX'):
        raise ValueError('DDS target must be PC or XBOX')
    if fmt not in BC8 | BC16 | PIXELS.keys():
        raise ValueError('Unsupported canonical DDS format')
    if not 0 < width <= 65535 or not 0 < height <= 65535 or not 0 <= mips <= 255 or not 0 <= tile <= 255:
        raise ValueError('Unrepresentable DDS metadata')
    mips = max(1, mips)
    legacy = fmt in LEGACY_FOURCC or fmt in LEGACY_PIXELS
    size = 164 if target == 'XBOX' else 128 if legacy else 148
    header = bytearray(size)
    struct.pack_into('<4sI', header, 0, b'DDS ', 124)
    block = fmt in BC8 | BC16
    pitch = ((width + 3) // 4) * ((height + 3) // 4) * (8 if fmt in BC8 else 16) if block else width * PIXELS[fmt]
    struct.pack_into('<6I', header, 8, 0x21007 | (0x80000 if block else 8),
                     height, width, pitch, 1, mips)
    struct.pack_into('<I', header, 76, 32)
    caps = 0x1000 | (0x400008 if mips > 1 else 0) | (8 if cube else 0)
    struct.pack_into('<II', header, 108, caps, 0xfe00 if cube else 0)
    if size > 128:
        struct.pack_into('<I4s', header, 80, 4, b'XBOX' if target == 'XBOX' else b'DX10')
        struct.pack_into('<5I', header, 128, fmt, 3, 4 if cube else 0, 1, 0)
        if target == 'XBOX':
            struct.pack_into('<4I', header, 148, tile, 0, 0, 10705)
    elif fmt in LEGACY_FOURCC:
        struct.pack_into('<I4s', header, 80, 4, LEGACY_FOURCC[fmt])
    else:
        flags, bits, red, green, blue, alpha = LEGACY_PIXELS[fmt]
        struct.pack_into('<7I', header, 80, flags, 0, bits, red, green, blue, alpha)
    return bytes(header)


def _name_warnings(display, ordinal):
    """Apply the inspection-only unsafe-name diagnostics after structural disposition is fixed."""
    result = []
    if display.startswith('\\') or len(display) > 1 and display[1] == ':':
        result.append(diagnostic('archive-name.absolute-path', ordinal, display))
    segments = display.split('\\')
    for index, segment in enumerate(segments):
        if segment in ('.', '..'):
            result.append(diagnostic('archive-name.traversal-segment', ordinal, display, segment,
                                     values={'segmentOrdinal': str(index)}))
            break
    for index, segment in enumerate(segments):
        stem = segment.split('.', 1)[0].upper()
        reserved = stem in {'CON', 'PRN', 'AUX', 'NUL'} or (
            len(stem) == 4 and stem[:3] in {'COM', 'LPT'} and stem[3] in '123456789')
        if reserved or any(c in segment for c in '\"*<>?|'):
            result.append(diagnostic('archive-name.windows-invalid-segment', ordinal, display, segment,
                                     values={'segmentOrdinal': str(index)}))
            break
    return result


def projection(raw, target='PC'):
    """Bound variable texture records and derive public semantics from fixture bytes alone."""
    if len(raw) > LIMIT:
        raise ValueError('Independent DDS expectation scanner input limit')
    if len(raw) < 24:
        return failure('io.invalid-span')
    magic, version, subtype, count, names_at = struct.unpack_from('<4sI4sIq', raw)
    if (magic, version, subtype) != (b'BTDX', 1, b'DX10'):
        return failure('archive.unsupported-variant', 'UNSUPPORTED')
    if 24 + count * 48 > len(raw):
        return failure('io.invalid-span')
    named = 0 < names_at < len(raw)
    warnings = []
    if not named:
        warnings.append(diagnostic('ba2.missing-name-table', field='fileNameTableOffset',
                                   offset=16, length=8, values={'stored': str(names_at)}))

    def reject(identifier, kind='FORMAT', **kwargs):
        """Preserve warnings encountered before a later bounded structural or payload failure."""
        result = failure(identifier, kind, **kwargs)
        result['diagnostics'] = warnings + result['diagnostics']
        return result

    def bounded(offset, length):
        """Check signed-64-bit spans before checking physical input bounds."""
        if offset < 0 or length < 0:
            return 'io.invalid-span'
        if offset + length > 0x7fffffffffffffff:
            return 'io.span-overflow'
        if offset > len(raw) or length > len(raw) - offset:
            return 'io.invalid-span'
        return None

    records_at, name_cursor, used_end = 24, names_at, 24
    entries, spans, pending, identities = [], [], [], set()
    decoded_budget = 0
    for ordinal in range(count):
        record_at = records_at
        error = bounded(record_at, 24)
        if error:
            return reject(error)
        base, extension, directory, mod, count_chunks, chunk_header, height, width, mips, fmt, flags, tile = struct.unpack_from('<I4sIBBHHHBBBB', raw, record_at)
        cube = bool(flags & 1)
        if not 1 <= count_chunks <= 4 or cube and count_chunks != 1:
            return reject('dx10.invalid-chunk-count')
        if not width or not height or not mips or mips > max(width, height).bit_length():
            return reject('dx10.invalid-mip-count')
        records_at += 24 + count_chunks * 24
        error = bounded(record_at + 24, count_chunks * 24)
        if error:
            return reject(error)
        next_mip = 0
        chunks, total_stored, total_decoded = [], 0, 0
        for index in range(count_chunks):
            chunk_at = record_at + 24 + index * 24
            offset, packed, unpacked, first, last, sentinel = struct.unpack_from('<qIIHHI', raw, chunk_at)
            if first != next_mip or last < first or last >= mips or index < count_chunks - 1 and last != first:
                return reject('dx10.invalid-mip-range')
            next_mip = last + 1
            stored = packed or unpacked
            error = bounded(offset, stored)
            if error:
                return reject(error)
            spans.append((offset, offset + stored))
            if stored:
                used_end = max(used_end, offset + stored)
            total_stored += stored
            total_decoded += unpacked
            decoded_budget += unpacked
            if decoded_budget > LIMIT:
                raise ValueError("Independent DDS expectation total decoded-byte limit")
            if not packed:
                warnings.append(diagnostic('dx10.stored-chunk', ordinal, field='chunkSizes',
                                           offset=chunk_at + 8, length=8,
                                           values={'stored': '0', 'unpacked': str(unpacked)}))
            if sentinel != 0xbaadf00d:
                warnings.append(diagnostic('dx10.sentinel-mismatch', ordinal, field='sentinel',
                                           offset=chunk_at + 20, length=4,
                                           values={'stored': f'{sentinel:08X}', 'expected': 'BAADF00D'}))
            chunks.append((offset, packed, unpacked, first, last))
        if next_mip != mips:
            return reject('dx10.invalid-mip-range')
        display = f'__jbsa_hash__\\d{directory:08x}\\e{ordinal:08x}-{base:08x}-x{extension.hex()}'
        wire, normalized = None, None
        if named:
            error = bounded(name_cursor, 2)
            if error:
                return reject(error)
            length, = struct.unpack_from('<H', raw, name_cursor)
            name_cursor += 2
            error = bounded(name_cursor, length)
            if error:
                return reject(error)
            wire = raw[name_cursor:name_cursor + length]
            if b'\0' in wire:
                return reject('ba2.nul-name')
            try:
                display = wire.decode('cp1252').replace('/', '\\')
                normalized = identity(display)
                if normalized is not None:
                    if normalized in identities:
                        return reject('ba2.duplicate-name')
                    identities.add(normalized)
                if all(value < 128 for value in wire):
                    folder, _, leaf = wire.replace(b'/', b'\\').rpartition(b'\\')
                    parts = leaf.rsplit(b'.', 1)
                    expected_base = binascii.crc32(parts[0].lower(), 0xffffffff) ^ 0xffffffff
                    expected_directory = binascii.crc32(folder.lower(), 0xffffffff) ^ 0xffffffff
                    expected_extension = (parts[1] if len(parts) == 2 else b'').lower()[:4].ljust(4, b'\0')
                    for value, expected, identifier, field, position in (
                            (f'{base:08X}', f'{expected_base:08X}', 'ba2.basename-hash-mismatch', 'baseNameHash', record_at),
                            (extension.hex().upper(), expected_extension.hex().upper(), 'ba2.extension-mismatch', 'extension', record_at + 4),
                            (f'{directory:08X}', f'{expected_directory:08X}', 'ba2.directory-hash-mismatch', 'directoryHash', record_at + 8)):
                        if value != expected:
                            warnings.append(diagnostic(identifier, ordinal, display, field, position, 4,
                                                       {'stored': value, 'expected': expected}))
            except UnicodeDecodeError:
                display = f'__jbsa_wire__\\e{ordinal:08x}'
                warnings.append(diagnostic('archive-name.undecodable-wire-bytes', ordinal,
                                           display, 'complete', name_cursor, length))
            name_cursor += length
        for actual, expected, identifier, field, position, length in (
                (mod, 0, 'dx10.nonzero-mod-index', 'modIndex', record_at + 12, 1),
                (chunk_header, 24, 'dx10.chunk-header-size', 'chunkHeaderSize', record_at + 14, 2)):
            if actual != expected:
                warnings.append(diagnostic(identifier, ordinal, display, field, position, length,
                                           {'stored': str(actual), 'expected': str(expected)}))
        if fmt not in BC8 | BC16 | PIXELS.keys():
            return reject('dds.unsupported-format', 'UNSUPPORTED')
        header = canonical_header(width, height, mips, fmt, cube, tile, target)
        compressed = sum(bool(c[1]) for c in chunks)
        entries.append({'decoded_name': display, 'wire_name_bytes': None if wire is None else wire.hex(),
                        'normalized_name_identity': normalized, 'basename_hash': base,
                        'directory_hash': directory, 'wire_hashes': {'basename': base, 'directory': directory},
                        'flags': {'texture': flags}, 'extension': extension.hex(), 'chunk_count': count_chunks,
                        'logical_size': total_decoded + len(header), 'stored_size': total_stored,
                        'decoded_size': total_decoded + len(header),
                        'compression_state': 'stored' if not compressed else 'zlib' if compressed == count_chunks else 'mixed',
                        'width': width, 'height': height, 'mip_count': mips, 'dxgi_format': fmt,
                        'cubemap': cube, 'tile_mode': tile, 'dds_header_sha256': hashlib.sha256(header).hexdigest()})
        pending.append((header, chunks, display))
    if named and names_at < records_at:
        return reject('ba2.names-overlap-index')
    used_end = max(used_end, records_at)
    if any(start < records_at for start, end in spans):
        return reject('ba2.payload-overlaps-index')
    previous = None
    for span in sorted(spans):
        if span[0] == span[1]:
            continue
        if named and span[0] < name_cursor and span[1] > names_at:
            return reject('ba2.payload-overlaps-names')
        if previous and span[0] < previous[1] and span != previous:
            return reject('ba2.overlapping-payloads')
        previous = span
    if named:
        used_end = max(used_end, name_cursor)
    if used_end < len(raw):
        warnings.append(diagnostic('ba2.trailing-data', field='trailingData', offset=used_end,
                                   length=len(raw) - used_end))
    disposition = 'TOLERATED_NONCANONICAL' if warnings else 'CONFORMING'
    for ordinal, (header, chunks, display) in enumerate(pending):
        whole = hashlib.sha256(header)
        projected_chunks = []
        for offset, packed, unpacked, first, last in chunks:
            if unpacked > LIMIT:
                raise ValueError('Independent DDS expectation decoded-byte limit')
            content = raw[offset:offset + (packed or unpacked)]
            if packed:
                values = {'codec': 'zlib', 'direction': 'decode', 'expected': str(unpacked),
                          'actual': '0', 'profile': 'jbsa-jdk-zlib-v1'}
                try:
                    decoder = zlib.decompressobj()
                    content = decoder.decompress(content, unpacked + 1)
                    if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
                        return reject('codec.invalid-data', operation='READ_CONTENT', ordinal=ordinal,
                                      name=display, values=values)
                    if len(content) != unpacked:
                        values['actual'] = str(len(content))
                        return reject('codec.size-mismatch', operation='READ_CONTENT', ordinal=ordinal,
                                      name=display, values=values)
                except zlib.error:
                    return reject('codec.invalid-data', operation='READ_CONTENT', ordinal=ordinal,
                                  name=display, values=values)
            whole.update(content)
            projected_chunks.append({'stored_size': packed or unpacked, 'decoded_size': unpacked,
                                     'start_mip': first, 'end_mip': last, 'first_mip': first, 'last_mip': last,
                                     'payload_sha256': hashlib.sha256(content).hexdigest()})
        entries[ordinal]['payload_sha256'] = whole.hexdigest()
        entries[ordinal]['reconstructed_dds_sha256'] = whole.hexdigest()
        entries[ordinal]['chunks'] = projected_chunks
    for ordinal, entry in enumerate(entries):
        warnings.extend(_name_warnings(entry['decoded_name'], ordinal))
    return {'archive_family': 'fo4-dx10-v1', 'wire_version': 1, 'subtype': 'DX10',
            'compression_method': None, 'disposition': disposition, 'entry_count': count,
            'entries': entries, 'diagnostics': warnings}



def reconstructed_files(raw, target='PC'):
    """Return independent canonical DDS files from a bounded, payload-valid archive fixture.

    Unsafe or undecodable names are refused because these returned relative names
    are intended for subsequent independent-validator fixture materialization.
    No files are written and no candidate output is read by this helper.
    """
    result = projection(raw, target)
    if 'failure_kind' in result:
        raise ValueError('Cannot reconstruct a rejected independent fixture')
    cursor = 24
    files = {}
    for entry in result['entries']:
        if entry['normalized_name_identity'] is None or entry['wire_name_bytes'] is None:
            raise ValueError('Cannot materialize an unsafe or absent fixture name')
        header = canonical_header(entry['width'], entry['height'], entry['mip_count'],
                                  entry['dxgi_format'], entry['cubemap'], entry['tile_mode'], target)
        cursor += 24
        content = bytearray(header)
        for _ in range(entry['chunk_count']):
            offset, packed, unpacked = struct.unpack_from('<qII', raw, cursor)
            cursor += 24
            payload = raw[offset:offset + (packed or unpacked)]
            if packed:
                payload = zlib.decompress(payload)
            content.extend(payload)
        files[entry['decoded_name'].replace('\\', '/')] = bytes(content)
    return files

if __name__ == '__main__':
    raw = Path(sys.argv[1]).read_bytes()
    target = sys.argv[2] if len(sys.argv) > 2 else 'PC'
    result = ({'pc':projection(raw), 'xbox':projection(raw,'XBOX'),
               'profile_inferred':projection(raw,'XBOX'), 'profile_explicit_pc':projection(raw)}
              if target == 'SELECTION' else projection(raw,target))
    print(json.dumps({'projection': result}, separators=(',', ':')))
