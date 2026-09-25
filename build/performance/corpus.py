"""Versioned, compression-neutral performance inputs; all generation is untimed.

Only the explicit materialize command writes payloads. Manifest generation hashes
bounded streams, so even the complete corpus needs no multi-gigabyte allocation.
"""

import argparse
from collections import Counter
import copy
import gzip
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import struct
import sys

GENERATOR_VERSION = "1.0.0"
CORPUS_VERSION = "performance-v1.1"
SEED = "jbsa-performance-v1-2026-09-05"
BLOCK_BYTES = 1048576
ALGORITHMS = {"shake256-counter-v1": {"version": 1, "block_bytes": BLOCK_BYTES,
              "domain": "UTF-8 seed + NUL + payload_id + NUL + uint64-le block counter"},
              "structured-record-v1": {"version": 1, "record_bytes": 4096,
              "template": "canonical JSON object repeated with LF to fill a 4096-byte record"}}
_SIZES = {"metadata-100k": (100000, 268435456), "mixed-10k": (10000, 2147483648),
          "bulk-compressible": (8, 2147483648), "bulk-incompressible": (8, 2147483648),
          "dds-mipmapped": (256, 2147483648), "shared-content": (10000, 1073741824)}
_FOURCC = {"bc1": (b"DXT1", 8, 71), "bc2": (b"DXT3", 16, 74),
           "bc3": (b"DXT5", 16, 77), "bc4": (b"BC4U", 8, 80), "bc5": (b"BC5U", 16, 83)}
_DDS_SPECIALIZATIONS = {"metadata-100k", "mixed-10k", "bulk-compressible", "bulk-incompressible"}


