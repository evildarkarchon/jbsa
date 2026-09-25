"""Pure, deterministic performance-v1 metric evaluation."""

import functools
import math
import random
import re
import statistics

MIB = 1_048_576
DEFAULT_BOOTSTRAP = {"resamples": 10000, "seed": 32011, "percentile_method": "linear"}


def validate_case(case):
    """Validate the seven exact PERF-001 identity fields, raising ValueError."""
    fields = {"case_id", "contract", "surface", "workload", "archive_family_or_layout", "codec_provider", "workers"}
    surfaces = {"pack-throughput", "unpack-throughput", "random-metadata", "random-payload", "pack-memory",
                "unpack-memory", "pack-output", "pack-scaling", "unpack-scaling"}
    if not isinstance(case, dict) or set(case) != fields:
        raise ValueError("case identity must contain exactly the seven PERF-001 fields")
    tokens = [case[field] for field in ("surface", "workload", "archive_family_or_layout", "codec_provider", "workers")]
    if any(not isinstance(token, str) or re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", token) is None for token in tokens):
        raise ValueError("case identity tokens must be lowercase ASCII")
    if (case["contract"] != "performance-v1" or case["surface"] not in surfaces
            or case["workers"] not in {"w1", "w2", "w4", "w8", "w16", "automatic", "none"}
            or case["case_id"] != "PV1-" + ".".join(tokens)):
        raise ValueError("case serialization, surface, worker, or contract mismatch")


def _evidence_boundary(function):
    """Convert malformed or incomplete observations into an INVALID result."""
    @functools.wraps(function)
    def checked(*args, **kwargs):
        """Evaluate a public seam while preserving fail-closed evidence semantics."""
        try:
            return function(*args, **kwargs)
        except (ValueError, TypeError, KeyError, OverflowError) as error:
            return {"outcome": "INVALID", "reasons": [str(error)], "extend_to": None}
    return checked


def _positive(value):
    """Reject zero, booleans and nonfinite measurements before arithmetic."""
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
        raise ValueError("measurement must be finite and positive")
    return value


def _integer(value):
    """Keep byte counts exact, including values above binary64 precision."""
    if type(value) is not int or value < 0:
        raise ValueError("byte count must be a nonnegative integer")
    return value


def _percentile(values, fraction):
    """Interpolate adjacent order statistics using the pinned linear rule."""
    index = (len(values) - 1) * fraction
    lower = math.floor(index)
    upper = math.ceil(index)
    return values[lower] + (values[upper] - values[lower]) * (index - lower)


def _combine(gates, **evidence):
    """Require every independent gate; incomplete evidence never qualifies."""
    outcomes = [gate["outcome"] for gate in gates.values()]
    outcome = "INVALID" if "INVALID" in outcomes or not outcomes else "FAIL" if "FAIL" in outcomes else "PASS"
    return {"outcome": outcome, "gates": gates,
            "extend_to": 15 if any(gate.get("extend_to") == 15 for gate in gates.values()) else None,
            **evidence}


@_evidence_boundary
def evaluate_ratios(ratios, boundary, direction="lower", protocol=None, sampling="process"):
    """Evaluate PERF-011 paired ratios; seven inconclusive rounds request fifteen.

    ``protocol`` is the bootstrap object (resamples, seed, percentile_method).
    Raw paired ratios are retained; callers must establish comparator identity.
    ``sampling='jmh'`` requires all thirty fork/iteration observations and does
    not apply the process-only seven-to-fifteen-round extension.
    """
    ratios = [_positive(value) for value in ratios]
    _positive(boundary)
    required_counts = (7, 15) if sampling == "process" else (30,) if sampling == "jmh" else ()
    if len(ratios) not in required_counts or direction not in ("lower", "upper"):
        raise ValueError("incorrect paired sample count or gate direction")
    settings = DEFAULT_BOOTSTRAP if protocol is None else protocol
    if (type(settings["resamples"]) is not int or settings["resamples"] < 1000
            or type(settings["seed"]) is not int or settings["percentile_method"] != "linear"):
        raise ValueError("invalid pinned bootstrap protocol")
    median = statistics.median(ratios)
    dispersion = statistics.median(abs(value - median) for value in ratios) / abs(median)
    rng = random.Random(settings["seed"])
    draws = sorted(statistics.median(rng.choices(ratios, k=len(ratios))) for _ in range(settings["resamples"]))
    low, high = _percentile(draws, 0.025), _percentile(draws, 0.975)
    passing = low >= boundary if direction == "lower" else high <= boundary
    failing = high < boundary if direction == "lower" else low > boundary
    inconclusive = dispersion > 0.05 or not (passing or failing)
    outcome = "INVALID" if inconclusive else "PASS" if passing else "FAIL"
    return {"outcome": outcome, "ratios": ratios, "median": median, "dispersion": dispersion,
            "ci": [low, high], "boundary": boundary, "direction": direction,
            "bootstrap": dict(settings), "extend_to": 15 if inconclusive and sampling == "process" and len(ratios) == 7 else None}


@_evidence_boundary
def evaluate_throughput(rounds, logical_bytes, baseline_required=False, protocol=None):
    """Compute MiB/s and independent oracle/baseline gates from paired wall times.

    Each round has candidate_seconds, oracle_seconds and, when required,
    baseline_seconds. Timings must already exclude setup and validation.
    """
    logical_bytes = _positive(_integer(logical_bytes))
    comparators = ["candidate", "oracle"] + (["baseline"] if baseline_required else [])
    values = {role: [_positive(row[role + "_seconds"]) for row in rounds] for role in comparators}
    gates = {"JBSA-PERF-014": evaluate_ratios([other / candidate for other, candidate in
             zip(values["oracle"], values["candidate"])], 0.80, protocol=protocol)}
    if baseline_required:
        gates["JBSA-PERF-019-throughput"] = evaluate_ratios([other / candidate for other, candidate in
            zip(values["baseline"], values["candidate"])], 0.95, protocol=protocol)
    return _combine(gates, raw_observations=rounds, metrics={role + "_mib_per_second":
                    [logical_bytes / MIB / seconds for seconds in samples] for role, samples in values.items()})


def _ceiling(actual, ceiling):
    """Represent a deterministic upper gate without floating point coercion."""
    return {"outcome": "PASS" if actual <= ceiling else "FAIL", "actual": actual,
            "boundary": ceiling, "direction": "upper"}


def _parts(parts):
    """Sum every output part exactly; an absent output is incomplete evidence."""
    if not isinstance(parts, list) or not parts:
        raise ValueError("output must enumerate every published part")
    return sum(_integer(part) for part in parts)


@_evidence_boundary
def evaluate_output(candidate_parts, oracle_parts, baseline_parts=None, *, stored=False, dds=False,
                    binary=False, binary_equal=None, baseline_required=False):
    """Apply PERF-017 integer allowances to sums of all published parts.

    ``binary_equal`` is the independently verified byte comparison, never a
    conclusion drawn from equal lengths. Baseline output always gates if given.
    """
    candidate, oracle = _parts(candidate_parts), _parts(oracle_parts)
    gates = {}
    if binary:
        if type(binary_equal) is not bool:
            raise ValueError("missing Binary Conformance byte comparison")
        gates["JBSA-PERF-017-binary"] = {"outcome": "PASS" if binary_equal and candidate == oracle else "FAIL"}
    else:
        allowance = max(65_536, oracle // 200) if stored and not dds else max(MIB, oracle // 20)
        gates["JBSA-PERF-017-oracle"] = _ceiling(candidate, oracle + allowance)
    values = {"candidate_bytes": candidate, "oracle_bytes": oracle,
              "oracle_difference": candidate - oracle, "oracle_ratio": candidate / oracle if oracle else None}
    if baseline_required or baseline_parts is not None:
        baseline = _parts(baseline_parts)
        gates["JBSA-PERF-017-baseline"] = _ceiling(candidate, baseline + max(65_536, baseline // 100))
        values.update(baseline_bytes=baseline, baseline_difference=candidate - baseline,
                      baseline_ratio=candidate / baseline if baseline else None)
    return _combine(gates, metrics=values, raw_observations={"candidate_parts": candidate_parts,
                    "oracle_parts": oracle_parts, "baseline_parts": baseline_parts})


@_evidence_boundary
def evaluate_memory(repetitions, logical_bytes, largest_entry_bytes, baseline_required=False, instrumentation_ok=True):
    """Gate five repetitions' maxima, retaining medians and all byte samples.

    Repetitions map candidate/oracle/baseline to private_bytes and working_set_bytes;
    candidate also requires heap_bytes derived from JFR. The caller must verify
    the Job Object, final counters, 50 ms sampling and JFR evidence separately.
    """
    logical_bytes, largest_entry_bytes = _integer(logical_bytes), _integer(largest_entry_bytes)
    if len(repetitions) != 5 or instrumentation_ok is not True:
        raise ValueError("five instrumented repetitions and complete JFR evidence are required")
    comparators = ["candidate", "oracle"] + (["baseline"] if baseline_required else [])
    values = {}
    for role in comparators:
        values[role] = {}
        for metric in ["private_bytes", "working_set_bytes"] + (["heap_bytes"] if role == "candidate" else []):
            samples = [_integer(row[role][metric]) for row in repetitions]
            values[role][metric] = {"samples": samples, "maximum": max(samples), "median": statistics.median(samples)}
    gates = {}
    for metric in ("private_bytes", "working_set_bytes"):
        candidate = values["candidate"][metric]["maximum"]
        oracle = values["oracle"][metric]["maximum"]
        gates["JBSA-PERF-016-oracle-" + metric] = _ceiling(candidate, oracle + max(512 * MIB, logical_bytes // 4))
        if baseline_required:
            baseline = values["baseline"][metric]["maximum"]
            gates["JBSA-PERF-016-baseline-" + metric] = _ceiling(candidate, max(baseline * 110 // 100, baseline + 64 * MIB))
    gates["JBSA-PERF-016-heap"] = _ceiling(values["candidate"]["heap_bytes"]["maximum"], 512 * MIB + 2 * largest_entry_bytes)
    return _combine(gates, metrics=values, raw_observations=repetitions)


@_evidence_boundary
def evaluate_scaling(rounds, logical_processors, protocol=None):
    """Evaluate every available worker count independently using paired wall times.

    Each round maps string worker counts (1,2,4,8,16) to seconds. Counts beyond
    available logical processors must be absent, and every available count present.
    """
    if type(logical_processors) is not int or logical_processors < 1:
        raise ValueError("logical processor count must be positive")
    counts = [str(count) for count in (1, 2, 4, 8, 16) if count <= logical_processors]
    if not rounds or any(set(row) != set(counts) for row in rounds):
        raise ValueError("missing, unknown or structurally unavailable worker count")
    values = {count: [_positive(row[count]) for row in rounds] for count in counts}
    if len(rounds) not in (7, 15):
        raise ValueError("expected seven or fifteen paired scaling rounds")
    metrics, gates = {}, {}
    for count in counts:
        ratios = [one / many for one, many in zip(values["1"], values[count])]
        speedup = statistics.median(ratios)
        metrics[count] = {"ratios": ratios, "speedup": speedup, "efficiency": speedup / int(count)}
        if count in {"2", "4", "8"}:
            gates["JBSA-PERF-018-w" + count] = evaluate_ratios(ratios, {"2": 1.5, "4": 2.5, "8": 4.0}[count], protocol=protocol)
        if count == "16":
            # Comparing against each lower count also protects the best lower count
            # without selecting a different fastest comparator in every round.
            for lower in counts[:-1]:
                gates["JBSA-PERF-018-w16-v-w" + lower] = evaluate_ratios(
                    [previous / current for previous, current in zip(values[lower], values[count])], 0.95, protocol=protocol)
    if not gates:
        gates["JBSA-PERF-018-single-worker"] = {"outcome": "PASS", "observational": True}
    return _combine(gates, metrics=metrics, raw_observations=rounds,
                    not_applicable=[count for count in (1, 2, 4, 8, 16) if count > logical_processors])


@_evidence_boundary
def evaluate_random(rounds, *, baseline_required=False, payload=False, compressed=False,
                    pairing_verified=False, protocol=None, index_gate=True):
    """Gate thirty paired JMH measurements, preserving payload throughput units.

    Rows contain candidate (and required baseline) objects with
    operations_per_second, p50, p95, p99, plus logical_mib_per_second for payload.
    First-release metadata rows with ``index_gate`` require metadata_10k_p50; payload rows require
    sequential_mib_per_second. The caller must verify matching corpus, layout,
    codec/provider/profile and workers except the specified independent variable.
    """
    if pairing_verified is not True or len(rounds) != 30:
        raise ValueError("random access requires verified comparator pairing and thirty JMH observations")
    names = ["operations_per_second", "p50", "p95", "p99"] + (["logical_mib_per_second"] if payload else [])
    roles = ["candidate"] + (["baseline"] if baseline_required else [])
    values = {role: {name: [_positive(row[role][name]) for row in rounds] for name in names} for role in roles}
    for role in roles:
        if any(not p50 <= p95 <= p99 for p50, p95, p99 in zip(values[role]["p50"], values[role]["p95"], values[role]["p99"])):
            raise ValueError("random latency percentiles must be ordered p50 <= p95 <= p99")
    gates = {}
    if baseline_required:
        for name in names:
            direction = "upper" if name in ("p50", "p95", "p99") else "lower"
            boundary = {"p50": 1.05, "p95": 1.10, "p99": 1.10}.get(name, 0.95)
            gates["JBSA-PERF-019-" + name] = evaluate_ratios(
                [candidate / baseline for candidate, baseline in zip(values["candidate"][name], values["baseline"][name])],
                boundary, direction, protocol, sampling="jmh")
    else:
        gates["JBSA-PERF-015-tail"] = evaluate_ratios(
            [p99 / median for p99, median in zip(values["candidate"]["p99"], values["candidate"]["p50"])],
            10 if compressed else 5, "upper", protocol, sampling="jmh")
        if payload:
            gates["JBSA-PERF-015-payload"] = evaluate_ratios(
                [value / _positive(row["sequential_mib_per_second"]) for value, row in
                 zip(values["candidate"]["logical_mib_per_second"], rounds)], 0.5, "lower", protocol, sampling="jmh")
        elif index_gate:
            gates["JBSA-PERF-015-index"] = evaluate_ratios(
                [value / _positive(row["metadata_10k_p50"]) for value, row in
                 zip(values["candidate"]["p50"], rounds)], 1.5, "upper", protocol, sampling="jmh")
    reported = {role: {name: {"samples": samples, "median": statistics.median(samples)}
               for name, samples in samples_by_metric.items()} for role, samples_by_metric in values.items()}
    return _combine(gates, metrics=reported, raw_observations=rounds)
