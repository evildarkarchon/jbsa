"""Prepare new-identity, explicitly untrusted CV1 review artifacts; never activate the catalog."""

import copy
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import shutil

ROOT = Path(__file__).resolve().parent.parent
PROPOSAL = ROOT / "docs/reviews/issue38-cv1"
CORPUS = ROOT / "tests/fixtures/bsa067"


def load_module(name, filename):
    """Load only independent build-time expectation code, never a product implementation."""
    spec = importlib.util.spec_from_file_location(name, ROOT / "build" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def canonical(value):
    """Serialize review objects with sorted keys and stable UTF-8 bytes."""
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def digest(path):
    """Identify exact file bytes for immutable evidence binding."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bind(path):
    """Use portable repository-relative paths and exact SHA-256 digests."""
    return {"path": path.relative_to(ROOT).as_posix(), "sha256": digest(path)}


def write_object(value, directory):
    """Write a new content-addressed proposed object, rejecting a conflicting existing file."""
    content = canonical(value)
    key = hashlib.sha256(content).hexdigest()
    path = directory / (key + ".json")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_bytes() != content:
        raise ValueError("Conflicting content address")
    path.write_bytes(content)
    return path


def failure(kind, identifier, operation="OPEN", ordinal=None, name=None, values=None):
    """Draft the exact stable failure surface for a declared malformed recipe, for review."""
    return {"failure_kind": kind, "diagnostics": [{"identifier": identifier, "severity": "ERROR",
            "operation": operation, "affected": {"entry_ordinal": ordinal, "entry_name": name,
                                                   "field": None, "byte_span": None}, "values": values or {}}]}


def prepare(family="bsa-067"):
    """Prepare available successor recipes, preserving bound shared cases and listing coverage gaps."""
    global PROPOSAL, CORPUS
    if family == "bsa-068":
        PROPOSAL = ROOT / "docs/reviews/issue42-cv1"
        CORPUS = PROPOSAL / "generated-corpus"
        with tempfile.TemporaryDirectory(dir=ROOT / "target") as temporary:
            generated = Path(temporary) / "corpus"
            subprocess.run([sys.executable, str(ROOT / "build/generate-bsa68-cv1-fixtures.py"),
                "--output", str(generated)], check=True)
            shutil.copytree(generated, CORPUS, dirs_exist_ok=True)
    PROPOSAL.mkdir(parents=True, exist_ok=True)
    expectation = load_module("bsa_review_expectation", "bsa-cv1-expectations.py")
    wire_generator = load_module("bsa_wire_generator", "generate-bsa-fixtures.py")
    active = json.loads((ROOT / "tests/conformance/catalog.json").read_text())
    catalog = copy.deepcopy(active)
    manifest = json.loads((CORPUS / "manifest.json").read_text())
    fixtures = {fixture["id"]: fixture for fixture in manifest["fixtures"]}
    scenario_expectations = {}
    if family == "bsa-068":
        for name in ("bsa68-format-expectations.json", "bsa68-framing-expectations.json", "bsa68-shared-expectations.json"):
            path = ROOT / "build" / name
            if path.exists():
                for token, value in json.loads(path.read_text()).items():
                    scenario_expectations[token] = value if "scenario" in value else {"scenario": token, "checks": value}
    configuration = next(value for value in catalog["tokens"]["configuration"] if value["token"] == "standard-v1")
    specification_digest = hashlib.sha256(canonical(catalog["specification_set"])).hexdigest()
    codec_profile = (json.loads((ROOT / "jbsa/src/main/resources/META-INF/jbsa-codec-profile.json").read_text())["profile_id"]
                     if family == "bsa-068" else "jbsa-jdk-zlib-v1")
    successors, goldens, tokens, uncovered = [], [], {}, []
    oracle_inputs = ROOT / "target" / ("bsa068-cv1-review-inputs" if family == "bsa-068" else "bsa-cv1-review-inputs")
    oracle_inputs.mkdir(parents=True, exist_ok=True)
    oracle_projections = {}
    for codec in ("stored", "zlib"):
        oracle_hex = PROPOSAL / "oracle" / (codec + ".hex")
        if not oracle_hex.exists():
            oracle_hex.parent.mkdir(parents=True, exist_ok=True)
            work = ROOT / "target" / ((family if family == "bsa-068" else "bsa") + "-cv1-oracle-" + codec)
            sources = work / "sources/meshes"
            sources.mkdir(parents=True, exist_ok=True)
            (sources / "a.nif").write_bytes(b"A" * 1024)
            (sources / "b.nif").write_bytes(bytes.fromhex("000102ff"))
            archive = work / "oracle.bsa"
            evidence = ROOT / "target" / ((family if family == "bsa-068" else "bsa") + "-cv1-oracle-evidence-" + codec)
            command = ["pwsh", "-NoProfile", "-File", str(ROOT / "build/run-bsa-oracle.ps1"),
                       "-Operation", "pack", "-InputPath", str(sources.parent), "-OutputPath", str(archive),
                       "-Compression", codec, "-WorkingDirectory", str(work), "-EvidenceDirectory", str(evidence)]
            if family == "bsa-068":
                command += ["-Selector", "fo3"]
            result = subprocess.run(command, check=True, capture_output=True, text=True)
            observation = json.loads(result.stdout)
            oracle_hex.write_text(archive.read_bytes().hex() + "\n", encoding="ascii", newline="\n")
            receipt = {"oracle_sha256": "4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2",
                       "archive_sha256": digest(archive), "exit_status": observation["exit_status"],
                       "arguments": command[4:], "observation_sha256": digest(evidence / "observation.json"),
                       "stdout": Path(observation["stdout"]["path"]).read_text(errors="replace"),
                       "stderr": Path(observation["stderr"]["path"]).read_text(errors="replace")}
            oracle_hex.with_suffix(".receipt.json").write_bytes(canonical(receipt))
        raw = bytes.fromhex(oracle_hex.read_text())
        receipt_path = oracle_hex.with_suffix(".receipt.json")
        receipt = json.loads(receipt_path.read_text())
        receipt.update({"creator": "JBSA project contributors", "spdx_license": "CC0-1.0",
                        "redistribution_class": "project-authored-redistributable",
                        "source": "Pinned local oracle packs project-authored a.nif=A*1024 and b.nif=000102ff payloads",
                        "archive_hex_sha256": digest(oracle_hex)})
        receipt_path.write_bytes(canonical(receipt))
        oracle_path = oracle_inputs / (codec + ".bsa")
        oracle_path.write_bytes(raw)
        oracle_projections[codec] = (bind(oracle_path), expectation.projection(raw))
    for case in catalog["cases"]:
        identity = case["identity"]
        if identity["archive_family"] != family:
            continue
        if (family == "bsa-068" and case["metadata"]["fixture_binding"]["state"] == "available"
                and case["metadata"]["golden_bindings"]):
            continue
        old_id = identity["case_id"]
        old_fixture = identity["fixture"]
        is_base = old_fixture.startswith("base-" + family + "-")
        fixture_id = (family + "-" + identity["codec"] if is_base else
                      family + "-xml" if old_fixture == "bsa-xml-067" else
                      family + "-stored" if old_fixture == "bsa-automatic-flags" else family + "-" + old_fixture)
        scenario_token = next((value.removeprefix("coverage-") for value in case["metadata"]["assertions"]
                               if value.startswith("coverage-") and value.removeprefix("coverage-") in scenario_expectations), old_fixture)
        scenario_details = scenario_expectations.get(scenario_token)
        if scenario_details is not None:
            source_codec = ("mixed" if scenario_token == "compression-mixed" else "zlib"
                            if scenario_token in ("compression-consumption", "compression-size-mismatch") else "stored")
            fixture_id = (family + "-scenario-" + scenario_token if scenario_token in
                ("compression-boundaries", "names-wire-encodings", "source-splitting") else family + "-" + source_codec)
        if fixture_id not in fixtures:
            uncovered.append(old_id)
            continue
        fixture = fixtures[fixture_id]
        token = (family + "-scenario-" + scenario_token if scenario_details is not None else fixture_id) + "-v1"
        identity["fixture"] = token
        identity["case_id"] = "CV1-" + ".".join(identity[key] for key in ("archive_family", "operation", "fixture", "codec", "configuration"))
        if is_base:
            case["metadata"]["base_matrix_cell"] = True
        successors.append({"previous_case_id": old_id, "proposed_case_id": identity["case_id"],
                           "previous_fixture_binding": copy.deepcopy(case["metadata"]["fixture_binding"])})
        fixture_path = CORPUS / fixture["output"]["path"]
        raw = fixture_path.read_bytes() if fixture_path.suffix == ".json" else bytes.fromhex(fixture_path.read_text())
        observed = scenario_details if scenario_details is not None else expectation.projection(raw)
        if identity["operation"] == "decode" and identity["codec"] in ("raw-deflate", "raw-lz4", "lz4-frame"):
            observed = failure("FORMAT", "codec.invalid-data", "READ_CONTENT", 0, "meshes\\a.nif",
                               {"codec": "zlib", "direction": "decode", "expected": "1024", "actual": "0", "profile": codec_profile})
        rejected_recipes = {"malformed-arithmetic-overflow": "io.invalid-span",
                            "malformed-equal-name-identities": "bsa.duplicate-name",
                            "malformed-impossible-counts": "bsa.unterminated-basename",
                            "malformed-out-of-range-spans": "io.invalid-span",
                            "malformed-partial-overlap": "bsa.overlapping-payloads",
                            "malformed-truncated-spans": "io.invalid-span"}
        if old_fixture in rejected_recipes:
            observed = failure("FORMAT", rejected_recipes[old_fixture])
        if old_fixture == "malformed-decompression-mismatch":
            observed = failure("FORMAT", "codec.size-mismatch", "READ_CONTENT", 0, "meshes\\a.nif",
                               {"codec": "zlib", "direction": "decode", "expected": "1025", "actual": "1024", "profile": codec_profile})
        if identity["operation"] == "encode":
            if identity["codec"] == "raw-deflate":
                observed = {"exit_status": 2, "artifact_exists": False}
            elif identity["codec"] in ("raw-lz4", "lz4-frame"):
                observed = failure("UNSUPPORTED", "bsa.unsupported-codec", "PACK")
            else:
                raw_expected = bytearray(wire_generator.archive(identity["codec"]))
                if family == "bsa-068":
                    import struct
                    struct.pack_into("<I", raw_expected, 4, 104)
                    struct.pack_into("<I", raw_expected, 12, struct.unpack_from("<I", raw_expected, 12)[0] & ~0x600)
                observed = expectation.projection(raw_expected)
        elif identity["operation"] == "extract":
            observed = failure("POLICY", "extract.ineligible-name", "EXTRACT", 0)
            warnings = [{"identifier": "archive-name.traversal-segment", "severity": "WARNING", "operation": "EXTRACT",
                         "affected": {"entry_ordinal": ordinal, "entry_name": "..\\abc\\" + name,
                                      "field": "..", "byte_span": None}, "values": {"segmentOrdinal": "0"}}
                        for ordinal, name in enumerate(("a.nif", "b.nif"))]
            observed["diagnostics"] = [warnings[0], observed["diagnostics"][0], warnings[1]]
        elif old_fixture == "malformed-illegal-tuples":
            observed = failure("UNSUPPORTED", "archive.unsupported-variant")
        elif identity["operation"] == "scenario":
            if scenario_details is not None:
                observed = scenario_details
            elif old_fixture == "bsa-automatic-flags":
                # This scenario repacks the source archive and checks automatic family flags.
                observed = expectation.projection(raw)
            if "entries" in observed:
                observed["entries"].sort(key=lambda entry: (entry["folder_hash"], entry["file_hash"]))
        if identity["operation"] in ("encode", "extract") and ("failure_kind" in observed or "exit_status" in observed):
            before = [{"path": "input.bsa", "kind": "file", "size": len(raw),
                       "sha256": hashlib.sha256(raw).hexdigest()}]
            observed["filesystem_before"] = before
            observed["filesystem_after"] = copy.deepcopy(before)
        if "failure_kind" in observed and "decode-semantic-projection" in case["metadata"]["assertions"]:
            # Rejected archives cannot expose a complete successful semantic projection. Successor
            # cases retain their coverage assertion and explicitly assert the owning rejection.
            case["metadata"]["assertions"] = ["explicit-rejection" if value == "decode-semantic-projection" else value
                                                for value in case["metadata"]["assertions"]]
        golden = {"contract": "conformance-v1", "case_id": identity["case_id"],
                  "configuration_sha256": configuration["sha256"], "specification_sha256": specification_digest,
                  "assertions": []}
        for assertion in case["metadata"]["assertions"]:
            value = fixture["output"]["sha256"] if assertion == "fixture-integrity" else observed
            if assertion == "oracle-to-jbsa":
                value = oracle_projections["stored" if identity["codec"] == "stored" else "zlib"][1]
            kind = "semantic" if assertion in ("decode-semantic-projection", "oracle-to-jbsa", "jbsa-to-oracle") else "exact"
            golden["assertions"].append({"assertion_id": assertion, "kind": kind, "expected": value})
        if identity["operation"] == "encode" and case["metadata"]["expected_behavior"] == "accept":
            golden["encode_configuration"] = {"oracle_global_compression": "stored" if identity["codec"] == "stored" else "zlib",
                                               "candidate_global_compression": "stored" if identity["codec"] == "stored" else "zlib",
                                               "candidate_entry_compression": {"meshes\\b.nif": "stored"} if identity["codec"] == "mixed" else {},
                                               "workers": 1, "sharing": False, "split_bytes": 0}
            golden["oracle_sha256"] = "4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2"
            golden["oracle_archive"] = oracle_projections["stored" if identity["codec"] == "stored" else "zlib"][0]
            golden["source_payloads"] = [{"path": "meshes/a.nif", "kind": "file", "size": 1024,
                                          "sha256": hashlib.sha256(b"A" * 1024).hexdigest()},
                                         {"path": "meshes/b.nif", "kind": "file", "size": 4,
                                          "sha256": hashlib.sha256(bytes.fromhex("000102ff")).hexdigest()}]
        golden_path = write_object(golden, CORPUS / "goldens/sha256")
        goldens.append({"id": identity["case_id"].removeprefix("CV1-").replace(".", "-"), "path": golden_path.relative_to(CORPUS).as_posix(),
                        "sha256": digest(golden_path), "source_fixture_ids": [fixture_id]})
        tokens[token] = fixture
    manifest["goldens"] = goldens
    proposal_manifest = CORPUS / "proposal-manifest.json"
    proposal_manifest.write_bytes(canonical(manifest))
    for token, fixture in tokens.items():
        binding = {"state": "available", "files": [bind(CORPUS / fixture["output"]["path"])],
                   "generator": {"id": manifest["generator"]["id"], "version": manifest["generator"]["version"],
                                 "descriptor": bind(CORPUS / "generator.json"),
                                 "implementation": bind(ROOT / manifest["generator"]["implementation"]),
                                 "recipe_sha256": fixture["input_sha256"], "configuration": fixture["generation"]},
                   "provenance": {"manifest": bind(proposal_manifest), "fixture_id": fixture["id"],
                                  "oracle_sha256": "4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2"
                                  if fixture["id"] in (family + "-stored", family + "-zlib", family + "-mixed") else None}}
        binding = json.loads(canonical(binding))
        description = "Proposed independently authored " + ("TES4" if family == "bsa-067" else family) + " " + fixture["id"] + " fixture, revision 1"
        descriptor = {"kind": "fixture-or-scenario-descriptor", "token": token, "description": description,
                      "binding": binding, "provenance": {"creator": "JBSA project contributors", "spdx_license": "CC0-1.0"}}
        descriptor_path = write_object(descriptor, ROOT / "tests/conformance/objects/sha256")
        catalog["tokens"]["fixture"].append({"token": token, "description": description,
                                               **bind(descriptor_path), "binding": binding})
        for case in catalog["cases"]:
            if case["identity"]["fixture"] == token:
                case["metadata"]["fixture_binding"] = binding
                case["metadata"]["golden_bindings"] = [bind(CORPUS / golden["path"]) for golden in goldens
                                                          if fixture["id"] in golden["source_fixture_ids"]]
    catalog_path = PROPOSAL / "catalog.json"
    # Preserve legacy descriptor object order: the current loader also checks its serialized shape.
    catalog_path.write_bytes(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    review = {"status": "UNTRUSTED_PENDING_MAINTAINER_APPROVAL", "requirement": "JBSA-CONF-007",
              "active_catalog": bind(ROOT / "tests/conformance/catalog.json"), "proposed_catalog": bind(catalog_path),
              "fixture_manifest": bind(proposal_manifest), "supersessions": successors,
              "uncovered_case_ids": uncovered,
              "goldens": goldens, "approval": None,
              "authoring_inputs": [bind(ROOT / "build" / name) for name in
                                   ("prepare-bsa-cv1-review.py", "bsa-cv1-expectations.py",
                                    "validate-bsa-wire.py", "generate-bsa-fixtures.py")]
                                  + [bind(path) for path in sorted((PROPOSAL / "oracle").iterdir())],
              "rationale": "Materialize immutable missing assignments as new case identities with independent wire evidence; preserve old descriptor objects unchanged."}
    if family == "bsa-068":
        review["authoring_inputs"] += [bind(ROOT / "build/generate-bsa68-cv1-fixtures.py")]
        review["authoring_inputs"] += [bind(ROOT / "jbsa/src/main/resources/META-INF/jbsa-codec-profile.json")]
        review["authoring_inputs"] += [bind(ROOT / "jbsa-test-support/src/main/java/io/github/evildarkarchon/jbsa/fixtures/BsaCv1FixtureGenerator.java")]
        review["authoring_inputs"] += [bind(ROOT / "build/bsa68-valid-split-recipe.json")]
        review["authoring_inputs"] += [bind(path) for path in sorted((ROOT / "build").glob("bsa68-*-expectations.json"))]
    (PROPOSAL / "review.json").write_bytes(canonical(review))
    pending_records = []
    for golden in goldens:
        affected = [case for case in catalog["cases"] if any(binding["sha256"] == golden["sha256"]
                    for binding in case["metadata"]["golden_bindings"])]
        fixture_binding = affected[0]["metadata"]["fixture_binding"]
        record = {"schema_version": 1, "golden_id": golden["id"], "status": "pending-maintainer-review",
                  "old_sha256": "0" * 64, "new_sha256": golden["sha256"],
                  "source_fixture_sha256s": [value["sha256"] for value in fixture_binding["files"]],
                  "oracle_sha256": fixture_binding["provenance"]["oracle_sha256"],
                  "generator": {"id": manifest["generator"]["id"], "version": manifest["generator"]["version"]},
                  "configuration": {"case_configuration": json.loads((ROOT / configuration["path"]).read_text()),
                                    "generator_configuration": fixture_binding["generator"]["configuration"],
                                    "specification_set": catalog["specification_set"]},
                  "affected_case_ids": [case["identity"]["case_id"] for case in affected],
                  "rationale": "First independently authored expected bytes for successor cases; old zero digest denotes no previously accepted golden.",
                  "semantic_difference": "Replaces missing evidence with exact wire semantics, diagnostic fields, payload digests and required local differential observations.",
                  "approval": None}
        pending_records.append(record)
    (PROPOSAL / "pending-records.json").write_bytes(canonical(pending_records))
    print(json.dumps({"proposal": str(catalog_path), "case_count": len(successors), "catalog_sha256": digest(catalog_path)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--family", choices=("bsa-067", "bsa-068"), default="bsa-067")
    prepare(parser.parse_args().family)
