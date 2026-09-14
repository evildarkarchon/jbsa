"""Generate CC0 Skyrim SE/AE BSA vectors directly from the written wire specification."""

import argparse
import hashlib
import json
from pathlib import Path
import struct


def wire_hash(name, extension=False):
    """Compute the specified ASCII BSA hash independently of the product implementation."""
    stem, suffix = name, b""
    if extension and b"." in name:
        at = name.rindex(b".")
        stem, suffix = name[:at], name[at:]
    low = stem[-1] | (len(stem) << 16) | (stem[0] << 24)
    if len(stem) > 2:
        low |= stem[-2] << 8
    low |= {b".kf": 0x80, b".nif": 0x8000, b".dds": 0x8080,
            b".wav": 0x80000000}.get(suffix, 0)
    accumulators = []
    for sequence in (stem[1:-2], suffix):
        value = 0
        for octet in sequence:
            value = (octet + 65599 * value) & 0xffffffff
        accumulators.append(value)
    return (((sum(accumulators)) & 0xffffffff) << 32) | low


def lz4_frame(payload):
    """Emit one valid independent 4 MiB-profile frame with a literal uncompressed block."""
    # 0x73 is the independently worked XXH32 header byte for FLG/BD 0x60/0x70.
    descriptor = bytes.fromhex("607073")
    return (bytes.fromhex("04224d18") + descriptor
            + struct.pack("<I", 0x80000000 | len(payload)) + payload + bytes(4))


def archive(mode, embedded):
    """Serialize one 24-byte folder record and two ordered 0x69 payload records."""
    folder = b"meshes"
    names = [b"a.nif", b"b.nif"]
    payloads = [b"A" * 1024, bytes.fromhex("000102ff")]
    compression = [mode != "stored", mode == "lz4-frame"]
    flags = (0x87 if any(compression) else 0x83) | (0x100 if embedded else 0)
    name_table = b"".join(name + b"\0" for name in names)
    folder_block = bytes([len(folder) + 1]) + folder + b"\0"
    data_offset = 36 + 24 + len(folder_block) + 32 + len(name_table)
    data = bytearray()
    records = bytearray()
    for name, payload, compressed in zip(names, payloads, compression):
        packed = struct.pack("<I", len(payload)) + lz4_frame(payload) if compressed else payload
        if embedded:
            full_name = folder + b"\\" + name
            packed = bytes([len(full_name)]) + full_name + packed
        toggle = 0x40000000 if compressed != bool(flags & 4) else 0
        records += struct.pack("<QII", wire_hash(name, True), len(packed) | toggle,
                               data_offset + len(data))
        data += packed
    header = struct.pack("<4s8I", b"BSA\0", 105, 36, flags, 1, 2, len(folder) + 1,
                         len(name_table), 1)
    folder_record = struct.pack("<QIIII", wire_hash(folder), 2, 0, 60 + len(name_table), 0)
    return header + folder_record + folder_block + records + name_table + data


def generate(destination):
    """Write deterministic SE/AE vectors and content-addressed project provenance."""
    destination.mkdir(parents=True, exist_ok=True)
    generator_hash = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    scanner_path = Path(__file__).with_name("validate-bsa-wire.py")
    manifest = {
        "schema_version": 1,
        "corpus_id": "jbsa-bsa-069-wire-vectors-v1",
        "creator": "JBSA project contributors",
        "spdx_license": "CC0-1.0",
        "redistribution_class": "project-authored-redistributable",
        "source": "docs/spec/formats/versioned-bsa.md; independently authored SSE/AE vectors",
        "generators": [
            {"path": "build/generate-bsa069-fixtures.py", "sha256": generator_hash},
        ],
        "validators": [
            {"path": "build/validate-bsa-wire.py",
             "sha256": hashlib.sha256(scanner_path.read_bytes()).hexdigest()},
        ],
        "payloads": [
            {"name": "meshes/a.nif", "size": 1024,
             "sha256": hashlib.sha256(b"A" * 1024).hexdigest()},
            {"name": "meshes/b.nif", "size": 4,
             "sha256": hashlib.sha256(bytes.fromhex("000102ff")).hexdigest()},
        ],
        "fixtures": [],
    }
    for game in ("skyrim-se", "skyrim-ae"):
        for mode in ("stored", "lz4-frame", "mixed"):
            for embedded in (False, True):
                name = game + "-" + mode + ("-embedded" if embedded else "")
                wire = archive(mode, embedded)
                encoded = (wire.hex() + "\n").encode("ascii")
                (destination / (name + ".hex")).write_bytes(encoded)
                manifest["fixtures"].append({
                    "id": name,
                    "path": name + ".hex",
                    "game": game,
                    "cli_selector": "-sse",
                    "compression": mode,
                    "embedded_names": embedded,
                    "wire_size": len(wire),
                    "wire_sha256": hashlib.sha256(wire).hexdigest(),
                    "file_sha256": hashlib.sha256(encoded).hexdigest(),
                    "oracle_sha256": None,
                })
    (destination / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    generate(parser.parse_args().output)
