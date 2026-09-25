"""Independent, bounded BA2 semantic expectations for an unapproved CV1 successor proposal.

This scanner follows the written specification and does not import JBSA, its
fixture generator or Reference Snapshot code. It excludes incidental offsets
and constants from semantic equality but retains diagnostic wire locations.
"""

import binascii
import hashlib
import json
import struct
import sys
from pathlib import Path
import zlib


def diagnostic(identifier, ordinal=None, name=None, field=None, offset=None, length=None,
               values=None, severity="WARNING", operation="OPEN"):
    """Represent the public diagnostic contract without machine-specific input paths."""
    return {"identifier": identifier, "severity": severity, "operation": operation,
            "affected": {"entry_ordinal": ordinal, "entry_name": name, "field": field,
                         "byte_span": None if offset is None else {"offset": offset, "length": length}},
            "values": values or {}}


def failure(identifier, kind="FORMAT", operation="OPEN", ordinal=None, name=None, values=None):
    """Specify an operation-owned rejection independently of a candidate observation."""
    assessment = None
    if kind == "FORMAT":
        extent = {"kind": "payloads", "ordinals": [ordinal]} if operation == "READ_CONTENT" else {"kind": "structure"}
        assessment = {"disposition": "REJECTED", "extent": extent}
    if operation == "EXTRACT":
        assessment = {"disposition": "CONFORMING", "extent": {"kind": "structure"}}
    return {"failure_kind": kind, "assessment": assessment, "diagnostics": [diagnostic(identifier, ordinal, name,
            severity="ERROR", operation=operation, values=values)]}


def identity(name):
    """Derive an identity only for safe complete names; use ASCII-only case folding."""
    name = name.replace("/", "\\")
    if ":" in name or any(not part or part in (".", "..") or part.endswith((".", " "))
                           for part in name.split("\\")):
        return None
    return "".join(chr(ord(value) + 32) if "A" <= value <= "Z" else value for value in name)


