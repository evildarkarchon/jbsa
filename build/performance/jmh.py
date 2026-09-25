"""Read unprofiled JMH 1.37 timing evidence without using aggregate scores."""

import math
import hashlib
import zipfile

JVM_ARGS = ["-Xms4g", "-Xmx4g", "-XX:+AlwaysPreTouch", "-XX:+UseG1GC"]
TIME_UNITS = {"ns/op": 1e-9, "us/op": 1e-6, "ms/op": 1e-3, "s/op": 1}


def _positive(value):
    """Reject absent, nonfinite and nonpositive measurements."""
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
        raise ValueError("JMH samples must be finite and positive")
    return value


def _iterations(raw):
    """Require the full three-fork by ten-iteration raw evidence array."""
    if not isinstance(raw, list) or len(raw) != 3 or any(not isinstance(fork, list) or len(fork) != 10 for fork in raw):
        raise ValueError("JMH must retain three forks with ten measurement iterations each")
    return raw


def _percentiles(histogram):
    """Calculate JMH weighted percentiles without expanding potentially huge samples.

    JMH 1.37 MultisetStatistics interpolates at rank * (N + 1) / 100,
    bounding out-of-range indices to the extreme observation. This differs from
    the protocol's bootstrap percentile rule, which applies to resampled medians.
    """
    if not isinstance(histogram, list) or not histogram:
        raise ValueError("missing JMH per-iteration SampleTime histogram")
    counts = {}
    for bucket in histogram:
        if not isinstance(bucket, list) or len(bucket) != 2:
            raise ValueError("invalid JMH histogram bucket")
        value, count = bucket
        _positive(value)
        if type(count) is not int or count <= 0:
            raise ValueError("JMH histogram count must be a positive integer")
        counts[value] = counts.get(value, 0) + count
    ordered = sorted(counts.items())
    total = sum(counts.values())

    def observation(index):
        """Return the bounded one-based order statistic by cumulative counts."""
        cumulative = 0
        for value, count in ordered:
            cumulative += count
            if cumulative >= index:
                return value
        return ordered[-1][0]

    result = {}
    for rank in (50, 95, 99):
        position = rank * (total + 1) / 100
        lower = math.floor(position)
        a, b = observation(lower), observation(lower + 1)
        result["p" + str(rank)] = a + (b - a) * (position - lower)
    return result


def _validate_header(result, benchmark, parameters, protocol):
    """Require the same artifact runtime and iteration protocol for timing and profiling."""
    settings = protocol["jmh"] if protocol is not None else {"jvm_args": JVM_ARGS}
    version = protocol["jvm"]["version"] if protocol is not None else "25.0.4.1+1"
    expected = {"jmhVersion": "1.37", "benchmark": benchmark, "forks": 3, "threads": 1,
                "warmupIterations": 5, "warmupTime": "2 s", "warmupBatchSize": 1,
                "measurementIterations": 10, "measurementTime": "2 s", "measurementBatchSize": 1,
                "jvmArgs": settings["jvm_args"], "params": parameters, "vmName": "OpenJDK 64-Bit Server VM"}
    if any(result.get(field) != value for field, value in expected.items()):
        raise ValueError("JMH benchmark, parameter, runtime option or iteration protocol mismatch")
    if result["jdkVersion"] != version.split("+")[0] or result["vmVersion"].removesuffix("-LTS") != version:
        raise ValueError("JMH JVM build mismatch")


