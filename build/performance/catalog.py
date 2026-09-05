"""Deterministic PV1 assignments; an assignment is never executed evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def canonical(value):
    """Serialize identity data with sorted keys, UTF-8 and no insignificant whitespace."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def digest(value):
    """Return a canonical JSON SHA-256 identity."""
    return hashlib.sha256(canonical(value)).hexdigest()


def validate_profiles(profiles):
    """Require an opaque full runtime profile and explicit per-codec settings.

    The entire runtime object is hashed together. Parameter, dispatch and native
    configuration fields are implementation data; a documentation reference is
    not a replacement for recording their concrete values.
    """
    if (not isinstance(profiles, dict) or set(profiles) != {"profile_id", "codecs"}
            or not isinstance(profiles["profile_id"], str) or not profiles["profile_id"].strip()
            or not isinstance(profiles["codecs"], dict)
            or set(profiles["codecs"]) != {"stored", "zlib", "lz4-frame", "raw-lz4"}):
        raise ValueError("Runtime profile requires an opaque profile_id and every codec mapping")
    for codec, provider in profiles["codecs"].items():
        if (not isinstance(provider, dict) or set(provider) != {"provider", "version", "configuration"}
                or not isinstance(provider["provider"], str)
                or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", provider["provider"])
                or not isinstance(provider["version"], str) or not provider["version"].strip()
                or (provider["provider"] == "none") != (codec == "stored")):
            raise ValueError(f"Invalid exact provider/version mapping for {codec}")
        config = provider["configuration"]
        if (not isinstance(config, dict) or set(config) != {"parameters", "size_dispatch", "native_configuration"}
                or any(not isinstance(value, dict) for value in config.values())):
            raise ValueError(f"Missing exact parameter, dispatch or native settings for {codec}")


def create_catalog(profiles=None):
    """Enumerate each required family, provider, direction and threading lane.

    Future slices bind concrete CV1 evidence and archives through registrations. Missing
    registrations are INVALID, so scaffolding cannot claim performance qualification.
    A supplied runtime profile binds all codec lanes to one canonical full-profile
    digest. Changing any setting creates new case IDs, including stored lanes.
    """
    if profiles is not None:
        validate_profiles(profiles)
    profile_sha256 = digest(profiles) if profiles is not None else None
    families = {
        "tes3": (0x100, None, None, "tes3", ["stored"]),
        "bsa-067": (0x67, None, None, "tes4", ["stored", "zlib", "mixed-zlib"]),
        "bsa-068": (0x68, None, None, "tes5", ["stored", "zlib", "mixed-zlib"]),
        "bsa-069": (0x69, None, None, "sse", ["stored", "lz4-frame", "mixed-lz4-frame"]),
        "fo4-gnrl-v1": (1, "GNRL", None, "fo4", ["stored", "zlib"]),
        "fo4-dx10-v1": (1, "DX10", None, "fo4dds", ["zlib"]),
        "sf-gnrl-v2": (2, "GNRL", None, "sf1", ["stored", "zlib"]),
        "sf-gnrl-v3-m3": (3, "GNRL", 3, "sf1", ["raw-lz4", "mixed-raw-lz4"]),
        "sf-dx10-v2": (2, "DX10", None, "sf1dds", ["zlib"]),
        "sf-dx10-v3-m3": (3, "DX10", 3, "sf1dds", ["raw-lz4"]),
        "fo4-gnrl-v7": (7, "GNRL", None, None, ["stored", "zlib"]),
        "fo4-gnrl-v8": (8, "GNRL", None, None, ["stored", "zlib"]),
        "fo4-dx10-v7": (7, "DX10", None, None, ["zlib"]),
        "fo4-dx10-v8": (8, "DX10", None, None, ["zlib"]),
    }
    cases = []
    for family, (version, kind, method, switch, codecs) in families.items():
        for lane in codecs:
            codec = lane.removeprefix("mixed-")
            provider = "none" if codec == "stored" else "jdk" if codec == "zlib" else "lwjgl"
            profile = profiles["codecs"][codec] if profiles is not None else None
            if profile is not None:
                provider = profile["provider"]
            workloads = ["mixed-10k", "metadata-100k", "bulk-compressible", "bulk-incompressible"]
            if kind == "DX10":
                workloads.append("dds-mipmapped")
            if family in ("bsa-067", "fo4-gnrl-v1"):
                workloads.append("shared-content")
            # Decode-only layouts use an explicitly sized subset, never an encode surrogate.
            if switch is None:
                workloads = ["mixed-10k"]
            for workload in workloads:
                for sharing in (["no", "yes"] if workload == "shared-content" else ["no"]):
                    provider_token = f"{lane}-{provider}-" + ("p"+profile_sha256 if profile_sha256 else "unbound-v1") + ("-share" if sharing == "yes" else "")
                    for direction in (["pack", "unpack"] if switch else ["unpack"]):
                        surfaces = [(f"{direction}-throughput", ["w1", "automatic"]),
                                    (f"{direction}-memory", ["w1", "automatic"])]
                        if direction == "pack":
                            surfaces.append(("pack-output", ["w1", "automatic"]))
                        if switch and workload in ("bulk-compressible", "mixed-10k", "dds-mipmapped"):
                            surfaces.append((f"{direction}-scaling", ["w1", "w2", "w4", "w8", "w16"]))
                        if direction == "unpack" and switch:
                            # The metadata bootstrap compares a registered 10k
                            # subset with the full 100k index. Payload bootstrap
                            # binds a fixed-size class instead of unrelated sizes.
                            if workload == "metadata-100k":
                                surfaces.append(("random-metadata", ["w1"]))
                            if workload in ("bulk-compressible", "bulk-incompressible", "dds-mipmapped"):
                                surfaces.append(("random-payload", ["w1"]))
                        for surface, workers in surfaces:
                            for worker in workers:
                                identity = {"contract": "performance-v1", "surface": surface,
                                            "workload": workload, "archive_family_or_layout": family,
                                            "codec_provider": provider_token, "workers": worker}
                                identity["case_id"] = f"PV1-{surface}.{workload}.{family}.{provider_token}.{worker}"
                                mapping = {"wire_selectors": {"version": version, "kind": kind, "method": method},
                                           "codec": codec, "provider": provider, "codec_profile_sha256": profile_sha256,
                                           "provider_configuration": profile}
                                config = {"family_switch": switch, "codec_switch": {"stored": None, "zlib": "zlib", "lz4-frame": "lz4f", "raw-lz4": "lz4"}[codec],
                                          "split": 2 if family == "tes3" or family.startswith("bsa-") else 0,
                                          "sharing": sharing, "oracle_mt": "no" if worker == "w1" else "yes",
                                          "candidate_workers": worker, "dds_input": kind == "DX10",
                                          "profile_bound": profiles is not None,
                                          "medium_file_count": 1000 if switch is None else None,
                                          "split_boundary_required": family.startswith("bsa-") and codec == "stored" and workload == "bulk-incompressible"}
                                cases.append({"identity": identity, "mapping": mapping, "configuration": config,
                                              "prerequisites": [], "requirements": ["JBSA-PERF-008"]})
    cases.sort(key=lambda c: c["identity"]["case_id"])
    return {"schema_version": 1, "contract": "performance-v1", "profiles": profiles, "cases": cases,
            "note": "Assignments require exact CV1, codec profile and corpus bindings before execution."}


