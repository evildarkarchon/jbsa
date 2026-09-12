"""Build-tool-neutral semantic inspection for Maven-to-Gradle artifact parity."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import Any


ARCHIVE_ENVELOPE_KEYS = frozenset({"archive_sha256"})


def sha256_file(path: Path) -> str:
    """Return the lowercase SHA-256 digest of one file without loading it all into memory."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def inspect_archive(path: Path) -> dict[str, Any]:
    """Inventory one ZIP/JAR by sorted entry names and uncompressed payload digests.

    The whole-file digest is retained as provenance but is ignored by semantic comparison because
    build tools may legitimately choose different ZIP envelopes.
    """
    with zipfile.ZipFile(path) as archive:
        entries = []
        for entry in sorted(archive.infolist(), key=lambda item: item.filename):
            payload = archive.read(entry) if not entry.is_dir() else b""
            entries.append(
                {
                    "path": entry.filename,
                    "size": len(payload),
                    "sha256": hashlib.sha256(payload).hexdigest(),
                }
            )
    return {
        "archive_sha256": sha256_file(path),
        "entries": entries,
        "file_name": path.name,
    }


def _manifest_main_attributes(payload: bytes) -> dict[str, str]:
    """Parse the main section of a JAR manifest, including continuation lines."""
    unfolded: list[str] = []
    for line in payload.decode("utf-8").replace("\r\n", "\n").splitlines():
        if not line:
            break
        if line.startswith(" ") and unfolded:
            unfolded[-1] += line[1:]
        else:
            unfolded.append(line)
    return dict(line.split(": ", 1) for line in unfolded if ": " in line)


def inspect_benchmark_contract(path: Path) -> dict[str, Any]:
    """Inspect the standalone benchmark's launcher, services, classes, and JPMS exclusion."""
    with zipfile.ZipFile(path) as archive:
        names = sorted(entry.filename for entry in archive.infolist())
        try:
            manifest = _manifest_main_attributes(archive.read("META-INF/MANIFEST.MF"))
        except KeyError as error:
            raise ValueError(f"Standalone benchmark has no manifest: {path}") from error
        services = []
        for name in names:
            if not name.startswith("META-INF/services/") or name.endswith("/"):
                continue
            providers = sorted(
                line.strip()
                for line in archive.read(name).decode("utf-8").replace("\r\n", "\n").splitlines()
                if line.strip() and not line.lstrip().startswith("#")
            )
            services.append({"path": name, "providers": providers})
    return {
        "benchmark_classes": [name for name in names if name.endswith("Benchmark.class")],
        "main_class": manifest.get("Main-Class"),
        "module_descriptors": [name for name in names if name == "module-info.class" or name.endswith("/module-info.class")],
        "service_descriptors": services,
    }


def inspect_tree(path: Path) -> dict[str, Any]:
    """Return a deterministic filename, size, and SHA-256 inventory for a directory tree."""
    if not path.is_dir():
        raise ValueError(f"Inventory root is not a directory: {path}")
    files = []
    for item in sorted((candidate for candidate in path.rglob("*") if candidate.is_file())):
        files.append(
            {
                "path": item.relative_to(path).as_posix(),
                "size": item.stat().st_size,
                "sha256": sha256_file(item),
            }
        )
    return {"files": files}


def _child(element: ET.Element | None, name: str) -> ET.Element | None:
    """Find one direct XML child by local name while ignoring its namespace."""
    if element is None:
        return None
    return next((candidate for candidate in element if candidate.tag.rsplit("}", 1)[-1] == name), None)


def _children(element: ET.Element | None, name: str) -> list[ET.Element]:
    """Find all direct XML children by local name while ignoring their namespace."""
    if element is None:
        return []
    return [candidate for candidate in element if candidate.tag.rsplit("}", 1)[-1] == name]


def _text(element: ET.Element | None, name: str, default: str | None = None) -> str | None:
    """Read and trim one direct child value, returning the supplied default when absent."""
    child = _child(element, name)
    if child is None or child.text is None or not child.text.strip():
        return default
    return child.text.strip()


def _normalize_dependency(dependency: ET.Element) -> dict[str, Any]:
    """Normalize consumer-visible Maven dependency fields and sorted exclusions."""
    exclusions = []
    for exclusion in _children(_child(dependency, "exclusions"), "exclusion"):
        exclusions.append(
            {"artifact_id": _text(exclusion, "artifactId"), "group_id": _text(exclusion, "groupId")}
        )
    return {
        "artifact_id": _text(dependency, "artifactId"),
        "classifier": _text(dependency, "classifier"),
        "exclusions": sorted(exclusions, key=lambda item: (item["group_id"] or "", item["artifact_id"] or "")),
        "group_id": _text(dependency, "groupId"),
        "optional": (_text(dependency, "optional", "false") or "false").lower() == "true",
        "scope": _text(dependency, "scope", "compile"),
        "type": _text(dependency, "type", "jar"),
        "version": _text(dependency, "version"),
    }


