"""Generate CC0 TES4 wire vectors directly from the written BSA specification."""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import zlib


def wire_hash(name, extension=False):
    """Compute the specified ASCII BSA hash without invoking product implementation."""
    stem, suffix = name, b""
    if extension and b"." in name:
        at = name.rindex(b".")
        stem, suffix = name[:at], name[at:]
    low = stem[-1] | (len(stem) << 16) | (stem[0] << 24)
    if len(stem) > 2:
        low |= stem[-2] << 8
    low |= {b".kf": 0x80, b".nif": 0x8000, b".dds": 0x8080,
            b".wav": 0x80000000}.get(suffix, 0)
    values = []
    for sequence in (stem[1:-2], suffix):
        value = 0
        for octet in sequence:
            value = (octet + 65599 * value) & 0xffffffff
        values.append(value)
    return (((sum(values)) & 0xffffffff) << 32) | low


def archive(mode):
    """Serialize one folder and two ordered files, including explicit compressed framing."""
    folder = b"meshes"
    names = [b"a.nif", b"b.nif"]
    payloads = [b"A" * 1024, bytes.fromhex("000102ff")]
    compression = [mode != "stored", mode == "zlib"]
    flags = 0x687 if mode != "stored" else 0x683
    name_table = b"".join(name + b"\0" for name in names)
    folder_block = bytes([len(folder) + 1]) + folder + b"\0"
    data_offset = 36 + 16 + len(folder_block) + 32 + len(name_table)
    data = bytearray()
    records = bytearray()
    for name, payload, compressed in zip(names, payloads, compression):
        packed = struct.pack("<I", len(payload)) + zlib.compress(payload, 9) if compressed else payload
        toggle = 0x40000000 if compressed != bool(flags & 4) else 0
        records += struct.pack("<QII", wire_hash(name, True), len(packed) | toggle,
                               data_offset + len(data))
        data += packed
    header = struct.pack("<4s8I", b"BSA\0", 103, 36, flags, 1, 2, 7,
                         len(name_table), 1)
    return (header + struct.pack("<QII", wire_hash(folder), 2, 52 + len(name_table))
            + folder_block + records + name_table + data)


def generate(destination):
    """Write deterministic text vectors and provenance; never invoke an archive product."""
    destination.mkdir(parents=True, exist_ok=True)
    cases = {mode: archive(mode) for mode in ("stored", "zlib", "mixed")}
    cases["truncated-payload"] = cases["stored"][:-1]
    bad = bytearray(cases["mixed"])
    # The first file record starts after the header, folder record and eight-byte folder block.
    first_payload, = struct.unpack_from("<I", bad, 36 + 16 + 8 + 12)
    struct.pack_into("<I", bad, first_payload, 1025)
    cases["decoded-size-mismatch"] = bytes(bad)
    manifest = {"schema_version": 1, "corpus_id": "jbsa-bsa-067-wire-vectors-v1",
                "creator": "JBSA project contributors", "spdx_license": "CC0-1.0",
                "redistribution_class": "project-authored-redistributable",
                "source": "docs/spec/formats/versioned-bsa.md; independently authored wire vectors",
                "generator": {"path": "build/generate-bsa-fixtures.py", "version": "1",
                              "spdx_license": "Apache-2.0",
                              "sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                              "command": "python build/generate-bsa-fixtures.py --output <directory>"},
                "fixtures": []}
    for name, content in cases.items():
        filename = "bsa-067-" + name + ".hex"
        encoded = (content.hex() + "\n").encode("ascii")
        (destination / filename).write_bytes(encoded)
        manifest["fixtures"].append({"id": "bsa-067-" + name, "path": filename,
                                    "representation": "lowercase hexadecimal followed by LF; decode before archive use",
                                    "wire_size": len(content),
                                    "wire_sha256": hashlib.sha256(content).hexdigest(),
                                    "file_sha256": hashlib.sha256(encoded).hexdigest(),
                                    "source": "independently authored BSA-002/003/006/008/009 wire vector; " + name,
                                    "oracle_sha256": None})
    (destination / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n"
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    generate(parser.parse_args().output)
