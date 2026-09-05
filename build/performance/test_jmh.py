"""Retained JMH JSON parsing at its public evidence seam."""

import unittest
from pathlib import Path
import tempfile
import zipfile

import jmh


def fixture():
    """Construct the actual JMH 1.37 JSON fork/iteration/histogram structure."""
    shared = {"jmhVersion": "1.37", "benchmark": "example.Random.lookup", "threads": 1, "forks": 3,
              "jvmArgs": ["-Xms4g", "-Xmx4g", "-XX:+AlwaysPreTouch", "-XX:+UseG1GC"],
              "jdkVersion": "25.0.4.1", "vmName": "OpenJDK 64-Bit Server VM", "vmVersion": "25.0.4.1+1-LTS",
              "warmupIterations": 5, "warmupTime": "2 s", "warmupBatchSize": 1,
              "measurementIterations": 10, "measurementTime": "2 s", "measurementBatchSize": 1,
              "params": {"entries": "100000"}, "secondaryMetrics": {}}
    return [shared | {"mode": "thrpt", "primaryMetric": {"scoreUnit": "ops/s", "rawData": [[100.0] * 10 for _ in range(3)]}},
            shared | {"mode": "sample", "secondaryMetrics": {name: {"scoreUnit": "ns/op"} for name in
                ("p0.00", "p0.50", "p0.90", "p0.95", "p0.99", "p0.999", "p0.9999", "p1.00")},
                "primaryMetric": {"scoreUnit": "ns/op", "rawDataHistogram":
                [[[[1, 50], [2, 45], [10, 5]] for _ in range(10)] for _ in range(3)],
                "scorePercentiles": {"50.0": 99999}}}]


