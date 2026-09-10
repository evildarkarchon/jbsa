"""Reconstruct ignored duplicate binaries required to inspect the historical approved packet."""

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def materialize():
    """Copy only exact hash-verified synthetic bytes to the frozen historical locations."""
    source = ROOT / "tests/fixtures/synthetic/manifest.json"
    original = json.loads(source.read_bytes())
    directory = ROOT / "docs/reviews/issue41-interface/conformance-rebinding/corpora/88727fae349b7e58"
    historical = json.loads((directory / "manifest.json").read_bytes())
    by_digest = {f["output"]["sha256"]: source.parent / f["output"]["path"] for f in original["fixtures"]}
    for fixture in historical["fixtures"]:
        output = fixture["output"]
        target = (directory / output["path"]).resolve()
        if target.parent != (directory / "artifacts").resolve():
            raise ValueError("Historical output escapes the fixed artifact directory")
        raw = by_digest[output["sha256"]].read_bytes()
        if hashlib.sha256(raw).hexdigest() != output["sha256"]:
            raise ValueError("Tracked synthetic source digest mismatch")
        target.parent.mkdir(exist_ok=True)
        target.write_bytes(raw)
    print("Materialized historical synthetic duplicates; active catalog uses original tracked sources.")


if __name__ == "__main__":
    materialize()
