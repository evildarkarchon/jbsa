"""Generate version-104 CV1 recipes using the unchanged common TES4 independent generator."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import shutil

ROOT = Path(__file__).resolve().parent.parent


def canonical(value):
    """Encode deterministic manifest bytes without depending on product code."""
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def generate(output):
    """Reuse common wire records and change only version-specific header fields and provenance."""
    if output.exists() and any(output.iterdir()):
        raise ValueError("Fixture destination must be absent or empty")
    output.mkdir(parents=True, exist_ok=True)
    java = (Path(os.environ["JAVA_HOME"]) / "bin/java.exe" if os.environ.get("JAVA_HOME")
            else Path(shutil.which("java") or "java")).resolve()
    runtime = subprocess.run([str(java), "--version"], check=True, capture_output=True, text=True)
    with tempfile.TemporaryDirectory(dir=ROOT / "target") as temporary:
        subprocess.run([str(java), "-cp", str(ROOT / "jbsa-test-support/target/classes"),
            "io.github.evildarkarchon.jbsa.fixtures.BsaCv1FixtureGenerator", "--output", temporary], check=True)
        source = Path(temporary)
        manifest = json.loads((source / "manifest.json").read_text())
        recipes = []
        for fixture in manifest["fixtures"]:
            raw = bytearray.fromhex((source / fixture["output"]["path"]).read_text())
            if not fixture["id"].endswith("illegal-tuples"):
                struct.pack_into("<I", raw, 4, 104)
            flags = struct.unpack_from("<I", raw, 12)[0] & ~0x600
            # 0x100 owns embedded framing in104; use an ignored high bit for the common anomaly.
            if fixture["id"].endswith("ignorable-constants"):
                flags = (flags & ~0x100) | 0x10000
            struct.pack_into("<I", raw, 12, flags)
            fixture["id"] = fixture["id"].replace("bsa-067", "bsa-068")
            procedure = fixture["generation"]["procedure"].replace("TES4", "BSA 0x68") + "; version104 automatic archive flags"
            fixture["coverage"] = ["structural", "bsa-068"]
            fixture["source"] = "Independently authored from docs/spec/formats/versioned-bsa.md; " + procedure
            fixture["generation"] = {"procedure": procedure,
                "command": "python build/generate-bsa68-cv1-fixtures.py --output <empty-directory>",
                "options": {"representation": "lowercase hexadecimal plus LF", "wire_size": str(len(raw)),
                            "wire_sha256": hashlib.sha256(raw).hexdigest()}}
            recipe = "bsa-068-v1|" + fixture["id"] + "|" + procedure
            fixture["input_sha256"] = hashlib.sha256(recipe.encode()).hexdigest()
            encoded = (raw.hex() + "\n").encode("ascii")
            path = "artifacts/" + fixture["id"] + ".hex"
            destination = output / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(encoded)
            fixture["output"] = {"path": path, "sha256": hashlib.sha256(encoded).hexdigest()}
            recipes.append({"id": fixture["id"], "input": recipe, "output_path": path,
                            "kind": fixture["kind"], "coverage": fixture["coverage"], "parameters": fixture["generation"]["options"]})
        manifest["corpus_id"] = "jbsa-bsa068-cv1-fixtures-v1"
        for scenario in ("compression-boundaries", "names-wire-encodings", "source-splitting"):
            original_name = {"names-wire-encodings": "name-encoding", "source-splitting": "split-boundaries"}.get(scenario, scenario)
            original = ROOT / "tests/fixtures/synthetic/artifacts/scenarios" / (original_name + ".json")
            encoded = original.read_bytes()
            identifier = "bsa-068-scenario-" + scenario
            path = "artifacts/" + identifier + ".json"
            (output / path).write_bytes(encoded)
            recipe = "bsa-068-retained-scenario-v1|" + scenario + "|" + hashlib.sha256(encoded).hexdigest()
            procedure = "Retain exact project-authored synthetic scenario declaration " + original.relative_to(ROOT).as_posix()
            options = {"original_sha256": hashlib.sha256(encoded).hexdigest(), "representation": "retained JSON scenario recipe"}
            manifest["fixtures"].append({"id": identifier, "kind": "scenario", "coverage": ["structural", "bsa-068"],
                "creator": "JBSA project contributors", "source": procedure, "spdx_license": "CC0-1.0",
                "generation": {"procedure": procedure, "command": "python build/generate-bsa68-cv1-fixtures.py --output <empty-directory>", "options": options},
                "reference_snapshot_revision": manifest["reference_snapshot_revision"], "oracle_sha256": None,
                "input_sha256": hashlib.sha256(recipe.encode()).hexdigest(),
                "output": {"path": path, "sha256": hashlib.sha256(encoded).hexdigest()},
                "redistribution_class": "project-authored-redistributable"})
            recipes.append({"id": identifier, "input": recipe, "output_path": path, "kind": "scenario", "coverage": ["structural", "bsa-068"], "parameters": options})
        manifest["generated_on"] = "2026-09-11"
        manifest["generator"] = {"id": "jbsa-bsa068-cv1-fixture-generator", "version": "1",
            "implementation": "build/generate-bsa68-cv1-fixtures.py", "implementation_spdx_license": "Apache-2.0",
            "command": "python build/generate-bsa68-cv1-fixtures.py --output <empty-directory>",
            "options": {"output": "<empty-directory>"}}
        (output / "manifest.json").write_bytes(canonical(manifest))
        (output / "generator.json").write_bytes(canonical({"schema_version": 1,
            "generator_id": "jbsa-bsa068-cv1-fixture-generator", "generator_version": "1",
            "algorithm": "independent-common-records-version104-v1", "recipes": recipes,
            "common_generator_runtime": {"sha256": hashlib.sha256(java.read_bytes()).hexdigest(), "version": runtime.stdout.strip()},
            "common_generator_class_sha256": hashlib.sha256((ROOT / "jbsa-test-support/target/classes/io/github/evildarkarchon/jbsa/fixtures/BsaCv1FixtureGenerator.class").read_bytes()).hexdigest(),
            "common_generator_sha256": hashlib.sha256((ROOT / "jbsa-test-support/src/main/java/io/github/evildarkarchon/jbsa/fixtures/BsaCv1FixtureGenerator.java").read_bytes()).hexdigest()}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    generate(parser.parse_args().output)
