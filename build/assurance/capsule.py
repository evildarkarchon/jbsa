"""Create a canonical Assurance v2 Evidence Capsule from one selected session."""

import argparse
import hashlib
import json
from pathlib import Path
import sys
from typing import Any


SESSION_FIELDS = (
    "candidate",
    "runtime",
    "jvm",
    "profile",
    "corpus",
    "protocol",
    "specification",
    "toolchain",
    "platform",
    "provider",
    "generator",
)
OUTCOMES = {"PASS", "FAIL", "INVALID"}


class CapsuleError(ValueError):
    """Report evidence that cannot form a trustworthy session capsule."""


def load_object(path: Path, label: str) -> dict[str, Any]:
    """Load one required JSON object and identify malformed input by role."""
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise CapsuleError(f"cannot read {label}: {error}") from error
    if not isinstance(document, dict):
        raise CapsuleError(f"{label} must contain a JSON object")
    return document


def selected_ids(selection: dict[str, Any]) -> list[str]:
    """Return every selected conformance and performance identity exactly once."""
    identifiers = []
    for collection, key in (
        (selection.get("assurance_scenarios"), "assurance_scenario_id"),
        (selection.get("performance_lanes"), "lane_id"),
    ):
        if not isinstance(collection, list):
            raise CapsuleError("selection has invalid scenario collections")
        for item in collection:
            identifier = item.get(key) if isinstance(item, dict) else None
            if not isinstance(identifier, str) or not identifier:
                raise CapsuleError("selection has an invalid scenario identity")
            identifiers.append(identifier)
    if len(identifiers) != len(set(identifiers)):
        raise CapsuleError("selection contains duplicate scenario identities")
    return sorted(identifiers)


def validate_session(session: dict[str, Any]) -> dict[str, str]:
    """Validate common run identities once and return them in stable field order."""
    missing = [
        field
        for field in SESSION_FIELDS
        if not isinstance(session.get(field), str) or not session[field].strip()
    ]
    if missing:
        raise CapsuleError("session identity is missing: " + ", ".join(missing))
    return {field: session[field] for field in SESSION_FIELDS}


def validate_results(
    document: dict[str, Any], expected_ids: list[str]
) -> list[dict[str, Any]]:
    """Require one valid result with retained evidence for every selected identity."""
    results = document.get("results")
    if not isinstance(results, list):
        raise CapsuleError("results must contain an array")
    validated = []
    identifiers = []
    for result in results:
        identifier = result.get("id") if isinstance(result, dict) else None
        outcome = result.get("outcome") if isinstance(result, dict) else None
        evidence = result.get("evidence") if isinstance(result, dict) else None
        if not isinstance(identifier, str) or not identifier:
            raise CapsuleError("result has an invalid identity")
        if outcome not in OUTCOMES:
            raise CapsuleError(f"result {identifier} has an invalid outcome")
        if not isinstance(evidence, list) or not evidence or not all(
            isinstance(reference, str) and reference for reference in evidence
        ):
            raise CapsuleError(f"result {identifier} has no retained evidence")
        identifiers.append(identifier)
        validated.append(
            {"evidence": sorted(evidence), "id": identifier, "outcome": outcome}
        )
    if len(identifiers) != len(set(identifiers)):
        raise CapsuleError("results contain duplicate scenario identities")
    missing = sorted(set(expected_ids) - set(identifiers))
    foreign = sorted(set(identifiers) - set(expected_ids))
    if missing or foreign:
        details = []
        if missing:
            details.append("missing " + ", ".join(missing))
        if foreign:
            details.append("foreign " + ", ".join(foreign))
        raise CapsuleError("result identities do not match selection: " + "; ".join(details))
    return sorted(validated, key=lambda result: result["id"])


def capsule(
    selection: dict[str, Any], session: dict[str, Any], results: dict[str, Any]
) -> dict[str, Any]:
    """Build one canonical content-addressed capsule from validated inputs."""
    expected_ids = selected_ids(selection)
    validated_results = validate_results(results, expected_ids)
    outcomes = {result["outcome"] for result in validated_results}
    aggregate = "INVALID" if "INVALID" in outcomes else "FAIL" if "FAIL" in outcomes else "PASS"
    document = {
        "outcome": aggregate,
        "environment": selection.get("environment"),
        "changed_paths": selection.get("changed_paths", []),
        "plan_digest": selection.get("plan_digest"),
        "requested_tier": selection.get("requested_tier"),
        "results": validated_results,
        "selected_scenario_ids": expected_ids,
        "selected_tier": selection.get("selected_tier"),
        "selection_reason": selection.get("selection_reason"),
        "session_identity": validate_session(session),
        "version": "assurance-v2-evidence-capsule-v1",
    }
    encoded = json.dumps(
        document, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    document["capsule_digest"] = "sha256:" + hashlib.sha256(encoded).hexdigest()
    return document


def write_json(path: Path, document: dict[str, Any]) -> None:
    """Write deterministic UTF-8 JSON to the requested artifact path."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def main() -> int:
    """Validate the session and publish its Evidence Capsule."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selection", required=True, type=Path)
    parser.add_argument("--session", required=True, type=Path)
    parser.add_argument("--results", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        document = capsule(
            load_object(arguments.selection, "selection"),
            load_object(arguments.session, "session identity"),
            load_object(arguments.results, "results"),
        )
    except CapsuleError as error:
        sys.stderr.write(f"evidence capsule invalid: {error}\n")
        return 2
    write_json(arguments.output, document)
    return 0 if document["outcome"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
