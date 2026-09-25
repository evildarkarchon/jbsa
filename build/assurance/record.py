"""Record tested Assurance v2 selections as one session Evidence Capsule."""

import argparse
import hashlib
from pathlib import Path
import platform
import re
import subprocess
import sys
from typing import Any
import xml.etree.ElementTree as ElementTree

import capsule as capsule_tool
import plan as plan_tool


def digest_files(repository: Path, paths: list[Path]) -> str:
    """Return a canonical digest for named repository files and directory trees."""
    files: set[Path] = set()
    for path in paths:
        if path.is_file():
            files.add(path)
        elif path.is_dir():
            files.update(candidate for candidate in path.rglob("*") if candidate.is_file())
    digest = hashlib.sha256()
    for path in sorted(files, key=lambda item: item.relative_to(repository).as_posix()):
        relative = path.relative_to(repository).as_posix()
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).hexdigest().encode("ascii"))
        digest.update(b"\n")
    return "sha256:" + digest.hexdigest()


def candidate_digest(repository: Path) -> str:
    """Identify the exact tracked and untracked, non-ignored source workspace."""
    base = subprocess.run(
        ["git", "rev-parse", "HEAD^{tree}"],
        cwd=repository,
        capture_output=True,
        check=False,
    )
    changes = subprocess.run(
        ["git", "diff", "--binary", "--no-ext-diff", "HEAD", "--", ".", ":(exclude)TES5Edit"],
        cwd=repository,
        capture_output=True,
        check=False,
    )
    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard", "-z"],
        cwd=repository,
        capture_output=True,
        check=False,
    )
    if any(result.returncode != 0 for result in (base, changes, untracked)):
        raise capsule_tool.CapsuleError("cannot enumerate candidate source identity")
    digest = hashlib.sha256()
    digest.update(base.stdout.strip())
    digest.update(b"\0")
    digest.update(changes.stdout)
    for entry in sorted(item for item in untracked.stdout.split(b"\0") if item):
        path = repository / entry.decode("utf-8")
        if not path.is_file():
            continue
        digest.update(entry)
        digest.update(b"\0")
        digest.update(hashlib.sha256(path.read_bytes()).hexdigest().encode("ascii"))
        digest.update(b"\n")
    return "sha256:" + digest.hexdigest()


def command_identity(command: list[str], repository: Path) -> str:
    """Return one normalized first-line identity for a required runtime command."""
    result = subprocess.run(
        command,
        cwd=repository,
        capture_output=True,
        text=True,
        check=False,
    )
    output = (result.stdout + "\n" + result.stderr).strip().splitlines()
    if result.returncode != 0 or not output:
        raise capsule_tool.CapsuleError(f"cannot identify required command: {command[0]}")
    return output[0].strip()


def specification_identity(repository: Path) -> str:
    """Bind the declared version and exact contents of the normative specification set."""
    specification_root = repository / "docs/spec"
    registry = (specification_root / "requirements.yaml").read_text(encoding="utf-8")
    match = re.search(r"^\s+version:\s+(\S+)\s*$", registry, re.MULTILINE)
    if match is None:
        raise capsule_tool.CapsuleError("cannot identify specification version")
    # The superseded v1 contracts explicitly identify themselves as non-normative history.
    historical = {
        specification_root / "conformance-v1.md",
        specification_root / "performance-v1.md",
    }
    normative = [
        path
        for path in specification_root.rglob("*")
        if path.is_file() and path not in historical
    ]
    return f"{match.group(1)}@{digest_files(repository, normative)}"


def session_identity(repository: Path, plan_path: Path, java_executable: str) -> dict[str, str]:
    """Bind common candidate, runtime, profile, corpus, and protocol identities once."""
    metadata = repository / "jbsa/src/main/resources/META-INF"
    fixture_root = repository / "tests/fixtures"
    fixture_inputs = [
        path for path in fixture_root.iterdir() if path.name != "local"
    ]
    java_identity = command_identity([java_executable, "-version"], repository)
    return {
        "candidate": candidate_digest(repository),
        "runtime": java_identity,
        "jvm": java_identity,
        "profile": digest_files(repository, list(metadata.glob("*profile*.json"))),
        "corpus": digest_files(
            repository,
            [*fixture_inputs, repository / "tests/performance/corpus"],
        ),
        "protocol": digest_files(repository, [repository / "tests/performance/protocol.json"]),
        "specification": specification_identity(repository),
        "toolchain": digest_files(
            repository,
            [
                repository / "gradle/wrapper/gradle-wrapper.properties",
                repository / "gradle/wrapper/gradle-wrapper.jar",
            ],
        ),
        "platform": platform.platform(),
        "provider": digest_files(repository, list(metadata.glob("*codec*.json"))),
        "generator": digest_files(
            repository,
            [*sorted((repository / "build/assurance").glob("*.py")), plan_path],
        ),
    }


