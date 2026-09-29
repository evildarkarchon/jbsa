"""Capture bounded, repeatable BSArch CLI observations for issue 50 deviations.

The pinned local executable is read only. Probe inputs and outputs stay under the
ignored ``target/issue50-oracle-probes`` directory; only the compact JSON record
is suitable for review in version control.
"""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import uuid


ORACLE_SHA256 = "4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2"
REFERENCE_COMMIT = "fd1e36020b2b5b6217e553dc0038983146a2e2dd"
ROOT = Path(__file__).resolve().parent.parent
ORACLE = ROOT / "tests/fixtures/local/oracle/BSArch.exe"
SCRATCH = ROOT / "target/issue50-oracle-probes"

SOURCES = {
    "source/meshes/alpha.nif": b"jbsa issue50 same payload\n",
    "source/meshes/beta.nif": b"jbsa issue50 same payload\n",
}
INVALID_ARCHIVE = b"not a Bethesda archive\n"
WARNING_ARCHIVE = bytes.fromhex(
    "00010000 14000000 01000000 01000000 00000000 00000000 536f556e445c6100 0000000000000000 07"
)
ANSI_SOURCE = b"jbsa issue50 accented payload\n"
STARFIELD_V2 = {
    "general": ROOT / "tests/fixtures/starfield-general/starfield-general-v2-zlib.hex",
    "dds": ROOT / "tests/fixtures/starfield-dds/starfield-dds-v2-zlib.hex",
}
STARFIELD_V2_SHA256 = {
    "general": "f19bc25cb551c02108879fbcdc4b7ee9469508b4a883c7423e034d0db023036f",
    "dds": "d71b8908fd343998a9bf0a024474f5b31daf065d53dc292e686fccc2ba616ce7",
}

# Each probe has a fresh working directory so one mutation cannot supply an
# accidental prerequisite to another. A few probes have intentional steps.
PROBES = (
    ("repeated-value-first-zero", "CLI-REPEATED-VALUE", (("pack", "source", "archive.bsa", "-tes3", "-split:0", "-split:-1", "-mt:no"),)),
    ("repeated-value-first-negative", "CLI-REPEATED-VALUE", (("pack", "source", "archive.bsa", "-tes3", "-split:-1", "-split:0", "-mt:no"),)),
    ("family-priority-tes3-tes4", "CLI-FAMILY-PRIORITY", (("pack", "source", "archive.bsa", "-tes4", "-tes3", "-mt:no"),)),
    ("family-priority-tes4-fo3", "CLI-FAMILY-PRIORITY", (("pack", "source", "archive.bsa", "-fo3", "-tes4", "-mt:no"),)),
    ("family-priority-fo3-sse", "CLI-FAMILY-PRIORITY", (("pack", "source", "archive.bsa", "-sse", "-fo3", "-mt:no"),)),
    ("family-priority-sse-fo4", "CLI-FAMILY-PRIORITY", (("pack", "source", "archive.bsa", "-fo4", "-sse", "-mt:no"),)),
    ("family-priority-fo4-sf1", "CLI-FAMILY-PRIORITY", (("pack", "source", "archive.ba2", "-sf1", "-fo4", "-mt:no"),)),
    ("ignored-unknown-and-extra", "CLI-IGNORED-ARGUMENT", (("pack", "source", "archive.bsa", "-tes3", "-mt:no", "--issue50-unknown", "extra-tail"),)),
    ("boolean-share-no", "CLI-BOOLEAN-NO", (("pack", "source", "archive.bsa", "-tes3", "-share:no", "-mt:no"),)),
    ("boolean-share-other", "CLI-BOOLEAN-NO", (("pack", "source", "archive.bsa", "-tes3", "-share:other", "-mt:other"),)),
    ("split-noninteger", "CLI-SPLIT-PARSE", (("pack", "source", "archive.bsa", "-tes3", "-split:word", "-mt:no"),)),
    ("split-zero", "CLI-SPLIT-PARSE", (("pack", "source", "archive.bsa", "-tes3", "-split:0", "-mt:no"),)),
    ("split-negative", "CLI-SPLIT-PARSE", (("pack", "source", "archive.bsa", "-tes3", "-split:-1", "-mt:no"),)),
    ("split-above-eight", "CLI-SPLIT-PARSE", (("pack", "source", "archive.bsa", "-tes3", "-split:9", "-mt:no"),)),
    ("flags-automatic", "CLI-ZERO-FLAGS", (("pack", "source", "archive.bsa", "-tes4", "-mt:no"),)),
    ("flags-zero", "CLI-ZERO-FLAGS", (("pack", "source", "archive.bsa", "-tes4", "-af:0", "-ff:0", "-mt:no"),)),
    ("unusable-mixed", "CLI-UNUSABLE-SOURCE", (("pack", "missing+source", "archive.bsa", "-tes3", "-mt:no"),)),
    ("unusable-only", "CLI-UNUSABLE-SOURCE", (("pack", "missing", "archive.bsa", "-tes3", "-mt:no"),)),
    ("stdout-invalid", "CLI-STDOUT", (("pack",),)),
    ("stdout-warning", "CLI-STDOUT", (("warning.bsa", "-list"),)),
    ("info-invalid-archive", "CLI-INFO-ZERO", (("invalid.bsa",),)),
    ("info-missing-path", "CLI-INFO-ZERO", (("missing.bsa",),)),
    ("replace-pack", "CLI-LEGACY-REPLACE", (("pack", "source", "archive.bsa", "-tes3", "-mt:no"), ("pack", "source", "archive.bsa", "-tes3", "-mt:no"))),
    ("replace-unpack", "CLI-LEGACY-REPLACE", (("pack", "source", "archive.bsa", "-tes3", "-mt:no"), ("unpack", "archive.bsa", "destination", "-mt:no"))),
    ("name-active-ansi-acp65001", "NAME-ACTIVE-ANSI", (("pack", "source", "archive.bsa", "-tes4", "-mt:no"), ("archive.bsa", "-list"))),
    ("sf3-general-v2-baseline", "SF3-ZLIB-FALLBACK", (("fixture.ba2", "-list"), ("unpack", "fixture.ba2", "destination", "-mt:no"))),
    ("sf3-general-v3-method2", "SF3-ZLIB-FALLBACK", (("fixture.ba2", "-list"), ("unpack", "fixture.ba2", "destination", "-mt:no"))),
    ("sf3-dds-v2-baseline", "SF3-ZLIB-FALLBACK", (("fixture.ba2", "-list"), ("unpack", "fixture.ba2", "destination", "-mt:no"))),
    ("sf3-dds-v3-method2", "SF3-ZLIB-FALLBACK", (("fixture.ba2", "-list"), ("unpack", "fixture.ba2", "destination", "-mt:no"))),
)


