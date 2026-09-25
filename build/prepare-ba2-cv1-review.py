"""Prepare 33 new-identity BA2 CV1 successor cases without activating or approving them."""

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parent.parent
PROPOSAL = ROOT / "docs/reviews/issue39-cv1"


def canonical(value):
    """Serialize immutable review objects as sorted compact UTF-8 JSON."""
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def digest(path):
    """Bind exact file bytes rather than a mutable location alone."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bind(path):
    """Return a portable repository-relative digest binding."""
    return {"path": path.relative_to(ROOT).as_posix(), "sha256": digest(path)}


def module(name):
    """Load an independent build-only generator or expectation scanner."""
    spec = importlib.util.spec_from_file_location(name, ROOT / "build" / (name + ".py"))
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def object_file(value, directory):
    """Write content-addressed proposed objects without replacing differing bytes."""
    content = canonical(value)
    path = directory / (hashlib.sha256(content).hexdigest() + ".json")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_bytes() != content:
        raise ValueError("Content-address conflict")
    path.write_bytes(content)
    return path


def named_archive(generator, names, payloads=None):
    """Author name-table scenarios while preserving the written ASCII identity rules."""
    payloads = payloads or [b"A" * 1024, bytes.fromhex("000102ff")]
    records, data = bytearray(), bytearray()
    for name, payload in zip(names, payloads):
        directory, _, filename = name.replace(b"/", b"\\").rpartition(b"\\")
        parts = filename.rsplit(b".", 1)
        extension = (parts[1] if len(parts) == 2 else b"")[:4].lower().ljust(4, b"\0")
        records += struct.pack("<I4sIBBHQIII", generator.wire_hash(parts[0]), extension,
            generator.wire_hash(directory), 0, 1, 16, 24 + 36 * len(names) + len(data), 0,
            len(payload), 0xbaadf00d)
        data += payload
    offset = 24 + len(records) + len(data)
    return struct.pack("<4sI4sIQ", b"BTDX", 1, b"GNRL", len(names), offset) + records + data + b"".join(struct.pack("<H", len(name)) + name for name in names)


def recipe(name, generator):
    """Materialize every old catalog scenario from explicit independent byte recipes."""
    if name.startswith("base-fo4-gnrl-v1-"):
        return generator.archive(name.removeprefix("base-fo4-gnrl-v1-"))
    if name == "input-zero-length":
        return named_archive(generator, [b"meshes/empty.nif"], [b""])
    if name == "gnrl-name-tables":
        return named_archive(generator, [b"Meshes/A.nif", b"Meshes/B.nif"])
    special_names = {
        "malformed-equal-name-identities": [b"meshes/a.nif", b"MESHES/A.NIF"],
        "malformed-undecodable-wire-names": [b"meshes/\x81.nif", b"meshes/b.nif"],
        "malformed-unsafe-absolute-names": [b"/meshes/a.nif", b"meshes/b.nif"],
        "malformed-unsafe-traversal-names": [b"../meshes/a.nif", b"meshes/b.nif"],
        "malformed-unsafe-name-extraction": [b"../meshes/a.nif", b"meshes/b.nif"],
        "malformed-windows-invalid-names": [b"meshes/CON.nif", b"meshes/b.nif"]}
    if name in special_names:
        return named_archive(generator, special_names[name])
    raw = bytearray(generator.archive("mixed" if name == "malformed-decompression-mismatch" else "stored"))
    if name == "malformed-exact-shared-spans":
        struct.pack_into("<Q", raw, 76, 96)
        struct.pack_into("<I", raw, 88, 1024)
    elif name == "malformed-harmless-trailing-bytes":
        raw += b"TRAIL"
    elif name in ("malformed-ignorable-constants", "malformed-owning-dispositions"):
        struct.pack_into("<I", raw, 56, 0)
    elif name == "malformed-missing-name-tables":
        struct.pack_into("<Q", raw, 16, 0)
    else:
        changes = {
            "malformed-arithmetic-overflow": (40, "Q", 0x7fffffffffffffff),
            "malformed-decompression-mismatch": (52, "I", 1025),
            "malformed-illegal-tuples": (37, "B", 2),
            "malformed-impossible-counts": (12, "I", 0xffffffff),
            "malformed-out-of-range-spans": (40, "Q", len(raw) + 1),
            "malformed-partial-overlap": (76, "Q", 97),
            "malformed-truncated-spans": (40, "Q", len(raw) - 1),
            "malformed-usable-name-hash-mismatch": (24, "I", 1)}
        offset, form, value = changes[name]
        struct.pack_into("<" + form, raw, offset, value)
    return bytes(raw)


def oracle_inputs(expectation):
    """Generate digest-pinned oracle archives from redistributable project-authored payloads."""
    projections = {}
    directory = PROPOSAL / "oracle"
    directory.mkdir(parents=True, exist_ok=True)
    staging = ROOT / "target/ba2-cv1-review-inputs"
    staging.mkdir(parents=True, exist_ok=True)
    for codec in ("stored", "zlib"):
        hex_path = directory / (codec + ".hex")
        if not hex_path.exists():
            work = ROOT / "target" / ("ba2-cv1-oracle-" + codec)
            sources = work / "sources/meshes"
            sources.mkdir(parents=True, exist_ok=True)
            (sources / "a.nif").write_bytes(b"A" * 1024)
            (sources / "b.nif").write_bytes(bytes.fromhex("000102ff"))
            archive = work / "oracle.ba2"
            evidence = ROOT / "target" / ("ba2-cv1-oracle-evidence-" + codec)
            command = ["pwsh", "-NoProfile", "-File", str(ROOT / "build/run-ba2-oracle.ps1"),
                "-Operation", "pack", "-InputPath", str(sources.parent), "-OutputPath", str(archive),
                "-Compression", codec, "-WorkingDirectory", str(work), "-EvidenceDirectory", str(evidence)]
            result = subprocess.run(command, check=True, capture_output=True, text=True)
            observation = json.loads(result.stdout)
            hex_path.write_bytes((archive.read_bytes().hex() + "\n").encode("ascii"))
            receipt = {"oracle_sha256": "4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2",
                "archive_sha256": digest(archive), "archive_hex_sha256": digest(hex_path),
                "exit_status": observation["exit_status"], "observation_sha256": digest(evidence / "observation.json"),
                "creator": "JBSA project contributors", "spdx_license": "CC0-1.0",
                "redistribution_class": "project-authored-redistributable", "source": "Pinned oracle packs project-authored A*1024 and 000102ff payloads"}
            hex_path.with_suffix(".receipt.json").write_bytes(canonical(receipt))
        archive = staging / (codec + ".ba2")
        raw = bytes.fromhex(hex_path.read_text())
        archive.write_bytes(raw)
        projections[codec] = (bind(archive), expectation.projection(raw))
    return projections


def prepare():
    """Create schema-compatible new fixture tokens, goldens and successor catalog for review."""
    PROPOSAL.mkdir(parents=True, exist_ok=True)
    generator, expectation = module("generate-ba2-fixtures"), module("ba2-cv1-expectations")
    active_path = ROOT / "tests/conformance/catalog.json"
    catalog = copy.deepcopy(json.loads(active_path.read_text()))
    configuration = next(x for x in catalog["tokens"]["configuration"] if x["token"] == "standard-v1")
    spec_digest = hashlib.sha256(canonical(catalog["specification_set"])).hexdigest()
    oracle = oracle_inputs(expectation)
    manifest = {"schema_version": 1, "generator": {"id": "jbsa-ba2-cv1-review-v1", "version": "1",
        "implementation": "build/prepare-ba2-cv1-review.py"}, "fixtures": [], "goldens": []}
    descriptor = PROPOSAL / "generator.json"
    descriptor.write_bytes(canonical({**manifest["generator"], "source": "docs/spec/formats/general-ba2.md",
        "spdx_license": "Apache-2.0", "parameters": "explicit old CV1 fixture token"}))
    successors, fixtures = [], {}
    for case in catalog["cases"]:
        identity = case["identity"]
        if identity["archive_family"] != "fo4-gnrl-v1":
            continue
        old_id, old_fixture = identity["case_id"], identity["fixture"]
        token = "fo4-general-" + old_fixture + "-v1"
        identity["fixture"] = token
        identity["case_id"] = "CV1-" + ".".join(identity[k] for k in ("archive_family", "operation", "fixture", "codec", "configuration"))
        if old_fixture.startswith("base-fo4-gnrl-v1-"):
            case["metadata"]["base_matrix_cell"] = True
        successors.append({"previous_case_id": old_id, "proposed_case_id": identity["case_id"]})
        raw = recipe(old_fixture, generator)
        fixture_path = PROPOSAL / "fixtures" / (token + ".hex")
        fixture_path.parent.mkdir(parents=True, exist_ok=True)
        fixture_path.write_bytes((raw.hex() + "\n").encode("ascii"))
        if token not in fixtures:
            fixture = {"id": token, "input_sha256": hashlib.sha256(canonical({"recipe": old_fixture})).hexdigest(),
                "generation": {"recipe": old_fixture}, "output": {"path": fixture_path.relative_to(PROPOSAL).as_posix(),
                "sha256": digest(fixture_path)}, "creator": "JBSA project contributors", "spdx_license": "CC0-1.0",
                "redistribution_class": "project-authored-redistributable"}
            fixtures[token] = fixture
            manifest["fixtures"].append(fixture)
        expected = expectation.projection(raw)
        operation, codec = identity["operation"], identity["codec"]
        if operation == "encode":
            if codec == "raw-deflate":
                expected = {"exit_status": 2, "artifact_exists": False}
            elif codec in ("raw-lz4", "lz4-frame"):
                expected = expectation.failure("ba2.unsupported-codec", "UNSUPPORTED", "PACK")
            else:
                expected = expectation.projection(generator.archive(codec))
        elif operation == "extract":
            expected = expectation.failure("extract.ineligible-name", "POLICY", "EXTRACT", 0)
            expected["diagnostics"].insert(0, expectation.diagnostic("archive-name.traversal-segment", 0,
                "..\\meshes\\a.nif", "..", values={"segmentOrdinal": "0"}, operation="EXTRACT"))
        if operation in ("encode", "extract") and ("failure_kind" in expected or "exit_status" in expected):
            before = [{"path": "input.ba2", "kind": "file", "size": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}]
            expected.update(filesystem_before=before, filesystem_after=copy.deepcopy(before))
        if "failure_kind" in expected:
            case["metadata"]["assertions"] = ["explicit-rejection" if value == "decode-semantic-projection" else value
                for value in case["metadata"]["assertions"]]
        golden = {"contract": "conformance-v1", "case_id": identity["case_id"],
            "configuration_sha256": configuration["sha256"], "specification_sha256": spec_digest, "assertions": []}
        for assertion in case["metadata"]["assertions"]:
            value = digest(fixture_path) if assertion == "fixture-integrity" else expected
            if assertion == "oracle-to-jbsa":
                value = oracle["stored" if codec == "stored" else "zlib"][1]
            golden["assertions"].append({"assertion_id": assertion,
                "kind": "semantic" if assertion in ("decode-semantic-projection", "oracle-to-jbsa", "jbsa-to-oracle") else "exact",
                "expected": value})
        if operation == "encode" and case["metadata"]["expected_behavior"] == "accept":
            golden.update(oracle_sha256="4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2",
                oracle_archive=oracle["stored" if codec == "stored" else "zlib"][0],
                source_payloads=[{"path": name, "kind": "file", "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                    for name, data in (("meshes/a.nif", b"A" * 1024), ("meshes/b.nif", bytes.fromhex("000102ff")))])
        golden_path = object_file(golden, PROPOSAL / "goldens/sha256")
        manifest["goldens"].append({"id": identity["case_id"], "path": golden_path.relative_to(PROPOSAL).as_posix(),
            "sha256": digest(golden_path), "source_fixture_ids": [token]})
    manifest_path = PROPOSAL / "proposal-manifest.json"
    manifest_path.write_bytes(canonical(manifest))
    for token, fixture in fixtures.items():
        binding = {"state": "available", "files": [bind(PROPOSAL / fixture["output"]["path"])],
            "generator": {"id": manifest["generator"]["id"], "version": "1", "descriptor": bind(descriptor),
                "implementation": bind(Path(__file__).resolve()), "recipe_sha256": fixture["input_sha256"],
                "configuration": fixture["generation"]},
            "provenance": {"manifest": bind(manifest_path), "fixture_id": token, "oracle_sha256": None}}
        binding = json.loads(canonical(binding))
        description = "Proposed independent General BA2 v1 scenario " + token
        token_object = object_file({"kind": "fixture-or-scenario-descriptor", "token": token,
            "description": description, "binding": binding,
            "provenance": {"creator": "JBSA project contributors", "spdx_license": "CC0-1.0"}}, ROOT / "tests/conformance/objects/sha256")
        catalog["tokens"]["fixture"].append({"token": token, "description": description, **bind(token_object), "binding": binding})
        for case in catalog["cases"]:
            if case["identity"]["fixture"] == token:
                case["metadata"]["fixture_binding"] = binding
                case["metadata"]["golden_bindings"] = [bind(PROPOSAL / golden["path"]) for golden in manifest["goldens"] if token in golden["source_fixture_ids"]]
    catalog_path = PROPOSAL / "catalog.json"
    catalog_path.write_bytes(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    (PROPOSAL / "review.json").write_bytes(canonical({"status": "UNTRUSTED_PENDING_MAINTAINER_APPROVAL",
        "automated_conformance": False, "approval": None, "active_catalog": bind(active_path),
        "proposed_catalog": bind(catalog_path), "supersessions": successors, "fixture_manifest": bind(manifest_path),
        "authoring_inputs": [bind(ROOT / "build" / name) for name in ("prepare-ba2-cv1-review.py", "ba2-cv1-expectations.py", "generate-ba2-fixtures.py")]}))
    pending = []
    for golden in manifest["goldens"]:
        affected = [case for case in catalog["cases"] if case["identity"]["case_id"] == golden["id"]]
        binding = affected[0]["metadata"]["fixture_binding"]
        pending.append({"schema_version": 1, "golden_id": golden["id"], "status": "pending-maintainer-review",
            "old_sha256": "0" * 64, "new_sha256": golden["sha256"],
            "source_fixture_sha256s": [file["sha256"] for file in binding["files"]], "oracle_sha256": binding["provenance"]["oracle_sha256"],
            "generator": {"id": manifest["generator"]["id"], "version": "1"},
            "configuration": {"case_configuration": json.loads((ROOT / configuration["path"]).read_text()),
                "generator_configuration": binding["generator"]["configuration"], "specification_set": catalog["specification_set"]},
            "affected_case_ids": [golden["id"]], "approval": None,
            "rationale": "First independently authored expectations for new successor identities; zero old digest denotes no accepted golden.",
            "semantic_difference": "Replace missing bindings with explicit BA2 wire metadata, disposition, validation extent, diagnostic and byte-preservation observations."})
    (PROPOSAL / "pending-records.json").write_bytes(canonical(pending))
    print(json.dumps({"case_count": len(successors), "proposed_catalog": bind(catalog_path)}))


if __name__ == "__main__":
    prepare()
