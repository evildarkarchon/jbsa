"""Validate and deterministically expand a compact Assurance v2 plan."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any


ROOT = Path(__file__).resolve().parents[2]


class PlanError(ValueError):
    """Report a deterministic Assurance v2 plan validation failure."""


def load_plan(path: Path) -> dict[str, Any]:
    """Load an Assurance v2 JSON plan from ``path``."""
    return json.loads(path.read_text(encoding="utf-8"))


def validate_unique_ids(items: list[dict[str, Any]], kind: str) -> None:
    """Require identifiers to be unique within one plan collection."""
    seen = set()
    for item in items:
        identifier = item["id"]
        if identifier in seen:
            raise PlanError(f"duplicate {kind} id: {identifier}")
        seen.add(identifier)


def validate_stable_ids(
    items: list[dict[str, Any]], kind: str, pattern: re.Pattern[str]
) -> None:
    """Require identifiers to use the stable spelling for their collection."""
    validate_unique_ids(items, kind)
    for item in items:
        if not isinstance(item.get("id"), str) or pattern.fullmatch(item["id"]) is None:
            raise PlanError(f'unstable {kind} id: {item.get("id")}')


def validate_evidence_references(references: Any) -> None:
    """Require nonempty repository-relative evidence references to existing files."""
    if not isinstance(references, list) or not references:
        raise PlanError("candidate scenario has no evidence references")
    for reference in references:
        candidate = (ROOT / reference).resolve() if isinstance(reference, str) else None
        if (
            candidate is None
            or not candidate.is_relative_to(ROOT)
            or not candidate.is_file()
        ):
            raise PlanError(f"referenced evidence file does not exist: {reference}")


def validate_test_selectors(selectors: list[str]) -> None:
    """Require each JUnit class or method selector to resolve to test source."""
    for selector in selectors:
        class_name, separator, method_name = selector.partition("#")
        relative_source = Path(*class_name.split(".")).with_suffix(".java")
        candidates = list(ROOT.glob(f"*/src/test/java/{relative_source.as_posix()}"))
        exists = bool(candidates)
        if exists and separator:
            method_pattern = re.compile(rf"\b{re.escape(method_name)}\s*\(")
            exists = any(
                method_pattern.search(path.read_text(encoding="utf-8")) is not None
                for path in candidates
            )
        if not exists:
            raise PlanError(f"referenced test selector does not exist: {selector}")


def validate(plan: dict[str, Any]) -> None:
    """Validate constraints that keep the compact plan executable and stable."""
    stable_id = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
    requirement_id = re.compile(r"JBSA-[A-Z0-9]+-[0-9]{3}")
    validate_stable_ids(plan["capabilities"], "capability", stable_id)
    validate_stable_ids(plan["scenarios"], "scenario", stable_id)
    validate_stable_ids(plan["requirements"], "requirement", requirement_id)
    validate_stable_ids(plan["performance"]["lanes"], "performance lane", stable_id)
    lane_count = len(plan["performance"]["lanes"])
    if lane_count < 20 or lane_count > 30:
        raise PlanError("performance plan must contain 20 to 30 lanes")
    scaling_lanes = [
        lane for lane in plan["performance"]["lanes"] if lane.get("surface") == "scaling"
    ]
    if len(scaling_lanes) != 1:
        raise PlanError("performance plan must have one scaling lane")
    workers = scaling_lanes[0].get("workers")
    if (
        not isinstance(workers, list)
        or len(workers) < 2
        or any(not isinstance(worker, int) or worker < 1 for worker in workers)
        or workers != sorted(set(workers))
    ):
        raise PlanError(
            f'scaling lane must define a workers vector: {scaling_lanes[0]["id"]}'
        )
    output_size_lanes = [
        lane
        for lane in plan["performance"]["lanes"]
        if "output-size" in lane.get("metrics", [])
    ]
    if (
        not output_size_lanes
        or any(lane.get("surface") == "output-size" for lane in plan["performance"]["lanes"])
        or any(lane.get("surface") != "throughput" for lane in output_size_lanes)
    ):
        raise PlanError("output-size must be a metric on a throughput lane")
    known_requirements = {item["id"] for item in plan["requirements"]}
    referenced_requirements = set()
    for scenario in plan["scenarios"]:
        if not isinstance(scenario.get("expected"), str) or not scenario["expected"].strip():
            raise PlanError(f'scenario has no semantic expectation: {scenario["id"]}')
        environments = scenario.get("environments", ["hosted", "local", "release"])
        if (
            not isinstance(environments, list)
            or not environments
            or not set(environments) <= {"hosted", "local", "release"}
        ):
            raise PlanError(f'scenario has invalid environments: {scenario["id"]}')
        selectors = scenario.get("applies_to")
        supported = {"families", "risk_tags"}
        if (
            not isinstance(selectors, dict)
            or set(selectors) - supported
            or not any(
                isinstance(selectors.get(key), list) and selectors[key]
                for key in supported
            )
        ):
            raise PlanError(
                f'scenario has no supported applies_to selector: {scenario["id"]}'
            )
        references = list(scenario.get("requirements", []))
        references.extend(
            reference
            for capability_references in scenario.get(
                "capability_requirements", {}
            ).values()
            for reference in capability_references
        )
        for reference in references:
            if reference not in known_requirements:
                raise PlanError(
                    f'scenario {scenario["id"]} references unknown requirement: {reference}'
                )
            referenced_requirements.add(reference)
    unreferenced = known_requirements - referenced_requirements
    if unreferenced:
        raise PlanError(f"requirements have no scenarios: {', '.join(sorted(unreferenced))}")
    allowed_statuses = {"qualified", "incomplete", "planned"}
    for capability in plan["capabilities"]:
        if (
            not isinstance(capability.get("behavioral_variant"), str)
            or stable_id.fullmatch(capability["behavioral_variant"]) is None
        ):
            raise PlanError(
                f'capability has no stable behavioral variant: {capability["id"]}'
            )
        if capability["status"] not in allowed_statuses:
            raise PlanError(f'unsupported capability status: {capability["status"]}')
        if capability["status"] == "qualified":
            matching = [
                scenario
                for scenario in plan["scenarios"]
                if scenario_applies(scenario, capability)
            ]
            if not matching:
                raise PlanError(f'qualified capability has no scenario: {capability["id"]}')
            for scenario in matching:
                selectors = scenario.get("test_selectors", {}).get(capability["id"], [])
                if not selectors or not all(isinstance(item, str) and item for item in selectors):
                    raise PlanError(
                        "qualified scenario has no test selectors: "
                        f'{capability["id"]}:{scenario["id"]}'
                    )
                validate_test_selectors(selectors)
                references = scenario.get("evidence_refs", {}).get(capability["id"])
                if references is not None:
                    validate_evidence_references(references)
        elif capability["status"] == "incomplete":
            for scenario in plan["scenarios"]:
                if not scenario_applies(scenario, capability):
                    continue
                selectors = scenario.get("test_selectors", {}).get(capability["id"], [])
                if not selectors or not all(isinstance(item, str) and item for item in selectors):
                    raise PlanError(
                        "incomplete scenario has no test selectors: "
                        f'{capability["id"]}:{scenario["id"]}'
                    )
                validate_test_selectors(selectors)
                references = scenario.get("evidence_refs", {}).get(capability["id"], [])
                validate_evidence_references(references)
    known_capability_ids = {capability["id"] for capability in plan["capabilities"]}
    for lane in plan["performance"]["lanes"]:
        if not isinstance(lane.get("implementation_path"), str) or not lane[
            "implementation_path"
        ]:
            raise PlanError(f'performance lane has no implementation path: {lane["id"]}')
        if not isinstance(lane.get("risks"), list) or not lane["risks"]:
            raise PlanError(f'performance lane has no risks: {lane["id"]}')
        if not isinstance(lane.get("release_gate"), bool):
            raise PlanError(f'performance lane has no release gate decision: {lane["id"]}')
        lane_status = lane.get("status")
        if lane.get("qualification_scope") == "development-checkpoint":
            if lane.get("release_gate") is not False:
                raise PlanError(
                    f'development checkpoint cannot gate release: {lane["id"]}'
                )
        if lane_status in {"qualified", "incomplete"}:
            lane_selectors = lane.get("test_selectors", [])
            if not lane_selectors:
                raise PlanError(
                    f'{lane_status} performance lane has no test selectors: {lane["id"]}'
                )
            validate_test_selectors(lane_selectors)
            validate_evidence_references(lane.get("evidence_refs"))
        singular_capability = lane.get("capability_id")
        aggregate_capabilities = lane.get("capability_ids")
        if singular_capability is not None and aggregate_capabilities is not None:
            raise PlanError(f'performance lane has conflicting capability selectors: {lane["id"]}')
        if singular_capability is not None and singular_capability not in known_capability_ids:
            raise PlanError(f'performance lane has unknown capability: {lane["id"]}')
        if aggregate_capabilities is not None and (
            not isinstance(aggregate_capabilities, list)
            or not aggregate_capabilities
            or not set(aggregate_capabilities) <= known_capability_ids
        ):
            raise PlanError(f'performance lane has unknown capabilities: {lane["id"]}')

    impact_rules = plan.get("impact_rules")
    if not isinstance(impact_rules, list) or not impact_rules:
        raise PlanError("plan has no impact rules")
    for rule in impact_rules:
        patterns = rule.get("paths") if isinstance(rule, dict) else None
        selected_capabilities = rule.get("capabilities") if isinstance(rule, dict) else None
        if not isinstance(patterns, list) or not patterns or not all(
            isinstance(pattern, str) and pattern for pattern in patterns
        ):
            raise PlanError("impact rule has no path patterns")
        if selected_capabilities == "full":
            continue
        if (
            not isinstance(selected_capabilities, list)
            or not selected_capabilities
            or not set(selected_capabilities) <= known_capability_ids
        ):
            raise PlanError("impact rule has unknown capabilities")


def scenario_applies(scenario: dict[str, Any], capability: dict[str, Any]) -> bool:
    """Return whether a scenario selector includes a capability."""
    selectors = scenario["applies_to"]
    family_match = capability["family"] in selectors.get("families", [])
    risk_match = bool(set(capability.get("risk_tags", [])) & set(selectors.get("risk_tags", [])))
    return family_match or risk_match


def performance_lane_applies(lane: dict[str, Any], capability_ids: set[str] | None) -> bool:
    """Return whether a singular or aggregate checkpoint intersects the selected capabilities."""
    if capability_ids is None:
        return True
    selected = set(lane.get("capability_ids", []))
    if lane.get("capability_id") is not None:
        selected.add(lane["capability_id"])
    return bool(selected & capability_ids)


def scenario_requirements(
    scenario: dict[str, Any], capability_id: str | None = None
) -> list[str]:
    """Return shared and capability-specific active requirements for a scenario."""
    requirements = set(scenario.get("requirements", []))
    if capability_id is not None:
        requirements.update(
            scenario.get("capability_requirements", {}).get(capability_id, [])
        )
    return sorted(requirements)


def semantic_expectation_id(
    scenario: dict[str, Any], capability: dict[str, Any]
) -> str:
    """Return a content identity derived only from stable asserted semantics."""
    semantic = {
        "behavioral_variant": capability["behavioral_variant"],
        "expected": scenario["expected"],
        "scenario_id": scenario["id"],
    }
    encoded = json.dumps(
        semantic, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def plan_digest(plan: dict[str, Any]) -> str:
    """Return the canonical plan identity while excluding volatile run metadata."""
    stable_plan = {key: value for key, value in plan.items() if key != "run_identity"}
    encoded = json.dumps(
        stable_plan, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def path_matches(path: str, pattern: str) -> bool:
    """Match repository paths with slash-aware ``*`` and recursive ``**`` globs."""
    expression = re.escape(pattern)
    expression = expression.replace(r"\*\*", "\0")
    expression = expression.replace(r"\*", "[^/]*")
    expression = expression.replace("\0", ".*")
    return re.fullmatch(expression, path) is not None


def expand(plan: dict[str, Any]) -> dict[str, Any]:
    """Expand qualified capabilities and preserve compact performance lanes."""
    generated_scenarios = []
    incomplete_scenarios = []
    for capability in plan["capabilities"]:
        for scenario in plan["scenarios"]:
            if not scenario_applies(scenario, capability):
                continue
            if capability["status"] == "qualified":
                generated_scenario = {
                    "assurance_scenario_id": f'{capability["id"]}:{scenario["id"]}',
                    "capability_id": capability["id"],
                    "family": capability["family"],
                    "environments": sorted(
                        scenario.get("environments", ["hosted", "local", "release"])
                    ),
                    "scenario_id": scenario["id"],
                    "semantic_expectation_id": semantic_expectation_id(
                        scenario, capability
                    ),
                    "requirement_ids": scenario_requirements(
                        scenario, capability["id"]
                    ),
                    "test_selectors": sorted(scenario["test_selectors"][capability["id"]]),
                }
                references = scenario.get("evidence_refs", {}).get(capability["id"])
                if references is not None:
                    generated_scenario["evidence_refs"] = sorted(references)
                generated_scenarios.append(generated_scenario)
            elif capability["status"] == "incomplete":
                incomplete_scenarios.append(
                    {
                        "assurance_scenario_id": f'{capability["id"]}:{scenario["id"]}',
                        "capability_id": capability["id"],
                        "family": capability["family"],
                        "environments": sorted(
                            scenario.get("environments", ["hosted", "local", "release"])
                        ),
                        "status": capability["status"],
                        "scenario_id": scenario["id"],
                        "semantic_expectation_id": semantic_expectation_id(
                            scenario, capability
                        ),
                        "requirement_ids": scenario_requirements(
                            scenario, capability["id"]
                        ),
                        "test_selectors": sorted(
                            scenario["test_selectors"][capability["id"]]
                        ),
                        "evidence_refs": sorted(
                            scenario["evidence_refs"][capability["id"]]
                        ),
                    }
                )

    traceability = {}
    for requirement in plan["requirements"]:
        requirement_scenarios = [
            scenario["id"]
            for scenario in plan["scenarios"]
            if requirement["id"] in scenario_requirements(scenario)
            or any(
                requirement["id"] in references
                for references in scenario.get("capability_requirements", {}).values()
            )
        ]
        traceability[requirement["id"]] = sorted(requirement_scenarios)

    lanes = []
    for lane in plan["performance"]["lanes"]:
        expanded_lane = {"lane_id": lane["id"]}
        expanded_lane.update({key: value for key, value in lane.items() if key != "id"})
        lanes.append(expanded_lane)

    # Stable ordering makes generated evidence independent of plan entry order.
    return {
        "assurance_scenarios": sorted(
            generated_scenarios,
            key=lambda scenario: scenario["assurance_scenario_id"],
        ),
        "incomplete_scenarios": sorted(incomplete_scenarios, key=lambda scenario: scenario["assurance_scenario_id"]),
        "performance_lanes": sorted(lanes, key=lambda lane: lane["lane_id"]),
        "traceability": traceability,
        "plan_digest": plan_digest(plan),
    }


def select(
    plan: dict[str, Any],
    requested_tier: str,
    changed_paths: list[str],
    environment: str = "hosted",
) -> dict[str, Any]:
    """Select the applicable generated scenarios, promoting unknown impact to full."""
    expanded = expand(plan)
    selected_tier = requested_tier
    selection_reason = requested_tier
    selected_capabilities: set[str] | None = None
    if requested_tier == "affected":
        selected_capabilities = set()
        for changed_path in changed_paths:
            normalized = changed_path.replace("\\", "/")
            matching_rules = [
                rule
                for rule in plan["impact_rules"]
                if any(path_matches(normalized, pattern) for pattern in rule["paths"])
            ]
            if not matching_rules or any(rule["capabilities"] == "full" for rule in matching_rules):
                selected_tier = "full"
                selection_reason = "unknown-impact" if not matching_rules else "shared-core-impact"
                selected_capabilities = None
                break
            for rule in matching_rules:
                selected_capabilities.update(rule["capabilities"])

        if not changed_paths:
            selected_tier = "full"
            selection_reason = "unknown-impact"
            selected_capabilities = None

    scenarios = [
        scenario for scenario in expanded["assurance_scenarios"] if environment in scenario["environments"]
    ]
    if selected_capabilities is not None:
        scenarios = [scenario for scenario in scenarios if scenario["capability_id"] in selected_capabilities]

    if selected_tier == "release":
        lanes = [
            lane
            for lane in expanded["performance_lanes"]
            if lane.get("release_gate") is True
            and environment in lane.get("environments", ["hosted", "local", "release"])
        ]
    else:
        lanes = [
            lane
            for lane in expanded["performance_lanes"]
            if lane.get("status") == "qualified"
            and lane.get("release_gate") is False
            and environment in lane.get("environments", ["hosted", "local", "release"])
            and (
                performance_lane_applies(lane, selected_capabilities)
            )
        ]
    return {
        "assurance_scenarios": scenarios,
        "performance_lanes": lanes,
        "plan_digest": expanded["plan_digest"],
        "requested_tier": requested_tier,
        "selected_tier": selected_tier,
        "selection_reason": selection_reason,
        "environment": environment,
        "changed_paths": sorted(path.replace("\\", "/") for path in changed_paths),
    }


def validation_receipt(plan: dict[str, Any]) -> dict[str, Any]:
    """Summarize the surfaces accepted by Assurance v2 validation."""
    return {
        "capability_count": len(plan["capabilities"]),
        "performance_lane_count": len(plan["performance"]["lanes"]),
        "scenario_count": len(plan["scenarios"]),
        "status": "valid",
        "version": plan["version"],
    }


def write_json(path: Path, document: dict[str, Any]) -> None:
    """Write deterministic UTF-8 JSON to ``path``."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def load_changed_paths(path: Path) -> list[str]:
    """Read a CI-supplied JSON path array, rejecting missing or malformed selection input."""
    try:
        paths = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise PlanError(f"invalid changed-paths file: {error}") from error
    if not isinstance(paths, list) or not all(isinstance(item, str) and item for item in paths):
        raise PlanError("changed-paths file must be a JSON array of nonempty strings")
    return paths


def main() -> int:
    """Run the Assurance v2 command-line interface."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("expand", "select", "validate"))
    parser.add_argument("plan", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--tier", choices=("affected", "full", "release"))
    parser.add_argument("--changed", action="append", default=[])
    parser.add_argument("--changed-file", type=Path)
    parser.add_argument("--environment", choices=("hosted", "local", "release"), default="hosted")
    arguments = parser.parse_args()

    plan = load_plan(arguments.plan)
    try:
        validate(plan)
    except PlanError as error:
        sys.stderr.write(f"assurance plan invalid: {error}\n")
        return 2
    if arguments.command == "expand":
        document = expand(plan)
    elif arguments.command == "select":
        if arguments.tier is None:
            parser.error("select requires --tier")
        changed_paths = list(arguments.changed)
        if arguments.changed_file is not None:
            try:
                changed_paths.extend(load_changed_paths(arguments.changed_file))
            except PlanError as error:
                sys.stderr.write(f"assurance selection invalid: {error}\n")
                return 2
        document = select(plan, arguments.tier, changed_paths, arguments.environment)
    else:
        document = validation_receipt(plan)
    write_json(arguments.output, document)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