def digest(data: bytes) -> str:
    """Return a lowercase SHA-256 digest for one immutable byte string."""
    return hashlib.sha256(data).hexdigest()


def assert_oracle() -> None:
    """Reject an absent or changed local oracle before every invocation."""
    if not ORACLE.is_file() or digest(ORACLE.read_bytes()) != ORACLE_SHA256:
        raise ValueError("local BSArch oracle is absent or differs from the pinned SHA-256")


def starfield_fixture(kind: str, version: int) -> bytes:
    """Derive a bounded method-2 v3 archive from a committed v2 zlib vector."""
    source = bytes.fromhex(STARFIELD_V2[kind].read_text(encoding="utf-8"))
    if digest(source) != STARFIELD_V2_SHA256[kind]:
        raise ValueError("committed Starfield v2 zlib fixture differs from its pinned bytes")
    if version == 2:
        return source
    if version != 3 or source[:4] != b"BTDX" or struct.unpack_from("<I", source, 4)[0] != 2:
        raise ValueError("unexpected Starfield fixture version or header")
    subtype = b"GNRL" if kind == "general" else b"DX10"
    if source[8:12] != subtype:
        raise ValueError("unexpected Starfield fixture subtype")
    data = bytearray(source)
    struct.pack_into("<I", data, 4, 3)
    data[32:32] = struct.pack("<I", 2)
    struct.pack_into("<Q", data, 16, struct.unpack_from("<Q", data, 16)[0] + 4)
    # Inserting the v3 method field shifts the single record and its payload by
    # four bytes. The stored zlib payload itself remains the pinned v2 vector.
    offset_field = 52 if kind == "general" else 60
    struct.pack_into("<Q", data, offset_field, struct.unpack_from("<Q", data, offset_field)[0] + 4)
    return bytes(data)


def snapshot(work: Path) -> list[dict[str, object]]:
    """Record every fixture and product by relative name, length, and digest."""
    files = []
    for path in sorted(work.rglob("*")):
        if path.is_file():
            data = path.read_bytes()
            files.append(
                {
                    "path": path.relative_to(work).as_posix(),
                    "size": len(data),
                    "sha256": digest(data),
                    "prefix_hex": data[:16].hex(),
                }
            )
    return files


def stream_text(data: bytes, work: Path) -> str:
    """Normalize volatile presentation details in an observed process stream."""
    text = data.decode("utf-8", errors="replace").replace("\r\n", "\n")
    # BSArch sometimes prints absolute paths. The fixture paths and command
    # arguments in the record retain their precise relative identities.
    text = text.replace(str(work), "<work>").replace(str(work).replace("\\", "/"), "<work>")
    # Elapsed time is presentation only and cannot alter a semantic oracle
    # observation or its content identity on an otherwise identical rerun.
    return re.sub(r"\b\d{2}:\d{2}:\d{2}\.\d+\b", "<elapsed>", text)