def _normalize_person(person: ET.Element) -> dict[str, Any]:
    """Normalize a Maven developer identity without retaining whitespace-only XML details."""
    return {
        "email": _text(person, "email"),
        "id": _text(person, "id"),
        "name": _text(person, "name"),
        "organization": _text(person, "organization"),
        "url": _text(person, "url"),
    }


def normalize_consumer_pom(document: str | bytes | Path) -> dict[str, Any]:
    """Normalize the publication contract of a consumer POM into deterministic JSON data."""
    if isinstance(document, Path):
        root = ET.parse(document).getroot()
    else:
        root = ET.fromstring(document)
    parent_element = _child(root, "parent")
    parent = None
    if parent_element is not None:
        parent = {
            "artifact_id": _text(parent_element, "artifactId"),
            "group_id": _text(parent_element, "groupId"),
            "relative_path": _text(parent_element, "relativePath"),
            "version": _text(parent_element, "version"),
        }
    dependencies = [
        _normalize_dependency(dependency)
        for dependency in _children(_child(root, "dependencies"), "dependency")
    ]
    dependencies.sort(
        key=lambda item: (
            item["group_id"] or "",
            item["artifact_id"] or "",
            item["type"] or "",
            item["classifier"] or "",
            item["scope"] or "",
            item["version"] or "",
        )
    )
    licenses = [
        {
            "distribution": _text(license_element, "distribution"),
            "name": _text(license_element, "name"),
            "url": _text(license_element, "url"),
        }
        for license_element in _children(_child(root, "licenses"), "license")
    ]
    developers = [_normalize_person(person) for person in _children(_child(root, "developers"), "developer")]
    scm_element = _child(root, "scm")
    return {
        "artifact_id": _text(root, "artifactId"),
        "dependencies": dependencies,
        "description": _text(root, "description"),
        "developers": sorted(developers, key=lambda item: (item["id"] or "", item["name"] or "")),
        "group_id": _text(root, "groupId"),
        "licenses": sorted(licenses, key=lambda item: (item["name"] or "", item["url"] or "")),
        "model_version": _text(root, "modelVersion"),
        "name": _text(root, "name"),
        "packaging": _text(root, "packaging", "jar"),
        "parent": parent,
        "scm": None
        if scm_element is None
        else {
            "connection": _text(scm_element, "connection"),
            "developer_connection": _text(scm_element, "developerConnection"),
            "tag": _text(scm_element, "tag"),
            "url": _text(scm_element, "url"),
        },
        "url": _text(root, "url"),
        "version": _text(root, "version"),
    }


def _normalize_sbom_component(component: dict[str, Any] | None) -> dict[str, Any] | None:
    """Normalize one CycloneDX component while retaining identity and cryptographic bindings."""
    if component is None:
        return None
    hashes = sorted(
        (
            {"algorithm": item.get("alg"), "content": item.get("content")}
            for item in component.get("hashes", [])
        ),
        key=lambda item: (item["algorithm"] or "", item["content"] or ""),
    )
    return {
        "bom_ref": component.get("bom-ref"),
        "group": component.get("group"),
        "hashes": hashes,
        "name": component.get("name"),
        "purl": component.get("purl"),
        "type": component.get("type"),
        "version": component.get("version"),
    }


def normalize_sbom(document: dict[str, Any] | str | bytes | Path) -> dict[str, Any]:
    """Normalize CycloneDX components and dependency edges without volatile document metadata."""
    if isinstance(document, Path):
        parsed = json.loads(document.read_text(encoding="utf-8"))
    elif isinstance(document, (str, bytes)):
        parsed = json.loads(document)
    else:
        parsed = document
    components = [_normalize_sbom_component(component) for component in parsed.get("components", [])]
    components.sort(key=lambda item: ((item or {}).get("bom_ref") or "", (item or {}).get("purl") or ""))
    dependencies = [
        {"depends_on": sorted(edge.get("dependsOn", [])), "ref": edge.get("ref")}
        for edge in parsed.get("dependencies", [])
    ]
    dependencies.sort(key=lambda edge: edge["ref"] or "")
    metadata = parsed.get("metadata") or {}
    return {
        "bom_format": parsed.get("bomFormat"),
        "components": components,
        "dependencies": dependencies,
        "root_component": _normalize_sbom_component(metadata.get("component")),
        "spec_version": parsed.get("specVersion"),
    }


