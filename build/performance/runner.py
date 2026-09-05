"""Explicit local PV1 qualification. Harness checks are not acceptance evidence."""
import argparse
import copy
import ctypes
import gzip
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time
import zipfile

from catalog import canonical, digest, select_cases

ROOT = Path(__file__).resolve().parents[2]
ORACLE_SHA256 = "4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2"


def load(path):
    """Read strict JSON, rejecting duplicate keys and nonfinite numbers."""
    def pairs(items):
        """Refuse duplicate object fields before an identity can be overwritten."""
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    return json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def file_digest(path):
    """Hash a file incrementally outside the measured operation."""
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def bound_file(binding, root):
    """Resolve a manifest-bound regular file without following filesystem indirections."""
    path = Path(os.path.abspath(root / binding["path"]))
    for parent in (path, *path.parents):
        if parent.is_symlink() or (hasattr(parent, "is_junction") and parent.is_junction()):
            raise ValueError(f"Input follows a filesystem indirection: {path}")
    if not path.is_file() or file_digest(path) != binding["sha256"]:
        raise ValueError(f"Bound input digest mismatch: {path}")
    return path


def observe_process(command, cwd, streams, timeout):
    """Time creation through exit with argument arrays and file-backed stream capture.

    A timeout kills the whole Windows process tree. Capture and hashing finish outside
    the timed interval; no printed tool timing is used as a clock.
    """
    streams.mkdir(parents=True, exist_ok=False)
    with (streams / "stdout.txt").open("wb") as stdout, (streams / "stderr.txt").open("wb") as stderr:
        started = time.perf_counter_ns()
        process = subprocess.Popen(command, cwd=cwd, stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr, shell=False)
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True, check=False)
            process.kill()
            process.wait()
            raise ValueError("Measured process timed out")
        elapsed = (time.perf_counter_ns() - started) / 1e9
    return {"command": command, "exit_code": code, "wall_seconds": elapsed,
            "stdout": str(streams / "stdout.txt"), "stderr": str(streams / "stderr.txt")}


def round_order(index, baseline_required):
    """Alternate first-release order or rotate all three comparators in paired rounds."""
    roles = ["candidate", "baseline", "oracle"] if baseline_required else ["candidate", "oracle"]
    offset = index % len(roles)
    return roles[offset:] + roles[:offset]


def validate_baseline_selection(configuration, registry):
    """Require the checked-in current baseline; registration changes require a reviewed commit."""
    current = registry["current"]
    if type(configuration.get("baseline_required")) is not bool or configuration["baseline_required"] != (current is not None):
        raise ValueError("Current Performance Baseline cannot be omitted or selected ad hoc")
    if current is not None:
        binding = configuration["baseline_bundle"]
        if binding["sha256"] != current["sha256"]:
            raise ValueError("Performance Baseline differs from the versioned registry")
        bundle = load(bound_file(binding, ROOT))
        accepted = load(bound_file(bundle["accepted_results"], ROOT))
        if accepted.get("outcome") != "PASS" or any(c["outcome"] != "PASS" for c in accepted["cases"]):
            raise ValueError("Baseline lacks a complete accepted result set")
        if bundle["comparators"]["candidate"] != configuration["comparators"]["baseline"]:
            raise ValueError("Baseline distribution, launcher, JVM or provider identity mismatch")
        if bundle["protocol_sha256"] != configuration["protocol_sha256"]:
            raise ValueError("Baseline protocol changed; full requalification required")


def validate_conformance_artifacts(tool, artifacts):
    """Match CV1 bytes to the actual verified candidate distribution, not a repeated claim."""
    if not artifacts or artifacts != tool["conformance_artifacts"]:
        raise ValueError("Conformance evidence belongs to another candidate")
    inventory = {item["sha256"] for item in tool["inventory"]}
    if any(item["sha256"] not in inventory for item in artifacts):
        raise ValueError("Conformance artifacts are absent from the executed distribution")