def select_cases(document, mode, impact=None, logical_processors=16):
    """Select complete affected lanes, rejecting unknown, duplicate or missing IDs.

    Unavailable scaling counts are structurally N/A and never enter the executed set.
    A targeted impact declares selectors and must list exactly their union of cases.
    """
    expected = create_catalog(document.get("profiles"))
    if document != expected:
        raise ValueError("Catalog differs from the versioned PV1 assignments")
    cases = document["cases"]
    if mode == "targeted":
        if not impact or not impact.get("reason") or not impact.get("selectors"):
            raise ValueError("Targeted qualification requires an explained impact manifest")
        selected = set()
        allowed = {"surface", "workload", "archive_family_or_layout", "codec_provider", "codec", "provider"}
        for selector in impact["selectors"]:
            if not selector or set(selector) - allowed:
                raise ValueError("Unknown or empty impact selector")
            matches = [c for c in cases if all(c["identity"].get(k, c["mapping"].get(k)) == v for k, v in selector.items())]
            if not matches:
                raise ValueError("Impact selector matches no Performance Case")
            selected.update(c["identity"]["case_id"] for c in matches)
        ids = impact.get("case_ids", [])
        if len(ids) != len(set(ids)) or set(ids) != selected:
            raise ValueError("Impact must contain every affected case exactly once")
        cases = [c for c in cases if c["identity"]["case_id"] in selected]
    elif mode != "full":
        raise ValueError("Use full or targeted qualification")
    return [c for c in cases if not (c["identity"]["surface"].endswith("scaling") and int(c["identity"]["workers"][1:]) > logical_processors)]


def main():
    """Write the versioned catalog for review; ordinary runs only read it."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--profiles", type=Path, help="Exact canonical runtime codec-profile JSON; omitted produces unbound assignments")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    profiles = json.loads(args.profiles.read_text(encoding="utf-8")) if args.profiles else None
    args.output.write_bytes(canonical(create_catalog(profiles)) + b"\n")


if __name__ == "__main__":
    main()