def canonical_bytes(value):
    """Serialize the manifest's UTF-8, sorted-key, whitespace-free JSON form."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False).encode("utf-8")


def definitions():
    """Return the six exact normative workloads without reading or generating bytes."""
    return {name: {"file_count": count, "logical_bytes": size,
                   "generator_version": GENERATOR_VERSION, "corpus_version": CORPUS_VERSION,
                   "seed": SEED} for name, (count, size) in _SIZES.items()}


def _recipe(path, length, payload_id, kind="pseudorandom", structural=None):
    """Describe one file with a content identity independent of its alias path."""
    return {"path": path, "length": length, "seed": SEED, "payload_id": payload_id,
            "algorithm": "structured-record-v1" if kind == "compressible" else "shake256-counter-v1",
            "structural": {"content_class": kind, **(structural or {})}}


def _texture(index, width, height, mips, format_name, cubemap=False):
    """Derive a valid legacy DDS envelope and contiguous dimensions-based chunks."""
    _, block, dxgi = _FOURCC[format_name]
    lengths = [((max(1, width >> mip)+3)//4)*((max(1, height >> mip)+3)//4)*block for mip in range(mips)]
    chunk_count, w, h = 1, width, height
    while not cubemap and chunk_count < min(4, mips) and min(w, h) >= 512:
        chunk_count += 1
        w //= 2
        h //= 2
    spans, offset = [], 0
    for chunk in range(chunk_count):
        length = lengths[chunk] if chunk < chunk_count-1 else sum(lengths[chunk:])*(6 if cubemap else 1)
        spans.append({"offset": offset, "length": length, "start_mip": chunk,
                      "end_mip": chunk if chunk < chunk_count-1 else mips-1})
        offset += length
    return _recipe(f"textures/group-{index//32:02d}/texture-{index:03d}.dds", 128+offset,
                   f"dds-{index:03d}", structural={"format": format_name, "dxgi_format": dxgi,
                   "width": width, "height": height, "mip_count": mips, "cubemap": cubemap,
                   "header_bytes": 128, "mip_bytes_per_face": lengths, "chunks": spans})


def plan(workload, variant=None):
    """Return complete fixed recipes, optionally with the versioned DDS source envelope.

    DDS variants retain the general workload's relative names and content classes,
    including extension-policy names. DDS archive encode still compresses every
    chunk; the extension class does not override that format's mandatory codec.
    """
    if workload not in _SIZES:
        raise ValueError(f"Unknown workload: {workload}")
    if variant is not None:
        if variant != "dds-source" or workload not in _DDS_SPECIALIZATIONS:
            raise ValueError("Unknown workload specialization")
        return _dds_specialization(workload)
    count, total = _SIZES[workload]
    if workload == "dds-mipmapped":
        shapes = [(4096,4096,13,"bc1",False), (2048,2048,12,"bc3",False),
                  (1024,1024,11,"bc5",True), (512,512,10,"bc2",False),
                  (511,257,9,"bc4",False), (4096,4096,1,"bc3",False),
                  (1024,1024,11,"bc1",False), (2048,2048,6,"bc5",False)]
        files = [_texture(i, *shapes[i % len(shapes)]) for i in range(254)]
        spare = total-sum(f["length"] for f in files)-256
        # Preserve one full coverage cycle, then enlarge ordinary textures so
        # exact-total balancing does not create a single dominant giant entry.
        for index in range(8, 254):
            original = files[index]["structural"]
            replacement = _texture(index, 4096, 4096, 13, original["format"])
            growth = replacement["length"]-files[index]["length"]
            if 0 < growth < spare-1048576:
                files[index] = replacement
                spare -= growth
        # A DDS cannot use arbitrary trailing padding. Two real one-mip BC1
        # rectangles consume the exact remaining block count, including headers.
        remaining_blocks = (total-sum(f["length"] for f in files)-256)//8
        rows, tail = divmod(remaining_blocks, 2048)
        if tail == 0:
            rows -= 1
            tail = 2048
        files.extend([_texture(254, 8192, rows*4, 1, "bc1"), _texture(255, tail*4, 4, 1, "bc1")])
        return files
    distinct = 5000 if workload == "shared-content" else count
    physical_total = total//2 if workload == "shared-content" else total
    base, remainder = divmod(physical_total, distinct)
    files = []
    for index in range(count):
        payload = index % distinct
        kind = "pseudorandom"
        extension = "bin"
        if workload in ("metadata-100k", "bulk-compressible", "shared-content"):
            kind, extension = "compressible", "txt"
        if workload == "mixed-10k":
            kind, extension = (("compressible", "txt") if index < 5000 else
                               ("pseudorandom", "bin") if index < 8000 else ("no-compress", "fuz"))
        path = f"{workload}/group-{index//100:04d}/nested-{index%10:02d}/file-{index:06d}.{extension}"
        files.append(_recipe(path, base+(payload < remainder), f"{workload}-{payload:06d}", kind,
                             {"extension_no_compress": extension == "fuz", "alias_group": payload} if workload == "shared-content"
                             else {"extension_no_compress": extension == "fuz"}))
    return files


def _dds_specialization(workload):
    """Fit valid BC1 surfaces to the exact count and total without trailing padding.

    Lengths are apportioned in complete BC block rows. The bulk rectangle factors
    `(256 MiB - 128) / 8` exactly, keeping each dimension within the BA2 u16 bound.
    """
    base = plan(workload)
    count, total = _SIZES[workload]
    block_columns = 1 if workload == "metadata-100k" else 16
    rows, remainder = divmod((total-128*count)//(block_columns*8), count)
    files = []
    for index, source in enumerate(base):
        width, height = block_columns*4, (rows+(index < remainder))*4
        if workload.startswith("bulk-"):
            width, height = 56896, 9436
        texture = _texture(index, width, height, 1, "bc1")
        # Name extensions intentionally preserve the source mix. DDS is an
        # envelope identified by bytes, not a promise that a .fuz file is audio.
        texture.update({"path": source["path"], "payload_id": source["payload_id"]+"-dds-source-v1",
                        "algorithm": source["algorithm"]})
        texture["structural"].update(source["structural"])
        files.append(texture)
    return files


def _dds_header(recipe):
    """Emit the canonical 128-byte PC header from the recipe's texture geometry."""
    meta = recipe["structural"]
    header = bytearray(128)
    header[:4] = b"DDS "
    struct.pack_into("<7I", header, 4, 124, 0xA1007, meta["height"], meta["width"],
                     meta["mip_bytes_per_face"][0], 1, meta["mip_count"])
    struct.pack_into("<II4s", header, 76, 32, 4, _FOURCC[meta["format"]][0])
    caps = 0x1000 | (0x400008 if meta["mip_count"] > 1 else 0) | (8 if meta["cubemap"] else 0)
    struct.pack_into("<II", header, 108, caps, 0xFE00 if meta["cubemap"] else 0)
    return bytes(header)


