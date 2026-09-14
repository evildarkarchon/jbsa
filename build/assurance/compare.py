"""Compare frozen CV1 cases with compact Assurance v2 scenario archetypes."""

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import sys
from typing import Any

import plan as plan_tool


class ComparisonError(ValueError):
    """Report a malformed input that prevents a trustworthy comparison."""


def load_json(path: Path, label: str) -> dict[str, Any]:
    """Load a JSON object from ``path`` and identify malformed input by role."""
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ComparisonError(f"cannot read {label}: {error}") from error
    if not isinstance(document, dict):
        raise ComparisonError(f"{label} must contain a JSON object")
    return document


def file_digest(path: Path) -> str:
    """Return the exact content identity used to pin the reviewed legacy input."""
    try:
        return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as error:
        raise ComparisonError(f"cannot hash legacy catalog: {error}") from error


def required_string(container: dict[str, Any], key: str, subject: str) -> str:
    """Return one required nonempty string or raise a contextual input error."""
    value = container.get(key)
    if not isinstance(value, str) or not value:
        raise ComparisonError(f"{subject} is missing {key}")
    return value


def validate_legacy(catalog: dict[str, Any]) -> list[dict[str, Any]]:
    """Validate the legacy fields used for scope and archetype classification."""
    if catalog.get("contract") != "conformance-v1":
        raise ComparisonError("legacy catalog contract must be conformance-v1")
    cases = catalog.get("cases")
    if not isinstance(cases, list):
        raise ComparisonError("legacy catalog cases must be an array")

    seen = set()
    for index, case in enumerate(cases):
        if not isinstance(case, dict):
            raise ComparisonError(f"legacy case at index {index} must be an object")
        identity = case.get("identity")
        metadata = case.get("metadata")
        if not isinstance(identity, dict) or not isinstance(metadata, dict):
            raise ComparisonError(f"legacy case at index {index} has invalid identity or metadata")
        case_id = required_string(identity, "case_id", f"legacy case at index {index}")
        if case_id in seen:
            raise ComparisonError(f"duplicate legacy case id: {case_id}")
        seen.add(case_id)
        for field in ("archive_family", "operation", "fixture", "codec"):
            required_string(identity, field, f"legacy case {case_id}")
        required_string(metadata, "expected_behavior", f"legacy case {case_id}")
    return cases


def validate_plan(plan: dict[str, Any]) -> dict[str, Any]:
    """Validate and expand the compact plan using its authoritative generator."""
    if plan.get("version") != "assurance-v2":
        raise ComparisonError("compact plan version must be assurance-v2")
    try:
        plan_tool.validate(plan)
        return plan_tool.expand(plan)
    except (KeyError, TypeError, plan_tool.PlanError) as error:
        raise ComparisonError(f"compact plan is invalid: {error}") from error


def archetype_for(case: dict[str, Any], available_scenarios: set[str]) -> str | None:
    """Map a legacy behavior to one applicable compact scenario when justified."""
    identity = case["identity"]
    metadata = case["metadata"]
    family = identity["archive_family"]
    operation = identity["operation"]
    fixture = identity["fixture"].lower()
    codec = identity["codec"]
    expected = metadata["expected_behavior"]

    scenario_id = None
    if operation == "decode" and expected == "accept":
        scenario_id = "decode-entries"
    elif operation == "decode" and expected == "reject":
        scenario_id = "unsupported-codec-decode"
    elif (
        operation == "decode"
        and expected == "assert-specified-outcome"
        and "malformed-" in fixture
    ):
        scenario_id = "malformed-input"
    elif operation == "encode" and expected == "accept":
        scenario_id = {
            "stored": "stored-mixed-round-trip",
            "zlib": "zlib-round-trip",
            "lz4-frame": "lz4-frame-round-trip",
            "mixed": "stored-mixed-round-trip",
        }.get(codec)
        if scenario_id == "stored-mixed-round-trip":
            family_row = f"{family}:{scenario_id}"
            if family_row not in available_scenarios:
                scenario_id = "stored-round-trip"
    elif operation == "encode" and expected == "reject":
        scenario_id = "unsupported-codec-encode"
    elif (
        operation == "extract"
        and expected == "assert-specified-outcome"
        and "unsafe-name" in fixture
    ):
        scenario_id = "unsafe-extraction"
    elif (
        operation == "scenario"
        and expected == "assert-specified-outcome"
        and "embedded" in fixture
    ):
        scenario_id = "embedded-name-round-trip"
    elif operation == "scenario" and expected == "assert-specified-outcome":
        scenario_id = "behavioral-interactions"

    assurance_scenario_id = f"{family}:{scenario_id}" if scenario_id is not None else None
    return assurance_scenario_id if assurance_scenario_id in available_scenarios else None


