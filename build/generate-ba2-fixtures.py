"""Generate project-authored FO4 General BA2 v1 vectors from the written specification."""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import zlib


def wire_hash(component):
    """Use the specified reflected CRC recurrence, initial zero and no final XOR."""
    crc = 0
    for value in component.lower().replace(b"/", b"\\"):
        crc ^= value
        for _ in range(8):
            crc = (crc >> 1) ^ (0xedb88320 if crc & 1 else 0)
    return crc


def archive(mode):
    """Serialize two ordered, independently authored payloads without a product writer."""
    names = [b"meshes/a.nif", b"meshes/b.nif"]
    payloads = [b"A" * 1024, bytes.fromhex("000102ff")]
    records, data = bytearray(), bytearray()
    for index, (name, payload) in enumerate(zip(names, payloads)):
        compressed = mode == "zlib" or mode in ("mixed", "raw-deflate", "raw-lz4", "lz4-frame") and index == 0
        encoded = zlib.compress(payload, 9) if compressed else payload
        if mode == "raw-deflate" and compressed:
            encoded = zlib.compress(payload, 9, wbits=-15)
        if mode == "raw-lz4" and compressed:
            # A literal-only LZ4 block: token length 15 plus extension lengths totaling 1009.
            encoded = bytes.fromhex("f0ffffff f4") + payload
        if mode == "lz4-frame" and compressed:
            # Standard independent-block frame descriptor 60 40 has header checksum 82.
            encoded = bytes.fromhex("04224d18604082") + struct.pack("<I", 0x80000000 | len(payload)) + payload + bytes(4)
        folder, filename = name.rsplit(b"/", 1)
        stem, extension = filename.rsplit(b".", 1)
        records += struct.pack("<I4sIBBHQIII", wire_hash(stem), extension.ljust(4, b"\0"),
                               wire_hash(folder), 0, 1, 16, 96 + len(data),
                               len(encoded) if compressed else 0, len(payload), 0xbaadf00d)
        data += encoded
    names_table = b"".join(struct.pack("<H", len(name)) + name for name in names)
    return struct.pack("<4sI4sIQ", b"BTDX", 1, b"GNRL", 2, 96 + len(data)) + records + data + names_table


def generate(destination):
    """Write deterministic hex artifacts, expected dispositions and digest-bound provenance."""
    destination.mkdir(parents=True, exist_ok=True)
    cases = {mode: (archive(mode), "CONFORMING") for mode in ("stored", "zlib", "mixed")}
    for mode in ("raw-deflate", "raw-lz4", "lz4-frame"):
        cases[mode] = (archive(mode), "REJECTED_AT_PAYLOAD")
    bad = bytearray(archive("mixed"))
    struct.pack_into("<I", bad, 24 + 28, 1025)
    cases["decoded-size-mismatch"] = (bytes(bad), "REJECTED_AT_PAYLOAD")
    bad = bytearray(archive("stored"))
    struct.pack_into("<Q", bad, 24 + 16, len(bad) - 1)
    cases["truncated-payload"] = (bytes(bad), "REJECTED_AT_STRUCTURE")
    for name, field, value, width in (
            ("missing-name-table", 16, 0, "Q"),
            ("sentinel-mismatch", 24 + 32, 0, "I"),
            ("hash-mismatch", 24, 1, "I"),
            ("chunk-count", 24 + 13, 2, "B"),
            ("partial-overlap", 60 + 16, 97, "Q")):
        bad = bytearray(archive("stored"))
        struct.pack_into("<" + width, bad, field, value)
        disposition = "REJECTED_AT_STRUCTURE" if name in ("chunk-count", "partial-overlap") else "TOLERATED_NONCANONICAL"
        cases[name] = (bytes(bad), disposition)
    cases["trailing-data"] = (archive("stored") + b"TRAIL", "TOLERATED_NONCANONICAL")
    manifest = {"schema_version": 1, "corpus_id": "jbsa-fo4-general-wire-vectors-v1",
                "creator": "JBSA project contributors", "spdx_license": "CC0-1.0",
                "redistribution_class": "project-authored-redistributable",
                "source": "docs/spec/formats/general-ba2.md; independently authored wire vectors",
                "generator": {"path": "build/generate-ba2-fixtures.py", "version": "1",
                              "spdx_license": "Apache-2.0",
                              "sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                              "command": "python build/generate-ba2-fixtures.py --output <directory>"},
                "fixtures": []}
    for name, (content, disposition) in cases.items():
        filename = "fo4-general-" + name + ".hex"
        encoded = (content.hex() + "\n").encode("ascii")
        (destination / filename).write_bytes(encoded)
        manifest["fixtures"].append({"id": "fo4-general-" + name, "path": filename,
                                    "representation": "lowercase hexadecimal followed by LF; decode before archive use",
                                    "wire_size": len(content), "expected_disposition": disposition,
                                    "wire_sha256": hashlib.sha256(content).hexdigest(),
                                    "file_sha256": hashlib.sha256(encoded).hexdigest(),
                                    "oracle_sha256": None})
    (destination / "manifest.json").write_bytes((json.dumps(manifest, indent=2) + "\n").encode("utf-8"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    generate(parser.parse_args().output)