def validate_jvm_bytes(jvm, distribution):
    """Compare the executing VM and Java launcher with the digest-pinned JDK ZIP.

    Application images may trim Java modules, but they cannot substitute the native
    VM or Java launcher while retaining the distribution's identity.
    """
    with zipfile.ZipFile(distribution) as archive:
        for key, suffix in (("java", "/bin/java.exe"), ("vm_library", "/bin/server/jvm.dll")):
            names = [name for name in archive.namelist() if name.endswith(suffix)]
            if len(names) != 1:
                raise ValueError("Pinned JDK distribution has ambiguous runtime members")
            actual = bound_file(jvm[key], ROOT)
            with archive.open(names[0]) as stream:
                expected = hashlib.file_digest(stream, "sha256").hexdigest()
            if file_digest(actual) != expected:
                raise ValueError("Executing JVM bytes differ from the pinned distribution")


def require_benchmark_volume(path):
    """Check the actual input or output volume, including Windows mounted directories."""
    if os.name != "nt":
        raise ValueError("Benchmark inputs require Windows NTFS volumes")
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    volume, filesystem = ctypes.create_unicode_buffer(32768), ctypes.create_unicode_buffer(64)
    path = str(Path(path).absolute())
    if not kernel.GetVolumePathNameW(ctypes.c_wchar_p(path), volume, len(volume)):
        raise ValueError("Cannot identify the benchmark input volume")
    if not kernel.GetVolumeInformationW(volume, None, 0, None, None, None, filesystem, len(filesystem)):
        raise ValueError("Cannot inspect benchmark volume filesystem")
    free, total, total_free = ctypes.c_ulonglong(), ctypes.c_ulonglong(), ctypes.c_ulonglong()
    if not kernel.GetDiskFreeSpaceExW(volume, ctypes.byref(free), ctypes.byref(total), ctypes.byref(total_free)):
        raise ValueError("Cannot inspect benchmark volume free space")
    if filesystem.value != "NTFS" or total.value == 0 or free.value / total.value < .2:
        raise ValueError("Every benchmark input/output volume must be NTFS with at least 20% free space")
    return {"volume": volume.value, "filesystem": filesystem.value, "free_bytes": free.value, "total_bytes": total.value}


def environment(mode, output, attestation):
    """Measure Windows/NTFS preconditions and retain human-attested quiet-work conditions."""
    if any(os.environ.get(key) for key in ("GITHUB_ACTIONS", "RUNNER_ENVIRONMENT", "ACTIONS_RUNNER_NAME", "CI")):
        raise ValueError("Normative benchmarks are forbidden in hosted and self-hosted CI")
    if os.name != "nt" or platform.machine().lower() not in ("amd64", "x86_64"):
        raise ValueError("Qualification requires local Windows x64")
    probe = subprocess.run(["pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File",
                            str(ROOT / "build/performance-environment.ps1"), "-VolumePath", str(output)],
                           capture_output=True, text=True, check=True)
    observed = json.loads(probe.stdout)
    if observed["filesystem"] != "NTFS" or observed["free_fraction"] < .2 or observed["cpu_percent"] >= 2:
        raise ValueError("NTFS, free space or background CPU precondition failed")
    if not observed["high_performance"] or not observed["security_active"]:
        raise ValueError("High performance plan and active security software are required")
    for key in ("no_concurrent_workload", "normal_security_configuration", "warmed_cache_protocol"):
        if attestation.get(key) is not True:
            raise ValueError(f"Missing environmental attestation: {key}")
    if mode == "full" and (attestation.get("boot_time") != observed["boot_time"] or
                            attestation.get("idle_seconds", 0) < 600 or observed["uptime_seconds"] < 600):
        raise ValueError("Full qualification requires this reboot and ten-minute idle attestation")
    observed["attestation"] = attestation
    return observed


