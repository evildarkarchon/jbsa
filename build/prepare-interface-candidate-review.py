"""Prepare untrusted specification-rebinding evidence without changing admitted goldens."""

import hashlib
import json
import copy
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "docs/reviews/issue41-interface/conformance-rebinding"


def canonical(value):
    """Return deterministic UTF-8 JSON bytes for digest-bound review artifacts."""
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def binding(path):
    """Bind exact repository bytes, preserving the source's portable path."""
    return {"path": path.relative_to(ROOT).as_posix(),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def prepare_catalog(catalog, specification, proposed):
    """Build complete successor provenance, descriptors, catalog and pending reviews."""
    candidate = copy.deepcopy(catalog)
    candidate["specification_set"] = specification
    by_digest = {item["old"]["sha256"]: item for item in proposed}
    manifests = {}
    changed_tokens = {}
    for token in candidate["tokens"]["fixture"]:
        original = token["binding"]
        if original["state"] != "available":
            continue
        source_path = original["provenance"]["manifest"]["path"]
        if source_path not in manifests:
            source = ROOT / source_path
            manifest = json.loads(source.read_bytes())
            directory = OUTPUT / "corpora" / hashlib.sha256(source_path.encode()).hexdigest()[:16]
            directory.mkdir(parents=True, exist_ok=True)
            for fixture in manifest["fixtures"]:
                raw = source.parent / fixture["output"]["path"]
                target = directory / "artifacts" / (fixture["output"]["sha256"] + raw.suffix)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(raw, target)
                fixture["output"]["path"] = target.relative_to(directory).as_posix()
            refreshed = []
            for golden in manifest["goldens"]:
                proposal = by_digest.get(golden["sha256"])
                if proposal is None:
                    continue
                golden["sha256"] = proposal["proposed"]["sha256"]
                golden["id"] = proposal["proposed_case_id"].lower().replace(".", "-")
                raw = ROOT / proposal["proposed"]["path"]
                target = directory / "goldens" / (golden["sha256"] + ".json")
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(raw, target)
                golden["path"] = target.relative_to(directory).as_posix()
                refreshed.append(golden)
            manifest["goldens"] = refreshed
            target = directory / "manifest.json"
            target.write_bytes(canonical(manifest))
            manifests[source_path] = (target, manifest)
        manifest_path, manifest = manifests[source_path]
        old_token = token["token"]
        token["token"] += "-interface-v1"
        fixture = next(f for f in manifest["fixtures"] if f["id"] == original["provenance"]["fixture_id"])
        original["files"] = [binding(manifest_path.parent / fixture["output"]["path"])]
        original["provenance"]["manifest"] = binding(manifest_path)
        original = json.loads(canonical(original))
        token["binding"] = original
        descriptor = {"kind": "fixture-or-scenario-descriptor", "token": token["token"],
            "description": token["description"], "binding": original,
            "provenance": {"creator": "JBSA project contributors", "spdx_license": "CC0-1.0"}}
        content = canonical(descriptor)
        target = ROOT / "tests/conformance/objects/sha256" / (hashlib.sha256(content).hexdigest() + ".json")
        target.write_bytes(content)
        token.update(binding(target))
        changed_tokens[old_token] = (token, manifest_path, manifest)
    for case in candidate["cases"]:
        change = changed_tokens.get(case["identity"]["fixture"])
        if change is None:
            continue
        token, manifest_path, manifest = change
        identity = case["identity"]
        if identity["fixture"] == f"base-{identity['archive_family']}-{identity['codec']}":
            case["metadata"]["base_matrix_cell"] = True
        identity["fixture"] = token["token"]
        identity["case_id"] = f"CV1-{identity['archive_family']}.{identity['operation']}.{identity['fixture']}.{identity['codec']}.{identity['configuration']}"
        case["metadata"]["fixture_binding"] = token["binding"]
        fixture_id = token["binding"]["provenance"]["fixture_id"]
        case["metadata"]["golden_bindings"] = [binding(manifest_path.parent / g["path"])
            for g in manifest["goldens"] if fixture_id in g["source_fixture_ids"]]
    records = []
    for proposal in proposed:
        cases = [c for c in candidate["cases"] if any(g["sha256"] == proposal["proposed"]["sha256"] for g in c["metadata"]["golden_bindings"])]
        case = cases[0]
        fixture = case["metadata"]["fixture_binding"]
        configuration = next(t for t in candidate["tokens"]["configuration"] if t["token"] == case["identity"]["configuration"])
        records.append({"schema_version": 1, "golden_id": proposal["proposed_case_id"].lower().replace(".", "-"),
            "status": "pending-maintainer-review", "old_sha256": proposal["old"]["sha256"],
            "new_sha256": proposal["proposed"]["sha256"], "source_fixture_sha256s": [f["sha256"] for f in fixture["files"]],
            "oracle_sha256": fixture["provenance"]["oracle_sha256"],
            "generator": {k: fixture["generator"][k] for k in ("id", "version")},
            "configuration": {"case_configuration": json.loads((ROOT / configuration["path"]).read_bytes()),
                "generator_configuration": fixture["generator"]["configuration"], "specification_set": specification},
            "affected_case_ids": [c["identity"]["case_id"] for c in cases], "approval": None,
            "rationale": "Interface Candidate specification rebinding; original independently approved observations retained.",
            "semantic_difference": proposal["semantic_difference"]})
    (OUTPUT / "catalog.json").write_text(json.dumps(candidate, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    (OUTPUT / "baseline-catalog.json").write_bytes(canonical(catalog))
    (OUTPUT / "pending-records.json").write_bytes(canonical(records))


def prepare():
    """Inventory every active golden and propose assertion-preserving successor bytes.

    Corpus manifests, immutable fixture tokens, successor cases and pending records
    are prepared together; activation requires explicit approval of the packet.
    """
    active = ROOT / "tests/conformance/catalog.json"
    catalog = json.loads(active.read_bytes())
    specification = [binding(ROOT / item["path"]) for item in catalog["specification_set"]]
    spec_digest = hashlib.sha256(canonical(specification)).hexdigest()
    proposed, unresolved = [], []
    goldens = {}
    for case in catalog["cases"]:
        for golden in case["metadata"]["golden_bindings"]:
            goldens.setdefault(golden["path"], {"binding": golden, "cases": []})["cases"].append(case)
    for path, entry in sorted(goldens.items()):
        original = ROOT / path
        if binding(original) != entry["binding"]:
            raise ValueError(f"Active golden digest mismatch: {path}")
        old = json.loads(original.read_bytes())
        affected = [case["identity"]["case_id"] for case in entry["cases"]]
        if old.get("contract") != "conformance-v1":
            unresolved.append({"golden": entry["binding"], "affected_case_ids": affected,
                "reason": "Structural template has no executable CV1 assertion contract; it is not promoted to qualified evidence."})
            continue
        successor = dict(old)
        source_case = next(case for case in entry["cases"] if case["identity"]["case_id"] == old["case_id"])
        identity = source_case["identity"]
        successor_fixture = identity["fixture"] + "-interface-v1"
        successor["case_id"] = f"CV1-{identity['archive_family']}.{identity['operation']}.{successor_fixture}.{identity['codec']}.{identity['configuration']}"
        successor["specification_sha256"] = spec_digest
        # Preserve independently approved observations; no candidate execution can supply expected bytes.
        assert successor["assertions"] == old["assertions"]
        content = canonical(successor)
        target = OUTPUT / "goldens/sha256" / (hashlib.sha256(content).hexdigest() + ".json")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        proposed.append({"old": entry["binding"], "proposed": binding(target),
            "old_case_id": old["case_id"], "proposed_case_id": successor["case_id"],
            "affected_case_ids": affected, "assertions_sha256": hashlib.sha256(canonical(old["assertions"])).hexdigest(),
            "semantic_difference": "Only case identity and governing specification digest change; all assertion bytes are preserved.",
            "approval": None, "status": "UNTRUSTED_PENDING_MAINTAINER_APPROVAL"})
    OUTPUT.mkdir(parents=True, exist_ok=True)
    prepare_catalog(catalog, specification, proposed)
    packet = {"status": "UNTRUSTED_PENDING_MAINTAINER_APPROVAL", "approval": None,
        "active_catalog": binding(active), "generator": binding(Path(__file__).resolve()),
        "activation_tool": binding(ROOT / "build/activate-interface-candidate-review.py"),
        "proposed_catalog": binding(OUTPUT / "catalog.json"),
        "baseline_catalog": binding(OUTPUT / "baseline-catalog.json"),
        "pending_records": binding(OUTPUT / "pending-records.json"),
        "old_specification_set": catalog["specification_set"], "proposed_specification_set": specification,
        "proposed_specification_sha256": spec_digest, "proposals": proposed,
        "unqualified_structural_templates": unresolved,
        "deferred_post_1_0": {"requirement": "JBSA-SCOPE-009", "retained_case_ids": [
            "CV1-fo4-dx10-v1.encode.dds-target-mismatch-xbox.zlib.xbox-v1",
            "CV1-fo4-dx10-v1.encode.dds-target-xbox.zlib.xbox-v1",
            "CV1-fo4-dx10-v1.scenario.dds-reconstruction-selection.zlib.pc-v1"]},
        "activation_requirements": ["Review governing specification changes, unchanged assertions and complete successor catalog.",
            "Approve this exact review packet digest and invoke the included activation tool with the maintainer identity.",
            "The activation tool validates the successor catalog and approved rebaseline against this exact historical catalog.",
            "Execute required adapters and retain actual results; proposal creation is not qualification."]}
    (OUTPUT / "review.json").write_bytes(canonical(packet))
    print(f"Prepared {len(proposed)} untrusted assertion successors; {len(unresolved)} structural templates remain unqualified.")


if __name__ == "__main__":
    prepare()