def unmapped_case(case: dict[str, Any]) -> dict[str, str]:
    """Describe one scoped legacy case for which no v2 archetype was found."""
    identity = case["identity"]
    return {
        "case_id": identity["case_id"],
        "codec": identity["codec"],
        "expected_behavior": case["metadata"]["expected_behavior"],
        "family": identity["archive_family"],
        "fixture": identity["fixture"],
        "operation": identity["operation"],
        "reason": "no applicable Assurance v2 scenario archetype",
    }


def retired_case(case: dict[str, Any]) -> dict[str, str] | None:
    """Identify a frozen CV1 obligation that the owning format never specified."""
    identity = case["identity"]
    if (
        identity["archive_family"] == "tes3"
        and identity["operation"] == "decode"
        and identity["codec"] in {"zlib", "lz4-frame", "raw-lz4", "raw-deflate", "mixed"}
        and case["metadata"]["expected_behavior"] == "reject"
    ):
        return {
            "case_id": identity["case_id"],
            "family": identity["archive_family"],
            "reason": "TES3 has no decode-time codec selection or compression marker",
        }
    if (
        identity["archive_family"] == "bsa-069"
        and identity["operation"] == "decode"
        and identity["fixture"].lower() == "malformed-harmless-trailing-bytes"
        and case["metadata"]["expected_behavior"] == "assert-specified-outcome"
    ):
        return {
            "case_id": identity["case_id"],
            "family": identity["archive_family"],
            "reason": "versioned BSA has no archive-level trailing-byte requirement",
        }
    return None