def check_prerequisites(case, registration, configuration, protocol):
    """Bind exact candidate, corpus, provider, JVM and passed CV1 evidence before invocation."""
    from corpus import read_manifest, validate_manifest, verify_materialization
    if not case["configuration"].get("profile_bound"):
        raise ValueError("Performance Case must bind an immutable release codec profile")
    if registration["case_id"] != case["identity"]["case_id"] or registration["case_sha256"] != digest(case):
        raise ValueError("Registration has stale case identity or mappings")
    if configuration["protocol_sha256"] != file_digest(ROOT / "tests/performance/protocol.json"):
        raise ValueError("Protocol digest mismatch")
    roles = ["candidate", "oracle"] + (["baseline"] if configuration["baseline_required"] else [])
    for role in roles:
        tool = configuration["comparators"][role]
        bound_file(tool["launcher"], ROOT)
        if not tool.get("inventory"):
            raise ValueError("Comparator must bind its complete distribution inventory")
        for item in tool["inventory"]:
            bound_file(item, ROOT)
        if role == "oracle":
            if tool["launcher"]["sha256"] != ORACLE_SHA256:
                raise ValueError("Conformance Oracle identity mismatch")
        else:
            jvm = tool["jvm"]
            distribution = bound_file(jvm["distribution"], ROOT)
            if file_digest(distribution) != protocol["jvm"]["distribution_sha256"]:
                raise ValueError("JVM distribution identity mismatch")
            release = bound_file(jvm["release"], ROOT).read_text()
            if 'IMPLEMENTOR_VERSION="Temurin-25.0.4.1+1"' not in release or 'OS_ARCH="x86_64"' not in release:
                raise ValueError("Installed JVM identity mismatch")
            bound_file(jvm["java"], ROOT)
            validate_jvm_bytes(jvm, distribution)
            inventory_digests = {item["sha256"] for item in tool["inventory"]}
            if any(binding["sha256"] not in inventory_digests for binding in (tool["launcher"], jvm["java"], jvm["vm_library"], tool["codec_profile"])):
                raise ValueError("Launcher, JVM and profile must belong to the bound distribution")
            if tool["provider_configurations"][case["mapping"]["codec"]] != case["mapping"]["provider_configuration"]:
                raise ValueError("Provider version or configuration mismatch")
            if tool["codec_profile_sha256"] != case["mapping"]["codec_profile_sha256"]:
                raise ValueError("Codec profile digest mismatch")
            profile = load(bound_file(tool["codec_profile"], ROOT))
            if digest(profile) != case["mapping"]["codec_profile_sha256"]:
                raise ValueError("Runtime codec profile bytes differ from the case mapping")
    manifest_path = bound_file(registration["corpus_manifest"], ROOT)
    manifest = read_manifest(manifest_path)
    validate_manifest(manifest, require_normative=True)
    if manifest["workload"] != case["identity"]["workload"]:
        raise ValueError("Smoke or mismatching corpus cannot provide qualification evidence")
    source = ROOT / registration["source_directory"]
    require_benchmark_volume(source)
    verify_materialization(manifest, source)
    if case["configuration"]["dds_input"] and any("header_bytes" not in f["structural"] for f in manifest["files"]):
        raise ValueError("DDS layout requires a conformed DDS specialization of this workload")
    medium_count = case["configuration"]["medium_file_count"]
    if medium_count and (manifest["file_count"] != medium_count or "projection" not in manifest):
        raise ValueError("Decode-only layout requires its manifest-sized medium projection")
    prerequisites = registration["prerequisites"]
    if not prerequisites or len(prerequisites) != len(set(prerequisites)):
        raise ValueError("Exact prerequisite Conformance Cases are required")
    cv1_catalog = load(ROOT / "tests/conformance/catalog.json")
    family = case["identity"]["archive_family_or_layout"]
    codec = case["mapping"]["codec"]
    required = {c["identity"]["case_id"] for c in cv1_catalog["cases"]
                if c["identity"]["archive_family"] in (family, "global")
                and c["identity"]["codec"] in (codec, "mixed", "none", "stored")}
    if not required or not required.issubset(set(prerequisites)):
        raise ValueError("Registration omitted required family/provider Conformance Cases")
    cv1 = load(bound_file(configuration["conformance_report"], ROOT))
    results = cv1.get("cases", cv1.get("results", []))
    matching = {item["case_id"]: item for item in results}
    if len(matching) != len(results):
        raise ValueError("Duplicate Conformance Case evidence")
    for case_id in prerequisites:
        item = matching.get(case_id, {})
        if item.get("result") != "PASS" or item.get("codec_profile_sha256") != case["mapping"]["codec_profile_sha256"]:
            raise ValueError("Missing or mismatched passed Conformance Case")
        validate_conformance_artifacts(configuration["comparators"]["candidate"], item.get("candidate_artifacts"))
    return manifest, source


