"""Produce untrusted, specification-authored TES4 semantic expectations for deliberate review.

This parser is build-only, bounded, and independent of JBSA and xEdit. Its output
is proposed evidence, never implicit maintainer approval or a release claim.
"""

import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys
import zlib

_spec = importlib.util.spec_from_file_location("independent_bsa_scanner", Path(__file__).with_name("validate-bsa-wire.py"))
_scanner = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_scanner)


def diagnostic(identifier, ordinal, name, field, offset, length, values):
    """Return the contract's stable warning fields, excluding runtime input paths."""
    return {"identifier": identifier, "severity": "WARNING", "operation": "OPEN",
            "affected": {"entry_ordinal": ordinal, "entry_name": name, "field": field,
                         "byte_span": {"offset": offset, "length": length}}, "values": values}


def identity(name):
    """Derive only structurally safe ASCII-normalized name identities."""
    mapped = name.replace("/", "\\")
    if ":" in mapped or any(not part or part in (".", "..") or part.endswith((".", " "))
                            for part in mapped.split("\\")):
        return None
    return "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in mapped)


def projection(raw):
    """Parse bounded named/unnamed 0x67 inputs; enforce the normative structural constraints."""
    try:
        return _projection(raw)
    except (ValueError, IndexError, struct.error, zlib.error):
        return {"failure_kind": "FORMAT", "diagnostics": []}


