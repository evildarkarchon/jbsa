"""Verify that substantive CV1 history is unchanged from a Git baseline."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


# The freeze intentionally excludes live harness documentation and Assurance v2 files.
FROZEN_PATHS = (
    "tests/conformance/catalog.json",
    ":(glob)tests/conformance/objects/**",
    ":(glob)tests/conformance/rebaselines/**",
    ":(glob)docs/reviews/**",
    "docs/spec/conformance-v1.md",
    "docs/spec/performance-v1.md",
    "tests/performance/catalog.json",
    "tests/performance/requirements.json",
    "tests/performance/protocol.json",
    "tests/performance/baselines.json",
)

FROZEN_ROOTS = (
    "docs/reviews",
    "docs/spec/conformance-v1.md",
    "docs/spec/performance-v1.md",
    "tests/conformance/catalog.json",
    "tests/conformance/objects",
    "tests/conformance/rebaselines",
    "tests/performance/baselines.json",
    "tests/performance/catalog.json",
    "tests/performance/protocol.json",
    "tests/performance/requirements.json",
)


def file_digest(path: Path) -> str:
    """Return one SHA-256 content identity without loading a large artifact at once."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return "sha256:" + digest.hexdigest()


def root_digest(repository: Path, relative_root: str) -> str:
    """Return a canonical file or directory-tree identity for one frozen root."""
    root = repository / relative_root
    if root.is_file():
        return file_digest(root)
    if not root.is_dir():
        raise ValueError(f"frozen evidence root does not exist: {relative_root}")
    digest = hashlib.sha256()
    for path in sorted(candidate for candidate in root.rglob("*") if candidate.is_file()):
        relative = path.relative_to(repository).as_posix()
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(file_digest(path).encode("ascii"))
        digest.update(b"\n")
    return "sha256:" + digest.hexdigest()


def snapshot(repository: Path) -> dict[str, object]:
    """Create the compact reversible-disposition index for frozen v1 evidence."""
    return {
        "restoration": "Restore the indexed roots from Git history at the cutover commit.",
        "roots": {
            root: root_digest(repository, root)
            for root in FROZEN_ROOTS
        },
        "version": "assurance-v2-history-index-v1",
    }


def write_json(path: Path, document: dict[str, object]) -> None:
    """Write deterministic canonical JSON for review and future verification."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def verify_index(repository: Path, index_path: Path) -> str | None:
    """Return an error when a retained digest index differs from frozen evidence."""
    try:
        expected = json.loads(index_path.read_text(encoding="utf-8"))
        actual = snapshot(repository)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        return str(error)
    if expected != actual:
        return "frozen evidence does not match the retained digest index"
    return None


def changed_paths(repository: Path, baseline: str) -> tuple[list[str], str | None]:
    """Return sorted frozen paths changed from ``baseline`` and any Git error."""
    try:
        revision = subprocess.run(
            [
                "git",
                "rev-parse",
                "--verify",
                "--end-of-options",
                f"{baseline}^{{commit}}",
            ],
            cwd=repository,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError as error:
        return [], str(error)
    if revision.returncode != 0:
        return [], revision.stderr.strip() or revision.stdout.strip() or "invalid baseline"
    resolved_baseline = revision.stdout.strip()

    try:
        result = subprocess.run(
            [
                "git",
                "diff",
                "--name-only",
                "--no-renames",
                resolved_baseline,
                "--",
                *FROZEN_PATHS,
            ],
            cwd=repository,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError as error:
        return [], str(error)
    if result.returncode != 0:
        return [], result.stderr.strip() or result.stdout.strip() or "git diff failed"
    try:
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard", "--", *FROZEN_PATHS],
            cwd=repository,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError as error:
        return [], str(error)
    if untracked.returncode != 0:
        return [], untracked.stderr.strip() or untracked.stdout.strip() or "git ls-files failed"
    paths = sorted(
        {
            line.replace("\\", "/")
            for output in (result.stdout, untracked.stdout)
            for line in output.splitlines()
            if line
        }
    )
    return paths, None


def baseline_has_index(repository: Path, baseline: str) -> bool:
    """Return whether the comparison base already entered the v2 frozen-history regime."""
    result = subprocess.run(
        ["git", "cat-file", "-e", f"{baseline}:tests/assurance/history.json"],
        cwd=repository,
        capture_output=True,
        check=False,
    )
    return result.returncode == 0


def main() -> int:
    """Run the frozen-history verification command."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("snapshot", "verify"))
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--baseline")
    parser.add_argument("--index", type=Path)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()

    if arguments.command == "snapshot":
        if arguments.output is None:
            parser.error("snapshot requires --output")
        try:
            write_json(arguments.output, snapshot(arguments.repository))
        except (OSError, ValueError) as error:
            sys.stderr.write(f"frozen CV1 history invalid: {error}\n")
            return 2
        return 0

    if arguments.baseline is None and arguments.index is None:
        parser.error("verify requires --baseline or --index")

    if arguments.baseline is not None:
        paths, error = changed_paths(arguments.repository, arguments.baseline)
        if error is not None:
            sys.stderr.write(f"frozen CV1 history invalid: {error}\n")
            return 2
        # The cutover commit necessarily adds the index and historical banners. Once a
        # baseline contains the index, every later frozen-path edit fails unconditionally.
        entering_frozen_regime = (
            arguments.index is not None
            and not baseline_has_index(arguments.repository, arguments.baseline)
        )
        if paths and not entering_frozen_regime:
            sys.stdout.write("frozen CV1 history changed:\n" + "\n".join(paths) + "\n")
            return 1
    if arguments.index is not None:
        error = verify_index(arguments.repository, arguments.index)
        if error is not None:
            sys.stderr.write(f"frozen CV1 history invalid: {error}\n")
            return 2
    sys.stdout.write("frozen CV1 history unchanged\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
