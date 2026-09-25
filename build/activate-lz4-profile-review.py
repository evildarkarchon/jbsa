"""Activate the explicitly approved profile-identity packet through the existing rebaseline gate."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
PACKET = ROOT / "docs/reviews/issue43-lz4-runtime/profile-rebinding"


def verify_bindings(value):
    """Verify every nested file binding, including the independent profile authorities and helpers."""
    if isinstance(value, dict):
        if isinstance(value.get("path"), str) and isinstance(value.get("sha256"), str):
            path = (ROOT / value["path"]).resolve()
            if not path.is_relative_to(ROOT) or hashlib.sha256(path.read_bytes()).hexdigest() != value["sha256"]:
                raise ValueError(f"Reviewed input changed: {value['path']}")
        for child in value.values():
            verify_bindings(child)
    elif isinstance(value, list):
        for child in value:
            verify_bindings(child)


def activate(approver, review_sha256):
    """Validate reviewed expectations and record supplied approval before publishing the catalog.

    Requires explicit maintainer approval. Supplemental JSON paths remain in the
    immutable reviewed packet; only schema-defined fields enter approval records.
    Any failed validation leaves the active catalog and approval store unchanged.
    """
    if not approver.strip():
        raise ValueError("An approving maintainer is required")
    raw = (PACKET / "review.json").read_bytes()
    if hashlib.sha256(raw).hexdigest() != review_sha256:
        raise ValueError("Packet digest differs from explicit approval")
    review = json.loads(raw)
    verify_bindings(review)
    records = json.loads((ROOT / review["pending_records"]["path"]).read_bytes())
    proposals = {item["proposed"]["sha256"]: item for item in review["proposals"]}
    destination = ROOT / "tests/conformance/rebaselines"
    approved = {}
    for pending in records:
        record = dict(pending)
        proposal = proposals[record["new_sha256"]]
        # The strict approval schema excludes annotations; their exact values stay digest-bound above.
        paths = record.pop("changed_json_paths")
        if paths != proposal["changed_json_paths"]:
            raise ValueError("Supplemental paths differ from the approved proposal")
        name = record["golden_id"]
        if re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name) is None or name in approved:
            raise ValueError("Invalid or duplicate golden identifier")
        if (destination / (name + ".json")).exists():
            raise ValueError("An immutable approval already exists for this successor")
        record["status"] = "approved"
        record["approval"] = {"approver": approver, "approved_at": datetime.now(timezone.utc).isoformat(),
                              "decision": "approved"}
        approved[name] = json.dumps(record, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(approved) != len(proposals):
        raise ValueError("Approval records do not cover the complete packet")
    with tempfile.TemporaryDirectory(prefix="jbsa-lz4-approval-") as temporary:
        directory = Path(temporary)
        for name, content in approved.items():
            (directory / (name + ".json")).write_bytes(content)
        subprocess.run(["pwsh", "-NoProfile", "-File", str(ROOT / "build/verify-conformance-rebaseline.ps1"),
                        "-BaselineCatalog", str(ROOT / review["baseline_catalog"]["path"]),
                        "-CandidateCatalog", str(ROOT / review["proposed_catalog"]["path"]),
                        "-RecordsDirectory", str(directory)], cwd=ROOT, check=True)
        for name, content in approved.items():
            (destination / (name + ".json")).write_bytes(content)
    # Publication follows approval validation, so preparation cannot admit expected outputs.
    (ROOT / "tests/conformance/catalog.json").write_bytes((ROOT / review["proposed_catalog"]["path"]).read_bytes())
    print(f"Activated {len(approved)} approved profile-identity successors; fresh conformance is required.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--approver", required=True)
    parser.add_argument("--review-sha256", required=True)
    arguments = parser.parse_args()
    activate(arguments.approver, arguments.review_sha256)