def input_files(source):
    """Enumerate a regular source tree deterministically for warming and exact output checks."""
    return sorted(path for path in source.rglob("*") if path.is_file())


def warm(paths):
    """Pre-read the exact measured inputs into the page cache outside timed regions."""
    for path in paths:
        with path.open("rb") as stream:
            while stream.read(1024 * 1024):
                pass  # Only reading, without decoding or hashing, warms the filesystem cache.


def command_for(case, tool, role, source, archive, output):
    """Construct equivalent CLI argument lists and explicit worker mappings."""
    config = case["configuration"]
    command = [str(bound_file(tool["launcher"], ROOT)), *tool["production_arguments"]]
    if case["identity"]["surface"].startswith("pack"):
        command += ["pack", str(source), str(output / "archive.bsa"), "-" + config["family_switch"],
                    f'-split:{config["split"]}', f'-share:{config["sharing"]}']
        if config["codec_switch"]:
            command.append("-z:" + config["codec_switch"])
    else:
        command += ["unpack", str(archive), str(output / "extracted")]
    command.append("-mt:" + config["oracle_mt"])
    if role != "oracle" and case["identity"]["workers"] not in ("w1", "automatic"):
        # The shipping CLI may expose worker control only after the concurrency slice.
        # Require an explicit pinned switch mapping rather than invent a production flag.
        worker = case["identity"]["workers"]
        command.extend(tool["worker_arguments"][worker])
    return command


def validate_output(case, registration, role, work, streams):
    """Run a digest-bound correctness validator after timing and retain its exact observation."""
    validator = registration["validator"]
    command = [str(bound_file(validator["executable"], ROOT)), *validator["arguments"],
               "--case-id", case["identity"]["case_id"], "--role", role, "--output", str(work)]
    for binding in validator["inputs"]:
        bound_file(binding, ROOT)
    observed = observe_process(command, work, streams, validator["timeout_seconds"])
    if observed["exit_code"] != 0:
        raise ValueError("Output validation instrumentation failed")
    result = load(observed["stdout"])
    if result.get("case_id") != case["identity"]["case_id"] or result.get("outcome") not in ("PASS", "FAIL"):
        raise ValueError("Output validator emitted incomplete evidence")
    if result["outcome"] == "FAIL":
        raise ArithmeticError("Produced output failed correctness validation")
    expected = load(bound_file(registration["expected_projection"], ROOT))
    if result.get("projection") != expected or not expected:
        raise ArithmeticError("Produced output differs from the pinned semantic projection")
    return result


def evaluate_output_rounds(records, **options):
    """Gate every measured output separately; a later good sample cannot erase a failure."""
    import metrics
    results = []
    for record in records:
        if record["warmup"]:
            continue
        observations = record["observations"]
        sizes = {role: [p["length"] for p in obs["output_parts"]] for role, obs in observations.items()}
        results.append(metrics.evaluate_output(sizes["candidate"], sizes["oracle"], sizes.get("baseline"),
            binary_equal=observations["candidate"]["output_parts"] == observations["oracle"]["output_parts"], **options))
    outcomes = {r["outcome"] for r in results}
    return {"outcome": "INVALID" if not results or "INVALID" in outcomes else "FAIL" if "FAIL" in outcomes else "PASS",
            "round_metrics": results}


def validate_split_output(parts):
    """Require a real split publication crossing 2 GiB, with each part within its limit."""
    limit = 2_147_483_648
    if len(parts) < 2 or sum(part["length"] for part in parts) <= limit or any(part["length"] > limit for part in parts):
        raise ArithmeticError("Required versioned-BSA output did not cross the 2 GiB split boundary correctly")