class JmhTests(unittest.TestCase):
    """Malformed or aggregated timing evidence cannot qualify."""

    def test_retains_all_forks_and_derives_jmh_weighted_percentiles(self):
        """Verify retains all forks and derives jmh weighted percentiles."""
        rows = jmh.parse_jmh(fixture(), "example.Random.lookup", {"entries": "100000"}, payload_bytes=1_048_576)
        self.assertEqual(len(rows), 30)
        self.assertEqual((rows[0]["fork"], rows[0]["iteration"], rows[0]["operations_per_second"], rows[0]["logical_mib_per_second"]), (0, 0, 100, 100))
        for name, expected in (("p50", 1.5e-9), ("p95", 9.6e-9), ("p99", 1e-8)):
            self.assertAlmostEqual(rows[0][name], expected, delta=1e-20)
        self.assertEqual((rows[-1]["fork"], rows[-1]["iteration"]), (2, 9))

    def test_rejects_missing_iterations_duplicate_modes_and_wrong_parameters(self):
        """Verify rejects missing iterations duplicate modes and wrong parameters."""
        documents = []
        incomplete = fixture()
        incomplete[0]["primaryMetric"]["rawData"][1].pop()
        documents.append(incomplete)
        documents.append(fixture() + [fixture()[0]])
        params = fixture()
        params[0]["params"] = {"entries": "10000"}
        documents.append(params)
        for document in documents:
            with self.subTest(document=document), self.assertRaises(ValueError):
                jmh.parse_jmh(document, "example.Random.lookup", {"entries": "100000"})

    def test_rejects_runtime_protocol_and_unit_mismatch(self):
        """Verify rejects runtime protocol and unit mismatch."""
        for field, value in (("jmhVersion", "1.36"), ("forks", 2), ("warmupTime", "1 s"),
                             ("measurementIterations", 9), ("jvmArgs", ["-Xmx4g"]), ("vmVersion", "25.0.3+1")):
            document = fixture()
            document[0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                jmh.parse_jmh(document, "example.Random.lookup", {"entries": "100000"})
        document = fixture()
        document[0]["primaryMetric"]["scoreUnit"] = "ops/ms"
        with self.assertRaises(ValueError):
            jmh.parse_jmh(document, "example.Random.lookup", {"entries": "100000"})

    def test_histogram_count_and_sample_validation(self):
        """Verify histogram count and sample validation."""
        for histogram in ([[1, 0]], [[1, 0.5]], [[float("nan"), 1]], []):
            document = fixture()
            document[1]["primaryMetric"]["rawDataHistogram"][0][0] = histogram
            with self.subTest(histogram=histogram), self.assertRaises(ValueError):
                jmh.parse_jmh(document, "example.Random.lookup", {"entries": "100000"})

    def test_pairing_retains_sample_coordinates_and_rejects_reordered_companion(self):
        """Verify pairing retains sample coordinates and rejects reordered companion."""
        candidate = jmh.parse_jmh(fixture(), "example.Random.lookup", {"entries": "100000"})
        rows = jmh.pair_random_rounds(candidate, metadata_10k=candidate)
        self.assertAlmostEqual(rows[0]["metadata_10k_p50"], 1.5e-9, delta=1e-20)
        self.assertEqual(rows[-1]["fork"], 2)
        with self.assertRaises(ValueError):
            jmh.pair_random_rounds(candidate, baseline=list(reversed(candidate)))

    def test_command_pins_protocol_and_quotes_each_path_as_one_argument(self):
        """Verify command pins protocol and quotes each path as one argument."""
        command = jmh.build_command("C:/space dir/java.exe", "C:/space dir/bench.jar", "example.Random.payload",
            {"manifestPath": "C:/space dir/manifest.json", "seed": "32012"}, "sample", "C:/space dir/result.json")
        self.assertEqual(command[:3], ["C:/space dir/java.exe", "-jar", "C:/space dir/bench.jar"])
        self.assertIn("manifestPath=C:/space dir/manifest.json", command)
        self.assertEqual(command[command.index("-f") + 1], "3")
        self.assertEqual(command[command.index("-wi") + 1], "5")
        self.assertEqual(command[command.index("-i") + 1], "10")
        self.assertNotIn("-prof", command)
        profiled = jmh.build_command("java", "bench.jar", "example.Random.payload", {}, "thrpt", "profile.json", profile=True)
        self.assertEqual(profiled[-2:], ["-prof", "gc"])

    def test_class_manifest_cannot_change_payload_bytes_or_borrow_another_corpus(self):
        """Verify class manifest cannot change payload bytes or borrow another corpus."""
        full = {"files": [{"path": "a", "length": 16, "sha256": "a" * 64}, {"path": "b", "length": 32, "sha256": "b" * 64}]}
        self.assertEqual(jmh.validate_class_manifest({"files": full["files"][:1]}, full, payload=True), 16)
        with self.assertRaises(ValueError):
            jmh.validate_class_manifest(full, full, payload=True)
        with self.assertRaises(ValueError):
            jmh.validate_class_manifest({"files": [{"path": "a", "length": 32, "sha256": "a" * 64}]}, full, payload=True)

    def test_run_case_refuses_missing_registration_before_process_creation(self):
        """Verify run case refuses missing registration before process creation."""
        with self.assertRaises(ValueError):
            jmh.run_case({}, {}, {}, {}, None)

    def test_allocation_profile_must_retain_all_iteration_evidence(self):
        """Verify allocation profile must retain all iteration evidence."""
        profile = fixture()[0]
        profile["secondaryMetrics"] = {"gc.alloc.rate.norm": {"scoreUnit": "B/op", "rawData": [[0] * 10 for _ in range(3)]}}
        self.assertIsNone(jmh.validate_profile([profile], "example.Random.lookup", {"entries": "100000"}))
        profile["secondaryMetrics"]["gc.alloc.rate.norm"]["rawData"][0].pop()
        with self.assertRaises(ValueError):
            jmh.validate_profile([profile], "example.Random.lookup", {"entries": "100000"})

    def test_profiling_results_cannot_supply_unprofiled_timing(self):
        """Verify profiling results cannot supply unprofiled timing."""
        document = fixture()
        document[0]["secondaryMetrics"] = {"gc.alloc.rate.norm": {"scoreUnit": "B/op"}}
        with self.assertRaises(ValueError):
            jmh.parse_jmh(document, "example.Random.lookup", {"entries": "100000"})

    def test_gzip_corpus_reaches_structural_preflight_before_any_invocation(self):
        """Verify gzip corpus reaches structural preflight before any invocation."""
        import runner
        manifest = runner.ROOT / "tests/performance/corpus/bulk-compressible.json.gz"
        manifest_binding = {"path": str(manifest), "sha256": runner.file_digest(manifest)}
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "archive.bsa"
            archive.write_bytes(b"preflight only")
            registration = {"corpus_manifest": manifest_binding, "jmh": {
                "manifest": manifest_binding,
                "archive": {"path": str(archive), "sha256": runner.file_digest(archive)},
                "oracle_archive_producer_sha256": runner.ORACLE_SHA256}}
            case = {"identity": {"surface": "random-metadata", "workload": "metadata-100k", "codec_provider": "stored-none-v1"}}
            with self.assertRaisesRegex(ValueError, "exactly 100000"):
                jmh.run_case(case, registration, {"baseline_required": False}, {"jmh": {"entry_selection_seed": 32012}}, None)

    def test_standalone_must_contain_exact_conformed_library_classes(self):
        """A changed or missing shaded library class must invalidate the benchmark artifact."""
        import runner
        with tempfile.TemporaryDirectory() as temporary:
            library, standalone = Path(temporary) / "library.jar", Path(temporary) / "standalone.jar"
            with zipfile.ZipFile(library, "w") as archive:
                archive.writestr("example/Archive.class", b"qualified library bytes")
                archive.writestr("module-info.class", b"module descriptor")
            with zipfile.ZipFile(standalone, "w") as archive:
                archive.writestr("example/Archive.class", b"qualified library bytes")
            lib = {"path": str(library), "sha256": runner.file_digest(library)}
            jar = {"path": str(standalone), "sha256": runner.file_digest(standalone)}
            tool = {"jmh_jar": jar, "jmh_library_artifact": lib, "inventory": [lib, jar], "conformance_artifacts": [lib]}
            self.assertEqual(jmh.validate_jmh_provenance(tool)["verified_class_count"], 1)
            with self.assertRaises(ValueError):
                jmh.validate_jmh_provenance(tool | {"inventory": [lib]})
            with self.assertRaises(ValueError):
                jmh.validate_jmh_provenance(tool | {"conformance_artifacts": []})
            with zipfile.ZipFile(standalone, "w") as archive:
                archive.writestr("example/Archive.class", b"different implementation")
            jar["sha256"] = runner.file_digest(standalone)
            with self.assertRaises(ValueError):
                jmh.validate_jmh_provenance(tool)


if __name__ == "__main__":
    unittest.main()