def parse_jmh(document, benchmark, parameters, *, payload_bytes=None, protocol=None):
    """Parse an exact unprofiled SampleTime/Throughput pair into thirty observations.

    ``parameters`` must equal the JMH params map, preventing cross-corpus pairing.
    Latencies return seconds; throughput requires ops/s. ``payload_bytes`` is
    the manifest-fixed uncompressed bytes per completed EOF-validated read.
    The caller separately verifies the JVM distribution digest, provider identity,
    benchmark implementation and artifact provenance. Invalid evidence raises
    ValueError; aggregate primaryMetric scores are intentionally unused.
    """
    try:
        if not isinstance(document, list) or len(document) != 2:
            raise ValueError("require exactly one SampleTime and one Throughput result")
        if payload_bytes is not None and (type(payload_bytes) is not int or payload_bytes <= 0):
            raise ValueError("fixed payload class requires positive integer uncompressed bytes")
        modes = {}
        for result in document:
            mode = result["mode"]
            if mode not in ("sample", "thrpt") or mode in modes:
                raise ValueError("duplicate or non-normative JMH mode")
            _validate_header(result, benchmark, parameters, protocol)
            # SampleTime emits its own percentile derivatives even without a
            # profiler. Only those built-in names may accompany timing evidence.
            allowed = {"p0.00", "p0.50", "p0.90", "p0.95", "p0.99", "p0.999", "p0.9999", "p1.00"} if mode == "sample" else set()
            if set(result.get("secondaryMetrics", {})) - allowed:
                raise ValueError("timing evidence contains profiling or unknown secondary metrics")
            modes[mode] = result["primaryMetric"]
        if modes["thrpt"]["scoreUnit"] != "ops/s" or modes["sample"]["scoreUnit"] not in TIME_UNITS:
            raise ValueError("JMH timing units do not describe ops/s and per-operation latency")
        throughput = _iterations(modes["thrpt"]["rawData"])
        samples = _iterations(modes["sample"]["rawDataHistogram"])
        factor = TIME_UNITS[modes["sample"]["scoreUnit"]]
        observations = []
        for fork in range(3):
            for iteration in range(10):
                ops = _positive(throughput[fork][iteration])
                observation = {"fork": fork, "iteration": iteration, "operations_per_second": ops,
                               **{name: value * factor for name, value in _percentiles(samples[fork][iteration]).items()}}
                if payload_bytes is not None:
                    observation["logical_mib_per_second"] = ops * payload_bytes / 1_048_576
                observations.append(observation)
        return observations
    except (KeyError, TypeError, AttributeError, OverflowError) as error:
        raise ValueError("incomplete or malformed JMH JSON evidence") from error


def validate_profile(document, benchmark, parameters, protocol=None):
    """Require separate allocation profiling with every fork and iteration retained.

    Zero allocations are valid. Nonfinite counts or absent gc.alloc.rate.norm
    instrumentation invalidate evidence. Profiled rates never enter timing gates.
    """
    try:
        if not isinstance(document, list) or len(document) != 1 or document[0]["mode"] != "thrpt":
            raise ValueError("allocation profile requires one Throughput result")
        result = document[0]
        _validate_header(result, benchmark, parameters, protocol)
        metric = result["secondaryMetrics"]["gc.alloc.rate.norm"]
        if metric["scoreUnit"] != "B/op":
            raise ValueError("allocation profiler must report bytes per operation")
        for fork in _iterations(metric["rawData"]):
            for value in fork:
                if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                    raise ValueError("allocation samples must be finite and nonnegative")
    except (KeyError, TypeError, AttributeError, OverflowError) as error:
        raise ValueError("missing or malformed allocation profiling evidence") from error


def pair_random_rounds(candidate, *, baseline=None, metadata_10k=None, sequential=None):
    """Pair parsed observations by exact fork/iteration coordinates.

    ``sequential`` supplies thirty dicts with fork, iteration and
    mib_per_second from the size-matched paired sequential observations.
    Artifact/configuration identity validation remains the caller's prerequisite.
    """
    expected = [(fork, iteration) for fork in range(3) for iteration in range(10)]
    try:
        for rows in (candidate, baseline, metadata_10k, sequential):
            if rows is not None and [(row["fork"], row["iteration"]) for row in rows] != expected:
                raise ValueError("random comparator observations are not paired by fork and iteration")
        rounds = []
        for index, row in enumerate(candidate):
            paired = {"fork": row["fork"], "iteration": row["iteration"], "candidate": row}
            if baseline is not None:
                paired["baseline"] = baseline[index]
            if metadata_10k is not None:
                paired["metadata_10k_p50"] = _positive(metadata_10k[index]["p50"])
            if sequential is not None:
                paired["sequential_mib_per_second"] = _positive(sequential[index]["mib_per_second"])
            rounds.append(paired)
        return rounds
    except (KeyError, TypeError) as error:
        raise ValueError("incomplete random comparator observation") from error