def observe_scaling_round(case, tool, source, archive, work, registration, timeout, round_index):
    """Measure all available worker counts inside one paired round with rotating order."""
    counts = [n for n in (1, 2, 4, 8, 16) if n <= (os.cpu_count() or 1)]
    counts = counts[round_index % len(counts):] + counts[:round_index % len(counts)]
    row, observations = {}, {}
    for workers in counts:
        variant = copy.deepcopy(case)
        variant["identity"]["workers"] = f"w{workers}"
        variant["configuration"]["oracle_mt"] = "no" if workers == 1 else "yes"
        child = work / f"workers-{workers}"
        child.mkdir()
        output = child / "output"
        output.mkdir()
        warm([archive] if archive else input_files(source))
        observation = observe_process(command_for(variant, tool, "candidate", source, archive, output), child, child / "streams", timeout)
        if observation["exit_code"] != 0:
            raise ArithmeticError("Scaling operation failed")
        observation["validation"] = validate_output(case, registration, "candidate", output, child / "validation")
        row[str(workers)] = observation["wall_seconds"]
        observations[str(workers)] = observation
    return row, observations


def execute_process_case(case, registration, configuration, protocol, directory):
    """Run complete fresh-output paired rounds, extending noisy or inconclusive timing cases."""
    import metrics
    manifest, source = check_prerequisites(case, registration, configuration, protocol)
    surface = case["identity"]["surface"]
    if surface.startswith("random"):
        return execute_random_case(case, registration, configuration, protocol, directory)
    archive = None
    if surface.startswith("unpack"):
        archive = bound_file(registration["oracle_archive"], ROOT)
        require_benchmark_volume(archive)
        if registration.get("oracle_archive_producer_sha256") != ORACLE_SHA256:
            raise ValueError("Unpack input must be bound to Conformance Oracle production evidence")
    warm([archive] if archive else input_files(source))
    baseline = configuration["baseline_required"]
    logical_bytes = sum(item["length"] for item in manifest["files"])
    largest = max(item["length"] for item in manifest["files"])
    records, paired, memory_rows, scaling_rows = [], [], [], []
    is_memory = surface.endswith("memory")
    measured_count = 5 if is_memory else 7
    warmups = 0 if is_memory else 2
    index = 0
    assessment = None
    while index < warmups + measured_count:
        row, observations = {}, {}
        records.append({"round": index, "warmup": index < warmups, "order": round_order(index, baseline), "observations": observations})
        for role in round_order(index, baseline):
            # Rehash all immutable inputs before every invocation, including the oracle.
            check_prerequisites(case, registration, configuration, protocol)
            warm([archive] if archive else input_files(source))
            tool = configuration["comparators"][role]
            work = directory / f"round-{index:02d}" / role
            work.mkdir(parents=True, exist_ok=False)
            output = work / "output"
            output.mkdir()
            command = command_for(case, tool, role, source, archive, output)
            if is_memory:
                observation = observe_memory(command, tool, work, configuration["timeout_seconds"])
                row[role] = {"private_bytes": observation["peak_private_bytes"],
                             "working_set_bytes": observation["peak_working_set_bytes"],
                             "heap_bytes": observation["heap_high_water_bytes"]}
            else:
                observation = observe_process(command, work, work / "streams", configuration["timeout_seconds"])
                row[role + "_seconds"] = observation["wall_seconds"]
            observations[role] = observation
            (directory / "rounds.json").write_bytes(canonical(records))
            if observation["exit_code"] != 0:
                raise ArithmeticError(f"{role} operation failed")
            observation["validation"] = validate_output(case, registration, role, output, work / "validation")
            parts = input_files(output)
            observation["output_parts"] = [{"path": str(p.relative_to(output)), "length": p.stat().st_size, "sha256": file_digest(p)} for p in parts]
            if surface.startswith("pack") and case["configuration"]["split_boundary_required"]:
                validate_split_output(observation["output_parts"])
            if not parts:
                raise ArithmeticError("No output artifacts were produced")
            (directory / "rounds.json").write_bytes(canonical(records))
        if surface.endswith("scaling"):
            scaling_work = directory / f"round-{index:02d}" / "scaling"
            scaling_work.mkdir()
            scaling_row, scaling_observations = observe_scaling_round(case, configuration["comparators"]["candidate"], source,
                archive, scaling_work, registration, configuration["timeout_seconds"], index)
            records[-1]["scaling_observations"] = scaling_observations
            if index >= warmups:
                scaling_rows.append(scaling_row)
        # Flush each completed round before starting the next; a later crash must
        # preserve its earlier raw evidence rather than reducing it to an empty case.
        (directory / "rounds.json").write_bytes(canonical(records))
        if index >= warmups:
            (memory_rows if is_memory else paired).append(row)
        index += 1
        if index == warmups + measured_count:
            if is_memory:
                assessment = metrics.evaluate_memory(memory_rows, logical_bytes, largest, baseline_required=baseline)
            elif surface == "pack-output":
                assessment = evaluate_output_rounds(records,
                    stored=case["mapping"]["codec"] == "stored", dds=case["configuration"]["dds_input"],
                    binary=registration["binary_conformance"], baseline_required=baseline)
            else:
                assessment = metrics.evaluate_throughput(paired, logical_bytes, baseline_required=baseline, protocol=protocol["bootstrap"])
                if surface.endswith("scaling"):
                    scaling = metrics.evaluate_scaling(scaling_rows, os.cpu_count() or 1, protocol=protocol["bootstrap"])
                    assessment["scaling"] = scaling
                    outcomes = {assessment["outcome"], scaling["outcome"]}
                    assessment["outcome"] = "INVALID" if "INVALID" in outcomes else "FAIL" if "FAIL" in outcomes else "PASS"
                    if scaling.get("extend_to"):
                        assessment["extend_to"] = scaling["extend_to"]
            if not is_memory and assessment.get("extend_to") == 15 and measured_count == 7:
                measured_count = 15
    return {"outcome": assessment["outcome"], "rounds": records, "derived_metrics": assessment,
            "paired_observations": paired or memory_rows, "corpus_sha256": registration["corpus_manifest"]["sha256"]}