def run_probe(probe: tuple, run_root: Path) -> dict[str, object]:
    """Execute one isolated probe, retaining all intermediate observations."""
    name, short_id, commands = probe
    work = run_root / name
    work.mkdir(parents=True)
    for relative, payload in SOURCES.items():
        target = work / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(payload)
    if name == "name-active-ansi-acp65001":
        (work / "source/meshes/caf\u00e9.nif").write_bytes(ANSI_SOURCE)
    if name.startswith("sf3-"):
        kind = "general" if "general" in name else "dds"
        version = 2 if "v2-baseline" in name else 3
        (work / "fixture.ba2").write_bytes(starfield_fixture(kind, version))
    if name == "info-invalid-archive":
        (work / "invalid.bsa").write_bytes(INVALID_ARCHIVE)
    if name == "stdout-warning":
        (work / "warning.bsa").write_bytes(WARNING_ARCHIVE)
    steps = []
    for index, command in enumerate(commands):
        if name == "replace-pack" and index == 1:
            (work / "source/meshes/alpha.nif").write_bytes(b"jbsa issue50 replacement payload\n")
        if name == "replace-unpack" and index == 1:
            destination = work / "destination/meshes"
            destination.mkdir(parents=True)
            (destination / "alpha.nif").write_bytes(b"predecessor\n")
        if name.startswith("sf3-") and index == 1:
            (work / "destination").mkdir()
        before = snapshot(work)
        assert_oracle()
        try:
            result = subprocess.run(
                [str(ORACLE), *command],
                cwd=work,
                input=b"",
                capture_output=True,
                timeout=20,
                check=False,
            )
            stdout = stream_text(result.stdout, work)
            stderr = stream_text(result.stderr, work)
            observation = {
                "exit_status": result.returncode,
                "stdout_text_sha256": digest(stdout.encode("utf-8")),
                "stderr_text_sha256": digest(stderr.encode("utf-8")),
                "stdout": stdout,
                "stderr": stderr,
            }
        except subprocess.TimeoutExpired as error:
            observation = {"timeout_seconds": 20, "error": str(error)}
        steps.append({"arguments": list(command), "before": before, "observation": observation, "after": snapshot(work)})
    return {"id": name, "deviation_id": "BSARCH-1.0-V1-" + short_id, "steps": steps}


def main() -> int:
    """Write a canonical, content-identified observation record for review."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="JSON record path")
    arguments = parser.parse_args()
    assert_oracle()
    if ctypes.windll.kernel32.GetACP() != 65001:
        raise ValueError("active-ANSI probe requires this host's Windows ACP 65001")
    SCRATCH.mkdir(parents=True, exist_ok=True)
    run_root = SCRATCH / uuid.uuid4().hex
    run_root.mkdir()
    record = {
        "schema_version": 1,
        "oracle_sha256": ORACLE_SHA256,
        "oracle_relative_path": ORACLE.relative_to(ROOT).as_posix(),
        "reference_snapshot_commit": REFERENCE_COMMIT,
        "probe_script_sha256": digest(
            Path(__file__).read_text(encoding="utf-8").replace("\r\n", "\n").encode("utf-8")
        ),
        "fixture_recipe": {
            **{path: {"size": len(data), "sha256": digest(data)} for path, data in sorted(SOURCES.items())},
            "invalid.bsa": {"size": len(INVALID_ARCHIVE), "sha256": digest(INVALID_ARCHIVE)},
            "warning.bsa": {"size": len(WARNING_ARCHIVE), "sha256": digest(WARNING_ARCHIVE)},
            "source/meshes/caf\u00e9.nif": {"size": len(ANSI_SOURCE), "sha256": digest(ANSI_SOURCE)},
            **{
                STARFIELD_V2[kind].relative_to(ROOT).as_posix(): {
                    "size": len(starfield_fixture(kind, 2)),
                    "sha256": STARFIELD_V2_SHA256[kind],
                }
                for kind in STARFIELD_V2
            },
            **{
                f"starfield-{kind}-v{version}.ba2": {
                    "size": len(starfield_fixture(kind, version)),
                    "sha256": digest(starfield_fixture(kind, version)),
                }
                for kind in STARFIELD_V2
                for version in (2, 3)
            },
        },
        "probes": [run_probe(probe, run_root) for probe in PROBES],
    }
    encoded = json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    record["record_sha256"] = digest(encoded)
    output = arguments.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8", newline="\n")
    print("record_sha256=" + record["record_sha256"])
    print("probes=" + str(len(record["probes"])))
    print("scratch=" + str(run_root))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as error:
        print("oracle probe error: " + str(error), file=sys.stderr)
        raise SystemExit(2) from error