def build_command(java, jar, benchmark, parameters, mode, output, *, profile=False):
    """Build a fixed JMH invocation with every filesystem path in one argument."""
    if mode not in ("sample", "thrpt"):
        raise ValueError("unsupported JMH timing mode")
    command = [str(java), "-jar", str(jar), "^" + benchmark.replace(".", r"\.") + "$",
               "-bm", mode, "-f", "3", "-wi", "5", "-w", "2s", "-i", "10", "-r", "2s",
               "-tu", "s", "-t", "1", "-jvm", str(java), "-jvmArgs", " ".join(JVM_ARGS),
               "-rf", "json", "-rff", str(output), "-foe", "true"]
    for name, value in sorted(parameters.items()):
        if "," in str(value):
            raise ValueError("JMH comma-separated parameter expansion is forbidden")
        command += ["-p", f"{name}={value}"]
    if profile:
        command += ["-prof", "gc"]
    return command


def validate_class_manifest(selected, corpus, *, payload=False):
    """Verify a manifest-pinned subset and derive its fixed uncompressed read size.

    Selected entries must retain path, length and digest from the qualification
    corpus. Return bytes/read for payload classes and None for metadata classes.
    """
    try:
        originals = {entry["path"]: entry for entry in corpus["files"]}
        entries = selected["files"]
        if not entries or len({entry["path"] for entry in entries}) != len(entries):
            raise ValueError("random class must contain distinct manifest entries")
        for entry in entries:
            if any(entry[key] != originals[entry["path"]][key] for key in ("path", "length", "sha256")):
                raise ValueError("random class changes the qualified corpus identity")
        if payload:
            lengths = {entry["length"] for entry in entries}
            if len(lengths) != 1:
                raise ValueError("random payload requires one manifest-fixed size class")
            size = next(iter(lengths))
            if type(size) is not int or size <= 0:
                raise ValueError("invalid manifest payload size")
            return size
        return None
    except (KeyError, TypeError) as error:
        raise ValueError("random class is not a subset of the qualified corpus") from error


def validate_jmh_provenance(tool):
    """Bind the standalone benchmark to the exact library that passed conformance.

    Both archives must belong to the verified distribution inventory. Every
    library class must occur byte-identically in the shaded benchmark, except
    module descriptors removed by shading. The complete standalone binding also
    identifies its trusted adapter and dependencies. Return auditable bindings
    and the number of compared classes; mismatches raise ValueError.
    """
    import runner
    try:
        jar_binding, library_binding = tool["jmh_jar"], tool["jmh_library_artifact"]
        if jar_binding not in tool["inventory"] or library_binding not in tool["inventory"]:
            raise ValueError("JMH and library artifacts must belong to the verified inventory")
        if library_binding not in tool["conformance_artifacts"]:
            raise ValueError("JMH library artifact has no matching conformance evidence")
        jar = runner.bound_file(jar_binding, runner.ROOT)
        library = runner.bound_file(library_binding, runner.ROOT)
        with zipfile.ZipFile(library) as qualified, zipfile.ZipFile(jar) as benchmark:
            library_names, benchmark_names = qualified.namelist(), benchmark.namelist()
            if len(library_names) != len(set(library_names)) or len(benchmark_names) != len(set(benchmark_names)):
                raise ValueError("Duplicate JAR entries make implementation identity ambiguous")
            classes = [name for name in library_names if name.endswith(".class") and name.rsplit("/", 1)[-1] != "module-info.class"]
            if not classes:
                raise ValueError("Conformed library artifact contains no implementation classes")
            for name in classes:
                if name not in benchmark_names:
                    raise ValueError("JMH omits a conformed library class: " + name)
                with qualified.open(name) as original, benchmark.open(name) as measured:
                    if hashlib.file_digest(original, "sha256").digest() != hashlib.file_digest(measured, "sha256").digest():
                        raise ValueError("JMH contains different library implementation bytes: " + name)
        return {"standalone": jar_binding, "library": library_binding, "verified_class_count": len(classes)}
    except (KeyError, TypeError, zipfile.BadZipFile) as error:
        raise ValueError("Missing or malformed JMH implementation provenance") from error