def memory_request(command, tool, work, timeout):
    """Bind the shipping process unchanged and route instrumented scratch to its sampled directory.

    Only JFR recording and scratch routing enter JAVA_TOOL_OPTIONS. A different
    memory executable, unbound runtime tool or ambient JVM injection is INVALID.
    """
    launcher = bound_file(tool["launcher"], ROOT)
    if not command or Path(command[0]).resolve() != launcher.resolve():
        raise ValueError("Memory command must use the actual shipping launcher")
    if tool.get("memory_launcher", tool["launcher"]) != tool["launcher"] or tool.get("memory_arguments"):
        raise ValueError("Memory cannot substitute a different launcher or production arguments")
    inventory = {(str(bound_file(item, ROOT)), item["sha256"]) for item in tool["inventory"]}
    required = [tool["launcher"]]
    java = "jvm" in tool
    if java:
        required += [tool["jvm"][key] for key in ("release", "java", "jfr_tool")]
    for binding in required:
        if (str(bound_file(binding, ROOT)), binding["sha256"]) not in inventory:
            raise ValueError("Memory launcher/runtime/JFR tool is outside the bound inventory")
    if any(os.environ.get(key) for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")):
        raise ValueError("Ambient JVM option injection is forbidden during memory measurement")
    scratch = work / "scratch"
    environment = {"TMP": str(scratch), "TEMP": str(scratch)}
    request = {"executable": command[0], "arguments": command[1:], "working_directory": str(work),
               "trace_directory": str(work / "memory"), "java_process": java,
               "scratch_directory": str(scratch), "timeout_seconds": timeout,
               "process_environment": environment}
    if java:
        request["jfr_path"] = str(work / "heap.jfr")
        request["jfr_repository"] = str(work / "jfr-repository")
        request["jfr_tool"] = str(bound_file(tool["jvm"]["jfr_tool"], ROOT))
        if any(character in str(work) for character in ('"', ',', '\r', '\n')):
            raise ValueError("Memory evidence path cannot be represented safely in JFR options")
        # JAVA_TOOL_OPTIONS reaches the same embedded HotSpot used by the shipping
        # jpackage launcher. Missing recording proves an incompatible launcher INVALID.
        environment["JAVA_TOOL_OPTIONS"] = " ".join('"' + argument + '"' for argument in (
            f"-Djava.io.tmpdir={scratch}",
            f"-XX:FlightRecorderOptions=repository={request['jfr_repository']}",
            f"-XX:StartFlightRecording=filename={request['jfr_path']},settings=profile,dumponexit=true"))
    return request


def observe_memory(command, tool, work, timeout):
    """Invoke the independent Job Object collector; profiling never contaminates timing rounds."""
    request = memory_request(command, tool, work, timeout)
    Path(request["scratch_directory"]).mkdir()
    if request.get("jfr_repository"):
        Path(request["jfr_repository"]).mkdir()
    request_path, response_path = work / "memory-request.json", work / "memory-response.json"
    request_path.write_bytes(canonical(request))
    process = subprocess.run(["pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File",
                              str(ROOT / "build/performance-memory.ps1"), "-RequestPath", str(request_path),
                              "-ResponsePath", str(response_path)], capture_output=True, text=True, timeout=timeout + 60)
    if process.returncode != 0 or not response_path.is_file():
        raise ValueError("Job Object/JFR memory instrumentation failed")
    result = load(response_path)
    if result["outcome"] != "PASS":
        raise ValueError("Incomplete Job Object/JFR memory evidence: " + "; ".join(result.get("reasons", [])))
    result["command"] = command
    result["process_environment"] = request["process_environment"]
    return result


def execute_random_case(case, registration, configuration, protocol, directory):
    """Execute pinned JMH artifacts; adapters must be supplied by conformed archive slices."""
    # The parser and trial interface validate the exact JMH protocol independently.
    import jmh
    return jmh.run_case(case, registration, configuration, protocol, directory)


def qualify(mode, output, config_path=None, impact_path=None):
    """Write a complete local case matrix, retaining INVALID for every missing prerequisite."""
    output = Path(os.path.abspath(output))
    if not output.is_relative_to(ROOT / "target") or output == ROOT / "target":
        raise ValueError("Evidence must use a new directory beneath repository target")
    for parent in (output, *output.parents):
        if parent.is_symlink() or (hasattr(parent, "is_junction") and parent.is_junction()):
            raise ValueError("Evidence must not follow a filesystem indirection")
    output.mkdir(parents=True, exist_ok=False)
    config = load(config_path) if config_path else None
    catalog_path = bound_file(config["catalog"], ROOT) if config else ROOT / "tests/performance/catalog.json"
    document = load(catalog_path)
    cases = select_cases(document, mode, load(impact_path) if impact_path else None, os.cpu_count() or 1)
    registrations, prerequisite_error, fingerprint = {}, None, {"os": platform.platform(), "logical_processors": os.cpu_count()}
    try:
        if config is None:
            raise ValueError("No pinned candidate, oracle, corpus and Conformance Case registration")
        validate_baseline_selection(config, load(ROOT / "tests/performance/baselines.json"))
        ids = {case["identity"]["case_id"] for case in cases}
        registrations = {item["case_id"]: item for item in config["cases"]}
        if len(registrations) != len(config["cases"]) or set(registrations) != ids:
            raise ValueError("Missing, unknown or duplicate required case registration")
        fingerprint = environment(mode, output, config["environment_attestation"])
        protocol = load(ROOT / "tests/performance/protocol.json")
    except (KeyError, ValueError, OSError, subprocess.SubprocessError) as error:
        prerequisite_error = str(error)
    records = []
    for case in cases:
        record = {**case["identity"], "identity_mappings": case["mapping"], "configuration": case["configuration"],
                  "comparators": config.get("comparators", {}) if config else {},
                  "prerequisite_conformance_cases": registrations.get(case["identity"]["case_id"], {}).get("prerequisites", []),
                  "environment": fingerprint, "rounds": [], "derived_metrics": {}, "outcome": "INVALID"}
        try:
            if prerequisite_error:
                raise ValueError(prerequisite_error)
            directory = output / ("case-" + digest(case["identity"]["case_id"])[:16])
            directory.mkdir()
            record.update(execute_process_case(case, registrations[case["identity"]["case_id"]], config, protocol, directory))
        except ArithmeticError as error:
            record["outcome"], record["reason"] = "FAIL", str(error)
        except (KeyError, ValueError, OSError, subprocess.SubprocessError, ImportError) as error:
            record["reason"] = str(error)
        record["evidence_directory"] = "case-" + digest(case["identity"]["case_id"])[:16]
        saved_rounds = output / record["evidence_directory"] / "rounds.json"
        if saved_rounds.is_file():
            record["rounds"] = load(saved_rounds)
        records.append(record)
    report = {"schema_version": 1, "contract": "performance-v1", "mode": mode,
              "catalog_sha256": file_digest(catalog_path),
              "protocol_sha256": file_digest(ROOT / "tests/performance/protocol.json") if (ROOT / "tests/performance/protocol.json").is_file() else None,
              "configuration_sha256": file_digest(config_path) if config_path else None,
              "cases": records, "outcome": "INVALID" if not records or any(r["outcome"] == "INVALID" for r in records) else "FAIL" if any(r["outcome"] == "FAIL" for r in records) else "PASS"}
    (output / "results.json").write_bytes(canonical(report) + b"\n")
    if report["outcome"] == "PASS" and mode == "full" and not config["baseline_required"]:
        bundle = {"contract": "performance-v1", "version": config["release_version"],
                  "comparators": {"candidate": config["comparators"]["candidate"]},
                  "catalog_sha256": report["catalog_sha256"], "protocol_sha256": report["protocol_sha256"],
                  "accepted_results": {"path": str((output / "results.json").relative_to(ROOT)),
                                       "sha256": file_digest(output / "results.json")},
                  "corpus_manifests": [r["corpus_manifest"] for r in config["cases"]]}
        bundle_path = output / "first-baseline.json"
        bundle_path.write_bytes(canonical(bundle))
        # This receipt avoids a digest cycle between the accepted results and bundle.
        # The release gate records this immutable bundle in the versioned registry.
        (output / "baseline-receipt.json").write_bytes(canonical({"first_baseline_sha256": file_digest(bundle_path)}))
    matrix = ["# Local performance-v1 qualification", "", f"Outcome: {report['outcome']}", "",
              "| Performance Case | Outcome | Reason |", "| --- | --- | --- |"]
    matrix += [f"| {r['case_id']} | {r['outcome']} | {r.get('reason', '').replace('|', '/')} |" for r in records]
    (output / "matrix.md").write_text("\n".join(matrix) + "\n", encoding="utf-8")
    for name in ("protocol.json", "catalog.json"):
        source = catalog_path if name == "catalog.json" else ROOT / "tests/performance" / name
        if source.is_file():
            shutil.copyfile(source, output / name)
    # Retain compressed raw streams in addition to directly inspectable text; never
    # bundle the proprietary oracle executable or the materialized input corpus.
    for stream in output.rglob("*.txt"):
        with stream.open("rb") as source, gzip.GzipFile(filename=str(stream) + ".gz", mode="wb", mtime=0) as destination:
            shutil.copyfileobj(source, destination)
    return report


def main():
    """Expose explicit full/targeted local commands; no Maven lifecycle runs benchmarks."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("full", "targeted"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--configuration", type=Path)
    parser.add_argument("--impact", type=Path)
    args = parser.parse_args()
    try:
        report = qualify(args.mode, args.output, args.configuration, args.impact)
        print(f"{report['outcome']}: {args.output / 'matrix.md'}")
        return 0 if report["outcome"] == "PASS" else 1
    except (ValueError, OSError, KeyError) as error:
        print(f"INVALID: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