def file_bytes(recipe):
    """Yield deterministic bounded chunks, with a separate header chunk for DDS.

    The random construction uses a counter-separated SHAKE stream, avoiding
    Python random-version dependencies and periodic reused random blocks.
    """
    remaining = recipe["length"]
    if recipe["structural"].get("header_bytes"):
        header = _dds_header(recipe)
        yield header
        remaining -= len(header)
    prefix = (recipe["seed"]+"\0"+recipe["payload_id"]+"\0").encode("utf-8")
    if recipe["algorithm"] == "structured-record-v1":
        line = canonical_bytes({"kind": "jbsa-synthetic-record", "payload": recipe["payload_id"],
                                "seed": recipe["seed"], "values": [0,1,2,3,5,8,13,21]})+b"\n"
        record = (line*((4096+len(line)-1)//len(line)))[:4096]
        block = record*(BLOCK_BYTES//4096)
    counter = 0
    while remaining:
        size = min(remaining, BLOCK_BYTES)
        if recipe["algorithm"] == "shake256-counter-v1":
            yield hashlib.shake_256(prefix+struct.pack("<Q", counter)).digest(size)
        elif recipe["algorithm"] == "structured-record-v1":
            yield block[:size]
        else:
            raise ValueError("Unknown generator algorithm")
        remaining -= size
        counter += 1


def manifest_digest(manifest):
    """Hash canonical content excluding the manifest's own digest field."""
    return hashlib.sha256(canonical_bytes({k: v for k, v in manifest.items() if k != "manifest_sha256"})).hexdigest()


def build_manifest(workload, files=None, variant=None):
    """Hash every generated file without storing payloads; custom recipes are smoke.

    Full generation reads gigabytes through the generator and is intentionally
    explicit. It must finish before any qualification timer starts.
    """
    scope = "normative" if files is None else "smoke"
    recipes = plan(workload, variant=variant) if files is None else files
    entries, hashes = [], {}
    for recipe in recipes:
        key = canonical_bytes({k: v for k, v in recipe.items() if k != "path"})
        if key not in hashes:
            digest = hashlib.sha256()
            for block in file_bytes(recipe):
                digest.update(block)
            hashes[key] = digest.hexdigest()
        entries.append({**recipe, "sha256": hashes[key]})
    manifest = {"contract": "performance-v1", "corpus_version": CORPUS_VERSION,
                "generator_version": GENERATOR_VERSION, "algorithms": ALGORITHMS,
                "seed": SEED, "workload": workload, "scope": scope,
                "file_count": len(entries), "logical_bytes": sum(f["length"] for f in entries),
                "content_mix": dict(Counter(f["structural"]["content_class"] for f in entries)),
                "files": entries}
    if variant is not None:
        manifest.update({"variant": variant, "variant_version": 1})
    manifest["manifest_sha256"] = manifest_digest(manifest)
    validate_manifest(manifest)
    return manifest


def recipe_digest(entries):
    """Hash full ordered recipe identities independently of generated file digests."""
    return hashlib.sha256(canonical_bytes([{k: v for k, v in entry.items() if k != "sha256"}
                                           for entry in entries])).hexdigest()


def build_projection(parent_manifest):
    """Derive the normative 1000-entry medium unpack input, preserving the 50/30/20 mix.

    Every tenth file is selected from a full mixed-10k manifest. Embedded parent
    evidence makes the parent manifest and recipe digests independently verifiable;
    parent records never contribute to the projection's logical byte count.
    """
    validate_manifest(parent_manifest, require_normative=True)
    if parent_manifest["workload"] != "mixed-10k" or "projection" in parent_manifest:
        raise ValueError("Medium projections require a complete mixed-10k parent")
    parent = copy.deepcopy(parent_manifest)
    entries = copy.deepcopy(parent["files"][::10])
    manifest = {k: copy.deepcopy(v) for k, v in parent.items()
                if k not in {"files", "manifest_sha256", "file_count", "logical_bytes", "content_mix"}}
    manifest.update({"file_count": len(entries), "logical_bytes": sum(entry["length"] for entry in entries),
                     "content_mix": dict(Counter(entry["structural"]["content_class"] for entry in entries)),
                     "files": entries,
                     "projection": {"algorithm": "stride-10-v1", "file_count": 1000,
                                    "parent_manifest_sha256": parent["manifest_sha256"],
                                    "parent_recipe_sha256": recipe_digest(parent["files"]),
                                    "parent_manifest": parent}})
    manifest["manifest_sha256"] = manifest_digest(manifest)
    validate_manifest(manifest, require_normative=True)
    return manifest


def _validate_projection(manifest):
    """Check complete parent evidence and return the only allowed projected entries."""
    projection = manifest["projection"]
    if (not isinstance(projection, dict)
            or set(projection) != {"algorithm", "file_count", "parent_manifest_sha256", "parent_recipe_sha256", "parent_manifest"}
            or projection["algorithm"] != "stride-10-v1" or projection["file_count"] != 1000):
        raise ValueError("Unknown corpus projection identity")
    parent = projection["parent_manifest"]
    if (not isinstance(parent, dict) or "projection" in parent or parent.get("workload") != "mixed-10k"
            or manifest["workload"] != "mixed-10k" or parent.get("variant") != manifest.get("variant")):
        raise ValueError("Projection parent workload or variant mismatch")
    validate_manifest(parent, require_normative=True)
    if (projection["parent_manifest_sha256"] != parent["manifest_sha256"]
            or projection["parent_recipe_sha256"] != recipe_digest(parent["files"])):
        raise ValueError("Projection parent digest mismatch")
    return parent["files"][::10]


def validate_manifest(manifest, require_normative=False):
    """Reject altered manifests, ambiguous Windows paths, or changed definitions."""
    if manifest.get("manifest_sha256") != manifest_digest(manifest):
        raise ValueError("Corpus manifest digest mismatch")
    if (manifest.get("corpus_version") != CORPUS_VERSION or manifest.get("generator_version") != GENERATOR_VERSION
            or manifest.get("algorithms") != ALGORITHMS or manifest.get("seed") != SEED
            or manifest.get("contract") != "performance-v1" or manifest.get("workload") not in _SIZES
            or manifest.get("scope") not in ("normative", "smoke")):
        raise ValueError("Corpus generator identity mismatch")
    if require_normative and manifest["scope"] != "normative":
        raise ValueError("Smoke corpus cannot supply qualification evidence")
    if manifest.get("variant") is not None:
        if (manifest["variant"] != "dds-source" or manifest.get("variant_version") != 1
                or manifest["workload"] not in _DDS_SPECIALIZATIONS):
            raise ValueError("Corpus specialization identity mismatch")
    elif "variant_version" in manifest:
        raise ValueError("A specialization version requires its variant identity")
    if "projection" in manifest and manifest["scope"] != "normative":
        raise ValueError("A smoke corpus cannot claim a normative projection")
    entries = manifest["files"]
    names = set()
    for entry in entries:
        name = entry["path"]
        parts = PurePosixPath(name).parts
        if (not isinstance(name, str) or not re.fullmatch(r"[a-z0-9][a-z0-9._/-]*", name)
                or any(part in (".", "..") or part.endswith((".", " ")) for part in parts)
                or "/".join(parts) != name or name.casefold() in names
                or any(part.split(".")[0].upper() in {"CON","PRN","AUX","NUL",*(f"COM{i}" for i in range(1,10)),*(f"LPT{i}" for i in range(1,10))} for part in parts)):
            raise ValueError(f"Unsafe or duplicate corpus path: {name}")
        names.add(name.casefold())
        if (type(entry["length"]) is not int or entry["length"] <= 0
                or not re.fullmatch("[0-9a-f]{64}", entry["sha256"])
                or entry["algorithm"] not in ALGORITHMS or entry["seed"] != SEED):
            raise ValueError("Invalid corpus file identity")
    if (len(entries) != manifest["file_count"] or sum(f["length"] for f in entries) != manifest["logical_bytes"]
            or dict(Counter(f["structural"]["content_class"] for f in entries)) != manifest["content_mix"]):
        raise ValueError("Corpus totals or content mix mismatch")
    if manifest["scope"] == "normative":
        recipes = [{k: v for k, v in f.items() if k != "sha256"} for f in entries]
        if "projection" in manifest:
            if entries != _validate_projection(manifest):
                raise ValueError("Normative projection recipe or payload identity mismatch")
        elif recipes != plan(manifest["workload"], variant=manifest.get("variant")):
            raise ValueError("Normative corpus recipe mismatch")


def write_manifest(manifest, path):
    """Write canonical JSON, optionally deterministic gzip with no timestamp/name."""
    validate_manifest(manifest)
    data = canonical_bytes(manifest)+b"\n"
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(gzip.compress(data, compresslevel=9, mtime=0) if path.suffix == ".gz" else data)


def read_manifest(path):
    """Read and validate a JSON or gzip manifest before returning its identity."""
    path = Path(path)
    data = path.read_bytes()
    manifest = json.loads(gzip.decompress(data) if path.suffix == ".gz" else data)
    validate_manifest(manifest)
    return manifest


def _safe_destination(root, relative):
    """Reject symlink/junction escapes before reading or writing corpus paths."""
    path = root/relative
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError(f"Corpus path escapes destination: {relative}")
    for parent in (path, *path.parents):
        if parent.is_symlink() or (hasattr(parent, "is_junction") and parent.is_junction()):
            raise ValueError(f"Corpus destination contains a link: {relative}")
        if parent == root:
            break
    return path


def _verify_file(entry, path):
    """Stream and compare exact length and SHA-256 for one existing file."""
    if not path.is_file() or path.stat().st_size != entry["length"]:
        raise ValueError(f"Corpus length/path mismatch: {entry['path']}")
    with path.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != entry["sha256"]:
        raise ValueError(f"Corpus byte/digest mismatch: {entry['path']}")


def _check_existing_paths(manifest, root):
    """Refuse extra files/directories and links rather than adopting local inputs."""
    expected = {f["path"] for f in manifest["files"]}
    directories = {parent.as_posix() for name in expected for parent in PurePosixPath(name).parents if parent.as_posix() != "."}
    _safe_destination(root, ".corpus-validation-probe")
    if root.exists():
        for path in root.rglob("*"):
            relative = path.relative_to(root).as_posix()
            _safe_destination(root, relative)
            if (path.is_file() and relative not in expected) or (path.is_dir() and relative not in directories):
                raise ValueError(f"Unexpected corpus path: {relative}")


def verify_materialization(manifest, destination):
    """Verify the complete exact directory against the digest-pinned manifest."""
    validate_manifest(manifest)
    root = Path(destination).absolute()
    _check_existing_paths(manifest, root)
    for entry in manifest["files"]:
        _verify_file(entry, _safe_destination(root, entry["path"]))


def materialize(manifest, destination):
    """Create missing files and verify existing ones, never overwrite mismatches.

    Interrupted writes remove only the file created by this call; completed
    verified files remain available for an explicit resume.
    """
    validate_manifest(manifest)
    root = Path(destination).absolute()
    _check_existing_paths(manifest, root)
    for entry in manifest["files"]:
        path = _safe_destination(root, entry["path"])
        if path.exists():
            _verify_file(entry, path)
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        created = False
        try:
            with path.open("xb") as stream:
                created = True
                digest, length = hashlib.sha256(), 0
                for block in file_bytes(entry):
                    stream.write(block)
                    digest.update(block)
                    length += len(block)
            if length != entry["length"] or digest.hexdigest() != entry["sha256"]:
                raise ValueError(f"Generated corpus byte/digest mismatch: {entry['path']}")
        except BaseException:
            # Exclusive-open failure does not transfer ownership of a file
            # another materializer might have created concurrently.
            if created:
                path.unlink(missing_ok=True)
            raise


def main(argv=None):
    """Expose explicit planning, manifest, materialization, and validation commands."""
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("definitions")
    manifest_parser = sub.add_parser("manifest")
    manifest_parser.add_argument("workload", choices=_SIZES)
    manifest_parser.add_argument("--output", required=True)
    manifest_parser.add_argument("--variant", choices=["dds-source"])
    projection_parser = sub.add_parser("projection")
    projection_parser.add_argument("parent_manifest")
    projection_parser.add_argument("--output", required=True)
    for command in ("materialize", "verify"):
        operation = sub.add_parser(command)
        operation.add_argument("manifest")
        operation.add_argument("destination")
    args = parser.parse_args(argv)
    try:
        if args.command == "definitions":
            print(json.dumps(definitions(), indent=2))
        elif args.command == "manifest":
            manifest = build_manifest(args.workload, variant=args.variant)
            write_manifest(manifest, args.output)
            print(manifest["manifest_sha256"])
        elif args.command == "projection":
            manifest = build_projection(read_manifest(args.parent_manifest))
            write_manifest(manifest, args.output)
            print(manifest["manifest_sha256"])
        else:
            manifest = read_manifest(args.manifest)
            (materialize if args.command == "materialize" else verify_materialization)(manifest, args.destination)
            print(manifest["manifest_sha256"])
        return 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(f"INVALID: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