def load_testcases(report_roots: list[Path]) -> list[tuple[ElementTree.Element, Path]]:
    """Load every JUnit testcase and retain the report that proves its outcome."""
    testcases = []
    for root in report_roots:
        for report in sorted(root.rglob("TEST-*.xml")):
            try:
                document = ElementTree.parse(report)
            except (OSError, ElementTree.ParseError) as error:
                raise capsule_tool.CapsuleError(f"cannot read JUnit report {report}: {error}") from error
            testcases.extend((testcase, report) for testcase in document.iter("testcase"))
    return testcases


def selector_result(
    selector: str,
    testcases: list[tuple[ElementTree.Element, Path]],
    repository: Path,
) -> tuple[str, list[str]]:
    """Derive one selector outcome from its real JUnit executions."""
    class_name, separator, method_name = selector.partition("#")
    if not separator:
        raise capsule_tool.CapsuleError(f"selector has no method identity: {selector}")
    matches = [
        (testcase, report)
        for testcase, report in testcases
        if testcase.get("classname") == class_name
        and (
            testcase.get("name") == method_name
            or (testcase.get("name") or "").startswith(method_name + "(")
            or (testcase.get("name") or "").startswith(method_name + "[")
        )
    ]
    if not matches:
        return "INVALID", ["missing-junit-result:" + selector]
    outcome = "PASS"
    for testcase, _ in matches:
        if testcase.find("error") is not None or testcase.find("skipped") is not None:
            outcome = "INVALID"
            break
        if testcase.find("failure") is not None:
            outcome = "FAIL"
    evidence = sorted(
        {
            report.relative_to(repository).as_posix()
            if report.is_relative_to(repository)
            else str(report)
            for _, report in matches
        }
    )
    return outcome, evidence


def results_from_reports(
    selection: dict[str, Any], report_roots: list[Path], repository: Path
) -> dict[str, list[dict[str, Any]]]:
    """Require every selector in every selected scenario to have real passing evidence."""
    testcases = load_testcases(report_roots)
    results = []
    for item, identity_key in (
        *((scenario, "assurance_scenario_id") for scenario in selection["assurance_scenarios"]),
        *((lane, "lane_id") for lane in selection["performance_lanes"]),
    ):
        selectors = item.get("test_selectors", [])
        if not selectors:
            results.append(
                {
                    "evidence": ["missing-test-selectors:" + item[identity_key]],
                    "id": item[identity_key],
                    "outcome": "INVALID",
                }
            )
            continue
        selector_results = [
            selector_result(selector, testcases, repository) for selector in selectors
        ]
        outcomes = {outcome for outcome, _ in selector_results}
        outcome = "INVALID" if "INVALID" in outcomes else "FAIL" if "FAIL" in outcomes else "PASS"
        results.append(
            {
                "evidence": sorted(
                    {reference for _, evidence in selector_results for reference in evidence}
                ),
                "id": item[identity_key],
                "outcome": outcome,
            }
        )
    return {"results": results}


def record(
    repository: Path,
    plan_path: Path,
    tier: str,
    environment: str,
    java_executable: str,
    report_roots: list[Path],
    selection_path: Path | None = None,
) -> dict[str, Any]:
    """Create a capsule whose outcomes are derived from the owning JUnit reports."""
    plan = plan_tool.load_plan(plan_path)
    plan_tool.validate(plan)
    selection = (
        plan_tool.load_plan(selection_path)
        if selection_path is not None
        else plan_tool.select(plan, tier, [], environment)
    )
    expected_selection = plan_tool.select(
        plan,
        selection.get("requested_tier", tier),
        selection.get("changed_paths", []),
        selection.get("environment", environment),
    )
    if selection != expected_selection:
        raise capsule_tool.CapsuleError("selection does not match deterministic plan generation")
    return capsule_tool.capsule(
        selection,
        session_identity(repository, plan_path, java_executable),
        results_from_reports(selection, report_roots, repository),
    )


def main() -> int:
    """Write the canonical capsule for one completed Assurance v2 test graph."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--plan", required=True, type=Path)
    parser.add_argument("--tier", choices=("affected", "full", "release"), required=True)
    parser.add_argument("--environment", choices=("hosted", "local", "release"), required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--reports", action="append", required=True, type=Path)
    parser.add_argument("--selection", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    try:
        document = record(
            arguments.repository.resolve(),
            arguments.plan.resolve(),
            arguments.tier,
            arguments.environment,
            arguments.java,
            [path.resolve() for path in arguments.reports],
            arguments.selection.resolve() if arguments.selection is not None else None,
        )
    except (OSError, UnicodeError, plan_tool.PlanError, capsule_tool.CapsuleError) as error:
        sys.stderr.write(f"assurance session invalid: {error}\n")
        return 2
    capsule_tool.write_json(arguments.output, document)
    return 0 if document["outcome"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
