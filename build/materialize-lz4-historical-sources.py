"""Reconstruct ignored synthetic binaries for the two frozen issue43 packets."""

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKETS = (
    "specification-rebinding/corpora/ca0190664f835abf",
    "profile-rebinding/corpora/eae93a2c9aca5b04",
)


def materialize():
    """Restore only SHA-verified original synthetic bytes at fixed packet locations.

    This helper does not alter manifests, goldens or active case identities. The
    active catalog uses tracked source locations and never needs these duplicates.
    """
    source = ROOT / "tests/fixtures/synthetic/manifest.json"
    original = json.loads(source.read_bytes())
    by_digest = {f["output"]["sha256"]: source.parent / f["output"]["path"]
                 for f in original["fixtures"]}
    count = 0
    for packet in PACKETS:
        directory = ROOT / "docs/reviews/issue43-lz4-runtime" / packet
        historical = json.loads((directory / "manifest.json").read_bytes())
        for fixture in historical["fixtures"]:
            output = fixture["output"]
            target = (directory / output["path"]).resolve()
            if target.parent != (directory / "artifacts").resolve():
                raise ValueError("Historical output escapes the fixed artifact directory")
            origin = by_digest[output["sha256"]].resolve()
            if not origin.is_relative_to(source.parent.resolve()):
                raise ValueError("Original fixture escapes the tracked synthetic corpus")
            raw = origin.read_bytes()
            if hashlib.sha256(raw).hexdigest() != output["sha256"]:
                raise ValueError("Tracked synthetic source digest mismatch")
            target.parent.mkdir(exist_ok=True)
            target.write_bytes(raw)
            count += 1
    print(f"Materialized {count} exact historical synthetic duplicates.")


if __name__ == "__main__":
    materialize()