def projection(raw):
    """Interpret bounded General v1 structure and consume all payloads into exact semantics."""
    if len(raw) > 64 * 1024 * 1024:
        raise ValueError("Independent expectation scanner limit")
    if len(raw) < 24:
        return failure("io.invalid-span")
    magic, version, subtype, count, name_offset = struct.unpack_from("<4sI4sIq", raw)
    if (magic, version, subtype) != (b"BTDX", 1, b"GNRL"):
        return failure("archive.unsupported-variant", "UNSUPPORTED")
    table_end = 24 + 36 * count
    if table_end > len(raw):
        return failure("io.invalid-span")
    named = 0 < name_offset < len(raw)
    if named and name_offset < table_end:
        return failure("ba2.names-overlap-index")
    warnings, entries, spans, identities = [], [], [], set()
    if not named:
        warnings.append(diagnostic("ba2.missing-name-table", field="fileNameTableOffset",
                                   offset=16, length=8, values={"stored": str(name_offset)}))
    cursor, used_end = name_offset, table_end
    pending = []
    for index in range(count):
        at = 24 + index * 36
        base, extension, folder, mod, chunks, chunk_size, offset, packed, unpacked, sentinel = struct.unpack_from("<I4sIBBHqIII", raw, at)
        if chunks != 1:
            return failure("gnrl.invalid-chunk-count")
        display = f"__jbsa_hash__\\d{folder:08x}\\e{index:08x}-{base:08x}-x{extension.hex()}"
        wire, normalized = None, None
        if named:
            if cursor + 2 > len(raw):
                return failure("io.invalid-span")
            length, = struct.unpack_from("<H", raw, cursor)
            cursor += 2
            if cursor + length > len(raw):
                return failure("io.invalid-span")
            wire = raw[cursor:cursor + length]
            if b"\0" in wire:
                return failure("ba2.nul-name")
            try:
                display = wire.decode("cp1252").replace("/", "\\")
                normalized = identity(display)
                if normalized is not None:
                    if normalized in identities:
                        return failure("ba2.duplicate-name")
                    identities.add(normalized)
                if all(value < 128 for value in wire):
                    directory, _, filename = wire.replace(b"/", b"\\").rpartition(b"\\")
                    parts = filename.rsplit(b".", 1)
                    expected_base = binascii.crc32(parts[0].lower(), 0xffffffff) ^ 0xffffffff
                    expected_folder = binascii.crc32(directory.lower(), 0xffffffff) ^ 0xffffffff
                    expected_extension = (parts[1] if len(parts) == 2 else b"").lower()[:4].ljust(4, b"\0")
                    for actual, expected, identifier, field, position in (
                        (f"{base:08X}", f"{expected_base:08X}", "ba2.basename-hash-mismatch", "baseNameHash", at),
                        (extension.hex().upper(), expected_extension.hex().upper(), "ba2.extension-mismatch", "extension", at + 4),
                        (f"{folder:08X}", f"{expected_folder:08X}", "ba2.directory-hash-mismatch", "directoryHash", at + 8)):
                        if actual != expected:
                            warnings.append(diagnostic(identifier, index, display, field, position, 4,
                                                       {"stored": actual, "expected": expected}))
            except UnicodeDecodeError:
                display = f"__jbsa_wire__\\e{index:08x}"
                warnings.append(diagnostic("archive-name.undecodable-wire-bytes", index, display,
                                           "complete", cursor, length))
            cursor += length
        for value, expected, identifier, field, position, size in (
            (str(mod), "0", "gnrl.nonzero-mod-index", "modIndex", at + 12, 1),
            (str(chunk_size), "16", "gnrl.chunk-header-size", "chunkHeaderSize", at + 14, 2),
            (f"{sentinel:08X}", "BAADF00D", "gnrl.sentinel-mismatch", "sentinel", at + 32, 4)):
            if value != expected:
                warnings.append(diagnostic(identifier, index, display, field, position, size,
                                           {"stored": value, "expected": expected}))
        stored = packed or unpacked
        if offset < table_end:
            return failure("ba2.payload-overlaps-index")
        if offset + stored > 0x7fffffffffffffff:
            return failure("io.span-overflow")
        if offset > len(raw) or stored > len(raw) - offset:
            return failure("io.invalid-span")
        if stored:
            spans.append((offset, offset + stored))
            used_end = max(used_end, offset + stored)
        pending.append((offset, packed, unpacked, display))
        entries.append({"decoded_name": display, "wire_name_bytes": None if wire is None else wire.hex(),
                        "normalized_name_identity": normalized, "basename_hash": base,
                        "directory_hash": folder, "extension": extension.hex(), "chunk_count": chunks,
                        "logical_size": unpacked, "stored_size": stored, "decoded_size": unpacked,
                        "compression_state": "zlib" if packed else "stored"})
    previous = None
    for span in sorted(spans):
        if named and span[0] < cursor and span[1] > name_offset:
            return failure("ba2.payload-overlaps-names")
        if previous and span[0] < previous[1] and span != previous:
            return failure("ba2.overlapping-payloads")
        previous = span
    if named:
        used_end = max(used_end, cursor)
    if used_end < len(raw):
        warnings.append(diagnostic("ba2.trailing-data", field="trailingData", offset=used_end,
                                   length=len(raw) - used_end))
    for index, (offset, packed, unpacked, display) in enumerate(pending):
        content = raw[offset:offset + (packed or unpacked)]
        if unpacked > 64 * 1024 * 1024:
            raise ValueError("Independent decoded-byte limit")
        if packed:
            values = {"codec": "zlib", "direction": "decode", "expected": str(unpacked),
                      "actual": "0", "profile": "jbsa-jdk-zlib-v1"}
            try:
                decoder = zlib.decompressobj()
                content = decoder.decompress(content, unpacked + 1)
                if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
                    return failure("codec.invalid-data", operation="READ_CONTENT", ordinal=index,
                                   name=display, values=values)
                if len(content) != unpacked:
                    values["actual"] = str(len(content))
                    return failure("codec.size-mismatch", operation="READ_CONTENT", ordinal=index,
                                   name=display, values=values)
            except zlib.error:
                return failure("codec.invalid-data", operation="READ_CONTENT", ordinal=index,
                               name=display, values=values)
        entries[index]["payload_sha256"] = hashlib.sha256(content).hexdigest()
        entries[index]["wire_hashes"] = {"basename": entries[index]["basename_hash"], "directory": entries[index]["directory_hash"]}
        entries[index]["flags"] = {}
        entries[index]["chunks"] = [{"stored_size": entries[index]["stored_size"], "decoded_size": unpacked,
                                      "payload_sha256": entries[index]["payload_sha256"]}]
    disposition = "TOLERATED_NONCANONICAL" if warnings else "CONFORMING"
    for index, entry in enumerate(entries):
        display = entry["decoded_name"]
        if display.startswith("\\") or len(display) > 1 and display[1] == ":":
            warnings.append(diagnostic("archive-name.absolute-path", index, display))
        for ordinal, segment in enumerate(display.split("\\")):
            if segment in (".", ".."):
                warnings.append(diagnostic("archive-name.traversal-segment", index, display, segment,
                                           values={"segmentOrdinal": str(ordinal)}))
                break
        for ordinal, segment in enumerate(display.split("\\")):
            stem = segment.split(".", 1)[0].upper()
            if stem in {"CON", "PRN", "AUX", "NUL"} or any(c in segment for c in '\"*<>?|'):
                warnings.append(diagnostic("archive-name.windows-invalid-segment", index, display, segment,
                                           values={"segmentOrdinal": str(ordinal)}))
                break
    return {"archive_family": "fo4-gnrl-v1", "wire_version": 1, "subtype": "GNRL",
            "compression_method": None, "disposition": disposition,
            "entry_count": count, "entries": entries, "diagnostics": warnings}


if __name__ == "__main__":
    print(json.dumps({"projection": projection(Path(sys.argv[1]).read_bytes())}, separators=(",", ":")))
