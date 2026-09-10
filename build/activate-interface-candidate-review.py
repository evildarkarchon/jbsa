"""Activate a reviewed Interface Candidate packet only with explicit approval arguments."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent.parent
PACKET = ROOT / "docs/reviews/issue41-interface/conformance-rebinding"


def activate():
    """Validate exact reviewed bytes, record supplied approval, then activate the catalog.

    Requires explicit maintainer authorization before invocation. Failed validation
    leaves the active catalog untouched; review records alone cannot activate cases.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--approver", required=True)
    parser.add_argument("--review-sha256", required=True)
    args = parser.parse_args()
    if not args.approver.strip():
        parser.error("--approver must identify the approving maintainer")
    review_bytes = (PACKET / "review.json").read_bytes()
    if hashlib.sha256(review_bytes).hexdigest() != args.review_sha256:
        raise ValueError("Reviewed packet digest differs from explicit approval")
    review = json.loads(review_bytes)
    for item in [review["active_catalog"], review["proposed_catalog"], review["pending_records"], review["generator"], review["activation_tool"], review["baseline_catalog"], *review["proposed_specification_set"]]:
        if hashlib.sha256((ROOT / item["path"]).read_bytes()).hexdigest() != item["sha256"]:
            raise ValueError(f"Reviewed input changed: {item['path']}")
    records = json.loads((PACKET / "pending-records.json").read_bytes())
    destination = (ROOT / "tests/conformance/rebaselines").resolve()
    names = set()
    for record in records:
        name = record.get("golden_id")
        if not isinstance(name, str) or re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name) is None or name in names:
            raise ValueError("Invalid or duplicate reviewed golden identifier")
        names.add(name)
        if (destination / (name + ".json")).resolve().parent != destination:
            raise ValueError("Review record destination escapes the rebaseline directory")
    approved = []
    for record in records:
        record["status"] = "approved"
        record["approval"] = {"approver": args.approver, "approved_at": datetime.now(timezone.utc).isoformat(), "decision": "approved"}
        approved.append((record["golden_id"] + ".json", json.dumps(record, ensure_ascii=False, separators=(",", ":")).encode("utf-8")))
    # A fresh directory prevents unrelated or stale approvals from joining this reviewed packet.
    with tempfile.TemporaryDirectory(prefix="jbsa-interface-approval-") as temporary:
        directory = Path(temporary)
        for filename, content in approved:
            (directory / filename).write_bytes(content)
        subprocess.run(["pwsh", "-NoProfile", "-File", str(ROOT / "build/verify-conformance-rebaseline.ps1"),
            "-BaselineCatalog", str(PACKET / "baseline-catalog.json"), "-CandidateCatalog", str(PACKET / "catalog.json"),
            "-RecordsDirectory", str(directory)], cwd=ROOT, check=True)
        for filename, content in approved:
            (destination / filename).write_bytes(content)
    # Catalog publication is last: incomplete review records cannot admit new golden evidence.
    (ROOT / "tests/conformance/catalog.json").write_bytes((PACKET / "catalog.json").read_bytes())
    print("Activated reviewed catalog; run the full verification suite to produce current qualification results.")


if __name__ == "__main__":
    activate()
