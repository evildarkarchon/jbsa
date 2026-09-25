"""Materialize small, project-authored CC0 DDS inputs for issue #40.

The inputs deliberately use DX10 envelopes even where extraction chooses legacy
headers. Their opaque bytes are deterministic and are never game assets.
"""

import argparse
import hashlib
import json
from pathlib import Path
import struct


def texture(width, height, mips, format_id, cube=False):
    """Construct a 2D DX10 DDS with independently sized, deterministic mip bytes."""
    header = bytearray(148)
    header[:4] = b'DDS '
    for offset, value in {4: 124, 8: 0x21007, 12: height, 16: width, 24: 1,
                          28: mips, 76: 32, 80: 4, 108: 0x401008 if mips > 1 else 0x1000,
                          112: 0xfe00 if cube else 0, 128: format_id,
                          132: 3, 136: 4 if cube else 0, 140: 1}.items():
        struct.pack_into('<I', header, offset, value)
    header[84:88] = b'DX10'
    result = bytearray(header)
    for face in range(6 if cube else 1):
        w, h = width, height
        for mip in range(mips):
            if format_id in (71, 72, 80, 81):
                length = ((w + 3) // 4) * ((h + 3) // 4) * 8
            elif format_id in (74, 75, 77, 78, 83, 84, 95, 96, 98, 99):
                length = ((w + 3) // 4) * ((h + 3) // 4) * 16
            else:
                length = w * h * (2 if format_id in (85, 86, 49) else 1 if format_id in (65, 61) else 4)
            # Distinct repeated blocks expose wrong face, mip, and chunk ordering.
            block = hashlib.sha256(f'jbsa-dds-v1:{format_id}:{face}:{mip}'.encode()).digest()
            result.extend((block * ((length + 31) // 32))[:length])
            w, h = max(1, w // 2), max(1, h // 2)
    return bytes(result)


def main():
    """Write immutable generated files and an exact digest manifest to a fresh directory."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    source = args.output / 'source' / 'textures'
    source.mkdir(parents=True)
    definitions = [(f'format-{fmt}', 8, 8, 4, fmt, False) for fmt in
                   (71, 72, 74, 75, 77, 78, 80, 81, 83, 84, 95, 96, 98, 99,
                    28, 29, 87, 91, 88, 93, 85, 86, 49, 65, 61)]
    definitions += [('bc1-small', 1, 1, 1, 71, False), ('bc7-odd', 5, 7, 3, 98, False),
                    ('bc1-nonsquare', 1024, 8, 11, 71, False),
                    ('bc1-two-chunks', 512, 512, 10, 71, False),
                    ('bc7-three-chunks', 1024, 1024, 11, 98, False),
                    ('bc1-four-chunks', 2048, 2048, 12, 71, False),
                    ('bc1-odd-chunks', 513, 515, 10, 71, False),
                    ('bc1-cubemap', 8, 8, 4, 71, True)]
    records = []
    for name, width, height, mips, fmt, cube in definitions:
        raw = texture(width, height, mips, fmt, cube)
        path = source / (name + '.dds')
        path.write_bytes(raw)
        records.append({'path': path.relative_to(args.output).as_posix(), 'size': len(raw),
                        'sha256': hashlib.sha256(raw).hexdigest(), 'width': width,
                        'height': height, 'mips': mips, 'format': fmt, 'cubemap': cube})
    (args.output / 'manifest.json').write_text(json.dumps(
        {'generator': 'issue40-dds-v1', 'license': 'CC0-1.0', 'files': records}, indent=2) + '\n')
    print(json.dumps({'files': len(records), 'bytes': sum(r['size'] for r in records)}))


if __name__ == '__main__':
    main()