def compare(catalog: dict[str, Any], plan: dict[str, Any]) -> dict[str, Any]:
    """Build a conservative shadow report without inferring coverage equivalence."""
    cases = validate_legacy(catalog)
    expanded = validate_plan(plan)
    capabilities = sorted(plan["capabilities"], key=lambda item: item["family"])
    scope_families = [item["family"] for item in capabilities]
    scope = set(scope_families)
    executable_scenario_ids = sorted(
        scenario["assurance_scenario_id"] for scenario in expanded["assurance_scenarios"]
    )
    incomplete_scenario_ids = sorted(scenario["assurance_scenario_id"] for scenario in expanded["incomplete_scenarios"])
    available_scenarios = set(executable_scenario_ids) | set(incomplete_scenario_ids)

    scoped_cases = sorted(
        (case for case in cases if case["identity"]["archive_family"] in scope),
        key=lambda case: case["identity"]["case_id"],
    )
    counts = {family: 0 for family in scope_families}
    mapped_case_ids: dict[str, list[str]] = defaultdict(list)
    mapped_signatures: dict[str, set[tuple[str, str]]] = defaultdict(set)
    retired = []
    unmapped = []
    for case in scoped_cases:
        identity = case["identity"]
        counts[identity["archive_family"]] += 1
        retirement = retired_case(case)
        if retirement is not None:
            retired.append(retirement)
            continue
        assurance_scenario_id = archetype_for(case, available_scenarios)
        if assurance_scenario_id is None:
            unmapped.append(unmapped_case(case))
            continue
        mapped_case_ids[assurance_scenario_id].append(identity["case_id"])
        mapped_signatures[assurance_scenario_id].add(
            (identity["operation"], case["metadata"]["expected_behavior"])
        )

    archetypes = [
        {
            "legacy_behavior_signatures": [
                {"operation": operation, "expected_behavior": expected}
                for operation, expected in sorted(mapped_signatures[assurance_scenario_id])
            ],
            "legacy_case_ids": sorted(mapped_case_ids[assurance_scenario_id]),
            "assurance_scenario_id": assurance_scenario_id,
        }
        for assurance_scenario_id in sorted(mapped_case_ids)
    ]
    unmapped_by_family: dict[str, list[str]] = defaultdict(list)
    for item in unmapped:
        unmapped_by_family[item["family"]].append(item["case_id"])
    retired_by_family: dict[str, list[str]] = defaultdict(list)
    for item in retired:
        retired_by_family[item["family"]].append(item["case_id"])

    gaps = [
        {
            "case_ids": sorted(case_ids),
            "family": family,
            "kind": "unmapped-legacy-cases",
        }
        for family, case_ids in sorted(unmapped_by_family.items())
    ]
    for capability in capabilities:
        if capability["status"] == "incomplete":
            family = capability["family"]
            gaps.append(
                {
                    "family": family,
                    "kind": "incomplete-v2-capability",
                    "scenario_ids": [
                        assurance_scenario_id
                        for assurance_scenario_id in incomplete_scenario_ids
                        if assurance_scenario_id.startswith(f"{capability['id']}:")
                    ],
                }
            )
    gaps.sort(key=lambda item: (item["family"], item["kind"]))

    families = []
    for capability in capabilities:
        family = capability["family"]
        family_unmapped = unmapped_by_family.get(family, [])
        family_retired = retired_by_family.get(family, [])
        mapped_count = sum(
            len(case_ids)
            for assurance_scenario_id, case_ids in mapped_case_ids.items()
            if assurance_scenario_id.startswith(f"{capability['id']}:")
        )
        equivalent = capability["status"] == "qualified" and not family_unmapped
        status = (
            "incomplete"
            if capability["status"] == "incomplete"
            else "gaps-documented"
            if family_unmapped
            else "mapped-equivalent"
        )
        families.append(
            {
                "comparison_status": status,
                "equivalent": equivalent,
                "family": family,
                "accounted_legacy_case_count": mapped_count + len(family_retired),
                "legacy_case_count": counts[family],
                "mapped_legacy_case_count": mapped_count,
                "retired_legacy_case_count": len(family_retired),
                "unmapped_legacy_case_count": len(family_unmapped),
                "v2_executable_scenario_ids": [
                    assurance_scenario_id
                    for assurance_scenario_id in executable_scenario_ids
                    if assurance_scenario_id.startswith(f"{capability['id']}:")
                ],
                "v2_incomplete_scenario_ids": [
                    assurance_scenario_id
                    for assurance_scenario_id in incomplete_scenario_ids
                    if assurance_scenario_id.startswith(f"{capability['id']}:")
                ],
            }
        )

    equivalent = not incomplete_scenario_ids and all(family["equivalent"] for family in families)
    comparison_status = (
        "incomplete"
        if incomplete_scenario_ids
        else "mapped-equivalent"
        if equivalent
        else "gaps-documented"
    )
    return {
        "comparison_status": comparison_status,
        "equivalent": equivalent,
        "families": families,
        "gaps": gaps,
        "legacy_case_counts": counts,
        "legacy_contract": catalog["contract"],
        "mapped_scenario_archetypes": archetypes,
        "plan_version": plan["version"],
        "retired_legacy_cases": sorted(retired, key=lambda item: item["case_id"]),
        "scope_families": scope_families,
        "unmapped_legacy_cases": unmapped,
        "v2_executable_scenario_ids": executable_scenario_ids,
        "v2_incomplete_scenario_ids": incomplete_scenario_ids,
        "version": "assurance-v2-shadow-comparison-v1",
    }


def write_json(path: Path, document: dict[str, Any]) -> None:
    """Write one deterministic canonical JSON report."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def main() -> int:
    """Run the Assurance v2 shadow comparison command-line interface."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--legacy", required=True, type=Path)
    parser.add_argument("--plan", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()

    try:
        catalog = load_json(arguments.legacy, "legacy catalog")
        plan = load_json(arguments.plan, "compact plan")
        validate_legacy(catalog)
        if plan.get("legacy_conformance_digest") != file_digest(arguments.legacy):
            raise ComparisonError("legacy catalog digest does not match reviewed input")
        report = compare(catalog, plan)
    except ComparisonError as error:
        sys.stderr.write(f"assurance comparison invalid: {error}\n")
        return 2
    write_json(arguments.output, report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