def _projection(raw):
    """Implement independent wire interpretation for the proposed synthetic case family."""
    magic, version, start, flags, folders, files, folder_names, file_names, file_flags = struct.unpack_from("<4s8I", raw)
    if magic != b"BSA\0" or version != 103 or folders > 1000 or files > 1000:
        raise ValueError("Unsupported selector or count")
    if start < 36 or (not flags & 1 and folder_names) or (not flags & 2 and file_names):
        raise ValueError("Name-section mismatch")
    position = start + folders * 16
    names_start = position + (folder_names + folders if flags & 1 else 0) + files * 16
    data_start = names_start + file_names
    if data_start > len(raw):
        raise ValueError("Section beyond input")
    pending = []
    folder_order = []
    for folder_ordinal in range(folders):
        folder_hash, count, offset = struct.unpack_from("<QII", raw, start + folder_ordinal * 16)
        if count > files - len(pending) or offset != position + file_names:
            raise ValueError("Folder relation")
        component, component_at = None, position
        if flags & 1:
            length = raw[position]
            position += 1
            component_at = position
            component = raw[position:position + length]
            if not component or component[-1] != 0 or b"\0" in component[:-1]:
                raise ValueError("Folder name")
            component = component[:-1]
            position += length
        folder_order.append({"ordinal": folder_ordinal, "hash": f"{folder_hash:016x}"})
        for local in range(count):
            name_hash, size_flags, offset = struct.unpack_from("<QII", raw, position)
            pending.append((folder_ordinal, folder_hash, component, component_at,
                            start + folder_ordinal * 16, local, name_hash, size_flags, offset, position))
            position += 16
    if len(pending) != files or position != names_start:
        raise ValueError("Section relation")
    entries, diagnostics, spans, identities = [], [], [], set()
    for ordinal, record in enumerate(pending):
        folder_ordinal, folder_hash, folder, folder_at, folder_field, local, name_hash, size_flags, offset, field = record
        basename, basename_at = None, position
        if flags & 2:
            end = raw.index(b"\0", position, data_start)
            basename = raw[position:end]
            position = end + 1
        texts = []
        for component, label, component_at in ((folder, "folder", folder_at), (basename, "basename", basename_at)):
            text = None
            if component is not None:
                try:
                    text = component.decode("cp1252")
                except UnicodeDecodeError:
                    diagnostics.append(diagnostic("archive-name.undecodable-wire-bytes", ordinal,
                                                  None, label, component_at, len(component), {}))
            texts.append(text)
        complete = all(text is not None for text in texts)
        name = "\\".join(texts) if complete else f"__jbsa_hash__\\f{folder_ordinal:08x}-{folder_hash:016x}\\e{ordinal:08x}-{name_hash:016x}"
        for warning in diagnostics:
            if warning["identifier"] == "archive-name.undecodable-wire-bytes" and warning["affected"]["entry_ordinal"] == ordinal:
                warning["affected"]["entry_name"] = name
        if complete and (name.startswith(("\\", "/")) or (len(name) > 1 and name[1] == ":")):
            diagnostics.append({"identifier": "archive-name.absolute-path", "severity": "WARNING", "operation": "OPEN",
                                "affected": {"entry_ordinal": ordinal, "entry_name": name, "field": None, "byte_span": None}, "values": {}})
        elif complete and ".." in name.split("\\"):
            diagnostics.append({"identifier": "archive-name.traversal-segment", "severity": "WARNING", "operation": "OPEN",
                                "affected": {"entry_ordinal": ordinal, "entry_name": name, "field": "..", "byte_span": None},
                                "values": {"segmentOrdinal": str(name.split("\\").index(".."))}})
        normalized = identity(name) if complete else None
        if normalized is not None:
            if normalized in identities:
                raise ValueError("Duplicate identity")
            identities.add(normalized)
        for component, is_file, stored_hash, hash_field, hash_offset in (
                (folder if local == 0 else None, False, folder_hash, "folderHash", folder_field),
                (basename, True, name_hash, "nameHash", field)):
            if component is not None and all(value < 128 for value in component) and not (is_file and component.startswith(b".") and component.count(b".") == 1):
                expected = _scanner.name_hash(component.lower().replace(b"/", b"\\"), is_file)
                if expected != stored_hash:
                    diagnostics.append(diagnostic("bsa.file-hash-mismatch" if is_file else "bsa.folder-hash-mismatch",
                                                  ordinal, name, hash_field, hash_offset, 8,
                                                  {"stored": f"{stored_hash:016X}", "expected": f"{expected:016X}"}))
        size = size_flags & ~0x40000000
        if offset < data_start or offset + size > len(raw):
            raise ValueError("Payload outside input")
        spans.append((offset, offset + size))
        payload = raw[offset:offset + size]
        compressed = bool(flags & 4) != bool(size_flags & 0x40000000)
        decoded_size = len(payload)
        if compressed:
            decoded_size, = struct.unpack_from("<I", payload)
            if decoded_size > 16 * 1024 * 1024:
                raise ValueError("Decoded size exceeds scanner scope")
            decoder = zlib.decompressobj()
            payload = decoder.decompress(payload[4:], decoded_size + 1)
            if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail or len(payload) != decoded_size:
                raise ValueError("Incomplete compressed stream")
        entries.append({"decoded_name": name,
                        "wire_name_bytes": {"folder": folder.hex() if folder is not None else None,
                                            "basename": basename.hex() if basename is not None else None},
                        "normalized_name_identity": normalized,
                        "wire_hashes": {"folder": f"{folder_hash:016x}", "file": f"{name_hash:016x}"},
                        "logical_size": decoded_size, "stored_size": size, "decoded_size": decoded_size,
                        "compression_state": "zlib" if compressed else "stored",
                        "flags": {"compression_toggle": bool(size_flags & 0x40000000)},
                        "payload_sha256": hashlib.sha256(payload).hexdigest(),
                        "folder_hash": f"{folder_hash:016x}", "file_hash": f"{name_hash:016x}", "embedded_name": None})
    if position != data_start:
        raise ValueError("File name extent")
    spans.sort()
    if any(right[0] < left[1] and left != right for left, right in zip(spans, spans[1:])):
        raise ValueError("Partial overlap")
    return {"archive_family": "bsa-067", "wire_version": 103, "entry_count": files,
            "disposition": "TOLERATED_NONCANONICAL" if any(warning["identifier"] not in ("archive-name.absolute-path", "archive-name.traversal-segment") for warning in diagnostics) else "CONFORMING",
            "archive_flags": flags, "file_flags": file_flags, "folder_order": folder_order,
            "entries": entries, "diagnostics": diagnostics}


if __name__ == "__main__":
    content = Path(sys.argv[1]).read_bytes()
    if sys.argv[1].endswith(".hex"):
        content = bytes.fromhex(content.decode("ascii"))
    print(json.dumps({"projection": projection(content)}, separators=(",", ":")))
