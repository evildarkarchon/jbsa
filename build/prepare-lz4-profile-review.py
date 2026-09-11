"""Prepare independently derived diagnostic profile rebaseline evidence, without activation."""

import copy
import hashlib
import importlib.util
import json
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "docs/reviews/issue43-lz4-runtime/profile-rebinding"
OLD_COMMIT = "0ca37d5a02b9ae9b7adccffd4f2cb667339a65f5"
PROFILE = "jbsa/src/main/resources/META-INF/jbsa-codec-profile.json"


def prepare():
    """Derive a successor packet from admitted assertions and independently pinned manifests.

    Reuse the specification packet's provenance machinery with the new suffix;
    validate every assertion against a separately computed exact transformation.
    """
    source = ROOT / "build/prepare-lz4-spec-review.py"
    module_spec = importlib.util.spec_from_file_location("spec_packet", source)
    module = importlib.util.module_from_spec(module_spec)
    # This derivative uses the already reviewed catalog machinery without changing its file.
    adapted = source.read_text().replace("-runtime-v1", "-profile-v1")
    exec(compile(adapted, str(source), "exec"), module.__dict__)
    module.OUTPUT = OUTPUT
    canonical, binding = module.canonical, module.binding
    authority_dir = OUTPUT / "authority"
    authority_dir.mkdir(parents=True, exist_ok=True)
    historical = subprocess.check_output(["git", "show", f"{OLD_COMMIT}:{PROFILE}"], cwd=ROOT)
    old_path = authority_dir / "source-old-profile.json"
    old_path.write_bytes(historical)
    current_path = ROOT / PROFILE
    pinned = authority_dir / "packaged-profile-manifest.json"
    pinned.write_bytes(current_path.read_bytes())
    old_id = json.loads(historical)["profile_id"]
    new_id = json.loads(pinned.read_bytes())["profile_id"]
    if old_id != "jbsa-jdk-zlib-v1" or new_id != "jbsa-lz4-v1":
        raise ValueError("Unexpected independently pinned profile identities")
    active = ROOT / "tests/conformance/catalog.json"
    catalog = json.loads(active.read_bytes())
    specification = catalog["specification_set"]
    if specification != [binding(ROOT / item["path"]) for item in specification]:
        raise ValueError("Specification drift; this packet must preserve the admitted specification")
    spec_digest = hashlib.sha256(canonical(specification)).hexdigest()
    goldens = {}
    for case in catalog["cases"]:
        for golden in case["metadata"]["golden_bindings"]:
            goldens.setdefault(golden["path"], {"binding": golden, "cases": []})["cases"].append(case)
    proposals = []
    for path, entry in sorted(goldens.items()):
        if binding(ROOT / path) != entry["binding"]:
            raise ValueError(f"Admitted digest mismatch: {path}")
        old = json.loads((ROOT / path).read_bytes())
        if old.get("contract") != "conformance-v1" or old["specification_sha256"] != spec_digest:
            raise ValueError("Only currently admitted executable assertions may be rebound")
        successor = copy.deepcopy(old)
        identity = next(c["identity"] for c in entry["cases"] if c["identity"]["case_id"] == old["case_id"])
        fixture = identity["fixture"] + "-profile-v1"
        successor["case_id"] = f"CV1-{identity['archive_family']}.{identity['operation']}.{fixture}.{identity['codec']}.{identity['configuration']}"
        changed = rebind(successor["assertions"], old_id, new_id)
        content = canonical(successor)
        target = OUTPUT / "goldens/sha256" / (hashlib.sha256(content).hexdigest() + ".json")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        proposals.append({"old": entry["binding"], "proposed": binding(target),
            "old_case_id": old["case_id"], "proposed_case_id": successor["case_id"],
            "affected_case_ids": [c["identity"]["case_id"] for c in entry["cases"]],
            "assertions_sha256": hashlib.sha256(canonical(old["assertions"])).hexdigest(),
            "changed_json_paths": changed,
            "semantic_difference": "Successor case identity and listed diagnostic values.profile paths only; all other observations and specification digest preserved.",
            "approval": None, "status": "UNTRUSTED_PENDING_MAINTAINER_APPROVAL"})
    module.prepare_catalog(catalog, specification, proposals)
    candidate = json.loads((OUTPUT / "catalog.json").read_bytes())
    if len(proposals) != 109 or len(candidate["cases"]) != len(catalog["cases"]):
        raise ValueError("Lost admitted cases or goldens")
    for old_case, new_case in zip(catalog["cases"], candidate["cases"]):
        for key in old_case["identity"]:
            if key not in {"case_id", "fixture"} and old_case["identity"][key] != new_case["identity"][key]:
                raise ValueError("Provider or operation identity drift")
        if [f["sha256"] for f in old_case["metadata"]["fixture_binding"]["files"]] != [f["sha256"] for f in new_case["metadata"]["fixture_binding"]["files"]]:
            raise ValueError("Fixture bytes changed")
    for proposal in proposals:
        expected = json.loads((ROOT / proposal["old"]["path"]).read_bytes())
        expected["case_id"] = proposal["proposed_case_id"]
        paths = rebind(expected["assertions"], old_id, new_id)
        actual = json.loads((ROOT / proposal["proposed"]["path"]).read_bytes())
        if actual != expected or paths != proposal["changed_json_paths"]:
            raise ValueError("Unintended observation edit")
    records_path = OUTPUT / "pending-records.json"
    records = json.loads(records_path.read_bytes())
    by_digest = {p["proposed"]["sha256"]: p for p in proposals}
    for record in records:
        record["rationale"] = "Required diagnostic profile identity derived from independently pinned historical and packaged manifests."
        record["changed_json_paths"] = by_digest[record["new_sha256"]]["changed_json_paths"]
    records_path.write_bytes(canonical(records))
    packet = {"status": "UNTRUSTED_PENDING_MAINTAINER_APPROVAL", "approval": None,
        "active_catalog": binding(active), "baseline_catalog": binding(OUTPUT / "baseline-catalog.json"),
        "generator": binding(Path(__file__).resolve()), "catalog_generator": binding(source),
        "activation_tool": binding(ROOT / "build/activate-interface-candidate-review.py"),
        "proposed_catalog": binding(OUTPUT / "catalog.json"), "pending_records": binding(records_path),
        "old_specification_set": specification, "proposed_specification_set": specification,
        "proposed_specification_sha256": spec_digest,
        "independent_profile_authority": {"historical_commit": OLD_COMMIT, "historical_source_path": PROFILE,
            "source_old_profile": binding(old_path), "packaged_source": binding(current_path),
            "pinned_manifest": binding(pinned), "old_profile_id": old_id, "new_profile_id": new_id},
        "proposals": proposals, "unqualified_structural_templates": [],
        "case_count": len(candidate["cases"]),
        "changed_golden_count": sum(bool(p["changed_json_paths"]) for p in proposals),
        "changed_assertion_count": sum(len(p["changed_json_paths"]) for p in proposals),
        "activation_requirements": ["Independently verify pinned profile authority and exact JSON changes.",
            "Approve exact packet digest before activation; do not infer expectations from candidate output.",
            "Execute conformance after activation; packet generation is not qualification."]}
    review = OUTPUT / "review.json"
    review.write_bytes(canonical(packet))
    print(json.dumps({"review": binding(review), "cases": packet["case_count"], "goldens": len(proposals),
        "changed_goldens": packet["changed_golden_count"], "changed_assertions": packet["changed_assertion_count"]}))


def rebind(assertions, old_id, new_id):
    """Replace exact historical diagnostic profile leaves, returning RFC 6901 paths."""
    changes = []

    def visit(value, path):
        """Traverse containers and constrain replacements to diagnostic values objects."""
        if isinstance(value, dict):
            for key, item in value.items():
                next_path = path + [key]
                if key == "profile" and item == old_id and path[-1] == "values" and any("diagnostic" in str(p).lower() for p in path):
                    value[key] = new_id
                    changes.append("/" + "/".join(str(p).replace("~", "~0").replace("/", "~1") for p in next_path))
                else:
                    visit(item, next_path)
        elif isinstance(value, list):
            for index, item in enumerate(value):
                visit(item, path + [index])

    visit(assertions, ["assertions"])
    return changes


if __name__ == "__main__":
    prepare()
