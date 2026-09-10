"""Prepare a source-location correction for unqualified synthetic cases only."""

import copy
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def canonical(value):
    """Serialize deterministic descriptor and evidence bytes."""
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()


def bind(path):
    """Bind exact repository-relative source bytes."""
    return {"path": path.relative_to(ROOT).as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def prepare():
    """Stage a catalog correction without changing the active catalog or reviewed packet."""
    active = ROOT / "tests/conformance/catalog.json"
    original = json.loads(active.read_bytes())
    candidate = copy.deepcopy(original)
    source = ROOT / "tests/fixtures/synthetic/manifest.json"
    manifest = json.loads(source.read_bytes())
    manifest["goldens"] = []
    manifest_path = ROOT / "docs/development/evidence/issue41-unqualified-manifest.json"
    # The catalog resolver accepts repository-contained relative paths and rejects escapes.
    # Reuse tracked bytes without adding files to the immutable generated synthetic corpus.
    for item in manifest["fixtures"]:
        item["output"]["path"] = "../../../tests/fixtures/synthetic/" + item["output"]["path"]
    manifest_path.write_bytes(canonical(manifest))
    changed = {}
    used_tokens = {c["identity"]["fixture"] for c in candidate["cases"]}
    for token in candidate["tokens"]["fixture"]:
        if token["token"] not in used_tokens:
            continue
        fixture = token["binding"]
        if fixture["state"] != "available" or "corpora/88727fae349b7e58/" not in fixture["provenance"]["manifest"]["path"]:
            continue
        old_token = token["token"]
        token["token"] += "-tracked-source-v1"
        source_fixture = next(f for f in manifest["fixtures"] if f["id"] == fixture["provenance"]["fixture_id"])
        raw_binding = bind((manifest_path.parent / source_fixture["output"]["path"]).resolve())
        raw_binding["path"] = manifest_path.parent.relative_to(ROOT).as_posix() + "/" + source_fixture["output"]["path"]
        fixture["files"] = [raw_binding]
        fixture["provenance"]["manifest"] = bind(manifest_path)
        fixture = json.loads(canonical(fixture))
        token["binding"] = fixture
        descriptor = {"kind": "fixture-or-scenario-descriptor", "token": token["token"], "description": token["description"],
            "binding": fixture, "provenance": {"creator": "JBSA project contributors", "spdx_license": "CC0-1.0"}}
        content = canonical(descriptor)
        target = ROOT / "tests/conformance/objects/sha256" / (hashlib.sha256(content).hexdigest() + ".json")
        target.write_bytes(content)
        token.update(bind(target))
        changed[old_token] = token
    repaired_cases = []
    for case in candidate["cases"]:
        token = changed.get(case["identity"]["fixture"])
        if token is None:
            continue
        assert case["metadata"]["golden_bindings"] == []
        before = case["identity"]["case_id"]
        identity = case["identity"]
        identity["fixture"] = token["token"]
        identity["case_id"] = f"CV1-{identity['archive_family']}.{identity['operation']}.{identity['fixture']}.{identity['codec']}.{identity['configuration']}"
        case["metadata"]["fixture_binding"] = token["binding"]
        repaired_cases.append({"old": before, "new": identity["case_id"]})
    admitted_before = [c for c in original["cases"] if c["metadata"]["golden_bindings"]]
    admitted_after = [c for c in candidate["cases"] if c["metadata"]["golden_bindings"]]
    assert admitted_before == admitted_after and len(admitted_after) == 109
    assert len(changed) == 12 and len(repaired_cases) == 16
    target = ROOT / "target/issue41-tracked-source-catalog.json"
    target.parent.mkdir(exist_ok=True)
    target.write_text(json.dumps(candidate, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    receipt = {"status": "prepared-not-activated", "rationale": "Reuse original tracked synthetic bytes within the compliance allowlist; no golden evidence changes.",
        "before_catalog": bind(active), "proposed_catalog": bind(target), "admitted_case_count": 109,
        "admitted_cases_sha256": hashlib.sha256(canonical(admitted_after)).hexdigest(), "admitted_cases_exactly_unchanged": True,
        "golden_rebaseline_required": False, "repaired_cases": repaired_cases, "source_manifest": bind(manifest_path)}
    (ROOT / "docs/development/evidence/issue41-source-repair.json").write_bytes(canonical(receipt))
    print("Prepared 16 unqualified case source repairs; all 109 admitted cases exactly unchanged.")


if __name__ == "__main__":
    prepare()