def run_case(case, registration, configuration, protocol, directory):
    """Execute paired JMH artifacts, companion cases and separate allocation profiling.

    The registration's ``jmh`` object contains digest-bound archive and manifest
    files and an oracle_archive_producer_sha256. First-release metadata-100k
    additionally binds ``metadata_10k`` with the same three fields. Payload
    manifests define a fixed size class; that exact archive also supplies a
    fresh sequential unpack beside each retained JMH observation. All candidate
    and baseline runtime bindings come from the qualification configuration.
    Missing registration raises ValueError before any process is started.
    """
    import metrics
    import runner
    from corpus import read_manifest

    if not all(key in registration for key in ("jmh", "corpus_manifest")) or not case.get("identity"):
        raise ValueError("random case requires complete JMH and corpus registration")
    payload = case["identity"]["surface"] == "random-payload"
    baseline_required = configuration["baseline_required"]
    index_gate = not payload and case["identity"]["workload"] == "metadata-100k"
    corpus = read_manifest(runner.bound_file(registration["corpus_manifest"], runner.ROOT))
    setup = registration["jmh"]

    def bind_trial(binding):
        """Bind oracle archive and selected entries before each trial invocation."""
        if binding["oracle_archive_producer_sha256"] != runner.ORACLE_SHA256:
            raise ValueError("random access requires a qualified oracle-produced archive")
        archive = runner.bound_file(binding["archive"], runner.ROOT)
        manifest_path = runner.bound_file(binding["manifest"], runner.ROOT)
        # Committed definitions are compressed; explicit JSON class selections
        # remain usable without requiring a duplicate uncompressed full corpus.
        manifest = read_manifest(manifest_path) if manifest_path.suffix == ".gz" else runner.load(manifest_path)
        size = validate_class_manifest(manifest, corpus, payload=payload)
        parameters = {"archivePath": str(archive), "manifestPath": str(manifest_path),
                      "providerIdentity": case["identity"]["codec_provider"], "seed": str(protocol["jmh"]["entry_selection_seed"])}
        return archive, manifest, parameters, size

    archive, selected, parameters, payload_bytes = bind_trial(setup)
    if index_gate and len(selected["files"]) != 100000:
        raise ValueError("metadata index-growth candidate must contain exactly 100000 entries")
    companion = None
    if not baseline_required and index_gate:
        companion = bind_trial(setup["metadata_10k"])
        if len(companion[1]["files"]) != 10000:
            raise ValueError("metadata index-growth comparator must contain exactly 10000 entries")
    benchmark = "io.github.evildarkarchon.jbsa.benchmarks.RandomAccessBenchmark." + ("payload" if payload else "metadata")
    roles = ["candidate", "baseline"] if baseline_required else ["candidate"]
    records, documents = [], {role: [] for role in roles}

    def execute(role, mode, binding, label, profile=False):
        """Recheck prerequisites, warm exact archive and retain one artifact execution."""
        runner.check_prerequisites(case, registration, configuration, protocol)
        trial_archive, _, trial_parameters, _ = bind_trial(binding)
        runner.require_benchmark_volume(trial_archive)
        tool = configuration["comparators"][role]
        java = runner.bound_file(tool["jvm"]["java"], runner.ROOT)
        provenance = validate_jmh_provenance(tool)
        jar = runner.bound_file(tool["jmh_jar"], runner.ROOT)
        work = directory / label
        work.mkdir(parents=True, exist_ok=False)
        runner.warm([trial_archive])
        destination = work / "jmh.json"
        command = build_command(java, jar, benchmark, trial_parameters, mode, destination, profile=profile)
        observed = runner.observe_process(command, work, work / "streams", configuration["timeout_seconds"])
        observed["implementation_provenance"] = provenance
        observed["provider_identity"] = {"token": case["identity"]["codec_provider"], "mapping": case["mapping"]}
        records.append({"role": role, "mode": mode, "profiling": profile, "label": label, "observation": observed})
        (directory / "rounds.json").write_bytes(runner.canonical(records))
        if observed["exit_code"] != 0 or not destination.is_file():
            raise ValueError("JMH execution failed or did not retain its JSON evidence")
        observed["jmh_json"] = {"path": str(destination), "sha256": runner.file_digest(destination)}
        (directory / "rounds.json").write_bytes(runner.canonical(records))
        result = runner.load(destination)
        if profile:
            validate_profile(result, benchmark, trial_parameters, protocol)
        return result

    # Alternating entire candidate/baseline artifacts preserves the required three
    # forks per artifact; the parser subsequently pairs their retained coordinates.
    for mode in ("sample", "thrpt"):
        for role in roles:
            documents[role].extend(execute(role, mode, setup, f"{mode}-{role}"))
    samples = {role: parse_jmh(document, benchmark, parameters, payload_bytes=payload_bytes, protocol=protocol)
               for role, document in documents.items()}
    metadata_samples, sequential = None, None
    if companion is not None:
        companion_document = []
        for mode in ("sample", "thrpt"):
            companion_document.extend(execute("candidate", mode, setup["metadata_10k"], f"{mode}-metadata-10k"))
        metadata_samples = parse_jmh(companion_document, benchmark, companion[2], protocol=protocol)
    if payload and not baseline_required:
        sequential = []
        logical_bytes = sum(entry["length"] for entry in selected["files"])
        sequential_case = {**case, "identity": {**case["identity"], "surface": "unpack-throughput"}}
        for index, sample in enumerate(samples["candidate"]):
            runner.check_prerequisites(case, registration, configuration, protocol)
            bind_trial(setup)
            runner.require_benchmark_volume(archive)
            work = directory / f"sequential-{index:02d}"
            work.mkdir()
            output = work / "output"
            output.mkdir()
            runner.warm([archive])
            command = runner.command_for(sequential_case, configuration["comparators"]["candidate"], "candidate", None, archive, output)
            observed = runner.observe_process(command, work, work / "streams", configuration["timeout_seconds"])
            records.append({"role": "candidate", "label": f"sequential-{index:02d}", "observation": observed})
            (directory / "rounds.json").write_bytes(runner.canonical(records))
            if observed["exit_code"] != 0:
                raise ArithmeticError("size-matched sequential unpack failed")
            observed["validation"] = runner.validate_output(case, registration, "candidate", output, work / "validation")
            # Count and hash the actual extraction so an archive with additional
            # entries cannot masquerade as a size-matched payload comparator.
            extracted = {path.relative_to(output / "extracted").as_posix(): path for path in runner.input_files(output / "extracted")}
            if set(extracted) != {entry["path"] for entry in selected["files"]}:
                raise ValueError("sequential archive does not match the fixed payload class")
            for entry in selected["files"]:
                path = extracted[entry["path"]]
                if path.stat().st_size != entry["length"] or runner.file_digest(path) != entry["sha256"]:
                    raise ArithmeticError("sequential output differs from the selected manifest")
            sequential.append({"fork": sample["fork"], "iteration": sample["iteration"],
                               "mib_per_second": logical_bytes / 1_048_576 / observed["wall_seconds"]})
            (directory / "rounds.json").write_bytes(runner.canonical(records))
    for role in roles:
        execute(role, "thrpt", setup, "profile-" + role, profile=True)
    rounds = pair_random_rounds(samples["candidate"], baseline=samples.get("baseline"),
                                metadata_10k=metadata_samples, sequential=sequential)
    assessment = metrics.evaluate_random(rounds, baseline_required=baseline_required, payload=payload,
        compressed=case["mapping"]["codec"] != "stored", pairing_verified=True, protocol=protocol["bootstrap"], index_gate=index_gate)
    return {"outcome": assessment["outcome"], "rounds": records, "paired_observations": rounds,
            "derived_metrics": assessment, "corpus_sha256": registration["corpus_manifest"]["sha256"]}