def _run_jdk_tool(java_home: Path, tool: str, arguments: list[str]) -> str:
    """Run one qualification-JDK tool and return stable normalized output or raise on failure."""
    executable = java_home / "bin" / f"{tool}.exe"
    if not executable.is_file():
        raise ValueError(f"Qualification JDK tool is missing: {executable}")
    completed = subprocess.run(
        [str(executable), *arguments],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if completed.returncode != 0:
        raise ValueError(f"{tool} failed for {arguments!r}: {completed.stderr.strip()}")
    return "\n".join(line.rstrip() for line in completed.stdout.replace("\r\n", "\n").splitlines()).strip()


def inspect_java_contract(path: Path, java_home: Path) -> dict[str, Any]:
    """Record a modular JAR's JPMS descriptor and normalized public `javap` signatures."""
    descriptor = _run_jdk_tool(java_home, "jar", ["--describe-module", "--file", str(path)])
    # `jar --describe-module` prefixes the descriptor with the inspected file URI; that location is
    # not part of JPMS semantics and necessarily differs between isolated Maven and Gradle roots.
    descriptor = re.sub(r"^(\S+)\s+jar:file:.*!/module-info\.class$", r"\1", descriptor, count=1, flags=re.MULTILINE)
    with zipfile.ZipFile(path) as archive:
        classes = sorted(
            entry.filename[:-6].replace("/", ".")
            for entry in archive.infolist()
            if entry.filename.endswith(".class")
            and entry.filename not in {"module-info.class"}
            and not entry.filename.endswith("/module-info.class")
            and not entry.filename.endswith("/package-info.class")
            and not entry.filename.startswith("META-INF/versions/")
        )
    signatures = []
    for class_name in classes:
        output = _run_jdk_tool(java_home, "javap", ["-public", "-s", "-classpath", str(path), class_name])
        normalized = "\n".join(
            line.strip() for line in output.splitlines() if not line.startswith("Compiled from ")
        )
        signatures.append({"class": class_name, "signature": normalized})
    return {"module_descriptor": descriptor, "public_signatures": signatures}


def normalize_staging_manifest(path: Path) -> dict[str, Any]:
    """Normalize the deterministic release-input manifest while retaining all identity fields."""
    document = json.loads(path.read_text(encoding="utf-8"))
    entries = [
        {
            "kind": entry.get("kind"),
            "path": entry.get("path"),
            "sha256": entry.get("sha256"),
            "source": entry.get("source"),
        }
        for entry in document.get("entries", [])
    ]
    entries.sort(key=lambda entry: entry["path"] or "")
    return {"entries": entries, "schema_version": document.get("schemaVersion")}


def compare_snapshots(
    expected: Any,
    actual: Any,
    *,
    ignore_archive_envelopes: bool = True,
) -> list[dict[str, Any]]:
    """Return deterministic leaf differences between two normalized snapshots.

    Whole-archive hashes are ignored by default for Maven-to-Gradle comparison; callers may disable
    that behavior for same-tool reproducibility checks.
    """
    differences: list[dict[str, Any]] = []

    def compare(left: Any, right: Any, path: str) -> None:
        if isinstance(left, dict) and isinstance(right, dict):
            for key in sorted(set(left) | set(right)):
                if ignore_archive_envelopes and key in ARCHIVE_ENVELOPE_KEYS:
                    continue
                child_path = f"{path}.{key}" if path else key
                if key not in left:
                    differences.append({"actual": right[key], "expected": None, "path": child_path})
                elif key not in right:
                    differences.append({"actual": None, "expected": left[key], "path": child_path})
                else:
                    compare(left[key], right[key], child_path)
            return
        if isinstance(left, list) and isinstance(right, list):
            for index in range(max(len(left), len(right))):
                child_path = f"{path}[{index}]"
                if index >= len(left):
                    differences.append({"actual": right[index], "expected": None, "path": child_path})
                elif index >= len(right):
                    differences.append({"actual": None, "expected": left[index], "path": child_path})
                else:
                    compare(left[index], right[index], child_path)
            return
        if left != right:
            differences.append({"actual": right, "expected": left, "path": path})

    compare(expected, actual, "")
    return differences


def _file_record(path: Path) -> dict[str, Any]:
    """Record one ordinary evidence file by name, size, and content digest."""
    return {"file_name": path.name, "sha256": sha256_file(path), "size": path.stat().st_size}


def inspect_build(build_root: Path, version: str, java_home: Path) -> dict[str, Any]:
    """Inspect all canonical Maven/Gradle parity outputs beneath one isolated build root."""
    artifacts = {
        "benchmark_standalone": build_root
        / "jbsa-benchmarks"
        / "target"
        / f"jbsa-benchmarks-{version}-standalone.jar",
        "library": build_root / "jbsa" / "target" / f"jbsa-{version}.jar",
        "library_sources": build_root / "jbsa" / "target" / f"jbsa-{version}-sources.jar",
        "library_javadocs": build_root / "jbsa" / "target" / f"jbsa-{version}-javadoc.jar",
        "thin_cli": build_root / "jbsa-cli" / "target" / f"jbsa-cli-{version}.jar",
    }
    missing = [str(path) for path in artifacts.values() if not path.is_file()]
    if missing:
        raise ValueError(f"Canonical artifacts are missing: {', '.join(missing)}")
    consumer_pom = build_root / "jbsa" / ".flattened-pom.xml"
    sbom = build_root / "target" / "compliance" / "jbsa.cdx.json"
    staging_manifest = build_root / "jbsa-dist" / "target" / "release-inputs.json"
    for required in (consumer_pom, sbom, staging_manifest):
        if not required.is_file():
            raise ValueError(f"Required parity evidence is missing: {required}")
    inspected_artifacts = {name: inspect_archive(path) for name, path in artifacts.items()}
    inspected_artifacts["benchmark_standalone"]["benchmark_contract"] = inspect_benchmark_contract(
        artifacts["benchmark_standalone"]
    )
    for name in ("library", "thin_cli"):
        inspected_artifacts[name]["java_contract"] = inspect_java_contract(artifacts[name], java_home)
    notice_paths = {
        "release_notes": build_root / "target" / "compliance" / "RELEASE-NOTES.md",
        "third_party_notices": build_root / "target" / "compliance" / "THIRD-PARTY-NOTICES.md",
    }
    normalized_sbom = normalize_sbom(sbom)
    staged_files = inspect_tree(build_root / "jbsa-dist" / "target" / "release-inputs")
    benchmark_name = artifacts["benchmark_standalone"].name
    return {
        "artifacts": inspected_artifacts,
        "benchmark_exclusions": {
            "present_in_sbom": any(
                component.get("name") == "jbsa-benchmarks"
                for component in normalized_sbom["components"]
                if component is not None
            ),
            "present_in_staging": any(file["path"] == benchmark_name for file in staged_files["files"]),
        },
        "consumer_pom": normalize_consumer_pom(consumer_pom),
        "notices": {name: _file_record(path) for name, path in notice_paths.items()},
        "runtime_dependencies": inspect_tree(build_root / "jbsa-dist" / "target" / "runtime-dependencies"),
        "sbom": normalized_sbom,
        "staged_files": staged_files,
        "staging_manifest": normalize_staging_manifest(staging_manifest),
    }


def _load_json(path: Path) -> Any:
    """Load one UTF-8 JSON document for CLI comparison."""
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path | None, document: Any) -> None:
    """Write deterministic UTF-8 JSON to a file or standard output."""
    text = json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    if path is None:
        sys.stdout.write(text)
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")


