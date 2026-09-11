"""Generate redistributable 0x68 vectors without product or Reference Snapshot code."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import struct


def archive(mode, embedded):
    """Apply specified 0x68 header and payload framing to the shared independent wire recipe."""
    source = Path(__file__).with_name("generate-bsa-fixtures.py")
    spec = importlib.util.spec_from_file_location("common_bsa_recipe", source)
    recipe = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(recipe)
    wire = bytearray(recipe.archive(mode))
    struct.pack_into("<I", wire, 4, 104)
    flags = struct.unpack_from("<I", wire, 12)[0] & ~0x600
    struct.pack_into("<I", wire, 12, flags | (0x100 if embedded else 0))
    if embedded:
        # Insert backwards to keep unread source offsets valid, then fix later absolute offsets.
        for ordinal, name in reversed(list(enumerate((b"a.nif", b"b.nif")))):
            record = 60 + ordinal * 16
            size, offset = struct.unpack_from("<II", wire, record + 8)
            full_name = b"meshes\\" + name
            prefix = bytes([len(full_name)]) + full_name
            wire[offset:offset] = prefix
            struct.pack_into("<I", wire, record + 8, size + len(prefix))
            for later in range(ordinal + 1, 2):
                field = 60 + later * 16 + 12
                struct.pack_into("<I", wire, field, struct.unpack_from("<I", wire, field)[0] + len(prefix))
    return bytes(wire)


def generate(destination):
    """Write per-game mode fixtures and reproducible provenance with source payload identities."""
    destination.mkdir(parents=True, exist_ok=True)
    manifest = {"schema_version": 1, "corpus_id": "jbsa-bsa-068-wire-vectors-v1",
                "spdx_license": "CC0-1.0", "creator": "JBSA project contributors",
                "redistribution_class": "project-authored-redistributable",
                "source": "docs/spec/formats/versioned-bsa.md; synthetic shared-family game-selector vectors",
                "generators": [{"path": "build/" + name, "sha256": hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()}
                               for name in ("generate-bsa068-fixtures.py", "generate-bsa-fixtures.py")],
                "payloads": [{"name": "meshes/a.nif", "size": 1024, "sha256": hashlib.sha256(b"A" * 1024).hexdigest()},
                             {"name": "meshes/b.nif", "size": 4, "sha256": hashlib.sha256(bytes.fromhex("000102ff")).hexdigest()}],
                "fixtures": []}
    for game, selector in (("fallout3", "fo3"), ("new-vegas", "fnv"), ("skyrim-le", "tes5")):
        for mode in ("stored", "zlib", "mixed"):
            for embedded in (False, True):
                name = game + "-" + mode + ("-embedded" if embedded else "")
                wire = archive(mode, embedded)
                content = (wire.hex() + "\n").encode("ascii")
                (destination / (name + ".hex")).write_bytes(content)
                manifest["fixtures"].append({"id": name, "path": name + ".hex", "game": game,
                                             "cli_selector": "-" + selector, "compression": mode,
                                             "embedded_names": embedded, "wire_size": len(wire),
                                             "wire_sha256": hashlib.sha256(wire).hexdigest(),
                                             "file_sha256": hashlib.sha256(content).hexdigest(),
                                             "oracle_sha256": None})
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    generate(parser.parse_args().output)
