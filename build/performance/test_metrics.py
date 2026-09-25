"""Worked numerical boundaries at the public metric evaluation seam."""

import unittest

import metrics


class IdentityTests(unittest.TestCase):
    """Exact identities exclude ambiguous or silently remapped cases."""

    def test_exact_fields_and_case_serialization(self):
        """Verify exact fields and case serialization."""
        case = {"case_id": "PV1-pack-throughput.mixed-10k.tes3.stored-none.w1",
                "contract": "performance-v1", "surface": "pack-throughput", "workload": "mixed-10k",
                "archive_family_or_layout": "tes3", "codec_provider": "stored-none", "workers": "w1"}
        self.assertIsNone(metrics.validate_case(case))
        for change in ({"unknown": True}, {"workers": "w3"}, {"workload": "Mixed"}, {"case_id": case["case_id"] + "-x"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                metrics.validate_case(case | change)


class ProcessMetricTests(unittest.TestCase):
    """Independent gates use within-round ratios and exact integer bytes."""

    def test_throughput_uses_inverse_wall_time_and_requires_baseline(self):
        """Verify throughput uses inverse wall time and requires baseline."""
        rounds = [{"candidate_seconds": 10, "oracle_seconds": 8, "baseline_seconds": 9.5}] * 7
        result = metrics.evaluate_throughput(rounds, 20 * metrics.MIB, baseline_required=True)
        self.assertEqual(result["outcome"], "PASS")
        self.assertEqual(result["metrics"]["candidate_mib_per_second"], [2] * 7)
        self.assertEqual(result["gates"]["JBSA-PERF-014"]["ratios"], [0.8] * 7)
        rounds = [{"candidate_seconds": 10, "oracle_seconds": 8}] * 7
        self.assertEqual(metrics.evaluate_throughput(rounds, 1, baseline_required=True)["outcome"], "INVALID")

    def test_throughput_cannot_average_away_baseline_failure(self):
        """Verify throughput cannot average away baseline failure."""
        rounds = [{"candidate_seconds": 10, "oracle_seconds": 20, "baseline_seconds": 9.4}] * 7
        self.assertEqual(metrics.evaluate_throughput(rounds, 1, baseline_required=True)["outcome"], "FAIL")

    def test_pairing_precedes_medians(self):
        """Verify pairing precedes medians."""
        rows = [{"candidate_seconds": candidate, "oracle_seconds": oracle} for candidate, oracle in
                zip([1, 2, 3, 4, 5, 6, 7], [10, 2, 3, 4, 5, 6, 7])]
        result = metrics.evaluate_throughput(rows, 1)
        self.assertEqual(result["gates"]["JBSA-PERF-014"]["median"], 1)
        self.assertEqual(result["gates"]["JBSA-PERF-014"]["ratios"], [10, 1, 1, 1, 1, 1, 1])

    def test_output_stored_fraction_is_floored_before_allowance(self):
        """Verify output stored fraction is floored before allowance."""
        self.assertEqual(metrics.evaluate_output([20_100_199], [20_000_199], stored=True)["outcome"], "PASS")
        self.assertEqual(metrics.evaluate_output([20_100_200], [20_000_199], stored=True)["outcome"], "FAIL")

    def test_output_large_integer_precision_and_split_sum(self):
        """Verify output large integer precision and split sum."""
        oracle = 10_000_000_000_000_001
        self.assertEqual(metrics.evaluate_output([10_050_000_000_000_001], [oracle], stored=True)["outcome"], "PASS")
        self.assertEqual(metrics.evaluate_output([10_050_000_000_000_002], [oracle], stored=True)["outcome"], "FAIL")
        self.assertEqual(metrics.evaluate_output([600_000, 600_000], [100_000], stored=False)["outcome"], "FAIL")

    def test_binary_and_baseline_size_are_independent(self):
        """Verify binary and baseline size are independent."""
        self.assertEqual(metrics.evaluate_output([10], [10], binary=True, binary_equal=False)["outcome"], "FAIL")
        self.assertEqual(metrics.evaluate_output([10], [10], binary=True)["outcome"], "INVALID")
        self.assertEqual(metrics.evaluate_output([1_100_000], [2_000_000], [1_000_000])["outcome"], "FAIL")
        self.assertEqual(metrics.evaluate_output([10], [10], baseline_required=True)["outcome"], "INVALID")

    def test_memory_gates_maximum_not_median(self):
        """Verify memory gates maximum not median."""
        candidate = {"private_bytes": 100, "working_set_bytes": 100, "heap_bytes": 100}
        oracle = {"private_bytes": 100, "working_set_bytes": 100}
        rounds = [{"candidate": dict(candidate), "oracle": oracle} for _ in range(5)]
        rounds[-1]["candidate"]["private_bytes"] = 536_871_013
        result = metrics.evaluate_memory(rounds, 100, 100)
        self.assertEqual(result["outcome"], "FAIL")
        self.assertEqual(result["metrics"]["candidate"]["private_bytes"]["median"], 100)
        self.assertEqual(result["metrics"]["candidate"]["private_bytes"]["maximum"], 536_871_013)

    def test_memory_instrumentation_and_separate_working_set_gate(self):
        """Verify memory instrumentation and separate working set gate."""
        rounds = [{"candidate": {"private_bytes": 100, "working_set_bytes": 536_871_013, "heap_bytes": 100},
                   "oracle": {"private_bytes": 100, "working_set_bytes": 100}}] * 5
        self.assertEqual(metrics.evaluate_memory(rounds, 100, 100)["outcome"], "FAIL")
        self.assertEqual(metrics.evaluate_memory(rounds[:4], 100, 100)["outcome"], "INVALID")
        self.assertEqual(metrics.evaluate_memory(rounds, 100, 100, instrumentation_ok=False)["outcome"], "INVALID")

    def test_memory_baseline_additive_allowance_and_heap_exact_boundary(self):
        """Verify memory baseline additive allowance and heap exact boundary."""
        candidate = {"private_bytes": 67_108_964, "working_set_bytes": 67_108_964, "heap_bytes": 536_871_112}
        rounds = [{"candidate": candidate, "oracle": {"private_bytes": 100, "working_set_bytes": 100},
                   "baseline": {"private_bytes": 100, "working_set_bytes": 100}}] * 5
        self.assertEqual(metrics.evaluate_memory(rounds, 100, 100, baseline_required=True)["outcome"], "PASS")
        for field in ("private_bytes", "working_set_bytes", "heap_bytes"):
            with self.subTest(field=field):
                changed = [row | {"candidate": candidate | {field: candidate[field] + 1}} for row in rounds]
                self.assertEqual(metrics.evaluate_memory(changed, 100, 100, baseline_required=True)["outcome"], "FAIL")

    def test_scaling_each_worker_and_observational_slowdown(self):
        """Verify scaling each worker and observational slowdown."""
        result = metrics.evaluate_scaling([{"1": 12, "2": 8, "4": 4.8, "8": 3}] * 7, 8)
        self.assertEqual(result["outcome"], "PASS")
        self.assertEqual(result["metrics"]["4"]["speedup"], 2.5)
        self.assertEqual(result["metrics"]["4"]["efficiency"], 0.625)
        rounds = [{"1": 12, "2": 8, "4": 4.8, "8": 3, "16": 4}] * 7
        self.assertEqual(metrics.evaluate_scaling(rounds, 16)["outcome"], "FAIL")
        self.assertEqual(metrics.evaluate_scaling(rounds, 8)["outcome"], "INVALID")

    def test_missing_required_scaling_count_is_invalid(self):
        """Verify missing required scaling count is invalid."""
        self.assertEqual(metrics.evaluate_scaling([{"1": 12, "2": 8}] * 7, 4)["outcome"], "INVALID")


class RatioTests(unittest.TestCase):
    """Paired confidence and dispersion gates cannot borrow aggregate results."""

    def test_inclusive_lower_boundary_passes(self):
        """Verify inclusive lower boundary passes."""
        result = metrics.evaluate_ratios([0.8] * 7, 0.8)
        self.assertEqual(result["outcome"], "PASS")
        self.assertEqual(result["ci"], [0.8, 0.8])

    def test_conclusive_upper_failure(self):
        """Verify conclusive upper failure."""
        self.assertEqual(metrics.evaluate_ratios([1.06] * 7, 1.05, "upper")["outcome"], "FAIL")

    def test_straddling_extends_then_becomes_invalid(self):
        """Verify straddling extends then becomes invalid."""
        result = metrics.evaluate_ratios([0.79, 0.79, 0.79, 0.8, 0.81, 0.81, 0.81], 0.8)
        self.assertEqual(result["outcome"], "INVALID")
        self.assertEqual(result["extend_to"], 15)
        result = metrics.evaluate_ratios([0.79] * 7 + [0.8] + [0.81] * 7, 0.8)
        self.assertEqual(result["outcome"], "INVALID")
        self.assertIsNone(result["extend_to"])

    def test_dispersion_requires_extension_even_when_interval_passes(self):
        """Verify dispersion requires extension even when interval passes."""
        result = metrics.evaluate_ratios([1, 1, 1, 1.1, 1.2, 1.2, 1.2], 0.8)
        self.assertAlmostEqual(result["dispersion"], 1 / 11)
        self.assertEqual(result["extend_to"], 15)

    def test_rejects_nonfinite_nonpositive_and_wrong_round_count(self):
        """Verify rejects nonfinite nonpositive and wrong round count."""
        for samples in ([1] * 6, [1] * 8, [float("nan")] * 7, [0] * 7, [True] * 7):
            with self.subTest(samples=samples):
                self.assertEqual(metrics.evaluate_ratios(samples, 0.8)["outcome"], "INVALID")


class RandomMetricTests(unittest.TestCase):
    """Random access gates preserve latency percentiles and throughput units."""

    def test_first_metadata_index_and_tail_independently(self):
        """Verify first metadata index and tail independently."""
        rows = [{"candidate": {"operations_per_second": 10, "p50": 3, "p95": 9, "p99": 15},
                 "metadata_10k_p50": 2}] * 30
        self.assertEqual(metrics.evaluate_random(rows, pairing_verified=True)["outcome"], "PASS")
        failed = [{**row, "metadata_10k_p50": 1.99} for row in rows]
        self.assertEqual(metrics.evaluate_random(failed, pairing_verified=True)["outcome"], "FAIL")
        self.assertEqual(metrics.evaluate_random(rows)["outcome"], "INVALID")

    def test_payload_gate_uses_mib_not_operations(self):
        """Verify payload gate uses mib not operations."""
        rows = [{"candidate": {"operations_per_second": 10000, "logical_mib_per_second": 0.49,
                               "p50": 1, "p95": 3, "p99": 5}, "sequential_mib_per_second": 1}] * 30
        result = metrics.evaluate_random(rows, payload=True, pairing_verified=True)
        self.assertEqual(result["outcome"], "FAIL")
        self.assertEqual(result["gates"]["JBSA-PERF-015-payload"]["median"], 0.49)

    def test_compressed_tail_has_separate_limit(self):
        """Verify compressed tail has separate limit."""
        rows = [{"candidate": {"operations_per_second": 10, "logical_mib_per_second": 0.5,
                               "p50": 1, "p95": 3, "p99": 10}, "sequential_mib_per_second": 1}] * 30
        self.assertEqual(metrics.evaluate_random(rows, payload=True, compressed=True, pairing_verified=True)["outcome"], "PASS")
        self.assertEqual(metrics.evaluate_random(rows, payload=True, pairing_verified=True)["outcome"], "FAIL")

    def test_baseline_each_percentile_and_both_payload_throughputs(self):
        """Verify baseline each percentile and both payload throughputs."""
        candidate = {"operations_per_second": 95, "logical_mib_per_second": 95, "p50": 1.05, "p95": 1.1, "p99": 2.2}
        baseline = {"operations_per_second": 100, "logical_mib_per_second": 100, "p50": 1, "p95": 1, "p99": 2}
        rows = [{"candidate": candidate, "baseline": baseline}] * 30
        self.assertEqual(metrics.evaluate_random(rows, payload=True, baseline_required=True, pairing_verified=True)["outcome"], "PASS")
        for metric, value in (("operations_per_second", 94), ("logical_mib_per_second", 94), ("p50", 1.06), ("p95", 1.11), ("p99", 2.22)):
            with self.subTest(metric=metric):
                changed = [{"candidate": candidate | {metric: value}, "baseline": baseline}] * 30
                self.assertEqual(metrics.evaluate_random(changed, payload=True, baseline_required=True, pairing_verified=True)["outcome"], "FAIL")

    def test_jmh_requires_all_forks_and_measurement_iterations(self):
        """Verify jmh requires all forks and measurement iterations."""
        rows = [{"candidate": {"operations_per_second": 10, "p50": 1, "p95": 2, "p99": 3}, "metadata_10k_p50": 1}] * 7
        self.assertEqual(metrics.evaluate_random(rows, pairing_verified=True)["outcome"], "INVALID")

    def test_index_growth_gate_is_inapplicable_to_small_bulk_corpus(self):
        """Verify index growth gate is inapplicable to small bulk corpus."""
        rows = [{"candidate": {"operations_per_second": 10, "p50": 1, "p95": 2, "p99": 3}}] * 30
        self.assertEqual(metrics.evaluate_random(rows, index_gate=False, pairing_verified=True)["outcome"], "PASS")

    def test_inverted_percentiles_are_invalid_evidence(self):
        """Verify inverted percentiles are invalid evidence."""
        rows = [{"candidate": {"operations_per_second": 10, "p50": 2, "p95": 1, "p99": 3}, "metadata_10k_p50": 2}] * 30
        self.assertEqual(metrics.evaluate_random(rows, pairing_verified=True)["outcome"], "INVALID")


if __name__ == "__main__":
    unittest.main()