def main(arguments: list[str] | None = None) -> int:
    """Inspect a build or compare two previously normalized parity snapshots."""
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    inspect_parser = subparsers.add_parser("inspect-build")
    inspect_parser.add_argument("--build-root", type=Path, required=True)
    inspect_parser.add_argument("--java-home", type=Path, required=True)
    inspect_parser.add_argument("--version", required=True)
    inspect_parser.add_argument("--output", type=Path, required=True)
    compare_parser = subparsers.add_parser("compare")
    compare_parser.add_argument("--expected", type=Path, required=True)
    compare_parser.add_argument("--actual", type=Path, required=True)
    compare_parser.add_argument("--output", type=Path)
    compare_parser.add_argument("--include-archive-envelopes", action="store_true")
    options = parser.parse_args(arguments)
    if options.command == "inspect-build":
        _write_json(
            options.output,
            inspect_build(options.build_root.resolve(), options.version, options.java_home.resolve()),
        )
        return 0
    differences = compare_snapshots(
        _load_json(options.expected),
        _load_json(options.actual),
        ignore_archive_envelopes=not options.include_archive_envelopes,
    )
    _write_json(options.output, {"differences": differences, "matches": not differences})
    return 0 if not differences else 1


if __name__ == "__main__":
    raise SystemExit(main())
